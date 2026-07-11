package com.apm.render

/** One completed frame-metrics aggregation window. */
internal data class FrameMetricsSnapshot(
    /** Frames observed in the window. */
    val frameCount: Int,
    /** Frames crossing the configured duration threshold. */
    val slowFrameCount: Int,
    /** Sum of total frame durations. */
    val totalDurationNanos: Long,
    /** Slowest total frame duration. */
    val maxDurationNanos: Long,
    /** Platform-reported listener callback drops. */
    val droppedFrames: Int
)

/** Fixed-size, thread-safe accumulator for Android FrameMetrics callbacks. */
internal class FrameMetricsAccumulator(
    /** Frames contained in one emitted snapshot. */
    private val windowSize: Int,
    /** Total-duration threshold used to classify slow frames. */
    private val slowFrameThresholdNanos: Long
) {
    /** Current window frame count. */
    private var frameCount = 0

    /** Current window slow-frame count. */
    private var slowFrameCount = 0

    /** Current window duration sum. */
    private var totalDurationNanos = 0L

    /** Current window maximum duration. */
    private var maxDurationNanos = 0L

    /** Current window platform callback drops. */
    private var droppedFrames = 0

    init {
        require(windowSize > 0) { "windowSize must be positive" }
        require(slowFrameThresholdNanos > 0L) { "slowFrameThresholdNanos must be positive" }
    }

    /** Records one frame and returns a snapshot only when the window is full. */
    @Synchronized
    fun record(totalDurationNanos: Long, droppedFrames: Int): FrameMetricsSnapshot? {
        val safeDuration = totalDurationNanos.coerceAtLeast(0L)
        frameCount += 1
        this.totalDurationNanos = safeAdd(this.totalDurationNanos, safeDuration)
        maxDurationNanos = maxOf(maxDurationNanos, safeDuration)
        if (safeDuration >= slowFrameThresholdNanos) {
            slowFrameCount += 1
        }
        this.droppedFrames = safeAddInt(this.droppedFrames, droppedFrames.coerceAtLeast(0))
        if (frameCount < windowSize) {
            return null
        }
        val snapshot = FrameMetricsSnapshot(
            frameCount,
            slowFrameCount,
            this.totalDurationNanos,
            maxDurationNanos,
            this.droppedFrames
        )
        resetLocked()
        return snapshot
    }

    /** Clears counters while the caller owns the accumulator lock. */
    private fun resetLocked() {
        frameCount = 0
        slowFrameCount = 0
        totalDurationNanos = 0L
        maxDurationNanos = 0L
        droppedFrames = 0
    }

    /** Adds nanosecond totals without wrapping. */
    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    /** Adds callback-drop totals without wrapping. */
    private fun safeAddInt(left: Int, right: Int): Int =
        if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right
}
