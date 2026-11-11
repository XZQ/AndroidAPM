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

    // --- 优先级字段测试 ---

    @Test
    fun `serialize writes priority enum name for every priority`() {
        // 每个优先级都应以枚举名字符串写入 wire format，与 Line Protocol 字段语义一致
        for (priority in ApmPriority.values()) {
            val event = ApmEvent(
                module = "test",
                name = "priority_case",
                timestamp = 5000L,
                priority = priority
            )

            val bytes = ProtobufSerializer.serialize(event)

            assertTrue(
                "Wire format should contain priority name ${priority.name}",
                containsUtf8(bytes, priority.name)
            )
        }
    }

    @Test
    fun `serialize writes priority under proto field 13`() {
        val event = ApmEvent(
            module = "test",
            name = "priority_tag",
            timestamp = 6000L,
            priority = ApmPriority.CRITICAL
        )

        val bytes = ProtobufSerializer.serialize(event)

        // 字段 13、wire type 2（length-delimited）的 tag 字节为 (13 shl 3) or 2
        val priorityTag = ((PRIORITY_FIELD_NUMBER shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val nameBytes = ApmPriority.CRITICAL.name.toByteArray(Charsets.UTF_8)
        var found = false
        // 在 tag 后应紧跟 varint 长度与枚举名字节
        for (i in 0..(bytes.size - nameBytes.size - 2)) {
            if (bytes[i] == priorityTag &&
                bytes[i + 1].toInt() == nameBytes.size &&
                bytes.copyOfRange(i + 2, i + 2 + nameBytes.size).contentEquals(nameBytes)
            ) {
                found = true
                break
            }
        }
        assertTrue("Priority should be encoded under proto field 13", found)
    }

    /** Stable identity is appended under protobuf field 14. */
    @Test
    fun `serialize writes event identity under proto field 14`() {
        val event = ApmEvent(module = "test", name = "identity", eventId = "event-14")

        val bytes = ProtobufSerializer.serialize(event)

        val identityTag = ((EVENT_ID_FIELD_NUMBER shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val identityBytes = event.eventId.toByteArray(Charsets.UTF_8)
        assertTrue(
            "Event identity should be encoded under proto field 14",
            bytes.indices.any { index ->
                index + identityBytes.size + 1 < bytes.size &&
                    bytes[index] == identityTag &&
                    bytes[index + 1].toInt() == identityBytes.size &&
                    bytes.copyOfRange(index + 2, index + 2 + identityBytes.size).contentEquals(identityBytes)
            }
        )
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

    // --- int64 varint 边界测试 ---

    @Test
    fun `negative timestamps encode as spec-compliant ten-byte varints`() {
        // protobuf 规范要求负 int64 以 64 位补码符号扩展编码为 10 字节 varint；
        // 历史实现只写 1 字节，会吞掉后续字段 tag 使整个消息不可解析或静默篡改时间戳。
        for (timestamp in listOf(-1L, -1000L, -123456789012345L, Long.MIN_VALUE)) {
            val event = ApmEvent(module = "test", name = "negative_ts", timestamp = timestamp)

            val bytes = ProtobufSerializer.serialize(event)

            // 用规范 varint 读取器完整遍历消息：任何编码错误都会导致越界/字段号非法断言失败
            val varintFields = walkEventVarintFields(bytes)
            assertEquals(
                "Timestamp $timestamp must round-trip through a spec-compliant varint reader",
                timestamp,
                varintFields[TIMESTAMP_FIELD_NUMBER]
            )
        }
    }

    @Test
    fun `positive timestamp boundaries still round-trip after varint hardening`() {
        for (timestamp in listOf(0L, 1L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE)) {
            val event = ApmEvent(module = "test", name = "positive_ts", timestamp = timestamp)

            val bytes = ProtobufSerializer.serialize(event)

            val varintFields = walkEventVarintFields(bytes)
            assertEquals(timestamp, varintFields[TIMESTAMP_FIELD_NUMBER])
        }
    }

    @Test
    fun `negative int64 varint always occupies ten bytes`() {
        val event = ApmEvent(module = "test", name = "varint_size", timestamp = -1L)

        val bytes = ProtobufSerializer.serialize(event)

        // 字段 1 的 tag 为单字节 0x08，其后必须是 9 个继续字节 + 1 个终止字节
        assertEquals(0x08, bytes[0].toInt() and 0xFF)
        for (index in 1..9) {
            assertTrue(
                "Negative varint byte $index must carry the continuation flag",
                bytes[index].toInt() and 0x80 != 0
            )
        }
        assertEquals(0x01, bytes[10].toInt() and 0xFF)
    }

    // --- 辅助方法 ---

    /**
     * 按 protobuf 规范读取一个 base-128 varint。
     *
     * @param bytes 输入字节流
     * @param offset 起始偏移
     * @return 解码值与消耗字节数
     */
    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = offset
        while (true) {
            assertTrue("Varint must terminate within the message", index < bytes.size)
            assertTrue("Varint must not exceed 10 bytes", shift < 64)
            val byte = bytes[index].toInt() and 0xFF
            // 第 10 字节只允许贡献最高 1 位，否则不是合法 64 位 varint
            if (shift == 63) {
                assertTrue("Tenth varint byte must be 0 or 1", byte and 0x7F <= 1)
            }
            result = result or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) {
                return result to (index - offset)
            }
            shift += 7
        }
    }

    /**
     * 完整遍历独立事件消息的每个字段，返回 varint 字段编号 → 值。
     * 任何非法字段号、wire type 或长度越界都断言失败，证明整个消息可被标准解析器消费。
     */
    private fun walkEventVarintFields(bytes: ByteArray): Map<Int, Long> {
        val varintFields = mutableMapOf<Int, Long>()
        var index = 0
        while (index < bytes.size) {
            val (tag, tagSize) = readVarint(bytes, index)
            index += tagSize
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            assertTrue("Field number must be positive, got $fieldNumber", fieldNumber > 0)
            when (wireType) {
                0 -> {
                    val (value, valueSize) = readVarint(bytes, index)
                    index += valueSize
                    varintFields[fieldNumber] = value
                }
                2 -> {
                    val (length, lengthSize) = readVarint(bytes, index)
                    index += lengthSize
                    assertTrue(
                        "Length-delimited payload must fit the message",
                        length >= 0 && index + length <= bytes.size
                    )
                    index += length.toInt()
                }
                else -> throw AssertionError("Unexpected wire type $wireType for field $fieldNumber")
            }
        }
        return varintFields
    }

    /**
     * 检查字节数组中是否包含指定的 UTF-8 字符串。
     * 用于验证 protobuf wire format 中嵌入了预期的字符串值。
     */
    private fun containsUtf8(bytes: ByteArray, text: String): Boolean {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (textBytes.isEmpty()) {
            return true
        }
        if (bytes.size < textBytes.size) {
            return false
        }

        // 简单的滑动窗口搜索
        for (i in 0..(bytes.size - textBytes.size)) {
            var found = true
            for (j in textBytes.indices) {
                if (bytes[i + j] != textBytes[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return true
            }
        }
        return false
    }

    companion object {
        /** timestamp 的 proto 字段编号，须与 ProtobufSerializer/apm_event.proto 一致。 */
        private const val TIMESTAMP_FIELD_NUMBER = 1

        /** priority 的 proto 字段编号，须与 ProtobufSerializer/apm_event.proto 一致。 */
        private const val PRIORITY_FIELD_NUMBER = 13

        /** event_id proto field number appended after priority. */
        private const val EVENT_ID_FIELD_NUMBER = 14

        /** protobuf wire type 2：length-delimited（字符串/bytes/嵌套消息）。 */
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2
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
