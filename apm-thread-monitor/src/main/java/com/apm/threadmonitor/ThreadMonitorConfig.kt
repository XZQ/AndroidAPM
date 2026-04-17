package com.apm.threadmonitor

/**
 * 线程监控模块配置。
 * 包含线程数量监控、线程泄漏检测、线程池监控配置。
 */
data class ThreadMonitorConfig(
    /** 是否开启线程监控。 */
    val enableThreadMonitor: Boolean = true,
    /** 线程数量告警阈值。 */
    val threadCountThreshold: Int = DEFAULT_THREAD_COUNT_THRESHOLD,
    /** 同名线程检测阈值。 */
    val duplicateThreadThreshold: Int = DEFAULT_DUPLICATE_THREAD_THRESHOLD,
    /** 检测间隔（毫秒）。 */
    val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用线程池监控。 */
    val enableThreadPoolMonitor: Boolean = true,
    /** 是否启用线程泄漏检测。 */
    val enableThreadLeakDetect: Boolean = true,
    /** 线程池队列积压告警阈值。 */
    val queueBacklogThreshold: Int = DEFAULT_QUEUE_BACKLOG_THRESHOLD,
    /** 线程疑似泄漏阈值（毫秒），线程存活超过此时间且无活动视为泄漏。 */
    val threadLeakThresholdMs: Long = DEFAULT_THREAD_LEAK_THRESHOLD_MS
) {
    companion object {
        /** 默认线程数量阈值：100。 */
        private const val DEFAULT_THREAD_COUNT_THRESHOLD = 100

        /** 默认同名线程阈值：5。 */
        private const val DEFAULT_DUPLICATE_THREAD_THRESHOLD = 5

        /** 默认检测间隔：30 秒。 */
        private const val DEFAULT_CHECK_INTERVAL_MS = 30_000L

        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000

        /** 默认队列积压阈值：100。 */
        private const val DEFAULT_QUEUE_BACKLOG_THRESHOLD = 100

        /** 默认线程泄漏阈值：5 分钟。 */
        private const val DEFAULT_THREAD_LEAK_THRESHOLD_MS = 300_000L
    }
}
