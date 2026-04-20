package com.apm.launch

import org.junit.Assert.*
import org.junit.Test

/**
 * LaunchModule 配置和参数测试。
 * 注：Module 构造函数依赖 Handler(Looper.getMainLooper()) 和 SystemClock.elapsedRealtime()，
 * 且实现 ActivityLifecycleCallbacks 接口需要 Activity/Bundle 等 Android 框架类，
 * 纯 JUnit 环境无主线程 Looper 和 Activity，因此仅测试 Config 层。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 */
class LaunchModuleTest {

    /** 默认开启冷启动监控。 */
    @Test
    fun `default enableColdStart is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableColdStart)
    }

    /** 默认开启热启动监控。 */
    @Test
    fun `default enableHotStart is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableHotStart)
    }

    /** 默认开启温启动监控。 */
    @Test
    fun `default enableWarmStart is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableWarmStart)
    }

    /** 默认启动超时 30 秒。 */
    @Test
    fun `default launchTimeoutMs is 30 seconds`() {
        val config = LaunchConfig()
        assertEquals(EXPECTED_TIMEOUT_MS, config.launchTimeoutMs)
    }

    /** 默认温启动阈值 5 秒。 */
    @Test
    fun `default warmStartThresholdMs is 5 seconds`() {
        val config = LaunchConfig()
        assertEquals(EXPECTED_WARM_THRESHOLD_MS, config.warmStartThresholdMs)
    }

    /** 默认开启分阶段追踪。 */
    @Test
    fun `default enablePhaseTracking is true`() {
        val config = LaunchConfig()
        assertTrue(config.enablePhaseTracking)
    }

    /** 默认开启首帧渲染追踪。 */
    @Test
    fun `default enableFirstFrameTracking is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableFirstFrameTracking)
    }

    /** 默认冷启动告警阈值 2 秒。 */
    @Test
    fun `default coldStartWarnThresholdMs is 2 seconds`() {
        val config = LaunchConfig()
        assertEquals(EXPECTED_COLD_WARN_MS, config.coldStartWarnThresholdMs)
    }

    /** 默认冷启动严重告警阈值 5 秒。 */
    @Test
    fun `default coldStartSevereThresholdMs is 5 seconds`() {
        val config = LaunchConfig()
        assertEquals(EXPECTED_COLD_SEVERE_MS, config.coldStartSevereThresholdMs)
    }

    /** 默认开启 ContentProvider 追踪。 */
    @Test
    fun `default enableContentProviderTracking is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableContentProviderTracking)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides all defaults`() {
        val config = LaunchConfig(
            enableColdStart = false,
            enableHotStart = false,
            enableWarmStart = false,
            launchTimeoutMs = CUSTOM_TIMEOUT_MS,
            warmStartThresholdMs = CUSTOM_WARM_THRESHOLD_MS,
            enablePhaseTracking = false,
            enableFirstFrameTracking = false,
            coldStartWarnThresholdMs = CUSTOM_COLD_WARN_MS,
            coldStartSevereThresholdMs = CUSTOM_COLD_SEVERE_MS,
            enableContentProviderTracking = false
        )
        // 验证所有自定义值已正确覆盖
        assertFalse(config.enableColdStart)
        assertFalse(config.enableHotStart)
        assertFalse(config.enableWarmStart)
        assertEquals(CUSTOM_TIMEOUT_MS, config.launchTimeoutMs)
        assertEquals(CUSTOM_WARM_THRESHOLD_MS, config.warmStartThresholdMs)
        assertFalse(config.enablePhaseTracking)
        assertFalse(config.enableFirstFrameTracking)
        assertEquals(CUSTOM_COLD_WARN_MS, config.coldStartWarnThresholdMs)
        assertEquals(CUSTOM_COLD_SEVERE_MS, config.coldStartSevereThresholdMs)
        assertFalse(config.enableContentProviderTracking)
    }

    /** data class copy 仅修改指定字段。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = LaunchConfig()
        val modified = original.copy(launchTimeoutMs = CUSTOM_TIMEOUT_MS)
        // 修改的字段
        assertEquals(CUSTOM_TIMEOUT_MS, modified.launchTimeoutMs)
        // 未修改的字段保持默认
        assertTrue(modified.enableColdStart)
        assertTrue(modified.enableHotStart)
    }

    /** 告警阈值应低于严重告警阈值。 */
    @Test
    fun `warn threshold is less than severe threshold`() {
        val config = LaunchConfig()
        assertTrue(config.coldStartWarnThresholdMs < config.coldStartSevereThresholdMs)
    }

    /** 温启动阈值应低于启动超时。 */
    @Test
    fun `warm threshold is less than launch timeout`() {
        val config = LaunchConfig()
        assertTrue(config.warmStartThresholdMs < config.launchTimeoutMs)
    }

    companion object {
        /** 期望的默认超时：30 秒。 */
        private const val EXPECTED_TIMEOUT_MS = 30_000L
        /** 期望的默认温启动阈值：5 秒。 */
        private const val EXPECTED_WARM_THRESHOLD_MS = 5_000L
        /** 期望的默认冷启动告警阈值：2 秒。 */
        private const val EXPECTED_COLD_WARN_MS = 2_000L
        /** 期望的默认冷启动严重告警阈值：5 秒。 */
        private const val EXPECTED_COLD_SEVERE_MS = 5_000L
        /** 自定义超时：60 秒。 */
        private const val CUSTOM_TIMEOUT_MS = 60_000L
        /** 自定义温启动阈值：10 秒。 */
        private const val CUSTOM_WARM_THRESHOLD_MS = 10_000L
        /** 自定义冷启动告警阈值：3 秒。 */
        private const val CUSTOM_COLD_WARN_MS = 3_000L
        /** 自定义冷启动严重告警阈值：8 秒。 */
        private const val CUSTOM_COLD_SEVERE_MS = 8_000L
    }
}
