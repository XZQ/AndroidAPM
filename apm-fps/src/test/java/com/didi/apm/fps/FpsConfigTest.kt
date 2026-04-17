package com.didi.apm.fps

import org.junit.Assert.*
import org.junit.Test

/**
 * FpsConfig 默认值测试。
 * 验证 FPS 监控配置项默认值正确。
 */
class FpsConfigTest {

    /** 默认开启 FPS 监控。 */
    @Test
    fun `default enableFpsMonitor is true`() {
        val config = FpsConfig()
        assertTrue(config.enableFpsMonitor)
    }

    /** 默认卡顿阈值 16ms。 */
    @Test
    fun `default jankThresholdMs is 16`() {
        val config = FpsConfig()
        assertEquals(16L, config.jankThresholdMs)
    }

    /** 默认冻结阈值 300ms。 */
    @Test
    fun `default frozenThresholdMs is 300`() {
        val config = FpsConfig()
        assertEquals(300L, config.frozenThresholdMs)
    }

    /** 默认窗口大小 60 帧。 */
    @Test
    fun `default windowSize is 60`() {
        val config = FpsConfig()
        assertEquals(60, config.windowSize)
    }

    /** 默认 FPS 告警阈值 30。 */
    @Test
    fun `default fpsWarnThreshold is 30`() {
        val config = FpsConfig()
        assertEquals(30, config.fpsWarnThreshold)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = FpsConfig(
            jankThresholdMs = 32L,
            frozenThresholdMs = 500L,
            windowSize = 120,
            fpsWarnThreshold = 45
        )
        assertEquals(32L, config.jankThresholdMs)
        assertEquals(500L, config.frozenThresholdMs)
        assertEquals(120, config.windowSize)
        assertEquals(45, config.fpsWarnThreshold)
    }
}

/**
 * FrameStats 数据类测试。
 */
class FrameStatsTest {

    /** 默认值。 */
    @Test
    fun `default stats`() {
        val stats = FrameStats(fps = 0, droppedFrames = 0, jankCount = 0, frozenCount = 0, frameCount = 0)
        assertEquals(0, stats.fps)
        assertEquals(0, stats.droppedFrames)
    }

    /** 自定义值。 */
    @Test
    fun `custom values`() {
        val stats = FrameStats(fps = 55, droppedFrames = 5, jankCount = 2, frozenCount = 0, frameCount = 60)
        assertEquals(55, stats.fps)
        assertEquals(5, stats.droppedFrames)
        assertEquals(2, stats.jankCount)
    }
}
