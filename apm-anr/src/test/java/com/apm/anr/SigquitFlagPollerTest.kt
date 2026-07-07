package com.apm.anr

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SigquitFlagPoller] 轮询消费逻辑测试。
 * 验证 watchdog 循环消费 native SIGQUIT 标志的纯逻辑，无需加载 JNI 库。
 */
class SigquitFlagPollerTest {

    /** 有未消费信号时触发回调并返回 true。 */
    @Test
    fun `poll triggers callback when signal pending`() {
        val callbackCount = AtomicInteger(0)
        val poller = SigquitFlagPoller(
            source = { SIGNAL_TIMESTAMP_MS },
            onSigquit = { callbackCount.incrementAndGet() }
        )

        assertTrue(poller.pollOnce())
        assertEquals(1, callbackCount.get())
    }

    /** 无信号（时间戳为 0）时不触发回调。 */
    @Test
    fun `poll is silent when no signal pending`() {
        val callbackCount = AtomicInteger(0)
        val poller = SigquitFlagPoller(
            source = { NO_SIGNAL },
            onSigquit = { callbackCount.incrementAndGet() }
        )

        assertFalse(poller.pollOnce())
        assertEquals(0, callbackCount.get())
    }

    /** 信号被消费后（源清零）后续轮询不再重复触发。 */
    @Test
    fun `consumed signal does not retrigger`() {
        val pending = AtomicLong(SIGNAL_TIMESTAMP_MS)
        val callbackCount = AtomicInteger(0)
        val poller = SigquitFlagPoller(
            // 模拟 native 原子交换：读取后清零
            source = { pending.getAndSet(NO_SIGNAL) },
            onSigquit = { callbackCount.incrementAndGet() }
        )

        assertTrue(poller.pollOnce())
        assertFalse(poller.pollOnce())
        assertFalse(poller.pollOnce())
        assertEquals(1, callbackCount.get())
    }

    companion object {
        /** 模拟的信号时间戳。 */
        private const val SIGNAL_TIMESTAMP_MS = 1_700_000_000_000L

        /** 无信号标志值。 */
        private const val NO_SIGNAL = 0L
    }
}
