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

/**
 * Replays a persistent outbox and acknowledges rows only after upload success.
 *
 * 重试语义（单一重试权威）：每个处理周期对一批行只做一次上传尝试；
 * 失败时 owner-aware failClaim 并按（该批最大 retry_count + 1）指数退避，
 * 下个周期重新从 outbox 选取。不存在内层重试循环，
 * 因此实际重试次数 = 行的 retry_count 上限（由 pruneExpired 控制），
 * 而不是 maxRetries × 周期数的放大值。
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
        uploader.shutdown()
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { store.releaseClaims(ownerId) }
            .onFailure {
                logger.e("Failed to release persistent upload claims", it)
                Apm.recordInternalError(ERROR_RELEASE_CLAIMS, it)
            }
    }

    /** Main durable replay loop. */
    private fun processLoop() {
        while (running) {
            val batch = runCatching {
                store.claimPending(
                    ownerId = ownerId,
                    limit = batchSize,
                    nowMs = System.currentTimeMillis(),
                    leaseDurationMs = leaseDurationMs
                )
            }.onFailure {
                logger.e("Failed to claim persistent upload outbox", it)
                Apm.recordInternalError(ERROR_CLAIM, it)
            }
                .getOrDefault(emptyList())
            selfMonitor?.updateQueueSize(runCatching { store.pendingCount() }.getOrDefault(0))

            if (batch.isEmpty()) {
                // 空闲周期顺带清理重试耗尽/超龄的行，防止 outbox 永久膨胀
                pruneExpiredRows()
                awaitWake(IDLE_POLL_MS)
                continue
            }

            val startedAt = System.currentTimeMillis()
            if (uploadOnce(batch)) {
                val ids = batch.map(PendingEvent::id)
                runCatching { store.acknowledgeClaim(ownerId, ids) }
                    .onSuccess { acknowledged ->
                        // A short lease may expire during a custom transport
                        // call; an ownership mismatch intentionally leaves the
                        // row durable for the new owner.
                        if (acknowledged != ids.size) {
                            logger.w("Acknowledged $acknowledged/${ids.size} uploaded rows because claim ownership changed")
                        }
                    }
                    .onFailure {
                        logger.e("Failed to acknowledge uploaded events", it)
                        Apm.recordInternalError(ERROR_ACKNOWLEDGE, it)
                    }
                selfMonitor?.recordUploadLatency(System.currentTimeMillis() - startedAt)
            } else {
                val ids = batch.map(PendingEvent::id)
                runCatching { store.failClaim(ownerId, ids) }
                    .onFailure {
                        logger.e("Failed to update persistent retry counters", it)
                        Apm.recordInternalError(ERROR_FAIL_CLAIM, it)
                    }
                // Rows remain durable; wait before selecting them again.
                // 服务端 Retry-After 建议优先于本地指数退避
                val retryAttempt = (batch.maxOfOrNull(PendingEvent::retryCount)?.plus(1) ?: 1)
                    .coerceAtMost(MAX_BACKOFF_ATTEMPT)
                val backoffMs = maxOf(
                    retryPolicy.delayForAttempt(retryAttempt),
                    uploader.retryAfterHintMs() ?: 0L
                )
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
        return runCatching {
            if (uploader is BatchApmUploader) {
                uploader.uploadBatch(events)
            } else {
                events.all(uploader::upload)
            }
        }.onFailure {
            logger.e("Persistent upload attempt failed", it)
            Apm.recordInternalError(ERROR_UPLOAD, it)
        }.getOrDefault(false)
    }

    /**
     * 清理重试次数耗尽或超过保留时长的 outbox 行。
     * 仅在空闲周期调用，避免与正常上传竞争数据库。
     */
    private fun pruneExpiredRows() {
        val pruned = runCatching {
            store.pruneExpired(MAX_OUTBOX_RETRIES, OUTBOX_TTL_MS)
        }.onFailure {
            logger.e("Failed to prune expired outbox rows", it)
            Apm.recordInternalError(ERROR_PRUNE, it)
        }.getOrDefault(0)
        // 有清理动作时输出警告，帮助发现持续性上传失败
        if (pruned > 0) {
            logger.w("Pruned $pruned expired outbox rows (retry>=$MAX_OUTBOX_RETRIES or age>${OUTBOX_TTL_MS}ms)")
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

        /** Default lease comfortably exceeds the built-in HTTP timeout. */
        private const val DEFAULT_LEASE_DURATION_MS = 120_000L

        /** Caps exponent work for rows retained over long outages. */
        private const val MAX_BACKOFF_ATTEMPT = 16

        /** 重试次数达到该值的行在空闲周期被清除。 */
        private const val MAX_OUTBOX_RETRIES = 10

        /** outbox 行最长保留时长：7 天。 */
        private const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Process-local sequence that distinguishes worker restarts. */
        private val workerSequence = AtomicLong(0L)

        /** Self-monitor tag for claim failures. */
        private const val ERROR_CLAIM = "persistent_upload_claim"
        /** Self-monitor tag for acknowledgement failures. */
        private const val ERROR_ACKNOWLEDGE = "persistent_upload_acknowledge"
        /** Self-monitor tag for failed-claim persistence failures. */
        private const val ERROR_FAIL_CLAIM = "persistent_upload_fail_claim"
        /** Self-monitor tag for transport exceptions. */
        private const val ERROR_UPLOAD = "persistent_upload_transport"
        /** Self-monitor tag for expiry pruning failures. */
        private const val ERROR_PRUNE = "persistent_upload_prune"
        /** Self-monitor tag for shutdown release failures. */
        private const val ERROR_RELEASE_CLAIMS = "persistent_upload_release"
    }
}
