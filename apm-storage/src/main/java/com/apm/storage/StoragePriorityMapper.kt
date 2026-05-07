package com.apm.storage

import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity

/**
 * SQLite 存储优先级映射器。
 *
 * 存储层使用数值优先级执行读取排序和水位线淘汰，映射集中在此处可避免
 * SQLite 写入、读取和测试口径分叉。
 */
internal object StoragePriorityMapper {

    /**
     * 将事件严重级别映射为存储优先级。
     *
     * @param event 待存储事件。
     * @return 存储排序使用的优先级数值，数值越大越重要。
     */
    fun priorityOf(event: ApmEvent): Int {
        return when (event.severity) {
            ApmSeverity.FATAL -> PRIORITY_CRITICAL
            ApmSeverity.ERROR -> PRIORITY_CRITICAL
            ApmSeverity.WARN -> PRIORITY_WARN
            ApmSeverity.INFO -> PRIORITY_INFO
            ApmSeverity.DEBUG -> PRIORITY_DEBUG
        }
    }

    /** DEBUG 存储优先级。 */
    private const val PRIORITY_DEBUG = 0

    /** INFO 存储优先级。 */
    private const val PRIORITY_INFO = 1

    /** WARN 存储优先级。 */
    private const val PRIORITY_WARN = 2

    /** ERROR/FATAL 存储优先级。 */
    private const val PRIORITY_CRITICAL = 3
}
