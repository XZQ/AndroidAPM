package com.apm.remoteconfig

import com.google.gson.JsonObject
import java.net.URL

/**
 * Immutable connection and identity settings for the signed remote configuration endpoint.
 *
 * @property endpoint full HTTPS URL ending in `/v1/config`
 * @property appId application id authorized by the ingest credential
 * @property environment deployment environment authorized by the ingest credential
 * @property installationId anonymous stable installation identifier used for rollout assignment
 * @property refreshIntervalMs fixed delay between completed refresh attempts
 * @property connectTimeoutMs bounded connection timeout
 * @property readTimeoutMs bounded response timeout
 * @property maxResponseBytes maximum accepted JSON response bytes
 */
data class RemoteConfigClientConfig(
    val endpoint: String,
    val appId: String,
    val environment: String,
    val installationId: String,
    val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES
) {
    init {
        val parsedEndpoint = runCatching { URL(endpoint) }
            .getOrElse { error -> throw IllegalArgumentException("Invalid remote config endpoint", error) }
        val secureEndpoint = parsedEndpoint.protocol == HTTPS_SCHEME
        val loopbackTestEndpoint = parsedEndpoint.protocol == HTTP_SCHEME &&
            parsedEndpoint.host == LOOPBACK_HOST
        require((secureEndpoint || loopbackTestEndpoint) && parsedEndpoint.userInfo == null) {
            "Remote config endpoint must use HTTPS outside loopback tests"
        }
        require(endpoint.length <= MAX_ENDPOINT_CHARACTERS) { "Remote config endpoint is too long" }
        require(isSafeIdentity(appId, MAX_APP_ID_BYTES)) { "Invalid appId" }
        require(isSafeIdentity(environment, MAX_ENVIRONMENT_BYTES)) { "Invalid environment" }
        require(isSafeIdentity(installationId, MAX_INSTALLATION_ID_BYTES)) {
            "Invalid installationId"
        }
        require(refreshIntervalMs >= MIN_REFRESH_INTERVAL_MS) { "refresh interval is too small" }
        require(connectTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "invalid connect timeout" }
        require(readTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "invalid read timeout" }
        require(maxResponseBytes in MIN_RESPONSE_BYTES..MAX_RESPONSE_BYTES) {
            "invalid response size limit"
        }
    }

    /** Returns whether an identity is non-blank, bounded UTF-8, and control-character free. */
    private fun isSafeIdentity(value: String, maxBytes: Int): Boolean {
        return value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= maxBytes &&
            value.all { character -> character.code >= HEADER_VALUE_MIN }
    }

    companion object {
        /** Production default refresh interval: fifteen minutes. */
        private const val DEFAULT_REFRESH_INTERVAL_MS = 15L * 60L * 1_000L

        /** Default connect timeout. */
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

        /** Default read timeout. */
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000

        /** Default response budget: 256 KiB. */
        private const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1_024

        /** Minimum production polling interval accepted by this client. */
        private const val MIN_REFRESH_INTERVAL_MS = 10_000L

        /** Minimum bounded socket timeout. */
        private const val MIN_TIMEOUT_MS = 100

        /** Maximum bounded socket timeout. */
        private const val MAX_TIMEOUT_MS = 60_000

        /** Minimum useful JSON response budget. */
        private const val MIN_RESPONSE_BYTES = 1_024

        /** Maximum allowed JSON response budget. */
        private const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024

        /** Allowed endpoint scheme/host values. */
        private const val HTTPS_SCHEME = "https"
        private const val HTTP_SCHEME = "http"
        private const val LOOPBACK_HOST = "127.0.0.1"

        /** Protocol/header identity bounds aligned with AndroidAPM-Server. */
        private const val MAX_ENDPOINT_CHARACTERS = 2_048
        private const val MAX_APP_ID_BYTES = 256
        private const val MAX_ENVIRONMENT_BYTES = 128
        private const val MAX_INSTALLATION_ID_BYTES = 256
        private const val HEADER_VALUE_MIN = 0x20
    }
}

/** Stable refresh outcome that does not expose response payload or credentials. */
enum class RemoteConfigRefreshStatus {
    /** A newly verified effective document was stored and published. */
    UPDATED,

    /** The server confirmed the cached ETag is current. */
    NOT_MODIFIED,

    /** The server authoritatively reported no active configuration for this installation. */
    NO_CONFIG,

    /** Transport, parsing, signature, rollback, or persistence validation failed. */
    FAILED
}

/**
 * Result of one synchronous refresh attempt.
 *
 * @property status classified outcome
 * @property revision verified revision when known
 * @property errorCode stable non-sensitive diagnostic code when failed
 */
data class RemoteConfigRefreshResult(
    val status: RemoteConfigRefreshStatus,
    val revision: Long? = null,
    val errorCode: String? = null
)

/** Minimal logger that keeps the remote-config module independent from core internal loggers. */
interface RemoteConfigLogger {

    /** Records a bounded debug message without payload or credential values. */
    fun debug(message: String)

    /** Records a bounded warning message without payload or credential values. */
    fun warn(message: String)

    /** Records a bounded failure code and throwable type. */
    fun error(message: String, throwable: Throwable? = null)

    companion object {
        /** No-op logger for hosts that do not want module-local Logcat output. */
        val NONE: RemoteConfigLogger = object : RemoteConfigLogger {
            override fun debug(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }

        /** Default logger routing rejected control-plane inputs to SDK internal diagnostics. */
        val APM: RemoteConfigLogger = object : RemoteConfigLogger {
            override fun debug(message: String) = Unit
            override fun warn(message: String) = com.apm.core.Apm.recordInternalError(message)
            override fun error(message: String, throwable: Throwable?) =
                com.apm.core.Apm.recordInternalError(message, throwable)
        }
    }
}

/** Parsed and signature-verified immutable configuration document. */
internal data class RemoteConfigDocument(
    /** Monotonic server revision. */
    val revision: Long,
    /** Signed issue time in epoch milliseconds. */
    val issuedAtMs: Long,
    /** Signed expiry time in epoch milliseconds. */
    val expiresAtMs: Long,
    /** Signed rollout size retained for audit and diagnostics. */
    val rolloutBasisPoints: Int,
    /** Flat or nested JSON payload exposed through typed getters. */
    val payload: JsonObject,
    /** Pinned public-key selector. */
    val keyId: String,
    /** Standard Base64 detached Ed25519 signature. */
    val signatureBase64: String,
    /** Exact deterministic bytes covered by the signature. */
    val canonicalBytes: ByteArray,
    /** Original bounded response retained for cache re-verification. */
    val rawJson: String
)

/** Durable cache state including rollback floor and trusted-time anchor. */
internal data class CachedRemoteConfig(
    /** Last verified raw document, retained even when inactive for rollback protection. */
    val rawJson: String?,
    /** HTTP entity tag associated with [rawJson]. */
    val etag: String?,
    /** Highest verified revision ever accepted by this installation. */
    val highestRevision: Long,
    /** Whether the server most recently considered the cached document active. */
    val active: Boolean,
    /** Trusted server or receipt wall time at the last successful response. */
    val serverTimeAtReceiptMs: Long,
    /** Elapsed realtime at the same receipt, valid only until device reboot. */
    val elapsedRealtimeAtReceiptMs: Long
)

/** Bounded HTTP response consumed by the provider state machine. */
internal data class RemoteConfigHttpResponse(
    /** HTTP status code. */
    val statusCode: Int,
    /** Response ETag when present. */
    val etag: String?,
    /** UTF-8 response body for 200, otherwise null. */
    val body: String?,
    /** Server Date header or local receipt time fallback. */
    val serverTimeMs: Long
)
