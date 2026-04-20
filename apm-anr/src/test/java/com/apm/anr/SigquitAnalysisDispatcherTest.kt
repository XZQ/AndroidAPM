package com.apm.anr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SigquitAnalysisDispatcher 调度测试。
 * 验证 SIGQUIT 检测不依赖主线程回调，且能拦截重复调度。
 */
class SigquitAnalysisDispatcherTest {

    /** 首次 SIGQUIT 应立即提交到分析调度器。 */
    @Test
    fun `first sigquit schedules analysis immediately`() {
        val scheduledTasks = mutableListOf<() -> Unit>()
        val dispatcher = SigquitAnalysisDispatcher { task ->
            // 收集调度任务，验证信号线程只负责转发。
            scheduledTasks += task
            true
        }
        val detected = AtomicBoolean(false)
        val anrDetected = AtomicBoolean(false)

        val accepted = dispatcher.dispatch(
            running = true,
            anrDetected = anrDetected,
            analysis = { detected.set(true) }
        )

        assertTrue(accepted)
        assertEquals(1, scheduledTasks.size)
        assertFalse(detected.get())

        scheduledTasks.single().invoke()

        assertTrue(detected.get())
        assertTrue(anrDetected.get())
    }

    /** 已经标记过 ANR 或模块已停止时不应重复调度。 */
    @Test
    fun `duplicate or stopped sigquit is ignored`() {
        val scheduledTasks = mutableListOf<() -> Unit>()
        val dispatcher = SigquitAnalysisDispatcher { task ->
            scheduledTasks += task
            true
        }
        val anrDetected = AtomicBoolean(true)

        val duplicateAccepted = dispatcher.dispatch(
            running = true,
            anrDetected = anrDetected,
            analysis = {}
        )
        val stoppedAccepted = dispatcher.dispatch(
            running = false,
            anrDetected = AtomicBoolean(false),
            analysis = {}
        )

        assertFalse(duplicateAccepted)
        assertFalse(stoppedAccepted)
        assertTrue(scheduledTasks.isEmpty())
    }
}
