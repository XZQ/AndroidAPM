package com.apm.core

import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.PendingEvent
import com.apm.storage.PendingEventStore
import com.apm.uploader.ApmUploader
import com.apm.uploader.BatchApmUploader
import com.apm.uploader.RetryPolicy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Replays a persistent outbox and acknowledges rows only after upload success.
 */
internal class PersistentUploadWorker(
    /** Durable source of pending events. */
    private val store: PendingEventStore,
    /** Network or custom transport. */
    private val uploader: ApmUploader,
    /** Retry policy used for one processing cycle. */
    private val retryPolicy: RetryPolicy,
    /** Maximum events sent in one transport call. */
    private val batchSize: Int,
    /** Logger for recoverable worker failures. */
    private val logger: ApmLogger,
    /** Optional SDK health monitor. */
    private val selfMonitor: SdkSelfMonitor?
) {
    /** Single worker executor that preserves outbox ordering. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME)
    }

    /** Coalescing wake-up signal for newly appended events. */
    private val wakeSignal = ArrayBlockingQueue<Unit>(1)

    /** Worker lifecycle flag. */
    @Volatile
    private var running = true

    init {
        executor.execute(::processLoop)
    }

    /** Signals that new durable work is available. */
    fun signal() {
        wakeSignal.offer(Unit)
    }

    /**
     * Stops network processing while leaving unacknowledged rows durable.
     */
    fun shutdown() {
        running = false
        signal()
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        uploader.shutdown()
    }

    /** Main durable replay loop. */
    private fun processLoop() {
        while (running) {
            val batch = runCatching { store.readPending(batchSize) }
                .onFailure { logger.e("Failed to read persistent upload outbox", it) }
                .getOrDefault(emptyList())
            selfMonitor?.updateQueueSize(runCatching { store.pendingCount() }.getOrDefault(0))

            if (batch.isEmpty()) {
                awaitWake(IDLE_POLL_MS)
                continue
            }

            val startedAt = System.currentTimeMillis()
            if (uploadWithRetry(batch)) {
                val ids = batch.map(PendingEvent::id)
                runCatching { store.deletePending(ids) }
                    .onFailure { logger.e("Failed to acknowledge uploaded events", it) }
                selfMonitor?.recordUploadLatency(System.currentTimeMillis() - startedAt)
            } else {
                val ids = batch.map(PendingEvent::id)
                runCatching { store.markRetry(ids) }
                    .onFailure { logger.e("Failed to update persistent retry counters", it) }
                // Rows remain durable; wait before selecting them again.
                val retryAttempt = (batch.maxOfOrNull(PendingEvent::retryCount)?.plus(1) ?: 1)
                    .coerceAtMost(MAX_BACKOFF_ATTEMPT)
                awaitWake(retryPolicy.delayForAttempt(retryAttempt))
            }
        }
    }

    /**
     * Uploads one batch with bounded immediate retries.
     *
     * @param batch durable rows
     * @return true when the complete batch was accepted
     */
    private fun uploadWithRetry(batch: List<PendingEvent>): Boolean {
        val events = batch.map(PendingEvent::event)
        var attempt = 0
        while (running && attempt <= retryPolicy.maxRetries) {
            val accepted = runCatching {
                if (uploader is BatchApmUploader) {
                    uploader.uploadBatch(events)
                } else {
                    events.all(uploader::upload)
                }
            }.onFailure {
                logger.e("Persistent upload attempt failed", it)
            }.getOrDefault(false)
            if (accepted) return true

            attempt++
            if (attempt <= retryPolicy.maxRetries) {
                awaitWake(retryPolicy.delayForAttempt(attempt))
            }
        }
        return false
    }

    /**
     * Waits for new work or a retry deadline.
     *
     * @param timeoutMs maximum wait duration
     */
    private fun awaitWake(timeoutMs: Long) {
        if (!running) return
        try {
            wakeSignal.poll(timeoutMs.coerceAtLeast(MIN_WAIT_MS), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            if (running) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        /** Worker thread name. */
        private const val THREAD_NAME = "apm-persistent-upload"

        /** Idle database poll interval for restart replay. */
        private const val IDLE_POLL_MS = 30_000L

        /** Lower wait bound that prevents a hot loop. */
        private const val MIN_WAIT_MS = 10L

        /** Maximum worker shutdown wait. */
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L

        /** Caps exponent work for rows retained over long outages. */
        private const val MAX_BACKOFF_ATTEMPT = 16
    }
}
