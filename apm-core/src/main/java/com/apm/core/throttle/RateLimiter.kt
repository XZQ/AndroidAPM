package com.apm.core.throttle

import java.util.concurrent.atomic.AtomicLong

/**
 * 令牌桶限流器。按 key 分桶，每个桶独立计数。
 * 用于控制同一事件类型在时间窗口内的最大发射数。
 *
 * 桶集合使用 access-order LinkedHashMap + LRU 上限（[maxBuckets]），
 * 防止高基数事件名导致桶无限增长泄漏内存；
 * 被逐出的冷门 key 再次出现时会重新创建满额桶（对低频事件无实际影响）。
 *
 * 线程安全：桶集合通过 synchronized 保护（tryAcquire 已迁移到
 * dispatcher worker 线程执行，锁竞争极低）；桶内计数使用 AtomicLong CAS。
 */
class RateLimiter(private val maxEventsPerWindow: Int, private val windowMs: Long, private val maxBuckets: Int = DEFAULT_MAX_BUCKETS) {
    /**
     * key → 令牌桶映射。
     * access-order 模式：每次访问将条目移至末尾，头部即最久未使用；
     * removeEldestEntry 在超出容量时自动逐出头部条目。
     */
    private val buckets = object : LinkedHashMap<String, TokenBucket>(
        INITIAL_CAPACITY, LOAD_FACTOR, true
    ) {
        /** 超出桶数量上限时逐出最久未使用的桶。 */
        override fun removeEldestEntry(eldest: Map.Entry<String, TokenBucket>): Boolean {
            return size > maxBuckets
        }
    }

    /**
     * 尝试获取一个令牌。
     * @param key 分桶键，通常为 "module/eventName"
     * @return true 表示通过，false 表示被限流
     */
    fun tryAcquire(key: String): Boolean {
        // 同步保护 LinkedHashMap 的访问顺序调整与 LRU 逐出
        val bucket = synchronized(buckets) {
            buckets.getOrPut(key) { TokenBucket(maxEventsPerWindow, windowMs) }
        }
        return bucket.tryAcquire()
    }

    /**
     * 当前桶数量（供测试与自监控观测）。
     *
     * @return 活跃桶数
     */
    fun bucketCount(): Int = synchronized(buckets) { buckets.size }

    /** 清除所有桶，重置限流状态。 */
    fun reset() {
        synchronized(buckets) { buckets.clear() }
    }

    /**
     * 单个令牌桶。使用 CAS 无锁实现。
     * 窗口到期时自动补充令牌到满。
     */
    private class TokenBucket(private val capacity: Int, private val windowMs: Long) {
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
                if (current <= 0) {
                    return false
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true
                }
            }
        }

        /**
         * 补充令牌。窗口到期时重置为满。
         * 使用 CAS 保证只有一个线程执行补充。
         */
        private fun refill() {
            val now = System.currentTimeMillis()
            val last = lastRefill.get()
            if (now - last < windowMs) {
                return
            }
            // 只有 CAS 成功的线程才执行补充
            if (lastRefill.compareAndSet(last, now)) {
                tokens.set(capacity.toLong())
            }
        }
    }

    companion object {
        /** 默认桶数量上限。 */
        private const val DEFAULT_MAX_BUCKETS = 256

        /** LinkedHashMap 初始容量。 */
        private const val INITIAL_CAPACITY = 16

        /** LinkedHashMap 负载因子。 */
        private const val LOAD_FACTOR = 0.75f
    }
}
