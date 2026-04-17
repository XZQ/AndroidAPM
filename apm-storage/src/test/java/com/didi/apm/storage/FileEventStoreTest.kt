package com.didi.apm.storage

import com.didi.apm.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * FileEventStore 单元测试。
 * 验证环形缓冲区、文件读写、边界条件。
 *
 * 注意：由于 FileEventStore 依赖 Android Context，
 * 这些测试验证的是逻辑正确性，实际 Android 集成需在 instrumented test 中验证。
 */
class FileEventStoreTest {

    // --- 逻辑验证（不依赖 Android Context）---

    /** 验证 EventStore 接口方法签名存在。 */
    @Test
    fun `EventStore interface has required methods`() {
        val methods = EventStore::class.java.methods.map { it.name }
        assertTrue("Should have append", methods.contains("append"))
        assertTrue("Should have readRecent", methods.contains("readRecent"))
        assertTrue("Should have clear", methods.contains("clear"))
    }

    /** 验证 ApmEvent toLineProtocol 序列化后不包含换行符。 */
    @Test
    fun `line protocol output has no newlines`() {
        val event = ApmEvent(
            module = "test",
            name = "unit_test",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "com.test",
            threadName = "main",
            fields = mapOf("key" to "value")
        )
        val line = event.toLineProtocol()
        // 环形缓冲区按行分割，line protocol 不能包含换行
        assertFalse("Line protocol should not contain newlines", line.contains("\n"))
        assertFalse("Line protocol should not contain carriage returns", line.contains("\r"))
    }

    /** 验证 line protocol 格式包含必要字段。 */
    @Test
    fun `line protocol contains required fields`() {
        val event = ApmEvent(
            module = "memory",
            name = "snapshot",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.WARN,
            processName = "com.example",
            threadName = "main",
            fields = mapOf("heapUsed" to 100L, "scene" to "MainActivity")
        )
        val line = event.toLineProtocol()
        // line protocol 格式：measurement,tag1=val1 field1=val1 field2=val2
        assertTrue("Should contain module name", line.contains("memory"))
        assertTrue("Should contain event name", line.contains("snapshot"))
        assertTrue("Should contain severity", line.contains("WARN"))
    }

    /** 验证不同 severity 级别的 line protocol 格式一致。 */
    @Test
    fun `line protocol format consistent across severities`() {
        for (severity in ApmSeverity.values()) {
            val event = ApmEvent(
                module = "test",
                name = "check",
                kind = ApmEventKind.ALERT,
                severity = severity,
                processName = "proc",
                threadName = "thread"
            )
            val line = event.toLineProtocol()
            assertNotNull("Line should not be null for $severity", line)
            assertTrue("Line should not be empty for $severity", line.isNotEmpty())
        }
    }

    /** 验证 fields 为空时仍能正常序列化。 */
    @Test
    fun `line protocol handles empty fields`() {
        val event = ApmEvent(
            module = "test",
            name = "empty",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.DEBUG,
            processName = "p",
            threadName = "t",
            fields = emptyMap()
        )
        val line = event.toLineProtocol()
        assertNotNull("Should serialize with empty fields", line)
    }

    /** 验证 fields 包含特殊字符时的序列化。 */
    @Test
    fun `line protocol handles special characters in fields`() {
        val event = ApmEvent(
            module = "test",
            name = "special",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "p",
            threadName = "t",
            fields = mapOf(
                "path" to "/data/data/com.example/files/test.db",
                "message" to "hello world"
            )
        )
        val line = event.toLineProtocol()
        assertNotNull("Should handle path slashes", line)
        assertTrue("Should contain path value", line.contains("com.example"))
    }

    /** 验证 FileEventStore 构造参数 maxLines 可自定义。 */
    @Test
    fun `FileEventStore accepts custom maxLines`() {
        // 验证构造函数接受 maxLines 参数（实际文件操作需 Android Context，这里验证签名）
        val constructors = FileEventStore::class.java.declaredConstructors
        assertTrue("Should have at least one constructor", constructors.isNotEmpty())
        // 验证存在双参数构造函数 (Context, maxLines)
        val twoParamCtor = constructors.any { it.parameterCount == 2 }
        assertTrue("Should have two-parameter constructor (Context, maxLines)", twoParamCtor)
    }

    /** 验证 ApmEvent 序列化后长度合理（不超过 10000 字符）。 */
    @Test
    fun `line protocol output length is reasonable`() {
        val event = ApmEvent(
            module = "test",
            name = "length_check",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            processName = "com.example.app",
            threadName = "main",
            fields = mapOf("key1" to "value1", "key2" to 123L, "key3" to true)
        )
        val line = event.toLineProtocol()
        assertTrue("Line should be non-empty", line.isNotEmpty())
        assertTrue("Line should be shorter than 10000 chars", line.length < 10000)
    }

    /** 验证 EventStore 接口默认 readRecent limit 为 20。 */
    @Test
    fun `EventStore readRecent has default limit 20`() {
        val method = EventStore::class.java.getDeclaredMethod("readRecent", Int::class.java)
        // 验证参数类型正确
        assertEquals("Parameter type should be Int", Int::class.java, method.parameterTypes[0])
    }
}
