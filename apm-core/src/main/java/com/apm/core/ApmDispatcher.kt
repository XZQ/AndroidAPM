package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import com.apm.core.aggregation.EventAggregator
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.storage.EventStore
import com.apm.core.throttle.RateLimiter
import com.apm.uploader.ApmUploader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * APM 事件分发器。
 * 负责聚合 → 限流检查 → PII 脱敏 → 本地存储 → 上传的五阶段流水线。
 * 所有事件处理在独立线程 "apm-dispatcher" 上执行，不阻塞调用方。
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
    var selfMonitor: SdkSelfMonitor? = null
) {
    /** 单线程执行器，保证事件按顺序处理。 */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME)
    }

    /** 分发器是否已关闭。 */
    @Volatile
    private var shutdown = false

    /**
     * 分发一个事件。执行流程：
     * 1. 聚合检查（METRIC 进滑动窗口，ALERT 栈指纹去重）
     * 2. 限流检查（ERROR/FATAL 级别跳过限流，保证关键事件不丢失）
     * 3. 提交到单线程执行器
     * 4. 在执行器中：存储 → 上传
     */
    fun dispatch(event: ApmEvent) {
        // stop 之后直接拒绝新事件，避免关闭期间出现尾部写入。
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop ${event.module}/${event.name}")
            return
        }

        // 记录事件发射
        selfMonitor?.recordEmit()

        // Phase 8: 聚合检查 — 可能吞入事件或输出聚合结果
        val eventsToDispatch = if (aggregator != null) {
            aggregator.process(event)
        } else {
            listOf(event)
        }

        // 聚合后可能返回 0 条（被吞入）或 1 条（聚合结果/原始事件）
        for (dispatchEvent in eventsToDispatch) {
            dispatchSingle(dispatchEvent)
        }
    }

    /**
     * 分发单个事件：限流 → 脱敏 → 存储 → 上传。
     */
    private fun dispatchSingle(event: ApmEvent) {
        // ERROR/FATAL 事件绕过限流，确保崩溃/ANR 等关键事件必达
        if (rateLimiter != null && event.severity != ApmSeverity.ERROR && event.severity != ApmSeverity.FATAL) {
            val key = "${event.module}/${event.name}"
            if (!rateLimiter.tryAcquire(key)) {
                logger.d("Rate limited: $key")
                // 记录事件丢弃
                selfMonitor?.recordDrop(event.priority)
                return
            }
        }

        // 异步执行脱敏+存储+上传，不阻塞调用线程
        try {
            executor.execute {
                try {
                    val startTime = System.currentTimeMillis()

                    // PII 脱敏：在存储和上传前对文本字段执行脱敏
                    val sanitizedEvent = piiSanitizer?.sanitize(event) ?: event
                    store.append(sanitizedEvent)
                    val uploadAccepted = uploader.upload(sanitizedEvent)

                    // 记录上传延迟
                    val latency = System.currentTimeMillis() - startTime
                    selfMonitor?.recordUploadLatency(latency)

                    if (!uploadAccepted) {
                        logger.w("Uploader rejected ${sanitizedEvent.module}/${sanitizedEvent.name}")
                        // 上传被拒绝计入丢弃
                        selfMonitor?.recordDrop(event.priority)
                    }
                } catch (throwable: Throwable) {
                    logger.e("Failed to dispatch ${event.module}/${event.name}", throwable)
                    // 异常计入丢弃
                    selfMonitor?.recordDrop(event.priority)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            logger.d("Dispatcher rejected ${event.module}/${event.name} after shutdown")
            selfMonitor?.recordDrop(event.priority)
        }
    }

    /**
     * 关闭分发器，停止接受新事件。
     * 刷出聚合器中未上报的聚合结果。
     */
    fun shutdown() {
        // 刷出聚合器的残留数据
        aggregator?.let { agg ->
            val remaining = agg.flush()
            for (event in remaining) {
                try {
                    store.append(event)
                    uploader.upload(event)
                } catch (e: Throwable) {
                    logger.e("Failed to flush aggregated event", e)
                }
            }
        }
        shutdown = true
        executor.shutdownNow()
    }

    companion object {
        /** 分发线程名，便于日志和性能分析定位。 */
        private const val THREAD_NAME = "apm-dispatcher"
    }
}
