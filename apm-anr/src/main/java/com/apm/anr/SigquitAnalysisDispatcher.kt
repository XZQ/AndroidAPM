package com.apm.anr

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SIGQUIT 分析任务调度器。
 * 负责在收到信号后立即转发到独立分析线程，并拦截重复调度。
 */
internal class SigquitAnalysisDispatcher(
    /** 分析任务调度函数，返回 true 表示任务已被接管。 */
    private val schedule: (() -> Unit) -> Boolean
) {

    /**
     * 尝试调度一次 SIGQUIT 分析任务。
     *
     * @param running 模块当前是否仍在运行
     * @param anrDetected ANR 已触发标记
     * @param analysis 实际分析任务
     * @return true 表示本次信号被成功调度
     */
    fun dispatch(
        running: Boolean,
        anrDetected: AtomicBoolean,
        analysis: () -> Unit
    ): Boolean {
        if (!running) return false

        // 仅允许首个 SIGQUIT 进入分析队列。
        if (!anrDetected.compareAndSet(false, true)) {
            return false
        }

        val accepted = schedule(analysis)
        if (!accepted) {
            // 调度失败时回滚标记，避免后续真实信号被误丢弃。
            anrDetected.set(false)
        }
        return accepted
    }
}
