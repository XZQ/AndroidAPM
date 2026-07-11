package com.apm.render

/**
 * 渲染监控模块配置。
 * 控制过度绘制、View 层级、绘制耗时等监控参数。
 */
data class RenderConfig(
    /** 是否开启渲染监控。 */
    val enableRenderMonitor: Boolean = true,
    /** Compatibility-only per-View draw threshold; public APIs expose frame totals instead. */
    @Deprecated(
        message = "Per-View draw timing is not available through supported APIs; use slowFrameThresholdMs"
    )
    val viewDrawThresholdMs: Long = DEFAULT_VIEW_DRAW_THRESHOLD_MS,
    /** View 层级深度告警阈值。层级超过此值告警。 */
    val viewDepthThreshold: Int = DEFAULT_VIEW_DEPTH_THRESHOLD,
    /** View 数量告警阈值。单个页面 View 总数超过此值告警。 */
    val viewCountThreshold: Int = DEFAULT_VIEW_COUNT_THRESHOLD,
    /** Compatibility-only GPU overdraw switch; public Android APIs expose no overdraw counter. */
    @Deprecated(
        message = "GPU overdraw cannot be measured through supported public APIs",
        replaceWith = ReplaceWith("false")
    )
    val detectOverdraw: Boolean = false,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** FrameMetrics total-duration threshold for a slow frame. */
    val slowFrameThresholdMs: Long = DEFAULT_SLOW_FRAME_THRESHOLD_MS
) {
    companion object {
        /** 默认 View 绘制阈值：16ms（一帧）。 */
        private const val DEFAULT_VIEW_DRAW_THRESHOLD_MS = 16L
        /** 默认 View 层级深度阈值：10 层。 */
        private const val DEFAULT_VIEW_DEPTH_THRESHOLD = 10
        /** 默认 View 数量阈值：300。 */
        private const val DEFAULT_VIEW_COUNT_THRESHOLD = 300
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** Default slow-frame threshold: two 60Hz frame intervals. */
        private const val DEFAULT_SLOW_FRAME_THRESHOLD_MS = 32L
    }
}
