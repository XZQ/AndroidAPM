package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority

/**
 * 事件存储接口。
 * 提供追加、读取、清除三种操作。
 * 实现类需保证线程安全。
 */
interface EventStore {
    /**
     * 追加一条事件到存储。
     * @param event 要存储的事件
     */
    fun append(event: ApmEvent)

    /**
     * 追加一条事件并返回存储层的精确接纳/淘汰结果。
     * 默认实现保持现有 [append] 语义；需要隔离坏事件或执行容量淘汰的实现应覆写。
     *
     * @param event 要存储的事件
     * @return 本次写入结果
     */
    fun appendWithResult(event: ApmEvent): EventStoreAppendResult {
        append(event)
        return EventStoreAppendResult(acceptedEventCount = 1)
    }

    /**
     * 批量追加事件。
     * 默认实现逐条调用 [append]；支持事务的实现应覆写为原子批量写入，
     * 显著降低高频事件场景的每条写入开销。
     *
     * @param events 要存储的事件列表
     */
    fun appendBatch(events: List<ApmEvent>) {
        // 默认逐条追加，保证语义等价
        for (event in events) {
            append(event)
        }
    }

    /**
     * 批量追加并返回存储层的精确接纳/淘汰结果。
     * 默认实现委托 [appendBatch]，因此自定义旧实现无需感知新结果类型。
     *
     * @param events 要存储的事件列表
     * @return 本次批量写入结果
     */
    fun appendBatchWithResult(events: List<ApmEvent>): EventStoreAppendResult {
        appendBatch(events)
        return EventStoreAppendResult(acceptedEventCount = events.size)
    }

    /**
     * 读取最近的事件。
     * @param limit 最大条数
     * @return line protocol 格式的字符串列表，最新在前
     */
    fun readRecent(limit: Int = 20): List<String>

    /** 清除所有已存储的事件。 */
    fun clear()

    /**
     * Closes storage resources.
     * Stateless implementations may keep the default no-op behavior.
     */
    fun close() = Unit
}

/**
 * 一次存储写入的结果。
 *
 * @property acceptedEventCount 被存储层接纳的输入事件数
 * @property rejectedEvents 因单事件编码或软大小限制而拒绝的原始事件
 * @property capacityEvictedEventCount 为满足行数或 payload 总量预算而淘汰的历史事件数
 * @property capacityEvictedPriorityCounts exact evictions by priority when the store can observe them
 */
data class EventStoreAppendResult(
    val acceptedEventCount: Int,
    val rejectedEvents: List<ApmEvent> = emptyList(),
    val capacityEvictedEventCount: Int = 0,
    val capacityEvictedPriorityCounts: Map<ApmPriority, Int> = emptyMap()
)

/**
 * Durable outbox rows removed because their retry or retention budget expired.
 *
 * @property prunedEventCount complete number of removed rows
 * @property priorityCounts exact removals by priority when supported by the store
 */
data class EventStorePruneResult(
    val prunedEventCount: Int,
    val priorityCounts: Map<ApmPriority, Int> = emptyMap()
)

/**
 * One event retained by a durable upload outbox.
 *
 * @property id storage row identifier
 * @property event decoded event
 * @property retryCount number of failed upload cycles
 */
data class PendingEvent(val id: Long, val event: ApmEvent, val retryCount: Int)

/**
 * Durable event store that supports acknowledged upload processing.
 */
interface PendingEventStore : EventStore {
    /**
     * Reads pending events by priority and age.
     *
     * @param limit maximum number of rows
     * @return pending rows in upload order
     */
    fun readPending(limit: Int): List<PendingEvent>

    /**
     * Atomically claims currently available rows for one upload worker.
     *
     * Implementations that do not support concurrent ownership retain the
     * legacy read behavior; durable multi-worker stores must override this
     * method and persist the lease before returning rows.
     *
     * @param ownerId stable identifier for the upload worker instance
     * @param limit maximum number of rows
     * @param nowMs current wall-clock time in milliseconds
     * @param leaseDurationMs duration after which abandoned rows are reclaimable
     * @return rows owned by [ownerId] until acknowledgement, failure, or expiry
     */
    fun claimPending(ownerId: String, limit: Int, nowMs: Long, leaseDurationMs: Long): List<PendingEvent> =
        readPending(limit)

    /**
     * Deletes rows only when they are currently owned by the caller.
     *
     * @param ownerId upload worker owner identifier
     * @param ids claimed row identifiers
     * @return acknowledged row count
     */
    fun acknowledgeClaim(ownerId: String, ids: List<Long>): Int = deletePending(ids)

    /**
     * Records a failed upload and makes the caller's rows immediately available.
     *
     * @param ownerId upload worker owner identifier
     * @param ids claimed row identifiers
     */
    fun failClaim(ownerId: String, ids: List<Long>) = markRetry(ids)

    /**
     * Releases every row currently owned by one worker.
     *
     * @param ownerId upload worker owner identifier
     * @return released row count, or zero for stores without lease support
     */
    fun releaseClaims(ownerId: String): Int = 0

    /**
     * Deletes events acknowledged by the server.
     *
     * @param ids row identifiers
     * @return deleted row count
     */
    fun deletePending(ids: List<Long>): Int

    /**
     * Increments retry counters after a failed upload cycle.
     *
     * @param ids row identifiers
     */
    fun markRetry(ids: List<Long>)

    /**
     * Returns the number of pending rows.
     *
     * @return pending row count
     */
    fun pendingCount(): Int

    /**
     * 清除重试次数耗尽或超过最大保留时长的行。
     *
     * 防止永久失败的事件无限期占据 outbox 并被反复重试。
     * 默认实现为 no-op，供不支持过期清理的实现与测试替身复用。
     *
     * @param maxRetryCount 重试次数上限（含）之上的行被清除
     * @param maxAgeMs 事件时间戳距今超过该毫秒数的行被清除
     * @return 清除的行数
     */
    fun pruneExpired(maxRetryCount: Int, maxAgeMs: Long): Int = 0

    /**
     * Removes expired rows and returns priority attribution when the implementation supports it.
     *
     * The compatibility default delegates to [pruneExpired] and reports an unattributed total.
     *
     * @param maxRetryCount retry-count removal threshold
     * @param maxAgeMs maximum retained event age
     * @return aggregate and per-priority removal result
     */
    fun pruneExpiredWithResult(maxRetryCount: Int, maxAgeMs: Long): EventStorePruneResult =
        EventStorePruneResult(prunedEventCount = pruneExpired(maxRetryCount, maxAgeMs))
}

/** Optional owner-aware permanent rejection, kept separate from successful collector ACKs. */
interface DiscardablePendingEventStore {
    /** Deletes only rejected rows still owned by the caller and returns the actual removed count. */
    fun discardClaim(ownerId: String, ids: List<Long>): Int
}
