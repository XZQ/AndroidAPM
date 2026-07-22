package com.apm.core

import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.PendingEvent
import com.apm.storage.PendingEventStore
import com.apm.uploader.ApmUploader
import com.apm.uploader.BatchApmUploader
import com.apm.uploader.RetryPolicy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Lower retry wait bound that prevents a hot loop. */
private const val MIN_RETRY_WAIT_MS = 10L

/** Upper retry wait bound that prevents hostile Retry-After hints from sleeping indefinitely. */
private const val MAX_RETRY_WAIT_MS = 60_000L

/** Returns a non-negative, finite retry wait while honoring a larger valid server hint. */
internal fun boundedRetryWaitMs(localDelayMs: Long, retryAfterMs: Long?): Long {
    return maxOf(localDelayMs, retryAfterMs ?: 0L).coerceIn(MIN_RETRY_WAIT_MS, MAX_RETRY_WAIT_MS)
}

/**
 * Replays a persistent outbox and acknowledges rows only after upload success.
 *
 * 重试语义（单一重试权威）：每个处理周期对一批行只做一次上传尝试；
 * 失败时 owner-aware failClaim 并按（该批最大 retry_count + 1）指数退避，
 * 下个周期重新从 outbox 选取。不存在内层重试循环，
 * `maxRetries` 表示首次尝试之后允许的重试次数；每次失败递增 `retry_count`，
 * 达到 `maxRetries + 1` 时立即淘汰，不存在周期数放大。
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
    /** Maximum ownership interval before another worker may reclaim a row. */
    private val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    /** Logger for recoverable worker failures. */
    private val logger: ApmLogger,
    /** Optional SDK health monitor. */
    private val selfMonitor: SdkSelfMonitor?
) {
    /** Failed-attempt count at which a row is exhausted, including the initial attempt. */
    private val retryFailureLimit = retryPolicy.maxRetries.coerceAtLeast(0).let { configuredRetries ->
        if (configuredRetries == Int.MAX_VALUE) Int.MAX_VALUE else configuredRetries + 1
    }

    /** Unique claim owner for this worker instance. */
    private val ownerId = "${ProcessSessionId.get()}-upload-${workerSequence.incrementAndGet()}"

    /** Single worker executor that preserves outbox ordering. */
    private val executor: ExecutorService = ApmExecutors.newSingleThreadExecutor(THREAD_NAME)

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
        // Ask the transport to cancel or stop before making owned rows
        // available to another worker.
        try {
            uploader.shutdown()
        } catch (error: Exception) {
            // A custom transport failure must not skip executor interruption or lease release.
            logger.e("Failed to shutdown persistent upload transport", error)
            Apm.recordInternalError(ERROR_SHUTDOWN_UPLOADER, error)
        }
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            store.releaseClaims(ownerId)
        } catch (error: Exception) {
            logger.e("Failed to release persistent upload claims", error)
            Apm.recordInternalError(ERROR_RELEASE_CLAIMS, error)
        }
    }

    /** Main durable replay loop. */
    private fun processLoop() {
        while (running) {
            val batch = try {
                store.claimPending(
                    ownerId = ownerId,
                    limit = batchSize,
                    nowMs = System.currentTimeMillis(),
                    leaseDurationMs = leaseDurationMs
                )
            } catch (error: Exception) {
                logger.e("Failed to claim persistent upload outbox", error)
                Apm.recordInternalError(ERROR_CLAIM, error)
                emptyList()
            }
            val pendingCount = try {
                store.pendingCount()
            } catch (error: Exception) {
                logger.e("Failed to count persistent upload outbox", error)
                Apm.recordInternalError(ERROR_PENDING_COUNT, error)
                0
            }
            selfMonitor?.updateQueueSize(pendingCount)

            if (batch.isEmpty()) {
                // 空闲周期顺带清理重试耗尽/超龄的行，防止 outbox 永久膨胀
                pruneExpiredRows()
                awaitWake(IDLE_POLL_MS)
                continue
            }

            val startedAt = System.currentTimeMillis()
            if (uploadOnce(batch)) {
                val ids = batch.map(PendingEvent::id)
                try {
                    val acknowledged = store.acknowledgeClaim(ownerId, ids)
                    // A short lease may expire during a custom transport
                    // call; an ownership mismatch intentionally leaves the
                    // row durable for the new owner.
                    if (acknowledged != ids.size) {
                        logger.w("Acknowledged $acknowledged/${ids.size} uploaded rows because claim ownership changed")
                    }
                } catch (error: Exception) {
                    logger.e("Failed to acknowledge uploaded events", error)
                    Apm.recordInternalError(ERROR_ACKNOWLEDGE, error)
                }
                selfMonitor?.recordUploadLatency(System.currentTimeMillis() - startedAt)
            } else {
                val ids = batch.map(PendingEvent::id)
                try {
                    store.failClaim(ownerId, ids)
                } catch (error: Exception) {
                    logger.e("Failed to update persistent retry counters", error)
                    Apm.recordInternalError(ERROR_FAIL_CLAIM, error)
                }
                // Prune after the failed claim is released. Waiting for an empty outbox would
                // let a permanently failing row be selected forever and never reach cleanup.
                pruneExpiredRows()
                // Rows remain durable; wait before selecting them again.
                // 服务端 Retry-After 建议优先于本地指数退避
                val retryAttempt = (batch.maxOfOrNull(PendingEvent::retryCount)?.plus(1) ?: 1)
                    .coerceAtMost(MAX_BACKOFF_ATTEMPT)
                val retryAfterMs = try {
                    uploader.retryAfterHintMs()
                } catch (error: Exception) {
                    logger.e("Failed to read persistent upload retry hint", error)
                    Apm.recordInternalError(ERROR_RETRY_HINT, error)
                    null
                }
                val backoffMs = boundedRetryWaitMs(retryPolicy.delayForAttempt(retryAttempt), retryAfterMs)
                awaitWake(backoffMs)
            }
        }
    }

    /**
     * Uploads one batch exactly once.
     * processLoop 的重选 + markRetry 是唯一重试层，此处不做内层循环。
     *
     * @param batch durable rows
     * @return true when the complete batch was accepted
     */
    private fun uploadOnce(batch: List<PendingEvent>): Boolean {
        val events = batch.map(PendingEvent::event)
        return try {
            if (uploader is BatchApmUploader) {
                uploader.uploadBatch(events)
            } else {
                events.all(uploader::upload)
            }
        } catch (error: Exception) {
            logger.e("Persistent upload attempt failed", error)
            Apm.recordInternalError(ERROR_UPLOAD, error)
            false
        }
    }

    /**
     * 清理重试次数耗尽或超过保留时长的 outbox 行。
     * 空闲周期负责年龄清理，失败路径负责及时清理刚耗尽的行。
     */
    private fun pruneExpiredRows() {
        val pruned = try {
            store.pruneExpired(retryFailureLimit, OUTBOX_TTL_MS)
        } catch (error: Exception) {
            logger.e("Failed to prune expired outbox rows", error)
            Apm.recordInternalError(ERROR_PRUNE, error)
            0
        }
        // 有清理动作时输出警告，帮助发现持续性上传失败
        if (pruned > 0) {
            logger.w(
                "Pruned $pruned expired outbox rows " +
                    "(retry>=$retryFailureLimit or age>${OUTBOX_TTL_MS}ms)"
            )
        }
    }

    /**
     * Waits for new work or a retry deadline.
     *
     * @param timeoutMs maximum wait duration
     */
    private fun awaitWake(timeoutMs: Long) {
        if (!running) {
            return
        }
        try {
            wakeSignal.poll(timeoutMs.coerceAtLeast(MIN_RETRY_WAIT_MS), TimeUnit.MILLISECONDS)
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

        /** Maximum worker shutdown wait. */
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L

        /** Default lease comfortably exceeds the built-in HTTP timeout. */
        private const val DEFAULT_LEASE_DURATION_MS = 120_000L

        /** Caps exponent work for rows retained over long outages. */
        private const val MAX_BACKOFF_ATTEMPT = 16

        /** outbox 行最长保留时长：7 天。 */
        private const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Process-local sequence that distinguishes worker restarts. */
        private val workerSequence = AtomicLong(0L)

        /** Self-monitor tag for claim failures. */
        private const val ERROR_CLAIM = "persistent_upload_claim"
        /** Self-monitor tag for pending-count failures. */
        private const val ERROR_PENDING_COUNT = "persistent_upload_pending_count"
        /** Self-monitor tag for acknowledgement failures. */
        private const val ERROR_ACKNOWLEDGE = "persistent_upload_acknowledge"
        /** Self-monitor tag for failed-claim persistence failures. */
        private const val ERROR_FAIL_CLAIM = "persistent_upload_fail_claim"
        /** Self-monitor tag for transport exceptions. */
        private const val ERROR_UPLOAD = "persistent_upload_transport"
        /** Self-monitor tag for invalid or failing transport retry hints. */
        private const val ERROR_RETRY_HINT = "persistent_upload_retry_hint"
        /** Self-monitor tag for expiry pruning failures. */
        private const val ERROR_PRUNE = "persistent_upload_prune"
        /** Self-monitor tag for shutdown release failures. */
        private const val ERROR_RELEASE_CLAIMS = "persistent_upload_release"
        /** Self-monitor tag for custom transport shutdown failures. */
        private const val ERROR_SHUTDOWN_UPLOADER = "persistent_upload_shutdown_transport"
    }
}
