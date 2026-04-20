package com.apm.fps

import org.junit.Assert.*
import org.junit.Test

/**
 * FpsModule 配置和参数测试。
 *
 * 注：FpsModule 构造函数初始化 FpsMonitor（内部依赖 Choreographer/FrameMetrics），
 * 并实现 ActivityLifecycleCallbacks（依赖 Activity/Context），
 * 纯 JUnit 环境无法实例化 Module。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 * 此文件验证 Config 层默认值和自定义值覆盖。
 */
class FpsModuleTest {

    /** 默认配置开启 FPS 监控。 */
    @Test
    fun `default config enables fps monitor`() {
        val config = FpsConfig()
        assertTrue(config.enableFpsMonitor)
    }

    /** 默认卡顿阈值 16ms（60fps 一帧）。 */
    @Test
    fun `default jank threshold is 16ms`() {
        val config = FpsConfig()
        assertEquals(16L, config.jankThresholdMs)
    }

    /** 默认冻结阈值 300ms。 */
    @Test
    fun `default frozen threshold is 300ms`() {
        val config = FpsConfig()
        assertEquals(300L, config.frozenThresholdMs)
    }

    /** 默认统计窗口 60 帧。 */
    @Test
    fun `default window size is 60 frames`() {
        val config = FpsConfig()
        assertEquals(60, config.windowSize)
    }

    /** 默认 FPS 告警阈值 30。 */
    @Test
    fun `default fps warn threshold is 30`() {
        val config = FpsConfig()
        assertEquals(30, config.fpsWarnThreshold)
    }

    /** 默认开启场景检测。 */
    @Test
    fun `default scene detect is enabled`() {
        val config = FpsConfig()
        assertTrue(config.enableSceneDetect)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = FpsConfig()
        assertEquals(4_000, config.maxStackTraceLength)
    }

    /** 默认开启 FrameMetrics。 */
    @Test
    fun `default frame metrics is enabled`() {
        val config = FpsConfig()
        assertTrue(config.enableFrameMetrics)
    }

    /** 默认开启丢帧严重程度分级。 */
    @Test
    fun `default drop severity is enabled`() {
        val config = FpsConfig()
        assertTrue(config.enableDropSeverity)
    }

    /** 默认丢帧 SEVERE 阈值 10 帧。 */
    @Test
    fun `default drop severity severe threshold is 10`() {
        val config = FpsConfig()
        assertEquals(10, config.dropSeveritySevereThreshold)
    }

    /** 默认丢帧 MODERATE 阈值 4 帧。 */
    @Test
    fun `default drop severity moderate threshold is 4`() {
        val config = FpsConfig()
        assertEquals(4, config.dropSeverityModerateThreshold)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = FpsConfig(
            enableFpsMonitor = false,
            jankThresholdMs = 32L,
            frozenThresholdMs = 500L,
            windowSize = 120,
            fpsWarnThreshold = 45,
            enableSceneDetect = false,
            enableFrameMetrics = false,
            enableDropSeverity = false,
            dropSeveritySevereThreshold = 15,
            dropSeverityModerateThreshold = 6
        )
        // 验证所有自定义值已生效
        assertFalse(config.enableFpsMonitor)
        assertEquals(32L, config.jankThresholdMs)
        assertEquals(500L, config.frozenThresholdMs)
        assertEquals(120, config.windowSize)
        assertEquals(45, config.fpsWarnThreshold)
        assertFalse(config.enableSceneDetect)
        assertFalse(config.enableFrameMetrics)
        assertFalse(config.enableDropSeverity)
        assertEquals(15, config.dropSeveritySevereThreshold)
        assertEquals(6, config.dropSeverityModerateThreshold)
    }

    /** 冻结阈值大于卡顿阈值。 */
    @Test
    fun `frozen threshold is greater than jank threshold`() {
        val config = FpsConfig()
        // 冻结判定应比卡顿判定更严格
        assertTrue(config.frozenThresholdMs > config.jankThresholdMs)
    }

    /** SEVERE 阈值大于 MODERATE 阈值。 */
    @Test
    fun `severe threshold is greater than moderate threshold`() {
        val config = FpsConfig()
        // SEVERE 级别掉帧数应大于 MODERATE 级别
        assertTrue(config.dropSeveritySevereThreshold > config.dropSeverityModerateThreshold)
    }

    /** FPS 告警阈值小于窗口大小。 */
    @Test
    fun `fps warn threshold is less than window size`() {
        val config = FpsConfig()
        // FPS 告警阈值（30）应小于统计窗口（60），才有告警意义
        assertTrue(config.fpsWarnThreshold < config.windowSize)
    }
}
