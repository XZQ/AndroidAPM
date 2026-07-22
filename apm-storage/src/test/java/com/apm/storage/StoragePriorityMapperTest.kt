package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SQLite 存储优先级映射测试。
 */
class StoragePriorityMapperTest {

    /** 严重级别应映射为 SQLite 淘汰和读取排序使用的稳定数值。 */
    @Test
    fun `severity maps to stable storage priority`() {
        assertEquals(0, StoragePriorityMapper.priorityOf(event(ApmPriority.LOW)))
        assertEquals(1, StoragePriorityMapper.priorityOf(event(ApmPriority.NORMAL)))
        assertEquals(2, StoragePriorityMapper.priorityOf(event(ApmPriority.HIGH)))
        assertEquals(3, StoragePriorityMapper.priorityOf(event(ApmPriority.CRITICAL)))
    }

    /** Persisted values map back exactly while corrupt values remain explicitly unknown. */
    @Test
    fun `stored values map back to event priority`() {
        for (priority in ApmPriority.values()) {
            assertEquals(priority, StoragePriorityMapper.fromStoredValue(priority.value))
        }
        assertEquals(null, StoragePriorityMapper.fromStoredValue(Int.MIN_VALUE))
    }

    /**
     * 构造指定严重级别的测试事件。
     *
     * @param priority 事件优先级。
     * @return 测试事件。
     */
    private fun event(priority: ApmPriority): ApmEvent {
        return ApmEvent(
            module = "storage",
            name = "priority",
            severity = ApmSeverity.INFO,
            priority = priority,
            processName = "process",
            threadName = "thread"
        )
    }
}
