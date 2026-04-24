package com.apm.sqlite

import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority

/**
 * SQLite 监控模块。
 * 监控数据库操作的耗时和频率，检测慢查询和主线程 DB 操作。
 *
 * 监控策略：
 * 1. 慢查询检测：SQL 执行超过阈值告警
 * 2. 主线程 DB 操作检测：在主线程执行 SQL 告警
 * 3. 大数据量操作检测：影响行数过多告警
 *
 * 使用方式（外部回调）：
 * ```kotlin
 * Apm.init(this, ApmConfig()) {
 *     register(SqliteModule())
 * }
 * // 在 SQLiteOpenHelper 或 ORM 层调用
 * sqliteModule.onSqlExecuted(sql, durationMs, affectedRows)
 * ```
 */
class SqliteModule(
    /** 模块配置。 */
    private val config: SqliteConfig = SqliteConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 是否已启动。 */
    @Volatile
    private var started = false

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableSqliteMonitor
        apmContext?.logger?.d("SQLite module started, slowQueryThreshold=${config.slowQueryThresholdMs}ms")
    }

    override fun onStop() {
        started = false
    }

    /**
     * 一次 SQL 操作完成时调用。
     * 由外部数据库代理或 ORM 框架在 SQL 执行后回调。
     *
     * @param sql 执行的 SQL 语句
     * @param durationMs 执行耗时（毫秒）
     * @param affectedRows 影响行数
     * @param databaseName 数据库名称
     */
    fun onSqlExecuted(
        sql: String,
        durationMs: Long,
        affectedRows: Int = 0,
        databaseName: String = ""
    ) {
        if (!started) return

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val isSlowQuery = durationMs >= config.slowQueryThresholdMs
        val isMainThreadDb = isMainThread && config.detectMainThreadDb
        val isLargeRows = affectedRows >= config.largeAffectedRowsThreshold

        // 无异常则跳过
        if (!isSlowQuery && !isMainThreadDb && !isLargeRows) return

        val fields = mutableMapOf<String, Any?>(
            FIELD_SQL to sql.take(config.maxSqlLength),
            FIELD_DURATION_MS to durationMs,
            FIELD_AFFECTED_ROWS to affectedRows,
            FIELD_IS_MAIN_THREAD to isMainThread
        )

        // 附加数据库名
        if (databaseName.isNotEmpty()) {
            fields[FIELD_DB_NAME] = databaseName
        }

        // 抓取堆栈
        if (isSlowQuery || isMainThreadDb) {
            val stackTrace = Thread.currentThread().stackTrace
                .joinToString(LINE_SEPARATOR)
                .take(config.maxStackTraceLength)
            fields[FIELD_STACK_TRACE] = stackTrace
        }

        val severity = when {
            isMainThreadDb && isSlowQuery -> ApmSeverity.ERROR
            isSlowQuery || isMainThreadDb -> ApmSeverity.WARN
            else -> ApmSeverity.INFO
        }

        val eventName = when {
            isMainThreadDb -> EVENT_MAIN_THREAD_DB
            isSlowQuery -> EVENT_SLOW_QUERY
            else -> EVENT_LARGE_OPERATION
        }

        Apm.emit(
            module = MODULE_NAME,
            name = eventName,
            kind = ApmEventKind.ALERT,
            severity = severity, priority = ApmPriority.NORMAL,
            fields = fields
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "sqlite"
        /** 慢查询事件。 */
        private const val EVENT_SLOW_QUERY = "slow_query"
        /** 主线程 DB 操作事件。 */
        private const val EVENT_MAIN_THREAD_DB = "main_thread_db"
        /** 大数据量操作事件。 */
        private const val EVENT_LARGE_OPERATION = "large_db_operation"
        /** 字段：SQL 语句。 */
        private const val FIELD_SQL = "sql"
        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"
        /** 字段：影响行数。 */
        private const val FIELD_AFFECTED_ROWS = "affectedRows"
        /** 字段：是否主线程。 */
        private const val FIELD_IS_MAIN_THREAD = "isMainThread"
        /** 字段：数据库名。 */
        private const val FIELD_DB_NAME = "databaseName"
        /** 字段：堆栈。 */
        private const val FIELD_STACK_TRACE = "stackTrace"
        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
    }
}
