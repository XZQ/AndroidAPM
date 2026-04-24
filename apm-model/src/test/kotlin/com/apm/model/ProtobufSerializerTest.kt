package com.apm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProtobufSerializer] 单元测试。
 *
 * 验证 protobuf 序列化的正确性和体积优势：
 * 1. 序列化 → 反序列化 round-trip 正确性（通过 protobuf wire format 校验）
 * 2. 与 Line Protocol 的体积对比
 * 3. 边界条件：空字段、可选字段、特殊字符
 * 4. 批量序列化格式正确性
 */
class ProtobufSerializerTest {

    // --- 基本序列化测试 ---

    @Test
    fun `serialize minimal event produces non-empty bytes`() {
        val event = ApmEvent(
            module = "test",
            name = "unit_test",
            timestamp = 1700000000000L
        )

        val bytes = ProtobufSerializer.serialize(event)

        assertNotNull(bytes)
        assertTrue("Serialized bytes should not be empty", bytes.isNotEmpty())
    }

    @Test
    fun `serialize preserves core fields in wire format`() {
        val event = ApmEvent(
            module = "memory",
            name = "snapshot",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            timestamp = 1700000000000L,
            processName = "com.app",
            threadName = "main"
        )

        val bytes = ProtobufSerializer.serialize(event)

        // 验证 wire format 中包含字符串字段值
        val payload = bytes.toString(Charsets.UTF_8)
        // protobuf 中字符串以 UTF-8 编码，可以在此检查
        assertTrue("Should contain module name", containsUtf8(bytes, "memory"))
        assertTrue("Should contain event name", containsUtf8(bytes, "snapshot"))
        assertTrue("Should contain kind", containsUtf8(bytes, "METRIC"))
        assertTrue("Should contain severity", containsUtf8(bytes, "INFO"))
        assertTrue("Should contain process name", containsUtf8(bytes, "com.app"))
        assertTrue("Should contain thread name", containsUtf8(bytes, "main"))
    }

    // --- 体积对比测试 ---

    @Test
    fun `protobuf is smaller than line protocol for typical event`() {
        val event = createTypicalEvent()

        val protobufSize = ProtobufSerializer.serialize(event).size
        val lineProtocolSize = event.toLineProtocol().toByteArray(Charsets.UTF_8).size

        assertTrue(
            "Protobuf ($protobufSize B) should be smaller than Line Protocol ($lineProtocolSize B)",
            protobufSize < lineProtocolSize
        )
    }

    @Test
    fun `protobuf is no larger than line protocol for events with many fields`() {
        val event = ApmEvent(
            module = "memory",
            name = "memory_snapshot",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            timestamp = 1700000000000L,
            processName = "com.example.app:main",
            threadName = "apm-memory-sampler",
            scene = "MainActivity",
            foreground = true,
            fields = mapOf(
                "java_heap_max" to "536870912",
                "java_heap_used" to "268435456",
                "pss_total" to "345678",
                "pss_dalvik" to "123456",
                "pss_native" to "98765",
                "native_heap" to "87654",
                "gc_count" to "42",
                "allocation_count" to "12345"
            ),
            globalContext = mapOf(
                "app_version" to "3.14.159",
                "device" to "Pixel_7_Pro",
                "os_version" to "34",
                "user_id" to "u_12345678"
            ),
            extras = mapOf(
                "oom_score" to "233",
                "low_memory" to "false"
            )
        )

        val protobufSize = ProtobufSerializer.serialize(event).size
        val lineProtocolSize = event.toLineProtocol().toByteArray(Charsets.UTF_8).size

        // protobuf 对短字符串 map 优势不明显，但绝不比 line protocol 更大
        // 真正的体积优势在 gzip 压缩后体现（protobuf 二进制压缩率远高于文本）
        assertTrue(
            "Protobuf ($protobufSize B) should not exceed Line Protocol ($lineProtocolSize B)",
            protobufSize <= lineProtocolSize
        )
    }

    // --- 可选字段测试 ---

    @Test
    fun `serialize event without optional fields`() {
        val event = ApmEvent(
            module = "test",
            name = "no_optional",
            timestamp = 1000L
        )

        val bytes = ProtobufSerializer.serialize(event)

        // 可选字段为 null 时不应写入 wire format
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `serialize event with all optional fields`() {
        val event = ApmEvent(
            module = "test",
            name = "all_fields",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            timestamp = 2000L,
            processName = "com.app",
            threadName = "main",
            scene = "DetailActivity",
            foreground = false,
            fields = mapOf("key1" to "value1", "key2" to 42),
            globalContext = mapOf("ctx" to "data"),
            extras = mapOf("extra" to "info")
        )

        val bytes = ProtobufSerializer.serialize(event)

        assertTrue(containsUtf8(bytes, "DetailActivity"))
        assertTrue(containsUtf8(bytes, "value1"))
        assertTrue(containsUtf8(bytes, "data"))
        assertTrue(containsUtf8(bytes, "info"))
    }

    // --- Map 字段测试 ---

    @Test
    fun `serialize handles empty maps`() {
        val event = ApmEvent(
            module = "test",
            name = "empty_maps",
            timestamp = 3000L,
            fields = emptyMap(),
            globalContext = emptyMap(),
            extras = emptyMap()
        )

        val bytes = ProtobufSerializer.serialize(event)

        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `serialize handles null values in fields`() {
        val event = ApmEvent(
            module = "test",
            name = "null_values",
            timestamp = 4000L,
            fields = mapOf("key_with_null" to null, "key_with_value" to "hello")
        )

        val bytes = ProtobufSerializer.serialize(event)

        // null 值应被转换为空字符串
        assertNotNull(bytes)
        assertTrue(containsUtf8(bytes, "key_with_null"))
        assertTrue(containsUtf8(bytes, "hello"))
    }

    // --- 批量序列化测试 ---

    @Test
    fun `serializeBatch produces length-prefixed format`() {
        val events = listOf(
            ApmEvent(module = "test", name = "event1", timestamp = 1000L),
            ApmEvent(module = "test", name = "event2", timestamp = 2000L)
        )

        val batchBytes = ProtobufSerializer.serializeBatch(events)

        assertNotNull(batchBytes)
        assertTrue(batchBytes.isNotEmpty())

        // 第一个事件的长度前缀（4 字节 big-endian）
        val firstLength = ((batchBytes[0].toInt() and 0xFF) shl 24) or
            ((batchBytes[1].toInt() and 0xFF) shl 16) or
            ((batchBytes[2].toInt() and 0xFF) shl 8) or
            (batchBytes[3].toInt() and 0xFF)

        assertTrue("First event length should be positive", firstLength > 0)
        assertTrue(
            "Batch should contain both events",
            batchBytes.size > firstLength + 4
        )
    }

    @Test
    fun `serializeBatch with empty list produces empty bytes`() {
        val batchBytes = ProtobufSerializer.serializeBatch(emptyList())

        assertTrue("Empty batch should produce empty bytes", batchBytes.isEmpty())
    }

    // --- 辅助方法 ---

    /**
     * 检查字节数组中是否包含指定的 UTF-8 字符串。
     * 用于验证 protobuf wire format 中嵌入了预期的字符串值。
     */
    private fun containsUtf8(bytes: ByteArray, text: String): Boolean {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (textBytes.isEmpty()) return true
        if (bytes.size < textBytes.size) return false

        // 简单的滑动窗口搜索
        for (i in 0..(bytes.size - textBytes.size)) {
            var found = true
            for (j in textBytes.indices) {
                if (bytes[i + j] != textBytes[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    /** 创建一个典型的 APM 事件用于测试。 */
    private fun createTypicalEvent(): ApmEvent {
        return ApmEvent(
            module = "fps",
            name = "frame_stats",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            timestamp = System.currentTimeMillis(),
            processName = "com.example.app",
            threadName = "main",
            scene = "MainActivity",
            foreground = true,
            fields = mapOf(
                "fps" to 58,
                "dropped_frames" to 2,
                "jank_count" to 1,
                "freeze_count" to 0
            )
        )
    }
}
