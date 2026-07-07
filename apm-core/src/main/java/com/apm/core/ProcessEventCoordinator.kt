package com.apm.core

import android.content.Context
import android.util.Base64
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 多进程事件协调器。
 *
 * 解决非上传进程（子进程）的事件上报问题：
 * - 非上传进程将事件写入临时文件，再原子发布为可消费 IPC 文件
 * - 上传进程定期扫描并消费已发布的 IPC 文件
 *
 * 文件命名：apm-ipc-{sessionId}-{sequence}.ipc
 * 每个发布文件保存一条 Base64 包裹的可逆事件 payload。
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
    /** 单个 IPC 文件最大行数，保留为二进制兼容参数；当前实现每个发布文件一条事件。 */
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
     * @param maxLinesPerFile 保留兼容参数；当前每个发布文件一条事件
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

    init {
        // 确保显式目录构造器和 Context 构造器都拥有可写目录。
        ipcDir.mkdirs()
    }

    /**
     * 启动协调器。
     * 上传进程启动定期扫描，非上传进程准备写入目录。
     */
    fun start() {
        if (started) return
        started = true

        if (isUploaderProcess) {
            // 上传进程：定期扫描 IPC 目录消费其他进程的事件
            scanExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME_SCAN)
            }
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
        if (isUploaderProcess) return // 上传进程直接走主通道，无需 IPC
        if (!started) return

        writeExecutor.execute {
            try {
                // 先写临时文件再发布，避免上传进程读到半写入内容。
                publishEventFile(event)
            } catch (e: Exception) {
                // IPC 写入失败不影响主流程，但记入自监控避免静默丢失
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, e)
            }
        }
    }

    /**
     * 同步发布 critical 事件到 IPC 文件。
     *
     * @param event 待传输的 critical 事件
     * @return true 表示事件文件已完整发布
     */
    fun writeEventSync(event: ApmEvent): Boolean {
        if (isUploaderProcess) return false
        if (!started) return false
        return runCatching { publishEventFile(event) }.getOrDefault(false)
    }

    /**
     * 将事件写入临时文件并原子发布为可消费文件。
     *
     * @param event 待发布事件
     * @return true 表示发布成功
     */
    private fun publishEventFile(event: ApmEvent): Boolean {
        val line = encodePayload(ApmEventCodec.encode(event))
        val fileStem = nextFileStem()
        val tempFile = File(ipcDir, "$fileStem$IPC_TEMP_EXTENSION")
        val readyFile = File(ipcDir, "$fileStem$IPC_FILE_EXTENSION")
        // 临时文件使用独占名称；写完后才让扫描端看到 .ipc。
        FileWriter(tempFile, false).use { writer ->
            writer.append(line)
            writer.append('\n')
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
        if (!started) return
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
            if (line.isBlank()) continue
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
     *
     * @param payload binary event payload
     * @return Base64 text
     */
    private fun encodePayloadFallback(payload: ByteArray): String {
        val encoded = StringBuilder((payload.size + BASE64_GROUP_PADDING) / BASE64_GROUP_SIZE * BASE64_BLOCK_SIZE)
        var index = 0
        while (index < payload.size) {
            val first = payload[index++].toInt() and BYTE_MASK
            val hasSecond = index < payload.size
            val second = if (hasSecond) payload[index++].toInt() and BYTE_MASK else 0
            val hasThird = index < payload.size
            val third = if (hasThird) payload[index++].toInt() and BYTE_MASK else 0
            encoded.append(BASE64_ALPHABET[first ushr FIRST_SHIFT])
            encoded.append(BASE64_ALPHABET[((first and FIRST_REMAINDER_MASK) shl SECOND_SHIFT) or (second ushr SECOND_RIGHT_SHIFT)])
            encoded.append(if (hasSecond) BASE64_ALPHABET[((second and SECOND_REMAINDER_MASK) shl THIRD_SHIFT) or (third ushr THIRD_RIGHT_SHIFT)] else BASE64_PADDING)
            encoded.append(if (hasThird) BASE64_ALPHABET[third and THIRD_REMAINDER_MASK] else BASE64_PADDING)
        }
        return encoded.toString()
    }

    /**
     * Decodes standard Base64 text without line wrapping.
     *
     * @param line Base64 text
     * @return decoded bytes
     */
    private fun decodePayloadFallback(line: String): ByteArray {
        val output = ByteArrayOutputStream(line.length / BASE64_BLOCK_SIZE * BASE64_GROUP_SIZE)
        var index = 0
        while (index < line.length) {
            val first = decodeBase64Char(line[index++])
            val second = decodeBase64Char(line[index++])
            val thirdChar = line[index++]
            val fourthChar = line[index++]
            val third = if (thirdChar == BASE64_PADDING) 0 else decodeBase64Char(thirdChar)
            val fourth = if (fourthChar == BASE64_PADDING) 0 else decodeBase64Char(fourthChar)
            output.write((first shl FIRST_SHIFT) or (second ushr SECOND_SHIFT))
            if (thirdChar != BASE64_PADDING) {
                output.write(((second and SECOND_REMAINDER_MASK) shl SECOND_RIGHT_SHIFT) or (third ushr THIRD_SHIFT))
            }
            if (fourthChar != BASE64_PADDING) {
                output.write(((third and FIRST_REMAINDER_MASK) shl THIRD_RIGHT_SHIFT) or fourth)
            }
        }
        return output.toByteArray()
    }

    /**
     * Converts one Base64 character to its numeric value.
     *
     * @param char encoded character
     * @return numeric value from 0 to 63
     */
    private fun decodeBase64Char(char: Char): Int {
        val index = BASE64_ALPHABET.indexOf(char)
        require(index >= 0) { "Invalid Base64 character: $char" }
        return index
    }

    /**
     * 停止协调器，释放线程资源。
     */
    fun stop() {
        started = false
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

    companion object {
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
        /** Standard Base64 alphabet. */
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        /** Standard Base64 padding character. */
        private const val BASE64_PADDING = '='
        /** Bit mask for one byte. */
        private const val BYTE_MASK = 0xFF
        /** Number of source bytes in one Base64 group. */
        private const val BASE64_GROUP_SIZE = 3
        /** Padding used to pre-size the encoded string. */
        private const val BASE64_GROUP_PADDING = 2
        /** Number of output characters in one Base64 block. */
        private const val BASE64_BLOCK_SIZE = 4
        /** First Base64 shift. */
        private const val FIRST_SHIFT = 2
        /** Second Base64 left shift. */
        private const val SECOND_SHIFT = 4
        /** Second Base64 right shift. */
        private const val SECOND_RIGHT_SHIFT = 4
        /** Third Base64 left shift. */
        private const val THIRD_SHIFT = 2
        /** Third Base64 right shift. */
        private const val THIRD_RIGHT_SHIFT = 6
        /** Remainder mask for the first source byte. */
        private const val FIRST_REMAINDER_MASK = 0x03
        /** Remainder mask for the second source byte. */
        private const val SECOND_REMAINDER_MASK = 0x0F
        /** Remainder mask for the third source byte. */
        private const val THIRD_REMAINDER_MASK = 0x3F
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
