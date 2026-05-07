package com.apm.uploader

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上传优先级排序测试。
 */
class UploadPriorityComparatorTest {

    /** 高优先级事件应排在低优先级事件前。 */
    @Test
    fun `higher priority events sort before lower priority events`() {
        val events = mutableListOf(
            event("low", ApmPriority.LOW, timestamp = EARLY_TIMESTAMP),
            event("critical", ApmPriority.CRITICAL, timestamp = EARLY_TIMESTAMP),
            event("normal", ApmPriority.NORMAL, timestamp = EARLY_TIMESTAMP)
        )

        events.sortWith(UploadPriorityComparator)

        assertEquals(listOf("critical", "normal", "low"), events.map { it.name })
    }

    /** 同优先级时较新的事件应优先上传。 */
    @Test
    fun `newer event wins when priority is equal`() {
        val events = mutableListOf(
            event("old", ApmPriority.HIGH, timestamp = EARLY_TIMESTAMP),
            event("new", ApmPriority.HIGH, timestamp = LATE_TIMESTAMP)
        )

        events.sortWith(UploadPriorityComparator)

        assertEquals(listOf("new", "old"), events.map { it.name })
    }

    /**
     * 构造测试事件。
     *
     * @param name 事件名。
     * @param priority 上传优先级。
     * @param timestamp 事件时间戳。
     * @return 测试事件。
     */
    private fun event(name: String, priority: ApmPriority, timestamp: Long): ApmEvent {
        return ApmEvent(
            module = "uploader",
            name = name,
            priority = priority,
            timestamp = timestamp,
            processName = "process",
            threadName = "thread"
        )
    }

    companion object {
        /** 较早时间戳。 */
        private const val EARLY_TIMESTAMP = 100L

        /** 较晚时间戳。 */
        private const val LATE_TIMESTAMP = 200L
    }
}
