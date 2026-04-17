package com.apm.anr

/**
 * ANR 模块配置。
 * 支持 Watchdog 检测和 SIGQUIT 信号检测双重模式。
 */
data class AnrConfig(
    /** Watchdog 检查间隔（毫秒）。每隔此时间检查一次主线程是否阻塞。 */
    val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    /** ANR 判定超时（毫秒）。主线程阻塞超过此时间判定为 ANR。 */
    val anrTimeoutMs: Long = DEFAULT_ANR_TIMEOUT_MS,
    /** 是否开启 ANR 监控。 */
    val enableAnrMonitor: Boolean = true,
    /** 主线程堆栈最大截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用 SIGQUIT 信号检测（比 Watchdog 更精准）。需要 JNI 支持。 */
    val enableSigquitDetection: Boolean = true,
    /** 是否尝试读取 /data/anr/traces.txt 文件。 */
    val enableTracesFileReading: Boolean = true,
    /** 是否启用 ANR 原因分类（CPU/IO/LOCK/DEADLOCK）。 */
    val enableAnrClassification: Boolean = true,
    /** ANR 去重窗口（毫秒），同一堆栈在此时间内不重复上报。 */
    val anrDeduplicationWindowMs: Long = DEFAULT_DEDUPLICATION_WINDOW_MS,
    /** ANR 严重告警阈值（毫秒），超过此值上报 SEVERE 级别。 */
    val anrSevereThresholdMs: Long = DEFAULT_ANR_SEVERE_MS,
    /** 主线程堆栈采样次数（用于原因分类分析）。 */
    val stackSampleCount: Int = DEFAULT_STACK_SAMPLE_COUNT,
    /** 堆栈采样间隔（毫秒）。 */
    val stackSampleIntervalMs: Long = DEFAULT_STACK_SAMPLE_INTERVAL_MS
) {
    companion object {
        /** 默认检查间隔：5 秒。 */
        private const val DEFAULT_CHECK_INTERVAL_MS = 5000L
        /** 默认 ANR 超时：5 秒。 */
        private const val DEFAULT_ANR_TIMEOUT_MS = 5000L
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认去重窗口：30 秒。 */
        private const val DEFAULT_DEDUPLICATION_WINDOW_MS = 30_000L
        /** 默认严重告警阈值：10 秒。 */
        private const val DEFAULT_ANR_SEVERE_MS = 10_000L
        /** 默认堆栈采样次数：3 次。 */
        private const val DEFAULT_STACK_SAMPLE_COUNT = 3
        /** 默认堆栈采样间隔：100ms。 */
        private const val DEFAULT_STACK_SAMPLE_INTERVAL_MS = 100L
    }
}
