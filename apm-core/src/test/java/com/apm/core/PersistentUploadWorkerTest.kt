package com.apm.core

import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.model.ApmEvent
import com.apm.storage.PendingEvent
import com.apm.storage.PendingEventStore
import com.apm.uploader.BatchApmUploader
import com.apm.uploader.RetryPolicy
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies durable replay and acknowledgement behavior.
 */
class PersistentUploadWorkerTest {

    /** Hostile retry hints must not create negative waits or effectively permanent sleeps. */
    @Test
    fun `retry wait is bounded for hostile hints`() {
        assertEquals(MIN_EXPECTED_WAIT_MS, boundedRetryWaitMs(-1L, -5L))
        assertEquals(MAX_EXPECTED_WAIT_MS, boundedRetryWaitMs(1_000L, Long.MAX_VALUE))
        assertEquals(NORMAL_HINT_MS, boundedRetryWaitMs(1_000L, NORMAL_HINT_MS))
    }

    /** Successfully uploaded rows are acknowledged only after batch acceptance. */
    @Test
    fun `successful batch is deleted from durable store`() {
        val store = FakePendingStore(mutableListOf(PendingEvent(7L, event("saved"), 0)))
        val uploaded = CountDownLatch(1)
        val uploader = RecordingBatchUploader(uploaded)
        val worker = PersistentUploadWorker(
            store = store,
            uploader = uploader,
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 10,
            logger = NoOpLogger,
            selfMonitor = null
        )

        worker.signal()
        assertTrue(uploaded.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.deleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        worker.shutdown()

        assertEquals(listOf("saved"), uploader.names)
        assertEquals(emptyList<Long>(), store.rows.map(PendingEvent::id))
        assertTrue(store.claimedOwner.isNotBlank())
        assertEquals(store.claimedOwner, store.acknowledgedOwner)
    }

    /** Failed uploads keep rows durable and increment retry counters. */
    @Test
    fun `failed batch remains pending and is marked for retry`() {
        val store = FakePendingStore(mutableListOf(PendingEvent(11L, event("kept"), 0)))
        val attempted = CountDownLatch(1)
        val uploader = FailingBatchUploader(attempted)
        val worker = PersistentUploadWorker(
            store = store,
            uploader = uploader,
            retryPolicy = RetryPolicy(maxRetries = 1, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 10,
            logger = NoOpLogger,
            selfMonitor = null
        )

        worker.signal()
        assertTrue(attempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.markedRetry.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        worker.shutdown()

        assertEquals(listOf(11L), store.rows.map(PendingEvent::id))
        assertEquals(listOf(1), store.rows.map(PendingEvent::retryCount))
        assertEquals(store.claimedOwner, store.failedOwner)
    }

    /** Disabling retries removes a failed row immediately after its initial upload attempt. */
    @Test
    fun `retry disabled prunes row after initial failure`() {
        val store = FakePendingStore(mutableListOf(PendingEvent(13L, event("exhausted"), 0)))
        val attempted = CountDownLatch(1)
        val selfMonitor = SdkSelfMonitor()
        val worker = PersistentUploadWorker(
            store = store,
            uploader = FailingBatchUploader(attempted),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 1,
            logger = NoOpLogger,
            selfMonitor = selfMonitor
        )

        assertTrue(attempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.pruned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        worker.shutdown()

        assertEquals(emptyList<Long>(), store.rows.map(PendingEvent::id))
        assertEquals(store.claimedOwner, store.failedOwner)
        assertEquals(1L, selfMonitor.getDropCount(SdkDropReason.OUTBOX_EXPIRED_OR_RETRY_EXHAUSTED))
        assertEquals(1L, selfMonitor.getUnattributedDropPriorityCount())
    }

    /** A recoverable transport exception follows the same durable retry path as a false result. */
    @Test
    fun `throwing batch remains pending and is marked for retry`() {
        val store = FakePendingStore(mutableListOf(PendingEvent(12L, event("throwing"), 0)))
        val attempted = CountDownLatch(1)
        val worker = PersistentUploadWorker(
            store = store,
            uploader = ThrowingBatchUploader(attempted),
            retryPolicy = RetryPolicy(maxRetries = 1, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 1,
            logger = NoOpLogger,
            selfMonitor = null
        )

        assertTrue(attempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.markedRetry.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        worker.shutdown()

        assertEquals(listOf(12L), store.rows.map(PendingEvent::id))
        assertEquals(listOf(1), store.rows.map(PendingEvent::retryCount))
    }

    /** Non-batch uploaders are invoked once per event and acknowledged as one durable batch. */
    @Test
    fun `single event uploader fallback deletes batch after all events succeed`() {
        val store = FakePendingStore(
            mutableListOf(
                PendingEvent(21L, event("first"), 0),
                PendingEvent(22L, event("second"), 0)
            )
        )
        val uploaded = CountDownLatch(2)
        val uploader = RecordingSingleUploader(uploaded)
        val worker = PersistentUploadWorker(
            store = store,
            uploader = uploader,
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 10,
            logger = NoOpLogger,
            selfMonitor = null
        )

        worker.signal()
        assertTrue(uploaded.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.deleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        worker.shutdown()

        assertEquals(listOf("first", "second"), uploader.names)
        assertEquals(emptyList<Long>(), store.rows.map(PendingEvent::id))
    }

    /** Shutdown releases every lease owned by this worker instance. */
    @Test
    fun `shutdown releases worker claims`() {
        val store = FakePendingStore(mutableListOf())
        val worker = PersistentUploadWorker(
            store = store,
            uploader = RecordingBatchUploader(CountDownLatch(0)),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 10,
            logger = NoOpLogger,
            selfMonitor = null
        )

        worker.shutdown()

        assertTrue(store.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.releasedOwner.isNotBlank())
    }

    /** A custom uploader shutdown failure must not skip executor stop or claim release. */
    @Test
    fun `shutdown continues after uploader failure`() {
        val store = FakePendingStore(mutableListOf())
        val worker = PersistentUploadWorker(
            store = store,
            uploader = ShutdownThrowingUploader(),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 1,
            logger = NoOpLogger,
            selfMonitor = null
        )

        worker.shutdown()

        assertTrue(store.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(store.releasedOwner.isNotBlank())
    }

    /** Fatal VM errors from custom uploaders must not be converted into ordinary retry failures. */
    @Test
    fun `fatal uploader error reaches uncaught handler`() {
        val fatal = OutOfMemoryError("fatal uploader")
        val uncaught = AtomicReference<Throwable?>()
        val observed = CountDownLatch(1)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name.startsWith(PERSISTENT_WORKER_THREAD_PREFIX)) {
                uncaught.set(error)
                observed.countDown()
            } else {
                previousHandler?.uncaughtException(thread, error)
            }
        }
        val worker = PersistentUploadWorker(
            store = FakePendingStore(mutableListOf(PendingEvent(31L, event("fatal"), 0))),
            uploader = FatalBatchUploader(fatal),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 1,
            logger = NoOpLogger,
            selfMonitor = null
        )
        try {
            assertTrue(observed.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertSame(fatal, uncaught.get())
        } finally {
            worker.shutdown()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    /** Fatal VM errors from durable claim code must not be converted into an idle poll. */
    @Test
    fun `fatal claim error reaches uncaught handler`() {
        val fatal = OutOfMemoryError("fatal claim")
        val uncaught = AtomicReference<Throwable?>()
        val observed = CountDownLatch(1)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name.startsWith(PERSISTENT_WORKER_THREAD_PREFIX)) {
                uncaught.set(error)
                observed.countDown()
            } else {
                previousHandler?.uncaughtException(thread, error)
            }
        }
        val worker = PersistentUploadWorker(
            store = FakePendingStore(mutableListOf(), claimFatal = fatal),
            uploader = RecordingBatchUploader(CountDownLatch(0)),
            retryPolicy = RetryPolicy(maxRetries = 0, baseDelayMs = RETRY_DELAY_MS),
            batchSize = 1,
            logger = NoOpLogger,
            selfMonitor = null
        )
        try {
            assertTrue(observed.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertSame(fatal, uncaught.get())
        } finally {
            worker.shutdown()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    /**
     * Creates a test event.
     *
     * @param name event name
     * @return event instance
     */
    private fun event(name: String): ApmEvent = ApmEvent(module = "core", name = name)

    /** Thread-safe in-memory durable store used by the worker test. */
    private class FakePendingStore(
        /** Mutable pending rows. */
        val rows: MutableList<PendingEvent>,
        /** Optional fatal error thrown while claiming rows. */
        private val claimFatal: Error? = null
    ) : PendingEventStore {
        /** Acknowledgement signal. */
        val deleted = CountDownLatch(1)

        /** Retry update signal. */
        val markedRetry = CountDownLatch(1)

        /** Lease release signal. */
        val released = CountDownLatch(1)

        /** Exhausted-row prune signal. */
        val pruned = CountDownLatch(1)

        /** Most recent claim owner. */
        @Volatile
        var claimedOwner: String = ""

        /** Most recent acknowledgement owner. */
        @Volatile
        var acknowledgedOwner: String = ""

        /** Most recent failure owner. */
        @Volatile
        var failedOwner: String = ""

        /** Most recent release owner. */
        @Volatile
        var releasedOwner: String = ""

        /** Adds an event using a generated row id. */
        @Synchronized
        override fun append(event: ApmEvent) {
            rows += PendingEvent((rows.maxOfOrNull(PendingEvent::id) ?: 0L) + 1L, event, 0)
        }

        /** Returns a textual view for the base storage contract. */
        @Synchronized
        override fun readRecent(limit: Int): List<String> = rows.takeLast(limit).map { it.event.name }

        /** Clears pending rows. */
        @Synchronized
        override fun clear() {
            rows.clear()
        }

        /** Reads pending rows in insertion order. */
        @Synchronized
        override fun readPending(limit: Int): List<PendingEvent> = rows.take(limit)

        /** Claims rows while recording the worker owner. */
        @Synchronized
        override fun claimPending(ownerId: String, limit: Int, nowMs: Long, leaseDurationMs: Long): List<PendingEvent> {
            claimFatal?.let { throw it }
            claimedOwner = ownerId
            return rows.take(limit)
        }

        /** Removes acknowledged rows. */
        @Synchronized
        override fun deletePending(ids: List<Long>): Int {
            val before = rows.size
            rows.removeAll { it.id in ids }
            deleted.countDown()
            return before - rows.size
        }

        /** Acknowledges rows only through the owner-aware API. */
        @Synchronized
        override fun acknowledgeClaim(ownerId: String, ids: List<Long>): Int {
            acknowledgedOwner = ownerId
            return deletePending(ids)
        }

        /** Increments retry counters for selected rows. */
        @Synchronized
        override fun markRetry(ids: List<Long>) {
            rows.replaceAll { row ->
                if (row.id in ids) row.copy(retryCount = row.retryCount + 1) else row
            }
            markedRetry.countDown()
        }

        /** Marks failed rows only through the owner-aware API. */
        @Synchronized
        override fun failClaim(ownerId: String, ids: List<Long>) {
            failedOwner = ownerId
            markRetry(ids)
        }

        /** Records explicit lease release on shutdown. */
        @Synchronized
        override fun releaseClaims(ownerId: String): Int {
            releasedOwner = ownerId
            released.countDown()
            return rows.size
        }

        /** Returns the current pending count. */
        @Synchronized
        override fun pendingCount(): Int = rows.size

        /** Removes rows whose retry counter reached the configured failure limit. */
        @Synchronized
        override fun pruneExpired(maxRetryCount: Int, maxAgeMs: Long): Int {
            val before = rows.size
            rows.removeAll { row -> row.retryCount >= maxRetryCount }
            val deletedCount = before - rows.size
            if (deletedCount > 0) {
                pruned.countDown()
            }
            return deletedCount
        }
    }

    /** Batch uploader that records accepted event names. */
    private class RecordingBatchUploader(private val uploaded: CountDownLatch) : BatchApmUploader {
        /** Names accepted by the uploader. */
        val names = mutableListOf<String>()

        /** Records and accepts a complete batch. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean {
            names += events.map(ApmEvent::name)
            uploaded.countDown()
            return true
        }
    }

    /** Batch uploader that rejects all work while recording the attempt. */
    private class FailingBatchUploader(private val attempted: CountDownLatch) : BatchApmUploader {
        /** Rejects the batch to exercise durable retry retention. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean {
            attempted.countDown()
            return false
        }
    }

    /** Batch uploader that throws one recoverable transport exception. */
    private class ThrowingBatchUploader(private val attempted: CountDownLatch) : BatchApmUploader {

        /** Signals the attempt and throws a recoverable network-style exception. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean {
            attempted.countDown()
            throw IOException("transport")
        }
    }

    /** Batch uploader that exposes whether fatal VM errors are swallowed by recovery code. */
    private class FatalBatchUploader(
        /** Fatal error emitted by the transport. */
        private val fatal: Error
    ) : BatchApmUploader {

        /** Throws the configured fatal error. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean {
            throw fatal
        }
    }

    /** Uploader whose shutdown exposes cleanup ordering failures. */
    private class ShutdownThrowingUploader : BatchApmUploader {

        /** Accepts no events in this empty-store test. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean = true

        /** Simulates an integration-owned shutdown failure. */
        override fun shutdown() {
            throw IllegalStateException("shutdown failure")
        }
    }

    /** Single-event uploader used to verify non-batch fallback behavior. */
    private class RecordingSingleUploader(private val uploaded: CountDownLatch) : com.apm.uploader.ApmUploader {
        /** Names accepted by the uploader. */
        val names = mutableListOf<String>()

        /** Records and accepts one event. */
        override fun upload(event: ApmEvent): Boolean {
            names += event.name
            uploaded.countDown()
            return true
        }
    }

    /** Logger used when no diagnostic output is expected. */
    private object NoOpLogger : ApmLogger {
        /** Ignores debug output. */
        override fun d(message: String) = Unit

        /** Ignores warning output. */
        override fun w(message: String) = Unit

        /** Ignores error output. */
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    companion object {
        /** Expected lower worker wait bound. */
        private const val MIN_EXPECTED_WAIT_MS = 10L

        /** Expected upper retry wait bound. */
        private const val MAX_EXPECTED_WAIT_MS = 60_000L

        /** Normal Retry-After hint that should win over local backoff. */
        private const val NORMAL_HINT_MS = 5_000L

        /** Thread-name prefix used to isolate the worker's uncaught fatal error. */
        private const val PERSISTENT_WORKER_THREAD_PREFIX = "apm-persistent-upload"
        /** Minimal retry delay for the test worker. */
        private const val RETRY_DELAY_MS = 30_000L

        /** Maximum wait for worker completion. */
        private const val TEST_TIMEOUT_SECONDS = 5L
    }
}
