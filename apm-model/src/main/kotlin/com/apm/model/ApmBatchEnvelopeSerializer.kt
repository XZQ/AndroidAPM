package com.apm.model

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Fully encoded versioned batch plus metadata required by the HTTP ACK contract.
 *
 * @property batchId deterministic identity derived from the ordered event IDs
 * @property eventCount number of events represented by [payload]
 * @property payload protobuf-encoded [ApmBatchEnvelope] bytes
 */
data class EncodedApmBatch(
    val batchId: String,
    val eventCount: Int,
    val payload: ByteArray
)

/** Serializes the collector-facing versioned protobuf batch envelope. */
object ApmBatchEnvelopeSerializer {
    /**
     * Encodes one non-empty event batch with standard resource and SDK identity.
     *
     * @param events ordered events included in this atomic ACK unit
     * @param resource standard host resource identity
     * @param sentAtMs wall-clock request creation time in epoch milliseconds
     * @return encoded envelope and its stable ACK metadata
     */
    fun serialize(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        sentAtMs: Long = System.currentTimeMillis()
    ): EncodedApmBatch = serializeForSchema(
        events,
        resource,
        sentAtMs,
        ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V2
    )

    /** Encodes one schema-V3 batch and requires occurrence identity on every event. */
    fun serializeV3(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        sentAtMs: Long = System.currentTimeMillis()
    ): EncodedApmBatch = serializeForSchema(
        events,
        resource,
        sentAtMs,
        ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V3
    )

    /** Shared explicit-envelope serializer with schema-specific event semantics. */
    private fun serializeForSchema(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        sentAtMs: Long,
        schemaVersion: Int
    ): EncodedApmBatch {
        require(events.isNotEmpty()) { "Versioned APM batch must contain at least one event" }
        // 公共入口直接预编码后走统一写路径，保证 serialize 与 serializeWithinBudget 输出一致
        val encodedEvents = ArrayList<ByteArray>(events.size)
        for (event in events) {
            encodedEvents += serializeEventForSchema(event, schemaVersion)
        }
        return serializePreEncoded(events, encodedEvents, resource, sentAtMs, schemaVersion)
    }

    /**
     * Builds deterministic sub-batches whose uncompressed protobuf payload stays within
     * [maxBatchBytes].
     *
     * 每事件只编码一次：编码结果同时用于精确字节贡献计算与最终 envelope 写入，
     * 再用一次单事件探针锚定 envelope 固定组件（schema/SDK/常长 batchId/sentAt/resource）
     * 的大小，后续候选批的尺寸用增量求和精确判定。这消除了每追加一事件就整批重序列化 +
     * 重哈希的 O(N^2) 编码行为；拆分点与逐候选全量序列化的实现一致。
     *
     * @param events ordered events to split; must be non-empty
     * @param resource standard host resource identity shared by every sub-batch
     * @param maxBatchBytes maximum uncompressed protobuf payload per sub-batch
     * @param sentAtMs wall-clock request creation time shared by every sub-batch
     * @return sub-batches in order, or null when one single event exceeds the budget
     */
    fun serializeWithinBudget(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        maxBatchBytes: Int,
        sentAtMs: Long
    ): List<EncodedApmBatch>? = serializeWithinBudgetForSchema(
        events,
        resource,
        maxBatchBytes,
        sentAtMs,
        ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V2
    )

    /** Splits schema-V3 events within the same exact uncompressed payload budget. */
    fun serializeWithinBudgetV3(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        maxBatchBytes: Int,
        sentAtMs: Long
    ): List<EncodedApmBatch>? = serializeWithinBudgetForSchema(
        events,
        resource,
        maxBatchBytes,
        sentAtMs,
        ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V3
    )

    /** Shared budget splitter for explicit V2/V3 envelopes. */
    private fun serializeWithinBudgetForSchema(
        events: List<ApmEvent>,
        resource: ApmResourceContext,
        maxBatchBytes: Int,
        sentAtMs: Long,
        schemaVersion: Int
    ): List<EncodedApmBatch>? {
        require(maxBatchBytes > 0) { "maxBatchBytes must be positive" }
        if (events.isEmpty()) {
            return emptyList()
        }

        // 单次编码：字节数组同时服务于贡献计算与最终写入
        val encodedEvents = ArrayList<ByteArray>(events.size)
        val contributions = IntArray(events.size)
        for (index in events.indices) {
            val encoded = serializeEventForSchema(events[index], schemaVersion)
            encodedEvents += encoded
            contributions[index] = eventContributionBytes(encoded.size)
        }

        // 单事件探针：固定组件大小 = 探针 envelope 大小 - 首事件贡献
        val probe = serializePreEncoded(
            events.subList(0, 1), encodedEvents.subList(0, 1), resource, sentAtMs, schemaVersion
        )
        val fixedBaseBytes = probe.payload.size - contributions[0]
        if (probe.payload.size > maxBatchBytes) {
            return null
        }

        val encodedBatches = ArrayList<EncodedApmBatch>()
        var batchStart = 0
        var runningBytes = probe.payload.size
        var index = 1
        while (index < events.size) {
            val candidateBytes = runningBytes + contributions[index]
            if (candidateBytes <= maxBatchBytes) {
                runningBytes = candidateBytes
                index++
                continue
            }
            // 追加当前事件会超预算：提交 [batchStart, index) 的候选批
            val committed = serializePreEncoded(
                events.subList(batchStart, index),
                encodedEvents.subList(batchStart, index),
                resource,
                sentAtMs,
                schemaVersion
            )
            if (committed.payload.size > maxBatchBytes) {
                // 增量算术与实际编码出现偏差时的兜底：宁可失败也不返回超预算批次
                return null
            }
            encodedBatches += committed
            batchStart = index
            runningBytes = fixedBaseBytes + contributions[index]
            if (runningBytes > maxBatchBytes) {
                return null
            }
            index++
        }
        if (batchStart < events.size) {
            encodedBatches += serializePreEncoded(
                events.subList(batchStart, events.size),
                encodedEvents.subList(batchStart, events.size),
                resource,
                sentAtMs,
                schemaVersion
            )
        }
        return encodedBatches
    }

    /**
     * Writes one envelope from already-encoded event bytes.
     *
     * The shared write path for [serialize] and [serializeWithinBudget]: identical headers,
     * resource block, batch identity, and repeated-event ordering guarantee byte-identical
     * output regardless of who performed the per-event encoding.
     */
    private fun serializePreEncoded(
        events: List<ApmEvent>,
        encodedEvents: List<ByteArray>,
        resource: ApmResourceContext,
        sentAtMs: Long,
        schemaVersion: Int
    ): EncodedApmBatch {
        require(events.isNotEmpty()) { "Versioned APM batch must contain at least one event" }
        require(events.size == encodedEvents.size) { "Encoded event count mismatch" }
        val batchId = stableBatchId(events, schemaVersion)
        // 容量按已编码字节数精确预估，仅加固定头部余量
        var encodedTotal = 0
        for (encoded in encodedEvents) {
            encodedTotal += encoded.size
        }
        val buffer = ByteArrayOutputStream(encodedTotal + ESTIMATE_ENVELOPE_FIXED_BYTES)
        val writer = ProtobufWriter(buffer)
        writer.writeInt64(FIELD_SCHEMA_VERSION, schemaVersion.toLong())
        writer.writeString(FIELD_SDK_NAME, ApmWireProtocol.SDK_NAME)
        writer.writeString(FIELD_SDK_VERSION, ApmWireProtocol.SDK_VERSION)
        writer.writeString(FIELD_BATCH_ID, batchId)
        writer.writeInt64(FIELD_SENT_AT_MS, sentAtMs)
        writer.writeMessage(FIELD_RESOURCE, serializeResource(resource))
        for (encoded in encodedEvents) {
            // Repeated embedded messages preserve dispatcher/upload ordering.
            writer.writeMessage(FIELD_EVENTS, encoded)
        }
        writer.flush()
        return EncodedApmBatch(batchId, events.size, buffer.toByteArray())
    }

    /** Selects the only valid event encoding for one explicit envelope schema. */
    private fun serializeEventForSchema(event: ApmEvent, schemaVersion: Int): ByteArray {
        return when (schemaVersion) {
            ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V2 ->
                ProtobufSerializer.serializeVersionedEvent(event)
            ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V3 ->
                ProtobufSerializer.serializeVersionedEventV3(event)
            else -> error("Unsupported APM envelope schema version: $schemaVersion")
        }
    }

    /** Encodes the fixed standard resource block without arbitrary unreviewed attributes. */
    private fun serializeResource(resource: ApmResourceContext): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = ProtobufWriter(buffer)
        writer.writeString(RESOURCE_SERVICE_NAME, resource.serviceName)
        writer.writeString(RESOURCE_SERVICE_VERSION, resource.serviceVersion)
        writer.writeString(RESOURCE_DEPLOYMENT_ENVIRONMENT, resource.deploymentEnvironment)
        writer.writeString(RESOURCE_INSTALLATION_ID, resource.installationId)
        writer.flush()
        return buffer.toByteArray()
    }

    /**
     * Returns the exact envelope bytes attributed to one already-encoded event: the repeated-field
     * tag, its length varint, and the event bytes themselves.
     *
     * All other envelope components (schema varint, SDK strings, the constant-length `b2-` +
     * 32-hex batchId, the single sentAt varint, and the fixed resource message) have constant
     * encoded size within one [serialize] call, so [serializeWithinBudget] can anchor them with a
     * one-event probe and then sum these contributions to know each candidate envelope's exact
     * size without re-serializing accumulated events.
     */
    private fun eventContributionBytes(eventBytesSize: Int): Int {
        var value = eventBytesSize
        var varintSize = 1
        while (value > VARINT_MAX_7BITS) {
            varintSize++
            value = value ushr VARINT_BIT_SHIFT
        }
        return EVENT_TAG_VARINT_BYTES + varintSize + eventBytesSize
    }

    /** Derives a retry-stable batch identity from length-delimited ordered event IDs. */
    private fun stableBatchId(events: List<ApmEvent>, schemaVersion: Int): String {
        val digest = MessageDigest.getInstance(SHA_256)
        digest.update(schemaVersion.toByte())
        for (event in events) {
            val identity = event.eventId.toByteArray(Charsets.UTF_8)
            // Length delimiting prevents ambiguous concatenations such as ["ab", "c"] and ["a", "bc"].
            digest.update(intToBytes(identity.size))
            digest.update(identity)
        }
        // 手写小写 hex 避免 16 次 String.format（每次各建一个 Formatter）；
        // "%02x" 是 locale 无关的小写十六进制，与查表输出逐字符一致
        val hash = digest.digest()
        val hex = StringBuilder(BATCH_ID_HASH_BYTES * HEX_CHARS_PER_BYTE)
        for (index in 0 until BATCH_ID_HASH_BYTES) {
            val byte = hash[index].toInt() and BYTE_MASK
            hex.append(HEX_DIGITS[byte ushr BITS_4])
            hex.append(HEX_DIGITS[byte and HALF_BYTE_MASK])
        }
        val prefix = when (schemaVersion) {
            ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V2 -> BATCH_ID_PREFIX_V2
            ApmWireProtocol.ENVELOPE_SCHEMA_VERSION_V3 -> BATCH_ID_PREFIX_V3
            else -> error("Unsupported APM envelope schema version: $schemaVersion")
        }
        return prefix + hex
    }

    /** Encodes a positive string length in fixed big-endian form for batch hashing. */
    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr BITS_24).toByte(),
        (value ushr BITS_16).toByte(),
        (value ushr BITS_8).toByte(),
        value.toByte()
    )

    /** Envelope field: schema version. */
    private const val FIELD_SCHEMA_VERSION = 1
    /** Envelope field: SDK name. */
    private const val FIELD_SDK_NAME = 2
    /** Envelope field: SDK version. */
    private const val FIELD_SDK_VERSION = 3
    /** Envelope field: stable batch identity. */
    private const val FIELD_BATCH_ID = 4
    /** Envelope field: request creation wall time. */
    private const val FIELD_SENT_AT_MS = 5
    /** Envelope field: standard resource message. */
    private const val FIELD_RESOURCE = 6
    /** Envelope field: repeated event messages. */
    private const val FIELD_EVENTS = 7
    /** Resource field: logical service name. */
    private const val RESOURCE_SERVICE_NAME = 1
    /** Resource field: host release version. */
    private const val RESOURCE_SERVICE_VERSION = 2
    /** Resource field: deployment environment. */
    private const val RESOURCE_DEPLOYMENT_ENVIRONMENT = 3
    /** Resource field: anonymous installation identity. */
    private const val RESOURCE_INSTALLATION_ID = 4
    /** Stable batch identity prefix naming the schema generation. */
    private const val BATCH_ID_PREFIX_V2 = "b2-"
    /** Stable schema-V3 batch identity prefix. */
    private const val BATCH_ID_PREFIX_V3 = "b3-"
    /** SHA-256 bytes retained in the compact batch identity. */
    private const val BATCH_ID_HASH_BYTES = 16
    /** SHA-256 algorithm name. */
    private const val SHA_256 = "SHA-256"
    /** Big-endian shift for byte 3. */
    private const val BITS_24 = 24
    /** Big-endian shift for byte 2. */
    private const val BITS_16 = 16
    /** Big-endian shift for byte 1. */
    private const val BITS_8 = 8
    /** High nibble shift inside one byte. */
    private const val BITS_4 = 4
    /** Mask isolating the low nibble of one byte. */
    private const val HALF_BYTE_MASK = 0x0F
    /** Unsigned byte mask. */
    private const val BYTE_MASK = 0xFF
    /** Hex characters emitted per input byte. */
    private const val HEX_CHARS_PER_BYTE = 2
    /** Lowercase hex lookup table matching locale-independent "%02x" formatting. */
    private const val HEX_DIGITS = "0123456789abcdef"
    /** Envelope fixed-header growth-hint bytes covering schema/SDK/batchId/sentAt/resource. */
    private const val ESTIMATE_ENVELOPE_FIXED_BYTES = 256
    /** Repeated events field number as an encoded single-byte varint tag. */
    private const val EVENT_TAG_VARINT_BYTES = 1
    /** Largest value still encoded in one varint byte. */
    private const val VARINT_MAX_7BITS = 0x7F
    /** Varint bit-group shift. */
    private const val VARINT_BIT_SHIFT = 7
}
