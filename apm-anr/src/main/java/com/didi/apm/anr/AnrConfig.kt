package com.didi.apm.anr

/**
 * ANR 模块配置。
 */
data class AnrConfig(
    /** Watchdog 检查间隔（毫秒）。每隔此时间检查一次主线程是否阻塞。 */
    val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    /** ANR 判定超时（毫秒）。主线程阻塞超过此时间判定为 ANR。 */
    val anrTimeoutMs: Long = DEFAULT_ANR_TIMEOUT_MS,
    /** 是否开启 ANR 监控。 */
    val enableAnrMonitor: Boolean = true,
    /** 主线程堆栈最大截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH
) {
    companion object {
        /** 默认检查间隔：5 秒。 */
        private const val DEFAULT_CHECK_INTERVAL_MS = 5000L
        /** 默认 ANR 超时：5 秒。 */
        private const val DEFAULT_ANR_TIMEOUT_MS = 5000L
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
    }
}
