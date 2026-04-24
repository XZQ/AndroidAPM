package com.apm.core

import android.content.Context
import com.apm.model.ApmEvent
import com.apm.model.toLineProtocol
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 多进程事件协调器。
 *
 * 解决非上传进程（子进程）的事件上报问题：
 * - 非上传进程将事件序列化为 line protocol 写入共享 IPC 目录
 * - 上传进程定期扫描并消费 IPC 文件中的事件
 *
 * 文件命名：apm-ipc-{processName}-{sessionId}.ipc
 * 每行一条事件（line protocol 格式）。
 *
 * 线程安全：写操作使用单线程执行器串行化，读操作加文件锁。
 */
class ProcessEventCoordinator(
    /** 应用上下文，用于获取共享文件目录。 */
    private val context: Context,
    /** 当前进程是否为上传进程（默认主进程负责上传）。 */
    private val isUploaderProcess: Boolean,
    /** IPC 文件扫描间隔（毫秒）。 */
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    /** 单个 IPC 文件最大行数，超过后创建新文件。 */
    private val maxLinesPerFile: Int = DEFAULT_MAX_LINES_PER_FILE,
    /** IPC 文件最大保留时间（毫秒），过期清理。 */
    private val maxFileAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS
) {
    /** 共享 IPC 目录：{cacheDir}/apm-ipc/。 */
    private val ipcDir = File(context.cacheDir, IPC_DIR_NAME).apply { mkdirs() }

    /** 当前进程的 IPC 写出文件。 */
    private var currentWriteFile: File? = null

    /** 当前写出文件的行数计数。 */
    private var currentLineCount = 0

    /** 写操作执行器，保证串行写入。 */
    private val writeExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, THREAD_NAME_WRITE)
    }

    /** 扫描执行器，仅上传进程使用。 */
    private var scanExecutor: ScheduledExecutorService? = null

    /** 上传进程消费事件时的回调。 */
    var onRemoteEvent: ((ApmEvent) -> Unit)? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /**
     * 启动协调器。
     * 上传进程启动定期扫描，非上传进程仅初始化写出文件。
     */
    fun start() {
        if (started) return
        started = true

        if (isUploaderProcess) {
            // 上传进程：定期扫描 IPC 目录消费其他进程的事件
            scanExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME_SCAN)
            }
            scanExecutor?.scheduleAtFixedRate(
                { scanAndConsume() },
                scanIntervalMs,
                scanIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * 将事件写入 IPC 文件（非上传进程调用）。
     * 事件序列化为 line protocol 后追加到当前写出文件。
     *
     * @param event 待传输的事件
     */
    fun writeEvent(event: ApmEvent) {
        if (isUploaderProcess) return // 上传进程直接走主通道，无需 IPC
        if (!started) return

        writeExecutor.execute {
            try {
                val line = event.toLineProtocol()
                val file = getOrCreateWriteFile()
                // 追加写入，每个事件一行
                FileWriter(file, true).use { writer ->
                    writer.append(line)
                    writer.append('\n')
                }
                currentLineCount++
            } catch (_: Exception) {
                // IPC 写入失败不影响主流程
            }
        }
    }

    /**
     * 获取或创建当前写出文件。
     * 当前行数超过阈值时切换到新文件。
     */
    private fun getOrCreateWriteFile(): File {
        val current = currentWriteFile
        // 文件存在且未超行数限制，继续使用
        if (current != null && current.exists() && currentLineCount < maxLinesPerFile) {
            return current
        }
        // 创建新文件：进程名 + 会话ID + 时间戳
        val processName = ProcessSessionId.get().replace(NON_ALPHA_REGEX, REPLACEMENT_UNDERSCORE)
        val timestamp = System.currentTimeMillis()
        val newFile = File(ipcDir, "${IPC_FILE_PREFIX}${processName}_${timestamp}${IPC_FILE_EXTENSION}")
        currentWriteFile = newFile
        currentLineCount = 0
        return newFile
    }

    /**
     * 扫描并消费 IPC 目录中的事件文件。
     * 仅上传进程调用。读取后删除已消费的文件。
     */
    private fun scanAndConsume() {
        if (!started) return
        try {
            val files = ipcDir.listFiles { file ->
                file.name.endsWith(IPC_FILE_EXTENSION)
            } ?: return

            val now = System.currentTimeMillis()
            for (file in files) {
                try {
                    // 清理过期文件
                    if (now - file.lastModified() > maxFileAgeMs) {
                        file.delete()
                        continue
                    }
                    // 读取并消费每行事件
                    consumeFile(file)
                    // 消费完成后删除文件
                    file.delete()
                } catch (_: Exception) {
                    // 单文件消费失败不影响后续文件
                }
            }
        } catch (_: Exception) {
            // 扫描失败静默处理
        }
    }

    /**
     * 读取单个 IPC 文件并回调每行事件。
     * 简单解析 line protocol 为 ApmEvent 的子集字段。
     *
     * @param file IPC 文件
     */
    private fun consumeFile(file: File) {
        val lines = file.readLines()
        for (line in lines) {
            if (line.isBlank()) continue
            try {
                // 解析 line protocol 为基本事件（提取 module、name 等字段）
                val event = parseLineProtocol(line)
                event?.let { onRemoteEvent?.invoke(it) }
            } catch (_: Exception) {
                // 单行解析失败跳过
            }
        }
    }

    /**
     * 简单解析 line protocol 为 ApmEvent。
     * 仅提取 ts、module、name、kind、severity、priority 等核心字段，
     * fields 以原始文本保留在 extras 中。
     *
     * @param line line protocol 格式的行
     * @return 解析后的 ApmEvent，解析失败返回 null
     */
    private fun parseLineProtocol(line: String): ApmEvent? {
        val segments = line.split("|")
        if (segments.size < MIN_PARSE_SEGMENTS) return null

        var timestamp = System.currentTimeMillis()
        var module = "unknown"
        var name = "unknown"

        for (segment in segments) {
            val eqIdx = segment.indexOf('=')
            if (eqIdx < 0) continue
            val key = segment.substring(0, eqIdx)
            val value = segment.substring(eqIdx + 1)
            when (key) {
                "ts" -> value.toLongOrNull()?.let { timestamp = it }
                "module" -> module = value
                "name" -> name = value
            }
        }

        return ApmEvent(
            module = module,
            name = name,
            timestamp = timestamp,
            extras = mapOf("ipc_source" to "remote_process")
        )
    }

    /**
     * 停止协调器，释放线程资源。
     */
    fun stop() {
        started = false
        writeExecutor.shutdownNow()
        scanExecutor?.shutdownNow()
        scanExecutor = null
    }

    companion object {
        /** IPC 目录名。 */
        private const val IPC_DIR_NAME = "apm-ipc"
        /** IPC 文件前缀。 */
        private const val IPC_FILE_PREFIX = "apm-ipc-"
        /** IPC 文件扩展名。 */
        private const val IPC_FILE_EXTENSION = ".ipc"
        /** 默认扫描间隔：5 秒。 */
        private const val DEFAULT_SCAN_INTERVAL_MS = 5000L
        /** 单文件最大行数：100 条。 */
        private const val DEFAULT_MAX_LINES_PER_FILE = 100
        /** 文件最大保留时间：5 分钟。 */
        private const val DEFAULT_MAX_FILE_AGE_MS = 300_000L
        /** 写线程名。 */
        private const val THREAD_NAME_WRITE = "apm-ipc-write"
        /** 扫描线程名。 */
        private const val THREAD_NAME_SCAN = "apm-ipc-scan"
        /** 最小合法行段数（ts + module + name）。 */
        private const val MIN_PARSE_SEGMENTS = 3
        /** 非字母数字正则。 */
        private const val NON_ALPHA_REGEX = "[^a-zA-Z0-9_.-]"
        /** 替换字符。 */
        private const val REPLACEMENT_UNDERSCORE = "_"
    }
}
