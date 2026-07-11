package com.apm.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies fixed-window frame duration aggregation. */
class FrameMetricsAccumulatorTest {
    /** Full windows report real slow-frame and dropped-frame counts then reset. */
    @Test
    fun `full window emits frame metrics snapshot`() {
        val accumulator = FrameMetricsAccumulator(windowSize = 3, slowFrameThresholdNanos = 20L)

        assertNull(accumulator.record(totalDurationNanos = 10L, droppedFrames = 0))
        assertNull(accumulator.record(totalDurationNanos = 25L, droppedFrames = 2))
        val snapshot = accumulator.record(totalDurationNanos = 40L, droppedFrames = 1)

        assertEquals(3, snapshot?.frameCount)
        assertEquals(2, snapshot?.slowFrameCount)
        assertEquals(75L, snapshot?.totalDurationNanos)
        assertEquals(40L, snapshot?.maxDurationNanos)
        assertEquals(3, snapshot?.droppedFrames)
        assertNull(accumulator.record(totalDurationNanos = 5L, droppedFrames = 0))
    }
}
