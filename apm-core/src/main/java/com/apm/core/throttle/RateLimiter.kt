package com.apm.core.throttle

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 令牌桶限流器。按 key 分桶，每个桶独立计数。
 * 用于控制同一事件类型在时间窗口内的最大发射数。
 *
 * 线程安全：每个桶使用 AtomicLong + CAS 实现无锁并发。
 */
class RateLimiter(
    /** 每个时间窗口内允许的最大事件数。 */
    private val maxEventsPerWindow: Int,
    /** 限流窗口时长（毫秒）。 */
    private val windowMs: Long
) {
    /** key → 令牌桶映射。懒创建，用完不清除（避免并发删除问题）。 */
    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    /**
     * 尝试获取一个令牌。
     * @param key 分桶键，通常为 "module/eventName"
     * @return true 表示通过，false 表示被限流
     */
    fun tryAcquire(key: String): Boolean {
        val bucket = buckets.computeIfAbsent(key) {
            TokenBucket(maxEventsPerWindow, windowMs)
        }
        return bucket.tryAcquire()
    }

    /** 清除所有桶，重置限流状态。 */
    fun reset() {
        buckets.clear()
    }

    /**
     * 单个令牌桶。使用 CAS 无锁实现。
     * 窗口到期时自动补充令牌到满。
     */
    private class TokenBucket(
        /** 桶容量 = 时间窗口内允许的最大事件数。 */
        private val capacity: Int,
        /** 窗口时长（毫秒）。 */
        private val windowMs: Long
    ) {
        /** 当前可用令牌数。 */
        private val tokens = AtomicLong(capacity.toLong())
        /** 上一次补充令牌的时间戳。 */
        private val lastRefill = AtomicLong(System.currentTimeMillis())

        /**
         * 尝试消耗一个令牌。CAS 循环保证无锁并发安全。
         * @return true 获取成功，false 令牌不足
         */
        fun tryAcquire(): Boolean {
            refill()
            while (true) {
                val current = tokens.get()
                if (current <= 0) return false
                if (tokens.compareAndSet(current, current - 1)) return true
            }
        }

        /**
         * 补充令牌。窗口到期时重置为满。
         * 使用 CAS 保证只有一个线程执行补充。
         */
        private fun refill() {
            val now = System.currentTimeMillis()
            val last = lastRefill.get()
            if (now - last < windowMs) return
            // 只有 CAS 成功的线程才执行补充
            if (lastRefill.compareAndSet(last, now)) {
                tokens.set(capacity.toLong())
            }
        }
    }
}
