package com.apm.crash

import com.apm.model.ApmOccurrenceContext
import com.apm.model.ProtobufSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Complete-or-absent identity in Android's 128-byte process-state summary; never truncates fields. */
internal object ExitOccurrenceSummary {
    /** Android ActivityManager's documented per-process summary limit. */
    internal const val MAX_BYTES = 128
    /** Versioned SDK-owned marker; other components' summaries are treated as unknown. */
    private const val MAGIC = 0x41505831
    /** Five mandatory identity fields, each with one unsigned-byte length. */
    private const val FIELD_COUNT = 5
    /** Fixed marker and field-length overhead. */
    private const val HEADER_BYTES = 4 + FIELD_COUNT

    /** Encodes all identity fields only when both V3 validity and the platform budget hold. */
    fun encode(occurrence: ApmOccurrenceContext): ByteArray? {
        return try {
            ProtobufSerializer.validateOccurrence(occurrence)
            val fields = listOf(
                occurrence.serviceVersion, occurrence.versionCode, occurrence.appBuild,
                occurrence.variant, occurrence.installationId
            ).map { field ->
                // Do not silently replace malformed UTF-16 and manufacture a different identity.
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(field))
                ByteArray(encoded.remaining()).also(encoded::get)
            }
            if (HEADER_BYTES + fields.sumOf { it.size } > MAX_BYTES) return null
            val bytes = ByteArrayOutputStream(MAX_BYTES)
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                fields.forEach { field ->
                    output.writeByte(field.size)
                    output.write(field)
                }
            }
            bytes.toByteArray()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Rejects missing, foreign, truncated, malformed UTF-8, trailing or semantically invalid data. */
    fun decode(bytes: ByteArray?): ApmOccurrenceContext? {
        if (bytes == null || bytes.size !in HEADER_BYTES..MAX_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) return null
                val fields = List(FIELD_COUNT) {
                    val length = input.readUnsignedByte()
                    require(length <= input.available())
                    val field = ByteArray(length)
                    input.readFully(field)
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(field)).toString()
                }
                if (input.available() != 0) return null
                ApmOccurrenceContext(fields[0], fields[1], fields[2], fields[3], fields[4]).also {
                    ProtobufSerializer.validateOccurrence(it)
                }
            }
        } catch (_: Exception) {
            // System records can originate from older SDKs or another owner of the platform slot.
            null
        }
    }
}
