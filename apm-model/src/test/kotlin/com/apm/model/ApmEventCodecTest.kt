package com.apm.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

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

    /** Version 4 retains the version-3 typed scalar contract exactly. */
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

    /** Version 4 freezes release, installation, and build-relative native identity. */
    @Test
    fun `occurrence snapshot survives durable round trip`() {
        val occurrence = ApmOccurrenceContext(
            serviceVersion = "2.4.1",
            versionCode = "20401",
            appBuild = "build-a1b2c3",
            variant = "productionRelease",
            installationId = "anonymous-installation",
            nativeFrames = listOf(
                ApmNativeFrameIdentity(
                    abi = "arm64-v8a",
                    moduleBuildId = "7f4a",
                    moduleName = "libsample.so",
                    moduleRelativePc = 0x1234,
                    loadBias = 0x1000
                )
            )
        )
        val source = ApmEvent(
            module = "crash",
            name = "native_crash",
            eventId = "occurrence-event"
        ).withOccurrenceContext(occurrence)

        val decoded = ApmEventCodec.decode(ApmEventCodec.encode(source))
        assertEquals(source, decoded)
        assertEquals(occurrence, decoded.occurrence)
    }

    /** Strict UTF-8 validation continues to accept valid multilingual and supplementary text. */
    @Test
    fun `valid unicode string round trip is preserved`() {
        val source = ApmEvent(
            module = "model",
            name = "unicode",
            fields = mapOf("text" to "测试🙂\u0000")
        )

        assertEquals(source, ApmEventCodec.decode(ApmEventCodec.encode(source)))
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

    /** Version 3 typed payloads remain readable with no invented occurrence identity. */
    @Test
    fun `version three payload remains readable`() {
        val decoded = ApmEventCodec.decode(versionThreePayload())

        assertEquals(mapOf("count" to 7), decoded.fields)
        assertEquals("legacy-event-3", decoded.eventId)
        assertEquals(null, decoded.occurrence)
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

    /** Trailing bytes cannot be interpreted differently by another durable payload consumer. */
    @Test
    fun `trailing bytes are rejected`() {
        val encoded = ApmEventCodec.encode(ApmEvent(module = "model", name = "trailing"))

        assertMalformed(encoded + byteArrayOf(0))
    }

    /** Malformed UTF-8 is rejected instead of being normalized to the replacement character. */
    @Test
    fun `malformed utf8 is rejected`() {
        for (sequence in MALFORMED_UTF8_SEQUENCES) {
            val encoded = ApmEventCodec.encode(ApmEvent(module = "model", name = "utf8"))
            encoded.writeMalformedFirstString(sequence)
            assertMalformed(encoded)
        }
    }

    /** The encoder stops at its hard budget instead of growing an unbounded backing buffer. */
    @Test
    fun `oversized aggregate payload is rejected while writing`() {
        val largeValue = "a".repeat(MAX_TEST_STRING_CHARACTERS)

        try {
            ApmEventCodec.encode(
                ApmEvent(
                    module = "model",
                    name = "aggregate-budget",
                    fields = mapOf("first" to largeValue, "second" to largeValue)
                )
            )
            fail("Expected oversized payload rejection")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed resource bound.
        }
    }

    /**
     * Fixed-seed truncation and byte-mutation corpus must either decode safely or fail with the
     * documented malformed-payload exception, never leak a parser-specific exception.
     */
    @Test
    fun `fixed seed malformed corpus remains bounded and deterministic`() {
        val source = ApmEvent(
            module = "model",
            name = "fuzz-seed",
            fields = mapOf("count" to 7, "text" to "value"),
            globalContext = mapOf("environment" to "test"),
            extras = mapOf("traceId" to "trace-1")
        )
        val encoded = ApmEventCodec.encode(source)
        for (length in 0 until encoded.size) {
            assertMalformed(encoded.copyOf(length))
        }

        val random = Random(MALFORMED_CORPUS_SEED)
        repeat(MALFORMED_CORPUS_MUTATIONS) {
            val candidate = encoded.copyOf()
            repeat(1 + random.nextInt(MAX_MUTATIONS_PER_SAMPLE)) {
                val index = random.nextInt(candidate.size)
                candidate[index] = (candidate[index].toInt() xor (1 shl random.nextInt(Byte.SIZE_BITS)))
                    .toByte()
            }
            try {
                val decoded = ApmEventCodec.decode(candidate)
                assertEquals(decoded, ApmEventCodec.decode(ApmEventCodec.encode(decoded)))
            } catch (_: IllegalArgumentException) {
                // Rejection is an expected fuzz outcome.
            }
        }
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

    /** Builds the exact version-3 format containing typed fields but no occurrence snapshot. */
    private fun versionThreePayload(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(3)
            output.writeLong(123L)
            listOf("core", "legacy", "METRIC", "INFO", "NORMAL", "process", "thread").forEach {
                output.writeTestString(it)
            }
            output.writeBoolean(false)
            output.writeByte(0)
            output.writeInt(1)
            output.writeTestString("count")
            output.writeByte(5) // FIELD_TYPE_INT in the published durable V3 format.
            output.writeInt(7)
            repeat(2) { output.writeInt(0) }
            output.writeTestString("legacy-event-3")
        }
        return buffer.toByteArray()
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

    /** Asserts the stable malformed-payload exception without accepting unrelated failures. */
    private fun assertMalformed(payload: ByteArray) {
        try {
            ApmEventCodec.decode(payload)
            fail("Expected malformed payload rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    /** Replaces the first encoded string with one fixed-length malformed UTF-8 sequence. */
    private fun ByteArray.writeMalformedFirstString(sequence: ByteArray) {
        check(sequence.size <= FIRST_STRING_BYTE_LENGTH)
        System.arraycopy(sequence, 0, this, FIRST_STRING_VALUE_OFFSET, sequence.size)
        for (index in sequence.size until FIRST_STRING_BYTE_LENGTH) {
            this[FIRST_STRING_VALUE_OFFSET + index] = ASCII_PADDING_BYTE
        }
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

        /** Version + timestamp + first string length prefix. */
        private const val FIRST_STRING_VALUE_OFFSET = 4 + 8 + 4

        /** The seeded module text has five bytes available for malformed sequence substitution. */
        private const val FIRST_STRING_BYTE_LENGTH = 5

        /** Valid ASCII used after a shorter malformed prefix. */
        private const val ASCII_PADDING_BYTE: Byte = 0x61

        /** Overlong, surrogate, out-of-range, truncated, and stray-continuation UTF-8 corpus. */
        private val MALFORMED_UTF8_SEQUENCES = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xE2.toByte(), 0x82.toByte())
        )

        /** One individually valid string; two copies exceed the complete payload hard limit. */
        private const val MAX_TEST_STRING_CHARACTERS = 1024 * 1024

        /** Stable mutation corpus parameters. */
        private const val MALFORMED_CORPUS_SEED = 0x41_50_4D
        private const val MALFORMED_CORPUS_MUTATIONS = 512
        private const val MAX_MUTATIONS_PER_SAMPLE = 3
    }
}
