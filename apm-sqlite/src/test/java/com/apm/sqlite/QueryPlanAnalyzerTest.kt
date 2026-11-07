package com.apm.sqlite

import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryPlanAnalyzer 规则引擎单元测试。
 *
 * 覆盖 EXPLAIN QUERY PLAN 明细行的纯解析逻辑：全表扫描（含新旧语法与限定名）、
 * 临时 B-TREE、自动索引三类规则，合成扫描行排除，以及配置开关与触发门控。
 * analyze(db, sql) 的游标路径依赖 Android SQLite 运行时，由 Robolectric/Instrumentation 覆盖。
 */
class QueryPlanAnalyzerTest {

    /** 默认配置的规则引擎实例。 */
    private val analyzer = QueryPlanAnalyzer(SqliteConfig())

    // --- isAnalyzable ---

    /** SELECT 与 WITH（CTE）前缀可分析，大小写与前导空白不敏感。 */
    @Test
    fun `isAnalyzable accepts select and with prefixes`() {
        assertTrue(analyzer.isAnalyzable("SELECT * FROM users"))
        assertTrue(analyzer.isAnalyzable("WITH cte AS (SELECT 1) SELECT * FROM cte"))
        assertTrue(analyzer.isAnalyzable("select name from users"))
        assertTrue(analyzer.isAnalyzable("   SELECT 1"))
    }

    /** 写操作与其他语句不支持 EXPLAIN QUERY PLAN。 */
    @Test
    fun `isAnalyzable rejects non select statements`() {
        assertFalse(analyzer.isAnalyzable("INSERT INTO users VALUES (1)"))
        assertFalse(analyzer.isAnalyzable("UPDATE users SET name = 'a'"))
        assertFalse(analyzer.isAnalyzable("DELETE FROM users"))
        assertFalse(analyzer.isAnalyzable("CREATE TABLE t (x INTEGER)"))
        assertFalse(analyzer.isAnalyzable("PRAGMA table_info(users)"))
    }

    // --- shouldAnalyze ---

    /** 总开关关闭时不分析。 */
    @Test
    fun `shouldAnalyze respects enable switch`() {
        val disabled = QueryPlanAnalyzer(SqliteConfig(enableQueryPlanAnalysis = false))
        assertFalse(disabled.shouldAnalyze(durationMs = SLOW_DURATION_MS, sql = SELECT_SQL))
    }

    /** 耗时达到阈值（含等于）才分析。 */
    @Test
    fun `shouldAnalyze respects duration threshold`() {
        val config = SqliteConfig()
        assertEquals(DEFAULT_QUERY_PLAN_THRESHOLD_MS, config.queryPlanThresholdMs)
        assertFalse(analyzer.shouldAnalyze(durationMs = DEFAULT_QUERY_PLAN_THRESHOLD_MS - 1, sql = SELECT_SQL))
        assertTrue(analyzer.shouldAnalyze(durationMs = DEFAULT_QUERY_PLAN_THRESHOLD_MS, sql = SELECT_SQL))
        assertTrue(analyzer.shouldAnalyze(durationMs = SLOW_DURATION_MS, sql = SELECT_SQL))
    }

    /** 非可分析语句即使在阈值之上也不分析。 */
    @Test
    fun `shouldAnalyze requires analyzable sql`() {
        assertFalse(analyzer.shouldAnalyze(durationMs = SLOW_DURATION_MS, sql = "UPDATE users SET x = 1"))
    }

    // --- 全表扫描规则 ---

    /** 旧式 SCAN TABLE 与新式 SCAN 两种规划器输出都识别为全表扫描。 */
    @Test
    fun `analyzeDetails flags full table scan in both planner forms`() {
        val legacy = analyzer.analyzeDetails(listOf("SCAN TABLE users"))
        assertEquals(1, legacy.size)
        val legacyIssue = legacy.single()
        assertEquals(ISSUE_TYPE_FULL_SCAN, legacyIssue.issueType)
        assertEquals("users", legacyIssue.tableName)
        assertEquals(ApmSeverity.WARN, legacyIssue.severity)

        val modern = analyzer.analyzeDetails(listOf("SCAN users"))
        assertEquals(ISSUE_TYPE_FULL_SCAN, modern.single().issueType)
        assertEquals("users", modern.single().tableName)
    }

    /** 限定名（main.users）完整保留，便于定位跨库/临时库表。 */
    @Test
    fun `analyzeDetails preserves qualified table names`() {
        val issues = analyzer.analyzeDetails(listOf("SCAN TABLE main.users"))
        assertEquals("main.users", issues.single().tableName)
    }

    /** SEARCH TABLE 走索引，不产生问题。 */
    @Test
    fun `analyzeDetails ignores index search`() {
        val issues = analyzer.analyzeDetails(listOf("SEARCH TABLE users USING INDEX idx_users (id)"))
        assertTrue(issues.isEmpty())
    }

    /** 合成扫描行（CONSTANT ROWS / SUBQUERY）不是应用表的全表扫描。 */
    @Test
    fun `analyzeDetails ignores synthetic scan rows`() {
        val issues = analyzer.analyzeDetails(
            listOf(
                "SCAN CONSTANT ROWS",
                "SCAN 2 CONSTANT ROW",
                "SCAN SUBQUERY 0",
            )
        )
        assertTrue(issues.isEmpty())
    }

    /** 全表扫描检测可独立关闭。 */
    @Test
    fun `full scan detection can be disabled`() {
        val disabled = QueryPlanAnalyzer(SqliteConfig(enableFullScanDetection = false))
        val issues = disabled.analyzeDetails(listOf("SCAN TABLE users"))
        assertTrue(issues.isEmpty())
    }

    // --- 临时 B-TREE 规则 ---

    /** USE TEMP B-TREE 触发 INFO 级问题，表名缺失时用占位符。 */
    @Test
    fun `analyzeDetails flags temp b-tree as info`() {
        val issues = analyzer.analyzeDetails(listOf("USE TEMP B-TREE FOR ORDER BY"))
        val issue = issues.single()
        assertEquals(ISSUE_TYPE_TEMP_BTREE, issue.issueType)
        assertEquals(TABLE_NAME_UNKNOWN, issue.tableName)
        assertEquals(ApmSeverity.INFO, issue.severity)
    }

    /** 临时 B-TREE 行若同时含表名则一并提取。 */
    @Test
    fun `temp b-tree extracts table name when present`() {
        val issues = analyzer.analyzeDetails(listOf("SCAN TABLE orders USE TEMP B-TREE FOR RIGHT INCREMENTAL SORT"))
        val btree = issues.single { it.issueType == ISSUE_TYPE_TEMP_BTREE }
        assertEquals("orders", btree.tableName)
    }

    /** 临时 B-TREE 检测可独立关闭。 */
    @Test
    fun `temp b-tree detection can be disabled`() {
        val disabled = QueryPlanAnalyzer(SqliteConfig(enableTempBTreeDetection = false))
        val issues = disabled.analyzeDetails(listOf("USE TEMP B-TREE FOR ORDER BY"))
        assertTrue(issues.isEmpty())
    }

    // --- 自动索引规则 ---

    /** AUTOINDEX 始终检测（优化器自动建临时索引说明设计缺失）。 */
    @Test
    fun `analyzeDetails flags autoindex as warn`() {
        val issues = analyzer.analyzeDetails(listOf("SEARCH TABLE users USING AUTOINDEX (name=?)"))
        val issue = issues.single()
        assertEquals(ISSUE_TYPE_AUTO_INDEX, issue.issueType)
        // SEARCH 行不含 SCAN 匹配，表名回退占位符。
        assertEquals(TABLE_NAME_UNKNOWN, issue.tableName)
        assertEquals(ApmSeverity.WARN, issue.severity)
    }

    // --- 聚合行为 ---

    /** 多行明细的问题按行聚合并保留原始 detail 文本。 */
    @Test
    fun `multiple plan lines aggregate issues with detail preserved`() {
        val scanDetail = "SCAN TABLE events"
        val btreeDetail = "USE TEMP B-TREE FOR GROUP BY"
        val issues = analyzer.analyzeDetails(listOf(scanDetail, btreeDetail))
        assertEquals(2, issues.size)
        assertTrue(issues.any { it.issueType == ISSUE_TYPE_FULL_SCAN && it.detail == scanDetail })
        assertTrue(issues.any { it.issueType == ISSUE_TYPE_TEMP_BTREE && it.detail == btreeDetail })
    }

    /** 空明细行不产生问题。 */
    @Test
    fun `empty plan produces no issues`() {
        assertTrue(analyzer.analyzeDetails(emptyList()).isEmpty())
    }

    companion object {
        /** 可分析的目标语句。 */
        private const val SELECT_SQL = "SELECT * FROM users WHERE id = ?"

        /** 明显超过默认阈值的耗时。 */
        private const val SLOW_DURATION_MS = 500L

        /** 默认 EXPLAIN 阈值：50ms。 */
        private const val DEFAULT_QUERY_PLAN_THRESHOLD_MS = 50L

        /** 全表扫描问题类型。 */
        private const val ISSUE_TYPE_FULL_SCAN = "SCAN_TABLE"

        /** 临时排序表问题类型。 */
        private const val ISSUE_TYPE_TEMP_BTREE = "USE_TEMP_BTREE"

        /** 自动索引问题类型。 */
        private const val ISSUE_TYPE_AUTO_INDEX = "AUTOINDEX"

        /** 未知表名占位符。 */
        private const val TABLE_NAME_UNKNOWN = "<unknown>"
    }
}
