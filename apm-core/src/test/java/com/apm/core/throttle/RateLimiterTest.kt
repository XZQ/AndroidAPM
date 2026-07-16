package com.apm.core.throttle

import org.junit.Assert.*
import org.junit.Test

/**
 * RateLimiter 令牌桶限流器测试。
 * 验证限流准确性、窗口重置、多 key 隔离。
 */
class RateLimiterTest {

    /** Reconfiguring a bucket replaces its capacity without retaining stale exhaustion state. */
    @Test
    fun `dynamic policy change replaces affected bucket`() {
        val limiter = RateLimiter(maxEventsPerWindow = 1, windowMs = 60_000L)

        assertTrue(limiter.tryAcquire("network/request"))
        assertFalse(limiter.tryAcquire("network/request"))
        assertTrue(limiter.tryAcquire("network/request", eventsPerWindow = 2, refillWindowMs = 5_000L))
        assertTrue(limiter.tryAcquire("network/request", eventsPerWindow = 2, refillWindowMs = 5_000L))
        assertFalse(limiter.tryAcquire("network/request", eventsPerWindow = 2, refillWindowMs = 5_000L))
    }

    /** 窗口内未超出配额时应全部通过。 */
    @Test
    fun `allows requests within limit`() {
        val limiter = RateLimiter(maxEventsPerWindow = 5, windowMs = 60_000L)

        for (i in 1..5) {
            assertTrue("Request $i should be allowed", limiter.tryAcquire("test"))
        }
    }

    /** 超出配额后应被限流。 */
    @Test
    fun `rejects requests exceeding limit`() {
        val limiter = RateLimiter(maxEventsPerWindow = 3, windowMs = 60_000L)

        assertTrue(limiter.tryAcquire("test"))
        assertTrue(limiter.tryAcquire("test"))
        assertTrue(limiter.tryAcquire("test"))
        assertFalse("4th request should be rejected", limiter.tryAcquire("test"))
    }

    /** 不同 key 应独立计数，互不影响。 */
    @Test
    fun `different keys have independent buckets`() {
        val limiter = RateLimiter(maxEventsPerWindow = 2, windowMs = 60_000L)

        assertTrue(limiter.tryAcquire("keyA"))
        assertTrue(limiter.tryAcquire("keyA"))
        assertFalse("keyA exhausted", limiter.tryAcquire("keyA"))

        assertTrue("keyB should still have tokens", limiter.tryAcquire("keyB"))
    }

    /** reset 后所有桶应清空，可重新通过。 */
    @Test
    fun `reset clears all buckets`() {
        val limiter = RateLimiter(maxEventsPerWindow = 1, windowMs = 60_000L)

        assertTrue(limiter.tryAcquire("test"))
        assertFalse("Should be rate limited", limiter.tryAcquire("test"))

        limiter.reset()

        assertTrue("Should pass after reset", limiter.tryAcquire("test"))
    }

    /** 配额为 0 的 limiter 应立即拒绝所有请求。 */
    @Test
    fun `zero limit rejects immediately`() {
        val limiter = RateLimiter(maxEventsPerWindow = 0, windowMs = 60_000L)
        assertFalse(limiter.tryAcquire("test"))
    }

    /** 超过桶数量上限时按 LRU 逐出，桶数不超过上限。 */
    @Test
    fun `bucket count is capped with lru eviction`() {
        val maxBuckets = 8
        val limiter = RateLimiter(
            maxEventsPerWindow = 1,
            windowMs = 60_000L,
            maxBuckets = maxBuckets
        )

        // 插入 3 倍于上限的不同 key
        for (i in 0 until maxBuckets * 3) {
            limiter.tryAcquire("module/event_$i")
        }

        assertTrue("Bucket count should be capped", limiter.bucketCount() <= maxBuckets)
    }

    /** LRU 逐出后，存活的热 key 仍维持正确的限流状态。 */
    @Test
    fun `surviving hot key keeps limiting after eviction`() {
        val maxBuckets = 4
        val limiter = RateLimiter(
            maxEventsPerWindow = 1,
            windowMs = 60_000L,
            maxBuckets = maxBuckets
        )

        // 热 key 消耗掉唯一令牌
        assertTrue(limiter.tryAcquire("hot"))
        assertFalse(limiter.tryAcquire("hot"))

        // 插入少量冷 key（不足以逐出刚访问过的热 key）
        for (i in 0 until maxBuckets - 1) {
            limiter.tryAcquire("cold_$i")
        }

        // 热 key 的限流状态应保持
        assertFalse("Hot key should stay limited", limiter.tryAcquire("hot"))
    }
}
