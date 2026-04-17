package com.didi.apm.render

/**
 * 渲染监控模块配置。
 * 控制过度绘制、View 层级、绘制耗时等监控参数。
 */
data class RenderConfig(
    /** 是否开启渲染监控。 */
    val enableRenderMonitor: Boolean = true,
    /** View 绘制耗时告警阈值（毫秒）。单个 View.draw 超过此值告警。 */
    val viewDrawThresholdMs: Long = DEFAULT_VIEW_DRAW_THRESHOLD_MS,
    /** View 层级深度告警阈值。层级超过此值告警。 */
    val viewDepthThreshold: Int = DEFAULT_VIEW_DEPTH_THRESHOLD,
    /** View 数量告警阈值。单个页面 View 总数超过此值告警。 */
    val viewCountThreshold: Int = DEFAULT_VIEW_COUNT_THRESHOLD,
    /** 是否检测过度绘制（需要开发者选项配合）。 */
    val detectOverdraw: Boolean = true,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH
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
    }
}
