package com.apm.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for FPS time windows and the allocation-free FrameMetrics accumulator. */
class FrameWindowPolicyTest {
    /** A registered real-render listener must suppress perpetual VSync fallback callbacks. */
    @Test
    fun `frame metrics registration suppresses choreographer fallback`() {
        assertFalse(shouldUseChoreographerFallback(frameMetricsRegistered = true))
    }

    /** Missing or failed FrameMetrics registration must retain the compatible VSync path. */
    @Test
    fun `missing frame metrics registration retains choreographer fallback`() {
        assertTrue(shouldUseChoreographerFallback(frameMetricsRegistered = false))
    }

    /** FPS is based on measured intervals, not callback count or the configured refresh rate. */
    @Test
    fun `rendered fps uses actual interval duration`() {
        assertEquals(60, calculateRenderedFps(60, 1_000_000_000L, maximumFps = 120))
        assertEquals(30, calculateRenderedFps(30, 1_000_000_000L, maximumFps = 120))
        assertEquals(120, calculateRenderedFps(240, 1_000_000_000L, maximumFps = 120))
        assertEquals(0, calculateRenderedFps(0, 0L, maximumFps = 120))
    }

    /** Report cadence follows elapsed time rather than the number of frames observed. */
    @Test
    fun `report window triggers after elapsed wall clock time`() {
        val window = FrameReportWindow(reportIntervalMs = 1_000L)

        assertFalse(window.onFrame(100L))
        repeat(240) { index ->
            assertFalse(window.onFrame(100L + index + 1L))
        }
        assertTrue(window.onFrame(1_000_000_100L))
    }

    /** The triggering frame becomes the next non-overlapping window baseline. */
    @Test
    fun `report window advances from triggering frame`() {
        val window = FrameReportWindow(reportIntervalMs = 10L)

        assertFalse(window.onFrame(1_000L))
        assertTrue(window.onFrame(10_001_000L))
        assertFalse(window.onFrame(20_000_999L))
        assertTrue(window.onFrame(20_001_000L))
    }

    /** A regressing monotonic timestamp starts a fresh window. */
    @Test
    fun `report window resets after timestamp regression`() {
        val window = FrameReportWindow(reportIntervalMs = 10L)

        assertFalse(window.onFrame(20_000_000L))
        assertFalse(window.onFrame(10_000_000L))
        assertTrue(window.onFrame(20_000_000L))
    }

    /** Invalid non-positive intervals are clamped to one millisecond. */
    @Test
    fun `report window clamps nonpositive interval`() {
        val window = FrameReportWindow(reportIntervalMs = 0L)

        assertFalse(window.onFrame(0L))
        assertFalse(window.onFrame(999_999L))
        assertTrue(window.onFrame(1_000_000L))
    }

    /** Primitive rolling totals aggregate the newest bounded sample set. */
    @Test
    fun `frame metrics accumulator retains newest samples`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 2)
        accumulator.record(1L, 2L, 3L, 4L, delayed = true)
        accumulator.record(10L, 20L, 30L, 40L, delayed = false)
        accumulator.record(100L, 200L, 300L, 400L, delayed = true)

        val snapshot = accumulator.snapshotAndReset()

        assertEquals(110L, snapshot?.measureLayoutNanos)
        assertEquals(220L, snapshot?.drawNanos)
        assertEquals(330L, snapshot?.syncNanos)
        assertEquals(440L, snapshot?.swapBuffersNanos)
        assertEquals(1, snapshot?.delayedFrames)
        assertNull(accumulator.snapshotAndReset())
    }

    /** Negative platform durations cannot reduce aggregate totals. */
    @Test
    fun `frame metrics accumulator clamps negative durations`() {
        val accumulator = FrameMetricsWindowAccumulator(capacity = 1)

        accumulator.record(-1L, -2L, -3L, -4L, delayed = false)

        val snapshot = accumulator.snapshotAndReset()
        assertEquals(0L, snapshot?.measureLayoutNanos)
        assertEquals(0L, snapshot?.drawNanos)
        assertEquals(0L, snapshot?.syncNanos)
        assertEquals(0L, snapshot?.swapBuffersNanos)
    }
}
