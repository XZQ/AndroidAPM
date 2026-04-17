package com.didi.apm.slowmethod

import org.junit.Assert.*
import org.junit.Test

/**
 * SlowMethodConfig 默认值测试。
 */
class SlowMethodConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableSlowMethod is true`() {
        val config = SlowMethodConfig()
        assertTrue(config.enableSlowMethod)
    }

    /** 默认阈值 300ms。 */
    @Test
    fun `default thresholdMs is 300`() {
        val config = SlowMethodConfig()
        assertEquals(300L, config.thresholdMs)
    }

    /** 默认严重阈值 800ms。 */
    @Test
    fun `default severeThresholdMs is 800`() {
        val config = SlowMethodConfig()
        assertEquals(800L, config.severeThresholdMs)
    }

    /** 默认包含堆栈。 */
    @Test
    fun `default includeStackTrace is true`() {
        val config = SlowMethodConfig()
        assertTrue(config.includeStackTrace)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = SlowMethodConfig(thresholdMs = 500L, severeThresholdMs = 1000L)
        assertEquals(500L, config.thresholdMs)
        assertEquals(1000L, config.severeThresholdMs)
    }
}
