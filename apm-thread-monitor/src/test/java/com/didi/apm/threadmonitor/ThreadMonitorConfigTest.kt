package com.didi.apm.threadmonitor

import org.junit.Assert.*
import org.junit.Test

/**
 * ThreadMonitorConfig 默认值测试。
 */
class ThreadMonitorConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableThreadMonitor is true`() {
        val config = ThreadMonitorConfig()
        assertTrue(config.enableThreadMonitor)
    }

    /** 默认线程数量告警阈值 100。 */
    @Test
    fun `default threadCountThreshold is 100`() {
        val config = ThreadMonitorConfig()
        assertEquals(100, config.threadCountThreshold)
    }

    /** 默认同名线程阈值 5。 */
    @Test
    fun `default duplicateThreadThreshold is 5`() {
        val config = ThreadMonitorConfig()
        assertEquals(5, config.duplicateThreadThreshold)
    }

    /** 默认检测间隔 30 秒。 */
    @Test
    fun `default checkIntervalMs is 30 seconds`() {
        val config = ThreadMonitorConfig()
        assertEquals(30_000L, config.checkIntervalMs)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = ThreadMonitorConfig(threadCountThreshold = 200, checkIntervalMs = 60_000L)
        assertEquals(200, config.threadCountThreshold)
        assertEquals(60_000L, config.checkIntervalMs)
    }
}
