package com.didi.apm.sqlite

import org.junit.Assert.*
import org.junit.Test

/**
 * SqliteConfig 默认值测试。
 */
class SqliteConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableSqliteMonitor is true`() {
        val config = SqliteConfig()
        assertTrue(config.enableSqliteMonitor)
    }

    /** 默认慢查询阈值 100ms。 */
    @Test
    fun `default slowQueryThresholdMs is 100`() {
        val config = SqliteConfig()
        assertEquals(100L, config.slowQueryThresholdMs)
    }

    /** 默认检测主线程 DB。 */
    @Test
    fun `default detectMainThreadDb is true`() {
        val config = SqliteConfig()
        assertTrue(config.detectMainThreadDb)
    }

    /** 默认大影响行数 1000。 */
    @Test
    fun `default largeAffectedRowsThreshold is 1000`() {
        val config = SqliteConfig()
        assertEquals(1000, config.largeAffectedRowsThreshold)
    }

    /** 默认 SQL 最大长度 500。 */
    @Test
    fun `default maxSqlLength is 500`() {
        val config = SqliteConfig()
        assertEquals(500, config.maxSqlLength)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = SqliteConfig(slowQueryThresholdMs = 200L, detectMainThreadDb = false)
        assertEquals(200L, config.slowQueryThresholdMs)
        assertFalse(config.detectMainThreadDb)
    }
}
