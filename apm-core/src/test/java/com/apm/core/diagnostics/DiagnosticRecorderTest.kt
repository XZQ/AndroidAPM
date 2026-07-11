package com.apm.core.diagnostics

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /** Builds and tracks one recorder with deterministic test identity. */
    private fun recorder(
        memoryLimit: Int = 20,
        queueCapacity: Int = 20,
        store: DiagnosticStore = RecordingStore()
    ): DiagnosticRecorder {
        return DiagnosticRecorder(
            config = DiagnosticsConfig(
                memoryRecordLimit = memoryLimit,
                writerQueueCapacity = queueCapacity
            ),
            processName = "com.example",
            sessionId = "session",
            store = store
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
}
