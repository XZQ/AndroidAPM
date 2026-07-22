package com.apm.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.ApmPriority
import com.apm.model.toLineProtocol
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于 SQLite 的事件存储实现。
 *
 * 替代 [FileEventStore] 的 500 行 ring buffer，提供：
 * - 持久化存储，容量 50,000 条
 * - 单事件 256 KiB 软上限与活跃 payload 64 MiB 总量预算
 * - 按优先级存储和读取（优先上传严重事件）
 * - 水位线保护：超行数或字节预算时按优先级和时间淘汰低优先级旧事件
 * - WAL 模式并发读写
 * - 批量事务写入 + 缓存行数计数（避免每条 append 全表 COUNT(*)）
 *
 * 线程安全：通过 synchronized 保护数据库操作。
 *
 * @param dbHelper SQLite 数据库助手
 * @param maxEvents 最大存储事件数，超出时自动淘汰
 * @param maxPayloadBytes 活跃 payload 总字节预算，超出时自动淘汰
 * @param maxEventPayloadBytes 单事件 payload 软上限，超出时单独拒绝
 */
class SQLiteEventStore(
    private val dbHelper: EventDbHelper,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxEventPayloadBytes: Int = DEFAULT_MAX_EVENT_PAYLOAD_BYTES
) : PendingEventStore {

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
        require(maxPayloadBytes > 0L) { "maxPayloadBytes must be positive" }
        require(maxEventPayloadBytes > 0) { "maxEventPayloadBytes must be positive" }
        require(maxEventPayloadBytes.toLong() <= maxPayloadBytes) {
            "maxEventPayloadBytes must not exceed maxPayloadBytes"
        }
    }

    /** Payload decoder seam used by deterministic fatal-error regression tests. */
    private var eventDecoder: (ByteArray) -> ApmEvent = ApmEventCodec::decode

    /** Creates a store with a caller-supplied internal payload decoder. */
    internal constructor(
        dbHelper: EventDbHelper,
        maxEvents: Int,
        eventDecoder: (ByteArray) -> ApmEvent
    ) : this(dbHelper, maxEvents, DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_EVENT_PAYLOAD_BYTES) {
        this.eventDecoder = eventDecoder
    }

    /**
     * 缓存的行数计数器。
     * 初始化时执行一次 COUNT(*)，之后随增删维护，
     * 每 [STATISTICS_RESYNC_INTERVAL] 次写入重同步一次以纠正漂移。
     */
    private val cachedRowCount = AtomicLong(UNINITIALIZED_COUNT)

    /** 缓存的活跃 payload 总字节数，与行数计数按相同周期重同步。 */
    private val cachedPayloadBytes = AtomicLong(UNINITIALIZED_COUNT)

    /** 距上次 COUNT(*) 重同步以来的写入条数（synchronized 保护）。 */
    private var appendsSinceResync = 0

    /**
     * 追加一条事件到 SQLite。
     * 委托批量路径，共享事务与计数维护逻辑。
     */
    @Synchronized
    override fun append(event: ApmEvent) {
        val result = appendBatchWithResult(listOf(event))
        require(result.rejectedEvents.isEmpty()) {
            "APM event payload exceeds $maxEventPayloadBytes byte durable soft limit"
        }
    }

    /** 追加单事件并返回精确存储结果。 */
    @Synchronized
    override fun appendWithResult(event: ApmEvent): EventStoreAppendResult =
        appendBatchWithResult(listOf(event))

    /**
     * 批量追加事件。
     * 所有插入在同一事务内提交，显著降低高频事件的每条写入开销。
     *
     * @param events 要存储的事件列表
     */
    @Synchronized
    override fun appendBatch(events: List<ApmEvent>) {
        val result = appendBatchWithResult(events)
        require(result.rejectedEvents.isEmpty()) {
            "${result.rejectedEvents.size} APM event payloads exceed the durable soft limit"
        }
    }

    /**
     * 编码时隔离单个坏事件，将其余有效事件放在同一事务中写入。
     *
     * @param events 要存储的事件列表
     * @return 接纳、拒绝与容量淘汰统计
     */
    @Synchronized
    override fun appendBatchWithResult(events: List<ApmEvent>): EventStoreAppendResult {
        if (events.isEmpty()) {
            return EventStoreAppendResult(acceptedEventCount = 0)
        }

        val encodedEvents = ArrayList<EncodedEvent>(events.size)
        val rejectedEvents = ArrayList<ApmEvent>()
        for (event in events) {
            try {
                val payload = ApmEventCodec.encode(event)
                if (payload.size > maxEventPayloadBytes) {
                    // 单事件软上限隔离异常输入，避免一个大 payload 毒化整个 dispatcher 批次。
                    rejectedEvents += event
                } else {
                    encodedEvents += EncodedEvent(event, payload)
                }
            } catch (_: IllegalArgumentException) {
                // Codec 的结构/硬上限校验失败仅拒绝当前事件，致命 VM 错误仍向外传播。
                rejectedEvents += event
            }
        }

        val trimResult = if (encodedEvents.isEmpty()) {
            CapacityTrimResult.EMPTY
        } else {
            appendBatchLocked(encodedEvents)
        }
        return EventStoreAppendResult(
            acceptedEventCount = events.size - rejectedEvents.size,
            rejectedEvents = rejectedEvents.toList(),
            capacityEvictedEventCount = trimResult.evictedEventCount,
            capacityEvictedPriorityCounts = trimResult.priorityCounts
        )
    }

    /**
     * 批量写入实现（调用方已持有锁）。
     * 事务插入 → 维护计数 → 周期性重同步 → 水位线淘汰。
     *
     * @param encodedEvents 已完成单事件大小校验的事件列表
     * @return 为满足容量预算而淘汰的事件数及其可观测优先级
     */
    private fun appendBatchLocked(encodedEvents: List<EncodedEvent>): CapacityTrimResult {
        val db = dbHelper.writableDatabase
        ensureStatisticsInitialized(db)

        // 单事务批量插入：一次 fsync 落盘整批事件
        var insertedCount = 0
        var insertedPayloadBytes = 0L
        db.beginTransaction()
        try {
            for (encodedEvent in encodedEvents) {
                val event = encodedEvent.event
                val values = ContentValues().apply {
                    put(COLUMN_PRIORITY, StoragePriorityMapper.priorityOf(event))
                    put(COLUMN_MODULE, event.module)
                    put(COLUMN_NAME, event.name)
                    put(COLUMN_SEVERITY, event.severity.name)
                    // data 列不再冗余存储 line protocol（readRecent 从 payload 解码渲染），
                    // 避免每条事件的双重序列化开销；保留列以免 schema 迁移
                    put(COLUMN_DATA, EMPTY_DATA)
                    put(COLUMN_PAYLOAD, encodedEvent.payload)
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
                    insertedPayloadBytes += encodedEvent.payload.size
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 维护缓存计数并周期性重同步，纠正外部删除造成的漂移
        cachedRowCount.addAndGet(insertedCount.toLong())
        cachedPayloadBytes.addAndGet(insertedPayloadBytes)
        appendsSinceResync += insertedCount
        if (appendsSinceResync >= STATISTICS_RESYNC_INTERVAL) {
            resyncStatistics(db)
            appendsSinceResync = 0
        }

        // 水位线保护：超行数或活跃 payload 预算时淘汰低优先级旧事件。
        return trimIfNeeded(db)
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
                    try {
                        results += decodeStoredEvent(payload, storedEventId).toLineProtocol()
                    } catch (_: Exception) {
                        // A corrupt debug row is omitted; fatal VM errors remain visible.
                    }
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
        // 行数与字节统计同步归零，避免 clear 后沿用旧水位。
        cachedRowCount.set(0L)
        cachedPayloadBytes.set(0L)
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
                try {
                    results += PendingEvent(id, decodeStoredEvent(payload, storedEventId), retryCount)
                } catch (_: Exception) {
                    corruptedIds += id
                }
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
                    try {
                        claimed += PendingEvent(id, decodeStoredEvent(payload, storedEventId), retryCount)
                    } catch (_: Exception) {
                        corruptedIds += id
                    }
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
        if (corruptedIds.isNotEmpty()) {
            // 坏行删除发生在同一事务中，提交后以数据库真值校准两个缓存维度。
            resyncStatistics(db)
        }
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
        return deleteAndAccount(
            db = db,
            selection = "$COLUMN_ID IN ($placeholders) AND $COLUMN_LEASE_OWNER = ?",
            selectionArgs = arguments.toTypedArray()
        )
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
        return deleteAndAccount(
            db = db,
            selection = "$COLUMN_ID IN ($placeholders)",
            selectionArgs = ids.map(Long::toString).toTypedArray()
        )
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
        val statistics = statisticsInternal(db)
        // 低频查询顺带校准行数与 payload 字节缓存。
        cachedRowCount.set(statistics.rowCount)
        cachedPayloadBytes.set(statistics.payloadBytes)
        return statistics.rowCount.toInt()
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
        return pruneExpiredWithResult(maxRetryCount, maxAgeMs).prunedEventCount
    }

    /** Removes expired rows with exact priority attribution inside the deletion transaction. */
    @Synchronized
    override fun pruneExpiredWithResult(maxRetryCount: Int, maxAgeMs: Long): EventStorePruneResult {
        val db = dbHelper.writableDatabase
        val oldestAllowedTimestamp = System.currentTimeMillis() - maxAgeMs
        val nowMs = System.currentTimeMillis()
        val selection = "($COLUMN_RETRY_COUNT >= ? OR $COLUMN_TIMESTAMP < ?) AND " +
            "($COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?)"
        val selectionArgs = arrayOf(
            maxRetryCount.toString(),
            oldestAllowedTimestamp.toString(),
            nowMs.toString()
        )
        var result = EventStorePruneResult(prunedEventCount = 0)
        db.beginTransaction()
        try {
            val priorities = priorityCountsInternal(db, selection, selectionArgs)
            val pruned = deleteAndAccount(db, selection, selectionArgs)
            result = EventStorePruneResult(
                prunedEventCount = pruned,
                priorityCounts = if (priorities.values.sum() == pruned) priorities else emptyMap()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result
    }

    /** Closes the underlying database helper. */
    override fun close() {
        dbHelper.close()
    }

    /**
     * 首次写入前初始化行数与 payload 字节缓存。
     *
     * @param db 可写数据库
     */
    private fun ensureStatisticsInitialized(db: SQLiteDatabase) {
        // 任一维度未初始化时从同一数据库快照重建，避免行数与字节水位错配。
        if (cachedRowCount.get() == UNINITIALIZED_COUNT ||
            cachedPayloadBytes.get() == UNINITIALIZED_COUNT
        ) {
            resyncStatistics(db)
        }
    }

    /**
     * 水位线保护：超行数或 payload 字节预算时淘汰低优先级旧事件。
     * 淘汰顺序：priority ASC → timestamp ASC（低优先级、旧事件先淘汰）。
     * 使用缓存统计判断水位；真正超限时先按数据库真值校准，避免跨实例陈旧缓存误删。
     *
     * @param db 可写数据库
     * @return 实际淘汰行数及其可观测优先级
     */
    private fun trimIfNeeded(db: SQLiteDatabase): CapacityTrimResult {
        if (!isOverCapacity(cachedRowCount.get(), cachedPayloadBytes.get())) {
            return CapacityTrimResult.EMPTY
        }

        val nowMs = System.currentTimeMillis()
        val idsToDelete = mutableListOf<Long>()
        val selectedPriorityCounts = mutableMapOf<ApmPriority, Int>()
        var deleted = 0
        db.beginTransaction()
        try {
            // The write transaction makes candidate selection and deletion atomic against another claimant.
            val actual = statisticsInternal(db)
            var projectedRows = actual.rowCount
            var projectedPayloadBytes = actual.payloadBytes
            if (isOverCapacity(projectedRows, projectedPayloadBytes)) {
                db.query(
                    TABLE_NAME,
                    arrayOf(COLUMN_ID, "LENGTH($COLUMN_PAYLOAD)", COLUMN_PRIORITY),
                    "$COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?",
                    arrayOf(nowMs.toString()),
                    null,
                    null,
                    "$COLUMN_PRIORITY ASC, $COLUMN_TIMESTAMP ASC"
                ).use { cursor ->
                    while (cursor.moveToNext() && isOverCapacity(projectedRows, projectedPayloadBytes)) {
                        idsToDelete += cursor.getLong(0)
                        StoragePriorityMapper.fromStoredValue(cursor.getInt(2))?.let { priority ->
                            selectedPriorityCounts[priority] =
                                (selectedPriorityCounts[priority] ?: 0) + 1
                        }
                        projectedRows -= 1L
                        projectedPayloadBytes = (projectedPayloadBytes - cursor.getLong(1)).coerceAtLeast(0L)
                    }
                }

                // Chunking stays below conservative Android SQLite bind-variable limits.
                for (idChunk in idsToDelete.chunked(TRIM_DELETE_BATCH_SIZE)) {
                    val placeholders = idChunk.joinToString(",") { "?" }
                    val arguments = idChunk.map(Long::toString) + nowMs.toString()
                    deleted += db.delete(
                        TABLE_NAME,
                        "$COLUMN_ID IN ($placeholders) AND " +
                            "($COLUMN_LEASE_OWNER IS NULL OR $COLUMN_LEASE_EXPIRES_AT <= ?)",
                        arguments.toTypedArray()
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (idsToDelete.isEmpty()) {
            // 活跃租约不允许为满足容量预算而删除，预算会在租约释放后的下一次写入重试。
            resyncStatistics(db)
            return CapacityTrimResult.EMPTY
        }

        // 容量淘汰是低频路径，删除后再次校准可覆盖跨实例并发变化。
        resyncStatistics(db)
        return CapacityTrimResult(
            evictedEventCount = deleted,
            priorityCounts = if (deleted == idsToDelete.size) selectedPriorityCounts else emptyMap()
        )
    }

    /** Counts selected rows by valid persisted priority without materializing event payloads. */
    private fun priorityCountsInternal(
        db: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>
    ): Map<ApmPriority, Int> {
        val counts = mutableMapOf<ApmPriority, Int>()
        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_PRIORITY, "COUNT(*)"),
            selection,
            selectionArgs,
            COLUMN_PRIORITY,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                StoragePriorityMapper.fromStoredValue(cursor.getInt(0))?.let { priority ->
                    counts[priority] = cursor.getInt(1)
                }
            }
        }
        return counts
    }

    /** 查询指定范围的行数与 payload 总字节，不额外加锁。 */
    private fun statisticsInternal(
        db: SQLiteDatabase,
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): StoreStatistics {
        db.query(
            TABLE_NAME,
            arrayOf("COUNT(*)", "COALESCE(SUM(LENGTH($COLUMN_PAYLOAD)), 0)"),
            selection,
            selectionArgs,
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return StoreStatistics(
                    rowCount = cursor.getLong(0),
                    payloadBytes = cursor.getLong(1)
                )
            }
        }
        return StoreStatistics(rowCount = 0L, payloadBytes = 0L)
    }

    /** 用数据库真值同时校准行数与 payload 字节缓存。 */
    private fun resyncStatistics(db: SQLiteDatabase): StoreStatistics {
        val statistics = statisticsInternal(db)
        cachedRowCount.set(statistics.rowCount)
        cachedPayloadBytes.set(statistics.payloadBytes)
        return statistics
    }

    /** 判断任一持久化容量维度是否超限。 */
    private fun isOverCapacity(rowCount: Long, payloadBytes: Long): Boolean =
        rowCount > maxEvents || payloadBytes > maxPayloadBytes

    /** Restores a migrated event ID when the payload predates codec version 2. */
    private fun decodeStoredEvent(payload: ByteArray, storedEventId: String): ApmEvent {
        val event = eventDecoder(payload)
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

    /**
     * 在删除前测量目标范围，并在删除后同步维护两个缓存维度。
     * 如果跨实例竞争导致命中行数变化，则直接回读数据库真值。
     */
    private fun deleteAndAccount(
        db: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>
    ): Int {
        val selected = statisticsInternal(db, selection, selectionArgs)
        val deleted = db.delete(TABLE_NAME, selection, selectionArgs)
        if (deleted.toLong() != selected.rowCount) {
            // 另一个进程可能在测量与删除之间改变了目标集合，不能按陈旧字节数扣减。
            resyncStatistics(db)
            return deleted
        }
        decrementCachedStatistics(selected)
        return deleted
    }

    /** 应用一次已确认删除的行数与字节增量，且不让陈旧缓存变为负数。 */
    private fun decrementCachedStatistics(deleted: StoreStatistics) {
        cachedRowCount.updateAndGet { current ->
            if (current == UNINITIALIZED_COUNT) current else (current - deleted.rowCount).coerceAtLeast(0L)
        }
        cachedPayloadBytes.updateAndGet { current ->
            if (current == UNINITIALIZED_COUNT) current else (current - deleted.payloadBytes).coerceAtLeast(0L)
        }
    }

    /**
     * 已完成 durable codec 编码的单事件。
     *
     * @property event 原始事件
     * @property payload 经硬上限和单事件软上限校验的二进制 payload
     */
    private data class EncodedEvent(
        val event: ApmEvent,
        val payload: ByteArray
    )

    /**
     * Capacity trimming result retained across the storage/core module boundary.
     *
     * @property evictedEventCount complete number of removed rows
     * @property priorityCounts exact counts when every selected row was removed and valid
     */
    private data class CapacityTrimResult(
        val evictedEventCount: Int,
        val priorityCounts: Map<ApmPriority, Int>
    ) {
        companion object {
            /** Shared empty result for the common below-budget path. */
            val EMPTY = CapacityTrimResult(0, emptyMap())
        }
    }

    /**
     * SQLite 活跃 outbox 的两个容量维度。
     *
     * @property rowCount 活跃行数
     * @property payloadBytes payload BLOB 字节总量
     */
    private data class StoreStatistics(
        val rowCount: Long,
        val payloadBytes: Long
    )

    /** Adds a lease duration without overflowing into an already-expired value. */
    private fun safeAdd(nowMs: Long, durationMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else nowMs + durationMs

    companion object {
        /** 默认最大存储事件数：50,000 条。 */
        private const val DEFAULT_MAX_EVENTS = 50_000

        /** 默认活跃 payload 总量预算：64 MiB。 */
        private const val DEFAULT_MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L

        /** 默认单事件 payload 软上限：256 KiB。 */
        private const val DEFAULT_MAX_EVENT_PAYLOAD_BYTES = 256 * 1024

        /** 缓存计数未初始化标记。 */
        private const val UNINITIALIZED_COUNT = -1L

        /** 每写入多少条后重同步行数和 payload 字节缓存。 */
        private const val STATISTICS_RESYNC_INTERVAL = 64

        /** 单次容量淘汰删除绑定的最大 ID 数，保守低于 Android SQLite 常见变量上限。 */
        private const val TRIM_DELETE_BATCH_SIZE = 500

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
