package com.apm.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies bounded Binder call aggregation windows. */
class BinderCallAggregatorTest {
    /** A snapshot is emitted exactly at the configured call count. */
    @Test
    fun `window emits count latency and slow calls then resets`() {
        val aggregator = BinderCallAggregator(windowSize = 3)

        assertNull(aggregator.record(durationMs = 10L, slow = false))
        assertNull(aggregator.record(durationMs = 20L, slow = true))
        val snapshot = aggregator.record(durationMs = 30L, slow = false)

        assertEquals(3, snapshot?.callCount)
        assertEquals(60L, snapshot?.totalDurationMs)
        assertEquals(30L, snapshot?.maxDurationMs)
        assertEquals(1, snapshot?.slowCallCount)
        assertNull(aggregator.record(durationMs = 5L, slow = false))
    }
}
