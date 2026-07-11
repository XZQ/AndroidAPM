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

    /** Common JSON, cookie, API-key, secret, and encoded query forms must all be redacted. */
    @Test
    fun `extended credential forms are redacted`() {
        val input = "\"apiKey\":\"json-key\" client_secret=form-secret Cookie: sid=cookie-value " +
            "Set-Cookie: auth=set-cookie-value api_key%3Dencoded-key secret=plain-secret"

        val output = DiagnosticSanitizer.sanitizeMessage(input)

        listOf("json-key", "form-secret", "cookie-value", "set-cookie-value", "encoded-key", "plain-secret")
            .forEach { secret -> assertFalse(output.contains(secret)) }
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
