package com.apm.core

import com.apm.model.ApmEvent
import com.apm.storage.PendingEvent
import com.apm.storage.PendingEventStore
import com.apm.uploader.BatchApmUploader
import com.apm.uploader.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies durable replay and acknowledgement behavior.
 */
class PersistentUploadWorkerTest {

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
        val rows: MutableList<PendingEvent>
    ) : PendingEventStore {
        /** Acknowledgement signal. */
        val deleted = CountDownLatch(1)

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

        /** Removes acknowledged rows. */
        @Synchronized
        override fun deletePending(ids: List<Long>): Int {
            val before = rows.size
            rows.removeAll { it.id in ids }
            deleted.countDown()
            return before - rows.size
        }

        /** Increments retry counters for selected rows. */
        @Synchronized
        override fun markRetry(ids: List<Long>) {
            rows.replaceAll { row ->
                if (row.id in ids) row.copy(retryCount = row.retryCount + 1) else row
            }
        }

        /** Returns the current pending count. */
        @Synchronized
        override fun pendingCount(): Int = rows.size
    }

    /** Batch uploader that records accepted event names. */
    private class RecordingBatchUploader(
        /** Upload completion signal. */
        private val uploaded: CountDownLatch
    ) : BatchApmUploader {
        /** Names accepted by the uploader. */
        val names = mutableListOf<String>()

        /** Records and accepts a complete batch. */
        override fun uploadBatch(events: List<ApmEvent>): Boolean {
            names += events.map(ApmEvent::name)
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
        /** Minimal retry delay for the test worker. */
        private const val RETRY_DELAY_MS = 10L

        /** Maximum wait for worker completion. */
        private const val TEST_TIMEOUT_SECONDS = 5L
    }
}
