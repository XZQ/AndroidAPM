package com.didi.apm.core

import org.junit.Assert.*
import org.junit.Test

/**
 * ApmConfig 全局配置测试。
 * 验证默认值和自定义参数覆盖。
 */
class ApmConfigTest {

    /** 默认 endpoint 为空字符串。 */
    @Test
    fun `default endpoint is empty`() {
        val config = ApmConfig()
        assertEquals("", config.endpoint)
    }

    /** 默认开启调试日志。 */
    @Test
    fun `default debugLogging is true`() {
        val config = ApmConfig()
        assertTrue(config.debugLogging)
    }

    /** 默认进程策略为主进程。 */
    @Test
    fun `default processStrategy is main only`() {
        val config = ApmConfig()
        assertEquals(ProcessStrategy.MAIN_PROCESS_ONLY, config.processStrategy)
    }

    /** 默认限流每窗口 10 条。 */
    @Test
    fun `default rateLimitEventsPerWindow is 10`() {
        val config = ApmConfig()
        assertEquals(10, config.rateLimitEventsPerWindow)
    }

    /** 默认限流窗口 60 秒。 */
    @Test
    fun `default rateLimitWindowMs is 60 seconds`() {
        val config = ApmConfig()
        assertEquals(60_000L, config.rateLimitWindowMs)
    }

    /** 默认最大重试 3 次。 */
    @Test
    fun `default maxRetries is 3`() {
        val config = ApmConfig()
        assertEquals(3, config.maxRetries)
    }

    /** 默认重试基础延迟 1 秒。 */
    @Test
    fun `default retryBaseDelayMs is 1 second`() {
        val config = ApmConfig()
        assertEquals(1000L, config.retryBaseDelayMs)
    }

    /** 默认开启上传重试。 */
    @Test
    fun `default enableRetry is true`() {
        val config = ApmConfig()
        assertTrue(config.enableRetry)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = ApmConfig(
            endpoint = "https://apm.example.com",
            debugLogging = false,
            processStrategy = ProcessStrategy.ALL_PROCESSES,
            rateLimitEventsPerWindow = 50,
            rateLimitWindowMs = 120_000L
        )
        assertEquals("https://apm.example.com", config.endpoint)
        assertFalse(config.debugLogging)
        assertEquals(ProcessStrategy.ALL_PROCESSES, config.processStrategy)
        assertEquals(50, config.rateLimitEventsPerWindow)
        assertEquals(120_000L, config.rateLimitWindowMs)
    }

    /** ProcessStrategy 枚举完整性。 */
    @Test
    fun `processStrategy enum has two values`() {
        assertEquals(2, ProcessStrategy.values().size)
    }
}
