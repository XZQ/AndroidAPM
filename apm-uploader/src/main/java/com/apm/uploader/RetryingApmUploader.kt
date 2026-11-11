package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 重试策略：指数退避参数。
 */
data class RetryPolicy(
    /** 最大重试次数。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 基础延迟（毫秒）。 */
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    /** 最大延迟上限（毫秒），防止指数增长过大。 */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    /** 退避倍数。delay = baseDelay * (multiplier ^ (attempt - 1))。 */
    val backoffMultiplier: Float = DEFAULT_BACKOFF_MULTIPLIER
) {
    init {
        // 退化参数快速失败：NaN 倍数会使 delayForAttempt 返回 0，负延迟使重试热循环，
        // 二者都会把失败批次变成无间隔的紧密重试风暴。
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(baseDelayMs >= 0L) { "baseDelayMs must be non-negative" }
        require(maxDelayMs > 0L) { "maxDelayMs must be positive" }
        require(backoffMultiplier.isFinite() && backoffMultiplier > 0f) {
            "backoffMultiplier must be finite and positive"
        }
    }

    /**
     * 计算第 N 次重试的等待时间。
     * @param attempt 重试序号（从 1 开始）
     * @return 延迟毫秒数，不超过 [maxDelayMs]
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) {
            return 0L
        }
        var delay = baseDelayMs.toFloat()
        repeat(attempt - 1) {
            delay = (delay * backoffMultiplier).coerceAtMost(maxDelayMs.toFloat())
        }
        return delay.toLong().coerceAtMost(maxDelayMs)
    }

    companion object {
        /** Default retry count after the initial attempt. */
        private const val DEFAULT_MAX_RETRIES = 3

        /** Default delay before the first retry. */
        private const val DEFAULT_BASE_DELAY_MS = 1000L

        /** Maximum retry delay. */
        private const val DEFAULT_MAX_DELAY_MS = 30_000L

        /** Default exponential backoff multiplier. */
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0f
    }
}

/**
 * 带重试和批量的上传器。
 * 包装底层上传器，提供：优先级队列缓冲、批量发送、指数退避重试。
 *
 * 线程安全：所有首次和重试调用均使用同一 worker；延期 task 仅重新排队。
 * 队列按 priority.value DESC 排序（CRITICAL 优先出队），
 * 500 条容量覆盖队列、执行中和延期重试；满时淘汰尚未交付的较低优先级事件。
 *
 * 组合限制：[upload] 在内存入队时即返回 true，不代表交付完成。不得把本类作为
 * `ApmConfig.uploader` 与持久 SQLite outbox 组合——持久 worker 会据此 ACK 删除
 * durable 行，进程死亡即丢失事件；SDK 工厂对该组合快速失败。持久场景请直接传入
 * 原始传输层，重试由 PersistentUploadWorker 单一权威负责。
 */
class RetryingApmUploader(
    /** 被包装的底层上传器。 */
    private val delegate: ApmUploader,
    /** 重试策略。 */
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /** 批量大小：从队列中一次取出的最大事件数。 */
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** 刷盘间隔（毫秒）：队列无数据时的最大等待时间。 */
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    /** 日志输出，由宿主注入以尊重全局 debugLogging 开关。 */
    private val logger: UploaderLogger = UploaderLogger.DEFAULT
) : ApmUploader {

    /**
     * 事件优先级队列，按 priority.value DESC 排序。
     * CRITICAL(3) 最先出队，LOW(0) 最后出队。
     * PriorityBlockingQueue 是无界的，通过显式容量检查控制内存。
     */
    private val queue = PriorityBlockingQueue<PendingUpload>(
        QUEUE_INITIAL_CAPACITY,
        Comparator { left, right -> UploadPriorityComparator.compare(left.event, right.event) }
    )

    /** Accepted events across queued, active, and delayed states; guarded by [acceptanceLock]. */
    private val pendingUploads = LinkedHashSet<PendingUpload>()

    /** Serializes event acceptance with the shutdown state transition. */
    private val acceptanceLock = Any()

    /** 单线程执行器，保证上传顺序。 */
    private val executor = Executors.newSingleThreadExecutor(
        backgroundThreadFactory(THREAD_NAME)
    )

    /**
     * 重试调度器：失败批次延迟后重新尝试，
     * 退避等待不再阻塞主 drain 线程（新批次可继续上传）。
     */
    private val retryScheduler = ScheduledThreadPoolExecutor(
        1,
        backgroundThreadFactory(RETRY_SCHEDULER_THREAD_NAME)
    ).apply {
        // Eviction cancels delayed events immediately, including their retained payload closures.
        removeOnCancelPolicy = true
    }

    /** 运行标志。volatile 保证工作线程可见性。 */
    @Volatile
    private var running = true

    init {
        // 启动上传工作线程
        executor.execute { processLoop() }
    }

    /**
     * 将事件加入上传队列。
     * 队列满时仅允许更高优先级事件淘汰当前最低优先级事件。
     *
     * @param event 待上传事件
     * @return true 表示成功入队，false 表示被丢弃
     */
    override fun upload(event: ApmEvent): Boolean {
        synchronized(acceptanceLock) {
            // Shutdown cannot overtake an accepted event before it reaches the queue.
            if (!running) {
                return false
            }
            if (pendingUploads.size >= QUEUE_CAPACITY) {
                val evicted = evictLowerPriority(event)
                if (!evicted) {
                    logger.w(
                        "Uploader at hard capacity ($QUEUE_CAPACITY), dropping ${event.priority} event: ${event.name}"
                    )
                    return false
                }
            }
            val pending = PendingUpload(event)
            pendingUploads.add(pending)
            queue.offer(pending)
            return true
        }
    }

    /**
     * Closes the uploader after a bounded queue drain.
     */
    override fun shutdown() {
        synchronized(acceptanceLock) {
            running = false
            // Future retries are abandoned on shutdown; queued events retain the bounded drain.
            for (pending in pendingUploads.toList()) {
                if (pending.state == UploadState.RETRY_WAIT) {
                    completeLocked(pending)
                }
            }
        }
        retryScheduler.shutdownNow()
        // Wake poll/sleep so the worker can drain already accepted events.
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.w("Uploader did not drain within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(acceptanceLock) {
            // An uncooperative delegate must not retain queued payloads after the drain deadline.
            for (pending in pendingUploads.toList()) {
                completeLocked(pending)
            }
        }
        delegate.shutdown()
    }

    /**
     * Returns the current in-memory queue size.
     *
     * @return accepted events waiting for upload
     */
    fun queueSize(): Int = queue.size

    /**
     * Evicts one lower-priority event when the queue is full.
     *
     * Scans queued and delayed entries once, selecting the same minimum priority/oldest timestamp.
     * In-flight entries remain owned by the delegate and cannot be evicted.
     *
     * @param incoming event competing for capacity
     * @return true when an event was removed
     */
    private fun evictLowerPriority(incoming: ApmEvent): Boolean {
        // Callers hold acceptanceLock, so selection and terminal cancellation are atomic.
        var candidate: PendingUpload? = null
        for (pending in pendingUploads) {
            val event = pending.event
            if (pending.state == UploadState.IN_FLIGHT) {
                // An exchange already handed to the delegate cannot safely be withdrawn.
                continue
            }
            if (event.priority.value >= incoming.priority.value) {
                // 只逐出严格更低优先级的候选
                continue
            }
            val current = candidate
            if (current == null ||
                event.priority.value < current.event.priority.value ||
                (event.priority.value == current.event.priority.value && event.timestamp < current.event.timestamp)
            ) {
                candidate = pending
            }
        }
        val victim = candidate ?: return false
        completeLocked(victim)
        // 容量淘汰是真实丢失：与硬容量拒绝一样必须可见，不能静默吞没。
        logger.w(
            "Uploader capacity eviction: dropped ${victim.event.priority} event: ${victim.event.name}"
        )
        return true
    }

    /**
     * 上传工作线程主循环。
     * 1. 等待队列中有数据（最长 flushIntervalMs）
     * 2. 取出第一条，再 drain 最多 batchSize-1 条组成批量
     * 3. 调用底层上传器发送，失败时按重试策略重试
     */
    private fun processLoop() {
        val batch = mutableListOf<PendingUpload>()
        // running=false 后仍处理完队列中剩余事件
        while (running || queue.isNotEmpty()) {
            try {
                batch.clear()
                // 阻塞等待第一条（超时后继续循环检查 running）
                val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    // 非阻塞取出更多事件凑够一批
                    queue.drainTo(batch, batchSize - 1)
                    synchronized(acceptanceLock) {
                        // Eviction may race with poll/drain; only still-owned entries reach transport.
                        batch.removeAll { it !in pendingUploads }
                        for (pending in batch) {
                            pending.state = UploadState.IN_FLIGHT
                        }
                    }
                }
                if (batch.isEmpty()) {
                    continue
                }
                uploadBatchAttempt(batch.toList())
            } catch (_: InterruptedException) {
                if (running) {
                    Thread.currentThread().interrupt()
                    break
                }
                // shutdown wakes the worker; continue draining accepted events.
            }
        }
    }

    /**
     * 执行一次批量上传尝试；失败时经调度器延迟重投。
     *
     * 退避等待在独立的重试调度线程上完成，主 drain 线程立即返回
     * 继续处理新批次 —— 单个失败批次的退避不再让整个队列停摆。
     * 超过最大重试次数后丢弃整批。
     *
     * @param pendingBatch accepted entries, each with its own retry count
     */
    private fun uploadBatchAttempt(pendingBatch: List<PendingUpload>) {
        val events = pendingBatch.map(PendingUpload::event)
        val uploadSucceeded = try {
            // 只有整批事件都被底层 uploader 成功接收时才算本轮成功。
            if (delegate is BatchApmUploader) {
                delegate.uploadBatch(events)
            } else {
                events.all { delegate.upload(it) }
            }
        } catch (e: Exception) {
            // 传输异常按失败处理，走统一重投逻辑
            logger.w("Upload attempt threw: ${e.message}")
            false
        }
        val retryHintMs = if (uploadSucceeded) 0L else try {
            // Host code executes outside the acceptance lock so producers remain responsive.
            delegate.retryAfterHintMs() ?: 0L
        } catch (error: Exception) {
            logger.w("Retry hint failed: ${error.message}")
            0L
        }
        var exhaustedDrops = 0
        synchronized(acceptanceLock) {
            for (pending in pendingBatch) {
                if (pending !in pendingUploads) continue
                val nextAttempt = pending.attempt + 1
                if (uploadSucceeded || !running || nextAttempt > retryPolicy.maxRetries) {
                    // Capacity belongs to the event until success, exhaustion, eviction, or shutdown.
                    if (!uploadSucceeded && running) {
                        // 分支条件已蕴含 nextAttempt > maxRetries：重试耗尽是真实丢失，按批可见。
                        exhaustedDrops++
                    }
                    completeLocked(pending)
                    continue
                }
                pending.attempt = nextAttempt
                pending.state = UploadState.RETRY_WAIT
                // 下限钳制：baseDelayMs=0 或退化策略不能把失败批次变成无间隔热循环。
                val delay = maxOf(
                    retryPolicy.delayForAttempt(nextAttempt),
                    retryHintMs,
                    MIN_RETRY_DELAY_MS
                )
                try {
                    pending.retryFuture = retryScheduler.schedule(
                        { enqueueRetry(pending) },
                        delay,
                        TimeUnit.MILLISECONDS
                    )
                } catch (_: RejectedExecutionException) {
                    // Scheduler shutdown cannot strand an accepted entry or its capacity reservation.
                    completeLocked(pending)
                    logger.w("Retry scheduler closed, dropping one event")
                }
            }
        }
        if (exhaustedDrops > 0) {
            // 重试耗尽按批汇总告警：丢失必须可见，且不在锁内做日志 IO。
            logger.w("Upload retries exhausted (${retryPolicy.maxRetries}); dropped $exhaustedDrops event(s)")
        }
    }

    /** Requeues a due retry; the scheduler never invokes the host delegate concurrently. */
    private fun enqueueRetry(pending: PendingUpload) {
        synchronized(acceptanceLock) {
            if (!running || pending !in pendingUploads) return
            pending.retryFuture = null
            pending.state = UploadState.QUEUED
            queue.offer(pending)
        }
    }

    /** Releases one event exactly once and removes every queued or delayed payload reference. */
    private fun completeLocked(pending: PendingUpload) {
        if (!pendingUploads.remove(pending)) return
        queue.remove(pending)
        pending.retryFuture?.cancel(false)
        pending.retryFuture = null
    }

    /** One accepted event and its bounded retry lifecycle; mutable fields use [acceptanceLock]. */
    private class PendingUpload(
        /** Immutable event retained until a terminal disposition. */
        val event: ApmEvent
    ) {
        /** Number of retries already scheduled after the initial attempt. */
        var attempt = FIRST_ATTEMPT

        /** Current ownership state, including delayed work outside the visible queue. */
        var state = UploadState.QUEUED

        /** Cancellable delayed task, cleared before returning the event to the worker. */
        var retryFuture: ScheduledFuture<*>? = null
    }

    /** Finite states counted against the same accepted-event capacity. */
    private enum class UploadState {
        /** Waiting for the sole transport worker. */
        QUEUED,
        /** Currently owned by a delegate exchange. */
        IN_FLIGHT,
        /** Retained by a bounded delayed retry task. */
        RETRY_WAIT
    }

    companion object {
        /**
         * Creates one module-local thread factory without introducing an apm-core dependency.
         *
         * @param name stable SDK thread name
         * @return daemon/background thread factory for in-memory retry work
         */
        private fun backgroundThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, name).apply {
                // Best-effort in-memory telemetry must never define application-process liveness.
                isDaemon = true
                // Retry transport yields CPU scheduling preference to host application work.
                priority = BACKGROUND_THREAD_PRIORITY
            }
        }

        /** 工作线程名。 */
        private const val THREAD_NAME = "apm-upload-retry"

        /** 重试调度线程名。 */
        private const val RETRY_SCHEDULER_THREAD_NAME = "apm-upload-retry-scheduler"

        /** Background priority shared by both module-local retry executors. */
        private const val BACKGROUND_THREAD_PRIORITY = Thread.MIN_PRIORITY

        /** 首次尝试的序号。 */
        private const val FIRST_ATTEMPT = 0

        /** 队列初始容量（PriorityBlockingQueue 会自动扩容）。 */
        private const val QUEUE_INITIAL_CAPACITY = 500

        /** 队列最大容量阈值，超过时丢弃 LOW 优先级事件。 */
        private const val QUEUE_CAPACITY = 500

        /** 默认批量大小。 */
        private const val DEFAULT_BATCH_SIZE = 10

        /** 默认刷盘间隔：30 秒。 */
        private const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L

        /** Maximum time allowed for a graceful in-memory drain. */
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /** Lower retry-delay bound that prevents a zero-delay hot loop without inflating tuned delays. */
        private const val MIN_RETRY_DELAY_MS = 1L
    }
}
