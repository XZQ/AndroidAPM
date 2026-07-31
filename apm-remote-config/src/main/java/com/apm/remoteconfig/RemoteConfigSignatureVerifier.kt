package com.apm.remoteconfig

import android.util.Base64
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException

/** Verifies a detached signature over the exact canonical configuration bytes. */
fun interface RemoteConfigSignatureVerifier {

    /**
     * Verifies one signature using the pinned key identified by [keyId].
     *
     * @param keyId server-published key identifier
     * @param message canonical unsigned envelope bytes
     * @param signatureBase64 standard Base64 encoded detached signature
     * @return true only when the key exists and the signature is valid
     */
    fun verify(keyId: String, message: ByteArray, signatureBase64: String): Boolean
}

/**
 * Tink-backed Ed25519 verifier accepting server-published raw 32-byte public keys.
 *
 * Tink supplies the crypto implementation on Android API 24–32 where the platform JCA provider
 * does not guarantee Ed25519. Public keys are pinned by the host application and converted to RAW
 * Tink keysets so the server's RFC 8032 64-byte signature is verified without a Tink prefix.
 *
 * @param publicKeysBase64 map of key id to standard Base64 raw Ed25519 public key
 */
class TinkEd25519SignatureVerifier(publicKeysBase64: Map<String, String>) :
    RemoteConfigSignatureVerifier {

    /** Immutable verifier map used for key rotation and rollback-safe key selection. */
    private val verifiers: Map<String, PublicKeyVerify>

    init {
        SignatureConfig.register()
        require(publicKeysBase64.size <= MAX_PINNED_KEYS) {
            "Too many pinned Ed25519 public keys"
        }
        verifiers = publicKeysBase64.mapValues { (keyId, publicKeyBase64) ->
            createVerifier(keyId, publicKeyBase64)
        }
    }

    /** Verifies without leaking whether a missing key or malformed signature caused rejection. */
    override fun verify(keyId: String, message: ByteArray, signatureBase64: String): Boolean {
        val verifier = verifiers[keyId] ?: return false
        return try {
            val signature = decodeStandardBase64(
                value = signatureBase64,
                maximumCharacters = MAX_SIGNATURE_BASE64_CHARACTERS,
                expectedBytes = ED25519_SIGNATURE_BYTES
            )
            verifier.verify(signature, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Converts one raw Ed25519 key into a public-only Tink RAW keyset. */
    private fun createVerifier(keyId: String, publicKeyBase64: String): PublicKeyVerify {
        require(keyId.isNotBlank() && keyId.toByteArray(Charsets.UTF_8).size <= MAX_KEY_ID_BYTES) {
            "Invalid pinned Ed25519 key id"
        }
        require(keyId.none(Char::isISOControl)) { "Invalid pinned Ed25519 key id" }
        val rawKey = decodeStandardBase64(
            value = publicKeyBase64,
            maximumCharacters = MAX_PUBLIC_KEY_BASE64_CHARACTERS,
            expectedBytes = ED25519_PUBLIC_KEY_BYTES
        )
        // Ed25519PublicKey protobuf: field 2 (key_value), length 32; version 0 is omitted.
        val serializedPublicKey = byteArrayOf(PROTO_KEY_VALUE_TAG, ED25519_PUBLIC_KEY_BYTES.toByte()) + rawKey
        val keysetJson = buildString {
            append("{\"primaryKeyId\":")
            append(TINK_KEY_ID)
            append(",\"key\":[{\"keyData\":{\"typeUrl\":\"")
            append(ED25519_TYPE_URL)
            append("\",\"value\":\"")
            append(Base64.encodeToString(serializedPublicKey, Base64.NO_WRAP))
            append("\",\"keyMaterialType\":\"ASYMMETRIC_PUBLIC\"},")
            append("\"status\":\"ENABLED\",\"keyId\":")
            append(TINK_KEY_ID)
            append(",\"outputPrefixType\":\"RAW\"}]}")
        }
        val handle = TinkJsonProtoKeysetFormat.parseKeyset(
            keysetJson,
            InsecureSecretKeyAccess.get()
        )
        return handle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
    }

    /** Strictly decodes canonical standard Base64 without ignored whitespace or excess input. */
    private fun decodeStandardBase64(
        value: String,
        maximumCharacters: Int,
        expectedBytes: Int
    ): ByteArray {
        require(value.length <= maximumCharacters && STANDARD_BASE64_PATTERN.matches(value)) {
            "Invalid standard Base64"
        }
        val decoded = Base64.decode(value, Base64.NO_WRAP)
        require(decoded.size == expectedBytes) { "Invalid decoded byte length" }
        require(Base64.encodeToString(decoded, Base64.NO_WRAP) == value) {
            "Non-canonical standard Base64"
        }
        return decoded
    }

    companion object {
        /** Raw Ed25519 public key size defined by RFC 8032. */
        private const val ED25519_PUBLIC_KEY_BYTES = 32

        /** Raw Ed25519 detached signature size defined by RFC 8032. */
        private const val ED25519_SIGNATURE_BYTES = 64

        /** Input budgets keep host keysets and untrusted detached signatures bounded. */
        private const val MAX_PINNED_KEYS = 16
        private const val MAX_KEY_ID_BYTES = 128
        private const val MAX_PUBLIC_KEY_BASE64_CHARACTERS = 64
        private const val MAX_SIGNATURE_BASE64_CHARACTERS = 128

        /** Canonical padded standard Base64, excluding whitespace and URL-safe substitutions. */
        private val STANDARD_BASE64_PATTERN =
            Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

        /** Protobuf tag for bytes field number 2. */
        private const val PROTO_KEY_VALUE_TAG: Byte = 0x12

        /** Fixed local Tink key id; the server key id remains the external selector. */
        private const val TINK_KEY_ID = 1_970_801

        /** Tink type URL for an Ed25519 public key protobuf. */
        private const val ED25519_TYPE_URL =
            "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
    }
}
