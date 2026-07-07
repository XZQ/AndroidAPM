package com.apm.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory

/**
 * SDK 共享线程设施。
 *
 * 统一各组件创建线程的方式：
 * - 线程名带 "apm-" 前缀，便于 systrace/线程 dump 中定位 SDK 线程
 * - 全部为 daemon 线程，绝不阻止宿主进程退出
 * - 后台任务默认使用最低优先级，减少与宿主主流程的 CPU 竞争
 *
 * 各监控模块与核心组件应优先使用本工具而非裸 new Thread()/Executors。
 */
object ApmExecutors {

    /** SDK 线程名统一前缀。 */
    private const val THREAD_NAME_PREFIX = "apm-"

    /**
     * 创建命名线程工厂。
     *
     * @param name 线程名（无 "apm-" 前缀时自动补齐）
     * @param priority 线程优先级，默认最低优先级
     * @return daemon 线程工厂
     */
    fun threadFactory(
        name: String,
        priority: Int = Thread.MIN_PRIORITY
    ): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, normalizeName(name)).apply {
            // daemon + 低优先级：监控线程不阻止退出、不与宿主抢 CPU
            isDaemon = true
            this.priority = priority
        }
    }

    /**
     * 创建单线程串行执行器。
     *
     * @param name 线程名
     * @param priority 线程优先级
     * @return 单线程执行器
     */
    fun newSingleThreadExecutor(
        name: String,
        priority: Int = Thread.MIN_PRIORITY
    ): ExecutorService = Executors.newSingleThreadExecutor(threadFactory(name, priority))

    /**
     * 创建单线程定时执行器。
     *
     * @param name 线程名
     * @param priority 线程优先级
     * @return 单线程定时执行器
     */
    fun newSingleThreadScheduledExecutor(
        name: String,
        priority: Int = Thread.MIN_PRIORITY
    ): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(threadFactory(name, priority))

    /**
     * 创建并启动一个命名 daemon 线程。
     *
     * @param name 线程名
     * @param priority 线程优先级
     * @param block 线程体
     * @return 已启动的线程
     */
    fun startThread(
        name: String,
        priority: Int = Thread.MIN_PRIORITY,
        block: Runnable
    ): Thread = Thread(block, normalizeName(name)).apply {
        isDaemon = true
        this.priority = priority
        start()
    }

    /**
     * 补齐线程名前缀。
     *
     * @param name 原始线程名
     * @return 带 "apm-" 前缀的线程名
     */
    private fun normalizeName(name: String): String {
        // 已带前缀的名称原样保留
        return if (name.startsWith(THREAD_NAME_PREFIX)) name else THREAD_NAME_PREFIX + name
    }
}
