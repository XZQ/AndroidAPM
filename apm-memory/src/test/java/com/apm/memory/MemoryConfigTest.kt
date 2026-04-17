package com.apm.memory

import org.junit.Assert.*
import org.junit.Test

/**
 * MemoryConfig 默认值测试。
 * 验证各配置项的默认值符合预期，确保不会被意外修改。
 */
class MemoryConfigTest {

    /** 默认前台采样间隔应为 15 秒。 */
    @Test
    fun `default foregroundIntervalMs is 15 seconds`() {
        val config = MemoryConfig()
        assertEquals(15_000L, config.foregroundIntervalMs)
    }

    /** 默认后台采样间隔应为 60 秒。 */
    @Test
    fun `default backgroundIntervalMs is 60 seconds`() {
        val config = MemoryConfig()
        assertEquals(60_000L, config.backgroundIntervalMs)
    }

    /** 默认 Java Heap 告警阈值应为 0.80。 */
    @Test
    fun `default javaHeapWarnRatio is 0_80`() {
        val config = MemoryConfig()
        assertEquals(0.80f, config.javaHeapWarnRatio, 0.001f)
    }

    /** 默认 Java Heap 危险阈值应为 0.90。 */
    @Test
    fun `default javaHeapCriticalRatio is 0_90`() {
        val config = MemoryConfig()
        assertEquals(0.90f, config.javaHeapCriticalRatio, 0.001f)
    }

    /** 默认 PSS 告警阈值应为 300MB (307200 KB)。 */
    @Test
    fun `default totalPssWarnKb is 300MB`() {
        val config = MemoryConfig()
        assertEquals(300 * 1024, config.totalPssWarnKb)
    }

    /** 默认采样率应为 1.0（全量）。 */
    @Test
    fun `default sampleRate is 1_0`() {
        val config = MemoryConfig()
        assertEquals(1.0f, config.sampleRate, 0.001f)
    }

    /** 默认每条快照都上报。 */
    @Test
    fun `default reportEverySnapshot is true`() {
        val config = MemoryConfig()
        assertTrue(config.reportEverySnapshot)
    }

    /** 默认泄漏检查延迟应为 5 秒。 */
    @Test
    fun `default leakCheckDelayMs is 5 seconds`() {
        val config = MemoryConfig()
        assertEquals(5_000L, config.leakCheckDelayMs)
    }

    /** 默认 dump 冷却时间应为 10 分钟。 */
    @Test
    fun `default dumpCooldownMs is 10 minutes`() {
        val config = MemoryConfig()
        assertEquals(10 * 60 * 1000L, config.dumpCooldownMs)
    }

    /** 默认 Native Heap 告警阈值应为 512MB。 */
    @Test
    fun `default nativeHeapWarnKb is 512MB`() {
        val config = MemoryConfig()
        assertEquals(512 * 1024L, config.nativeHeapWarnKb)
    }

    /** 默认启用 Activity 泄漏检测。 */
    @Test
    fun `default leak detectors are enabled`() {
        val config = MemoryConfig()
        assertTrue(config.enableActivityLeak)
        assertTrue(config.enableFragmentLeak)
        assertTrue(config.enableViewModelLeak)
    }

    /** 默认启用 OOM 监控但关闭 Hprof dump。 */
    @Test
    fun `default oom monitor on but hprof dump off`() {
        val config = MemoryConfig()
        assertTrue(config.enableOomMonitor)
        assertFalse(config.enableHprofDump)
    }

    /** 自定义参数应正确覆盖默认值。 */
    @Test
    fun `custom values override defaults`() {
        val config = MemoryConfig(
            foregroundIntervalMs = 5000L,
            javaHeapWarnRatio = 0.6f,
            sampleRate = 0.5f,
            enableNativeMonitor = true
        )
        assertEquals(5000L, config.foregroundIntervalMs)
        assertEquals(0.6f, config.javaHeapWarnRatio, 0.001f)
        assertEquals(0.5f, config.sampleRate, 0.001f)
        assertTrue(config.enableNativeMonitor)
    }

    /** data class copy 应正确工作。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = MemoryConfig()
        val modified = original.copy(foregroundIntervalMs = 1000L)
        assertEquals(1000L, modified.foregroundIntervalMs)
        assertEquals(original.backgroundIntervalMs, modified.backgroundIntervalMs)
    }
}
