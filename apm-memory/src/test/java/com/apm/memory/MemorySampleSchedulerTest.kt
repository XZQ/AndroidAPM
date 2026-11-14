package com.apm.memory

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemorySampleScheduler 行为测试。
 *
 * 验证首次采样立即触发、reschedule 取代旧周期任务、stop 取消后续采样，
 * 以及 stop 后重调度被静默拒绝（RejectedExecutionException 不外抛）。
 * 使用真实单线程定时执行器与宽裕的静默窗口，保证结果确定性。
 */
class MemorySampleSchedulerTest {

    /** 启动后初始延迟为 0，第一次采样必须立即发生。 */
    @Test
    fun `start fires the first sample immediately`() {
        val firstFire = CountDownLatch(1)
        val scheduler = MemorySampleScheduler { firstFire.countDown() }
        try {
            // scheduleWithFixedDelay 的初始延迟为 0，无需等待一个完整周期
            scheduler.start(FIRST_INTERVAL_MS)
            assertTrue(firstFire.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            scheduler.stop()
        }
    }

    /** reschedule 取消旧周期任务并以新间隔重新调度。 */
    @Test
    fun `reschedule replaces the pending fixed delay`() {
        val fires = AtomicInteger(0)
        val firstFire = CountDownLatch(1)
        val scheduler = MemorySampleScheduler {
            fires.incrementAndGet()
            firstFire.countDown()
        }
        try {
            // 先用短周期触发首次采样，再切换到长周期
            scheduler.start(FAST_INTERVAL_MS)
            assertTrue(firstFire.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val afterFirst = fires.get()
            // 新任务初始延迟同样是 0：立即再触发一次
            scheduler.reschedule(SECOND_INTERVAL_MS)
            Thread.sleep(QUIET_WINDOW_MS)
            // 旧 50ms 周期若未被取消会持续触发；新 2s 周期在窗口内不再触发
            assertEquals(afterFirst + 1, fires.get())
        } finally {
            scheduler.stop()
        }
    }

    /** stop 取消尚未执行的周期采样。 */
    @Test
    fun `stop cancels pending samples`() {
        val fires = AtomicInteger(0)
        val firstFire = CountDownLatch(1)
        val scheduler = MemorySampleScheduler {
            fires.incrementAndGet()
            firstFire.countDown()
        }
        try {
            scheduler.start(FAST_INTERVAL_MS)
            assertTrue(firstFire.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val afterFirst = fires.get()
            // 停止后下一个 50ms 周期点不应再触发
            scheduler.stop()
            Thread.sleep(STOP_QUIET_WINDOW_MS)
            assertEquals(afterFirst, fires.get())
        } finally {
            scheduler.stop()
        }
    }

    /** stop 后的重调度被静默拒绝，不向宿主线程抛异常。 */
    @Test
    fun `reschedule after stop is silently rejected`() {
        val fires = AtomicInteger(0)
        val firstFire = CountDownLatch(1)
        val scheduler = MemorySampleScheduler {
            fires.incrementAndGet()
            firstFire.countDown()
        }
        // 不使用 try/finally：本用例验证的就是 stop 之后的重调度路径
        scheduler.start(FAST_INTERVAL_MS)
        assertTrue(firstFire.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val afterFirst = fires.get()
        scheduler.stop()
        // 执行器已关闭，RejectedExecutionException 必须被吞掉
        scheduler.reschedule(FAST_INTERVAL_MS)
        Thread.sleep(STOP_QUIET_WINDOW_MS)
        assertEquals(afterFirst, fires.get())
    }

    companion object {
        /** 首轮短采样周期。 */
        private const val FIRST_INTERVAL_MS = 50L

        /** 快速触发周期，用于验证取消语义。 */
        private const val FAST_INTERVAL_MS = 50L

        /** 重调度后的慢周期。 */
        private const val SECOND_INTERVAL_MS = 2_000L

        /** 重调度后的静默观察窗口。 */
        private const val QUIET_WINDOW_MS = 600L

        /** stop 之后的静默观察窗口。 */
        private const val STOP_QUIET_WINDOW_MS = 400L

        /** 首次触发等待上限。 */
        private const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
