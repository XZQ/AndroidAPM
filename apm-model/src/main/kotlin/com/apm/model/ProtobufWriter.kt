package com.apm.model

import java.io.ByteArrayOutputStream

/**
 * Protobuf wire format 写入器。
 *
 * 直接操作 protobuf wire format 字节流，零外部依赖。
 * 支持 proto3 常用类型：varint、length-delimited、bool。
 *
 * 编码规则：
 * - Tag = (fieldNumber << 3) | wireType
 * - Varint: 变长整数，每字节 7 位有效 + 1 位继续标志
 * - Length-delimited: varint 长度前缀 + payload
 * - Map: repeated { key = 1; value = 2 } message
 *
 * 写入的字节流可被标准 protobuf 库（Java/C++/Go/Python）反序列化。
 */
internal class ProtobufWriter(
    /** 底层字节输出流。 */
    private val stream: ByteArrayOutputStream
) {

    /**
     * 写入 int64 字段（varint 编码）。
     *
     * @param fieldNumber proto 字段编号
     * @param value 字段值
     */
    fun writeInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarint64(value)
    }

    /**
     * 写入 string 字段（length-delimited 编码）。
     *
     * @param fieldNumber proto 字段编号
     * @param value UTF-8 字符串
     */
    fun writeString(fieldNumber: Int, value: String) {
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint32(bytes.size)
        stream.write(bytes)
    }

    /**
     * 写入 bool 字段（varint 编码，0 = false, 1 = true）。
     *
     * @param fieldNumber proto 字段编号
     * @param value 布尔值
     */
    fun writeBool(fieldNumber: Int, value: Boolean) {
        writeTag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarint32(if (value) 1 else 0)
    }

    /**
     * 写入 string → string map entry。
     *
     * protobuf map 编码规则：
     * map<string, string> field = N → repeated { string key = 1; string value = 2 }
     * 即每个 entry 是一个 length-delimited 的嵌套 message。
     *
     * @param fieldNumber map 字段编号
     * @param key map 键
     * @param value map 值
     */
    fun writeStringMapEntry(fieldNumber: Int, key: String, value: String) {
        // 预计算 entry payload 大小，写入 length 前缀
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)

        // entry payload = tag(key) + varint(len) + bytes + tag(value) + varint(len) + bytes
        var entrySize = 0
        entrySize += tagEncodedSize(MAP_ENTRY_KEY) + varintEncodedSize(keyBytes.size) + keyBytes.size
        entrySize += tagEncodedSize(MAP_ENTRY_VALUE) + varintEncodedSize(valueBytes.size) + valueBytes.size

        // 外层：tag + length + entry payload
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint32(entrySize)

        // entry 内部：key (field 1) + value (field 2)
        writeTag(MAP_ENTRY_KEY, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint32(keyBytes.size)
        stream.write(keyBytes)

        writeTag(MAP_ENTRY_VALUE, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint32(valueBytes.size)
        stream.write(valueBytes)
    }

    /** 刷新底层输出流。 */
    fun flush() {
        stream.flush()
    }

    // --- Wire format primitives ---

    /**
     * 写入 tag 字节。
     * tag = (fieldNumber << 3) | wireType
     */
    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint32((fieldNumber shl TAG_SHIFT) or wireType)
    }

    /**
     * 写入 32-bit varint（可变长整数）。
     * 每字节 7 位有效数据 + 1 位继续标志 (MSB)。
     */
    private fun writeVarint32(value: Int) {
        var v = value
        // 低 7 位有数据且还有更高位时，写入带继续标志的字节
        while (v > VARINT_MAX_7BITS) {
            stream.write((v and MASK_7BITS) or VARINT_CONTINUE_FLAG)
            v = v ushr VARINT_BIT_SHIFT
        }
        // 最后一个字节不带继续标志
        stream.write(v)
    }

    /**
     * 写入 64-bit varint（可变长整数）。
     * 与 writeVarint32 逻辑相同，但支持 64 位值。
     */
    private fun writeVarint64(value: Long) {
        var v = value
        while (v > VARINT_MAX_7BITS_LONG) {
            stream.write((v.toInt() and MASK_7BITS) or VARINT_CONTINUE_FLAG)
            v = v ushr VARINT_BIT_SHIFT
        }
        stream.write(v.toInt())
    }

    /**
     * 计算 tag 编码后的字节数。
     * tag 本身是一个 varint，大小取决于字段编号。
     */
    private fun tagEncodedSize(fieldNumber: Int): Int {
        return varintEncodedSize((fieldNumber shl TAG_SHIFT) or WIRE_TYPE_LENGTH_DELIMITED)
    }

    /**
     * 计算 varint32 编码后的字节数。
     * 用于预计算 length-delimited 的长度前缀。
     */
    private fun varintEncodedSize(value: Int): Int {
        var size = 0
        var v = value
        while (v > VARINT_MAX_7BITS) {
            size++
            v = v ushr VARINT_BIT_SHIFT
        }
        // 最后一个字节
        return size + 1
    }

    companion object {
        // --- Protobuf wire types (proto3 spec) ---

        /** Varint：int32, int64, uint32, uint64, sint32, sint64, bool, enum。 */
        private const val WIRE_TYPE_VARINT = 0

        /** Length-delimited：string, bytes, embedded messages, packed repeated。 */
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // --- Varint encoding constants ---

        /** 7 位掩码：低 7 位有效数据。 */
        private const val MASK_7BITS = 0x7F

        /** 继续标志：MSB = 1 表示后面还有更多字节。 */
        private const val VARINT_CONTINUE_FLAG = 0x80

        /** 7 位最大值（int）。 */
        private const val VARINT_MAX_7BITS = 0x7F

        /** 7 位最大值（long）。 */
        private const val VARINT_MAX_7BITS_LONG = 0x7FL

        /** Varint 每字节移位数。 */
        private const val VARINT_BIT_SHIFT = 7

        /** Tag 中字段编号的位移。 */
        private const val TAG_SHIFT = 3

        // --- Map entry field numbers (proto spec: key=1, value=2) ---

        /** Map entry 内部 key 字段编号（proto 规范固定为 1）。 */
        private const val MAP_ENTRY_KEY = 1

        /** Map entry 内部 value 字段编号（proto 规范固定为 2）。 */
        private const val MAP_ENTRY_VALUE = 2
    }
}
