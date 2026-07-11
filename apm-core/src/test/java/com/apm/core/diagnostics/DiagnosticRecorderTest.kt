package com.apm.core.diagnostics

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Concurrency, resource-bound, and failure-isolation tests for the diagnostic recorder.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticRecorderTest {

    /** Recorders created by a test and shut down afterward. */
    private val recorders = mutableListOf<DiagnosticRecorder>()

    /** Stops every background writer created by the test. */
    @After
    fun tearDown() {
        recorders.forEach(DiagnosticRecorder::shutdown)
    }

    /** The memory snapshot must keep only the newest configured entries. */
    @Test
    fun `memory snapshot is newest first and bounded`() {
        val recorder = recorder(memoryLimit = 2)
        recorder.record(DiagnosticLevel.INFO, "core", null, "one", null)
        recorder.record(DiagnosticLevel.INFO, "core", null, "two", null)
        recorder.record(DiagnosticLevel.ERROR, "core", "three", "three", null)

        assertEquals(listOf("three", "two"), recorder.snapshot(10).map(DiagnosticEntry::message))
    }

    /** A full queue must drop without waiting for a blocked file sink. */
    @Test
    fun `queue overflow never blocks and counts drops`() {
        val store = BlockingStore()
        val recorder = recorder(queueCapacity = 1, store = store)
        recorder.record(DiagnosticLevel.INFO, "core", null, "first", null)
        assertTrue(store.appendStarted.await(1L, TimeUnit.SECONDS))
        recorder.record(DiagnosticLevel.INFO, "core", null, "second", null)

        val startedAt = System.nanoTime()
        recorder.record(DiagnosticLevel.INFO, "core", null, "third", null)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(elapsedMs < 100L)
        assertEquals(1L, recorder.status().droppedRecords)
        store.releaseAppend.countDown()
        assertTrue(recorder.flush(1_000L))
    }

    /** ERROR records may evict one older queued record but must account for the loss. */
    @Test
    fun `error record evicts older queued record`() {
        val store = BlockingStore()
        val recorder = recorder(queueCapacity = 1, store = store)
        recorder.record(DiagnosticLevel.INFO, "core", null, "first", null)
        assertTrue(store.appendStarted.await(1L, TimeUnit.SECONDS))
        recorder.record(DiagnosticLevel.INFO, "core", null, "second", null)
        recorder.record(DiagnosticLevel.ERROR, "core", "failure", "third", null)
        store.releaseAppend.countDown()

        assertTrue(recorder.flush(1_000L))
        assertEquals(listOf("first", "third"), store.entries.map(DiagnosticEntry::message))
        assertEquals(1L, recorder.status().droppedRecords)
    }

    /** A file exception must preserve memory evidence and disable only the file sink. */
    @Test
    fun `file failure keeps memory snapshot and avoids recursion`() {
        val recorder = recorder(store = ThrowingStore())
        recorder.record(
            DiagnosticLevel.ERROR,
            component = "storage",
            code = "write",
            message = "failed",
            error = IOException("disk full")
        )

        assertTrue(recorder.flush(1_000L))
        assertEquals("failed", recorder.snapshot(1).single().message)
        assertEquals(1L, recorder.status().writeFailures)
        assertFalse(recorder.status().fileSinkHealthy)
    }

    /** Read-side failures must not inflate write-failure totals. */
    @Test
    fun `read failure has independent metric`() {
        val recorder = recorder(store = ReadThrowingStore())

        assertTrue(recorder.snapshot(1).isEmpty())
        assertEquals(1L, recorder.status().readFailures)
        assertEquals(0L, recorder.status().writeFailures)
    }

    /** Status must read cached disk usage rather than traversing the store on the caller thread. */
    @Test
    fun `status uses cached retained bytes`() {
        val store = CountingStore()
        val recorder = recorder(store = store)
        recorder.record(DiagnosticLevel.INFO, "core", null, "cached", null)
        assertTrue(recorder.flush(1_000L))
        val callsAfterWriter = store.retainedCalls.get()

        recorder.status()
        recorder.status()

        assertEquals(callsAfterWriter, store.retainedCalls.get())
    }

    /** Memory and queue buffers must obey byte budgets as well as record counts. */
    @Test
    fun `variable sized buffers remain byte bounded`() {
        val store = BlockingStore()
        val recorder = recorder(
            memoryLimit = 100,
            memoryByteLimit = MIN_TEST_BUFFER_BYTES,
            queueCapacity = 100,
            queueByteLimit = MIN_TEST_BUFFER_BYTES,
            store = store
        )
        recorder.record(DiagnosticLevel.INFO, "core", null, "first", null)
        assertTrue(store.appendStarted.await(1L, TimeUnit.SECONDS))

        repeat(30) { index ->
            recorder.record(DiagnosticLevel.INFO, "core", null, "$index-${"x".repeat(4_096)}", null)
        }

        val status = recorder.status()
        assertTrue(status.memoryBytes <= MIN_TEST_BUFFER_BYTES)
        assertTrue(status.queueBytes <= MIN_TEST_BUFFER_BYTES)
        assertTrue(status.droppedRecords > 0L)
        store.releaseAppend.countDown()
    }

    /** Cooldown must preserve queued evidence instead of silently settling it. */
    @Test
    fun `cooldown waits before dequeue`() {
        val clock = AtomicLong(0L)
        val cooldownStarted = CountDownLatch(1)
        val releaseCooldown = CountDownLatch(1)
        val store = FailOnceStore()
        val recorder = recorder(
            store = store,
            clockMs = clock::get,
            retryWait = {
                cooldownStarted.countDown()
                releaseCooldown.await(2L, TimeUnit.SECONDS)
                clock.set(FILE_RETRY_COOLDOWN_MS)
            }
        )
        recorder.record(DiagnosticLevel.INFO, "core", null, "fails", null)
        assertTrue(recorder.flush(1_000L))
        assertTrue(cooldownStarted.await(1L, TimeUnit.SECONDS))

        recorder.record(DiagnosticLevel.INFO, "core", null, "retained", null)

        assertEquals(1, recorder.status().queueDepth)
        assertEquals(0L, recorder.status().droppedRecords)
        releaseCooldown.countDown()
        assertTrue(recorder.flush(1_000L))
        assertEquals(listOf("retained"), store.entries.map(DiagnosticEntry::message))
    }

    /** Builds and tracks one recorder with deterministic test identity. */
    private fun recorder(
        memoryLimit: Int = 20,
        memoryByteLimit: Long = 4L * 1024L * 1024L,
        queueCapacity: Int = 20,
        queueByteLimit: Long = 4L * 1024L * 1024L,
        store: DiagnosticStore = RecordingStore(),
        clockMs: () -> Long = System::currentTimeMillis,
        retryWait: (Long) -> Unit = Thread::sleep
    ): DiagnosticRecorder {
        return DiagnosticRecorder(
            config = DiagnosticsConfig(
                memoryRecordLimit = memoryLimit,
                memoryByteLimit = memoryByteLimit,
                writerQueueCapacity = queueCapacity,
                writerQueueByteLimit = queueByteLimit
            ),
            processName = "com.example",
            sessionId = "session",
            store = store,
            clockMs = clockMs,
            retryWait = retryWait
        ).also(recorders::add)
    }

    /** In-memory store that preserves real recorder output for assertions. */
    private open class RecordingStore : DiagnosticStore {
        /** Entries accepted by the store. */
        val entries = CopyOnWriteArrayList<DiagnosticEntry>()

        /** Records one real diagnostic entry. */
        override fun append(entry: DiagnosticEntry) {
            entries += entry
        }

        /** Returns all entries in accepted order. */
        override fun readAll(): DiagnosticReadResult = DiagnosticReadResult(entries.toList(), 0L)

        /** In-memory test store consumes no disk. */
        override fun retainedBytes(): Long = 0L

        /** Export is not exercised by recorder tests. */
        override fun exportTo(target: File, status: DiagnosticStatus): DiagnosticExportResult {
            return DiagnosticExportResult(false, null, 0, "unsupported")
        }

        /** Clears accepted entries. */
        override fun clear(): Boolean {
            entries.clear()
            return true
        }
    }

    /** Store whose first append blocks so bounded-queue behavior is deterministic. */
    private class BlockingStore : RecordingStore() {
        /** Signals that the writer entered append. */
        val appendStarted = CountDownLatch(1)
        /** Releases the blocked writer. */
        val releaseAppend = CountDownLatch(1)

        /** Blocks before preserving the entry. */
        override fun append(entry: DiagnosticEntry) {
            appendStarted.countDown()
            releaseAppend.await(2L, TimeUnit.SECONDS)
            super.append(entry)
        }
    }

    /** Store that simulates an unavailable app-private filesystem. */
    private class ThrowingStore : RecordingStore() {
        /** Always fails the persistence boundary. */
        override fun append(entry: DiagnosticEntry) {
            throw IOException("disk full")
        }
    }

    /** Store whose persisted reads fail while writes remain available. */
    private class ReadThrowingStore : RecordingStore() {
        /** Always fails the read boundary. */
        override fun readAll(): DiagnosticReadResult {
            throw IOException("read unavailable")
        }
    }

    /** Store that fails the first append and accepts later retry evidence. */
    private class FailOnceStore : RecordingStore() {
        /** Number of append attempts. */
        private val attempts = AtomicLong(0L)

        /** Fails exactly the first append. */
        override fun append(entry: DiagnosticEntry) {
            if (attempts.incrementAndGet() == 1L) {
                throw IOException("temporary failure")
            }
            super.append(entry)
        }
    }

    /** Store that counts retained-byte queries across construction, writer, and status paths. */
    private class CountingStore : RecordingStore() {
        /** Number of retained-byte queries. */
        val retainedCalls = AtomicLong(0L)

        /** Counts and returns the in-memory store's zero disk usage. */
        override fun retainedBytes(): Long {
            retainedCalls.incrementAndGet()
            return 0L
        }
    }

    private companion object {
        /** Minimum buffer byte budget accepted by production validation. */
        private const val MIN_TEST_BUFFER_BYTES = 64L * 1024L
        /** Production retry cooldown used by the deterministic fake clock. */
        private const val FILE_RETRY_COOLDOWN_MS = 60_000L
    }
}
