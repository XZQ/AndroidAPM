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
    private val store: DiagnosticStore
) {

    /** Monotonic record sequence. */
    private val sequence = AtomicLong(0L)
    /** Bounded persistence queue. */
    private val queue = ArrayBlockingQueue<DiagnosticEntry>(config.writerQueueCapacity)
    /** In-memory newest-record ring. */
    private val memory = ArrayDeque<DiagnosticEntry>(config.memoryRecordLimit)
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
    /** Current corrupt persisted-line count observed during a read. */
    private val corruptRecords = AtomicLong(0L)
    /** Current file-sink health. */
    private val fileSinkHealthy = AtomicBoolean(true)
    /** Last sanitized file-sink failure. */
    private val lastFailure = AtomicReference<String?>(null)
    /** Earliest wall-clock time at which file writes may be retried. */
    private val nextFileRetryAtMs = AtomicLong(0L)
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
        addToMemory(entry)
        if (!accepting.get()) {
            // A stopped runtime preserves memory evidence but cannot promise persistence.
            droppedRecords.incrementAndGet()
            return
        }
        enqueue(entry)
    }

    /** Returns a newest-first snapshot merged from persisted and in-memory evidence. */
    fun snapshot(limit: Int): List<DiagnosticEntry> {
        val boundedLimit = limit.coerceIn(1, config.memoryRecordLimit)
        val memorySnapshot = synchronized(memoryLock) { memory.toList() }
        val persisted = try {
            store.readAll().also { read -> corruptRecords.set(read.corruptRecords) }.entries
        } catch (error: Exception) {
            // Snapshot remains useful from memory when persisted reads are unavailable.
            handleFileFailure(error)
            emptyList()
        }
        return (persisted + memorySnapshot)
            .distinctBy { entry -> entry.sessionId to entry.sequence }
            .sortedWith(compareBy<DiagnosticEntry> { entry -> entry.timestampMs }.thenBy { entry -> entry.sequence })
            .takeLast(boundedLimit)
            .asReversed()
    }

    /** Returns current diagnostics health without throwing into the host application. */
    fun status(): DiagnosticStatus {
        val retainedBytes = try {
            store.retainedBytes()
        } catch (error: Exception) {
            // Status remains available with a zero byte snapshot after a read-side file failure.
            handleFileFailure(error)
            0L
        }
        return DiagnosticStatus(
            enabled = true,
            fileSinkHealthy = fileSinkHealthy.get(),
            queueDepth = queue.size,
            retainedBytes = retainedBytes,
            droppedRecords = droppedRecords.get(),
            writeFailures = writeFailures.get(),
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
            handleFileFailure(error)
            false
        }
        if (cleared) {
            synchronized(memoryLock) {
                memory.clear()
            }
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
    private fun addToMemory(entry: DiagnosticEntry) {
        synchronized(memoryLock) {
            if (memory.size == config.memoryRecordLimit) {
                memory.removeFirst()
            }
            memory.addLast(entry)
        }
    }

    /** Offers one entry to the bounded writer queue with ERROR preference. */
    private fun enqueue(entry: DiagnosticEntry) {
        pendingWrites.incrementAndGet()
        if (queue.offer(entry)) {
            return
        }
        settlePendingWrite()
        if (entry.level == DiagnosticLevel.ERROR) {
            val evicted = queue.poll()
            if (evicted != null) {
                // The evicted queued record had already contributed to pendingWrites.
                droppedRecords.incrementAndGet()
                settlePendingWrite()
            }
            pendingWrites.incrementAndGet()
            if (queue.offer(entry)) {
                return
            }
            settlePendingWrite()
        }
        droppedRecords.incrementAndGet()
    }

    /** Runs file persistence until shutdown and queue drain complete. */
    private fun writerLoop() {
        while (running.get() || queue.isNotEmpty()) {
            val entry = try {
                queue.poll(WRITER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Shutdown uses interruption only to wake the timed poll.
                null
            }
            if (entry == null) {
                continue
            }
            persistIfAvailable(entry)
            settlePendingWrite()
        }
    }

    /** Persists one entry unless the sink is inside its bounded failure cooldown. */
    private fun persistIfAvailable(entry: DiagnosticEntry) {
        val now = System.currentTimeMillis()
        if (now < nextFileRetryAtMs.get()) {
            return
        }
        try {
            store.append(entry)
            fileSinkHealthy.set(true)
            nextFileRetryAtMs.set(0L)
        } catch (error: Exception) {
            // File failures are isolated here and never re-enter ApmLogger or record().
            handleFileFailure(error)
            nextFileRetryAtMs.set(now + FILE_RETRY_COOLDOWN_MS)
        }
    }

    /** Updates local sink health and reports through raw Logcat without recursion. */
    private fun handleFileFailure(error: Exception) {
        writeFailures.incrementAndGet()
        fileSinkHealthy.set(false)
        val summary = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name)
        lastFailure.set(summary)
        try {
            Log.e(RAW_LOG_TAG, "Diagnostic file sink failed: $summary")
        } catch (_: RuntimeException) {
            // Plain JVM environments may not provide an Android Log implementation.
        }
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
    }
}
