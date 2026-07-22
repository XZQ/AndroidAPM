package com.apm.fps

/**
 * Decides when an FPS report window has elapsed using monotonic frame timestamps.
 *
 * Frame count is intentionally not part of this policy: a one-second window remains one second on
 * 60 Hz, 90 Hz, 120 Hz, and severely janky devices.
 */
internal class FrameReportWindow(reportIntervalMs: Long) {

    /** Validated report interval represented in nanoseconds. */
    private val reportIntervalNanos = millisToNanosSaturated(reportIntervalMs)

    /** First monotonic frame timestamp in the active window. */
    private var windowStartNanos = UNSET_TIMESTAMP_NANOS

    /** Clears the current window so the next frame establishes a new start timestamp. */
    internal fun reset() {
        windowStartNanos = UNSET_TIMESTAMP_NANOS
    }

    /**
     * Records one frame timestamp and returns true exactly when the wall-clock window has elapsed.
     *
     * A regressing timestamp starts a fresh window instead of producing a negative or overflowing
     * duration.
     */
    internal fun onFrame(frameTimeNanos: Long): Boolean {
        val startNanos = windowStartNanos
        if (startNanos == UNSET_TIMESTAMP_NANOS || frameTimeNanos < startNanos) {
            // The first frame, or a clock reset, establishes a new monotonic baseline.
            windowStartNanos = frameTimeNanos
            return false
        }
        if (frameTimeNanos - startNanos < reportIntervalNanos) {
            return false
        }
        // The triggering frame is the baseline for the next non-overlapping wall-clock window.
        windowStartNanos = frameTimeNanos
        return true
    }

    companion object {
        /** Nanoseconds in one millisecond. */
        private const val NANOS_PER_MS = 1_000_000L

        /** Minimum effective report interval accepted by the runtime. */
        private const val MIN_REPORT_INTERVAL_MS = 1L

        /** Sentinel that cannot collide with a valid non-negative Choreographer timestamp. */
        private const val UNSET_TIMESTAMP_NANOS = Long.MIN_VALUE

        /** Converts milliseconds without overflowing the monotonic-duration representation. */
        private fun millisToNanosSaturated(intervalMs: Long): Long {
            val positiveIntervalMs = intervalMs.coerceAtLeast(MIN_REPORT_INTERVAL_MS)
            val maxMillis = Long.MAX_VALUE / NANOS_PER_MS
            return positiveIntervalMs.coerceAtMost(maxMillis) * NANOS_PER_MS
        }
    }
}
