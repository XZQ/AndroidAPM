package com.apm.anr

import org.junit.Assert.*
import org.junit.Test

/**
 * AnrModule 配置和参数测试。
 *
 * 注：AnrModule 构造函数内属性初始化使用 Handler(Looper.getMainLooper())，
 * 纯 JUnit 环境无主线程 Looper，因此无法实例化 Module。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 * 此文件验证 Config 层默认值和自定义值覆盖。
 */
class AnrModuleTest {

    /** 默认配置开启 ANR 监控。 */
    @Test
    fun `default config enables anr monitor`() {
        val config = AnrConfig()
        assertTrue(config.enableAnrMonitor)
    }

    /** 默认检查间隔为 5 秒。 */
    @Test
    fun `default check interval is 5 seconds`() {
        val config = AnrConfig()
        assertEquals(5_000L, config.checkIntervalMs)
    }

    /** 默认 ANR 超时为 5 秒。 */
    @Test
    fun `default anr timeout is 5 seconds`() {
        val config = AnrConfig()
        assertEquals(5_000L, config.anrTimeoutMs)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = AnrConfig()
        assertEquals(4_000, config.maxStackTraceLength)
    }

    /** 默认开启 SIGQUIT 检测。 */
    @Test
    fun `default sigquit detection is enabled`() {
        val config = AnrConfig()
        assertTrue(config.enableSigquitDetection)
    }

    /** 默认开启 traces 文件读取。 */
    @Test
    fun `default traces file reading is enabled`() {
        val config = AnrConfig()
        assertTrue(config.enableTracesFileReading)
    }

    /** 默认开启 ANR 原因分类。 */
    @Test
    fun `default anr classification is enabled`() {
        val config = AnrConfig()
        assertTrue(config.enableAnrClassification)
    }

    /** 默认去重窗口 30 秒。 */
    @Test
    fun `default deduplication window is 30 seconds`() {
        val config = AnrConfig()
        assertEquals(30_000L, config.anrDeduplicationWindowMs)
    }

    /** 默认严重告警阈值 10 秒。 */
    @Test
    fun `default anr severe threshold is 10 seconds`() {
        val config = AnrConfig()
        assertEquals(10_000L, config.anrSevereThresholdMs)
    }

    /** 默认堆栈采样次数 3。 */
    @Test
    fun `default stack sample count is 3`() {
        val config = AnrConfig()
        assertEquals(3, config.stackSampleCount)
    }

    /** 默认堆栈采样间隔 100ms。 */
    @Test
    fun `default stack sample interval is 100ms`() {
        val config = AnrConfig()
        assertEquals(100L, config.stackSampleIntervalMs)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = AnrConfig(
            enableAnrMonitor = false,
            checkIntervalMs = 3_000L,
            anrTimeoutMs = 10_000L,
            maxStackTraceLength = 2_000,
            enableSigquitDetection = false,
            enableTracesFileReading = false,
            enableAnrClassification = false,
            anrDeduplicationWindowMs = 60_000L,
            anrSevereThresholdMs = 20_000L,
            stackSampleCount = 5,
            stackSampleIntervalMs = 200L
        )
        // 验证所有自定义值已生效
        assertFalse(config.enableAnrMonitor)
        assertEquals(3_000L, config.checkIntervalMs)
        assertEquals(10_000L, config.anrTimeoutMs)
        assertEquals(2_000, config.maxStackTraceLength)
        assertFalse(config.enableSigquitDetection)
        assertFalse(config.enableTracesFileReading)
        assertFalse(config.enableAnrClassification)
        assertEquals(60_000L, config.anrDeduplicationWindowMs)
        assertEquals(20_000L, config.anrSevereThresholdMs)
        assertEquals(5, config.stackSampleCount)
        assertEquals(200L, config.stackSampleIntervalMs)
    }

    /** 去重窗口大于检查间隔。 */
    @Test
    fun `deduplication window is larger than check interval`() {
        val config = AnrConfig()
        // 去重窗口应大于单次检查间隔，避免漏报
        assertTrue(config.anrDeduplicationWindowMs > config.checkIntervalMs)
    }

    /** 严重告警阈值大于或等于检查间隔。 */
    @Test
    fun `severe threshold is at least check interval`() {
        val config = AnrConfig()
        // 严重告警阈值不应小于检查间隔
        assertTrue(config.anrSevereThresholdMs >= config.checkIntervalMs)
    }
}
