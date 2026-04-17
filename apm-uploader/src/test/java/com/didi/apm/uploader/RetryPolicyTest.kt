package com.didi.apm.uploader

import org.junit.Assert.*
import org.junit.Test

/**
 * RetryPolicy 指数退避策略测试。
 * 验证延迟计算的正确性和边界条件。
 */
class RetryPolicyTest {

    /** 默认参数构建 RetryPolicy，验证默认值。 */
    @Test
    fun `default policy has expected values`() {
        val policy = RetryPolicy()
        assertEquals(3, policy.maxRetries)
        assertEquals(1000L, policy.baseDelayMs)
        assertEquals(30_000L, policy.maxDelayMs)
        assertEquals(2.0f, policy.backoffMultiplier, 0.01f)
    }

    /** attempt <= 0 时延迟应为 0。 */
    @Test
    fun `delayForAttempt returns zero for non-positive attempt`() {
        val policy = RetryPolicy()
        assertEquals(0L, policy.delayForAttempt(0))
        assertEquals(0L, policy.delayForAttempt(-1))
    }

    /** 第一次重试延迟 = baseDelay * multiplier^1 = 1000 * 2 = 2000。 */
    @Test
    fun `delayForAttempt calculates first retry correctly`() {
        val policy = RetryPolicy(baseDelayMs = 1000L, backoffMultiplier = 2.0f)
        assertEquals(2000L, policy.delayForAttempt(1))
    }

    /** 第二次重试延迟 = baseDelay * multiplier^2 = 1000 * 4 = 4000。 */
    @Test
    fun `delayForAttempt calculates second retry correctly`() {
        val policy = RetryPolicy(baseDelayMs = 1000L, backoffMultiplier = 2.0f)
        assertEquals(4000L, policy.delayForAttempt(2))
    }

    /** 第三次重试延迟 = baseDelay * multiplier^3 = 1000 * 8 = 8000。 */
    @Test
    fun `delayForAttempt calculates third retry correctly`() {
        val policy = RetryPolicy(baseDelayMs = 1000L, backoffMultiplier = 2.0f)
        assertEquals(8000L, policy.delayForAttempt(3))
    }

    /** 延迟不应超过 maxDelayMs。 */
    @Test
    fun `delayForAttempt is capped by maxDelayMs`() {
        val policy = RetryPolicy(baseDelayMs = 1000L, backoffMultiplier = 2.0f, maxDelayMs = 5000L)
        // 第 3 次: 8000 > 5000，应被截断
        assertEquals(5000L, policy.delayForAttempt(3))
        // 第 10 次: 更大，仍被截断
        assertEquals(5000L, policy.delayForAttempt(10))
    }

    /** 自定义 multiplier 时计算正确。 */
    @Test
    fun `delayForAttempt with custom multiplier`() {
        val policy = RetryPolicy(baseDelayMs = 500L, backoffMultiplier = 1.5f)
        // attempt 1: 500 * 1.5 = 750
        assertEquals(750L, policy.delayForAttempt(1))
        // attempt 2: 500 * 1.5^2 = 500 * 2.25 = 1125
        assertEquals(1125L, policy.delayForAttempt(2))
    }

    /** 延迟随 attempt 递增。 */
    @Test
    fun `delay increases with each attempt`() {
        val policy = RetryPolicy(baseDelayMs = 1000L, backoffMultiplier = 2.0f, maxDelayMs = 60_000L)
        var prev = 0L
        for (attempt in 1..5) {
            val delay = policy.delayForAttempt(attempt)
            assertTrue("Delay should increase at attempt $attempt", delay > prev)
            prev = delay
        }
    }
}
