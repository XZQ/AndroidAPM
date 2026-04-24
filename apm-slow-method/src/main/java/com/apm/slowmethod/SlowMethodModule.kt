package com.apm.slowmethod

import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority

/**
 * 慢方法检测模块。
 * 通过 hook 主线程 Looper 的 Message 日志检测慢方法，
 * 并在检测到慢方法时触发栈采样，精确定位热点方法。
 *
 * 双层检测：
 * 1. Looper.mLogging 反射 hook — 检测 Message 级别总耗时
 * 2. StackSamplingProfiler 触发式采样 — 定位具体热点方法（折中方案）
 */
class SlowMethodModule(
    /** 模块配置。 */
    private val config: SlowMethodConfig = SlowMethodConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 原始的 Printer，hook 前保存。 */
    private var originPrinter: Printer? = null
    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false
    /** 当前消息开始处理的时间戳。 */
    @Volatile
    private var dispatchStartTime: Long = 0L
    /** 栈采样分析器。 */
    private val samplingProfiler = StackSamplingProfiler(config)

    /** Looper 的 mLogging 字段（反射获取）。 */
    private val loggingField by lazy {
        Looper::class.java.getDeclaredField("mLogging").apply {
            isAccessible = true
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
        // 设置栈采样完成回调
        samplingProfiler.onSamplingComplete = { topMethods, sampleCount ->
            onSamplingResult(topMethods, sampleCount)
        }
    }

    /**
     * Hook 主线程 Looper 的 Printer。
     * 通过反射设置 mLogging 字段。
     */
    override fun onStart() {
        if (!config.enableSlowMethod) return
        monitoring = true
        // 保存原始 Printer
        originPrinter = loggingField.get(Looper.getMainLooper()) as? Printer
        // 设置自定义 Printer 监控消息分发
        loggingField.set(Looper.getMainLooper(), looperPrinter)
        // 初始化 ASM 插桩 Tracer
        ApmSlowMethodTracer.init(config.thresholdMs)
        apmContext?.logger?.d("SlowMethod module started, threshold=${config.thresholdMs}ms, sampling=${config.enableStackSampling}")
    }

    /** 恢复原始 Printer，释放采样器。 */
    override fun onStop() {
        monitoring = false
        // 禁用 ASM Tracer
        ApmSlowMethodTracer.disable()
        // 恢复原始 Printer
        val current = loggingField.get(Looper.getMainLooper())
        if (current === looperPrinter) {
            loggingField.set(Looper.getMainLooper(), originPrinter)
        }
        samplingProfiler.destroy()
    }

    /**
     * 自定义 Printer，拦截主线程 Looper 消息分发日志。
     * 检测到慢方法时触发栈采样。
     */
    private val looperPrinter = Printer { log ->
        if (!monitoring) return@Printer

        if (log.startsWith(DISPATCH_PREFIX)) {
            // 消息开始分发，记录开始时间
            dispatchStartTime = SystemClock.uptimeMillis()
        } else if (log.startsWith(FINISH_PREFIX)) {
            // 消息分发完成，计算耗时
            if (dispatchStartTime <= 0L) return@Printer
            val duration = SystemClock.uptimeMillis() - dispatchStartTime
            dispatchStartTime = 0L

            // 超过阈值则上报
            if (duration >= config.thresholdMs) {
                reportSlowMethod(duration, log)
                // 触发式启动栈采样，捕获后续热点方法
                if (config.enableStackSampling) {
                    samplingProfiler.startSampling()
                }
            }
        }
    }

    /**
     * 上报慢方法事件。
     * 抓取主线程堆栈并构造 APM 事件。
     */
    private fun reportSlowMethod(durationMs: Long, logMsg: String) {
        val fields = mutableMapOf<String, Any?>(
            FIELD_DURATION_MS to durationMs,
            FIELD_IS_SEVERE to (durationMs >= config.severeThresholdMs),
            FIELD_LOOPER_MSG to logMsg.take(FIELD_MSG_MAX_LENGTH)
        )

        // 抓取主线程堆栈
        if (config.includeStackTrace) {
            val stackTrace = captureMainThreadStack()
            fields[FIELD_STACK_TRACE] = stackTrace
        }

        val severity = if (durationMs >= config.severeThresholdMs) {
            ApmSeverity.ERROR
        } else {
            ApmSeverity.WARN
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_METHOD,
            kind = ApmEventKind.ALERT,
            severity = severity, priority = ApmPriority.NORMAL,
            fields = fields
        )
        apmContext?.logger?.d("Slow method detected: ${durationMs}ms")
    }

    /**
     * 栈采样完成回调。
     * 将热点方法附加到 APM 事件中上报。
     */
    private fun onSamplingResult(topMethods: List<StackSamplingProfiler.MethodSample>, sampleCount: Int) {
        if (topMethods.isEmpty()) return

        // 构造热点方法摘要
        val methodsSummary = topMethods.joinToString(LINE_SEPARATOR) { sample ->
            "${sample.methodSignature} (hit: ${sample.hitCount}/${sampleCount})"
        }

        val fields = mutableMapOf<String, Any?>(
            FIELD_TOP_METHODS to methodsSummary,
            FIELD_SAMPLE_COUNT to sampleCount,
            FIELD_METHOD_COUNT to topMethods.size
        )

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_HOT_METHODS,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO, priority = ApmPriority.NORMAL,
            fields = fields
        )
        apmContext?.logger?.d("Stack sampling complete: ${topMethods.size} hot methods in ${sampleCount} samples")
    }

    /** 抓取主线程堆栈，截断到最大长度。 */
    private fun captureMainThreadStack(): String {
        val mainThread = Looper.getMainLooper().thread
        val trace = mainThread.stackTrace
        return trace.joinToString(LINE_SEPARATOR).take(config.maxStackTraceLength)
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "slow_method"
        /** 模块名引用（供 ApmSlowMethodTracer 使用）。 */
        const val MODULE_NAME_REF = "slow_method"
        /** 慢方法检测事件名。 */
        private const val EVENT_SLOW_METHOD = "slow_method_detected"
        /** 热点方法事件名。 */
        private const val EVENT_HOT_METHODS = "hot_methods_detected"
        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"
        /** 字段：是否严重。 */
        private const val FIELD_IS_SEVERE = "isSevere"
        /** 字段：Looper 消息摘要。 */
        private const val FIELD_LOOPER_MSG = "looperMsg"
        /** 字段：主线程堆栈。 */
        private const val FIELD_STACK_TRACE = "mainThreadStack"
        /** 字段：热点方法列表。 */
        private const val FIELD_TOP_METHODS = "topMethods"
        /** 字段：采样次数。 */
        private const val FIELD_SAMPLE_COUNT = "sampleCount"
        /** 字段：热点方法数量。 */
        private const val FIELD_METHOD_COUNT = "methodCount"
        /** Looper 分发开始日志前缀。 */
        private const val DISPATCH_PREFIX = ">>>>> Dispatching to"
        /** Looper 分发结束日志前缀。 */
        private const val FINISH_PREFIX = "<<<<< Finished to"
        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
        /** 消息字段最大长度。 */
        private const val FIELD_MSG_MAX_LENGTH = 200
    }
}
