package com.apm.gcmonitor

import org.junit.Assert.*
import org.junit.Test

/**
 * GcMonitorModule 配置和参数测试。
 *
 * 注：GcMonitorModule 构造函数内属性初始化使用 Handler(Looper.getMainLooper()),
 * 纯 JUnit 环境无主线程 Looper，因此无法实例化 Module。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 * 此文件验证 Config 层默认值和自定义值覆盖。
 */
class GcMonitorModuleTest {

    /** 默认配置开启 GC 监控。 */
    @Test
    fun `default config enables gc monitor`() {
        val config = GcMonitorConfig()
        assertTrue(config.enableGcMonitor)
    }

    /** 默认检测间隔 10 秒。 */
    @Test
    fun `default check interval is 10 seconds`() {
        val config = GcMonitorConfig()
        assertEquals(10_000L, config.checkIntervalMs)
    }

    /** 默认 GC 次数飙升阈值 5。 */
    @Test
    fun `default gc count spike threshold is 5`() {
        val config = GcMonitorConfig()
        assertEquals(5, config.gcCountSpikeThreshold)
    }

    /** 默认 GC 耗时占比阈值 10%。 */
    @Test
    fun `default gc time ratio threshold is 10 percent`() {
        val config = GcMonitorConfig()
        assertEquals(0.10f, config.gcTimeRatioThreshold, 0.001f)
    }

    /** 默认 Heap 增长阈值 20%。 */
    @Test
    fun `default heap growth threshold is 20 percent`() {
        val config = GcMonitorConfig()
        assertEquals(0.20f, config.heapGrowthThreshold, 0.001f)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = GcMonitorConfig()
        assertEquals(4_000, config.maxStackTraceLength)
    }

    /** 默认开启对象分配速率检测。 */
    @Test
    fun `default allocation rate is enabled`() {
        val config = GcMonitorConfig()
        assertTrue(config.enableAllocationRate)
    }

    /** 默认分配速率阈值 1024 KB/s（1MB/s）。 */
    @Test
    fun `default allocation rate threshold is 1024`() {
        val config = GcMonitorConfig()
        assertEquals(1_024f, config.allocationRateThresholdKbPerSec, 0.001f)
    }

    /** 默认开启 GC 回收率分析。 */
    @Test
    fun `default gc reclaim analysis is enabled`() {
        val config = GcMonitorConfig()
        assertTrue(config.enableGcReclaimAnalysis)
    }

    /** 默认 GC 低回收率阈值 10%。 */
    @Test
    fun `default gc low reclaim rate is 10 percent`() {
        val config = GcMonitorConfig()
        assertEquals(0.10f, config.gcLowReclaimRate, 0.001f)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = GcMonitorConfig(
            enableGcMonitor = false,
            checkIntervalMs = 5_000L,
            gcCountSpikeThreshold = 10,
            gcTimeRatioThreshold = 0.20f,
            heapGrowthThreshold = 0.30f,
            maxStackTraceLength = 2_000,
            enableAllocationRate = false,
            allocationRateThresholdKbPerSec = 2_048f,
            enableGcReclaimAnalysis = false,
            gcLowReclaimRate = 0.20f
        )
        // 验证所有自定义值已生效
        assertFalse(config.enableGcMonitor)
        assertEquals(5_000L, config.checkIntervalMs)
        assertEquals(10, config.gcCountSpikeThreshold)
        assertEquals(0.20f, config.gcTimeRatioThreshold, 0.001f)
        assertEquals(0.30f, config.heapGrowthThreshold, 0.001f)
        assertEquals(2_000, config.maxStackTraceLength)
        assertFalse(config.enableAllocationRate)
        assertEquals(2_048f, config.allocationRateThresholdKbPerSec, 0.001f)
        assertFalse(config.enableGcReclaimAnalysis)
        assertEquals(0.20f, config.gcLowReclaimRate, 0.001f)
    }

    /** GC 耗时占比阈值在合法范围 0~1。 */
    @Test
    fun `gc time ratio threshold is in valid range`() {
        val config = GcMonitorConfig()
        // GC 耗时占比阈值应为 0~1 之间的浮点数
        assertTrue(config.gcTimeRatioThreshold in 0f..1f)
    }

    /** Heap 增长阈值在合法范围 0~1。 */
    @Test
    fun `heap growth threshold is in valid range`() {
        val config = GcMonitorConfig()
        // Heap 增长阈值应为 0~1 之间的浮点数
        assertTrue(config.heapGrowthThreshold in 0f..1f)
    }

    /** GC 低回收率阈值在合法范围 0~1。 */
    @Test
    fun `gc low reclaim rate is in valid range`() {
        val config = GcMonitorConfig()
        // GC 低回收率阈值应为 0~1 之间的浮点数
        assertTrue(config.gcLowReclaimRate in 0f..1f)
    }

    /** GC 次数飙升阈值为正数。 */
    @Test
    fun `gc count spike threshold is positive`() {
        val config = GcMonitorConfig()
        // GC 次数飙升阈值应为正整数
        assertTrue(config.gcCountSpikeThreshold > 0)
    }

    /** 检测间隔大于 0。 */
    @Test
    fun `check interval is positive`() {
        val config = GcMonitorConfig()
        // 检测间隔应为正数
        assertTrue(config.checkIntervalMs > 0L)
    }
}
