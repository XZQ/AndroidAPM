package com.apm.model

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
}
