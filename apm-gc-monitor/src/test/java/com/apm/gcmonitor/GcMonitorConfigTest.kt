package com.apm.gcmonitor

import org.junit.Assert.*
import org.junit.Test

/**
 * GcMonitorConfig 默认值测试。
 */
class GcMonitorConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableGcMonitor is true`() {
        val config = GcMonitorConfig()
        assertTrue(config.enableGcMonitor)
    }

    /** 默认检测间隔 10 秒。 */
    @Test
    fun `default checkIntervalMs is 10 seconds`() {
        val config = GcMonitorConfig()
        assertEquals(10_000L, config.checkIntervalMs)
    }

    /** 默认 GC 次数飙升阈值 5。 */
    @Test
    fun `default gcCountSpikeThreshold is 5`() {
        val config = GcMonitorConfig()
        assertEquals(5, config.gcCountSpikeThreshold)
    }

    /** 默认 GC 耗时占比阈值 10%。 */
    @Test
    fun `default gcTimeRatioThreshold is 10 percent`() {
        val config = GcMonitorConfig()
        assertEquals(0.10f, config.gcTimeRatioThreshold, 0.001f)
    }

    /** 默认 Heap 增长阈值 20%。 */
    @Test
    fun `default heapGrowthThreshold is 20 percent`() {
        val config = GcMonitorConfig()
        assertEquals(0.20f, config.heapGrowthThreshold, 0.001f)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = GcMonitorConfig(checkIntervalMs = 5_000L, gcCountSpikeThreshold = 10)
        assertEquals(5_000L, config.checkIntervalMs)
        assertEquals(10, config.gcCountSpikeThreshold)
    }
}

/**
 * GcStats 数据类测试。
 */
class GcStatsTest {

    /** 默认值全为 0。 */
    @Test
    fun `default stats are all zero`() {
        val stats = GcStats()
        assertEquals(0L, stats.gcCount)
        assertEquals(0L, stats.gcTimeMs)
        assertEquals(0L, stats.javaHeapUsed)
    }

    /** 自定义值。 */
    @Test
    fun `custom values`() {
        val stats = GcStats(gcCount = 100, gcTimeMs = 5000, javaHeapUsed = 50 * 1024 * 1024)
        assertEquals(100L, stats.gcCount)
        assertEquals(5000L, stats.gcTimeMs)
    }
}
