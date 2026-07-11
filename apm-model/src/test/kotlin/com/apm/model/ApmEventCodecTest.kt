package com.apm.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
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

        assertEquals(source.copy(fields = mapOf("durationMs" to "42", "success" to "true")), decoded)
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
        assertEquals("", decoded.eventId)
    }

    /** Builds the exact durable format written before event identity was appended. */
    private fun versionOnePayload(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(1)
            output.writeLong(123L)
            listOf("core", "legacy", "METRIC", "INFO", "NORMAL", "process", "thread").forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            output.writeBoolean(false)
            output.writeByte(0)
            repeat(3) { output.writeInt(0) }
        }
        return buffer.toByteArray()
    }
}
