package com.apm.anr

import org.junit.Assert.*
import org.junit.Test

/**
 * AnrConfig 默认值测试。
 * 验证 ANR 监控配置项默认值正确。
 */
class AnrConfigTest {

    /** 默认检查间隔应为 5 秒。 */
    @Test
    fun `default checkIntervalMs is 5 seconds`() {
        val config = AnrConfig()
        assertEquals(5000L, config.checkIntervalMs)
    }

    /** 默认 ANR 超时阈值应为 5 秒。 */
    @Test
    fun `default anrTimeoutMs is 5 seconds`() {
        val config = AnrConfig()
        assertEquals(5000L, config.anrTimeoutMs)
    }

    /** 默认开启 ANR 监控。 */
    @Test
    fun `default enableAnrMonitor is true`() {
        val config = AnrConfig()
        assertTrue(config.enableAnrMonitor)
    }

    /** 默认堆栈最大长度为 4000。 */
    @Test
    fun `default maxStackTraceLength is 4000`() {
        val config = AnrConfig()
        assertEquals(4000, config.maxStackTraceLength)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = AnrConfig(
            checkIntervalMs = 3000L,
            anrTimeoutMs = 10_000L,
            enableAnrMonitor = false
        )
        assertEquals(3000L, config.checkIntervalMs)
        assertEquals(10_000L, config.anrTimeoutMs)
        assertFalse(config.enableAnrMonitor)
    }
}
