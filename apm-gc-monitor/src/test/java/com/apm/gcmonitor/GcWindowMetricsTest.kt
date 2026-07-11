package com.apm.gcmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Tests deterministic GC-window calculations independently of Android runtime stats. */
class GcWindowMetricsTest {

    /** Allocation and reclaim metrics use monotonic counter deltas. */
    @Test
    fun `calculate derives allocation rate and reclaim rate`() {
        val previous = GcStats(timestamp = 1_000L, bytesAllocated = 1_000L, bytesFreed = 400L)
        val current = GcStats(timestamp = 2_000L, bytesAllocated = 3_048L, bytesFreed = 1_424L)

        val metrics = GcWindowMetrics.calculate(previous, current)

        assertEquals(2f, metrics.allocationRateKbPerSec, 0.001f)
        assertEquals(1_024L, metrics.gcReclaimBytes)
        assertEquals(0.5f, metrics.gcReclaimRate, 0.001f)
    }

    /** Counter resets and a zero-length window never create negative or infinite metrics. */
    @Test
    fun `calculate clamps reset counters and zero window`() {
        val previous = GcStats(timestamp = 2_000L, bytesAllocated = 10_000L, bytesFreed = 8_000L)
        val current = GcStats(timestamp = 2_000L, bytesAllocated = 5L, bytesFreed = 3L)

        val metrics = GcWindowMetrics.calculate(previous, current)

        assertEquals(0f, metrics.allocationRateKbPerSec, 0.001f)
        assertEquals(0L, metrics.gcReclaimBytes)
        assertEquals(0f, metrics.gcReclaimRate, 0.001f)
    }

    /** A temporarily unavailable ART counter cannot become a lifecycle-sized recovery spike. */
    @Test
    fun `calculate marks unavailable and recovered counter windows invalid`() {
        val unavailable = GcStats(
            gcCount = -1L,
            gcTimeMs = -1L,
            timestamp = 1_000L,
            bytesAllocated = -1L,
            bytesFreed = -1L
        )
        val recovered = GcStats(
            gcCount = 5_000L,
            gcTimeMs = 50_000L,
            timestamp = 2_000L,
            bytesAllocated = 10_000_000L,
            bytesFreed = 8_000_000L
        )

        val metrics = GcWindowMetrics.calculate(unavailable, recovered)

        assertFalse(metrics.allocationRateAvailable)
        assertFalse(metrics.reclaimRateAvailable)
        assertFalse(metrics.gcCountersAvailable)
        assertEquals(0L, metrics.gcCountDelta)
        assertEquals(0L, metrics.gcTimeDeltaMs)
        assertEquals(0f, metrics.allocationRateKbPerSec, 0.001f)
    }
}
