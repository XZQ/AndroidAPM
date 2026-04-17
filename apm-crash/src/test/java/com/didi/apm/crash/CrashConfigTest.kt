package com.didi.apm.crash

import org.junit.Assert.*
import org.junit.Test

/**
 * CrashConfig 默认值测试。
 * 验证崩溃模块配置项默认值正确。
 */
class CrashConfigTest {

    /** 默认开启 Java 崩溃捕获。 */
    @Test
    fun `default enableJavaCrash is true`() {
        val config = CrashConfig()
        assertTrue(config.enableJavaCrash)
    }

    /** 默认关闭 Native 崩溃监控。 */
    @Test
    fun `default enableNativeCrash is false`() {
        val config = CrashConfig()
        assertFalse(config.enableNativeCrash)
    }

    /** 默认堆栈最大长度为 4000。 */
    @Test
    fun `default maxStackTraceLength is 4000`() {
        val config = CrashConfig()
        assertEquals(4000, config.maxStackTraceLength)
    }

    /** 自定义参数应正确覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = CrashConfig(
            enableJavaCrash = false,
            enableNativeCrash = true,
            maxStackTraceLength = 8000
        )
        assertFalse(config.enableJavaCrash)
        assertTrue(config.enableNativeCrash)
        assertEquals(8000, config.maxStackTraceLength)
    }
}
