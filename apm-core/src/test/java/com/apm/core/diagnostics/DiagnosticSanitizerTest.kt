package com.apm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy and size-bound tests for SDK diagnostic text.
 */
class DiagnosticSanitizerTest {

    /** Credential-shaped values must be removed before diagnostics persistence. */
    @Test
    fun `credentials are redacted and messages are bounded`() {
        val input = "https://host/path?token=secret&name=ok Authorization: Bearer abc"

        val output = DiagnosticSanitizer.sanitizeMessage(input.repeat(500))

        assertFalse(output.contains("secret"))
        assertFalse(output.contains("Bearer abc"))
        assertTrue(output.length <= 4_096)
        assertTrue(output.endsWith("...[truncated]"))
    }

    /** Exception stacks must be bounded and deterministically fingerprinted. */
    @Test
    fun `throwable stack is bounded and hash is stable`() {
        val error = IllegalStateException("password=top-secret")
        // Use a synthetic deep stack to verify frame and character bounds deterministically.
        error.stackTrace = Array(100) { index ->
            StackTraceElement("example.Class$index", "method$index", "Source.kt", index + 1)
        }

        val first = DiagnosticSanitizer.sanitizeThrowable(error, includeStack = true)
        val second = DiagnosticSanitizer.sanitizeThrowable(error, includeStack = true)

        assertEquals("java.lang.IllegalStateException", first.className)
        assertEquals("password=[REDACTED]", first.message)
        assertNotNull(first.stackTrace)
        assertTrue(first.stackTrace!!.lineSequence().count() <= 64)
        assertEquals(first.stackHash, second.stackHash)
    }
}
