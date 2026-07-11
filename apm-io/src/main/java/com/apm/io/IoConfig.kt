package com.apm.io

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
    /** Compatibility-only generic Java IO hook; use the explicit stream wrappers. */
    @Deprecated(
        message = "Generic Java IO hooking is not supported; use IoModule.wrapInputStream/wrapOutputStream",
        replaceWith = ReplaceWith("false")
    )
    val enableAutoHook: Boolean = false,
    /** 小 buffer 检测阈值（字节），低于此值的读写操作视为小 buffer。 */
    val smallBufferThreshold: Int = DEFAULT_SMALL_BUFFER_THRESHOLD,
    /** 重复读检测阈值：同一文件被读次数超过此值触发告警。 */
    val duplicateReadThreshold: Int = DEFAULT_DUPLICATE_READ_THRESHOLD,
    /** 是否启用 Closeable 泄漏检测。 */
    val enableCloseableLeak: Boolean = true,
    /** 是否启用文件描述符（FD）泄漏检测。 */
    val enableFdLeakDetection: Boolean = true,
    /** FD 泄漏告警阈值：打开的 FD 数量超过此值触发告警。 */
    val fdLeakThreshold: Int = DEFAULT_FD_LEAK_THRESHOLD,
    /** 是否启用 IO 吞吐量统计。 */
    val enableThroughputStats: Boolean = true,
    /** 吞吐量统计聚合窗口大小（操作次数）。 */
    val throughputWindow: Int = DEFAULT_THROUGHPUT_WINDOW,
    /** 是否启用 Native PLT Hook（需要 JNI 库 libapm-io.so）。 */
    val enableNativePltHook: Boolean = true,
    /** 是否启用零拷贝检测（检测不必要的 buffer 拷贝）。 */
    val enableZeroCopyDetection: Boolean = false
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

        /** 默认 FD 泄漏阈值：500。 */
        private const val DEFAULT_FD_LEAK_THRESHOLD = 500

        /** 默认吞吐量统计窗口：100。 */
        private const val DEFAULT_THROUGHPUT_WINDOW = 100
    }
}

/** Pure throughput reporting gate shared by runtime code and unit tests. */
internal object ThroughputWindowGate {
    /** Returns true at each complete configured operation window. */
    internal fun shouldReport(operationCount: Long, configuredWindow: Int): Boolean {
        val window = configuredWindow.coerceAtLeast(1).toLong()
        return operationCount > 0L && operationCount % window == 0L
    }
}

/** Selects exactly one throughput source when Native and Java instrumentation coexist. */
internal object ThroughputSourcePolicy {
    /** Explicit Java proxy calls always count their logical bytes when enabled. */
    internal fun shouldCountJava(enableThroughput: Boolean): Boolean = enableThroughput

    /** Native callbacks count only calls not already enclosed by a Java proxy. */
    internal fun shouldCountNative(enableThroughput: Boolean, insideJavaProxy: Boolean): Boolean {
        return enableThroughput && !insideJavaProxy
    }
}

/** Bounds duplicate-read reporting to one event per tracked path lifecycle. */
internal object DuplicateReadGate {
    /** Reports only when the threshold is first reached. */
    internal fun shouldReport(readCount: Int, threshold: Int): Boolean {
        return readCount == threshold.coerceAtLeast(1)
    }
}

/** Pure small-buffer gate used with bounded per-path runtime state. */
internal object SmallBufferGate {
    /** Reports a small buffer only before that path has emitted its first finding. */
    internal fun shouldReport(bufferSize: Int, threshold: Int, alreadyReported: Boolean): Boolean {
        return !alreadyReported && bufferSize in 1 until threshold.coerceAtLeast(1)
    }
}
