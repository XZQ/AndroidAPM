package com.apm.webview

import org.junit.Assert.*
import org.junit.Test

/**
 * WebviewConfig 默认值测试。
 */
class WebviewConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableWebviewMonitor is true`() {
        val config = WebviewConfig()
        assertTrue(config.enableWebviewMonitor)
    }

    /** 默认页面加载阈值 5 秒。 */
    @Test
    fun `default pageLoadThresholdMs is 5 seconds`() {
        val config = WebviewConfig()
        assertEquals(5000L, config.pageLoadThresholdMs)
    }

    /** 默认 JS 执行阈值 2 秒。 */
    @Test
    fun `default jsExecutionThresholdMs is 2 seconds`() {
        val config = WebviewConfig()
        assertEquals(2000L, config.jsExecutionThresholdMs)
    }

    /** 默认白屏阈值 3 秒。 */
    @Test
    fun `default whiteScreenThresholdMs is 3 seconds`() {
        val config = WebviewConfig()
        assertEquals(3000L, config.whiteScreenThresholdMs)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = WebviewConfig(pageLoadThresholdMs = 10_000L, jsExecutionThresholdMs = 5000L)
        assertEquals(10_000L, config.pageLoadThresholdMs)
        assertEquals(5000L, config.jsExecutionThresholdMs)
    }
}
