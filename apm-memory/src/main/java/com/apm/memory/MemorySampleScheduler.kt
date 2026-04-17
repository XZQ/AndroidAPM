package com.apm.memory

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 内存采样调度器。
 * 使用单线程定时执行器，按前后台不同间隔周期触发采样。
 * 线程优先级设为 MIN_PRIORITY，减少对主线程的影响。
 */
internal class MemorySampleScheduler(
    /** 采样动作回调，由 MemoryModule 注入。 */
    private val sampleAction: () -> Unit
) {
    /** 单线程定时执行器。 */
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    /** 当前定时任务的 Future，用于取消和重新调度。 */
    private var future: ScheduledFuture<*>? = null

    /**
     * 启动定时采样。
     * @param intervalMs 初始采样间隔（毫秒）
     */
    fun start(intervalMs: Long) {
        reschedule(intervalMs)
    }

    /**
     * 重新调度采样间隔。
     * 取消旧任务，以新间隔提交新任务。
     *
     * @param intervalMs 新的采样间隔（毫秒）
     */
    fun reschedule(intervalMs: Long) {
        // 取消之前的定时任务
        future?.cancel(false)
        future = executor.scheduleWithFixedDelay(
            { sampleAction() },
            INITIAL_DELAY_MS,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    /** 停止调度器，取消任务并关闭执行器。 */
    fun stop() {
        future?.cancel(false)
        executor.shutdown()
    }

    companion object {
        /** 工作线程名。 */
        private const val THREAD_NAME = "memory-sampler"
        /** 初始延迟：0，启动后立即执行第一次采样。 */
        private const val INITIAL_DELAY_MS = 0L
    }
}
