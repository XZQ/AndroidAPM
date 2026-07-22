package com.apm.core

import android.content.Context
import android.util.Base64
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * IPC artifacts removed during process-local consent revocation.
 *
 * @property clearedFileCount ready or temporary hand-off files successfully removed
 * @property allFilesCleared whether every observed SDK hand-off file was removed
 */
internal data class IpcConsentCleanupResult(
    val clearedFileCount: Int,
    val allFilesCleared: Boolean
)

/**
 * 多进程事件协调器。
 *
 * 解决非上传进程（子进程）的事件上报问题：
 * - 非上传进程将事件写入临时文件，再原子发布为可消费 IPC 文件
 * - 上传进程定期扫描并消费已发布的 IPC 文件
 *
 * 文件命名：apm-ipc-{sessionId}-{sequence}.ipc
 * 每个发布文件保存至多 [maxLinesPerFile] 行 Base64 包裹的可逆事件 payload：
 * 普通事件先进入短暂缓冲，按行数或 [WRITE_FLUSH_DELAY_MS] 定时合批发布，
 * 大幅减少高频子进程事件的文件数与扫描开销；critical 事件立即单文件发布。
 *
 * 线程安全：普通写操作使用单线程执行器串行化，critical 写操作可同步发布。
 */
class ProcessEventCoordinator internal constructor(
    /** 共享 IPC 目录：{cacheDir}/apm-ipc/。 */
    private val ipcDir: File,
    /** 当前进程是否为上传进程（默认主进程负责上传）。 */
    private val isUploaderProcess: Boolean,
    /** IPC 文件扫描间隔（毫秒）。 */
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    /** 单个 IPC 文件最大行数：缓冲达到该行数立即合批发布。 */
    private val maxLinesPerFile: Int = DEFAULT_MAX_LINES_PER_FILE,
    /** IPC 文件最大保留时间（毫秒），过期清理。 */
    private val maxFileAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS
) {
    /**
     * Creates a coordinator rooted at the app cache directory.
     *
     * @param context 应用上下文，用于获取共享文件目录
     * @param isUploaderProcess 当前进程是否为上传进程
     * @param scanIntervalMs IPC 文件扫描间隔（毫秒）
     * @param maxLinesPerFile 单个 IPC 文件最大行数（合批阈值）
     * @param maxFileAgeMs IPC 文件最大保留时间（毫秒）
     */
    constructor(
        context: Context,
        isUploaderProcess: Boolean,
        scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
        maxLinesPerFile: Int = DEFAULT_MAX_LINES_PER_FILE,
        maxFileAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS
    ) : this(
        ipcDir = File(context.cacheDir, IPC_DIR_NAME).apply { mkdirs() },
        isUploaderProcess = isUploaderProcess,
        scanIntervalMs = scanIntervalMs,
        maxLinesPerFile = maxLinesPerFile,
        maxFileAgeMs = maxFileAgeMs
    )

    /** 写操作执行器，保证串行写入。 */
    private val writeExecutor: ScheduledExecutorService =
        ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME_WRITE)

    /** 扫描执行器，仅上传进程使用。 */
    private var scanExecutor: ScheduledExecutorService? = null

    /** 上传进程消费事件时的回调。 */
    var onRemoteEvent: ((ApmEvent) -> Unit)? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /** 待发布事件缓冲（合批用），由 [pendingLock] 保护。 */
    private val pendingEvents = ArrayList<ApmEvent>()

    /** 缓冲访问锁。 */
    private val pendingLock = Any()

    /** 是否已调度延迟 flush（由 [pendingLock] 保护）。 */
    private var flushScheduled = false

    init {
        // 确保显式目录构造器和 Context 构造器都拥有可写目录。
        ipcDir.mkdirs()
    }

    /**
     * 启动协调器。
     * 上传进程启动定期扫描，非上传进程准备写入目录。
     */
    fun start() {
        if (started) {
            return
        }
        started = true

        if (isUploaderProcess) {
            // 上传进程：定期扫描 IPC 目录消费其他进程的事件
            scanExecutor = ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME_SCAN)
            scanExecutor?.scheduleWithFixedDelay(
                { scanAndConsume() },
                scanIntervalMs,
                scanIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * 将事件写入 IPC 文件（非上传进程调用）。
     * 事件先写入临时文件，再发布为上传进程可消费的 IPC 文件。
     *
     * @param event 待传输的事件
     */
    fun writeEvent(event: ApmEvent) {
        if (isUploaderProcess) {
            return // 上传进程直接走主通道，无需 IPC
        }
        if (!started) {
            return
        }

        try {
            writeExecutor.execute {
                try {
                    // 进入合批缓冲：按行数阈值或定时器触发批量发布
                    bufferEvent(event)
                } catch (e: Exception) {
                    // IPC 写入失败不影响主流程，但记入自监控避免静默丢失
                    Apm.recordInternalError(ERROR_TAG_IPC_WRITE, e)
                }
            }
        } catch (error: RejectedExecutionException) {
            // Consent revocation may close the writer between the started check and submission.
            Apm.recordInternalError(ERROR_TAG_IPC_WRITE, error)
        }
    }

    /**
     * 把事件放入合批缓冲。
     * 达到 [maxLinesPerFile] 行立即发布；否则调度一次延迟 flush 兜底。
     *
     * @param event 待发布事件
     */
    private fun bufferEvent(event: ApmEvent) {
        val shouldFlushNow: Boolean
        synchronized(pendingLock) {
            pendingEvents.add(event)
            shouldFlushNow = pendingEvents.size >= maxLinesPerFile
            // 未达到行数阈值时安排定时 flush，保证低频事件的发布延迟有上界
            if (!shouldFlushNow && !flushScheduled) {
                flushScheduled = true
                writeExecutor.schedule(
                    { flushPendingEvents() },
                    WRITE_FLUSH_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
        if (shouldFlushNow) {
            flushPendingEvents()
        }
    }

    /**
     * 把缓冲中的事件合批发布为一个 IPC 文件。
     * 缓冲为空时为 no-op；发布失败记入自监控。
     */
    private fun flushPendingEvents() {
        val batch: List<ApmEvent>
        synchronized(pendingLock) {
            flushScheduled = false
            if (pendingEvents.isEmpty()) {
                return
            }
            batch = ArrayList(pendingEvents)
            pendingEvents.clear()
        }
        try {
            publishBatchFile(batch)
        } catch (e: Exception) {
            // 发布失败丢弃该批（IPC 尽力而为），记入自监控
            Apm.recordInternalError(ERROR_TAG_IPC_WRITE, e)
        }
    }

    /**
     * 立即执行一次合批发布。
     * 供测试和受控调用方使用，不等待定时器。
     */
    internal fun flushPendingNow() {
        flushPendingEvents()
    }

    /**
     * 当前缓冲中待发布的事件数。
     * 供测试观测写线程的消化进度。
     *
     * @return 缓冲事件数
     */
    internal fun pendingBufferSize(): Int = synchronized(pendingLock) { pendingEvents.size }

    /**
     * 同步发布 critical 事件到 IPC 文件。
     *
     * @param event 待传输的 critical 事件
     * @return true 表示事件文件已完整发布
     */
    fun writeEventSync(event: ApmEvent): Boolean {
        if (isUploaderProcess) {
            return false
        }
        synchronized(pendingLock) {
            if (!started) {
                return false
            }
            // Serialize against consent cleanup so every completed file is deleted before return.
            return try {
                publishBatchFile(listOf(event))
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, error)
                false
            }
        }
    }

    /**
     * 将一批事件写入临时文件并原子发布为可消费文件（每事件一行）。
     *
     * @param events 待发布事件批次
     * @return true 表示发布成功
     */
    private fun publishBatchFile(events: List<ApmEvent>): Boolean {
        if (events.isEmpty()) {
            return true
        }
        val fileStem = nextFileStem()
        val tempFile = File(ipcDir, "$fileStem$IPC_TEMP_EXTENSION")
        val readyFile = File(ipcDir, "$fileStem$IPC_FILE_EXTENSION")
        // 临时文件使用独占名称；写完后才让扫描端看到 .ipc。
        FileWriter(tempFile, false).use { writer ->
            for (event in events) {
                writer.append(encodePayload(ApmEventCodec.encode(event)))
                writer.append('\n')
            }
        }
        return if (tempFile.renameTo(readyFile)) {
            true
        } else {
            // 某些文件系统 rename 失败时退化为复制后删除，仍保持扫描端只看 .ipc。
            tempFile.copyTo(readyFile, overwrite = true)
            tempFile.delete()
            true
        }
    }

    /**
     * 生成单调递增的事件文件名。
     *
     * @return 不含扩展名的文件名
     */
    private fun nextFileStem(): String {
        val sessionId = ProcessSessionId.get().replace(NON_ALPHA_REGEX, REPLACEMENT_UNDERSCORE)
        val sequence = FILE_SEQUENCE.incrementAndGet()
        return "$IPC_FILE_PREFIX${sessionId}_$sequence"
    }

    /**
     * 扫描并消费 IPC 目录中的事件文件。
     * 仅上传进程调用。读取后删除已消费的文件。
     */
    private fun scanAndConsume() {
        if (!started) {
            return
        }
        try {
            val files = ipcDir.listFiles { file ->
                file.name.endsWith(IPC_FILE_EXTENSION)
            } ?: return

            val now = System.currentTimeMillis()
            cleanupExpiredTempFiles(now)
            for (file in files) {
                try {
                    val fileAgeMs = now - file.lastModified()
                    // 清理过期文件
                    if (fileAgeMs > maxFileAgeMs) {
                        file.delete()
                        continue
                    }
                    // 读取并消费每行事件
                    consumeFile(file)
                    // 消费完成后删除文件
                    file.delete()
                } catch (e: Exception) {
                    // 单文件消费失败不影响后续文件，但记入自监控
                    Apm.recordInternalError(ERROR_TAG_IPC_CONSUME_FILE, e)
                }
            }
        } catch (e: Exception) {
            // 扫描失败不中断调度，但记入自监控
            Apm.recordInternalError(ERROR_TAG_IPC_SCAN, e)
        }
    }

    /**
     * Runs one immediate scan cycle.
     * Used by tests and controlled callers that do not want to wait for the scheduled delay.
     */
    internal fun scanAndConsumeNow() {
        scanAndConsume()
    }

    /**
     * 清理异常退出遗留的临时文件。
     *
     * @param now 当前时间戳
     */
    private fun cleanupExpiredTempFiles(now: Long) {
        val tempFiles = ipcDir.listFiles { file ->
            file.name.endsWith(IPC_TEMP_EXTENSION)
        } ?: return
        for (file in tempFiles) {
            val fileAgeMs = now - file.lastModified()
            // 仅清理明显过期的临时文件，避免误删正在写入的事件。
            if (fileAgeMs > maxFileAgeMs) {
                file.delete()
            }
        }
    }

    /**
     * 读取单个 IPC 文件并回调每行事件。
     * 每行保存一个 Base64 包裹的可逆 ApmEvent payload。
     *
     * @param file IPC 文件
     */
    private fun consumeFile(file: File) {
        val lines = file.readLines()
        for (line in lines) {
            if (line.isBlank()) {
                continue
            }
            try {
                // Decode the complete event so no fields are lost across processes.
                val event = parseLineProtocol(line)
                event?.let { onRemoteEvent?.invoke(it) }
            } catch (e: Exception) {
                // 单行解析失败跳过该行，但记入自监控（可能是编码不兼容）
                Apm.recordInternalError(ERROR_TAG_IPC_PARSE_LINE, e)
            }
        }
    }

    /**
     * Decodes one Base64-wrapped durable event payload.
     *
     * @param line line protocol 格式的行
     * @return 解析后的 ApmEvent，解析失败返回 null
     */
    private fun parseLineProtocol(line: String): ApmEvent? {
        val payload = decodePayload(line)
        val event = ApmEventCodec.decode(payload)
        return event.copy(extras = event.extras + ("ipc_source" to "remote_process"))
    }

    /**
     * Encodes payload for one IPC line.
     *
     * @param payload binary event payload
     * @return Base64 text without line wraps
     */
    private fun encodePayload(payload: ByteArray): String {
        return try {
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (_: RuntimeException) {
            // Local JVM unit tests use Android stubs, so fall back to a small local codec there.
            encodePayloadFallback(payload)
        }
    }

    /**
     * Decodes payload from one IPC line.
     *
     * @param line Base64 text without line wraps
     * @return binary event payload
     */
    private fun decodePayload(line: String): ByteArray {
        return try {
            Base64.decode(line, Base64.NO_WRAP)
        } catch (_: RuntimeException) {
            // Local JVM unit tests use Android stubs, so fall back to a small local codec there.
            decodePayloadFallback(line)
        }
    }

    /**
     * Encodes bytes with standard Base64 without line wrapping.
     * 仅 JVM 单元测试路径可达（设备上 android.util.Base64 恒可用且优先命中，
     * 本方法只在其抛 RuntimeException 的 Android 桩环境执行），
     * 因此 java.util.Base64 的 API 26 要求不影响 minSdk 24 设备。
     *
     * @param payload binary event payload
     * @return Base64 text
     */
    @android.annotation.SuppressLint("NewApi")
    private fun encodePayloadFallback(payload: ByteArray): String {
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Decodes standard Base64 text without line wrapping.
     * 仅 JVM 单元测试路径可达（同 [encodePayloadFallback] 的可达性说明）。
     *
     * @param line Base64 text
     * @return decoded bytes
     */
    @android.annotation.SuppressLint("NewApi")
    private fun decodePayloadFallback(line: String): ByteArray {
        return java.util.Base64.getDecoder().decode(line)
    }

    /**
     * 停止协调器，释放线程资源。
     */
    fun stop() {
        started = false
        // 关闭前提交最终 flush，避免缓冲中的事件丢失
        runCatching { writeExecutor.execute { flushPendingEvents() } }
        writeExecutor.shutdown()
        scanExecutor?.shutdown()
        try {
            writeExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            scanExecutor?.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            writeExecutor.shutdownNow()
            scanExecutor?.shutdownNow()
        }
        scanExecutor = null
    }

    /**
     * Stops IPC without flushing buffered telemetry and deletes process-shared hand-off artifacts.
     *
     * @return deletion count and completeness for the local IPC directory
     */
    internal fun stopAndClearForConsentRevocation(): IpcConsentCleanupResult {
        started = false
        synchronized(pendingLock) {
            pendingEvents.clear()
            flushScheduled = false
        }
        // Cancel queued writes before waiting for any in-flight file operation to finish.
        writeExecutor.shutdownNow()
        scanExecutor?.shutdownNow()
        var writersStopped = false
        var scannerStopped = scanExecutor == null
        try {
            writersStopped = writeExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            scannerStopped = scanExecutor?.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        scanExecutor = null
        synchronized(pendingLock) {
            // An in-flight buffer callback may have raced the first clear before observing shutdown.
            pendingEvents.clear()
            flushScheduled = false
        }
        val cleanup = clearIpcFilesForConsentRevocation()
        return cleanup.copy(allFilesCleared = cleanup.allFilesCleared && writersStopped && scannerStopped)
    }

    /** Deletes every ready or temporary SDK hand-off file after IPC executors have stopped. */
    private fun clearIpcFilesForConsentRevocation(): IpcConsentCleanupResult {
        return clearIpcFilesForConsentRevocation(ipcDir)
    }

    companion object {
        /** Clears dormant ready/temp IPC files when no coordinator runtime is available. */
        internal fun clearConsentArtifacts(context: Context): IpcConsentCleanupResult {
            return clearIpcFilesForConsentRevocation(File(context.cacheDir, IPC_DIR_NAME))
        }

        /** Performs best-effort deletion after active writers have stopped or before SDK init. */
        private fun clearIpcFilesForConsentRevocation(ipcDirectory: File): IpcConsentCleanupResult {
            val files = ipcDirectory.listFiles { file ->
                file.name.startsWith(IPC_FILE_PREFIX) &&
                    (file.name.endsWith(IPC_FILE_EXTENSION) || file.name.endsWith(IPC_TEMP_EXTENSION))
            }.orEmpty()
            var cleared = 0
            var complete = true
            for (file in files) {
                if (file.delete() || !file.exists()) {
                    cleared += 1
                } else {
                    complete = false
                }
            }
            return IpcConsentCleanupResult(clearedFileCount = cleared, allFilesCleared = complete)
        }

        /** 自监控 tag：IPC 写入失败。 */
        private const val ERROR_TAG_IPC_WRITE = "ipc_write"

        /** 自监控 tag：单个 IPC 文件消费失败。 */
        private const val ERROR_TAG_IPC_CONSUME_FILE = "ipc_consume_file"

        /** 自监控 tag：IPC 目录扫描失败。 */
        private const val ERROR_TAG_IPC_SCAN = "ipc_scan"

        /** 自监控 tag：IPC 行解析失败。 */
        private const val ERROR_TAG_IPC_PARSE_LINE = "ipc_parse_line"

        /** IPC 目录名。 */
        private const val IPC_DIR_NAME = "apm-ipc"
        /** IPC 文件前缀。 */
        private const val IPC_FILE_PREFIX = "apm-ipc-"
        /** IPC 文件扩展名。 */
        private const val IPC_FILE_EXTENSION = ".ipc"
        /** 临时文件扩展名。 */
        private const val IPC_TEMP_EXTENSION = ".tmp"
        /** 默认扫描间隔：5 秒。 */
        private const val DEFAULT_SCAN_INTERVAL_MS = 5000L
        /** 合批缓冲的定时 flush 延迟（毫秒）。 */
        private const val WRITE_FLUSH_DELAY_MS = 500L
        /** 单文件最大行数：100 条。 */
        private const val DEFAULT_MAX_LINES_PER_FILE = 100
        /** 文件最大保留时间：5 分钟。 */
        private const val DEFAULT_MAX_FILE_AGE_MS = 300_000L
        /** 写线程名。 */
        private const val THREAD_NAME_WRITE = "apm-ipc-write"
        /** 扫描线程名。 */
        private const val THREAD_NAME_SCAN = "apm-ipc-scan"
        /** 非字母数字正则。 */
        private const val NON_ALPHA_REGEX = "[^a-zA-Z0-9_.-]"
        /** 替换字符。 */
        private const val REPLACEMENT_UNDERSCORE = "_"
        /** Maximum time allowed for pending IPC writes. */
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
        /** Global file sequence used to avoid filename collisions within one process. */
        private val FILE_SEQUENCE = AtomicLong()
    }
}
