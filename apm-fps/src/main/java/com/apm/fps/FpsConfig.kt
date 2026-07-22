package com.apm.fps

/**
 * FPS 监控模块配置。
 * 控制帧率采集频率、卡顿阈值、丢帧严重程度分级、FrameMetrics 采集等。
 */
data class FpsConfig(
    /** 是否开启 FPS 监控。 */
    val enableFpsMonitor: Boolean = true,
    /** 卡顿判定阈值（毫秒）。单帧耗时超过此值判定为卡顿。默认 16ms（60fps 一帧）。 */
    val jankThresholdMs: Long = DEFAULT_JANK_THRESHOLD_MS,
    /** 严重卡顿阈值（毫秒）。超过此值判定为严重卡顿（冻结）。 */
    val frozenThresholdMs: Long = DEFAULT_FROZEN_THRESHOLD_MS,
    /** 兼容字段；FPS 上报节奏由 [reportIntervalMs] 的单调时间窗口决定。 */
    @Deprecated(message = "FPS reports use reportIntervalMs; retained for source compatibility")
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    /** Wall-clock duration of one FPS reporting window. Values below 1 ms are clamped at runtime. */
    val reportIntervalMs: Long = DEFAULT_REPORT_INTERVAL_MS,
    /** FPS 低于此值触发告警。 */
    val fpsWarnThreshold: Int = DEFAULT_FPS_WARN_THRESHOLD,
    /** 是否检测场景信息（当前 Activity 名）。 */
    val enableSceneDetect: Boolean = true,
    /** Compatibility-only field; frame callbacks do not capture stack traces. */
    @Deprecated(message = "FPS monitoring does not capture stack traces")
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用 FrameMetrics（API 24+）采集渲染管线各阶段耗时。 */
    val enableFrameMetrics: Boolean = true,
    /** 是否启用丢帧严重程度分级（参考 Matrix）。 */
    val enableDropSeverity: Boolean = true,
    /** 丢帧严重程度阈值：单次掉帧数达到此值判定为 SEVERE。 */
    val dropSeveritySevereThreshold: Int = DEFAULT_DROP_SEVERE_THRESHOLD,
    /** 丢帧严重程度阈值：单次掉帧数达到此值判定为 MODERATE。 */
    val dropSeverityModerateThreshold: Int = DEFAULT_DROP_MODERATE_THRESHOLD
) {
    companion object {
        /** 默认卡顿阈值：16ms（60fps）。 */
        private const val DEFAULT_JANK_THRESHOLD_MS = 16L

        /** 默认冻结阈值：300ms。 */
        private const val DEFAULT_FROZEN_THRESHOLD_MS = 300L

        /** 默认统计窗口：60 帧（约 1 秒）。 */
        private const val DEFAULT_WINDOW_SIZE = 60

        /** Default wall-clock reporting window: one second. */
        private const val DEFAULT_REPORT_INTERVAL_MS = 1_000L

        /** FPS 告警阈值：30 fps。 */
        private const val DEFAULT_FPS_WARN_THRESHOLD = 30

        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000

        /** 丢帧 SEVERE 阈值：单次掉 10 帧。 */
        private const val DEFAULT_DROP_SEVERE_THRESHOLD = 10

        /** 丢帧 MODERATE 阈值：单次掉 4 帧。 */
        private const val DEFAULT_DROP_MODERATE_THRESHOLD = 4
    }
}
