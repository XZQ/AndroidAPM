package com.didi.apm.ipc

import org.junit.Assert.*
import org.junit.Test

/**
 * IpcConfig 默认值测试。
 */
class IpcConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableIpcMonitor is true`() {
        val config = IpcConfig()
        assertTrue(config.enableIpcMonitor)
    }

    /** 默认 Binder 阈值 500ms。 */
    @Test
    fun `default binderThresholdMs is 500`() {
        val config = IpcConfig()
        assertEquals(500L, config.binderThresholdMs)
    }

    /** 默认主线程 Binder 阈值 100ms。 */
    @Test
    fun `default mainThreadBinderThresholdMs is 100`() {
        val config = IpcConfig()
        assertEquals(100L, config.mainThreadBinderThresholdMs)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = IpcConfig(binderThresholdMs = 1000L, mainThreadBinderThresholdMs = 200L)
        assertEquals(1000L, config.binderThresholdMs)
        assertEquals(200L, config.mainThreadBinderThresholdMs)
    }
}
