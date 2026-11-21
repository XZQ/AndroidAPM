package com.apm.remoteconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine tests for verification, rollback, expiry, ETag, and fail-safe fallback. */
class SignedRemoteConfigProviderTest {

    /** A verified 200 response is committed before typed getters publish its values. */
    @Test
    fun `verified response publishes typed values and etag`() {
        val fixture = Fixture(response(HTTP_OK, documentJson(2), etag = ETAG_TWO))

        val result = fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.UPDATED, result.status)
        assertEquals(2L, result.revision)
        assertTrue(fixture.provider.getBoolean(KEY_ENABLED, false))
        assertEquals(2_500L, fixture.provider.getLongValue(KEY_SAMPLE_BASIS_POINTS, 0L))
        assertEquals("https://collector.example/v1/events", fixture.provider.getString(KEY_ENDPOINT, ""))
        assertEquals(ETAG_TWO, fixture.store.value.etag)
        assertEquals(2L, fixture.store.value.highestRevision)
    }

    /** A lower signed revision is rejected while last-known-good values remain active. */
    @Test
    fun `rollback is rejected and last verified config remains active`() {
        val fixture = Fixture(
            response(HTTP_OK, documentJson(2), etag = ETAG_TWO),
            response(HTTP_OK, documentJson(1), etag = ETAG_ONE)
        )
        assertEquals(RemoteConfigRefreshStatus.UPDATED, fixture.provider.refreshNow().status)

        val rollback = fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.FAILED, rollback.status)
        assertEquals(ERROR_REVISION_ROLLBACK, rollback.errorCode)
        assertTrue(fixture.provider.getBoolean(KEY_ENABLED, false))
        assertEquals(2L, fixture.store.value.highestRevision)
    }

    /** The same immutable revision cannot be replaced by differently signed content. */
    @Test
    fun `same revision equivocation is rejected`() {
        val fixture = Fixture(
            response(HTTP_OK, documentJson(2), etag = ETAG_TWO),
            response(
                HTTP_OK,
                documentJson(2, signature = ALTERNATE_VALID_SIGNATURE),
                etag = ETAG_TWO_ALTERNATE
            )
        )
        fixture.provider.refreshNow()

        val equivocation = fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.FAILED, equivocation.status)
        assertEquals(ERROR_REVISION_EQUIVOCATION, equivocation.errorCode)
        assertEquals(ETAG_TWO, fixture.store.value.etag)
    }

    /** Network failure retains a non-expired verified cache, while expiry returns caller defaults. */
    @Test
    fun `network failure keeps last known good until trusted expiry`() {
        val fixture = Fixture(
            response(HTTP_OK, documentJson(2), etag = ETAG_TWO),
            failureResponse()
        )
        fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.FAILED, fixture.provider.refreshNow().status)
        assertTrue(fixture.provider.getBoolean(KEY_ENABLED, false))

        fixture.clock.elapsedTimeMs = EXPIRED_ELAPSED_TIME_MS
        assertFalse(fixture.provider.getBoolean(KEY_ENABLED, false))
        assertEquals("fallback", fixture.provider.getString(KEY_ENDPOINT, "fallback"))
    }

    /** 304 refreshes trusted time only when a verified active cache exists. */
    @Test
    fun `not modified preserves verified document`() {
        val fixture = Fixture(
            response(HTTP_OK, documentJson(2), etag = ETAG_TWO),
            response(HTTP_NOT_MODIFIED, null, etag = ETAG_TWO)
        )
        fixture.provider.refreshNow()
        fixture.clock.elapsedTimeMs += ELAPSED_ADVANCE_MS

        val result = fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.NOT_MODIFIED, result.status)
        assertTrue(fixture.provider.getBoolean(KEY_ENABLED, false))
        assertEquals(fixture.clock.elapsedTimeMs, fixture.store.value.elapsedRealtimeAtReceiptMs)
    }

    /** Authoritative 204 disables values but preserves the rollback floor and verified body. */
    @Test
    fun `no config disables cached values without erasing rollback floor`() {
        val fixture = Fixture(
            response(HTTP_OK, documentJson(2), etag = ETAG_TWO),
            response(HTTP_NO_CONTENT, null)
        )
        fixture.provider.refreshNow()

        val result = fixture.provider.refreshNow()

        assertEquals(RemoteConfigRefreshStatus.NO_CONFIG, result.status)
        assertFalse(fixture.provider.getBoolean(KEY_ENABLED, false))
        assertFalse(fixture.store.value.active)
        assertEquals(2L, fixture.store.value.highestRevision)
        assertTrue(fixture.store.value.rawJson?.isNotEmpty() == true)
    }

    /** A document expiring mid-interval notifies core without waiting for the next poll. */
    @Test
    fun `expiry notifies core without waiting for next poll`() {
        // 文档在受信接收锚点后 300ms 到期；轮询间隔远大于此，回调只能来自到期闹钟。
        // issuedAt 取接收锚点，保证 expiresAt > issuedAt 的文档有效性约束。
        val issuedAtIso = java.time.Instant.ofEpochMilli(SERVER_TIME_MS).toString()
        val expiresAtIso = java.time.Instant.ofEpochMilli(SERVER_TIME_MS + EXPIRY_NOTICE_DELAY_MS).toString()
        val fixture = Fixture(response(HTTP_OK, documentJsonWithExpiry(2, expiresAtIso, issuedAtIso), etag = ETAG_TWO))
        val accepted = java.util.concurrent.CountDownLatch(1)
        val expired = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)

        fixture.provider.start {
            if (calls.incrementAndGet() == 1) accepted.countDown() else expired.countDown()
        }
        try {
            // 初始 refresh 接受文档触发第一次回调，并按受信时钟排定到期闹钟。
            assertTrue(accepted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // 到期判定基于受信时钟；推进 fake 时钟越过 expiresAt，闹钟触发时即视为到期。
            fixture.clock.elapsedTimeMs += EXPIRY_NOTICE_DELAY_MS + 1_000L
            // 若没有到期闹钟，第二次回调只能等 60s 后的下一轮询，远超等待窗口。
            assertTrue(expired.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(fixture.provider.getBoolean(KEY_ENABLED, false))
        } finally {
            fixture.provider.stop()
        }
    }

    /** 确定性复现「notify 与 reschedule 之间恰好到期」：重排必须零延迟补发而非静默等下一轮询。 */
    @Test
    fun `expiry between notify and reschedule still delivers immediate notice`() {
        // 第一次回调（notifyIfEffectiveChanged 内、rescheduleExpiryLocked 前）把 fake 时钟
        // 推过 expiresAt：随后的重排看到 delayMs<=0——正是竞态窗口。
        // 旧实现静默返回，第二次回调只能等 60s 轮询；新实现零延迟补发。
        val issuedAtIso = java.time.Instant.ofEpochMilli(SERVER_TIME_MS).toString()
        val expiresAtIso = java.time.Instant.ofEpochMilli(SERVER_TIME_MS + EXPIRY_NOTICE_DELAY_MS).toString()
        val fixture = Fixture(response(HTTP_OK, documentJsonWithExpiry(2, expiresAtIso, issuedAtIso), etag = ETAG_TWO))
        val accepted = java.util.concurrent.CountDownLatch(1)
        val expired = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)

        fixture.provider.start {
            if (calls.incrementAndGet() == 1) {
                accepted.countDown()
                // 此刻 notifiedFingerprint 已更新为非空基线，reschedule 尚未执行。
                fixture.clock.elapsedTimeMs += EXPIRY_NOTICE_DELAY_MS + 1_000L
            } else {
                expired.countDown()
            }
        }
        try {
            assertTrue(accepted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(expired.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(fixture.provider.getBoolean(KEY_ENABLED, false))
        } finally {
            fixture.provider.stop()
        }
    }

    /** Test assembly with deterministic dependencies. */
    private class Fixture(vararg responses: Any) {
        /** In-memory durable cache. */
        val store = FakeStore()

        /** Mutable trusted clock. */
        val clock = FakeClock()

        /** Queue-backed transport. */
        private val transport = FakeTransport(responses.toMutableList())

        /** Provider under test. */
        var provider = newProvider()
            private set

        /** Rebuilds the provider so pre-seeded durable cache is loaded, modeling a process restart. */
        fun rebuildProvider() {
            provider = newProvider()
        }

        /** Creates one provider bound to the shared fixture dependencies. */
        private fun newProvider(): SignedRemoteConfigProvider = SignedRemoteConfigProvider(
            store = store,
            verifier = RemoteConfigSignatureVerifier { keyId, _, signature ->
                keyId == TEST_KEY_ID &&
                    (signature == VALID_SIGNATURE || signature == ALTERNATE_VALID_SIGNATURE)
            },
            transport = transport,
            clock = clock,
            refreshIntervalMs = TEST_REFRESH_INTERVAL_MS
        )
    }

    /** In-memory atomic cache. */
    private class FakeStore : RemoteConfigStore {
        /** Last committed value. */
        var value = EMPTY_CACHE

        /** Returns the last complete value. */
        override fun read(): CachedRemoteConfig = value

        /** Atomically replaces the test value. */
        override fun write(value: CachedRemoteConfig): Boolean {
            this.value = value
            return true
        }
    }

    /** Deterministic clock with independently mutable wall and elapsed values. */
    private class FakeClock : RemoteConfigClock {
        /** Test wall time. */
        var wallTimeMs: Long = SERVER_TIME_MS

        /** Test elapsed realtime. */
        var elapsedTimeMs: Long = INITIAL_ELAPSED_TIME_MS

        /** Returns [wallTimeMs]. */
        override fun wallTimeMs(): Long = wallTimeMs

        /** Returns [elapsedTimeMs]. */
        override fun elapsedRealtimeMs(): Long = elapsedTimeMs
    }

    /** Queue-backed transport accepting responses or exceptions. */
    private class FakeTransport(
        /** Mutable ordered outcomes. */
        private val outcomes: MutableList<Any>
    ) : RemoteConfigTransport {
        /** Returns or throws the next outcome. */
        override fun fetch(etag: String?): RemoteConfigHttpResponse {
            val next = outcomes.removeAt(0)
            if (next is Exception) {
                throw next
            }
            return next as RemoteConfigHttpResponse
        }
    }

    companion object {
        /** HTTP response codes used by the fake transport. */
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_MODIFIED = 304

        /** Pinned test key and accepted detached signature. */
        private const val TEST_KEY_ID = "key-2026"
        private const val VALID_SIGNATURE = "dmFsaWQ="
        private const val ALTERNATE_VALID_SIGNATURE = "YWx0ZXJuYXRl"

        /** Payload keys consumed by typed getters and Apm. */
        private const val KEY_ENABLED = "apm.enabled"
        private const val KEY_SAMPLE_BASIS_POINTS = "apm.sampling.default_basis_points"
        private const val KEY_ENDPOINT = "apm.upload.endpoint"

        /** Stable ETag values. */
        private const val ETAG_ONE = "\"config-1\""
        private const val ETAG_TWO = "\"config-2\""
        private const val ETAG_TWO_ALTERNATE = "\"config-2-alternate\""

        /** Deterministic clock and scheduler values. */
        private const val SERVER_TIME_MS = 1_700_000_000_000L
        private const val INITIAL_ELAPSED_TIME_MS = 1_000L
        private const val ELAPSED_ADVANCE_MS = 5_000L
        private const val EXPIRED_ELAPSED_TIME_MS = 100_000_000_000L
        private const val TEST_REFRESH_INTERVAL_MS = 60_000L

        /** Expiry delay long enough to advance the fake trusted clock before the alarm fires. */
        private const val EXPIRY_NOTICE_DELAY_MS = 1_500L

        /** Stable rollback error code asserted without inspecting logs. */
        private const val ERROR_REVISION_ROLLBACK = "remote_config_revision_rollback"
        private const val ERROR_REVISION_EQUIVOCATION = "remote_config_revision_equivocation"

        /** Empty first-run cache. */
        private val EMPTY_CACHE = CachedRemoteConfig(
            rawJson = null,
            etag = null,
            highestRevision = 0L,
            active = false,
            serverTimeAtReceiptMs = 0L,
            elapsedRealtimeAtReceiptMs = 0L
        )

        /** Creates one fake transport response. */
        private fun response(
            status: Int,
            body: String?,
            etag: String? = null
        ): RemoteConfigHttpResponse = RemoteConfigHttpResponse(
            statusCode = status,
            etag = etag,
            body = body,
            serverTimeMs = SERVER_TIME_MS
        )

        /** Creates a deterministic transport failure. */
        private fun failureResponse(): Exception = IllegalStateException("offline")

        /** Creates a valid server-shaped signed document for one revision. */
        private fun documentJson(
            revision: Long,
            signature: String = VALID_SIGNATURE
        ): String = documentJsonWithExpiry(revision, "2026-07-17T00:00:00Z", "2026-07-16T00:00:00Z", signature)

        /** Creates a valid signed document with explicit issue and expiry instants. */
        private fun documentJsonWithExpiry(
            revision: Long,
            expiresAtIso: String,
            issuedAtIso: String,
            signature: String = VALID_SIGNATURE
        ): String =
            "{\"revision\":$revision,\"issuedAt\":\"$issuedAtIso\"," +
                "\"expiresAt\":\"$expiresAtIso\",\"rolloutBasisPoints\":10000," +
                "\"payload\":{\"apm.enabled\":true," +
                "\"apm.sampling.default_basis_points\":2500," +
                "\"apm.upload.endpoint\":\"https://collector.example/v1/events\"}," +
                "\"keyId\":\"$TEST_KEY_ID\",\"signature\":\"$signature\"}"
    }
}
