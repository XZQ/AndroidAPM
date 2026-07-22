package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import com.apm.core.aggregation.EventAggregator
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.EventStore
import com.apm.storage.EventStoreAppendResult
import com.apm.storage.PendingEventStore
import com.apm.core.throttle.RateLimiter
import com.apm.core.throttle.DynamicEventPolicy
import com.apm.core.throttle.DynamicConfigProvider
import com.apm.uploader.ApmUploader
import com.apm.uploader.RetryPolicy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Runs one aggregation-maintenance tick without cancelling future ticks after recoverable failure. */
internal inline fun runAggregationMaintenance(
    flush: () -> List<ApmEvent>,
    enqueueEvent: (ApmEvent) -> Unit,
    onFailure: (Exception) -> Unit
) {
    try {
        for (event in flush()) {
            enqueueEvent(event)
        }
    } catch (error: Exception) {
        onFailure(error)
    }
}

/**
 * Process-local event data removed during consent revocation.
 *
 * @property discardedQueuedEventCount events removed before dispatcher processing
 * @property clearedStoredEventCount pending durable rows observed before clearing, when supported
 * @property storageCleared whether [EventStore.clear] completed successfully
 */
internal data class ConsentStorageCleanupResult(
    val discardedQueuedEventCount: Int,
    val clearedStoredEventCount: Int?,
    val storageCleared: Boolean
)

/**
 * APM 事件分发器。
 * 负责聚合 → 限流检查 → PII 脱敏 → 本地存储 → 上传的五阶段流水线。
 *
 * 线程模型（性能关键设计）：
 * - 调用线程（常为主线程）只做：shutdown 检查 + 发射计数 + 有界队列入队，
 *   聚合、限流、脱敏、序列化、存储全部推迟到 "apm-dispatcher" worker 线程；
 * - worker 每轮从队列批量取出至多 [MAX_BATCH_DRAIN] 条事件，
 *   经 [EventStore.appendBatch] 单事务落盘，摊薄每条事件的写入开销；
 * - 队列容量固定为 [QUEUE_CAPACITY]，并另施加 retained-byte 估算预算；压力下允许
 *   高优先级事件替换足够数量的最低优先级事件，同级保持先到先得，杜绝大事件在
 *   条数有界的外观下造成无界内存积压。
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
    /** Dynamic policy source used for sampling and rate-limit overrides. */
    dynamicConfigProvider: DynamicConfigProvider = DynamicConfigProvider.NOOP,
    /** 可选事件聚合器，null 表示不聚合。 */
    private val aggregator: EventAggregator? = null,
    /** 可选 PII 脱敏器，null 表示不脱敏。 */
    private val piiSanitizer: PiiSanitizer? = null,
    /** 可选 SDK 自监控组件，null 表示不自监控。 */
    var selfMonitor: SdkSelfMonitor? = null,
    /** Retry policy for the persistent upload worker. */
    retryPolicy: RetryPolicy = RetryPolicy(),
    /** Maximum events sent in one durable batch. */
    uploadBatchSize: Int = DEFAULT_UPLOAD_BATCH_SIZE,
    /** Duration for which one durable upload worker owns a claimed batch. */
    uploadLeaseDurationMs: Long = DEFAULT_UPLOAD_LEASE_DURATION_MS,
    /** Bounded ingress capacity; exposed internally for deterministic pressure tests. */
    queueCapacity: Int = QUEUE_CAPACITY,
    /** Retained-byte budget for queued event payloads and lazy-event captures. */
    maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    /** Whether one noisy NORMAL/LOW module is isolated after the shared queue reaches high water. */
    enableModuleIsolation: Boolean = true,
    /** Queue occupancy percentage that activates per-module isolation. */
    moduleIsolationHighWatermarkPercent: Int = DEFAULT_MODULE_ISOLATION_HIGH_WATERMARK_PERCENT,
    /** Maximum queue-capacity percentage available to one NORMAL/LOW module under pressure. */
    maxModuleQueueSharePercent: Int = DEFAULT_MAX_MODULE_QUEUE_SHARE_PERCENT
) {
    /** Fail-safe resolver for signed sampling and rate-limit configuration. */
    private val dynamicEventPolicy = DynamicEventPolicy(dynamicConfigProvider) { error ->
        Apm.recordInternalError(ERROR_DYNAMIC_EVENT_POLICY, error)
    }

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
        val preAggregated: Boolean = false,
        /** Admission priority known before a lazy event is resolved. */
        val priority: ApmPriority,
        /** Source module known before lazy resolution and used for noisy-neighbor isolation. */
        val sourceModule: String,
        /** Conservative retained-byte admission weight reserved while this element is queued. */
        val estimatedBytes: Long
    ) {
        /**
         * 解析出最终事件（可能触发延迟构建）。
         *
         * @return 待处理事件
         */
        fun resolve(): ApmEvent = event ?: checkNotNull(eventFactory).invoke()
    }

    /** Effective positive ingress capacity shared by the queue and isolation thresholds. */
    private val effectiveQueueCapacity = queueCapacity.coerceAtLeast(1)

    /** Effective positive retained-byte budget applied independently of event count. */
    private val effectiveMaxQueuedBytes = maxQueuedBytes.coerceAtLeast(MIN_QUEUED_BYTES)

    /** Whether per-module queue isolation is enabled. */
    private val moduleIsolationEnabled = enableModuleIsolation

    /** Validated queue percentage at which noisy-neighbor isolation starts. */
    private val effectiveModuleIsolationHighWatermarkPercent =
        moduleIsolationHighWatermarkPercent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Validated maximum share, never larger than the activation watermark. */
    private val effectiveMaxModuleQueueSharePercent = maxModuleQueueSharePercent.coerceIn(
        MIN_PERCENT,
        effectiveModuleIsolationHighWatermarkPercent
    )

    /** Absolute queue size that activates module isolation. */
    private val moduleIsolationHighWatermarkSize = percentOfCapacity(
        effectiveQueueCapacity,
        effectiveModuleIsolationHighWatermarkPercent
    )

    /** Absolute maximum queued events allowed for one NORMAL/LOW module under pressure. */
    private val maxModuleQueueSize = percentOfCapacity(
        effectiveQueueCapacity,
        effectiveMaxModuleQueueSharePercent
    )

    /** 待处理事件的有界队列（背压保护：满时丢弃并计数，绝不阻塞调用线程）。 */
    private val queue = ArrayBlockingQueue<QueuedEvent>(effectiveQueueCapacity)

    /** Makes producer admission and overflow replacement atomic without ever waiting. */
    private val admissionLock = ReentrantLock()

    /** Per-module queued occupancy guarded by [admissionLock]. */
    private val queuedModuleCounts = HashMap<String, Int>()

    /** Total reserved retained bytes guarded by [admissionLock]. */
    @Volatile
    private var queuedBytes = 0L

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
            leaseDurationMs = uploadLeaseDurationMs.coerceAtLeast(MIN_UPLOAD_LEASE_DURATION_MS),
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
                    runAggregationMaintenance(
                        flush = eventAggregator::flushExpired,
                        // 聚合结果重新入队（打 preAggregated 标记避免二次聚合）
                        enqueueEvent = { event ->
                            enqueue(
                                QueuedEvent(
                                    event = event,
                                    preAggregated = true,
                                    priority = event.priority,
                                    sourceModule = normalizedModule(event.module),
                                    estimatedBytes = ApmEventSizeEstimator.estimate(event)
                                )
                            )
                        },
                        onFailure = { error ->
                            logger.e("Failed to flush expired aggregation windows", error)
                            Apm.recordInternalError(ERROR_AGGREGATION_MAINTENANCE, error)
                        }
                    )
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
        selfMonitor?.recordEmit()
        // stop 之后直接拒绝新事件，避免关闭期间出现尾部写入。
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop ${event.module}/${event.name}")
            selfMonitor?.recordDrop(event.priority, SdkDropReason.DISPATCHER_SHUTDOWN)
            return
        }

        // Directly constructed module events may still reference mutable host maps.
        val eventSnapshot = snapshotEvent(event)

        enqueue(
            QueuedEvent(
                event = eventSnapshot,
                priority = eventSnapshot.priority,
                sourceModule = normalizedModule(eventSnapshot.module),
                estimatedBytes = ApmEventSizeEstimator.estimate(eventSnapshot)
            )
        )
    }

    /**
     * 分发一个延迟构建的事件。
     * 事件构建（上下文 map 合并等分配开销）在 worker 线程执行，
     * 调用线程只承担一次闭包分配 + 入队。
     *
     * @param priority 入队前已知的事件优先级，用于队列压力下的准入决策
     * @param sourceModule 入队前已知的来源模块，用于共享队列的 noisy-neighbor 隔离
     * @param estimatedBytes conservative retained-byte weight captured before lazy construction
     * @param eventFactory 事件构建工厂，须为纯函数（可安全在 worker 线程执行）
     */
    fun dispatchLazy(
        priority: ApmPriority = ApmPriority.NORMAL,
        sourceModule: String = UNKNOWN_SOURCE_MODULE,
        estimatedBytes: Long = DEFAULT_LAZY_EVENT_ESTIMATE_BYTES,
        eventFactory: () -> ApmEvent
    ) {
        selfMonitor?.recordEmit()
        // stop 之后直接拒绝新事件
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop lazy event")
            selfMonitor?.recordDrop(priority, SdkDropReason.DISPATCHER_SHUTDOWN)
            return
        }

        enqueue(
            QueuedEvent(
                event = null,
                eventFactory = eventFactory,
                priority = priority,
                sourceModule = normalizedModule(sourceModule),
                estimatedBytes = estimatedBytes.coerceAtLeast(MIN_EVENT_ESTIMATE_BYTES)
            )
        )
    }

    /**
     * 入队一个事件；队列满时丢弃并计入自监控。
     *
     * @param queued 待入队元素
     */
    private fun enqueue(queued: QueuedEvent) {
        // Producer contention drops immediately instead of delaying host work. Every producer
        // participates so no caller can steal the slot between a priority eviction and replace.
        if (!admissionLock.tryLock()) {
            logger.d("Dispatcher admission busy, drop priority=${queued.priority}")
            selfMonitor?.recordDrop(queued.priority, SdkDropReason.DISPATCHER_ADMISSION_BUSY)
            return
        }
        try {
            // A single event can never fit this queue regardless of priority or evictions.
            if (queued.estimatedBytes > effectiveMaxQueuedBytes) {
                logger.d(
                    "Dispatcher event exceeds byte budget, drop priority=${queued.priority} " +
                        "estimatedBytes=${queued.estimatedBytes}"
                )
                selfMonitor?.recordDrop(queued.priority, SdkDropReason.DISPATCHER_BYTE_BUDGET)
                return
            }

            // Once the shared queue is pressured, preserve capacity for other modules without
            // weakening delivery of HIGH/CRITICAL signals from the noisy module.
            if (shouldIsolateModule(queued)) {
                logger.d(
                    "Dispatcher module isolated, drop module=${queued.sourceModule} " +
                        "priority=${queued.priority}"
                )
                selfMonitor?.recordDispatcherModuleIsolationDrop(queued.priority)
                return
            }

            // The common path remains one non-blocking offer after a non-waiting lock attempt.
            if (offerTracked(queued)) {
                return
            }

            val bytePressure = !hasByteCapacity(queued.estimatedBytes)
            val needsQueueSlot = queue.remainingCapacity() == 0
            val bytesToFree = if (bytePressure) {
                queuedBytes - (effectiveMaxQueuedBytes - queued.estimatedBytes)
            } else {
                0L
            }
            val evictionCandidates = queue
                .filter { existing -> existing.priority.value < queued.priority.value }
                .sortedBy(QueuedEvent::priority)
            val selectedVictims = ArrayList<QueuedEvent>()
            var selectedBytes = 0L
            // Byte pressure may require multiple lower-priority victims, unlike count pressure.
            for (candidate in evictionCandidates) {
                selectedVictims += candidate
                selectedBytes += candidate.estimatedBytes
                if ((!needsQueueSlot || selectedVictims.isNotEmpty()) && selectedBytes >= bytesToFree) {
                    break
                }
            }
            val canReplace = (!needsQueueSlot || selectedVictims.isNotEmpty()) && selectedBytes >= bytesToFree
            if (canReplace) {
                for (victim in selectedVictims) {
                    if (removeTracked(victim)) {
                        logger.d(
                            "Dispatcher capacity eviction priority=${victim.priority} " +
                                "for priority=${queued.priority}"
                        )
                        selfMonitor?.recordDrop(
                            victim.priority,
                            SdkDropReason.DISPATCHER_PRIORITY_EVICTION
                        )
                    }
                }
                if (offerTracked(queued)) {
                    return
                }
            }

            val reason = if (!hasByteCapacity(queued.estimatedBytes)) {
                SdkDropReason.DISPATCHER_BYTE_BUDGET
            } else {
                SdkDropReason.DISPATCHER_QUEUE_FULL
            }
            logger.d("Dispatcher capacity full, drop priority=${queued.priority} reason=$reason")
            selfMonitor?.recordDrop(queued.priority, reason)
        } finally {
            admissionLock.unlock()
        }
    }

    /** Returns true when a NORMAL/LOW source has consumed its pressure-time queue share. */
    private fun shouldIsolateModule(queued: QueuedEvent): Boolean {
        if (!moduleIsolationEnabled || queued.priority.value >= ApmPriority.HIGH.value) {
            return false
        }
        if (queue.size < moduleIsolationHighWatermarkSize) {
            return false
        }
        return (queuedModuleCounts[queued.sourceModule] ?: 0) >= maxModuleQueueSize
    }

    /** Offers one element and atomically publishes its per-module occupancy before visibility. */
    private fun offerTracked(queued: QueuedEvent): Boolean {
        if (!hasByteCapacity(queued.estimatedBytes)) {
            return false
        }
        incrementQueuedModule(queued.sourceModule)
        if (queue.offer(queued)) {
            queuedBytes += queued.estimatedBytes
            return true
        }
        // Roll back the reservation when the bounded queue rejected the element.
        decrementQueuedModule(queued.sourceModule)
        return false
    }

    /** Returns whether one retained-byte reservation fits without integer overflow. */
    private fun hasByteCapacity(estimatedBytes: Long): Boolean {
        return estimatedBytes <= effectiveMaxQueuedBytes &&
            queuedBytes <= effectiveMaxQueuedBytes - estimatedBytes
    }

    /** Removes one queued victim and releases both count and byte occupancy. */
    private fun removeTracked(queued: QueuedEvent): Boolean {
        if (!queue.remove(queued)) {
            return false
        }
        decrementQueuedModule(queued.sourceModule)
        queuedBytes = (queuedBytes - queued.estimatedBytes).coerceAtLeast(0L)
        return true
    }

    /** Increments one module occupancy while [admissionLock] is held. */
    private fun incrementQueuedModule(sourceModule: String) {
        if (!moduleIsolationEnabled) {
            return
        }
        queuedModuleCounts[sourceModule] = (queuedModuleCounts[sourceModule] ?: 0) + 1
    }

    /** Decrements one module occupancy and removes empty keys while [admissionLock] is held. */
    private fun decrementQueuedModule(sourceModule: String) {
        if (!moduleIsolationEnabled) {
            return
        }
        val remaining = (queuedModuleCounts[sourceModule] ?: 1) - 1
        if (remaining <= 0) {
            queuedModuleCounts.remove(sourceModule)
        } else {
            queuedModuleCounts[sourceModule] = remaining
        }
    }

    /** Releases occupancy for a worker-drained batch before running the potentially slow pipeline. */
    private fun releaseQueueOccupancy(batch: List<QueuedEvent>) {
        admissionLock.lock()
        try {
            for (queued in batch) {
                decrementQueuedModule(queued.sourceModule)
                queuedBytes = (queuedBytes - queued.estimatedBytes).coerceAtLeast(0L)
            }
        } finally {
            admissionLock.unlock()
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
        selfMonitor?.recordEmit()
        if (shutdown) {
            selfMonitor?.recordDrop(event.priority, SdkDropReason.DISPATCHER_SHUTDOWN)
            return false
        }
        return try {
            val sanitizedEvent = piiSanitizer?.sanitize(event) ?: event
            val appendResult = store.appendWithResult(sanitizedEvent)
            recordStorageResult(appendResult)
            if (appendResult.acceptedEventCount <= 0) {
                return false
            }
            persistentUploadWorker?.signal()
            true
        } catch (error: Exception) {
            logger.e("Failed to persist critical event ${event.module}/${event.name}", error)
            selfMonitor?.recordDrop(event.priority, SdkDropReason.STORAGE_FAILURE)
            Apm.recordInternalError(ERROR_PROCESS_EVENT, error)
            false
        }
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
            // Queue-share accounting covers queued work only, not the batch currently executing.
            releaseQueueOccupancy(drainBuffer)
            processBatch(drainBuffer)

            // 更新队列积压快照供自监控上报
            selfMonitor?.updateQueuePressure(queue.size, queuedBytes)
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
            try {
                // 延迟构建的事件在此线程完成构建（上下文合并等）
                val resolved = queued.resolve()
                if (!queued.preAggregated && !passesSampling(resolved)) {
                    continue
                }
                // 聚合检查 — 可能吞入事件（返回空）或输出聚合结果
                val expanded = if (aggregator != null && !queued.preAggregated) {
                    aggregator.process(resolved)
                } else {
                    listOf(resolved)
                }
                for (event in expanded) {
                    // 限流检查（ERROR/FATAL 跳过限流，保证关键事件不丢失）
                    if (!passesRateLimit(event)) {
                        continue
                    }
                    // PII 脱敏：在存储和上传前对文本字段执行脱敏
                    toPersist += piiSanitizer?.sanitize(event) ?: event
                }
            } catch (error: Exception) {
                // One malformed or failing monitor event must not terminate the shared worker.
                logger.e("Failed to process queued event", error)
                selfMonitor?.recordDrop(
                    queued.event?.priority ?: queued.priority,
                    SdkDropReason.EVENT_PROCESSING_FAILURE
                )
                Apm.recordInternalError(ERROR_PROCESS_EVENT, error)
            }
        }
        if (toPersist.isEmpty()) {
            return
        }

        try {
            val startTime = ApmClock.monotonicTimeMillis()
            // 单事务批量落盘
            val appendResult = store.appendBatchWithResult(toPersist)
            recordStorageResult(appendResult)
            if (appendResult.acceptedEventCount <= 0) {
                return
            }

            if (persistentUploadWorker != null) {
                // The durable row is the ownership hand-off point.
                persistentUploadWorker.signal()
            } else {
                // 内存路径逐条交给 uploader（其内部自带队列/批量）
                val rejectedEventIds = appendResult.rejectedEvents.mapTo(hashSetOf(), ApmEvent::eventId)
                for (event in toPersist) {
                    if (event.eventId in rejectedEventIds) {
                        continue
                    }
                    if (!uploader.upload(event)) {
                        logger.w("Uploader rejected ${event.module}/${event.name}")
                        // 上传被拒绝计入丢弃
                        selfMonitor?.recordDrop(event.priority, SdkDropReason.UPLOADER_REJECTED)
                    }
                }
                // 记录整批处理延迟
                selfMonitor?.recordUploadLatency(ApmClock.elapsedMillisSince(startTime))
            }
        } catch (error: Exception) {
            logger.e("Failed to dispatch batch of ${toPersist.size} events", error)
            Apm.recordInternalError(ERROR_PERSIST_BATCH, error)
            // 异常整批计入丢弃
            for (event in toPersist) {
                selfMonitor?.recordDrop(event.priority, SdkDropReason.STORAGE_FAILURE)
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
        val dynamicLimit = dynamicEventPolicy.rateLimitFor(
            event = event,
            defaultEventsPerWindow = rateLimiter.maxEventsPerWindow(),
            defaultWindowMs = rateLimiter.windowMs()
        )
        if (rateLimiter.tryAcquire(key, dynamicLimit.eventsPerWindow, dynamicLimit.windowMs)) {
            return true
        }
        logger.d("Rate limited: $key")
        // 记录事件丢弃
        selfMonitor?.recordDrop(event.priority, SdkDropReason.RATE_LIMIT)
        return false
    }

    /** Applies deterministic dynamic sampling and records a policy drop. */
    private fun passesSampling(event: ApmEvent): Boolean {
        if (dynamicEventPolicy.shouldSample(event)) {
            return true
        }
        logger.d("Sampled out: ${event.module}/${event.name}")
        selfMonitor?.recordDrop(event.priority, SdkDropReason.DYNAMIC_SAMPLING)
        return false
    }

    /**
     * 关闭分发器，停止接受新事件。
     * 排空队列 → 刷出聚合器残留 → 关闭上传与存储。
     */
    fun shutdown() {
        shutdown = true
        shutdownPhase("aggregation executor") { aggregationExecutor?.shutdownNow() }

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
        shutdownPhase("aggregation flush") {
            aggregator?.let { agg ->
                val remaining = agg.flush()
                for (event in remaining) {
                    try {
                        val appendResult = store.appendWithResult(event)
                        recordStorageResult(appendResult)
                        if (appendResult.acceptedEventCount > 0) {
                            if (persistentUploadWorker != null) {
                                persistentUploadWorker.signal()
                            } else {
                                uploader.upload(event)
                            }
                        }
                    } catch (e: Exception) {
                        logger.e("Failed to flush aggregated event", e)
                        Apm.recordInternalError(ERROR_AGGREGATION_FLUSH, e)
                    }
                }
            }
        }

        if (persistentUploadWorker != null) {
            shutdownPhase("persistent uploader") { persistentUploadWorker.shutdown() }
        } else {
            shutdownPhase("uploader") { uploader.shutdown() }
        }
        shutdownPhase("store") { store.close() }
    }

    /**
     * Stops delivery without draining queued or aggregated telemetry, then erases local event data.
     *
     * Consent revocation deliberately differs from graceful [shutdown]: queued work is discarded,
     * aggregation residue is not flushed, the uploader is stopped before storage is cleared, and
     * the outbox is closed only after the clear attempt.
     *
     * @return counts and success state for the process-local privacy erase
     */
    internal fun shutdownForConsentRevocation(): ConsentStorageCleanupResult {
        shutdown = true
        shutdownPhase("aggregation executor") { aggregationExecutor?.shutdownNow() }
        running = false

        val discardedQueuedEvents = clearQueuedEventsForConsentRevocation()
        workerThread.interrupt()
        try {
            workerThread.join(DISPATCH_SHUTDOWN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (workerThread.isAlive) {
            logger.w("Dispatcher did not stop within ${DISPATCH_SHUTDOWN_TIMEOUT_MS}ms during consent revocation")
        }

        if (persistentUploadWorker != null) {
            shutdownPhase("persistent uploader") { persistentUploadWorker.shutdown() }
        } else {
            shutdownPhase("uploader") { uploader.shutdown() }
        }

        val storedEventCount = try {
            (store as? PendingEventStore)?.pendingCount()
        } catch (error: Exception) {
            logger.e("Failed to count pending events before consent erase", error)
            Apm.recordInternalError(ERROR_CONSENT_PENDING_COUNT, error)
            null
        }
        val storageCleared = try {
            store.clear()
            true
        } catch (error: Exception) {
            logger.e("Failed to clear event storage after consent revocation", error)
            Apm.recordInternalError(ERROR_CONSENT_STORAGE_CLEAR, error)
            false
        }
        shutdownPhase("store") { store.close() }
        return ConsentStorageCleanupResult(
            discardedQueuedEventCount = discardedQueuedEvents,
            clearedStoredEventCount = storedEventCount,
            storageCleared = storageCleared
        )
    }

    /** Removes all queued work and resets per-module occupancy under the admission lock. */
    private fun clearQueuedEventsForConsentRevocation(): Int {
        admissionLock.lock()
        return try {
            val discarded = queue.size
            val priorityCounts = queue.groupingBy(QueuedEvent::priority).eachCount()
            queue.clear()
            queuedModuleCounts.clear()
            queuedBytes = 0L
            selfMonitor?.recordDropsByPriority(
                totalCount = discarded,
                priorityCounts = priorityCounts,
                reason = SdkDropReason.CONSENT_REVOKED
            )
            discarded
        } finally {
            admissionLock.unlock()
        }
    }

    /** Executes one dispatcher shutdown phase and continues after degradation. */
    private inline fun shutdownPhase(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            logger.e("Failed to shutdown dispatcher $name", error)
            Apm.recordInternalError("$SHUTDOWN_ERROR_TAG_PREFIX${name.replace(' ', '_')}", error)
        }
    }

    /**
     * Makes storage validation rejection and capacity eviction visible to SDK health reporting.
     *
     * @param result one storage append result
     */
    private fun recordStorageResult(result: EventStoreAppendResult) {
        if (result.rejectedEvents.isNotEmpty()) {
            logger.w("Storage rejected ${result.rejectedEvents.size} event payloads")
            for (event in result.rejectedEvents) {
                selfMonitor?.recordDrop(event.priority, SdkDropReason.STORAGE_PAYLOAD_REJECTED)
            }
            // The storage isolated bad input instead of throwing away the rest of the batch.
            Apm.recordInternalError(
                ERROR_STORAGE_PAYLOAD_REJECTED,
                IllegalArgumentException("Storage rejected ${result.rejectedEvents.size} event payloads")
            )
        }
        if (result.capacityEvictedEventCount > 0) {
            logger.w("Storage capacity evicted ${result.capacityEvictedEventCount} events")
            selfMonitor?.recordDropsByPriority(
                totalCount = result.capacityEvictedEventCount,
                priorityCounts = result.capacityEvictedPriorityCounts,
                reason = SdkDropReason.STORAGE_CAPACITY_EVICTED
            )
        }
    }

    companion object {
        /** 分发线程名，便于日志和性能分析定位。 */
        private const val THREAD_NAME = "apm-dispatcher"

        /** Default durable upload batch size. */
        private const val DEFAULT_UPLOAD_BATCH_SIZE = 20

        /** Default row ownership duration for one transport attempt. */
        private const val DEFAULT_UPLOAD_LEASE_DURATION_MS = 120_000L

        /** Lower bound that prevents immediately expired ownership. */
        private const val MIN_UPLOAD_LEASE_DURATION_MS = 1L

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

        /** Default dispatcher retained-byte budget: 8 MiB. */
        private const val DEFAULT_MAX_QUEUED_BYTES = 8L * 1024L * 1024L

        /** Lowest effective queue-byte budget after runtime clamping. */
        private const val MIN_QUEUED_BYTES = 1L

        /** Conservative weight for internal lazy factories without captured payload metadata. */
        private const val DEFAULT_LAZY_EVENT_ESTIMATE_BYTES = 1_024L

        /** Lowest reservation applied to an explicitly supplied lazy-event estimate. */
        private const val MIN_EVENT_ESTIMATE_BYTES = 256L

        /** Default queue pressure percentage that activates per-module isolation. */
        private const val DEFAULT_MODULE_ISOLATION_HIGH_WATERMARK_PERCENT = 75

        /** Default queue-capacity share for one NORMAL/LOW module under pressure. */
        private const val DEFAULT_MAX_MODULE_QUEUE_SHARE_PERCENT = 50

        /** Lowest accepted percentage after runtime validation. */
        private const val MIN_PERCENT = 1

        /** Highest accepted percentage after runtime validation. */
        private const val MAX_PERCENT = 100

        /** Offset used to round a positive integer percentage upward. */
        private const val PERCENT_ROUNDING_OFFSET = 99L

        /** Stable bucket for a missing or blank source module. */
        private const val UNKNOWN_SOURCE_MODULE = "unknown"

        /** Internal-error tag for one failed queued event transformation. */
        private const val ERROR_PROCESS_EVENT = "dispatcher_process_event"

        /** Internal-error tag for a failed durable batch append. */
        private const val ERROR_PERSIST_BATCH = "dispatcher_persist_batch"

        /** Internal-error tag for isolated invalid or oversized durable payloads. */
        private const val ERROR_STORAGE_PAYLOAD_REJECTED = "storage_payload_rejected"

        /** Internal-error tag for a failed aggregation shutdown flush. */
        private const val ERROR_AGGREGATION_FLUSH = "dispatcher_aggregation_flush"

        /** Internal-error tag for a failed periodic aggregation maintenance tick. */
        private const val ERROR_AGGREGATION_MAINTENANCE = "dispatcher_aggregation_maintenance"

        /** Internal-error tag for a custom dynamic policy provider failure. */
        private const val ERROR_DYNAMIC_EVENT_POLICY = "dispatcher_dynamic_event_policy"

        /** Internal-error tag for a failed pending-row count during consent erase. */
        private const val ERROR_CONSENT_PENDING_COUNT = "consent_pending_count"

        /** Internal-error tag for a failed event-store clear during consent erase. */
        private const val ERROR_CONSENT_STORAGE_CLEAR = "consent_storage_clear"

        /** worker 单轮最多批量取出的事件数。 */
        private const val MAX_BATCH_DRAIN = 32

        /** worker 空闲时的队列轮询超时（毫秒），决定关闭响应延迟。 */
        private const val WORKER_POLL_MS = 100L

        /** Stable internal-error prefix for isolated dispatcher shutdown phases. */
        private const val SHUTDOWN_ERROR_TAG_PREFIX = "dispatcher_shutdown_"

        /** Normalizes an admission module without resolving a lazy event. */
        private fun normalizedModule(module: String): String = module.ifBlank { UNKNOWN_SOURCE_MODULE }

        /** Converts a validated percentage into a positive ceiling of queue capacity. */
        private fun percentOfCapacity(capacity: Int, percent: Int): Int =
            ((capacity.toLong() * percent + PERCENT_ROUNDING_OFFSET) / MAX_PERCENT)
                .toInt()
                .coerceAtLeast(1)
    }
}
