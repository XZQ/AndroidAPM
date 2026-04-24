package com.apm.core

import org.junit.Assert.*
import org.junit.Test

/**
 * ProcessEventCoordinator 和 ProcessSessionId 单元测试。
 * 仅测试纯逻辑部分，不依赖 Android Context。
 */
class ProcessEventCoordinatorTest {

    /** ProcessSessionId 应返回非空字符串。 */
    @Test
    fun `session id is non-empty string`() {
        val sessionId = ProcessSessionId.get()
        assertTrue("Session ID should be non-empty", sessionId.isNotEmpty())
    }

    /** ProcessSessionId 多次调用应返回相同的值。 */
    @Test
    fun `session id is stable across calls`() {
        val id1 = ProcessSessionId.get()
        val id2 = ProcessSessionId.get()
        assertEquals("Session ID should be stable", id1, id2)
    }

    /** SessionId 应包含递增序号前缀。 */
    @Test
    fun `session id contains sequence prefix`() {
        val sessionId = ProcessSessionId.get()
        // 格式为 {seq}_{uuid_prefix}，下划线前是数字
        val underscoreIdx = sessionId.indexOf('_')
        assertTrue("Should contain underscore separator", underscoreIdx > 0)
        val prefix = sessionId.substring(0, underscoreIdx)
        assertTrue("Prefix should be numeric", prefix.toLongOrNull() != null)
    }
}
