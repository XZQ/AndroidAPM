package com.apm.storage

import com.apm.model.ApmEvent

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
 * One event retained by a durable upload outbox.
 *
 * @property id storage row identifier
 * @property event decoded event
 * @property retryCount number of failed upload cycles
 */
data class PendingEvent(
    val id: Long,
    val event: ApmEvent,
    val retryCount: Int
)

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
}
