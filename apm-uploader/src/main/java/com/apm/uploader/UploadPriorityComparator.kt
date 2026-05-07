package com.apm.uploader

import com.apm.model.ApmEvent

/**
 * 上传队列排序器。
 *
 * 优先级越高越先上传；同优先级时较新的事件先上传，减少关键新事件在
 * 大量旧事件后排队的等待时间。
 */
internal object UploadPriorityComparator : Comparator<ApmEvent> {

    /**
     * 比较两个待上传事件。
     *
     * @param left 左侧事件。
     * @param right 右侧事件。
     * @return 排序比较结果。
     */
    override fun compare(left: ApmEvent, right: ApmEvent): Int {
        val priorityCompare = right.priority.value.compareTo(left.priority.value)
        if (priorityCompare != 0) {
            return priorityCompare
        }
        // 同优先级时较新的事件优先处理。
        return right.timestamp.compareTo(left.timestamp)
    }
}
