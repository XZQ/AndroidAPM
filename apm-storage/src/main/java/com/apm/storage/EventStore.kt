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
}
