package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmPriority

/**
 * SQLite 存储优先级映射器。
 *
 * 存储层使用数值优先级执行读取排序和水位线淘汰，映射集中在此处可避免
 * SQLite 写入、读取和测试口径分叉。
 */
internal object StoragePriorityMapper {

    /**
     * Maps the explicit event priority used by upload ordering.
     *
     * @param event 待存储事件。
     * @return 存储排序使用的优先级数值，数值越大越重要。
     */
    fun priorityOf(event: ApmEvent): Int {
        return event.priority.value
    }

    /** Maps one persisted numeric value back to a known priority, or null for corrupt input. */
    fun fromStoredValue(value: Int): ApmPriority? =
        ApmPriority.values().firstOrNull { priority -> priority.value == value }
}
