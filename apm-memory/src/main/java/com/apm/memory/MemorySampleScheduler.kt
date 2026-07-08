package com.apm.memory

import com.apm.core.ApmExecutors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 内存采样调度器。
 * 使用 [ApmExecutors] 单线程定时执行器（默认最低优先级），按前后台不同间隔周期触发采样。
 *
 * @param sampleAction 每次采样周期触发的回调
 */
internal class MemorySampleScheduler(private val sampleAction: () -> Unit) {
    /** 单线程定时执行器：周期性内存轮询非时间敏感，经 [ApmExecutors] 以最低优先级后台执行。 */
    private val executor: ScheduledExecutorService = ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME)

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
