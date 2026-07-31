package com.apm.remoteconfig

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

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
        assertFalse(verifier.verify(TEST_KEY_ID, message, "$signatureBase64\n"))
        assertFalse(verifier.verify(TEST_KEY_ID, message, signatureBase64.withNonCanonicalPadBits()))
        assertFalse(
            verifier.verify(
                TEST_KEY_ID,
                message,
                Base64.encodeToString(ByteArray(ED25519_SIGNATURE_BYTES - 1), Base64.NO_WRAP)
            )
        )
        assertFalse(verifier.verify(TEST_KEY_ID, message, "A".repeat(OVERSIZED_BASE64_CHARACTERS)))
    }

    /** Duplicate keys are rejected before Gson can silently retain only the final value. */
    @Test
    fun `duplicate json keys are rejected`() {
        val duplicate = SIGNED_FIXTURE_JSON.replace(
            "\"revision\":7",
            "\"revision\":7,\"revision\":8"
        )

        assertMalformed(duplicate)
    }

    /** Deep documents cannot reach the recursive canonicalizer or exhaust the thread stack. */
    @Test
    fun `excessive json nesting is rejected before canonicalization`() {
        val nestedPayload = "{\"child\":".repeat(EXCESSIVE_NESTING) +
            "true" +
            "}".repeat(EXCESSIVE_NESTING)
        val payloadStart = SIGNED_FIXTURE_JSON.indexOf(PAYLOAD_PREFIX) + PAYLOAD_PREFIX.length
        val payloadEnd = SIGNED_FIXTURE_JSON.indexOf(PAYLOAD_SUFFIX)
        val document = SIGNED_FIXTURE_JSON.substring(0, payloadStart) +
            nestedPayload +
            SIGNED_FIXTURE_JSON.substring(payloadEnd)

        assertMalformed(document)
    }

    /** Cached documents are protected by an absolute parser bound even without HTTP transport. */
    @Test
    fun `oversized raw json is rejected`() {
        assertMalformed(" ".repeat(OVERSIZED_JSON_CHARACTERS))
    }

    /** Fixed-seed syntax mutations fail closed without stack overflow or parser exception leakage. */
    @Test
    fun `fixed seed remote config corpus remains bounded`() {
        val random = Random(REMOTE_CONFIG_CORPUS_SEED)
        repeat(REMOTE_CONFIG_CORPUS_MUTATIONS) {
            val candidate = SIGNED_FIXTURE_JSON.toCharArray()
            repeat(1 + random.nextInt(MAX_MUTATIONS_PER_SAMPLE)) {
                candidate[random.nextInt(candidate.size)] = JSON_MUTATION_CHARACTERS[
                    random.nextInt(JSON_MUTATION_CHARACTERS.size)
                ]
            }
            try {
                val parsed = CanonicalRemoteConfigJson.parse(candidate.concatToString())
                assertTrue(parsed.canonicalBytes.isNotEmpty())
            } catch (_: IllegalArgumentException) {
                // Rejection is an expected fuzz outcome.
            }
        }
    }

    /** Only the stable malformed-document exception is accepted by adversarial parser tests. */
    private fun assertMalformed(rawJson: String) {
        try {
            CanonicalRemoteConfigJson.parse(rawJson)
            fail("Expected malformed remote config rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    /** Changes only ignored padding bits so a permissive decoder would yield identical bytes. */
    private fun String.withNonCanonicalPadBits(): String {
        val characters = toCharArray()
        val index = length - BASE64_DOUBLE_PADDING_LENGTH - 1
        val alphabetIndex = STANDARD_BASE64_ALPHABET.indexOf(characters[index])
        check(alphabetIndex >= 0 && alphabetIndex % BASE64_PAD_BIT_VARIANTS == 0)
        characters[index] = STANDARD_BASE64_ALPHABET[alphabetIndex + 1]
        return characters.concatToString()
    }

    companion object {
        /** JCA algorithm name available on the JDK 17 test runtime. */
        private const val ED25519_ALGORITHM = "Ed25519"

        /** Raw RFC 8032 public key length. */
        private const val ED25519_PUBLIC_KEY_BYTES = 32

        /** Raw RFC 8032 detached signature length. */
        private const val ED25519_SIGNATURE_BYTES = 64

        /** External server key selector used in the fixture. */
        private const val TEST_KEY_ID = "key-2026"

        /** Stable envelope delimiters used to replace the complete payload without decoding text. */
        private const val PAYLOAD_PREFIX = "\"payload\":"
        private const val PAYLOAD_SUFFIX = ",\"rolloutBasisPoints\""

        /** Parser and mutation corpus boundaries. */
        private const val EXCESSIVE_NESTING = 40
        private const val OVERSIZED_JSON_CHARACTERS = 2 * 1024 * 1024 + 1
        private const val OVERSIZED_BASE64_CHARACTERS = 132
        private const val REMOTE_CONFIG_CORPUS_SEED = 0x52_43_46
        private const val REMOTE_CONFIG_CORPUS_MUTATIONS = 512
        private const val MAX_MUTATIONS_PER_SAMPLE = 3
        private const val BASE64_DOUBLE_PADDING_LENGTH = 2
        private const val BASE64_PAD_BIT_VARIANTS = 16
        private const val STANDARD_BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private val JSON_MUTATION_CHARACTERS = charArrayOf(
            '{', '}', '[', ']', '"', '\\', ',', ':', '\u0000', '0', 'x'
        )

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
