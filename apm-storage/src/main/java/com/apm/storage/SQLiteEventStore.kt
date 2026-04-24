package com.apm.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.apm.model.ApmEvent
import com.apm.model.toLineProtocol

/**
 * 基于 SQLite 的事件存储实现。
 *
 * 替代 [FileEventStore] 的 500 行 ring buffer，提供：
 * - 持久化存储，容量 50,000 条
 * - 按优先级存储和读取（优先上传严重事件）
 * - 水位线保护：超容量时按优先级和时间淘汰低优先级旧事件
 * - WAL 模式并发读写
 *
 * 线程安全：通过 synchronized 保护数据库操作。
 *
 * @param dbHelper SQLite 数据库助手
 * @param maxEvents 最大存储事件数，超出时自动淘汰
 */
class SQLiteEventStore(
    private val dbHelper: EventDbHelper,
    /** 最大存储事件数。超出后按优先级 ASC + timestamp ASC 淘汰。 */
    private val maxEvents: Int = DEFAULT_MAX_EVENTS
) : EventStore {

    /**
     * 追加一条事件到 SQLite。
     *
     * 1. 序列化事件为 Line Protocol 格式
     * 2. 写入数据库
     * 3. 检查水位线，超容量时淘汰低优先级旧事件
     */
    @Synchronized
    override fun append(event: ApmEvent) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PRIORITY, event.priorityValue)
            put(COLUMN_MODULE, event.module)
            put(COLUMN_NAME, event.name)
            put(COLUMN_SEVERITY, event.severity.name)
            put(COLUMN_DATA, event.toLineProtocol())
            put(COLUMN_TIMESTAMP, event.timestamp)
            put(COLUMN_RETRY_COUNT, 0)
        }
        db.insert(TABLE_NAME, null, values)

        // 水位线保护：超容量时淘汰低优先级旧事件
        trimIfNeeded(db)
    }

    /**
     * 读取最近的事件（按时间倒序）。
     *
     * @param limit 最大条数
     * @return line protocol 格式的字符串列表，最新在前
     */
    @Synchronized
    override fun readRecent(limit: Int): List<String> {
        if (limit <= 0) return emptyList()

        val db = dbHelper.readableDatabase
        val results = mutableListOf<String>()

        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_DATA),
            null, null,
            null, null,
            "$COLUMN_TIMESTAMP DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursor.getString(0))
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
    }

    /**
     * 按优先级读取待上传事件（优先级高的先出）。
     *
     * @param limit 最大条数
     * @return (data, id) 列表，优先级高的在前
     */
    @Synchronized
    fun readByPriority(limit: Int): List<Pair<Long, String>> {
        if (limit <= 0) return emptyList()

        val db = dbHelper.readableDatabase
        val results = mutableListOf<Pair<Long, String>>()

        // 优先级降序（CRITICAL=3 先出），同优先级按时间升序（旧的先出）
        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID, COLUMN_DATA),
            null, null,
            null, null,
            "$COLUMN_PRIORITY DESC, $COLUMN_TIMESTAMP ASC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val data = cursor.getString(1)
                results.add(id to data)
            }
        }

        return results
    }

    /**
     * 删除已成功上传的事件。
     *
     * @param ids 要删除的事件 ID 列表
     * @return 删除的行数
     */
    @Synchronized
    fun deleteByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = dbHelper.writableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        return db.delete(
            TABLE_NAME,
            "$COLUMN_ID IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    /**
     * 获取当前存储的事件总数。
     */
    @Synchronized
    fun count(): Int {
        val db = dbHelper.readableDatabase
        db.query(
            TABLE_NAME,
            arrayOf("COUNT(*)"),
            null, null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    /**
     * 水位线保护：超容量时淘汰低优先级旧事件。
     * 淘汰顺序：priority ASC → timestamp ASC（低优先级、旧事件先淘汰）。
     */
    private fun trimIfNeeded(db: SQLiteDatabase) {
        val currentCount = countInternal(db)
        if (currentCount <= maxEvents) return

        val toDelete = currentCount - maxEvents
        // 查找要淘汰的事件 ID
        val idsToDelete = mutableListOf<Long>()
        db.query(
            TABLE_NAME,
            arrayOf(COLUMN_ID),
            null, null,
            null, null,
            "$COLUMN_PRIORITY ASC, $COLUMN_TIMESTAMP ASC",
            toDelete.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                idsToDelete.add(cursor.getLong(0))
            }
        }

        // 批量删除
        if (idsToDelete.isNotEmpty()) {
            val placeholders = idsToDelete.joinToString(",") { "?" }
            db.delete(
                TABLE_NAME,
                "$COLUMN_ID IN ($placeholders)",
                idsToDelete.map { it.toString() }.toTypedArray()
            )
        }
    }

    /**
     * 内部计数方法，不额外 synchronized（调用方已持有锁）。
     */
    private fun countInternal(db: SQLiteDatabase): Int {
        db.query(
            TABLE_NAME,
            arrayOf("COUNT(*)"),
            null, null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    companion object {
        /** 默认最大存储事件数：50,000 条。 */
        private const val DEFAULT_MAX_EVENTS = 50_000

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
        /** 列：时间戳。 */
        private const val COLUMN_TIMESTAMP = "timestamp"
        /** 列：重试次数。 */
        private const val COLUMN_RETRY_COUNT = "retry_count"
    }
}

/**
 * ApmEvent 的优先级数值映射。
 * 用于 SQLite 存储和排序。
 */
private val ApmEvent.priorityValue: Int
    get() = when (severity) {
        com.apm.model.ApmSeverity.FATAL -> 3
        com.apm.model.ApmSeverity.ERROR -> 3
        com.apm.model.ApmSeverity.WARN -> 2
        com.apm.model.ApmSeverity.INFO -> 1
        com.apm.model.ApmSeverity.DEBUG -> 0
    }
