package com.apm.core

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import com.apm.storage.EventStore
import com.apm.core.throttle.RateLimiter
import com.apm.uploader.ApmUploader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * APM 事件分发器。
 * 负责限流检查 → 本地存储 → 上传的三阶段流水线。
 * 所有事件处理在独立线程 "apm-dispatcher" 上执行，不阻塞调用方。
 */
internal class ApmDispatcher(
    /** 本地事件存储。 */
    private val store: EventStore,
    /** 上传通道。 */
    private val uploader: ApmUploader,
    /** 日志接口。 */
    private val logger: ApmLogger,
    /** 可选限流器，null 表示不限流。 */
    private val rateLimiter: RateLimiter? = null
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
     * 1. 限流检查（ERROR/FATAL 级别跳过限流，保证关键事件不丢失）
     * 2. 提交到单线程执行器
     * 3. 在执行器中：存储 → 上传
     */
    fun dispatch(event: ApmEvent) {
        // stop 之后直接拒绝新事件，避免关闭期间出现尾部写入。
        if (shutdown) {
            logger.d("Dispatcher already shutdown, drop ${event.module}/${event.name}")
            return
        }

        // ERROR/FATAL 事件绕过限流，确保崩溃/ANR 等关键事件必达
        if (rateLimiter != null && event.severity != ApmSeverity.ERROR && event.severity != ApmSeverity.FATAL) {
            val key = "${event.module}/${event.name}"
            if (!rateLimiter.tryAcquire(key)) {
                logger.d("Rate limited: $key")
                return
            }
        }

        // 异步执行存储+上传，不阻塞调用线程
        try {
            executor.execute {
                try {
                    store.append(event)
                    val uploadAccepted = uploader.upload(event)
                    if (!uploadAccepted) {
                        logger.w("Uploader rejected ${event.module}/${event.name}")
                    }
                } catch (throwable: Throwable) {
                    logger.e("Failed to dispatch ${event.module}/${event.name}", throwable)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            logger.d("Dispatcher rejected ${event.module}/${event.name} after shutdown")
        }
    }

    /** 关闭分发器，停止接受新事件。 */
    fun shutdown() {
        shutdown = true
        executor.shutdownNow()
    }

    companion object {
        /** 分发线程名，便于日志和性能分析定位。 */
        private const val THREAD_NAME = "apm-dispatcher"
    }
}
