package com.didi.apm.ipc

import android.os.Looper
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity

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
class IpcModule(
    /** 模块配置。 */
    private val config: IpcConfig = IpcConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 是否已启动。 */
    @Volatile
    private var started = false

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableIpcMonitor
        apmContext?.logger?.d("IPC module started, binderThreshold=${config.binderThresholdMs}ms")
    }

    override fun onStop() {
        started = false
    }

    /**
     * Binder 调用完成时记录。
     *
     * @param interfaceName Binder 接口名（如 IServiceManager）
     * @param methodName 方法名（如 getService）
     * @param durationMs 调用耗时（毫秒）
     */
    fun onBinderCallComplete(
        interfaceName: String,
        methodName: String,
        durationMs: Long
    ) {
        if (!started) return

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val threshold = if (isMainThread) config.mainThreadBinderThresholdMs else config.binderThresholdMs

        if (durationMs < threshold) return

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
            severity = severity,
            fields = fields
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "ipc"
        /** 慢 Binder 调用事件。 */
        private const val EVENT_SLOW_BINDER = "slow_binder_call"
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
        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
    }
}
