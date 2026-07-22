package com.apm.fps

/**
 * Allocation-free per-frame rolling accumulator for API 24+ FrameMetrics callbacks.
 *
 * Primitive rings preserve the most recent [capacity] samples without allocating an object or a
 * queue node on every main-thread frame. Only [snapshotAndReset] creates a report object.
 */
internal class FrameMetricsWindowAccumulator(capacity: Int) {

    /** Maximum number of newest samples retained by the rolling totals. */
    private val capacity = validateCapacity(capacity)

    /** Measure/layout duration ring. */
    private val measureLayoutNanos = LongArray(capacity)

    /** Draw duration ring. */
    private val drawNanos = LongArray(capacity)

    /** Render synchronization duration ring. */
    private val syncNanos = LongArray(capacity)

    /** Buffer-swap duration ring. */
    private val swapBuffersNanos = LongArray(capacity)

    /** Delayed-frame marker ring. */
    private val delayedFrames = BooleanArray(capacity)

    /** Index replaced by the next sample. */
    private var nextIndex = 0

    /** Number of valid samples currently represented by the rolling totals. */
    private var sampleCount = 0

    /** Rolling measure/layout duration total. */
    private var totalMeasureLayoutNanos = 0L

    /** Rolling draw duration total. */
    private var totalDrawNanos = 0L

    /** Rolling synchronization duration total. */
    private var totalSyncNanos = 0L

    /** Rolling buffer-swap duration total. */
    private var totalSwapBuffersNanos = 0L

    /** Rolling delayed-frame count. */
    private var delayedFrameCount = 0

    /** Adds one frame while retaining only the newest [capacity] samples. */
    internal fun record(
        measureLayoutDurationNanos: Long,
        drawDurationNanos: Long,
        syncDurationNanos: Long,
        swapBuffersDurationNanos: Long,
        delayed: Boolean
    ) {
        if (sampleCount == capacity) {
            // Remove the overwritten slot from rolling totals before storing the newest frame.
            totalMeasureLayoutNanos -= measureLayoutNanos[nextIndex]
            totalDrawNanos -= drawNanos[nextIndex]
            totalSyncNanos -= syncNanos[nextIndex]
            totalSwapBuffersNanos -= swapBuffersNanos[nextIndex]
            if (delayedFrames[nextIndex]) {
                delayedFrameCount--
            }
        } else {
            sampleCount++
        }

        val safeMeasureLayout = measureLayoutDurationNanos.coerceAtLeast(0L)
        val safeDraw = drawDurationNanos.coerceAtLeast(0L)
        val safeSync = syncDurationNanos.coerceAtLeast(0L)
        val safeSwap = swapBuffersDurationNanos.coerceAtLeast(0L)
        measureLayoutNanos[nextIndex] = safeMeasureLayout
        drawNanos[nextIndex] = safeDraw
        syncNanos[nextIndex] = safeSync
        swapBuffersNanos[nextIndex] = safeSwap
        delayedFrames[nextIndex] = delayed

        totalMeasureLayoutNanos += safeMeasureLayout
        totalDrawNanos += safeDraw
        totalSyncNanos += safeSync
        totalSwapBuffersNanos += safeSwap
        if (delayed) {
            delayedFrameCount++
        }
        nextIndex = (nextIndex + 1) % capacity
    }

    /** Returns one aggregate for the current window and clears all logical samples. */
    internal fun snapshotAndReset(): FrameMetricsBreakdown? {
        if (sampleCount == 0) {
            return null
        }
        val snapshot = FrameMetricsBreakdown(
            measureLayoutNanos = totalMeasureLayoutNanos,
            drawNanos = totalDrawNanos,
            syncNanos = totalSyncNanos,
            swapBuffersNanos = totalSwapBuffersNanos,
            delayedFrames = delayedFrameCount
        )
        reset()
        return snapshot
    }

    /** Clears logical state without reallocating the primitive rings. */
    internal fun reset() {
        nextIndex = 0
        sampleCount = 0
        totalMeasureLayoutNanos = 0L
        totalDrawNanos = 0L
        totalSyncNanos = 0L
        totalSwapBuffersNanos = 0L
        delayedFrameCount = 0
    }

    companion object {
        /** Validates capacity before primitive arrays are allocated. */
        private fun validateCapacity(capacity: Int): Int {
            require(capacity > 0) { "capacity must be positive" }
            return capacity
        }
    }
}
