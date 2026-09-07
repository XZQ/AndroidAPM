package com.apm.model

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Versioned protobuf envelope, typed scalar, resource, and stable identity tests. */
class ApmBatchEnvelopeSerializerTest {
    /** Every durable scalar has an explicit wire discriminator and canonical text. */
    @Test
    fun `typed values preserve all supported scalar types`() {
        val cases = listOf(
            null to ApmTypedValue(ApmScalarType.NULL, null),
            "text" to ApmTypedValue(ApmScalarType.STRING, "text"),
            true to ApmTypedValue(ApmScalarType.BOOLEAN, "true"),
            1.toByte() to ApmTypedValue(ApmScalarType.BYTE, "1"),
            2.toShort() to ApmTypedValue(ApmScalarType.SHORT, "2"),
            3 to ApmTypedValue(ApmScalarType.INT, "3"),
            4L to ApmTypedValue(ApmScalarType.LONG, "4"),
            1.25f to ApmTypedValue(ApmScalarType.FLOAT, "1.25"),
            2.5 to ApmTypedValue(ApmScalarType.DOUBLE, "2.5"),
            'x' to ApmTypedValue(ApmScalarType.CHAR, "x"),
            BigInteger("12345678901234567890") to
                ApmTypedValue(ApmScalarType.BIG_INTEGER, "12345678901234567890"),
            BigDecimal("123.4500") to ApmTypedValue(ApmScalarType.BIG_DECIMAL, "123.4500")
        )

        for ((source, expected) in cases) {
            assertEquals(expected, ApmTypedValue.from(source))
        }
    }

    /** Unsupported objects retain a deterministic STRING fallback without object deserialization. */
    @Test
    fun `unsupported typed value falls back to string`() {
        assertEquals(
            ApmTypedValue(ApmScalarType.STRING, "fallback-value"),
            ApmTypedValue.from(FallbackValue)
        )
    }

    /** Envelope carries frozen schema, SDK, resource, typed fields, and stable ACK metadata. */
    @Test
    fun `envelope includes versioned resource and typed event contract`() {
        val encoded = ApmBatchEnvelopeSerializer.serialize(
            events = listOf(
                ApmEvent(
                    module = "fps",
                    name = "frame_stats",
                    eventId = "event-a",
                    fields = mapOf("frame_count" to 42, "healthy" to true, "missing" to null)
                )
            ),
            resource = resource(),
            sentAtMs = FIXED_SENT_AT_MS
        )

        assertEquals(1, encoded.eventCount)
        assertEquals(EXPECTED_BATCH_ID_LENGTH, encoded.batchId.length)
        assertTrue(encoded.batchId.startsWith("b2-"))
        for (text in listOf(
            ApmWireProtocol.SDK_NAME,
            ApmWireProtocol.SDK_VERSION,
            "wallet",
            "1.2.3",
            "production",
            "install-42",
            "event-a",
            "frame_count",
            ApmScalarType.INT.name,
            ApmScalarType.BOOLEAN.name,
            ApmScalarType.NULL.name
        )) {
            assertTrue("Missing UTF-8 protocol value: $text", containsUtf8(encoded.payload, text))
        }
    }

    /** Versioned event bytes use append-only field 15 and do not duplicate legacy string field 10. */
    @Test
    fun `versioned event writes typed field number`() {
        val eventBytes = ProtobufSerializer.serializeVersionedEvent(
            ApmEvent(module = "test", name = "typed", fields = mapOf("count" to 42))
        )
        val fieldNumbers = topLevelFieldNumbers(eventBytes)

        assertTrue(TYPED_FIELDS_NUMBER in fieldNumbers)
        assertTrue(LEGACY_FIELDS_NUMBER !in fieldNumbers)
    }

    /** V3 writes field 16, uses a b3 identity, and retains the occurrence release across upload. */
    @Test
    fun `v3 envelope carries occurrence bound identity`() {
        val oldOccurrence = occurrence(serviceVersion = "1.0.0", versionCode = "100")
        val event = ApmEvent(
            module = "crash",
            name = "java_crash",
            eventId = "old-release-event"
        ).withOccurrenceContext(oldOccurrence)
        // Simulate an old durable event uploaded by a process that has already upgraded.
        val upgradedResource = resource().copy(serviceVersion = "2.0.0")

        val encoded = ApmBatchEnvelopeSerializer.serializeV3(
            listOf(event),
            upgradedResource,
            FIXED_SENT_AT_MS
        )
        val eventBytes = ProtobufSerializer.serializeVersionedEventV3(event)

        assertTrue(encoded.batchId.startsWith("b3-"))
        assertTrue(OCCURRENCE_NUMBER in topLevelFieldNumbers(eventBytes))
        assertTrue(containsUtf8(encoded.payload, "1.0.0"))
        assertTrue(containsUtf8(encoded.payload, "2.0.0"))
        assertTrue(containsUtf8(encoded.payload, "anonymous-installation"))
    }

    /** V2 cannot silently accept a business-required V3 occurrence snapshot. */
    @Test
    fun `v2 rejects occurrence semantic smuggling`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApmBatchEnvelopeSerializer.serialize(
                listOf(event("event-v2").withOccurrenceContext(occurrence())),
                resource(),
                FIXED_SENT_AT_MS
            )
        }
    }

    /** Batch identity survives request-time changes but changes when the ordered event set changes. */
    @Test
    fun `batch identity is retry stable and event set specific`() {
        val events = listOf(event("event-a"), event("event-b"))
        val first = ApmBatchEnvelopeSerializer.serialize(events, resource(), sentAtMs = 1L)
        val retry = ApmBatchEnvelopeSerializer.serialize(events, resource(), sentAtMs = 2L)
        val changed = ApmBatchEnvelopeSerializer.serialize(
            listOf(event("event-a"), event("event-c")),
            resource(),
            sentAtMs = 1L
        )

        assertEquals(first.batchId, retry.batchId)
        assertNotEquals(first.batchId, changed.batchId)
    }

    /** Empty event lists cannot create ACK units with ambiguous success semantics. */
    @Test
    fun `empty versioned batch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApmBatchEnvelopeSerializer.serialize(emptyList(), resource())
        }
    }

    /** Budget splitting matches a naive greedy full-reserialize reference on varied events. */
    @Test
    fun `budget splitting matches naive greedy reference`() {
        val events = buildList {
            repeat(BUDGET_CORPUS_SIZE) { index ->
                add(
                    ApmEvent(
                        module = "budget",
                        name = "event-$index",
                        eventId = "budget-event-$index",
                        // 变长 payload 使各预算下的拆分边界落在不同位置
                        fields = mapOf(
                            "payload" to "x".repeat((index * PRIME_STRIDE) % MAX_FILL_LENGTH),
                            "index" to index
                        )
                    )
                )
            }
        }
        for (budget in BUDGET_CASES) {
            val actual = ApmBatchEnvelopeSerializer.serializeWithinBudget(
                events = events,
                resource = resource(),
                maxBatchBytes = budget,
                sentAtMs = FIXED_SENT_AT_MS
            )
            val expected = naiveGreedySplit(events, budget)
            if (expected == null || actual == null) {
                // 两种实现必须对"单事件超预算"做出一致的 null 判定
                assertTrue(
                    "oversize agreement required for budget=$budget",
                    expected == null && actual == null
                )
                continue
            }
            // 拆分点、batchId、eventCount 与 payload 必须与朴素参考实现完全一致
            assertEquals(expected.size, actual.size)
            for (index in expected.indices) {
                assertEquals(expected[index].batchId, actual[index].batchId)
                assertEquals(expected[index].eventCount, actual[index].eventCount)
                assertTrue(expected[index].payload.contentEquals(actual[index].payload))
                assertTrue("sub-batch must fit budget=$budget", actual[index].payload.size <= budget)
            }
            // 全部事件按原顺序进入某个子批
            assertEquals(events.size, actual.sumOf { it.eventCount })
        }
    }

    /** Empty input yields an empty split result rather than an ACK unit. */
    @Test
    fun `budget splitting accepts empty input`() {
        val result = ApmBatchEnvelopeSerializer.serializeWithinBudget(
            events = emptyList(),
            resource = resource(),
            maxBatchBytes = 1024,
            sentAtMs = FIXED_SENT_AT_MS
        )
        assertTrue(result != null && result.isEmpty())
    }

    /**
     * Reference implementation of the historical algorithm: serialize the accumulated candidate
     * after every append and commit the last fitting candidate on overflow.
     */
    private fun naiveGreedySplit(events: List<ApmEvent>, maxBatchBytes: Int): List<EncodedApmBatch>? {
        val encodedBatches = ArrayList<EncodedApmBatch>()
        val currentEvents = ArrayList<ApmEvent>()
        var currentEncoded: EncodedApmBatch? = null
        for (event in events) {
            currentEvents += event
            val candidate = ApmBatchEnvelopeSerializer.serialize(currentEvents, resource(), FIXED_SENT_AT_MS)
            if (candidate.payload.size <= maxBatchBytes) {
                currentEncoded = candidate
                continue
            }
            currentEvents.removeAt(currentEvents.lastIndex)
            if (currentEncoded == null) {
                return null
            }
            encodedBatches += currentEncoded
            currentEvents.clear()
            currentEvents += event
            currentEncoded = ApmBatchEnvelopeSerializer.serialize(currentEvents, resource(), FIXED_SENT_AT_MS)
            if (currentEncoded.payload.size > maxBatchBytes) {
                return null
            }
        }
        currentEncoded?.let(encodedBatches::add)
        return encodedBatches
    }

    /** Creates one event with a caller-selected stable identity. */
    private fun event(identity: String): ApmEvent = ApmEvent(
        module = "test",
        name = "event",
        eventId = identity
    )

    /** Complete fixed resource used by protocol tests. */
    private fun resource(): ApmResourceContext = ApmResourceContext(
        serviceName = "wallet",
        serviceVersion = "1.2.3",
        deploymentEnvironment = "production",
        installationId = "install-42"
    )

    /** Complete event-level occurrence identity used by V3 tests. */
    private fun occurrence(
        serviceVersion: String = "1.2.3",
        versionCode: String = "123"
    ): ApmOccurrenceContext = ApmOccurrenceContext(
        serviceVersion = serviceVersion,
        versionCode = versionCode,
        appBuild = "build-fixture",
        variant = "release",
        installationId = "anonymous-installation"
    )

    /** Returns whether a protobuf payload contains one exact UTF-8 subsequence. */
    private fun containsUtf8(payload: ByteArray, text: String): Boolean {
        val expected = text.toByteArray(Charsets.UTF_8)
        if (expected.isEmpty()) {
            return true
        }
        return payload.indices.any { start ->
            start + expected.size <= payload.size &&
                payload.copyOfRange(start, start + expected.size).contentEquals(expected)
        }
    }

    /** Parses top-level protobuf tags while skipping supported varint and length-delimited values. */
    private fun topLevelFieldNumbers(payload: ByteArray): Set<Int> {
        val fields = mutableSetOf<Int>()
        var cursor = 0
        while (cursor < payload.size) {
            val (tag, afterTag) = readVarint(payload, cursor)
            cursor = afterTag
            fields += tag ushr TAG_SHIFT
            cursor = when (tag and WIRE_TYPE_MASK) {
                WIRE_TYPE_VARINT -> readVarint(payload, cursor).second
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val (length, afterLength) = readVarint(payload, cursor)
                    afterLength + length
                }
                else -> error("Unsupported test wire type")
            }
        }
        return fields
    }

    /** Reads one non-negative protobuf varint and returns value plus next cursor. */
    private fun readVarint(payload: ByteArray, start: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var cursor = start
        while (cursor < payload.size) {
            val current = payload[cursor].toInt() and BYTE_MASK
            value = value or ((current and VARINT_DATA_MASK) shl shift)
            cursor += 1
            if (current and VARINT_CONTINUATION == 0) {
                return value to cursor
            }
            shift += VARINT_SHIFT
        }
        error("Truncated test varint")
    }

    /** Stable unsupported object used to prove string fallback. */
    private object FallbackValue {
        /** Returns deterministic fallback text. */
        override fun toString(): String = "fallback-value"
    }

    companion object {
        /** Fixed request timestamp avoiding wall-clock assertions. */
        private const val FIXED_SENT_AT_MS = 1_700_000_000_000L
        /** Prefix plus the first sixteen SHA-256 bytes rendered as lowercase hexadecimal. */
        private const val EXPECTED_BATCH_ID_LENGTH = 35
        /** Legacy string-valued fields number. */
        private const val LEGACY_FIELDS_NUMBER = 10
        /** Versioned typed fields number. */
        private const val TYPED_FIELDS_NUMBER = 15
        /** Schema-V3 occurrence snapshot field number. */
        private const val OCCURRENCE_NUMBER = 16
        /** Budget-split corpus size covering single, multi, and overflow sub-batches. */
        private const val BUDGET_CORPUS_SIZE = 24
        /** Prime stride producing varied payload lengths across the corpus. */
        private const val PRIME_STRIDE = 37
        /** Upper bound for the varying fill length inside one corpus event. */
        private const val MAX_FILL_LENGTH = 211
        /** Budget cases spanning single-batch, multi-batch, and oversize-single outcomes. */
        private val BUDGET_CASES = listOf(512, 1024, 2048, 4096, 8192)
        /** Protobuf tag field-number shift. */
        private const val TAG_SHIFT = 3
        /** Protobuf tag wire-type mask. */
        private const val WIRE_TYPE_MASK = 0x7
        /** Protobuf varint wire type. */
        private const val WIRE_TYPE_VARINT = 0
        /** Protobuf length-delimited wire type. */
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2
        /** Unsigned byte mask. */
        private const val BYTE_MASK = 0xFF
        /** Varint data-bit mask. */
        private const val VARINT_DATA_MASK = 0x7F
        /** Varint continuation flag. */
        private const val VARINT_CONTINUATION = 0x80
        /** Varint payload bits per byte. */
        private const val VARINT_SHIFT = 7
    }
}
