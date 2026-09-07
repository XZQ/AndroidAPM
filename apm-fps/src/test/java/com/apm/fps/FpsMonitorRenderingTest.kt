package com.apm.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real monitor windows with synthetic render timestamps and measured frame work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FpsMonitorRenderingTest {
    /** Idle and low-rate rendering do not imply missed frames, even on high-refresh displays. */
    @Test
    fun `intermittent rendering does not fabricate dropped or frozen frames`() {
        for (rate in listOf(60f, 90f, 120f)) {
            val reports = mutableListOf<FrameStats>()
            val monitor = FpsMonitor(FpsConfig()).apply {
                setRefreshRate(rate)
                onFrameStats = reports::add
            }
            val budget = (1_000_000_000L / rate).toLong()
            monitor.recordFrameMetricsSample(1_000_000_000L, 4_000_000L, budget)
            monitor.recordFrameMetricsSample(2_000_000_000L, 4_000_000L, budget)
            val stats = reports.single()
            assertEquals(1, stats.fps)
            assertEquals(0, stats.droppedFrames)
            assertEquals(0, stats.jankCount)
            assertEquals(0, stats.frozenCount)
            assertFalse(shouldReportFrameStats(stats.copy(frameMetricsBreakdown = FrameMetricsBreakdown()), FpsConfig()))
        }
    }

    /** Work exceeding the refresh/platform deadline is detected independently of inter-render idle. */
    @Test
    fun `slow work and frozen frames use actual work and deadline`() {
        val reports = mutableListOf<FrameStats>()
        val monitor = FpsMonitor(FpsConfig()).apply {
            setRefreshRate(120f)
            onFrameStats = reports::add
        }
        monitor.recordFrameMetricsSample(1_000_000_000L, 10_000_000L, 8_333_333L)
        monitor.recordFrameMetricsSample(2_000_000_000L, 400_000_000L, 8_333_333L)
        val stats = reports.single()
        assertEquals(2, stats.jankCount)
        assertEquals(1, stats.frozenCount)
        assertTrue(stats.droppedFrames > 0)
        assertTrue(shouldReportFrameStats(stats.copy(frameMetricsBreakdown = FrameMetricsBreakdown()), FpsConfig()))
    }

    /** Platform deadlines may differ from a refresh interval; the fallback remains compatible. */
    @Test
    fun `platform deadline wins and missing work is not an anomaly`() {
        val reports = mutableListOf<FrameStats>()
        val monitor = FpsMonitor(FpsConfig()).apply {
            onFrameStats = reports::add
        }
        monitor.recordFrameMetricsSample(1_000_000_000L, 20_000_000L, 25_000_000L)
        monitor.recordFrameMetricsSample(2_000_000_000L, -1L, -1L)
        val stats = reports.single()
        assertEquals(0, stats.jankCount)
        assertEquals(0, stats.droppedFrames)
        assertTrue(shouldReportFrameStats(stats, FpsConfig()))
    }
}
