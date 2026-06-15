package com.apm.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Reversible binary codec used by the persistent event outbox.
 *
 * Field values are normalized to strings because both supported wire formats
 * already serialize arbitrary values through [Any.toString].
 */
object ApmEventCodec {

    /**
     * Encodes an event into the durable storage format.
     *
     * @param event event to encode
     * @return versioned binary payload
     */
    fun encode(event: ApmEvent): ByteArray {
        val buffer = ByteArrayOutputStream()
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
            output.writeStringMap(event.fields.mapValues { (_, value) -> value?.toString().orEmpty() })
            output.writeStringMap(event.globalContext)
            output.writeStringMap(event.extras)
        }
        return buffer.toByteArray().also { payload ->
            require(payload.size <= MAX_PAYLOAD_BYTES) {
                "APM event payload exceeds $MAX_PAYLOAD_BYTES bytes"
            }
        }
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
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val version = input.readInt()
            require(version == FORMAT_VERSION) { "Unsupported APM event format version: $version" }
            ApmEvent(
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
                fields = input.readStringMap(),
                globalContext = input.readStringMap(),
                extras = input.readStringMap()
            )
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
        val bytes = ByteArray(size)
        readFully(bytes)
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
        return buildMap(size) {
            repeat(size) {
                put(readString(), readString())
            }
        }
    }

    /** Current durable payload format version. */
    private const val FORMAT_VERSION = 1

    /** Maximum accepted encoded event size. */
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    /** Maximum accepted UTF-8 string size. */
    private const val MAX_STRING_BYTES = 1024 * 1024

    /** Maximum entries accepted in each event map. */
    private const val MAX_MAP_ENTRIES = 4096

    /** Encoded state for a null boolean. */
    private const val BOOLEAN_NULL = 0

    /** Encoded state for false. */
    private const val BOOLEAN_FALSE = 1

    /** Encoded state for true. */
    private const val BOOLEAN_TRUE = 2
}
