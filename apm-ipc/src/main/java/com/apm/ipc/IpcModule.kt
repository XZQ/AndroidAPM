package com.apm.ipc

import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmClock
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.core.diagnostics.HostIntegrationPoint
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority

/**
 * IPC/Binder 监控模块。
 * 监控跨进程调用的耗时，检测主线程 Binder 阻塞。
 *
 * 监控策略：
 * 1. 跟踪每次 Binder 调用的耗时
 * 2. 主线程 Binder 调用使用更严格的阈值
 * 3. 统计 Binder 调用频率
 *
 * 使用方式（外部回调）：
 * ```kotlin
 * Apm.init(this, ApmConfig()) {
 *     register(IpcModule())
 * }
 * // 在 ServiceManager / AIDL 调用前后
 * ipcModule.onBinderCallStart(interfaceName, methodName)
 * ipcModule.onBinderCallEnd(interfaceName, methodName, durationMs)
 * ```
 */
class IpcModule(private val config: IpcConfig = IpcConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /** Optional fixed-size call accumulator. */
    private val aggregator = if (config.enableBinderAggregation) {
        BinderCallAggregator(config.aggregationWindowSize.coerceAtLeast(1))
    } else {
        null
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableIpcMonitor
        apmContext?.setHostIntegrationModuleActive(HostIntegrationPoint.IPC, started)
        @Suppress("DEPRECATION")
        if (config.enableBinderHook) {
            apmContext?.logger?.w("enableBinderHook is deprecated and ignored; use traceBinderCall")
        }
        apmContext?.logger?.d("IPC module started, binderThreshold=${config.binderThresholdMs}ms")
    }

    override fun onStop() {
        started = false
        apmContext?.setHostIntegrationModuleActive(HostIntegrationPoint.IPC, false)
        aggregator?.reset()
    }

    /**
     * Measures one explicit AIDL or service call without reflection or hidden APIs.
     * The application result and exception semantics are preserved exactly.
     *
     * @param interfaceName Binder interface or service name
     * @param methodName invoked method name
     * @param block application call to execute
     * @return application result from [block]
     */
    fun <T> traceBinderCall(interfaceName: String, methodName: String, block: () -> T): T {
        val startedAtNanos = ApmClock.monotonicTimeNanos()
        try {
            return block()
        } finally {
            // Completion is recorded even when the application call throws;
            // the original exception continues to the caller.
            val durationMs = (ApmClock.monotonicTimeNanos() - startedAtNanos)
                .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
            onBinderCallComplete(interfaceName, methodName, durationMs)
        }
    }

    /**
     * Binder 调用完成时记录。
     *
     * @param interfaceName Binder 接口名（如 IServiceManager）
     * @param methodName 方法名（如 getService）
     * @param durationMs 调用耗时（毫秒）
     */
    fun onBinderCallComplete(interfaceName: String, methodName: String, durationMs: Long) {
        if (!started) {
            return
        }
        apmContext?.recordHostIntegrationObservation(HostIntegrationPoint.IPC)

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val threshold = if (isMainThread) config.mainThreadBinderThresholdMs else config.binderThresholdMs

        val aggregation = aggregator?.record(durationMs, durationMs >= threshold)
        if (aggregation != null) {
            reportAggregation(aggregation)
        }

        if (durationMs < threshold) {
            return
        }

        val fields = mutableMapOf<String, Any?>(
            FIELD_INTERFACE to interfaceName,
            FIELD_METHOD to methodName,
            FIELD_DURATION_MS to durationMs,
            FIELD_IS_MAIN_THREAD to isMainThread,
            FIELD_THRESHOLD to threshold
        )

        // 抓取堆栈
        val stackTrace = Thread.currentThread().stackTrace
            .joinToString(LINE_SEPARATOR)
            .take(config.maxStackTraceLength)
        fields[FIELD_STACK_TRACE] = stackTrace

        val severity = if (isMainThread) ApmSeverity.ERROR else ApmSeverity.WARN

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_BINDER,
            kind = ApmEventKind.ALERT,
            severity = severity, priority = ApmPriority.NORMAL,
            fields = fields
        )
    }

    /** Emits one completed Binder aggregation window. */
    private fun reportAggregation(snapshot: BinderAggregationSnapshot) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_BINDER_AGGREGATION,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_CALL_COUNT to snapshot.callCount,
                FIELD_TOTAL_DURATION_MS to snapshot.totalDurationMs,
                FIELD_AVERAGE_DURATION_MS to snapshot.totalDurationMs / snapshot.callCount,
                FIELD_MAX_DURATION_MS to snapshot.maxDurationMs,
                FIELD_SLOW_CALL_COUNT to snapshot.slowCallCount
            )
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "ipc"

        /** 慢 Binder 调用事件。 */
        private const val EVENT_SLOW_BINDER = "slow_binder_call"
        /** Completed Binder aggregation window event. */
        private const val EVENT_BINDER_AGGREGATION = "binder_call_aggregation"

        /** 字段：接口名。 */
        private const val FIELD_INTERFACE = "interfaceName"

        /** 字段：方法名。 */
        private const val FIELD_METHOD = "methodName"

        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"

        /** 字段：是否主线程。 */
        private const val FIELD_IS_MAIN_THREAD = "isMainThread"

        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"

        /** 字段：堆栈。 */
        private const val FIELD_STACK_TRACE = "stackTrace"
        /** Field: calls retained in an aggregation window. */
        private const val FIELD_CALL_COUNT = "callCount"
        /** Field: summed call latency. */
        private const val FIELD_TOTAL_DURATION_MS = "totalDurationMs"
        /** Field: average call latency. */
        private const val FIELD_AVERAGE_DURATION_MS = "averageDurationMs"
        /** Field: maximum call latency. */
        private const val FIELD_MAX_DURATION_MS = "maxDurationMs"
        /** Field: calls that crossed the applicable threshold. */
        private const val FIELD_SLOW_CALL_COUNT = "slowCallCount"

        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
        /** Nanoseconds contained in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
