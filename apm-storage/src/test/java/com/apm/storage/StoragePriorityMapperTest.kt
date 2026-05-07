package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQLite 存储优先级映射测试。
 */
class StoragePriorityMapperTest {

    /** 严重级别应映射为 SQLite 淘汰和读取排序使用的稳定数值。 */
    @Test
    fun `severity maps to stable storage priority`() {
        assertEquals(0, StoragePriorityMapper.priorityOf(event(ApmSeverity.DEBUG)))
        assertEquals(1, StoragePriorityMapper.priorityOf(event(ApmSeverity.INFO)))
        assertEquals(2, StoragePriorityMapper.priorityOf(event(ApmSeverity.WARN)))
        assertEquals(3, StoragePriorityMapper.priorityOf(event(ApmSeverity.ERROR)))
        assertEquals(3, StoragePriorityMapper.priorityOf(event(ApmSeverity.FATAL)))
    }

    /**
     * 构造指定严重级别的测试事件。
     *
     * @param severity 严重级别。
     * @return 测试事件。
     */
    private fun event(severity: ApmSeverity): ApmEvent {
        return ApmEvent(
            module = "storage",
            name = "priority",
            severity = severity,
            processName = "process",
            threadName = "thread"
        )
    }
}
