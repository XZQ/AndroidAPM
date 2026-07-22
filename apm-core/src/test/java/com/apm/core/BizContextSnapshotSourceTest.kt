package com.apm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verifies business-context caller snapshots and non-blocking asynchronous cache behavior. */
class BizContextSnapshotSourceTest {

    /** Synchronous mode captures an immutable occurrence-time value on every emit request. */
    @Test
    fun `synchronous mode captures provider on every request`() {
        val calls = AtomicInteger(0)
        val hostContext = mutableMapOf("user" to "first")
        val source = BizContextSnapshotSource(
            provider = BizContextProvider {
                calls.incrementAndGet()
                hostContext
            },
            mode = BizContextCaptureMode.SYNCHRONOUS,
            refreshIntervalMs = REFRESH_INTERVAL_MS,
            onError = { throw AssertionError(it) }
        )

        val first = source.capture()
        hostContext["user"] = "second"
        val second = source.capture()

        assertEquals(mapOf("user" to "first"), first)
        assertEquals(mapOf("user" to "second"), second)
        assertEquals(2, calls.get())
    }

    /** Async refresh freezes host maps and retains the last good snapshot after provider failure. */
    @Test
    fun `async mode retains immutable last good snapshot`() {
        val errors = mutableListOf<Exception>()
        val hostContext = mutableMapOf("tenant" to "one")
        var fail = false
        val source = BizContextSnapshotSource(
            provider = BizContextProvider {
                if (fail) throw IOException("refresh")
                hostContext
            },
            mode = BizContextCaptureMode.ASYNC_CACHED,
            refreshIntervalMs = REFRESH_INTERVAL_MS,
            onError = errors::add
        )

        assertTrue(source.capture().isEmpty())
        source.refreshNowForTest()
        hostContext["tenant"] = "two"
        assertEquals(mapOf("tenant" to "one"), source.capture())

        fail = true
        source.refreshNowForTest()

        assertEquals(mapOf("tenant" to "one"), source.capture())
        assertEquals("refresh", errors.single().message)
    }

    /** Explicit async refresh runs on the SDK executor and is rejected after source shutdown. */
    @Test
    fun `async refresh request never invokes provider on caller`() {
        val firstRefresh = CountDownLatch(1)
        val requestedRefreshStarted = CountDownLatch(1)
        val releaseRequestedRefresh = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val source = BizContextSnapshotSource(
            provider = BizContextProvider {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    firstRefresh.countDown()
                } else if (call == 2) {
                    requestedRefreshStarted.countDown()
                    releaseRequestedRefresh.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                mapOf("thread" to Thread.currentThread().name)
            },
            mode = BizContextCaptureMode.ASYNC_CACHED,
            refreshIntervalMs = ONE_DAY_MS,
            onError = { throw AssertionError(it) }
        )

        try {
            source.start()
            assertTrue(firstRefresh.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val cacheDeadline = System.currentTimeMillis() + AWAIT_TIMEOUT_SECONDS * MILLIS_PER_SECOND
            while (source.capture().isEmpty() && System.currentTimeMillis() < cacheDeadline) {
                // The provider latch fires just before the source atomically publishes its snapshot.
                Thread.sleep(CACHE_POLL_INTERVAL_MS)
            }
            assertTrue(source.capture().getValue("thread").startsWith("apm-biz-context"))

            assertTrue(source.requestRefresh())
            assertTrue(requestedRefreshStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(source.requestRefresh())
            releaseRequestedRefresh.countDown()
        } finally {
            releaseRequestedRefresh.countDown()
            source.stop()
        }
        assertFalse(source.requestRefresh())
    }

    companion object {
        /** Ordinary refresh interval used by direct-capture tests. */
        private const val REFRESH_INTERVAL_MS = 1_000L

        /** Long cadence prevents the periodic second tick from racing explicit refresh. */
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1_000L

        /** Maximum wait for one scheduled provider call. */
        private const val AWAIT_TIMEOUT_SECONDS = 2L

        /** Seconds-to-milliseconds conversion for the cache publication deadline. */
        private const val MILLIS_PER_SECOND = 1_000L

        /** Short polling interval used only after the provider has already completed. */
        private const val CACHE_POLL_INTERVAL_MS = 5L
    }
}
