package com.apm.core.throttle

import com.apm.core.ApmClock

/**
 * 令牌桶限流器。按 key 分桶，每个桶独立计数。
 * 用于控制同一事件类型在时间窗口内的最大发射数。
 *
 * 桶集合使用 access-order LinkedHashMap + LRU 上限（[maxBuckets]），
 * 防止高基数事件名导致桶无限增长泄漏内存；
 * 被逐出的冷门 key 再次出现时会重新创建满额桶（对低频事件无实际影响）。
 *
 * 线程安全：桶集合通过 synchronized 保护（tryAcquire 已迁移到
 * dispatcher worker 线程执行，锁竞争极低）；桶内补充与扣减在同一临界区原子完成。
 */
class RateLimiter(
    /** 每个窗口允许通过的默认最大事件数。 */
    private val maxEventsPerWindow: Int,
    /** 默认补充窗口时长（毫秒）。 */
    private val windowMs: Long,
    /** 最大保留桶数上限。 */
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS
) {
    /** 至少保留 1 个桶，避免非正配置导致刚创建的桶在入表瞬间被逐出而使限流失效。 */
    private val effectiveMaxBuckets = maxBuckets.coerceAtLeast(1)

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
            return size > effectiveMaxBuckets
        }
    }

    /**
     * 尝试获取一个令牌。
     * @param key 分桶键，通常为 "module/eventName"
     * @return true 表示通过，false 表示被限流
     */
    fun tryAcquire(
        key: String,
        eventsPerWindow: Int = maxEventsPerWindow,
        refillWindowMs: Long = windowMs
    ): Boolean {
        // 同步保护 LinkedHashMap 的访问顺序调整与 LRU 逐出
        val bucket = synchronized(buckets) {
            val existing = buckets[key]
            if (existing != null && existing.matches(eventsPerWindow, refillWindowMs)) {
                existing
            } else {
                // A verified dynamic policy change replaces only the affected bucket.
                TokenBucket(eventsPerWindow, refillWindowMs).also { replacement ->
                    buckets[key] = replacement
                }
            }
        }
        return bucket.tryAcquire()
    }

    /**
     * 当前桶数量（供测试与自监控观测）。
     *
     * @return 活跃桶数
     */
    fun bucketCount(): Int = synchronized(buckets) { buckets.size }

    /** Returns the application-bundled default capacity. */
    internal fun maxEventsPerWindow(): Int = maxEventsPerWindow

    /** Returns the application-bundled default refill window. */
    internal fun windowMs(): Long = windowMs

    /** 清除所有桶，重置限流状态。 */
    fun reset() {
        synchronized(buckets) { buckets.clear() }
    }

    /**
     * 单个令牌桶。窗口到期补充与扣减在同一对象监视器内原子完成，
     * 避免并发线程在时间戳推进与令牌写回之间误判为空桶。
     */
    private class TokenBucket(
        /** 桶容量上限。 */
        private val capacity: Int,
        /** 补充窗口时长（毫秒）。 */
        private val windowMs: Long
    ) {
        /** 当前可用令牌数。 */
        private var tokens = capacity.toLong()
        /** 上一次补充令牌的时间戳。 */
        private var lastRefill = ApmClock.monotonicTimeMillis()

        /** Returns whether this bucket already represents the effective dynamic policy. */
        fun matches(capacity: Int, windowMs: Long): Boolean {
            return this.capacity == capacity && this.windowMs == windowMs
        }

        /**
         * 尝试消耗一个令牌；窗口到期时先补充再扣减。
         * @return true 获取成功，false 令牌不足
         */
        @Synchronized
        fun tryAcquire(): Boolean {
            val now = ApmClock.monotonicTimeMillis()
            if (now - lastRefill >= windowMs) {
                // 新窗口到达时原子重置水位与可用令牌
                lastRefill = now
                tokens = capacity.toLong()
            }
            if (tokens <= 0L) {
                return false
            }
            tokens -= 1L
            return true
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
