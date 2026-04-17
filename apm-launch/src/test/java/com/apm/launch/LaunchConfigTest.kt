package com.apm.launch

import org.junit.Assert.*
import org.junit.Test

/**
 * LaunchConfig 默认值测试。
 * 验证启动监控配置项默认值正确。
 */
class LaunchConfigTest {

    /** 默认开启冷启动监控。 */
    @Test
    fun `default enableColdStart is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableColdStart)
    }

    /** 默认开启热启动监控。 */
    @Test
    fun `default enableHotStart is true`() {
        val config = LaunchConfig()
        assertTrue(config.enableHotStart)
    }

    /** 默认启动超时为 30 秒。 */
    @Test
    fun `default launchTimeoutMs is 30 seconds`() {
        val config = LaunchConfig()
        assertEquals(30_000L, config.launchTimeoutMs)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = LaunchConfig(
            enableColdStart = false,
            enableHotStart = false,
            launchTimeoutMs = 60_000L
        )
        assertFalse(config.enableColdStart)
        assertFalse(config.enableHotStart)
        assertEquals(60_000L, config.launchTimeoutMs)
    }

    /** data class copy 应正确工作。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = LaunchConfig()
        val modified = original.copy(launchTimeoutMs = 10_000L)
        assertEquals(10_000L, modified.launchTimeoutMs)
        assertTrue(modified.enableColdStart)
        assertTrue(modified.enableHotStart)
    }
}
