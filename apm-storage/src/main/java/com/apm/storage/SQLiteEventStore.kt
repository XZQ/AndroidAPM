package com.apm.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.toLineProtocol
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于 SQLite 的事件存储实现。
 *
 * 替代 [FileEventStore] 的 500 行 ring buffer，提供：
 * - 持久化存储，容量 50,000 条
 * - 按优先级存储和读取（优先上传严重事件）
 * - 水位线保护：超容量时按优先级和时间淘汰低优先级旧事件
 * - WAL 模式并发读写
 * - 批量事务写入 + 缓存行数计数（避免每条 append 全表 COUNT(*)）
 *
 * 线程安全：通过 synchronized 保护数据库操作。
 *
 * @param dbHelper SQLite 数据库助手
 * @param maxEvents 最大存储事件数，超出时自动淘汰
 */
class SQLiteEventStore(private val dbHelper: EventDbHelper, private val maxEvents: Int = DEFAULT_MAX_EVENTS) : PendingEventStore {

    /**
     * 缓存的行数计数器。
     * 初始化时执行一次 COUNT(*)，之后随增删维护，
     * 每 [COUNT_RESYNC_INTERVAL] 次写入重同步一次以纠正漂移。
     */
    private val cachedRowCount = AtomicLong(UNINITIALIZED_COUNT)

    /** 距上次 COUNT(*) 重同步以来的写入条数（synchronized 保护）。 */
    private var appendsSinceResync = 0

    /**
     * 追加一条事件到 SQLite。
     * 委托批量路径，共享事务与计数维护逻辑。
     */
    @Synchronized
    override fun append(event: ApmEvent) {
        appendBatchLocked(listOf(event))
    }

    /**
     * 批量追加事件。
     * 所有插入在同一事务内提交，显著降低高频事件的每条写入开销。
     *
     * @param events 要存储的事件列表
     */
    @Synchronized
    override fun appendBatch(events: List<ApmEvent>) {
        if (events.isEmpty()) {
            return
        }
        appendBatchLocked(events)
    }

    /**
     * 批量写入实现（调用方已持有锁）。
     * 事务插入 → 维护计数 → 周期性重同步 → 水位线淘汰。
     *
     * @param events 要存储的事件列表
     */
    private fun appendBatchLocked(events: List<ApmEvent>) {
        val db = dbHelper.writableDatabase
        ensureRowCountInitialized(db)

        // 单事务批量插入：一次 fsync 落盘整批事件
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (event in events) {
                val values = ContentValues().apply {
                    put(COLUMN_PRIORITY, StoragePriorityMapper.priorityOf(event))
                    put(COLUMN_MODULE, event.module)
                    put(COLUMN_NAME, event.name)
                    put(COLUMN_SEVERITY, event.severity.name)
                    // data 列不再冗余存储 line protocol（readRecent 从 payload 解码渲染），
                    // 避免每条事件的双重序列化开销；保留列以免 schema 迁移
                    put(COLUMN_DATA, EMPTY_DATA)
                    put(COLUMN_PAYLOAD, ApmEventCodec.encode(event))
                    put(COLUMN_EVENT_ID, event.eventId)
                    put(COLUMN_TIMESTAMP, event.timestamp)
                    put(COLUMN_RETRY_COUNT, 0)
                    putNull(COLUMN_LEASE_OWNER)
                    put(COLUMN_LEASE_EXPIRES_AT, NO_LEASE_EXPIRY)
                }
                // Stable event identity makes local replay idempotent. Count
                // only successful inserts so capacity accounting stays exact.
                if (db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0L) {
                    insertedCount += 1
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 维护缓存计数并周期性重同步，纠正外部删除造成的漂移
        cachedRowCount.addAndGet(insertedCount.toLong())
        appendsSinceResync += insertedCount
        if (appendsSinceResync >= COUNT_RESYNC_INTERVAL) {
            cachedRowCount.set(countInternal(db))
            appendsSinceResync = 0
        }

        // 水位线保护：超容量时淘汰低优先级旧事件
        trimIfNeeded(db)
    }

    /**
     * 读取最近的事件（按时间倒序）。
     * 从 payload BLOB 解码后渲染 line protocol（data 列已不再冗余存储）。
     *
     * @param limit 最大条数
     * @return line protocol 格式的字符串列表，最新在前
     */
    @Synchronized
    override fun readRecent(limit: Int): List<String> {
        if (limit <= 0) {
            return emptyList()
        }

        val db = dbHelper.readableDatabase
        val results = mutableListOf<String>()

        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_DATA, COLUMN_PAYLOAD, COLUMN_EVENT_ID),
            null, null,
            null, null,
            "$COLUMN_TIMESTAMP DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val data = cursor.getString(0)
                // 兼容旧行：data 列非空直接使用；新行从 payload 解码渲染
                if (!data.isNullOrEmpty()) {
                    results.add(data)
                } else {
                    val payload = cursor.getBlob(1)
                    val storedEventId = cursor.getString(2)
                    runCatching { decodeStoredEvent(payload, storedEventId).toLineProtocol() }
                        .onSuccess(results::add)
                }
            }
        }

        return results
    }

    /**
     * 清除所有事件。
     */
    @Synchronized
    override fun clear() {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, null, null)
        // 计数器同步归零
        cachedRowCount.set(0L)
    }

    /**
     * 按优先级读取待上传事件（优先级高的先出）。
     *
     * @param limit 最大条数
     * @return (data, id) 列表，优先级高的在前
     */
    @Synchronized
    override fun readPending(limit: Int): List<PendingEvent> {
        if (limit <= 0) {
            return emptyList()
        }

        val db = dbHelper.readableDatabase
        val results = mutableListOf<PendingEvent>()
        val corruptedIds = mutableListOf<Long>()

        // 优先级降序（CRITICAL=3 先出），同优先级按时间升序（旧的先出）
        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID, COLUMN_PAYLOAD, COLUMN_RETRY_COUNT, COLUMN_EVENT_ID),
            null, null,
            null, null,
            "$COLUMN_PRIORITY DESC, $COLUMN_TIMESTAMP ASC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val payload = cursor.getBlob(1)
                val retryCount = cursor.getInt(2)
                val storedEventId = cursor.getString(3)
                runCatching {
                    PendingEvent(id, decodeStoredEvent(payload, storedEventId), retryCount)
                }.onSuccess(results::add)
                    .onFailure { corruptedIds += id }
            }
        }
        if (corruptedIds.isNotEmpty()) {
            deletePendingLocked(corruptedIds)
        }

        return results
    }

    /**
     * Atomically reserves available rows for one upload worker.
     * SQLite write transactions serialize the read-and-update sequence across
     * store instances and processes that share this database.
     *
     * @param ownerId unique upload worker identifier
     * @param limit maximum rows to reserve
     * @param nowMs current wall-clock time
     * @param leaseDurationMs reclaim delay for abandoned work
     * @return rows whose persisted owner is [ownerId]
     */
    @Synchronized
    override fun claimPending(
        ownerId: String,
        limit: Int,
        nowMs: Long,
        leaseDurationMs: Long
    ): List<PendingEvent> {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
        if (limit <= 0) {
            return emptyList()
        }

        val db = dbHelper.writableDatabase
        val claimed = mutableListOf<PendingEvent>()
        val corruptedIds = mutableListOf<Long>()
        val leaseExpiresAt = safeAdd(nowMs, leaseDurationMs)
        db.beginTransaction()
        try {
            // The write transaction is acquired before selecting candidates,
            // preventing another process from observing the same free rows.
            db.query(
                TABLE_NAME,
                arrayOf(COLUMN_ID, COLUMN_PAYLOAD, COLUMN_RETRY_COUNT, COLUMN_EVENT_ID),
                "$COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?",
                arrayOf(nowMs.toString()),
                null,
                null,
                "$COLUMN_PRIORITY DESC, $COLUMN_TIMESTAMP ASC",
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val payload = cursor.getBlob(1)
                    val retryCount = cursor.getInt(2)
                    val storedEventId = cursor.getString(3)
                    runCatching {
                        PendingEvent(id, decodeStoredEvent(payload, storedEventId), retryCount)
                    }.onSuccess(claimed::add)
                        .onFailure { corruptedIds += id }
                }
            }
            if (corruptedIds.isNotEmpty()) {
                deleteRows(db, corruptedIds)
            }
            if (claimed.isNotEmpty()) {
                val claimedIds = claimed.map(PendingEvent::id)
                val placeholders = claimedIds.joinToString(",") { "?" }
                val values = ContentValues().apply {
                    put(COLUMN_LEASE_OWNER, ownerId)
                    put(COLUMN_LEASE_EXPIRES_AT, leaseExpiresAt)
                }
                db.update(
                    TABLE_NAME,
                    values,
                    "$COLUMN_ID IN ($placeholders)",
                    claimedIds.map(Long::toString).toTypedArray()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        decrementCachedCount(corruptedIds.size)
        return claimed
    }

    /** Deletes only rows still owned by the acknowledging worker. */
    @Synchronized
    override fun acknowledgeClaim(ownerId: String, ids: List<Long>): Int {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        if (ids.isEmpty()) {
            return 0
        }
        val db = dbHelper.writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = ids.map(Long::toString) + ownerId
        val deleted = db.delete(
            TABLE_NAME,
            "$COLUMN_ID IN ($placeholders) AND $COLUMN_LEASE_OWNER = ?",
            arguments.toTypedArray()
        )
        decrementCachedCount(deleted)
        return deleted
    }

    /** Increments retry count and releases only rows owned by the caller. */
    @Synchronized
    override fun failClaim(ownerId: String, ids: List<Long>) {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        if (ids.isEmpty()) {
            return
        }
        val placeholders = ids.joinToString(",") { "?" }
        val arguments = ids.map(Long::toString) + ownerId
        dbHelper.writableDatabase.execSQL(
            "UPDATE $TABLE_NAME SET $COLUMN_RETRY_COUNT = $COLUMN_RETRY_COUNT + 1, " +
                "$COLUMN_LEASE_OWNER = NULL, $COLUMN_LEASE_EXPIRES_AT = $NO_LEASE_EXPIRY " +
                "WHERE $COLUMN_ID IN ($placeholders) AND $COLUMN_LEASE_OWNER = ?",
            arguments.toTypedArray()
        )
    }

    /** Releases every active lease belonging to one worker. */
    @Synchronized
    override fun releaseClaims(ownerId: String): Int {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        val values = ContentValues().apply {
            putNull(COLUMN_LEASE_OWNER)
            put(COLUMN_LEASE_EXPIRES_AT, NO_LEASE_EXPIRY)
        }
        return dbHelper.writableDatabase.update(
            TABLE_NAME,
            values,
            "$COLUMN_LEASE_OWNER = ?",
            arrayOf(ownerId)
        )
    }

    /**
     * 删除已成功上传的事件。
     *
     * @param ids 要删除的事件 ID 列表
     * @return 删除的行数
     */
    @Synchronized
    override fun deletePending(ids: List<Long>): Int {
        return deletePendingLocked(ids)
    }

    /**
     * 删除实现（调用方已持有锁），同步维护缓存计数。
     *
     * @param ids 要删除的事件 ID 列表
     * @return 删除的行数
     */
    private fun deletePendingLocked(ids: List<Long>): Int {
        if (ids.isEmpty()) {
            return 0
        }
        val db = dbHelper.writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val deleted = db.delete(
            TABLE_NAME,
            "$COLUMN_ID IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
        // 删除后同步扣减缓存计数
        if (deleted > 0 && cachedRowCount.get() != UNINITIALIZED_COUNT) {
            cachedRowCount.addAndGet(-deleted.toLong())
        }
        return deleted
    }

    /**
     * Increments retry counters for rows retained after a failed upload.
     *
     * @param ids row identifiers
     */
    @Synchronized
    override fun markRetry(ids: List<Long>) {
        if (ids.isEmpty()) {
            return
        }
        val db = dbHelper.writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        db.execSQL(
            "UPDATE $TABLE_NAME SET $COLUMN_RETRY_COUNT = $COLUMN_RETRY_COUNT + 1 " +
                "WHERE $COLUMN_ID IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    /**
     * 获取当前存储的事件总数。
     * 低频调用，直接 COUNT(*) 并顺带校准缓存计数。
     */
    @Synchronized
    override fun pendingCount(): Int {
        val db = dbHelper.readableDatabase
        val count = countInternal(db)
        // 顺带校准缓存计数
        cachedRowCount.set(count)
        return count.toInt()
    }

    /**
     * 清除重试耗尽或超龄的 outbox 行。
     *
     * @param maxRetryCount 重试次数达到该值（含）的行被清除
     * @param maxAgeMs 事件时间戳距今超过该毫秒数的行被清除
     * @return 清除的行数
     */
    @Synchronized
    override fun pruneExpired(maxRetryCount: Int, maxAgeMs: Long): Int {
        val db = dbHelper.writableDatabase
        val oldestAllowedTimestamp = System.currentTimeMillis() - maxAgeMs
        val nowMs = System.currentTimeMillis()
        val deleted = db.delete(
            TABLE_NAME,
            "($COLUMN_RETRY_COUNT >= ? OR $COLUMN_TIMESTAMP < ?) AND " +
                "($COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?)",
            arrayOf(maxRetryCount.toString(), oldestAllowedTimestamp.toString(), nowMs.toString())
        )
        // 清理后同步扣减缓存计数
        if (deleted > 0 && cachedRowCount.get() != UNINITIALIZED_COUNT) {
            cachedRowCount.addAndGet(-deleted.toLong())
        }
        return deleted
    }

    /** Closes the underlying database helper. */
    override fun close() {
        dbHelper.close()
    }

    /**
     * 首次写入前用一次 COUNT(*) 初始化缓存计数。
     *
     * @param db 可写数据库
     */
    private fun ensureRowCountInitialized(db: SQLiteDatabase) {
        // 仅第一次写入触发全表计数
        if (cachedRowCount.get() == UNINITIALIZED_COUNT) {
            cachedRowCount.set(countInternal(db))
        }
    }

    /**
     * 水位线保护：超容量时淘汰低优先级旧事件。
     * 淘汰顺序：priority ASC → timestamp ASC（低优先级、旧事件先淘汰）。
     * 使用缓存计数判断水位，避免每次写入全表 COUNT(*)。
     */
    private fun trimIfNeeded(db: SQLiteDatabase) {
        val currentCount = cachedRowCount.get()
        if (currentCount <= maxEvents) {
            return
        }

        val toDelete = (currentCount - maxEvents).toInt()
        // 查找要淘汰的事件 ID
        val idsToDelete = mutableListOf<Long>()
        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID),
            "$COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?",
            arrayOf(System.currentTimeMillis().toString()),
            null, null,
            "$COLUMN_PRIORITY ASC, $COLUMN_TIMESTAMP ASC",
            toDelete.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                idsToDelete.add(cursor.getLong(0))
            }
        }

        // 批量删除并扣减缓存计数
        if (idsToDelete.isNotEmpty()) {
            val placeholders = idsToDelete.joinToString(",") { "?" }
            val deleted = db.delete(
                TABLE_NAME,
                "$COLUMN_ID IN ($placeholders)",
                idsToDelete.map { it.toString() }.toTypedArray()
            )
            cachedRowCount.addAndGet(-deleted.toLong())
        }
    }

    /**
     * 内部计数方法，不额外 synchronized（调用方已持有锁）。
     */
    private fun countInternal(db: SQLiteDatabase): Long {
        db.query(
            TABLE_NAME,
            arrayOf("COUNT(*)"),
            null, null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 0L
    }

    /** Restores a migrated event ID when the payload predates codec version 2. */
    private fun decodeStoredEvent(payload: ByteArray, storedEventId: String): ApmEvent {
        val event = ApmEventCodec.decode(payload)
        return if (event.eventId.isBlank()) event.copy(eventId = storedEventId) else event
    }

    /** Deletes rows inside an existing transaction without reopening the database. */
    private fun deleteRows(db: SQLiteDatabase, ids: List<Long>): Int {
        if (ids.isEmpty()) {
            return 0
        }
        val placeholders = ids.joinToString(",") { "?" }
        return db.delete(
            TABLE_NAME,
            "$COLUMN_ID IN ($placeholders)",
            ids.map(Long::toString).toTypedArray()
        )
    }

    /** Applies one deletion delta without mutating an uninitialized cache. */
    private fun decrementCachedCount(deleted: Int) {
        if (deleted > 0 && cachedRowCount.get() != UNINITIALIZED_COUNT) {
            cachedRowCount.addAndGet(-deleted.toLong())
        }
    }

    /** Adds a lease duration without overflowing into an already-expired value. */
    private fun safeAdd(nowMs: Long, durationMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else nowMs + durationMs

    companion object {
        /** 默认最大存储事件数：50,000 条。 */
        private const val DEFAULT_MAX_EVENTS = 50_000

        /** 缓存计数未初始化标记。 */
        private const val UNINITIALIZED_COUNT = -1L

        /** 每写入多少条后用 COUNT(*) 重同步一次缓存计数。 */
        private const val COUNT_RESYNC_INTERVAL = 512

        /** data 列占位空串（line protocol 改为读取时从 payload 渲染）。 */
        private const val EMPTY_DATA = ""

        /** 表名。 */
        private const val TABLE_NAME = "events"

        /** 列：自增主键。 */
        private const val COLUMN_ID = "id"
        /** 列：事件优先级。 */
        private const val COLUMN_PRIORITY = "priority"
        /** 列：模块名。 */
        private const val COLUMN_MODULE = "module"
        /** 列：事件名。 */
        private const val COLUMN_NAME = "name"
        /** 列：严重级别。 */
        private const val COLUMN_SEVERITY = "severity"
        /** 列：序列化数据。 */
        private const val COLUMN_DATA = "data"
        /** 列：可逆二进制事件负载。 */
        private const val COLUMN_PAYLOAD = "payload"
        /** Column: stable event identity used for server-side deduplication. */
        private const val COLUMN_EVENT_ID = "event_id"
        /** 列：时间戳。 */
        private const val COLUMN_TIMESTAMP = "timestamp"
        /** 列：重试次数。 */
        private const val COLUMN_RETRY_COUNT = "retry_count"
        /** Column: upload worker that currently owns the row. */
        private const val COLUMN_LEASE_OWNER = "lease_owner"
        /** Column: wall-clock time after which another worker may reclaim the row. */
        private const val COLUMN_LEASE_EXPIRES_AT = "lease_expires_at"
        /** Sentinel expiry for an unclaimed row. */
        private const val NO_LEASE_EXPIRY = 0L
    }
}
