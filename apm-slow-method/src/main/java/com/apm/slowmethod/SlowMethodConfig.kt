package com.apm.slowmethod

/**
 * 慢方法检测模块配置。
 * 控制检测阈值、栈采样参数等。
 */
data class SlowMethodConfig(
    /** 是否开启慢方法检测。 */
    val enableSlowMethod: Boolean = true,
    /** 慢方法判定阈值（毫秒）。主线程单次消息处理超过此值判定为慢方法。 */
    val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    /** 严重慢方法阈值（毫秒）。 */
    val severeThresholdMs: Long = DEFAULT_SEVERE_THRESHOLD_MS,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否在告警中包含主线程堆栈。 */
    val includeStackTrace: Boolean = true,
    /** 是否启用栈采样精确定位（检测到慢方法时触发式采样）。 */
    val enableStackSampling: Boolean = true,
    /** 栈采样间隔（毫秒），默认 10ms。 */
    val samplingIntervalMs: Long = DEFAULT_SAMPLING_INTERVAL_MS,
    /** 栈采样窗口时长（毫秒），默认 5s。 */
    val samplingWindowMs: Long = DEFAULT_SAMPLING_WINDOW_MS,
    /** 热点方法上报数量，默认 Top 10。 */
    val topMethodCount: Int = DEFAULT_TOP_METHOD_COUNT
) {
    companion object {
        /** 默认慢方法阈值：300ms。 */
        private const val DEFAULT_THRESHOLD_MS = 300L
        /** 默认严重慢方法阈值：800ms。 */
        private const val DEFAULT_SEVERE_THRESHOLD_MS = 800L
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认栈采样间隔：10ms。 */
        private const val DEFAULT_SAMPLING_INTERVAL_MS = 10L
        /** 默认栈采样窗口：5s。 */
        private const val DEFAULT_SAMPLING_WINDOW_MS = 5000L
        /** 默认热点方法数量：10。 */
        private const val DEFAULT_TOP_METHOD_COUNT = 10
    }
}
