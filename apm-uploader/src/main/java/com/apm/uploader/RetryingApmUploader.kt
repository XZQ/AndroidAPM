package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
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
 * 线程安全：内部使用单线程执行器 + PriorityBlockingQueue。
 * 队列按 priority.value DESC 排序（CRITICAL 优先出队），
 * 队列超容量时丢弃 LOW 优先级事件。
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
    private val queue = PriorityBlockingQueue<ApmEvent>(
        QUEUE_INITIAL_CAPACITY,
        UploadPriorityComparator
    )

    /** Hard capacity permits shared by producers and the worker. */
    private val capacityPermits = Semaphore(QUEUE_CAPACITY)

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
    private val retryScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            backgroundThreadFactory(RETRY_SCHEDULER_THREAD_NAME)
        )

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
            if (!capacityPermits.tryAcquire()) {
                val evicted = evictLowerPriority(event)
                if (!evicted || !capacityPermits.tryAcquire()) {
                    logger.w(
                        "Queue at hard capacity ($QUEUE_CAPACITY), dropping ${event.priority} event: ${event.name}"
                    )
                    return false
                }
            }
            queue.offer(event)
            return true
        }
    }

    /**
     * Closes the uploader after a bounded queue drain.
     */
    override fun shutdown() {
        synchronized(acceptanceLock) {
            running = false
        }
        // Wake poll/sleep so the worker can drain already accepted events.
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.w("Uploader did not drain within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 关闭重试调度器：未到期的重投任务被放弃（内存路径尽力而为）
        retryScheduler.shutdownNow()
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
     * 单趟手工扫描替代 filter + minWithOrNull 的两个中间 list 分配：
     * 维护最优候选引用即可完成同样的 (priority, timestamp) 最小值选择。
     *
     * @param incoming event competing for capacity
     * @return true when an event was removed
     */
    private fun evictLowerPriority(incoming: ApmEvent): Boolean {
        synchronized(queue) {
            var candidate: ApmEvent? = null
            for (event in queue) {
                if (event.priority.value >= incoming.priority.value) {
                    // 只逐出严格更低优先级的候选
                    continue
                }
                val current = candidate
                if (current == null ||
                    event.priority.value < current.priority.value ||
                    (event.priority.value == current.priority.value && event.timestamp < current.timestamp)
                ) {
                    candidate = event
                }
            }
            val victim = candidate ?: return false
            if (queue.remove(victim)) {
                capacityPermits.release()
                return true
            }
            return false
        }
    }

    /**
     * 上传工作线程主循环。
     * 1. 等待队列中有数据（最长 flushIntervalMs）
     * 2. 取出第一条，再 drain 最多 batchSize-1 条组成批量
     * 3. 调用底层上传器发送，失败时按重试策略重试
     */
    private fun processLoop() {
        val batch = mutableListOf<ApmEvent>()
        // running=false 后仍处理完队列中剩余事件
        while (running || queue.isNotEmpty()) {
            try {
                batch.clear()
                // 阻塞等待第一条（超时后继续循环检查 running）
                val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    capacityPermits.release()
                    batch.add(first)
                    // 非阻塞取出更多事件凑够一批
                    val drained = queue.drainTo(batch, batchSize - 1)
                    if (drained > 0) {
                        capacityPermits.release(drained)
                    }
                }
                if (batch.isEmpty()) {
                    continue
                }
                uploadBatchAttempt(batch.toList(), FIRST_ATTEMPT)
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
     * @param events 本批事件
     * @param attempt 当前尝试序号（0 = 首次）
     */
    private fun uploadBatchAttempt(events: List<ApmEvent>, attempt: Int) {
        val uploadSucceeded = try {
            // 只有整批事件都被底层 uploader 成功接收时才算本轮成功。
            if (delegate is BatchApmUploader) {
                delegate.uploadBatch(events)
            } else {
                events.all { delegate.upload(it) }
            }
        } catch (e: Exception) {
            // 传输异常按失败处理，走统一重投逻辑
            logger.w("Upload attempt ${attempt + 1} threw: ${e.message}")
            false
        }
        if (uploadSucceeded) {
            return
        }

        val nextAttempt = attempt + 1
        if (nextAttempt > retryPolicy.maxRetries) {
            logger.e("Upload failed after ${retryPolicy.maxRetries} retries, dropping ${events.size} events")
            return
        }

        // 按指数退避延迟重投；服务端 Retry-After 建议优先于本地退避
        val delay = maxOf(
            retryPolicy.delayForAttempt(nextAttempt),
            delegate.retryAfterHintMs() ?: 0L
        )
        try {
            retryScheduler.schedule(
                { uploadBatchAttempt(events, nextAttempt) },
                delay,
                TimeUnit.MILLISECONDS
            )
        } catch (_: RejectedExecutionException) {
            // 调度器已关闭（shutdown 期间），内存路径尽力而为，放弃重试
            logger.w("Retry scheduler closed, dropping ${events.size} events")
        }
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
    }
}
