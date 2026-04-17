package com.didi.apm.launch

/**
 * 启动监控模块配置。
 * 支持冷/热/温启动追踪，以及分阶段耗时分析。
 */
data class LaunchConfig(
    /** 是否监控冷启动。 */
    val enableColdStart: Boolean = true,
    /** 是否监控热启动。 */
    val enableHotStart: Boolean = true,
    /** 是否监控温启动。 */
    val enableWarmStart: Boolean = true,
    /** 启动超时阈值（毫秒），超出不认为是启动。 */
    val launchTimeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS,
    /** 温启动判定：从 onStop 到 restart 不超过此时间为热启动。 */
    val warmStartThresholdMs: Long = DEFAULT_WARM_START_THRESHOLD_MS,
    /** 是否启用分阶段追踪（ContentProvider、Application、首帧渲染等）。 */
    val enablePhaseTracking: Boolean = true,
    /** 是否启用首帧渲染检测（通过 Window.OnFrameRenderedListener）。 */
    val enableFirstFrameTracking: Boolean = true,
    /** 冷启动告警阈值（毫秒），超过此值上报 WARN 级别。 */
    val coldStartWarnThresholdMs: Long = DEFAULT_COLD_START_WARN_MS,
    /** 冷启动严重告警阈值（毫秒），超过此值上报 ERROR 级别。 */
    val coldStartSevereThresholdMs: Long = DEFAULT_COLD_START_SEVERE_MS,
    /** 是否记录 ContentProvider 初始化耗时。 */
    val enableContentProviderTracking: Boolean = true
) {
    companion object {
        /** 默认启动超时：30 秒。 */
        private const val DEFAULT_LAUNCH_TIMEOUT_MS = 30_000L
        /** 默认温启动阈值：5 秒。热启动超过此时间为温启动。 */
        private const val DEFAULT_WARM_START_THRESHOLD_MS = 5_000L
        /** 默认冷启动告警阈值：2 秒。 */
        private const val DEFAULT_COLD_START_WARN_MS = 2_000L
        /** 默认冷启动严重告警阈值：5 秒。 */
        private const val DEFAULT_COLD_START_SEVERE_MS = 5_000L
    }
}
