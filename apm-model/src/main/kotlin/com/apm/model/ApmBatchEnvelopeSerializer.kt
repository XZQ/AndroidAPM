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
    ): EncodedApmBatch {
        require(events.isNotEmpty()) { "Versioned APM batch must contain at least one event" }
        val batchId = stableBatchId(events)
        val buffer = ByteArrayOutputStream()
        val writer = ProtobufWriter(buffer)
        writer.writeInt64(FIELD_SCHEMA_VERSION, ApmWireProtocol.ENVELOPE_SCHEMA_VERSION.toLong())
        writer.writeString(FIELD_SDK_NAME, ApmWireProtocol.SDK_NAME)
        writer.writeString(FIELD_SDK_VERSION, ApmWireProtocol.SDK_VERSION)
        writer.writeString(FIELD_BATCH_ID, batchId)
        writer.writeInt64(FIELD_SENT_AT_MS, sentAtMs)
        writer.writeMessage(FIELD_RESOURCE, serializeResource(resource))
        for (event in events) {
            // Repeated embedded messages preserve dispatcher/upload ordering.
            writer.writeMessage(FIELD_EVENTS, ProtobufSerializer.serializeVersionedEvent(event))
        }
        writer.flush()
        return EncodedApmBatch(batchId, events.size, buffer.toByteArray())
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

    /** Derives a retry-stable batch identity from length-delimited ordered event IDs. */
    private fun stableBatchId(events: List<ApmEvent>): String {
        val digest = MessageDigest.getInstance(SHA_256)
        digest.update(ApmWireProtocol.ENVELOPE_SCHEMA_VERSION.toByte())
        for (event in events) {
            val identity = event.eventId.toByteArray(Charsets.UTF_8)
            // Length delimiting prevents ambiguous concatenations such as ["ab", "c"] and ["a", "bc"].
            digest.update(intToBytes(identity.size))
            digest.update(identity)
        }
        return BATCH_ID_PREFIX + digest.digest()
            .take(BATCH_ID_HASH_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
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
    private const val BATCH_ID_PREFIX = "b2-"
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
}
