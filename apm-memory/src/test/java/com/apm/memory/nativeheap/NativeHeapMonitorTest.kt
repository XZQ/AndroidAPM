package com.apm.memory.nativeheap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NativeHeapMonitor 行为测试。
 *
 * 验证 enable/disable 状态门控与 Debug API 读取路径；
 * Robolectric 环境下 Debug.getNativeHeap* 返回确定性默认值，
 * 上报路径依赖 Apm.emit（未初始化时为安全 no-op）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeHeapMonitorTest {

    /** 未启用的监控返回默认空统计，上报为 no-op。 */
    @Test
    fun `disabled monitor returns default stats`() {
        val monitor = NativeHeapMonitor()
        // enable() 之前 getStats 不触碰 Debug API
        val stats = monitor.getStats()
        assertEquals(0L, stats.currentBytes)
        assertEquals(0L, stats.totalAllocBytes)
        assertEquals(0L, stats.totalFreeBytes)
        // 未启用时 reportStats 直接早退
        monitor.reportStats(TEST_SCENE)
    }

    /** enable 返回 true 且重复启用幂等。 */
    @Test
    fun `enable is idempotent`() {
        val monitor = NativeHeapMonitor()
        assertTrue(monitor.enable())
        // 二次启用保持 true，不重复初始化
        assertTrue(monitor.enable())
    }

    /** 启用后读取 Debug Native Heap 计数不抛异常。 */
    @Test
    fun `enabled monitor reads debug native heap without crashing`() {
        val monitor = NativeHeapMonitor()
        monitor.enable()
        // Robolectric 的 Debug shadow 返回非负默认值
        val stats = monitor.getStats()
        assertTrue(stats.currentBytes >= 0L)
        assertTrue(stats.totalFreeBytes >= 0L)
        // 启用状态下上报走 Apm.emit（未初始化时安全丢弃）
        monitor.reportStats(TEST_SCENE)
    }

    /** disable 后统计回到默认空值。 */
    @Test
    fun `disable resets stats to defaults`() {
        val monitor = NativeHeapMonitor()
        monitor.enable()
        monitor.disable()
        // 关闭后不再读取 Debug API，返回默认构造统计
        val stats = monitor.getStats()
        assertEquals(0L, stats.currentBytes)
        assertEquals(0L, stats.peakBytes)
        assertEquals(0L, stats.allocCount)
    }

    companion object {
        /** 测试场景名。 */
        private const val TEST_SCENE = "test"
    }
}
