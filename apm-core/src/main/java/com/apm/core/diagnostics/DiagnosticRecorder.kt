package com.apm.core.diagnostics

import android.util.Log
import com.apm.core.ApmExecutors
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-blocking diagnostic recorder backed by a memory ring and an independent file writer.
 */
internal class DiagnosticRecorder(
    /** Validated resource and privacy configuration. */
    private val config: DiagnosticsConfig,
    /** Android process identity included in every record. */
    private val processName: String,
    /** SDK session identity included in every record. */
    private val sessionId: String,
    /** Independent persistent store. */
    private val store: DiagnosticStore,
    /** Wall-clock source used by retry scheduling. */
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** Interruptible wait used during file-sink cooldown. */
    private val retryWait: (Long) -> Unit = Thread::sleep
) {

    /** Monotonic record sequence. */
    private val sequence = AtomicLong(0L)
    /** Bounded persistence queue. */
    private val queue = ArrayBlockingQueue<SizedEntry>(config.writerQueueCapacity)
    /** Short lock protecting queue admission and byte accounting. */
    private val queueLock = Any()
    /** Encoded bytes currently waiting in [queue]. */
    private val queueBytes = AtomicLong(0L)
    /** In-memory newest-record ring. */
    private val memory = ArrayDeque<SizedEntry>(config.memoryRecordLimit)
    /** Encoded bytes currently retained in [memory]. */
    private var memoryBytes = 0L
    /** Short memory-ring lock. */
    private val memoryLock = Any()
    /** Monitor used by bounded flush calls. */
    private val pendingLock = Object()
    /** Records accepted into the queue but not settled by the writer. */
    private val pendingWrites = AtomicLong(0L)
    /** Whether new records may enter the file queue. */
    private val accepting = AtomicBoolean(true)
    /** Whether the writer loop should continue. */
    private val running = AtomicBoolean(true)
    /** Queue-pressure or closed-writer record losses. */
    private val droppedRecords = AtomicLong(0L)
    /** File-sink failures. */
    private val writeFailures = AtomicLong(0L)
    /** Persisted-journal read failures. */
    private val readFailures = AtomicLong(0L)
    /** Current corrupt persisted-line count observed during a read. */
    private val corruptRecords = AtomicLong(0L)
    /** Current file-sink health. */
    private val fileSinkHealthy = AtomicBoolean(true)
    /** Last sanitized file-sink failure. */
    private val lastFailure = AtomicReference<String?>(null)
    /** Earliest wall-clock time at which file writes may be retried. */
    private val nextFileRetryAtMs = AtomicLong(0L)
    /** Cached retained disk bytes; status reads never traverse the filesystem. */
    private val retainedBytes = AtomicLong(0L)

    init {
        try {
            retainedBytes.set(store.retainedBytes())
        } catch (error: Exception) {
            // Construction remains available in memory when existing journals cannot be inspected.
            noteReadFailure(error)
        }
    }

    /** Dedicated low-priority diagnostics writer. */
    private val writerThread = ApmExecutors.startThread(
        name = WRITER_THREAD_NAME,
        priority = ApmExecutors.PRIORITY_BACKGROUND,
        block = Runnable(::writerLoop)
    )

    /**
     * Records one diagnostic without waiting for file IO or queue capacity.
     *
     * @param level diagnostic severity
     * @param component SDK component
     * @param code stable error code when available
     * @param message controlled SDK message
     * @param error optional throwable
     */
    fun record(
        level: DiagnosticLevel,
        component: String,
        code: String?,
        message: String,
        error: Throwable?
    ) {
        val throwable = DiagnosticSanitizer.sanitizeThrowable(error, config.includeStackTraces)
        val entry = DiagnosticEntry(
            sequence = sequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            sessionId = sessionId,
            level = level,
            component = DiagnosticSanitizer.sanitizeMessage(component),
            code = code?.let(DiagnosticSanitizer::sanitizeMessage),
            message = DiagnosticSanitizer.sanitizeMessage(message),
            processName = processName,
            threadName = Thread.currentThread().name,
            exceptionClass = throwable.className,
            exceptionMessage = throwable.message,
            stackTrace = throwable.stackTrace,
            stackHash = throwable.stackHash
        )
        val sizedEntry = SizedEntry(entry, encodedSizeBytes(entry))
        addToMemory(sizedEntry)
        if (!accepting.get()) {
            // A stopped runtime preserves memory evidence but cannot promise persistence.
            droppedRecords.incrementAndGet()
            return
        }
        enqueue(sizedEntry)
    }

    /** Returns a newest-first snapshot merged from persisted and in-memory evidence. */
    fun snapshot(limit: Int): List<DiagnosticEntry> {
        val boundedLimit = limit.coerceIn(1, config.memoryRecordLimit)
        val memorySnapshot = memorySnapshot()
        val persisted = try {
            store.readAll().also { read -> corruptRecords.set(read.corruptRecords) }.entries
        } catch (error: Exception) {
            // Snapshot remains useful from memory when persisted reads are unavailable.
            noteReadFailure(error)
            emptyList()
        }
        return (persisted + memorySnapshot)
            .distinctBy { entry -> Triple(entry.processName, entry.sessionId, entry.sequence) }
            .sortedWith(compareBy<DiagnosticEntry> { entry -> entry.timestampMs }.thenBy { entry -> entry.sequence })
            .takeLast(boundedLimit)
            .asReversed()
    }

    /** Returns a stable oldest-first copy of current memory evidence. */
    internal fun memorySnapshot(): List<DiagnosticEntry> {
        return synchronized(memoryLock) { memory.map(SizedEntry::entry) }
    }

    /** Returns current diagnostics health without throwing into the host application. */
    fun status(): DiagnosticStatus {
        val currentMemoryBytes = synchronized(memoryLock) { memoryBytes }
        return DiagnosticStatus(
            enabled = true,
            fileSinkHealthy = fileSinkHealthy.get(),
            queueDepth = queue.size,
            queueBytes = queueBytes.get(),
            memoryBytes = currentMemoryBytes,
            retainedBytes = retainedBytes.get(),
            droppedRecords = droppedRecords.get(),
            writeFailures = writeFailures.get(),
            readFailures = readFailures.get(),
            corruptRecords = corruptRecords.get(),
            lastFailure = lastFailure.get()
        )
    }

    /** Waits for accepted records to settle, bounded by [timeoutMs]. */
    fun flush(timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val deadline = System.nanoTime() + timeoutNanos
        synchronized(pendingLock) {
            while (pendingWrites.get() > 0L) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    return false
                }
                val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
                pendingLock.wait(waitMs)
            }
        }
        return true
    }

    /** Flushes accepted records and exports a controlled ZIP package. */
    fun exportTo(target: File): DiagnosticExportResult {
        flush(EXPLICIT_OPERATION_FLUSH_TIMEOUT_MS)
        return store.exportTo(target, status())
    }

    /** Clears persisted and in-memory records after a bounded writer flush. */
    fun clear(): Boolean {
        flush(EXPLICIT_OPERATION_FLUSH_TIMEOUT_MS)
        val cleared = try {
            store.clear()
        } catch (error: Exception) {
            handleWriteFailure(error)
            false
        }
        if (cleared) {
            synchronized(memoryLock) {
                memory.clear()
                memoryBytes = 0L
            }
            retainedBytes.set(0L)
            corruptRecords.set(0L)
        }
        return cleared
    }

    /** Stops file acceptance and drains the writer within a fixed host-safe bound. */
    fun shutdown() {
        if (!accepting.getAndSet(false)) {
            return
        }
        flush(SHUTDOWN_TIMEOUT_MS)
        running.set(false)
        writerThread.interrupt()
        try {
            writerThread.join(SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            // Restore interruption for callers while keeping shutdown non-throwing.
            Thread.currentThread().interrupt()
        }
    }

    /** Adds an entry to the bounded memory ring. */
    private fun addToMemory(entry: SizedEntry) {
        synchronized(memoryLock) {
            if (entry.encodedBytes > config.memoryByteLimit) {
                return
            }
            while (memory.isNotEmpty() &&
                (memory.size >= config.memoryRecordLimit || memoryBytes + entry.encodedBytes > config.memoryByteLimit)
            ) {
                // Evict oldest evidence until both independent bounds admit the new record.
                memoryBytes -= memory.removeFirst().encodedBytes
            }
            memory.addLast(entry)
            memoryBytes += entry.encodedBytes
        }
    }

    /** Offers one entry to the bounded writer queue with ERROR preference. */
    private fun enqueue(entry: SizedEntry) {
        if (offerQueued(entry)) {
            return
        }
        if (entry.entry.level == DiagnosticLevel.ERROR) {
            val evicted = synchronized(queueLock) {
                val candidate = queue.firstOrNull { queued -> queued.entry.level != DiagnosticLevel.ERROR }
                if (candidate != null && queue.remove(candidate)) {
                    queueBytes.addAndGet(-candidate.encodedBytes)
                    candidate
                } else {
                    null
                }
            }
            if (evicted != null) {
                // The evicted queued record had already contributed to pendingWrites.
                droppedRecords.incrementAndGet()
                settlePendingWrite()
            }
            if (offerQueued(entry)) {
                return
            }
        }
        droppedRecords.incrementAndGet()
    }

    /** Offers a sized record only when both count and byte bounds admit it. */
    private fun offerQueued(entry: SizedEntry): Boolean {
        synchronized(queueLock) {
            if (entry.encodedBytes > config.writerQueueByteLimit ||
                queueBytes.get() + entry.encodedBytes > config.writerQueueByteLimit
            ) {
                return false
            }
            if (!queue.offer(entry)) {
                return false
            }
            queueBytes.addAndGet(entry.encodedBytes)
            pendingWrites.incrementAndGet()
            return true
        }
    }

    /** Runs file persistence until shutdown and queue drain complete. */
    private fun writerLoop() {
        while (running.get() || queue.isNotEmpty()) {
            waitForFileRetry()
            val sizedEntry = pollQueued()
            if (sizedEntry == null) {
                continue
            }
            persist(sizedEntry.entry)
            settlePendingWrite()
        }
    }

    /** Polls queue state and byte accounting atomically, then waits briefly when idle. */
    private fun pollQueued(): SizedEntry? {
        synchronized(queueLock) {
            val entry = queue.poll()
            if (entry != null) {
                queueBytes.addAndGet(-entry.encodedBytes)
                return entry
            }
        }
        try {
            Thread.sleep(WRITER_POLL_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            // Shutdown interruption wakes the idle writer.
        }
        return null
    }

    /** Waits before dequeueing so cooldown never silently consumes accepted records. */
    private fun waitForFileRetry() {
        while (running.get()) {
            val remainingMs = nextFileRetryAtMs.get() - clockMs()
            if (remainingMs <= 0L) {
                return
            }
            try {
                retryWait(remainingMs.coerceAtMost(WRITER_POLL_TIMEOUT_MS))
            } catch (_: InterruptedException) {
                // Shutdown interruption wakes the cooldown wait without consuming queued evidence.
                return
            }
        }
    }

    /** Persists one dequeued entry and updates the disk-byte cache. */
    private fun persist(entry: DiagnosticEntry) {
        val now = clockMs()
        try {
            store.append(entry)
            fileSinkHealthy.set(true)
            nextFileRetryAtMs.set(0L)
            try {
                retainedBytes.set(store.retainedBytes())
            } catch (error: Exception) {
                // A successful append remains healthy even if the follow-up byte snapshot is unavailable.
                noteReadFailure(error)
            }
        } catch (error: Exception) {
            // File failures are isolated here and never re-enter ApmLogger or record().
            handleWriteFailure(error)
            nextFileRetryAtMs.set(now + FILE_RETRY_COOLDOWN_MS)
        }
    }

    /** Updates local sink health and reports through raw Logcat without recursion. */
    private fun handleWriteFailure(error: Exception) {
        writeFailures.incrementAndGet()
        fileSinkHealthy.set(false)
        reportRawFailure(error, "write")
    }

    /** Records a mutation failure initiated by a process-aware facade operation. */
    internal fun noteWriteFailure(error: Exception) {
        handleWriteFailure(error)
    }

    /** Counts one persisted-journal read failure without mislabeling it as a write failure. */
    internal fun noteReadFailure(error: Exception) {
        readFailures.incrementAndGet()
        reportRawFailure(error, "read")
    }

    /** Stores and emits one sanitized non-recursive raw failure summary. */
    private fun reportRawFailure(error: Exception, operation: String) {
        val summary = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name)
        lastFailure.set(summary)
        try {
            Log.e(RAW_LOG_TAG, "Diagnostic file $operation failed: $summary")
        } catch (_: RuntimeException) {
            // Plain JVM environments may not provide an Android Log implementation.
        }
    }

    /** Returns the UTF-8 JSONL footprint used for variable-sized buffer accounting. */
    private fun encodedSizeBytes(entry: DiagnosticEntry): Long {
        return (DiagnosticJsonCodec.encode(entry) + NEWLINE).toByteArray(Charsets.UTF_8).size.toLong()
    }

    /** Marks one accepted queue record as written, failed, skipped, or evicted. */
    private fun settlePendingWrite() {
        pendingWrites.decrementAndGet()
        synchronized(pendingLock) {
            pendingLock.notifyAll()
        }
    }

    private companion object {
        /** Diagnostics writer thread name without the shared prefix. */
        private const val WRITER_THREAD_NAME = "diagnostics-writer"
        /** Writer queue poll timeout. */
        private const val WRITER_POLL_TIMEOUT_MS = 100L
        /** File-sink retry cooldown after an exception. */
        private const val FILE_RETRY_COOLDOWN_MS = 60_000L
        /** Flush bound for explicit support operations. */
        private const val EXPLICIT_OPERATION_FLUSH_TIMEOUT_MS = 1_000L
        /** Shutdown flush and join bound. */
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
        /** Raw non-recursive Android Log tag. */
        private const val RAW_LOG_TAG = "AndroidAPM"
        /** JSONL delimiter included in buffer byte accounting. */
        private const val NEWLINE = "\n"
    }

    /** Entry paired with its immutable encoded JSONL footprint. */
    private data class SizedEntry(
        /** Structured diagnostic evidence. */
        val entry: DiagnosticEntry,
        /** UTF-8 JSONL bytes consumed by the entry. */
        val encodedBytes: Long
    )
}
