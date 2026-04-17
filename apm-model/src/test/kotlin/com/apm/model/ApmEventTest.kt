package com.apm.model

import org.junit.Assert.*
import org.junit.Test

/**
 * ApmEvent 序列化测试。
 * 验证 line protocol 格式正确性、特殊字符转义、空字段处理。
 */
class ApmEventTest {

    /** 基本序列化：所有标准字段都应出现在输出中。 */
    @Test
    fun `toLineProtocol contains all required fields`() {
        val event = ApmEvent(
            module = "memory",
            name = "snapshot",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "com.example",
            threadName = "main"
        )
        val result = event.toLineProtocol()

        assertTrue("Should contain module", result.contains("module=memory"))
        assertTrue("Should contain name", result.contains("name=snapshot"))
        assertTrue("Should contain kind", result.contains("kind=METRIC"))
        assertTrue("Should contain severity", result.contains("severity=INFO"))
        assertTrue("Should contain process", result.contains("process=com.example"))
        assertTrue("Should contain thread", result.contains("thread=main"))
    }

    /** 管道符 | 应被转义为 /，防止破坏 line protocol 格式。 */
    @Test
    fun `toLineProtocol escapes pipe character`() {
        val event = ApmEvent(module = "test|module", name = "event")
        val result = event.toLineProtocol()

        assertFalse("Should not contain raw pipe", result.contains("module=test|module"))
        assertTrue("Should escape pipe to slash", result.contains("module=test/module"))
    }

    /** 逗号 , 应被转义为 ;。 */
    @Test
    fun `toLineProtocol escapes comma`() {
        val event = ApmEvent(module = "test", name = "event", fields = mapOf("key" to "val,ue"))
        val result = event.toLineProtocol()

        assertFalse("Should not contain raw comma in values", result.contains("val,ue"))
        assertTrue("Should escape comma to semicolon", result.contains("val;ue"))
    }

    /** null 场景字段不应出现在输出中。 */
    @Test
    fun `toLineProtocol omits null optional fields`() {
        val event = ApmEvent(module = "test", name = "event", scene = null, foreground = null)
        val result = event.toLineProtocol()

        assertFalse("Should not contain scene when null", result.contains("scene="))
        assertFalse("Should not contain foreground when null", result.contains("foreground="))
    }

    /** 非空可选字段应出现在输出中。 */
    @Test
    fun `toLineProtocol includes non-null optional fields`() {
        val event = ApmEvent(module = "test", name = "event", scene = "MainActivity", foreground = true)
        val result = event.toLineProtocol()

        assertTrue("Should contain scene", result.contains("scene=MainActivity"))
        assertTrue("Should contain foreground", result.contains("foreground=true"))
    }

    /** fields Map 应按键名字典序排列。 */
    @Test
    fun `toLineProtocol sorts fields alphabetically`() {
        val event = ApmEvent(
            module = "test", name = "event",
            fields = mapOf("zebra" to "1", "alpha" to "2", "middle" to "3")
        )
        val result = event.toLineProtocol()

        val alphaIdx = result.indexOf("alpha=")
        val middleIdx = result.indexOf("middle=")
        val zebraIdx = result.indexOf("zebra=")

        assertTrue("alpha should come before middle", alphaIdx < middleIdx)
        assertTrue("middle should come before zebra", middleIdx < zebraIdx)
    }

    /** 时间戳应有非零默认值。 */
    @Test
    fun `event has non-zero timestamp by default`() {
        val event = ApmEvent(module = "test", name = "event")
        assertTrue("Timestamp should be non-zero", event.timestamp > 0L)
    }

    /** Severity 枚举值完整性检查。 */
    @Test
    fun `severity enum has expected values`() {
        val values = ApmSeverity.values()
        assertEquals(5, values.size)
        assertEquals(ApmSeverity.DEBUG, values[0])
        assertEquals(ApmSeverity.FATAL, values[4])
    }

    /** EventKind 枚举值完整性检查。 */
    @Test
    fun `eventKind enum has expected values`() {
        val values = ApmEventKind.values()
        assertEquals(3, values.size)
    }
}
