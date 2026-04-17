package com.didi.apm.fps

/**
 * 帧率统计数据。
 * 一个统计窗口内的帧率汇总，包含丢帧严重程度分级。
 */
data class FrameStats(
    /** 当前窗口 FPS。 */
    val fps: Int,
    /** 掉帧总数。 */
    val droppedFrames: Int,
    /** 卡顿次数（单帧超 jank 阈值）。 */
    val jankCount: Int,
    /** 严重卡顿（冻结）次数（单帧超 frozen 阈值）。 */
    val frozenCount: Int,
    /** 窗口内总帧数。 */
    val frameCount: Int,
    /** 设备刷新率（Hz），用于计算理论帧时间。 */
    val refreshRate: Float = DEFAULT_REFRESH_RATE,
    /** 丢帧严重程度：Level 0-3，参考 Matrix 分级。 */
    val dropSeverity: Int = DROP_SEVERITY_NONE,
    /** FrameMetrics 各阶段耗时（API 24+），null 表示不可用。 */
    val frameMetricsBreakdown: FrameMetricsBreakdown? = null
) {
    companion object {
        /** 默认刷新率。 */
        const val DEFAULT_REFRESH_RATE = 60f

        /** 丢帧严重程度：无掉帧。 */
        const val DROP_SEVERITY_NONE = 0

        /** 丢帧严重程度：轻微（单次掉 1-3 帧）。 */
        const val DROP_SEVERITY_MINOR = 1

        /** 丢帧严重程度：中等（单次掉 4-9 帧）。 */
        const val DROP_SEVERITY_MODERATE = 2

        /** 丢帧严重程度：严重（单次掉 10+ 帧，用户明显感知）。 */
        const val DROP_SEVERITY_SEVERE = 3
    }
}

/**
 * FrameMetrics 各阶段耗时拆分（API 24+）。
 * 提供比 Choreographer 更细粒度的渲染管线分析。
 */
data class FrameMetricsBreakdown(
    /** Measure + Layout 阶段总耗时（纳秒）。 */
    val measureLayoutNanos: Long = 0L,
    /** Draw 阶段耗时（纳秒）。 */
    val drawNanos: Long = 0L,
    /** Sync 阶段耗时（纳秒）。 */
    val syncNanos: Long = 0L,
    /** Swap Buffers 阶段耗时（纳秒）。 */
    val swapBuffersNanos: Long = 0L,
    /** 延迟帧数（VSync 到开始处理的延迟）。 */
    val delayedFrames: Int = 0
)
