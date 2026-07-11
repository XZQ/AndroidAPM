package com.apm.ipc

/** One completed fixed-size Binder aggregation window. */
internal data class BinderAggregationSnapshot(
    /** Number of calls in the window. */
    val callCount: Int,
    /** Sum of all call durations. */
    val totalDurationMs: Long,
    /** Slowest call duration. */
    val maxDurationMs: Long,
    /** Number of calls that crossed their thread-specific threshold. */
    val slowCallCount: Int
)

/** Bounded, thread-safe Binder call accumulator. */
internal class BinderCallAggregator(
    /** Number of calls emitted in one snapshot. */
    private val windowSize: Int
) {
    /** Calls currently retained in the active window. */
    private var callCount = 0

    /** Duration sum retained in the active window. */
    private var totalDurationMs = 0L

    /** Maximum duration retained in the active window. */
    private var maxDurationMs = 0L

    /** Slow calls retained in the active window. */
    private var slowCallCount = 0

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    /**
     * Records one completed call and closes a full window.
     *
     * @param durationMs non-negative Binder duration
     * @param slow whether the call crossed its applicable threshold
     * @return completed snapshot, or null while the window is incomplete
     */
    @Synchronized
    fun record(durationMs: Long, slow: Boolean): BinderAggregationSnapshot? {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        callCount += 1
        totalDurationMs = safeAdd(totalDurationMs, safeDurationMs)
        maxDurationMs = maxOf(maxDurationMs, safeDurationMs)
        if (slow) {
            slowCallCount += 1
        }
        if (callCount < windowSize) {
            return null
        }
        val snapshot = BinderAggregationSnapshot(callCount, totalDurationMs, maxDurationMs, slowCallCount)
        resetLocked()
        return snapshot
    }

    /** Clears an incomplete aggregation window. */
    @Synchronized
    fun reset() {
        resetLocked()
    }

    /** Clears counters while the caller owns the monitor lock. */
    private fun resetLocked() {
        callCount = 0
        totalDurationMs = 0L
        maxDurationMs = 0L
        slowCallCount = 0
    }

    /** Adds durations without wrapping a long-running process into negatives. */
    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
