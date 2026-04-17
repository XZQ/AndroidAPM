package com.didi.apm.sqlite

/**
 * SQLite 监控模块配置。
 * 包含慢查询检测和 EXPLAIN QUERY PLAN 规则引擎配置。
 */
data class SqliteConfig(
    /** 是否开启 SQLite 监控。 */
    val enableSqliteMonitor: Boolean = true,
    /** 慢查询阈值（毫秒）。 */
    val slowQueryThresholdMs: Long = DEFAULT_SLOW_QUERY_THRESHOLD_MS,
    /** 是否检测主线程 DB 操作。 */
    val detectMainThreadDb: Boolean = true,
    /** 单次操作影响行数告警阈值。 */
    val largeAffectedRowsThreshold: Int = DEFAULT_LARGE_AFFECTED_ROWS,
    /** 最大 SQL 语句长度。 */
    val maxSqlLength: Int = DEFAULT_MAX_SQL_LENGTH,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用 EXPLAIN QUERY PLAN 分析。 */
    val enableQueryPlanAnalysis: Boolean = true,
    /** 耗时超过此值才做 EXPLAIN 分析（毫秒），避免频繁分析。 */
    val queryPlanThresholdMs: Long = DEFAULT_QUERY_PLAN_THRESHOLD_MS,
    /** 是否启用全表扫描检测。 */
    val enableFullScanDetection: Boolean = true,
    /** 是否启用临时排序表检测。 */
    val enableTempBTreeDetection: Boolean = true
) {
    companion object {
        /** 默认慢查询阈值：100ms。 */
        private const val DEFAULT_SLOW_QUERY_THRESHOLD_MS = 100L
        /** 默认大影响行数阈值：1000。 */
        private const val DEFAULT_LARGE_AFFECTED_ROWS = 1000
        /** 默认 SQL 最大长度。 */
        private const val DEFAULT_MAX_SQL_LENGTH = 500
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认 EXPLAIN 分析阈值：50ms。 */
        private const val DEFAULT_QUERY_PLAN_THRESHOLD_MS = 50L
    }
}
