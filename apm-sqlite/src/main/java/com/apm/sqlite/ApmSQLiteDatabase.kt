package com.apm.sqlite

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock

/**
 * SQLiteDatabase 自动计时包装器。
 *
 * 零依赖的自动捕获方案：把常用读写操作（query/insert/update/delete/execSQL）
 * 委托给底层 [SQLiteDatabase] 并自动计时，通过 [SqliteModule.onSqlExecuted]
 * 送入既有分析管线（慢查询、主线程 DB、大结果集检测）。
 *
 * 使用方式：
 * ```kotlin
 * val db = ApmSQLiteDatabase(helper.writableDatabase, sqliteModule, "app.db")
 * db.insert("users", null, values)   // 自动计时并上报
 * db.rawQuery("SELECT ...", null)
 * ```
 *
 * 说明：
 * - 未覆盖的高级操作可通过 [delegate] 直接访问底层数据库；
 * - 手动埋点 API（[SqliteModule.onSqlExecuted]）保持不变，二者可共存。
 */
class ApmSQLiteDatabase(
    /** 被包装的底层数据库。 */
    val delegate: SQLiteDatabase,
    /** 接收计时上报的 SQLite 监控模块。 */
    private val module: SqliteModule,
    /** 数据库名称（用于上报标识）。 */
    private val databaseName: String = delegate.path.orEmpty()
) {

    /**
     * 执行原始查询并计时。
     *
     * @param sql 查询语句
     * @param selectionArgs 绑定参数
     * @return 查询游标
     */
    fun rawQuery(sql: String, selectionArgs: Array<String>? = null): Cursor {
        return timed(sql) { delegate.rawQuery(sql, selectionArgs) }
    }

    /**
     * 结构化查询并计时。
     *
     * @param table 表名
     * @param columns 返回列
     * @param selection where 子句
     * @param selectionArgs 绑定参数
     * @param groupBy 分组
     * @param having 分组过滤
     * @param orderBy 排序
     * @param limit 限制
     * @return 查询游标
     */
    fun query(
        table: String,
        columns: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        groupBy: String? = null,
        having: String? = null,
        orderBy: String? = null,
        limit: String? = null
    ): Cursor {
        // 上报语句采用可读的概要形式
        val sqlSummary = "SELECT FROM $table WHERE ${selection ?: SUMMARY_NO_SELECTION}"
        return timed(sqlSummary) {
            delegate.query(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit)
        }
    }

    /**
     * 插入一行并计时。
     *
     * @param table 表名
     * @param nullColumnHack 空列占位
     * @param values 待插入值
     * @return 新行 id，失败返回 -1
     */
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long {
        return timed("INSERT INTO $table", affectedRows = SINGLE_ROW) {
            delegate.insert(table, nullColumnHack, values)
        }
    }

    /**
     * 更新行并计时（影响行数自动上报）。
     *
     * @param table 表名
     * @param values 更新值
     * @param whereClause where 子句
     * @param whereArgs 绑定参数
     * @return 受影响行数
     */
    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int {
        val startMs = SystemClock.elapsedRealtime()
        val affected = delegate.update(table, values, whereClause, whereArgs)
        report("UPDATE $table WHERE ${whereClause ?: SUMMARY_NO_SELECTION}", startMs, affected)
        return affected
    }

    /**
     * 删除行并计时（影响行数自动上报）。
     *
     * @param table 表名
     * @param whereClause where 子句
     * @param whereArgs 绑定参数
     * @return 受影响行数
     */
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        val startMs = SystemClock.elapsedRealtime()
        val affected = delegate.delete(table, whereClause, whereArgs)
        report("DELETE FROM $table WHERE ${whereClause ?: SUMMARY_NO_SELECTION}", startMs, affected)
        return affected
    }

    /**
     * 执行任意 SQL 并计时。
     *
     * @param sql SQL 语句
     * @param bindArgs 绑定参数
     */
    fun execSQL(sql: String, bindArgs: Array<Any?>? = null) {
        timed(sql) {
            if (bindArgs == null) {
                delegate.execSQL(sql)
            } else {
                delegate.execSQL(sql, bindArgs)
            }
        }
    }

    /**
     * 计时执行并上报。
     *
     * @param sql 上报用的 SQL 概要
     * @param affectedRows 受影响行数（查询类为 0）
     * @param block 实际数据库操作
     * @return 操作结果
     */
    private inline fun <T> timed(sql: String, affectedRows: Int = 0, block: () -> T): T {
        val startMs = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            // 无论成功失败都上报耗时（失败的慢操作同样有诊断价值）
            report(sql, startMs, affectedRows)
        }
    }

    /**
     * 上报一次 SQL 执行。
     *
     * @param sql SQL 概要
     * @param startMs 开始时间（elapsedRealtime）
     * @param affectedRows 受影响行数
     */
    private fun report(sql: String, startMs: Long, affectedRows: Int) {
        val durationMs = SystemClock.elapsedRealtime() - startMs
        module.onSqlExecuted(
            sql = sql.take(MAX_SQL_LENGTH),
            durationMs = durationMs,
            affectedRows = affectedRows,
            databaseName = databaseName
        )
    }

    companion object {
        /** 无 where 子句时的概要占位。 */
        private const val SUMMARY_NO_SELECTION = "1"

        /** 单行操作的影响行数。 */
        private const val SINGLE_ROW = 1

        /** 上报 SQL 的最大长度。 */
        private const val MAX_SQL_LENGTH = 500
    }
}
