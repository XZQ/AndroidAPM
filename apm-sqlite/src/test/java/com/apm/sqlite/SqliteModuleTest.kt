package com.apm.sqlite

import org.junit.Assert.*
import org.junit.Test

/**
 * SqliteModule 配置和参数测试。
 * 注：Module 的 onSqlExecuted 方法内部使用 Looper.myLooper() == Looper.getMainLooper()
 * 判断是否主线程，但构造函数本身不依赖 Handler/Looper。
 * 由于 onSqlExecuted 调用 Apm.emit() 和 Looper API，
 * 纯 JUnit 环境下无法安全测试该方法的完整路径，
 * 因此仅测试 Config 层和模块可实例化的基本行为。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 */
class SqliteModuleTest {

    /** 默认开启 SQLite 监控。 */
    @Test
    fun `default enableSqliteMonitor is true`() {
        val config = SqliteConfig()
        assertTrue(config.enableSqliteMonitor)
    }

    /** 默认慢查询阈值 100ms。 */
    @Test
    fun `default slowQueryThresholdMs is 100`() {
        val config = SqliteConfig()
        assertEquals(EXPECTED_SLOW_QUERY_MS, config.slowQueryThresholdMs)
    }

    /** 默认检测主线程 DB 操作。 */
    @Test
    fun `default detectMainThreadDb is true`() {
        val config = SqliteConfig()
        assertTrue(config.detectMainThreadDb)
    }

    /** 默认大影响行数阈值 1000。 */
    @Test
    fun `default largeAffectedRowsThreshold is 1000`() {
        val config = SqliteConfig()
        assertEquals(EXPECTED_LARGE_ROWS, config.largeAffectedRowsThreshold)
    }

    /** 默认 SQL 最大长度 500。 */
    @Test
    fun `default maxSqlLength is 500`() {
        val config = SqliteConfig()
        assertEquals(EXPECTED_MAX_SQL_LENGTH, config.maxSqlLength)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default maxStackTraceLength is 4000`() {
        val config = SqliteConfig()
        assertEquals(EXPECTED_MAX_STACK_LENGTH, config.maxStackTraceLength)
    }

    /** 默认开启 EXPLAIN QUERY PLAN 分析。 */
    @Test
    fun `default enableQueryPlanAnalysis is true`() {
        val config = SqliteConfig()
        assertTrue(config.enableQueryPlanAnalysis)
    }

    /** 默认 EXPLAIN 分析阈值 50ms。 */
    @Test
    fun `default queryPlanThresholdMs is 50`() {
        val config = SqliteConfig()
        assertEquals(EXPECTED_QUERY_PLAN_MS, config.queryPlanThresholdMs)
    }

    /** 默认开启全表扫描检测。 */
    @Test
    fun `default enableFullScanDetection is true`() {
        val config = SqliteConfig()
        assertTrue(config.enableFullScanDetection)
    }

    /** 默认开启临时排序表检测。 */
    @Test
    fun `default enableTempBTreeDetection is true`() {
        val config = SqliteConfig()
        assertTrue(config.enableTempBTreeDetection)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides all defaults`() {
        val config = SqliteConfig(
            enableSqliteMonitor = false,
            slowQueryThresholdMs = CUSTOM_SLOW_QUERY_MS,
            detectMainThreadDb = false,
            largeAffectedRowsThreshold = CUSTOM_LARGE_ROWS,
            maxSqlLength = CUSTOM_MAX_SQL_LENGTH,
            maxStackTraceLength = CUSTOM_MAX_STACK_LENGTH,
            enableQueryPlanAnalysis = false,
            queryPlanThresholdMs = CUSTOM_QUERY_PLAN_MS,
            enableFullScanDetection = false,
            enableTempBTreeDetection = false
        )
        // 验证所有自定义值已正确覆盖
        assertFalse(config.enableSqliteMonitor)
        assertEquals(CUSTOM_SLOW_QUERY_MS, config.slowQueryThresholdMs)
        assertFalse(config.detectMainThreadDb)
        assertEquals(CUSTOM_LARGE_ROWS, config.largeAffectedRowsThreshold)
        assertEquals(CUSTOM_MAX_SQL_LENGTH, config.maxSqlLength)
        assertEquals(CUSTOM_MAX_STACK_LENGTH, config.maxStackTraceLength)
        assertFalse(config.enableQueryPlanAnalysis)
        assertEquals(CUSTOM_QUERY_PLAN_MS, config.queryPlanThresholdMs)
        assertFalse(config.enableFullScanDetection)
        assertFalse(config.enableTempBTreeDetection)
    }

    /** data class copy 仅修改指定字段。 */
    @Test
    fun `copy modifies specified fields only`() {
        val original = SqliteConfig()
        val modified = original.copy(slowQueryThresholdMs = CUSTOM_SLOW_QUERY_MS)
        // 修改的字段
        assertEquals(CUSTOM_SLOW_QUERY_MS, modified.slowQueryThresholdMs)
        // 未修改的字段保持默认
        assertTrue(modified.enableSqliteMonitor)
        assertTrue(modified.detectMainThreadDb)
    }

    /** EXPLAIN 分析阈值应低于慢查询阈值（只对较慢的查询做分析）。 */
    @Test
    fun `query plan threshold is less than slow query threshold`() {
        val config = SqliteConfig()
        assertTrue(config.queryPlanThresholdMs < config.slowQueryThresholdMs)
    }

    companion object {
        /** 期望的默认慢查询阈值：100ms。 */
        private const val EXPECTED_SLOW_QUERY_MS = 100L
        /** 期望的默认大影响行数：1000。 */
        private const val EXPECTED_LARGE_ROWS = 1000
        /** 期望的默认 SQL 最大长度：500。 */
        private const val EXPECTED_MAX_SQL_LENGTH = 500
        /** 期望的默认堆栈最大长度：4000。 */
        private const val EXPECTED_MAX_STACK_LENGTH = 4000
        /** 期望的默认 EXPLAIN 分析阈值：50ms。 */
        private const val EXPECTED_QUERY_PLAN_MS = 50L
        /** 自定义慢查询阈值：200ms。 */
        private const val CUSTOM_SLOW_QUERY_MS = 200L
        /** 自定义大影响行数：5000。 */
        private const val CUSTOM_LARGE_ROWS = 5000
        /** 自定义 SQL 最大长度：1000。 */
        private const val CUSTOM_MAX_SQL_LENGTH = 1000
        /** 自定义堆栈最大长度：8000。 */
        private const val CUSTOM_MAX_STACK_LENGTH = 8000
        /** 自定义 EXPLAIN 分析阈值：100ms。 */
        private const val CUSTOM_QUERY_PLAN_MS = 100L
    }
}
