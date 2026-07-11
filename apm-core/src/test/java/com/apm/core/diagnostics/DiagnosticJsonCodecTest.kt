package com.apm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stable JSONL persistence format tests.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticJsonCodecTest {

    /** Every controlled entry field must survive a JSONL round trip. */
    @Test
    fun `entry codec round trips controlled fields`() {
        val entry = diagnosticEntry()

        val decoded = DiagnosticJsonCodec.decode(DiagnosticJsonCodec.encode(entry))

        assertEquals(entry, decoded)
    }

    /** A corrupt line must be ignored instead of escaping into the host app. */
    @Test
    fun `invalid JSONL line returns null`() {
        assertNull(DiagnosticJsonCodec.decode("{not-json"))
    }

    /** Creates a complete entry so the codec contract cannot silently drop fields. */
    private fun diagnosticEntry(): DiagnosticEntry = DiagnosticEntry(
        sequence = 7L,
        timestampMs = 123_456L,
        sessionId = "1_session",
        level = DiagnosticLevel.ERROR,
        component = "storage",
        code = "event_store_write",
        message = "disk failed",
        processName = "com.example",
        threadName = "apm-dispatcher",
        exceptionClass = "java.io.IOException",
        exceptionMessage = "disk full",
        stackTrace = "example.Store.append(Store.kt:10)",
        stackHash = "abc123"
    )
}
