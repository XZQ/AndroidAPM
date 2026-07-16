package com.apm.remoteconfig

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cross-runtime canonical JSON and Ed25519 compatibility tests. */
@RunWith(RobolectricTestRunner::class)
class CanonicalRemoteConfigJsonTest {

    /** Canonical bytes match Python sort_keys/no-whitespace/ensure_ascii=false ordering. */
    @Test
    fun `canonical envelope matches server ordering and escaping`() {
        val document = CanonicalRemoteConfigJson.parse(SIGNED_FIXTURE_JSON)

        assertEquals(EXPECTED_CANONICAL_JSON, document.canonicalBytes.toString(Charsets.UTF_8))
        assertEquals(7L, document.revision)
        assertEquals("测试\nvalue", document.payload["message"].asString)
    }

    /** Tink verifies a raw RFC 8032 signature produced by the JDK Ed25519 provider. */
    @Test
    fun `tink verifier accepts raw jdk ed25519 signature and rejects mutation`() {
        val keyPair = KeyPairGenerator.getInstance(ED25519_ALGORITHM).generateKeyPair()
        val rawPublicKey = keyPair.public.encoded.takeLast(ED25519_PUBLIC_KEY_BYTES).toByteArray()
        val publicKeyBase64 = Base64.encodeToString(rawPublicKey, Base64.NO_WRAP)
        val message = EXPECTED_CANONICAL_JSON.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance(ED25519_ALGORITHM).run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
        val signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        val verifier = TinkEd25519SignatureVerifier(mapOf(TEST_KEY_ID to publicKeyBase64))

        assertTrue(verifier.verify(TEST_KEY_ID, message, signatureBase64))
        assertFalse(verifier.verify(TEST_KEY_ID, message + 1, signatureBase64))
        assertFalse(verifier.verify("unknown", message, signatureBase64))
    }

    companion object {
        /** JCA algorithm name available on the JDK 17 test runtime. */
        private const val ED25519_ALGORITHM = "Ed25519"

        /** Raw RFC 8032 public key length. */
        private const val ED25519_PUBLIC_KEY_BYTES = 32

        /** External server key selector used in the fixture. */
        private const val TEST_KEY_ID = "key-2026"

        /** Deliberately unsorted server-shaped response with Unicode and an escaped newline. */
        private const val SIGNED_FIXTURE_JSON =
            "{\"signature\":\"c2ln\",\"keyId\":\"key-2026\",\"payload\":{" +
                "\"z\":2,\"message\":\"测试\\nvalue\",\"enabled\":true}," +
                "\"rolloutBasisPoints\":5000,\"expiresAt\":\"2026-07-17T00:00:00Z\"," +
                "\"revision\":7,\"issuedAt\":\"2026-07-16T00:00:00Z\"}"

        /** Exact unsigned bytes emitted by Python json.dumps(sort_keys=True,separators). */
        private const val EXPECTED_CANONICAL_JSON =
            "{\"expiresAt\":\"2026-07-17T00:00:00Z\"," +
                "\"issuedAt\":\"2026-07-16T00:00:00Z\",\"keyId\":\"key-2026\"," +
                "\"payload\":{\"enabled\":true,\"message\":\"测试\\nvalue\",\"z\":2}," +
                "\"revision\":7,\"rolloutBasisPoints\":5000}"
    }
}
