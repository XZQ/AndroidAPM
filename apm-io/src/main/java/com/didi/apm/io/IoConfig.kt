package com.didi.apm.io

/**
 * IO 监控模块配置。
 * 控制主线程 IO 检测阈值、自动 Hook、多维度检测等。
 */
data class IoConfig(
    /** 是否开启 IO 监控。 */
    val enableIoMonitor: Boolean = true,
    /** 主线程 IO 耗时告警阈值（毫秒）。 */
    val mainThreadIoThresholdMs: Long = DEFAULT_MAIN_THREAD_IO_THRESHOLD_MS,
    /** 单次读写耗时告警阈值（毫秒）。 */
    val singleIoThresholdMs: Long = DEFAULT_SINGLE_IO_THRESHOLD_MS,
    /** 单次读写的最大字节数告警阈值（字节）。 */
    val largeBufferSize: Long = DEFAULT_LARGE_BUFFER_SIZE,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用自动 Hook（代理 InputStream/OutputStream）。 */
    val enableAutoHook: Boolean = true,
    /** 小 buffer 检测阈值（字节），低于此值的读写操作视为小 buffer。 */
    val smallBufferThreshold: Int = DEFAULT_SMALL_BUFFER_THRESHOLD,
    /** 重复读检测阈值：同一文件被读次数超过此值触发告警。 */
    val duplicateReadThreshold: Int = DEFAULT_DUPLICATE_READ_THRESHOLD,
    /** 是否启用 Closeable 泄漏检测。 */
    val enableCloseableLeak: Boolean = true
) {
    companion object {
        /** 默认主线程 IO 阈值：50ms。 */
        private const val DEFAULT_MAIN_THREAD_IO_THRESHOLD_MS = 50L
        /** 默认单次 IO 阈值：500ms。 */
        private const val DEFAULT_SINGLE_IO_THRESHOLD_MS = 500L
        /** 默认大 buffer 阈值：512KB。 */
        private const val DEFAULT_LARGE_BUFFER_SIZE = 512 * 1024L
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认小 buffer 阈值：4KB。 */
        private const val DEFAULT_SMALL_BUFFER_THRESHOLD = 4096
        /** 默认重复读阈值：5 次。 */
        private const val DEFAULT_DUPLICATE_READ_THRESHOLD = 5
    }
}
