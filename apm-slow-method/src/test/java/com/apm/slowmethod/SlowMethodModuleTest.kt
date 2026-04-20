package com.apm.slowmethod

import org.junit.Assert.*
import org.junit.Test

/**
 * SlowMethodModule 配置和参数测试。
 * 注：Module 构造函数在属性初始化时通过反射访问 Looper.getMainLooper() 的 mLogging 字段，
 * 且 onStart/onStop 使用 Looper.getMainLooper() 和 SystemClock.uptimeMillis()，
 * 纯 JUnit 环境无主线程 Looper，因此仅测试 Config 层。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 */
class SlowMethodModuleTest {

    /** 默认开启慢方法检测。 */
    @Test
    fun `default enableSlowMethod is true`() {
        val config = SlowMethodConfig()
        assertTrue(config.enableSlowMethod)
    }

    /** 默认阈值 300ms。 */
    @Test
    fun `default thresholdMs is 300`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_THRESHOLD_MS, config.thresholdMs)
    }

    /** 默认严重阈值 800ms。 */
    @Test
    fun `default severeThresholdMs is 800`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_SEVERE_MS, config.severeThresholdMs)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default maxStackTraceLength is 4000`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_STACK_LENGTH, config.maxStackTraceLength)
    }

    /** 默认包含堆栈。 */
    @Test
    fun `default includeStackTrace is true`() {
        val config = SlowMethodConfig()
        assertTrue(config.includeStackTrace)
    }

    /** 默认开启栈采样。 */
    @Test
    fun `default enableStackSampling is true`() {
        val config = SlowMethodConfig()
        assertTrue(config.enableStackSampling)
    }

    /** 默认采样间隔 10ms。 */
    @Test
    fun `default samplingIntervalMs is 10`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_SAMPLING_INTERVAL_MS, config.samplingIntervalMs)
    }

    /** 默认采样窗口 5 秒。 */
    @Test
    fun `default samplingWindowMs is 5000`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_SAMPLING_WINDOW_MS, config.samplingWindowMs)
    }

    /** 默认热点方法上报数量 10。 */
    @Test
    fun `default topMethodCount is 10`() {
        val config = SlowMethodConfig()
        assertEquals(EXPECTED_TOP_METHOD_COUNT, config.topMethodCount)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides all defaults`() {
        val config = SlowMethodConfig(
            enableSlowMethod = false,
            thresholdMs = CUSTOM_THRESHOLD_MS,
            severeThresholdMs = CUSTOM_SEVERE_MS,
            maxStackTraceLength = CUSTOM_STACK_LENGTH,
            includeStackTrace = false,
            enableStackSampling = false,
            samplingIntervalMs = CUSTOM_SAMPLING_INTERVAL_MS,
            samplingWindowMs = CUSTOM_SAMPLING_WINDOW_MS,
            topMethodCount = CUSTOM_TOP_METHOD_COUNT
        )
        // 验证所有自定义值已正确覆盖
        assertFalse(config.enableSlowMethod)
        assertEquals(CUSTOM_THRESHOLD_MS, config.thresholdMs)
        assertEquals(CUSTOM_SEVERE_MS, config.severeThresholdMs)
        assertEquals(CUSTOM_STACK_LENGTH, config.maxStackTraceLength)
        assertFalse(config.includeStackTrace)
        assertFalse(config.enableStackSampling)
        assertEquals(CUSTOM_SAMPLING_INTERVAL_MS, config.samplingIntervalMs)
        assertEquals(CUSTOM_SAMPLING_WINDOW_MS, config.samplingWindowMs)
        assertEquals(CUSTOM_TOP_METHOD_COUNT, config.topMethodCount)
    }

    /** data class copy 仅修改指定字段。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = SlowMethodConfig()
        val modified = original.copy(thresholdMs = CUSTOM_THRESHOLD_MS)
        // 修改的字段
        assertEquals(CUSTOM_THRESHOLD_MS, modified.thresholdMs)
        // 未修改的字段保持默认
        assertTrue(modified.enableSlowMethod)
        assertEquals(EXPECTED_SEVERE_MS, modified.severeThresholdMs)
    }

    /** 严重阈值应大于普通阈值。 */
    @Test
    fun `severe threshold is greater than normal threshold`() {
        val config = SlowMethodConfig()
        assertTrue(config.severeThresholdMs > config.thresholdMs)
    }

    /** 采样间隔应小于采样窗口。 */
    @Test
    fun `sampling interval is less than sampling window`() {
        val config = SlowMethodConfig()
        assertTrue(config.samplingIntervalMs < config.samplingWindowMs)
    }

    companion object {
        /** 期望的默认阈值：300ms。 */
        private const val EXPECTED_THRESHOLD_MS = 300L
        /** 期望的默认严重阈值：800ms。 */
        private const val EXPECTED_SEVERE_MS = 800L
        /** 期望的默认堆栈长度：4000。 */
        private const val EXPECTED_STACK_LENGTH = 4000
        /** 期望的默认采样间隔：10ms。 */
        private const val EXPECTED_SAMPLING_INTERVAL_MS = 10L
        /** 期望的默认采样窗口：5000ms。 */
        private const val EXPECTED_SAMPLING_WINDOW_MS = 5000L
        /** 期望的默认热点方法数量：10。 */
        private const val EXPECTED_TOP_METHOD_COUNT = 10
        /** 自定义阈值：500ms。 */
        private const val CUSTOM_THRESHOLD_MS = 500L
        /** 自定义严重阈值：1000ms。 */
        private const val CUSTOM_SEVERE_MS = 1000L
        /** 自定义堆栈长度：8000。 */
        private const val CUSTOM_STACK_LENGTH = 8000
        /** 自定义采样间隔：20ms。 */
        private const val CUSTOM_SAMPLING_INTERVAL_MS = 20L
        /** 自定义采样窗口：10000ms。 */
        private const val CUSTOM_SAMPLING_WINDOW_MS = 10_000L
        /** 自定义热点方法数量：5。 */
        private const val CUSTOM_TOP_METHOD_COUNT = 5
    }
}
