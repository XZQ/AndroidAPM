package com.apm.storage

import com.apm.model.ApmEvent

/**
 * 事件存储接口。
 * 提供追加、读取、清除三种操作。
 * 实现类需保证线程安全。
 */
interface EventStore {
    /**
     * 追加一条事件到存储。
     * @param event 要存储的事件
     */
    fun append(event: ApmEvent)

    /**
     * 读取最近的事件。
     * @param limit 最大条数
     * @return line protocol 格式的字符串列表，最新在前
     */
    fun readRecent(limit: Int = 20): List<String>

    /** 清除所有已存储的事件。 */
    fun clear()
}
