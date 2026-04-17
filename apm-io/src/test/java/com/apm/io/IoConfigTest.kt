package com.apm.io

import org.junit.Assert.*
import org.junit.Test

/**
 * IoConfig 默认值测试。
 */
class IoConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableIoMonitor is true`() {
        val config = IoConfig()
        assertTrue(config.enableIoMonitor)
    }

    /** 默认主线程 IO 阈值 50ms。 */
    @Test
    fun `default mainThreadIoThresholdMs is 50`() {
        val config = IoConfig()
        assertEquals(50L, config.mainThreadIoThresholdMs)
    }

    /** 默认单次 IO 阈值 500ms。 */
    @Test
    fun `default singleIoThresholdMs is 500`() {
        val config = IoConfig()
        assertEquals(500L, config.singleIoThresholdMs)
    }

    /** 默认大 buffer 阈值 512KB。 */
    @Test
    fun `default largeBufferSize is 512KB`() {
        val config = IoConfig()
        assertEquals(512 * 1024L, config.largeBufferSize)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = IoConfig(mainThreadIoThresholdMs = 100L, singleIoThresholdMs = 1000L)
        assertEquals(100L, config.mainThreadIoThresholdMs)
        assertEquals(1000L, config.singleIoThresholdMs)
    }
}
