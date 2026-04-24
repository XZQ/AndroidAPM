package com.apm.model

import org.junit.Assert.*
import org.junit.Test

/**
 * ApmPriority 枚举测试。
 * 验证枚举值正确性、默认值行为、序列化格式。
 */
class ApmPriorityTest {

    /** 枚举应有 4 个值，从 LOW(0) 到 CRITICAL(3)。 */
    @Test
    fun `enum has four values with correct ordering`() {
        val values = ApmPriority.values()
        assertEquals(4, values.size)
        // 按 value 升序排列
        assertEquals(ApmPriority.LOW, values[0])
        assertEquals(ApmPriority.NORMAL, values[1])
        assertEquals(ApmPriority.HIGH, values[2])
        assertEquals(ApmPriority.CRITICAL, values[3])
    }

    /** 每个 level 的 value 应正确映射。 */
    @Test
    fun `enum values have correct integer mapping`() {
        assertEquals(0, ApmPriority.LOW.value)
        assertEquals(1, ApmPriority.NORMAL.value)
        assertEquals(2, ApmPriority.HIGH.value)
        assertEquals(3, ApmPriority.CRITICAL.value)
    }

    /** CRITICAL 应大于 HIGH，HIGH 应大于 NORMAL，NORMAL 应大于 LOW。 */
    @Test
    fun `priority ordering is correct`() {
        assertTrue(ApmPriority.CRITICAL.value > ApmPriority.HIGH.value)
        assertTrue(ApmPriority.HIGH.value > ApmPriority.NORMAL.value)
        assertTrue(ApmPriority.NORMAL.value > ApmPriority.LOW.value)
    }

    /** ApmEvent 默认优先级应为 NORMAL。 */
    @Test
    fun `event default priority is NORMAL`() {
        val event = ApmEvent(module = "test", name = "event")
        assertEquals(ApmPriority.NORMAL, event.priority)
    }

    /** ApmEvent 可显式指定优先级。 */
    @Test
    fun `event priority can be explicitly set`() {
        val event = ApmEvent(
            module = "crash",
            name = "java_crash",
            priority = ApmPriority.CRITICAL
        )
        assertEquals(ApmPriority.CRITICAL, event.priority)
    }

    /** line protocol 输出应包含 priority 字段。 */
    @Test
    fun `toLineProtocol includes priority`() {
        val event = ApmEvent(
            module = "test",
            name = "event",
            priority = ApmPriority.HIGH
        )
        val result = event.toLineProtocol()
        assertTrue("Should contain priority=HIGH", result.contains("priority=HIGH"))
    }

    /** 不同优先级的事件应产生不同的 line protocol 输出。 */
    @Test
    fun `different priorities produce different line protocol`() {
        val low = ApmEvent(module = "test", name = "e", priority = ApmPriority.LOW)
        val critical = ApmEvent(module = "test", name = "e", priority = ApmPriority.CRITICAL)
        assertNotEquals(low.toLineProtocol(), critical.toLineProtocol())
    }

    /** valueOf 应正确解析枚举名。 */
    @Test
    fun `valueOf returns correct enum`() {
        assertEquals(ApmPriority.LOW, ApmPriority.valueOf("LOW"))
        assertEquals(ApmPriority.NORMAL, ApmPriority.valueOf("NORMAL"))
        assertEquals(ApmPriority.HIGH, ApmPriority.valueOf("HIGH"))
        assertEquals(ApmPriority.CRITICAL, ApmPriority.valueOf("CRITICAL"))
    }
}
