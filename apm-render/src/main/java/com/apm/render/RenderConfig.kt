package com.apm.render

/**
 * 渲染监控模块配置。
 * 控制 View 层级深度、View 数量及 FrameMetrics 慢帧阈值等监控参数。
 */
data class RenderConfig(
    /** 是否开启渲染监控。 */
    val enableRenderMonitor: Boolean = true,
    /** View 层级深度告警阈值。层级超过此值告警。 */
    val viewDepthThreshold: Int = DEFAULT_VIEW_DEPTH_THRESHOLD,
    /** View 数量告警阈值。单个页面 View 总数超过此值告警。 */
    val viewCountThreshold: Int = DEFAULT_VIEW_COUNT_THRESHOLD,
    /** FrameMetrics total-duration threshold for a slow frame. */
    val slowFrameThresholdMs: Long = DEFAULT_SLOW_FRAME_THRESHOLD_MS
) {
    companion object {
        /** 默认 View 层级深度阈值：10 层。 */
        private const val DEFAULT_VIEW_DEPTH_THRESHOLD = 10
        /** 默认 View 数量阈值：300。 */
        private const val DEFAULT_VIEW_COUNT_THRESHOLD = 300
        /** Default slow-frame threshold: two 60Hz frame intervals. */
        private const val DEFAULT_SLOW_FRAME_THRESHOLD_MS = 32L
    }
}
