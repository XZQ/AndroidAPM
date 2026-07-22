package com.apm.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the durable event payload contract.
 */
class ApmEventCodecTest {

    /** All event metadata survives a durable round trip. */
    @Test
    fun `event round trip preserves metadata`() {
        val source = ApmEvent(
            module = "network",
            name = "request",
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            priority = ApmPriority.HIGH,
            timestamp = 1234L,
            processName = "sample",
            threadName = "worker",
            scene = "HomeActivity",
            foreground = true,
            fields = mapOf("durationMs" to 42L, "success" to true),
            globalContext = mapOf("appId" to "demo"),
            extras = mapOf("traceId" to "abc")
        )

        val decoded = ApmEventCodec.decode(ApmEventCodec.encode(source))

        assertEquals(source, decoded)
    }

    /** Version 3 restores every supported scalar field type exactly. */
    @Test
    fun `typed fields preserve supported scalar types`() {
        val fields = linkedMapOf<String, Any?>(
            "null" to null,
            "string" to "value",
            "boolean" to true,
            "byte" to Byte.MIN_VALUE,
            "short" to Short.MAX_VALUE,
            "int" to Int.MIN_VALUE,
            "long" to Long.MAX_VALUE,
            "float" to 1.25f,
            "double" to -2.5,
            "char" to '中',
            "bigInteger" to BigInteger("123456789012345678901234567890"),
            "bigDecimal" to BigDecimal("1.2300E+100")
        )

        val decoded = ApmEventCodec.decode(
            ApmEventCodec.encode(ApmEvent(module = "model", name = "typed", fields = fields))
        )

        assertEquals(fields, decoded.fields)
        for ((key, value) in fields) {
            // Equality alone can hide numeric widening, so verify every non-null runtime class too.
            assertEquals(value?.javaClass, decoded.fields[key]?.javaClass)
        }
    }

    /** Unsupported arbitrary objects retain the historical deterministic string fallback. */
    @Test
    fun `unsupported field object falls back to string`() {
        val source = ApmEvent(
            module = "model",
            name = "fallback",
            fields = mapOf("custom" to StableTextValue("custom-value"))
        )

        val decoded = ApmEventCodec.decode(ApmEventCodec.encode(source))

        assertEquals("custom-value", decoded.fields["custom"])
        assertTrue(decoded.fields["custom"] is String)
    }

    /** Version 2 durable payloads preserve the event identity across retries. */
    @Test
    fun `event round trip preserves identity`() {
        val source = ApmEvent(module = "core", name = "saved", eventId = "event-42")

        val decoded = ApmEventCodec.decode(ApmEventCodec.encode(source))

        assertEquals("event-42", decoded.eventId)
    }

    /** Version 1 payloads remain readable and expose an empty identity for storage migration. */
    @Test
    fun `version one payload remains readable`() {
        val decoded = ApmEventCodec.decode(versionOnePayload())

        assertEquals("legacy", decoded.name)
        assertEquals(mapOf("count" to "7"), decoded.fields)
        assertEquals("", decoded.eventId)
    }

    /** Version 2 payloads remain readable with string fields and their appended identity. */
    @Test
    fun `version two payload remains readable`() {
        val decoded = ApmEventCodec.decode(versionTwoPayload())

        assertEquals("legacy", decoded.name)
        assertEquals(mapOf("enabled" to "true"), decoded.fields)
        assertEquals("legacy-event-2", decoded.eventId)
    }

    /** Unknown version-3 field tags reject the corrupt event instead of misaligning later fields. */
    @Test(expected = IllegalArgumentException::class)
    fun `unknown typed field tag is rejected`() {
        ApmEventCodec.decode(payloadWithUnknownTypedField())
    }

    /** Arbitrary-precision values cannot turn one field into an excessive parser workload. */
    @Test(expected = IllegalArgumentException::class)
    fun `oversized big number is rejected`() {
        ApmEventCodec.encode(
            ApmEvent(
                module = "model",
                name = "oversized-big-number",
                fields = mapOf("value" to BigInteger("9".repeat(OVERSIZED_BIG_NUMBER_DIGITS)))
            )
        )
    }

    /** Builds the exact durable format written before event identity was appended. */
    private fun versionOnePayload(): ByteArray {
        return legacyPayload(version = 1, fields = mapOf("count" to "7"), eventId = null)
    }

    /** Builds the exact version-2 format containing string fields and an appended identity. */
    private fun versionTwoPayload(): ByteArray {
        return legacyPayload(
            version = 2,
            fields = mapOf("enabled" to "true"),
            eventId = "legacy-event-2"
        )
    }

    /** Builds a legacy version-1/2 payload without relying on the current production writer. */
    private fun legacyPayload(
        version: Int,
        fields: Map<String, String>,
        eventId: String?
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(version)
            output.writeLong(123L)
            listOf("core", "legacy", "METRIC", "INFO", "NORMAL", "process", "thread").forEach { value ->
                // Preserve the historical metadata order exactly.
                output.writeTestString(value)
            }
            output.writeBoolean(false)
            output.writeByte(0)
            output.writeInt(fields.size)
            for ((key, value) in fields) {
                output.writeTestString(key)
                output.writeTestString(value)
            }
            repeat(2) { output.writeInt(0) }
            if (eventId != null) {
                output.writeTestString(eventId)
            }
        }
        return buffer.toByteArray()
    }

    /** Builds a version-3 prefix containing one deliberately unsupported type tag. */
    private fun payloadWithUnknownTypedField(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(3)
            output.writeLong(123L)
            listOf("core", "corrupt", "METRIC", "INFO", "NORMAL", "process", "thread").forEach { value ->
                // Match production metadata order up to the typed field map.
                output.writeTestString(value)
            }
            output.writeBoolean(false)
            output.writeByte(0)
            output.writeInt(1)
            output.writeTestString("field")
            output.writeByte(127)
        }
        return buffer.toByteArray()
    }

    /** Writes the bounded length-prefixed UTF-8 shape shared by all durable versions. */
    private fun DataOutputStream.writeTestString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    /** Stable unsupported value used to verify the safe string fallback. */
    private class StableTextValue(
        /** Text returned by [toString]. */
        private val text: String
    ) {
        /** Returns deterministic text without exposing an object serialization contract. */
        override fun toString(): String = text
    }

    /** Test-only payload sizes. */
    private companion object {
        /** One digit above the production arbitrary-precision parsing budget. */
        private const val OVERSIZED_BIG_NUMBER_DIGITS = 4_097
    }
}
