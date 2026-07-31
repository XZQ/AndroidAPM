package com.apm.threadmonitor

import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.core.ApmExecutors
import com.apm.core.diagnostics.HostIntegrationPoint
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

    /** Background scheduler used for thread snapshots. */
    private var scheduler: ScheduledExecutorService? = null

    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false

    /** Host-registered executors that expose real queue backlog metrics. */
    private val threadPools = ThreadPoolRegistry()

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        if (!config.enableThreadMonitor) {
            return
        }
        @Suppress("DEPRECATION")
        if (config.enableThreadLeakDetect) {
            apmContext?.logger?.w("enableThreadLeakDetect is deprecated and ignored; register executors explicitly")
        }
        monitoring = true
        // 周期性线程快照采集非时间敏感，使用最低优先级后台执行。
        scheduler = ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME).apply {
            scheduleWithFixedDelay(
                { if (monitoring) checkThreads() },
                config.checkIntervalMs,
                config.checkIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }
        apmContext?.setHostIntegrationModuleActive(
            HostIntegrationPoint.THREAD_POOL,
            config.enableThreadPoolMonitor
        )
        apmContext?.setHostIntegrationActiveRegistrations(
            HostIntegrationPoint.THREAD_POOL,
            if (config.enableThreadPoolMonitor) threadPools.size() else 0
        )
        apmContext?.logger?.d("ThreadMonitor module started")
    }

    override fun onStop() {
        monitoring = false
        apmContext?.setHostIntegrationModuleActive(HostIntegrationPoint.THREAD_POOL, false)
        scheduler?.shutdownNow()
        scheduler = null
        threadPools.clear()
    }

    /**
     * Registers one host-owned executor for queue and saturation monitoring.
     * Registration does not change executor behavior or ownership.
     *
     * @param name stable name included in telemetry
     * @param executor host-owned executor
     */
    fun registerThreadPool(name: String, executor: ThreadPoolExecutor) {
        threadPools.register(name, executor)
        if (monitoring && config.enableThreadPoolMonitor) {
            apmContext?.setHostIntegrationActiveRegistrations(
                HostIntegrationPoint.THREAD_POOL,
                threadPools.size()
            )
        }
    }

    /**
     * Stops monitoring one previously registered executor.
     *
     * @param name registration name
     * @return true when a registration was removed
     */
    fun unregisterThreadPool(name: String): Boolean {
        val removed = threadPools.unregister(name)
        if (removed && monitoring && config.enableThreadPoolMonitor) {
            apmContext?.setHostIntegrationActiveRegistrations(
                HostIntegrationPoint.THREAD_POOL,
                threadPools.size()
            )
        }
        return removed
    }

    /**
     * 执行线程快照检测。
     * 扫描所有线程，统计数量和分组信息。
     */
    private fun checkThreads() {
        val threads = Thread.getAllStackTraces().keys.toList()
        val activeCount = threads.size

        // 线程数量告警
        if (activeCount >= config.threadCountThreshold) {
            reportThreadCountSpike(activeCount)
        }

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

        if (config.enableThreadPoolMonitor) {
            inspectRegisteredThreadPools()
        }
    }

    /** Reports real queue backlog for explicitly registered executors. */
    private fun inspectRegisteredThreadPools() {
        val snapshots = threadPools.snapshots()
        if (snapshots.isNotEmpty()) {
            apmContext?.recordHostIntegrationObservation(HostIntegrationPoint.THREAD_POOL)
        }
        for (snapshot in snapshots) {
            if (snapshot.queuedTasks >= config.queueBacklogThreshold) {
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_THREAD_POOL_BACKLOG,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.WARN,
                    priority = ApmPriority.LOW,
                    fields = mapOf(
                        FIELD_POOL_NAME to snapshot.name,
                        FIELD_QUEUE_SIZE to snapshot.queuedTasks,
                        FIELD_POOL_SIZE to snapshot.poolSize,
                        FIELD_ACTIVE_COUNT to snapshot.activeCount,
                        FIELD_MAX_POOL_SIZE to snapshot.maxPoolSize,
                        FIELD_COMPLETED_TASKS to snapshot.completedTaskCount,
                        FIELD_THRESHOLD to config.queueBacklogThreshold
                    )
                )
            }
        }
    }

    /** 上报线程数量膨胀。 */
    private fun reportThreadCountSpike(count: Int) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_THREAD_COUNT_SPIKE,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
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
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
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
            severity = ApmSeverity.ERROR, priority = ApmPriority.LOW,
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
        /** Registered executor queue backlog event. */
        private const val EVENT_THREAD_POOL_BACKLOG = "thread_pool_backlog"

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
        /** Field: registered pool name. */
        private const val FIELD_POOL_NAME = "poolName"
        /** Field: queued task count. */
        private const val FIELD_QUEUE_SIZE = "queueSize"
        /** Field: current worker count. */
        private const val FIELD_POOL_SIZE = "poolSize"
        /** Field: active worker count. */
        private const val FIELD_ACTIVE_COUNT = "activeCount"
        /** Field: configured maximum worker count. */
        private const val FIELD_MAX_POOL_SIZE = "maxPoolSize"
        /** Field: completed task count. */
        private const val FIELD_COMPLETED_TASKS = "completedTaskCount"

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"

        /** Background scanner thread name. */
        private const val THREAD_NAME = "apm-thread-monitor"
    }
}
