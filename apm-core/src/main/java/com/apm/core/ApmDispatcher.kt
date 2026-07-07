package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import com.apm.core.aggregation.EventAggregator
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.EventStore
import com.apm.storage.PendingEventStore
import com.apm.core.throttle.RateLimiter
import com.apm.uploader.ApmUploader
import com.apm.uploader.RetryPolicy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * APM 事件分发器。
 * 负责聚合 → 限流检查 → PII 脱敏 → 本地存储 → 上传的五阶段流水线。
 *
 * 线程模型（性能关键设计）：
 * - 调用线程（常为主线程）只做：shutdown 检查 + 发射计数 + 有界队列入队，
 *   聚合、限流、脱敏、序列化、存储全部推迟到 "apm-dispatcher" worker 线程；
 * - worker 每轮从队列批量取出至多 [MAX_BATCH_DRAIN] 条事件，
 *   经 [EventStore.appendBatch] 单事务落盘，摊薄每条事件的写入开销；
 * - 队列容量固定为 [QUEUE_CAPACITY]，满时丢弃新事件并计入自监控，
 *   杜绝无界积压导致的 OOM。
 *
 * 集成 SDK 自监控：在每个关键节点调用 [SdkSelfMonitor] 记录指标。
 */
internal class ApmDispatcher(
    /** 本地事件存储。 */
    private val store: EventStore,
    /** 上传通道。 */
    private val uploader: ApmUploader,
    /** 日志接口。 */
    private val logger: ApmLogger,
    /** 可选限流器，null 表示不限流。 */
    private val rateLimiter: RateLimiter? = null,
    /** 可选事件聚合器，null 表示不聚合。 */
    private val aggregator: EventAggregator? = null,
    /** 可选 PII 脱敏器，null 表示不脱敏。 */
    private val piiSanitizer: PiiSanitizer? = null,
    /** 可选 SDK 自监控组件，null 表示不自监控。 */
    var selfMonitor: SdkSelfMonitor? = null,
    /** Retry policy for the persistent upload worker. */
    retryPolicy: RetryPolicy = RetryPolicy(),
    /** Maximum events sent in one durable batch. */
    uploadBatchSize: Int = DEFAULT_UPLOAD_BATCH_SIZE
) {
    /**
     * 队列元素：已构建事件或延迟构建工厂 + 是否已经过聚合处理。
     * 聚合器周期性刷出的聚合结果不能再次进入聚合器，需要打标跳过。
     * 延迟工厂用于把事件构建（上下文 map 合并等）从调用线程搬到 worker 线程。
     */
    private class QueuedEvent(
        /** 已构建事件；为 null 时使用 [eventFactory] 延迟构建。 */
        val event: ApmEvent?,
        /** 延迟构建工厂，在 worker 线程执行。 */
        val eventFactory: (() -> ApmEvent)? = null,
        /** true 表示事件已由聚合器输出，跳过聚合阶段。 */
        val preAggregated: Boolean = false
    ) {
        /**
         * 解析出最终事件（可能触发延迟构建）。
         *
         * @return 待处理事件
         */
        fun resolve(): ApmEvent = event ?: checkNotNull(eventFactory).invoke()
    }

    /** 待处理事件的有界队列（背压保护：满时丢弃并计数，绝不阻塞调用线程）。 */
    private val queue = ArrayBlockingQueue<QueuedEvent>(QUEUE_CAPACITY)

    /** worker 循环运行标志。 */
    @Volatile
    private var running = true

    /** 分发器是否已关闭（不再接受新事件）。 */
    @Volatile
    private var shutdown = false

    /** Durable outbox worker, present only for a [PendingEventStore]. */
    private val persistentUploadWorker = (store as? PendingEventStore)?.let { pendingStore ->
        PersistentUploadWorker(
            store = pendingStore,
            uploader = uploader,
            retryPolicy = retryPolicy,
            batchSize = uploadBatchSize.coerceAtLeast(1),
            logger = logger,
            selfMonitor = selfMonitor
        )
    }

    /** 分发 worker 线程：批量消费队列并执行完整流水线。 */
    private val workerThread = Thread(::workerLoop, THREAD_NAME).apply {
        // daemon 线程避免阻止进程退出
        isDaemon = true
        start()
    }

    /** Periodic executor that flushes expired aggregation windows. */
    private val aggregationExecutor: ScheduledExecutorService? = aggregator?.let { eventAggregator ->
        ApmExecutors.newSingleThreadScheduledExecutor(AGGREGATION_THREAD_NAME).apply {
            val interval = eventAggregator.windowDurationMs
                .coerceAtMost(MAX_AGGREGATION_POLL_MS)
                .coerceAtLeast(MIN_AGGREGATION_POLL_MS)
            scheduleWithFixedDelay(
                {
                    // 聚合结果重新入队（打 preAggregated 标记避免二次聚合）
                    for (event in eventAggregator.flushExpired()) {
                        enqueue(QueuedEvent(event, preAggregated = true))
                    }
                },
                interval,
                interval,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * 分发一个事件。
     * 调用线程只做入队，聚合/限流/脱敏/存储全部在 worker 线程执行。
     */
    fun dispatch(event: ApmEvent) {
        // stop 之后直接拒绝新事件，避免关闭期间出现尾部写入。
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop ${event.module}/${event.name}")
            return
        }

        // 记录事件发射
        selfMonitor?.recordEmit()

        enqueue(QueuedEvent(event))
    }

    /**
     * 分发一个延迟构建的事件。
     * 事件构建（上下文 map 合并等分配开销）在 worker 线程执行，
     * 调用线程只承担一次闭包分配 + 入队。
     *
     * @param eventFactory 事件构建工厂，须为纯函数（可安全在 worker 线程执行）
     */
    fun dispatchLazy(eventFactory: () -> ApmEvent) {
        // stop 之后直接拒绝新事件
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop lazy event")
            return
        }

        // 记录事件发射
        selfMonitor?.recordEmit()

        enqueue(QueuedEvent(event = null, eventFactory = eventFactory))
    }

    /**
     * 入队一个事件；队列满时丢弃并计入自监控。
     *
     * @param queued 待入队元素
     */
    private fun enqueue(queued: QueuedEvent) {
        // offer 非阻塞：满即丢弃，绝不阻塞调用线程
        if (!queue.offer(queued)) {
            // 延迟构建事件在溢出时无法得知优先级，按默认优先级计数
            logger.d("Dispatcher queue full, drop event")
            selfMonitor?.recordDrop(queued.event?.priority ?: com.apm.model.ApmPriority.NORMAL)
        }
    }

    /**
     * Persists a critical event synchronously before a process may terminate.
     *
     * This method deliberately avoids a blocking network request. A durable
     * outbox worker replays the event on this or the next process start.
     *
     * @param event critical event
     * @return true when local persistence succeeded
     */
    fun dispatchCriticalSync(event: ApmEvent): Boolean {
        if (shutdown) return false
        selfMonitor?.recordEmit()
        return runCatching {
            val sanitizedEvent = piiSanitizer?.sanitize(event) ?: event
            store.append(sanitizedEvent)
            persistentUploadWorker?.signal()
            true
        }.onFailure {
            logger.e("Failed to persist critical event ${event.module}/${event.name}", it)
            selfMonitor?.recordDrop(event.priority)
        }.getOrDefault(false)
    }

    /**
     * worker 主循环。
     * 阻塞等待首条事件后批量 drain，整批执行流水线；
     * running=false 后继续处理直到队列排空。
     */
    private fun workerLoop() {
        val drainBuffer = ArrayList<QueuedEvent>(MAX_BATCH_DRAIN)
        // 关闭后仍需排空已接受的事件
        while (running || queue.isNotEmpty()) {
            val first = try {
                queue.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // 中断视为关闭加速信号，回到循环条件判断
                continue
            } ?: continue

            drainBuffer.clear()
            drainBuffer.add(first)
            // 非阻塞补齐一批，摊薄事务与信号开销
            queue.drainTo(drainBuffer, MAX_BATCH_DRAIN - 1)
            processBatch(drainBuffer)

            // 更新队列积压快照供自监控上报
            selfMonitor?.updateQueueSize(queue.size)
        }
    }

    /**
     * 在 worker 线程处理一批事件：聚合 → 限流 → 脱敏 → 批量存储 → 上传。
     *
     * @param batch 本轮取出的队列元素
     */
    private fun processBatch(batch: List<QueuedEvent>) {
        // 聚合与限流在此线程执行，调用线程零开销
        val toPersist = ArrayList<ApmEvent>(batch.size)
        for (queued in batch) {
            // 延迟构建的事件在此线程完成构建（上下文合并等）
            val resolved = queued.resolve()
            // 聚合检查 — 可能吞入事件（返回空）或输出聚合结果
            val expanded = if (aggregator != null && !queued.preAggregated) {
                aggregator.process(resolved)
            } else {
                listOf(resolved)
            }
            for (event in expanded) {
                // 限流检查（ERROR/FATAL 跳过限流，保证关键事件不丢失）
                if (!passesRateLimit(event)) continue
                // PII 脱敏：在存储和上传前对文本字段执行脱敏
                toPersist += piiSanitizer?.sanitize(event) ?: event
            }
        }
        if (toPersist.isEmpty()) return

        try {
            val startTime = System.currentTimeMillis()
            // 单事务批量落盘
            store.appendBatch(toPersist)

            if (persistentUploadWorker != null) {
                // The durable row is the ownership hand-off point.
                persistentUploadWorker.signal()
            } else {
                // 内存路径逐条交给 uploader（其内部自带队列/批量）
                for (event in toPersist) {
                    if (!uploader.upload(event)) {
                        logger.w("Uploader rejected ${event.module}/${event.name}")
                        // 上传被拒绝计入丢弃
                        selfMonitor?.recordDrop(event.priority)
                    }
                }
                // 记录整批处理延迟
                selfMonitor?.recordUploadLatency(System.currentTimeMillis() - startTime)
            }
        } catch (throwable: Throwable) {
            logger.e("Failed to dispatch batch of ${toPersist.size} events", throwable)
            // 异常整批计入丢弃
            for (event in toPersist) {
                selfMonitor?.recordDrop(event.priority)
            }
        }
    }

    /**
     * 限流检查。
     *
     * @param event 待检查事件
     * @return true 表示放行；false 表示已被限流并计数
     */
    private fun passesRateLimit(event: ApmEvent): Boolean {
        // ERROR/FATAL 事件绕过限流，确保崩溃/ANR 等关键事件必达
        if (rateLimiter == null ||
            event.severity == ApmSeverity.ERROR ||
            event.severity == ApmSeverity.FATAL
        ) {
            return true
        }
        val key = "${event.module}/${event.name}"
        if (rateLimiter.tryAcquire(key)) return true
        logger.d("Rate limited: $key")
        // 记录事件丢弃
        selfMonitor?.recordDrop(event.priority)
        return false
    }

    /**
     * 关闭分发器，停止接受新事件。
     * 排空队列 → 刷出聚合器残留 → 关闭上传与存储。
     */
    fun shutdown() {
        shutdown = true
        aggregationExecutor?.shutdownNow()

        // 通知 worker 排空队列后退出
        running = false
        try {
            workerThread.join(DISPATCH_SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (workerThread.isAlive) {
            logger.w("Dispatcher did not drain within ${DISPATCH_SHUTDOWN_TIMEOUT_MS}ms")
            workerThread.interrupt()
        }

        // 刷出聚合器的残留数据（worker 已退出，直接写存储）
        aggregator?.let { agg ->
            val remaining = agg.flush()
            for (event in remaining) {
                try {
                    store.append(event)
                    if (persistentUploadWorker != null) {
                        persistentUploadWorker.signal()
                    } else {
                        uploader.upload(event)
                    }
                } catch (e: Throwable) {
                    logger.e("Failed to flush aggregated event", e)
                }
            }
        }

        if (persistentUploadWorker != null) {
            persistentUploadWorker.shutdown()
        } else {
            uploader.shutdown()
        }
        store.close()
    }

    companion object {
        /** 分发线程名，便于日志和性能分析定位。 */
        private const val THREAD_NAME = "apm-dispatcher"

        /** Default durable upload batch size. */
        private const val DEFAULT_UPLOAD_BATCH_SIZE = 20

        /** Maximum time allowed for already accepted dispatch tasks. */
        private const val DISPATCH_SHUTDOWN_TIMEOUT_MS = 3_000L

        /** Aggregation maintenance thread name. */
        private const val AGGREGATION_THREAD_NAME = "apm-aggregation"

        /** Fastest aggregation expiry polling interval. */
        private const val MIN_AGGREGATION_POLL_MS = 1_000L

        /** Slowest aggregation expiry polling interval. */
        private const val MAX_AGGREGATION_POLL_MS = 60_000L

        /** 有界事件队列容量：满时丢弃新事件（背压保护）。 */
        private const val QUEUE_CAPACITY = 2_048

        /** worker 单轮最多批量取出的事件数。 */
        private const val MAX_BATCH_DRAIN = 32

        /** worker 空闲时的队列轮询超时（毫秒），决定关闭响应延迟。 */
        private const val WORKER_POLL_MS = 100L
    }
}
