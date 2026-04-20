package com.apm.io

import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import java.io.InputStream
import java.io.OutputStream

/**
 * IO 监控模块。
 * 监控主线程文件读写操作，检测耗时 IO 和大文件操作。
 *
 * 监控策略：
 * 1. 主线程 IO 耗时检测：记录主线程上所有文件操作的耗时
 * 2. 大 buffer 检测：单次读写超过阈值触发告警
 * 3. 通过代理 InputStream/OutputStream 的方式 hook IO 操作
 *
 * 使用方式（外部调用）：
 * ```kotlin
 * Apm.init(this, ApmConfig()) {
 *     register(IoModule())
 * }
 * // 在业务代码中记录 IO 操作
 * ioModule.onIoOperation(path, opType, durationMs, bytes)
 * ```
 */
class IoModule(
    /** 模块配置。 */
    private val config: IoConfig = IoConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /** 自动 Hook / Native Hook 桥接器。 */
    private var nativeIoHook: NativeIoHook? = null

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableIoMonitor
        if (started) {
            nativeIoHook = NativeIoHook(config).also { it.init() }
        }
        apmContext?.logger?.d("IO module started, mainThreadThreshold=${config.mainThreadIoThresholdMs}ms")
    }

    override fun onStop() {
        started = false
        nativeIoHook?.destroy()
        nativeIoHook = null
    }

    /**
     * 包装 InputStream，接入自动 IO 追踪。
     * 未启动时返回原始流。
     */
    fun wrapInputStream(source: InputStream, path: String): InputStream {
        return nativeIoHook?.wrapInputStream(source, path) ?: source
    }

    /**
     * 包装 OutputStream，接入自动 IO 追踪。
     * 未启动时返回原始流。
     */
    fun wrapOutputStream(source: OutputStream, path: String): OutputStream {
        return nativeIoHook?.wrapOutputStream(source, path) ?: source
    }

    /**
     * 记录自动 Hook 的读取行为。
     * 用于小 buffer、重复读和吞吐量统计。
     */
    fun onRead(path: String, bytesRead: Int, bufferUsed: Int) {
        nativeIoHook?.onRead(path, bytesRead, bufferUsed)
    }

    /**
     * 记录自动 Hook 的关闭行为。
     * 用于主线程 IO、FD 释放和 closeable 泄漏分析。
     */
    fun onClose(source: Any, totalBytes: Long) {
        nativeIoHook?.onClose(source, totalBytes)
    }

    /**
     * 记录一次 buffer 拷贝行为。
     * 零拷贝检测开启时用于识别优化机会。
     */
    fun onBufferCopy(fromPath: String, toPath: String, bytes: Long, bufferCount: Int) {
        nativeIoHook?.onBufferCopy(fromPath, toPath, bytes, bufferCount)
    }

    /**
     * 一次 IO 操作完成时调用。
     * 由外部代理或业务代码在 IO 操作完成后回调。
     *
     * @param path 文件路径
     * @param opType 操作类型（read/write/flush）
     * @param durationMs 操作耗时（毫秒）
     * @param bytes 操作字节数
     */
    fun onIoOperation(
        path: String,
        opType: String,
        durationMs: Long,
        bytes: Long = 0
    ) {
        if (!started) return

        // 判断是否在主线程
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        // 主线程 IO 且超过阈值
        val isSlowOnMainThread = isMainThread && durationMs >= config.mainThreadIoThresholdMs
        // 单次 IO 超过通用阈值
        val isSlowIo = durationMs >= config.singleIoThresholdMs
        // 大 buffer 操作
        val isLargeBuffer = bytes >= config.largeBufferSize

        if (!isSlowOnMainThread && !isSlowIo && !isLargeBuffer) return

        val fields = mutableMapOf<String, Any?>(
            FIELD_PATH to path.take(MAX_PATH_LENGTH),
            FIELD_OP_TYPE to opType,
            FIELD_DURATION_MS to durationMs,
            FIELD_BYTES to bytes,
            FIELD_IS_MAIN_THREAD to isMainThread
        )

        // 抓取堆栈
        if (isSlowOnMainThread || isSlowIo) {
            val stackTrace = captureCurrentThreadStack()
            fields[FIELD_STACK_TRACE] = stackTrace
        }

        val severity = when {
            isSlowOnMainThread -> ApmSeverity.ERROR
            isSlowIo -> ApmSeverity.WARN
            else -> ApmSeverity.INFO
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_IO_ISSUE,
            kind = ApmEventKind.ALERT,
            severity = severity,
            fields = fields
        )
    }

    /** 抓取当前线程堆栈。 */
    private fun captureCurrentThreadStack(): String {
        val trace = Thread.currentThread().stackTrace
        return trace.joinToString(LINE_SEPARATOR).take(config.maxStackTraceLength)
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "io"

        /** IO 告警事件名。 */
        private const val EVENT_IO_ISSUE = "io_issue"

        /** 字段：文件路径。 */
        private const val FIELD_PATH = "path"

        /** 字段：操作类型。 */
        private const val FIELD_OP_TYPE = "opType"

        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"

        /** 字段：字节数。 */
        private const val FIELD_BYTES = "bytes"

        /** 字段：是否主线程。 */
        private const val FIELD_IS_MAIN_THREAD = "isMainThread"

        /** 字段：堆栈。 */
        private const val FIELD_STACK_TRACE = "stackTrace"

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"

        /** 路径最大长度。 */
        private const val MAX_PATH_LENGTH = 256
    }
}
