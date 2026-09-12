package com.apm.crash

import com.apm.model.ApmNativeFrameIdentity
import com.apm.model.ApmOccurrenceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies complete-or-absent identity within the platform's fixed summary budget. */
class ExitOccurrenceSummaryTest {
    /** Anonymous test-only identity, with no relationship to a host installation. */
    private val identity = ApmOccurrenceContext("版本1", "12", "build-old", "release", "test-install-old")

    /** Unicode survives exactly; native frames are not process-wide historical identity. */
    @Test
    fun `summary round trip retains all five fields without native frames`() {
        val withFrames = identity.copy(
            nativeFrames = listOf(ApmNativeFrameIdentity("arm64-v8a", "abcd", "libtest.so", 42L))
        )
        assertEquals(identity, ExitOccurrenceSummary.decode(ExitOccurrenceSummary.encode(withFrames)))
    }

    /** The exact 128-byte boundary succeeds, while one additional UTF-8 byte rejects the whole ID. */
    @Test
    fun `summary budget counts utf8 bytes and never truncates identity`() {
        val base = identity.copy(serviceVersion = "x")
        val baseSize = ExitOccurrenceSummary.encode(base)!!.size
        val exact = base.copy(serviceVersion = "x".repeat(1 + ExitOccurrenceSummary.MAX_BYTES - baseSize))
        val encoded = ExitOccurrenceSummary.encode(exact)
        assertNotNull(encoded)
        assertEquals(ExitOccurrenceSummary.MAX_BYTES, encoded!!.size)
        assertEquals(exact, ExitOccurrenceSummary.decode(encoded))
        assertNull(ExitOccurrenceSummary.encode(exact.copy(serviceVersion = exact.serviceVersion + "x")))
        assertNull(ExitOccurrenceSummary.encode(exact.copy(serviceVersion = "中" + exact.serviceVersion.drop(1))))
    }

    /** Missing/foreign/oversized/partial/trailing records cannot be upgraded into a valid identity. */
    @Test
    fun `malformed summary framing is rejected`() {
        val bytes = ExitOccurrenceSummary.encode(identity)!!
        assertNull(ExitOccurrenceSummary.decode(null))
        assertNull(ExitOccurrenceSummary.decode("foreign-summary".toByteArray()))
        assertNull(ExitOccurrenceSummary.decode(ByteArray(ExitOccurrenceSummary.MAX_BYTES + 1)))
        for (length in bytes.indices) {
            assertNull("truncated length $length", ExitOccurrenceSummary.decode(bytes.copyOf(length)))
        }
        assertNull(ExitOccurrenceSummary.decode(bytes + byteArrayOf(0)))
        assertNull(ExitOccurrenceSummary.decode(bytes.copyOf().also { it[0] = 0 }))
        assertNull(ExitOccurrenceSummary.decode(bytes.copyOf().also { it[FIRST_LENGTH_OFFSET] = 127 }))
    }

    /** Invalid Unicode and non-canonical V3 fields remain unknown on both encoding and decoding. */
    @Test
    fun `summary rejects unicode replacement and invalid occurrence fields`() {
        assertNull(ExitOccurrenceSummary.encode(identity.copy(serviceVersion = "\uD800")))
        assertNull(ExitOccurrenceSummary.encode(identity.copy(installationId = "")))
        assertNull(ExitOccurrenceSummary.encode(identity.copy(versionCode = "01")))
        val simple = ApmOccurrenceContext("1", "2", "b", "r", "i")
        val bytes = ExitOccurrenceSummary.encode(simple)!!
        assertNull(ExitOccurrenceSummary.decode(bytes.copyOf().also { it[FIRST_LENGTH_OFFSET + 1] = 0xFF.toByte() }))
        // The second one-byte field follows the first length+value pair.
        assertNull(ExitOccurrenceSummary.decode(bytes.copyOf().also { it[FIRST_LENGTH_OFFSET + 3] = 'x'.code.toByte() }))
    }

    companion object {
        /** APX1's four-byte marker precedes the first unsigned length. */
        private const val FIRST_LENGTH_OFFSET = 4
    }
}
