package com.apm.threadmonitor

import android.os.Handler
import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity

/**
 * 线程监控模块。
 * 定期扫描所有线程，检测线程数量膨胀、同名线程泄漏、死锁等异常。
 *
 * 监控策略：
 * 1. 定期获取所有线程快照
 * 2. 统计线程总数，超过阈值告警
 * 3. 按 thread name 分组，同名线程过多告警（可能泄漏）
 * 4. 检测 BLOCKED 状态线程（可能死锁）
 */
class ThreadMonitorModule(private val config: ThreadMonitorConfig = ThreadMonitorConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    /** 定时检测 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false

    /** 定时检测任务。 */
    private val checkTask = object : Runnable {
        override fun run() {
            if (!monitoring) return
            checkThreads()
            mainHandler.postDelayed(this, config.checkIntervalMs)
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        if (!config.enableThreadMonitor) {
            return
        }
        monitoring = true
        // 延迟一个周期后开始首次检测
        mainHandler.postDelayed(checkTask, config.checkIntervalMs)
        apmContext?.logger?.d("ThreadMonitor module started")
    }

    override fun onStop() {
        monitoring = false
        mainHandler.removeCallbacks(checkTask)
    }

    /**
     * 执行线程快照检测。
     * 扫描所有线程，统计数量和分组信息。
     */
    private fun checkThreads() {
        val threadGroup = Thread.currentThread().threadGroup
        val activeCount = threadGroup?.activeCount() ?: 0

        // 线程数量告警
        if (activeCount >= config.threadCountThreshold) {
            reportThreadCountSpike(activeCount)
        }

        // 获取所有线程快照
        val threads = mutableListOf<Thread>()
        threadGroup?.enumerate(threads.toTypedArray(), true)

        // 按 thread name 分组统计
        val nameGroups = threads.groupingBy { it.name.orEmpty() }.eachCount()

        // 检测同名线程泄漏
        for ((name, count) in nameGroups) {
            if (count >= config.duplicateThreadThreshold) {
                reportDuplicateThread(name, count)
            }
        }

        // 检测 BLOCKED 线程（可能死锁）
        val blockedThreads = threads.filter { it.state == Thread.State.BLOCKED }
        if (blockedThreads.isNotEmpty()) {
            reportBlockedThreads(blockedThreads)
        }
    }

    /** 上报线程数量膨胀。 */
    private fun reportThreadCountSpike(count: Int) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_THREAD_COUNT_SPIKE,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            fields = mapOf(
                FIELD_THREAD_COUNT to count,
                FIELD_THRESHOLD to config.threadCountThreshold
            )
        )
    }

    /** 上报同名线程泄漏。 */
    private fun reportDuplicateThread(name: String, count: Int) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_DUPLICATE_THREAD,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            fields = mapOf(
                FIELD_THREAD_NAME to name,
                FIELD_THREAD_COUNT to count,
                FIELD_THRESHOLD to config.duplicateThreadThreshold
            )
        )
    }

    /** 上报 BLOCKED 线程。 */
    private fun reportBlockedThreads(blockedThreads: List<Thread>) {
        val threadInfos = blockedThreads.joinToString(LINE_SEPARATOR) { thread ->
            val stack = thread.stackTrace.joinToString(LINE_SEPARATOR)
                .take(config.maxStackTraceLength)
            "${thread.name}(${thread.state}): $stack"
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_BLOCKED_THREAD,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = mapOf(
                FIELD_BLOCKED_COUNT to blockedThreads.size,
                FIELD_THREAD_INFO to threadInfos.take(config.maxStackTraceLength)
            )
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "thread_monitor"

        /** 线程数量膨胀事件。 */
        private const val EVENT_THREAD_COUNT_SPIKE = "thread_count_spike"

        /** 同名线程泄漏事件。 */
        private const val EVENT_DUPLICATE_THREAD = "duplicate_thread"

        /** BLOCKED 线程事件。 */
        private const val EVENT_BLOCKED_THREAD = "blocked_thread"

        /** 字段：线程数。 */
        private const val FIELD_THREAD_COUNT = "threadCount"

        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"

        /** 字段：线程名。 */
        private const val FIELD_THREAD_NAME = "threadName"

        /** 字段：BLOCKED 数量。 */
        private const val FIELD_BLOCKED_COUNT = "blockedCount"

        /** 字段：线程信息。 */
        private const val FIELD_THREAD_INFO = "threadInfo"

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
    }
}
