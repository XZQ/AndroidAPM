package com.apm.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Reversible binary codec used by the persistent event outbox.
 *
 * Version 3 preserves supported scalar field types across durable storage while
 * legacy version 1/2 payloads remain readable with their original string semantics.
 * Unsupported arbitrary objects are still reduced to [Any.toString] instead of
 * introducing unsafe object serialization into the SDK contract.
 */
object ApmEventCodec {

    /**
     * Encodes an event into the durable storage format.
     *
     * @param event event to encode
     * @return versioned binary payload
     */
    fun encode(event: ApmEvent): ByteArray {
        val buffer = BoundedByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeLong(event.timestamp)
            output.writeString(event.module)
            output.writeString(event.name)
            output.writeString(event.kind.name)
            output.writeString(event.severity.name)
            output.writeString(event.priority.name)
            output.writeString(event.processName)
            output.writeString(event.threadName)
            output.writeNullableString(event.scene)
            output.writeNullableBoolean(event.foreground)
            output.writeTypedMap(event.fields)
            output.writeStringMap(event.globalContext)
            output.writeStringMap(event.extras)
            // Version 2+ appends identity so every version-1 field keeps its original order.
            output.writeString(event.eventId)
        }
        return buffer.toByteArray()
    }

    /**
     * Decodes an event previously produced by [encode].
     *
     * @param payload durable binary payload
     * @return decoded event
     * @throws IllegalArgumentException when the payload is malformed or unsupported
     */
    fun decode(payload: ByteArray): ApmEvent {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "APM event payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val version = input.readInt()
                require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
                    "Unsupported APM event format version: $version"
                }
                val event = ApmEvent(
                    timestamp = input.readLong(),
                    module = input.readString(),
                    name = input.readString(),
                    kind = enumValueOrDefault(input.readString(), ApmEventKind.METRIC),
                    severity = enumValueOrDefault(input.readString(), ApmSeverity.INFO),
                    priority = enumValueOrDefault(input.readString(), ApmPriority.NORMAL),
                    processName = input.readString(),
                    threadName = input.readString(),
                    scene = input.readNullableString(),
                    foreground = input.readNullableBoolean(),
                    fields = if (version >= FORMAT_VERSION_WITH_TYPED_FIELDS) {
                        input.readTypedMap()
                    } else {
                        // Version 1/2 stored every field value as a string.
                        input.readStringMap()
                    },
                    globalContext = input.readStringMap(),
                    extras = input.readStringMap(),
                    // Legacy rows receive a deterministic ID from their SQLite row during schema migration.
                    eventId = if (version >= FORMAT_VERSION_WITH_EVENT_ID) input.readString() else ""
                )
                require(input.available() == 0) { "Trailing bytes in APM event payload" }
                event
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Malformed APM event payload", error)
        }
    }

    /**
     * Parses an enum value while tolerating data written by a newer producer.
     *
     * @param name serialized enum name
     * @param fallback value used when the name is unknown
     * @return parsed or fallback value
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }

    /**
     * Writes a UTF-8 string with a 32-bit length prefix.
     *
     * @param value string to write
     */
    private fun DataOutputStream.writeString(value: String) {
        require(value.length <= MAX_STRING_BYTES) {
            "APM event string exceeds $MAX_STRING_BYTES characters"
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "APM event string exceeds $MAX_STRING_BYTES bytes"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    /**
     * Reads a bounded UTF-8 string.
     *
     * @return decoded string
     */
    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid APM event string length: $size" }
        require(size <= available()) { "Truncated APM event string" }
        val bytes = ByteArray(size)
        readFully(bytes)
        require(bytes.isValidUtf8()) { "Invalid UTF-8 in APM event string" }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Writes an optional string.
     *
     * @param value optional value
     */
    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) {
            writeString(value)
        }
    }

    /**
     * Reads an optional string.
     *
     * @return optional decoded value
     */
    private fun DataInputStream.readNullableString(): String? {
        return if (readBoolean()) readString() else null
    }

    /**
     * Writes an optional boolean using a compact state byte.
     *
     * @param value optional value
     */
    private fun DataOutputStream.writeNullableBoolean(value: Boolean?) {
        writeByte(
            when (value) {
                null -> BOOLEAN_NULL
                false -> BOOLEAN_FALSE
                true -> BOOLEAN_TRUE
            }
        )
    }

    /**
     * Reads an optional boolean state byte.
     *
     * @return optional boolean
     */
    private fun DataInputStream.readNullableBoolean(): Boolean? {
        return when (val value = readByte().toInt()) {
            BOOLEAN_NULL -> null
            BOOLEAN_FALSE -> false
            BOOLEAN_TRUE -> true
            else -> throw IllegalArgumentException("Invalid nullable boolean value: $value")
        }
    }

    /**
     * Writes a bounded string map.
     *
     * @param values map to write
     */
    private fun DataOutputStream.writeStringMap(values: Map<String, String>) {
        require(values.size <= MAX_MAP_ENTRIES) {
            "APM event map exceeds $MAX_MAP_ENTRIES entries"
        }
        writeInt(values.size)
        for ((key, value) in values) {
            writeString(key)
            writeString(value)
        }
    }

    /**
     * Reads a bounded string map.
     *
     * @return decoded insertion-ordered map
     */
    private fun DataInputStream.readStringMap(): Map<String, String> {
        val size = readInt()
        require(size in 0..MAX_MAP_ENTRIES) { "Invalid APM event map size: $size" }
        require(size <= available() / MIN_STRING_MAP_ENTRY_BYTES) {
            "Truncated APM event string map"
        }
        return buildMap(size) {
            repeat(size) {
                put(readString(), readString())
            }
        }
    }

    /**
     * Writes a bounded field map with one explicit scalar type tag per value.
     *
     * @param values event field values to write
     */
    private fun DataOutputStream.writeTypedMap(values: Map<String, Any?>) {
        require(values.size <= MAX_MAP_ENTRIES) {
            "APM event map exceeds $MAX_MAP_ENTRIES entries"
        }
        writeInt(values.size)
        for ((key, value) in values) {
            // Keys remain bounded UTF-8 strings while values carry their own scalar tag.
            writeString(key)
            writeTypedValue(value)
        }
    }

    /**
     * Reads a bounded insertion-ordered typed field map.
     *
     * @return decoded typed field values
     */
    private fun DataInputStream.readTypedMap(): Map<String, Any?> {
        val size = readInt()
        require(size in 0..MAX_MAP_ENTRIES) { "Invalid APM event map size: $size" }
        require(size <= available() / MIN_TYPED_MAP_ENTRY_BYTES) {
            "Truncated APM event typed map"
        }
        return buildMap(size) {
            repeat(size) {
                // Unknown tags fail the one corrupt event instead of shifting the remaining payload.
                put(readString(), readTypedValue())
            }
        }
    }

    /**
     * Writes one supported scalar or a deterministic string fallback for an arbitrary object.
     *
     * @param value field value to encode
     */
    private fun DataOutputStream.writeTypedValue(value: Any?) {
        when (value) {
            null -> writeByte(FIELD_TYPE_NULL)
            is String -> {
                writeByte(FIELD_TYPE_STRING)
                writeString(value)
            }
            is Boolean -> {
                writeByte(FIELD_TYPE_BOOLEAN)
                writeBoolean(value)
            }
            is Byte -> {
                writeByte(FIELD_TYPE_BYTE)
                writeByte(value.toInt())
            }
            is Short -> {
                writeByte(FIELD_TYPE_SHORT)
                writeShort(value.toInt())
            }
            is Int -> {
                writeByte(FIELD_TYPE_INT)
                writeInt(value)
            }
            is Long -> {
                writeByte(FIELD_TYPE_LONG)
                writeLong(value)
            }
            is Float -> {
                writeByte(FIELD_TYPE_FLOAT)
                writeFloat(value)
            }
            is Double -> {
                writeByte(FIELD_TYPE_DOUBLE)
                writeDouble(value)
            }
            is Char -> {
                writeByte(FIELD_TYPE_CHAR)
                writeChar(value.code)
            }
            is BigInteger -> {
                writeByte(FIELD_TYPE_BIG_INTEGER)
                writeBigNumberText(value.toString())
            }
            is BigDecimal -> {
                writeByte(FIELD_TYPE_BIG_DECIMAL)
                // BigDecimal.toString preserves both numeric value and scale without exponent expansion.
                writeBigNumberText(value.toString())
            }
            else -> {
                // Arbitrary objects retain the historical safe fallback and never trigger deserialization.
                writeByte(FIELD_TYPE_STRING)
                writeString(value.toString())
            }
        }
    }

    /**
     * Reads one scalar value from its durable type tag.
     *
     * @return restored scalar value
     */
    private fun DataInputStream.readTypedValue(): Any? {
        return when (val type = readUnsignedByte()) {
            FIELD_TYPE_NULL -> null
            FIELD_TYPE_STRING -> readString()
            FIELD_TYPE_BOOLEAN -> readStrictBoolean()
            FIELD_TYPE_BYTE -> readByte()
            FIELD_TYPE_SHORT -> readShort()
            FIELD_TYPE_INT -> readInt()
            FIELD_TYPE_LONG -> readLong()
            FIELD_TYPE_FLOAT -> readFloat()
            FIELD_TYPE_DOUBLE -> readDouble()
            FIELD_TYPE_CHAR -> readChar()
            FIELD_TYPE_BIG_INTEGER -> BigInteger(readBigNumberText())
            FIELD_TYPE_BIG_DECIMAL -> BigDecimal(readBigNumberText())
            else -> throw IllegalArgumentException("Unsupported APM field type tag: $type")
        }
    }

    /**
     * Writes bounded decimal text for arbitrary-precision numbers.
     *
     * @param value canonical decimal representation
     */
    private fun DataOutputStream.writeBigNumberText(value: String) {
        require(value.length <= MAX_BIG_NUMBER_TEXT_CHARACTERS) {
            "APM big-number field exceeds $MAX_BIG_NUMBER_TEXT_CHARACTERS characters"
        }
        writeString(value)
    }

    /**
     * Reads bounded decimal text before invoking an arbitrary-precision parser.
     *
     * @return bounded canonical decimal representation
     */
    private fun DataInputStream.readBigNumberText(): String {
        val value = readString()
        require(value.length <= MAX_BIG_NUMBER_TEXT_CHARACTERS) {
            "APM big-number field exceeds $MAX_BIG_NUMBER_TEXT_CHARACTERS characters"
        }
        return value
    }

    /**
     * Reads the only two valid boolean bytes instead of accepting any non-zero corruption as true.
     *
     * @return decoded boolean value
     */
    private fun DataInputStream.readStrictBoolean(): Boolean {
        return when (val value = readUnsignedByte()) {
            TYPED_BOOLEAN_FALSE -> false
            TYPED_BOOLEAN_TRUE -> true
            else -> throw IllegalArgumentException("Invalid typed boolean value: $value")
        }
    }

    /** Returns true only for shortest-form Unicode scalar UTF-8 without surrogate code points. */
    private fun ByteArray.isValidUtf8(): Boolean {
        var index = 0
        while (index < size) {
            val first = this[index].toInt() and BYTE_MASK
            when {
                first <= ASCII_MAX -> index += 1
                first in UTF8_TWO_BYTE_MIN..UTF8_TWO_BYTE_MAX -> {
                    if (!hasContinuation(index + 1)) return false
                    index += 2
                }
                first == UTF8_THREE_BYTE_LOW_PREFIX -> {
                    if (!hasByteIn(index + 1, UTF8_THREE_BYTE_LOW_SECOND_MIN, CONTINUATION_MAX) ||
                        !hasContinuation(index + 2)
                    ) {
                        return false
                    }
                    index += 3
                }
                first in UTF8_THREE_BYTE_GENERAL_MIN..UTF8_THREE_BYTE_GENERAL_MAX ||
                    first in UTF8_THREE_BYTE_GENERAL_HIGH_MIN..UTF8_THREE_BYTE_GENERAL_HIGH_MAX -> {
                    if (!hasContinuation(index + 1) || !hasContinuation(index + 2)) return false
                    index += 3
                }
                first == UTF8_THREE_BYTE_SURROGATE_PREFIX -> {
                    if (!hasByteIn(index + 1, CONTINUATION_MIN, UTF8_SURROGATE_SECOND_MAX) ||
                        !hasContinuation(index + 2)
                    ) {
                        return false
                    }
                    index += 3
                }
                first == UTF8_FOUR_BYTE_LOW_PREFIX -> {
                    if (!hasByteIn(index + 1, UTF8_FOUR_BYTE_LOW_SECOND_MIN, CONTINUATION_MAX) ||
                        !hasContinuation(index + 2) ||
                        !hasContinuation(index + 3)
                    ) {
                        return false
                    }
                    index += 4
                }
                first in UTF8_FOUR_BYTE_GENERAL_MIN..UTF8_FOUR_BYTE_GENERAL_MAX -> {
                    if (!hasContinuation(index + 1) ||
                        !hasContinuation(index + 2) ||
                        !hasContinuation(index + 3)
                    ) {
                        return false
                    }
                    index += 4
                }
                first == UTF8_FOUR_BYTE_HIGH_PREFIX -> {
                    if (!hasByteIn(index + 1, CONTINUATION_MIN, UTF8_FOUR_BYTE_HIGH_SECOND_MAX) ||
                        !hasContinuation(index + 2) ||
                        !hasContinuation(index + 3)
                    ) {
                        return false
                    }
                    index += 4
                }
                else -> return false
            }
        }
        return true
    }

    /** Returns whether [index] contains one UTF-8 continuation byte. */
    private fun ByteArray.hasContinuation(index: Int): Boolean {
        return hasByteIn(index, CONTINUATION_MIN, CONTINUATION_MAX)
    }

    /** Returns whether [index] exists and its unsigned byte is inside the requested range. */
    private fun ByteArray.hasByteIn(index: Int, minimum: Int, maximum: Int): Boolean {
        if (index >= size) return false
        return (this[index].toInt() and BYTE_MASK) in minimum..maximum
    }

    /** Byte-array output that rejects oversized payloads before expanding its backing buffer. */
    private class BoundedByteArrayOutputStream : ByteArrayOutputStream() {
        /** Writes one byte only while the durable payload budget has capacity. */
        override fun write(value: Int) {
            require(count < MAX_PAYLOAD_BYTES) {
                "APM event payload exceeds $MAX_PAYLOAD_BYTES bytes"
            }
            super.write(value)
        }

        /** Writes one range only when it fits completely inside the durable payload budget. */
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= MAX_PAYLOAD_BYTES - count) {
                "APM event payload exceeds $MAX_PAYLOAD_BYTES bytes"
            }
            super.write(bytes, offset, length)
        }
    }

    /** Current durable payload format version. */
    private const val FORMAT_VERSION = 3

    /** Oldest durable format still accepted. */
    private const val LEGACY_FORMAT_VERSION = 1

    /** First durable format containing the appended event identity. */
    private const val FORMAT_VERSION_WITH_EVENT_ID = 2

    /** First durable format preserving supported scalar field types. */
    private const val FORMAT_VERSION_WITH_TYPED_FIELDS = 3

    /** Maximum accepted encoded event size. */
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    /** Maximum accepted UTF-8 string size. */
    private const val MAX_STRING_BYTES = 1024 * 1024

    /** Maximum decimal characters parsed into one arbitrary-precision number. */
    private const val MAX_BIG_NUMBER_TEXT_CHARACTERS = 4096

    /** Maximum entries accepted in each event map. */
    private const val MAX_MAP_ENTRIES = 4096

    /** Minimum encoded bytes for one string-map key/value pair: two empty length prefixes. */
    private const val MIN_STRING_MAP_ENTRY_BYTES = 8

    /** Minimum encoded bytes for one typed-map entry: empty key prefix plus null tag. */
    private const val MIN_TYPED_MAP_ENTRY_BYTES = 5

    /** Unsigned-byte and strict UTF-8 validation constants. */
    private const val BYTE_MASK = 0xFF
    private const val ASCII_MAX = 0x7F
    private const val CONTINUATION_MIN = 0x80
    private const val CONTINUATION_MAX = 0xBF
    private const val UTF8_TWO_BYTE_MIN = 0xC2
    private const val UTF8_TWO_BYTE_MAX = 0xDF
    private const val UTF8_THREE_BYTE_LOW_PREFIX = 0xE0
    private const val UTF8_THREE_BYTE_LOW_SECOND_MIN = 0xA0
    private const val UTF8_THREE_BYTE_GENERAL_MIN = 0xE1
    private const val UTF8_THREE_BYTE_GENERAL_MAX = 0xEC
    private const val UTF8_THREE_BYTE_SURROGATE_PREFIX = 0xED
    private const val UTF8_SURROGATE_SECOND_MAX = 0x9F
    private const val UTF8_THREE_BYTE_GENERAL_HIGH_MIN = 0xEE
    private const val UTF8_THREE_BYTE_GENERAL_HIGH_MAX = 0xEF
    private const val UTF8_FOUR_BYTE_LOW_PREFIX = 0xF0
    private const val UTF8_FOUR_BYTE_LOW_SECOND_MIN = 0x90
    private const val UTF8_FOUR_BYTE_GENERAL_MIN = 0xF1
    private const val UTF8_FOUR_BYTE_GENERAL_MAX = 0xF3
    private const val UTF8_FOUR_BYTE_HIGH_PREFIX = 0xF4
    private const val UTF8_FOUR_BYTE_HIGH_SECOND_MAX = 0x8F

    /** Encoded state for a null boolean. */
    private const val BOOLEAN_NULL = 0

    /** Encoded state for false. */
    private const val BOOLEAN_FALSE = 1

    /** Encoded state for true. */
    private const val BOOLEAN_TRUE = 2

    /** Typed-field tag: null. */
    private const val FIELD_TYPE_NULL = 0

    /** Typed-field tag: UTF-8 string or unsupported-object fallback. */
    private const val FIELD_TYPE_STRING = 1

    /** Typed-field tag: boolean. */
    private const val FIELD_TYPE_BOOLEAN = 2

    /** Typed-field tag: signed byte. */
    private const val FIELD_TYPE_BYTE = 3

    /** Typed-field tag: signed short. */
    private const val FIELD_TYPE_SHORT = 4

    /** Typed-field tag: signed int. */
    private const val FIELD_TYPE_INT = 5

    /** Typed-field tag: signed long. */
    private const val FIELD_TYPE_LONG = 6

    /** Typed-field tag: IEEE-754 float. */
    private const val FIELD_TYPE_FLOAT = 7

    /** Typed-field tag: IEEE-754 double. */
    private const val FIELD_TYPE_DOUBLE = 8

    /** Typed-field tag: UTF-16 code unit. */
    private const val FIELD_TYPE_CHAR = 9

    /** Typed-field tag: arbitrary-precision integer encoded as bounded decimal text. */
    private const val FIELD_TYPE_BIG_INTEGER = 10

    /** Typed-field tag: arbitrary-precision decimal encoded as bounded canonical text. */
    private const val FIELD_TYPE_BIG_DECIMAL = 11

    /** Valid encoded typed boolean state: false. */
    private const val TYPED_BOOLEAN_FALSE = 0

    /** Valid encoded typed boolean state: true. */
    private const val TYPED_BOOLEAN_TRUE = 1
}
