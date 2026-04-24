package com.apm.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级会话标识。
 * 每个 APM 初始化时生成唯一的 sessionId，用于：
 * - 区分同一进程不同启动阶段的事件
 * - 跨进程事件归属关联
 *
 * 线程安全：sessionId 在初始化时确定，之后只读。
 */
object ProcessSessionId {

    /** 进程序列号，每次 JVM 启动递增（用于区分同进程多次启动）。 */
    private val processSequence = AtomicLong(0)

    /** 当前进程的会话 ID。格式：{processSeq}_{uuid_prefix}。 */
    private val sessionId: String = generateSessionId()

    /**
     * 获取当前进程的会话 ID。
     *
     * @return 会话 ID 字符串
     */
    fun get(): String = sessionId

    /**
     * 生成会话 ID。
     * 格式：{递增序号}_{UUID 前 8 位}，保证全局唯一且可排序。
     *
     * @return 格式化的会话 ID
     */
    private fun generateSessionId(): String {
        val seq = processSequence.incrementAndGet()
        // 取 UUID 前 8 位，兼顾唯一性和可读性
        val uuidPrefix = UUID.randomUUID().toString().substring(UUID_PREFIX_START, UUID_PREFIX_END)
        return "${seq}_${uuidPrefix}"
    }

    /** UUID 前 8 位的起始索引。 */
    private const val UUID_PREFIX_START = 0
    /** UUID 前 8 位的结束索引。 */
    private const val UUID_PREFIX_END = 8
}
