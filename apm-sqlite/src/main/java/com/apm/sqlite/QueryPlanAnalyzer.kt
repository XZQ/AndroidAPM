package com.apm.sqlite

import com.apm.core.Apm
import android.database.sqlite.SQLiteDatabase
import com.apm.model.ApmSeverity

/**
 * EXPLAIN QUERY PLAN 规则引擎。
 * 对 SQL 语句执行 EXPLAIN QUERY PLAN，检测全表扫描、临时排序表、自动索引等结构性问题。
 * 无需依赖数据量大小，开发期即可发现问题根因（对标 Matrix SQLiteLint）。
 *
 * 规则说明：
 * 1. SCAN TABLE → 全表扫描，缺少索引
 * 2. USE TEMP B-TREE → 需要排序索引
 * 3. AUTOINDEX → 查询优化器自动创建临时索引，说明设计有问题
 */
class QueryPlanAnalyzer(private val config: SqliteConfig) {

    /**
     * 查询计划问题数据类。
     */
    data class QueryPlanIssue(
        /** 问题类型标识。 */
        val issueType: String,
        /** 涉及的表名。 */
        val tableName: String,
        /** 问题描述详情。 */
        val detail: String,
        /** 问题严重级别。 */
        val severity: ApmSeverity
    )

    /**
     * 判断 SQL 是否适合做 EXPLAIN QUERY PLAN 分析。
     * INSERT/UPDATE/DELETE 不支持，仅 SELECT 可用。
     */
    fun isAnalyzable(sql: String): Boolean {
        val trimmed = sql.trimStart().uppercase()
        return trimmed.startsWith(SQL_SELECT) || trimmed.startsWith(SQL_WITH)
    }

    /** Applies the public enable switch and duration threshold before EXPLAIN. */
    internal fun shouldAnalyze(durationMs: Long, sql: String): Boolean {
        return config.enableQueryPlanAnalysis &&
            durationMs >= config.queryPlanThresholdMs &&
            isAnalyzable(sql)
    }

    /**
     * 对 SQL 执行 EXPLAIN QUERY PLAN 并返回检测到的问题列表。
     */
    fun analyze(db: SQLiteDatabase, sql: String): List<QueryPlanIssue> {
        return analyze(db, sql, selectionArgs = null)
    }

    /** Executes a parameterized EXPLAIN while retaining the published two-argument JVM API. */
    fun analyze(db: SQLiteDatabase, sql: String, selectionArgs: Array<String>?): List<QueryPlanIssue> {
        val issues = mutableListOf<QueryPlanIssue>()
        try {
            // 执行 EXPLAIN QUERY PLAN
            val cursor = db.rawQuery(QUERY_PLAN_PREFIX + sql, selectionArgs)
            cursor.use {
                val planLines = mutableListOf<String>()
                while (it.moveToNext()) {
                    // EXPLAIN QUERY PLAN 输出列：selectid | order | from | detail
                    val detail = it.getString(INDEX_DETAIL)
                    planLines.add(detail.orEmpty())
                }
                issues.addAll(analyzeDetails(planLines))
            }
        } catch (e: Exception) {
            // EXPLAIN QUERY PLAN 执行失败不影响正常流程，但记入自监控
            Apm.recordInternalError(ERROR_TAG_EXPLAIN_PLAN, e)
        }
        return issues
    }

    /** Parses planner detail rows without depending on Android SQLite runtime. */
    internal fun analyzeDetails(planLines: List<String>): List<QueryPlanIssue> {
        val issues = mutableListOf<QueryPlanIssue>()
        for (line in planLines) {
            val scanMatch = REGEX_SCAN_TABLE.find(line)
            // Modern SQLite emits both "SCAN table" and older "SCAN TABLE table" forms.
            val scannedEntity = scanMatch?.groupValues?.get(INDEX_TABLE_NAME_GROUP)
            val isTableScan = scannedEntity != null &&
                !REGEX_SYNTHETIC_SCAN.containsMatchIn(line) &&
                scannedEntity.uppercase() !in NON_TABLE_SCAN_ENTITIES
            if (config.enableFullScanDetection && isTableScan) {
                issues.add(
                    QueryPlanIssue(
                        issueType = ISSUE_TYPE_FULL_SCAN,
                        tableName = requireNotNull(scannedEntity),
                        detail = line,
                        severity = ApmSeverity.WARN
                    )
                )
            }
            if (config.enableTempBTreeDetection && REGEX_TEMP_BTREE.containsMatchIn(line)) {
                issues.add(
                    QueryPlanIssue(
                        issueType = ISSUE_TYPE_TEMP_BTREE,
                        tableName = scanMatch?.groupValues?.get(INDEX_TABLE_NAME_GROUP) ?: TABLE_NAME_UNKNOWN,
                        detail = line,
                        severity = ApmSeverity.INFO
                    )
                )
            }
            if (REGEX_AUTOINDEX.containsMatchIn(line)) {
                issues.add(
                    QueryPlanIssue(
                        issueType = ISSUE_TYPE_AUTO_INDEX,
                        tableName = scanMatch?.groupValues?.get(INDEX_TABLE_NAME_GROUP) ?: TABLE_NAME_UNKNOWN,
                        detail = line,
                        severity = ApmSeverity.WARN
                    )
                )
            }
        }
        return issues
    }

    companion object {
        /** 自监控 tag：EXPLAIN QUERY PLAN 执行失败。 */
        private const val ERROR_TAG_EXPLAIN_PLAN = "sqlite_explain_plan"

        /** 全表扫描问题类型。 */
        private const val ISSUE_TYPE_FULL_SCAN = "SCAN_TABLE"
        /** 临时排序表问题类型。 */
        private const val ISSUE_TYPE_TEMP_BTREE = "USE_TEMP_BTREE"
        /** 自动索引问题类型。 */
        private const val ISSUE_TYPE_AUTO_INDEX = "AUTOINDEX"
        /** SQL 前缀：SELECT。 */
        private const val SQL_SELECT = "SELECT"
        /** SQL 前缀：WITH（CTE）。 */
        private const val SQL_WITH = "WITH"
        /** EXPLAIN QUERY PLAN 前缀。 */
        private const val QUERY_PLAN_PREFIX = "EXPLAIN QUERY PLAN "
        /** Matches modern `SCAN table` and legacy `SCAN TABLE table` planner details. */
        private val REGEX_SCAN_TABLE = Regex("\\bSCAN(?: TABLE)? ([A-Za-z0-9_.$-]+)", RegexOption.IGNORE_CASE)
        /** 匹配 USE TEMP B-TREE 的正则。 */
        private val REGEX_TEMP_BTREE = Regex("USE TEMP B-TREE", RegexOption.IGNORE_CASE)
        /** 匹配 AUTOINDEX 的正则。 */
        private val REGEX_AUTOINDEX = Regex("AUTOINDEX", RegexOption.IGNORE_CASE)
        /** Planner scan targets that are synthetic rows rather than application tables. */
        private val NON_TABLE_SCAN_ENTITIES = setOf("CONSTANT", "SUBQUERY")
        /** Matches SQLite synthetic constant/subquery scan rows, including numeric prefixes. */
        private val REGEX_SYNTHETIC_SCAN = Regex(
            "\\bSCAN (?:\\d+ )?(?:CONSTANT ROWS?|SUBQUERY\\b)",
            RegexOption.IGNORE_CASE
        )
        /** 游标中 detail 列的索引。 */
        private const val INDEX_DETAIL = 3
        /** 正则中表名捕获组的索引。 */
        private const val INDEX_TABLE_NAME_GROUP = 1
        /** 未知表名占位符。 */
        private const val TABLE_NAME_UNKNOWN = "<unknown>"
    }
}
