package com.apm.remoteconfig

import android.content.Context
import com.apm.core.ApmExecutors
import com.apm.core.throttle.ManagedDynamicConfigProvider
import com.apm.uploader.HttpHeaderProvider
import com.google.gson.JsonElement
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Pulls, verifies, caches, and atomically publishes AndroidAPM remote configuration.
 *
 * The provider fails closed: unverified, rolled-back, oversized, malformed, or expired documents
 * never reach getters. Network failure retains the last verified non-expired document; an
 * authoritative 204 disables it. The signed revision remains as a durable rollback floor even
 * after expiry or disablement.
 *
 */
class SignedRemoteConfigProvider internal constructor(
    /** Durable cache used as the startup last-known-good source and rollback floor. */
    private val store: RemoteConfigStore,
    /** Pinned-key verifier independent from platform API-level Ed25519 availability. */
    private val verifier: RemoteConfigSignatureVerifier,
    /** Bounded authenticated HTTP transport. */
    private val transport: RemoteConfigTransport,
    /** Wall/monotonic clock pair used for expiry checks. */
    private val clock: RemoteConfigClock,
    /** Polling delay retained independently from transport internals. */
    private val refreshIntervalMs: Long,
    /** Bounded logger that never receives response payload or credential values. */
    private val logger: RemoteConfigLogger = RemoteConfigLogger.APM
) : ManagedDynamicConfigProvider {

    /**
     * Creates the production provider with app-private cache, Tink Ed25519, and bounded HTTP.
     *
     * @param context application or component context used only for app-private cache storage
     * @param config endpoint and installation identity
     * @param publicKeysBase64 pinned raw Ed25519 public keys keyed by server `keyId`
     * @param headerProvider per-request short-lived credential provider
     * @param logger bounded module-local logger
     */
    constructor(
        context: Context,
        config: RemoteConfigClientConfig,
        publicKeysBase64: Map<String, String>,
        headerProvider: HttpHeaderProvider,
        logger: RemoteConfigLogger = RemoteConfigLogger.APM
    ) : this(
        store = SharedPreferencesRemoteConfigStore(context),
        verifier = TinkEd25519SignatureVerifier(publicKeysBase64),
        transport = HttpRemoteConfigTransport(config, headerProvider, AndroidRemoteConfigClock),
        clock = AndroidRemoteConfigClock,
        refreshIntervalMs = config.refreshIntervalMs,
        logger = logger
    )

    /** Serializes explicit refresh calls with the background scheduler. */
    private val refreshLock = Any()

    /** Guards lifecycle transitions and callback fingerprint updates. */
    private val lifecycleLock = Any()

    /** Atomically published immutable provider state. */
    @Volatile
    private var state: ProviderState = loadCachedState()

    /** Provider-owned scheduler, created only after core starts the managed provider. */
    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Core callback invoked when the effective verified view changes or expires. */
    @Volatile
    private var onConfigChanged: (() -> Unit)? = null

    /** Last view fingerprint observed by core, including null for fail-closed defaults. */
    private var notifiedFingerprint: EffectiveFingerprint? = null

    /** Returns a verified boolean or the caller default when absent, invalid, or expired. */
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = activePayloadValue(key) ?: return defaultValue
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            value.asBoolean
        } else {
            defaultValue
        }
    }

    /** Returns a verified integral long or the caller default. */
    override fun getLongValue(key: String, defaultValue: Long): Long {
        val value = activePayloadValue(key) ?: return defaultValue
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            value.asString.toLongOrNull() ?: defaultValue
        } else {
            defaultValue
        }
    }

    /** Returns a verified finite float or the caller default. */
    override fun getFloatValue(key: String, defaultValue: Float): Float {
        val value = activePayloadValue(key) ?: return defaultValue
        val parsed = if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            value.asString.toFloatOrNull()
        } else {
            null
        }
        return if (parsed != null && parsed.isFinite()) parsed else defaultValue
    }

    /** Returns a verified string or the caller default. */
    override fun getString(key: String, defaultValue: String): String {
        val value = activePayloadValue(key) ?: return defaultValue
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            value.asString
        } else {
            defaultValue
        }
    }

    /** Starts one immediate refresh followed by fixed-delay polling. */
    override fun start(onConfigChanged: () -> Unit) {
        synchronized(lifecycleLock) {
            if (executor != null) {
                return
            }
            this.onConfigChanged = onConfigChanged
            notifiedFingerprint = effectiveFingerprint()
            executor = ApmExecutors.newSingleThreadScheduledExecutor(REFRESH_THREAD_NAME).apply {
                scheduleWithFixedDelay(
                    { refreshNow() },
                    0L,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /** Stops polling and drops the callback without clearing the verified cache. */
    override fun stop() {
        val toStop = synchronized(lifecycleLock) {
            val current = executor
            executor = null
            onConfigChanged = null
            notifiedFingerprint = null
            current
        }
        toStop?.shutdownNow()
    }

    /**
     * Performs one synchronous network refresh; callers must use a worker thread.
     *
     * @return classified result without sensitive payload data
     */
    fun refreshNow(): RemoteConfigRefreshResult {
        val result = synchronized(refreshLock) {
            try {
                val before = state
                val response = transport.fetch(before.cache.etag)
                when (response.statusCode) {
                    HTTP_OK -> acceptDocument(response, before)
                    HTTP_NO_CONTENT -> acceptNoConfig(response, before)
                    HTTP_NOT_MODIFIED -> acceptNotModified(response, before)
                    else -> failure(ERROR_HTTP_STATUS)
                }
            } catch (error: Exception) {
                logger.error(ERROR_REFRESH_FAILED, error)
                failure(ERROR_REFRESH_FAILED)
            }
        }
        notifyIfEffectiveChanged()
        return result
    }

    /** Accepts a new signed document only after verify, rollback, and durable-cache checks. */
    private fun acceptDocument(
        response: RemoteConfigHttpResponse,
        before: ProviderState
    ): RemoteConfigRefreshResult {
        val body = response.body ?: return failure(ERROR_EMPTY_BODY)
        val document = try {
            CanonicalRemoteConfigJson.parse(body)
        } catch (error: Exception) {
            logger.error(ERROR_PARSE_FAILED, error)
            return failure(ERROR_PARSE_FAILED)
        }
        if (!verifier.verify(document.keyId, document.canonicalBytes, document.signatureBase64)) {
            return failure(ERROR_SIGNATURE_INVALID)
        }
        if (document.revision < before.cache.highestRevision) {
            return failure(ERROR_REVISION_ROLLBACK)
        }
        if (
            document.revision == before.document?.revision &&
            document.signatureBase64 != before.document.signatureBase64
        ) {
            // Revisions are immutable server records. A second valid signature for the same
            // revision is publisher equivocation, not an update that may replace the LKG view.
            return failure(ERROR_REVISION_EQUIVOCATION)
        }
        val elapsedNow = clock.elapsedRealtimeMs()
        val cache = CachedRemoteConfig(
            rawJson = body,
            etag = response.etag,
            highestRevision = maxOf(before.cache.highestRevision, document.revision),
            active = true,
            serverTimeAtReceiptMs = response.serverTimeMs,
            elapsedRealtimeAtReceiptMs = elapsedNow
        )
        if (!store.write(cache)) {
            return failure(ERROR_CACHE_WRITE)
        }
        state = ProviderState(document, cache)
        logger.debug("Remote config revision accepted")
        return RemoteConfigRefreshResult(RemoteConfigRefreshStatus.UPDATED, document.revision)
    }

    /** Applies an authoritative empty rollout while retaining the highest verified revision. */
    private fun acceptNoConfig(
        response: RemoteConfigHttpResponse,
        before: ProviderState
    ): RemoteConfigRefreshResult {
        val cache = before.cache.copy(
            etag = null,
            active = false,
            serverTimeAtReceiptMs = response.serverTimeMs,
            elapsedRealtimeAtReceiptMs = clock.elapsedRealtimeMs()
        )
        if (!store.write(cache)) {
            return failure(ERROR_CACHE_WRITE)
        }
        state = before.copy(cache = cache)
        return RemoteConfigRefreshResult(
            RemoteConfigRefreshStatus.NO_CONFIG,
            before.document?.revision
        )
    }

    /** Refreshes the trusted-time anchor for a valid cached ETag. */
    private fun acceptNotModified(
        response: RemoteConfigHttpResponse,
        before: ProviderState
    ): RemoteConfigRefreshResult {
        val document = before.document ?: return failure(ERROR_NOT_MODIFIED_WITHOUT_CACHE)
        if (!before.cache.active) {
            return failure(ERROR_NOT_MODIFIED_WITHOUT_CACHE)
        }
        val cache = before.cache.copy(
            serverTimeAtReceiptMs = response.serverTimeMs,
            elapsedRealtimeAtReceiptMs = clock.elapsedRealtimeMs()
        )
        if (!store.write(cache)) {
            return failure(ERROR_CACHE_WRITE)
        }
        state = before.copy(cache = cache)
        return RemoteConfigRefreshResult(RemoteConfigRefreshStatus.NOT_MODIFIED, document.revision)
    }

    /** Loads and re-verifies the cache; corruption never becomes an active configuration. */
    private fun loadCachedState(): ProviderState {
        val cached = store.read()
        val rawJson = cached.rawJson ?: return ProviderState(null, cached.copy(active = false))
        val document = try {
            CanonicalRemoteConfigJson.parse(rawJson)
        } catch (error: Exception) {
            logger.error(ERROR_CACHE_PARSE, error)
            return ProviderState(null, cached.copy(active = false))
        }
        if (!verifier.verify(document.keyId, document.canonicalBytes, document.signatureBase64)) {
            logger.warn(ERROR_CACHE_SIGNATURE)
            return ProviderState(null, cached.copy(active = false))
        }
        if (document.revision < cached.highestRevision) {
            logger.warn(ERROR_REVISION_ROLLBACK)
            return ProviderState(null, cached.copy(active = false))
        }
        val rollbackFloor = maxOf(cached.highestRevision, document.revision)
        return ProviderState(document, cached.copy(highestRevision = rollbackFloor))
    }

    /** Returns one payload element only while the server-active document is not expired. */
    private fun activePayloadValue(key: String): JsonElement? {
        val snapshot = state
        val document = snapshot.document ?: return null
        if (!snapshot.cache.active || trustedNowMs(snapshot.cache) >= document.expiresAtMs) {
            return null
        }
        return document.payload.get(key)
    }

    /** Computes trusted current time from server Date plus elapsed realtime until reboot. */
    private fun trustedNowMs(cache: CachedRemoteConfig): Long {
        val elapsedNow = clock.elapsedRealtimeMs()
        val anchored = cache.serverTimeAtReceiptMs > 0L &&
            cache.elapsedRealtimeAtReceiptMs > 0L &&
            elapsedNow >= cache.elapsedRealtimeAtReceiptMs
        return if (anchored) {
            cache.serverTimeAtReceiptMs + (elapsedNow - cache.elapsedRealtimeAtReceiptMs)
        } else {
            // Elapsed realtime moving backwards means reboot; wall time is the only local fallback.
            clock.wallTimeMs()
        }
    }

    /** Builds a stable token for callback deduplication and expiry-driven disablement. */
    private fun effectiveFingerprint(): EffectiveFingerprint? {
        val snapshot = state
        val document = snapshot.document ?: return null
        if (!snapshot.cache.active || trustedNowMs(snapshot.cache) >= document.expiresAtMs) {
            return null
        }
        return EffectiveFingerprint(document.revision, document.signatureBase64)
    }

    /** Notifies core once after the effective verified view changes. */
    private fun notifyIfEffectiveChanged() {
        val callback = synchronized(lifecycleLock) {
            val current = effectiveFingerprint()
            if (current == notifiedFingerprint) {
                return
            }
            notifiedFingerprint = current
            onConfigChanged
        }
        try {
            callback?.invoke()
        } catch (error: Exception) {
            // Host callbacks cannot kill the polling worker.
            logger.error(ERROR_CALLBACK, error)
        }
    }

    /** Creates a stable failed result and bounded warning. */
    private fun failure(code: String): RemoteConfigRefreshResult {
        logger.warn(code)
        return RemoteConfigRefreshResult(RemoteConfigRefreshStatus.FAILED, errorCode = code)
    }

    /** Atomically published state pair. */
    private data class ProviderState(
        /** Last verified document, retained for rollback even when inactive. */
        val document: RemoteConfigDocument?,
        /** Durable metadata controlling activity, ETag, time, and rollback. */
        val cache: CachedRemoteConfig
    )

    /** Effective callback token. */
    private data class EffectiveFingerprint(
        /** Verified revision. */
        val revision: Long,
        /** Signature distinguishes an unexpected same-revision publisher equivocation. */
        val signatureBase64: String
    )

    companion object {
        /** Provider thread name passed through ApmExecutors. */
        private const val REFRESH_THREAD_NAME = "remote-config"

        /** Supported successful HTTP statuses. */
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_MODIFIED = 304

        /** Stable diagnostic codes. */
        private const val ERROR_HTTP_STATUS = "remote_config_http_status"
        private const val ERROR_REFRESH_FAILED = "remote_config_refresh_failed"
        private const val ERROR_EMPTY_BODY = "remote_config_empty_body"
        private const val ERROR_PARSE_FAILED = "remote_config_parse_failed"
        private const val ERROR_SIGNATURE_INVALID = "remote_config_signature_invalid"
        private const val ERROR_REVISION_ROLLBACK = "remote_config_revision_rollback"
        private const val ERROR_REVISION_EQUIVOCATION = "remote_config_revision_equivocation"
        private const val ERROR_CACHE_WRITE = "remote_config_cache_write_failed"
        private const val ERROR_NOT_MODIFIED_WITHOUT_CACHE = "remote_config_304_without_cache"
        private const val ERROR_CACHE_PARSE = "remote_config_cache_parse_failed"
        private const val ERROR_CACHE_SIGNATURE = "remote_config_cache_signature_invalid"
        private const val ERROR_CALLBACK = "remote_config_callback_failed"
    }
}
