package com.apm.slowmethod

import android.os.Looper
import android.util.Printer
import com.apm.core.Apm
import com.apm.core.ApmClock
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 慢方法检测模块。
 * 通过 hook 主线程 Looper 的 Message 日志检测慢方法，
 * 并在慢消息仍在执行时由后台采样线程合并抓取阻塞现场堆栈与触发热点采样。
 *
 * 双层检测：
 * 1. Looper.mLogging 包装 — 检测 Message 级别总耗时，并在超时瞬间捕获真实阻塞栈（而非 Finished 事后栈）
 * 2. StackSamplingProfiler 触发式采样 — 在阻塞执行期立即启动采样定位热点方法，空闲时零定时器唤醒
 */
class SlowMethodModule(private val config: SlowMethodConfig = SlowMethodConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 原始的 Printer，hook 前保存。 */
    private var originPrinter: Printer? = null
    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false
    /** 主线程分发单调递增序号（仅在主线程递增）。 */
    private var dispatchSequence: Long = IDLE_DISPATCH_SEQ
    /** 当前活跃消息分发序号；0 表示主线程当前空闲（避免依赖可能为负的 nanoTime 起点作哨兵）。 */
    @Volatile
    private var activeDispatchSeq: Long = IDLE_DISPATCH_SEQ
    /** 当前活跃消息开始处理的单调时间戳（毫秒）。 */
    @Volatile
    private var dispatchStartTime: Long = 0L
    /** 最近一次已在执行期完成现场抓栈/触发采样的分发序号。 */
    @Volatile
    private var capturedDispatchSeq: Long = IDLE_DISPATCH_SEQ
    /** 最近一次在执行期抓取到的主线程阻塞堆栈快照。 */
    @Volatile
    private var capturedInFlightStack: String? = null
    /** 粗粒度合并看门狗是否已处于调度中，避免高频短消息逐条竞争 Handler 锁。 */
    private val watchdogArmed = AtomicBoolean(false)
    /** Restartable stack sampling profiler. */
    private var samplingProfiler: StackSamplingProfiler? = null

    /** Looper 的 mLogging 字段（反射获取）。 */
    private val loggingField by lazy {
        Looper::class.java.getDeclaredField("mLogging").apply {
            isAccessible = true
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /**
     * Hook 主线程 Looper 的 Printer。
     * 通过反射设置 mLogging 字段。
     */
    @Synchronized
    override fun onStart() {
        if (monitoring || !config.enableSlowMethod) {
            return
        }
        monitoring = true
        activeDispatchSeq = IDLE_DISPATCH_SEQ
        capturedDispatchSeq = IDLE_DISPATCH_SEQ
        capturedInFlightStack = null
        watchdogArmed.set(false)
        // Reflection is used only to preserve an existing logger; installation
        // itself uses the supported public API.
        originPrinter = runCatching {
            loggingField.get(Looper.getMainLooper()) as? Printer
        }.getOrNull()?.takeUnless { it === looperPrinter }
        samplingProfiler?.destroy()
        // 仅在启用现场堆栈或栈采样时创建后台采样线程，纯耗时模式保持零额外线程
        samplingProfiler = if (config.includeStackTrace || config.enableStackSampling) {
            StackSamplingProfiler(config).also { profiler ->
                profiler.onSamplingComplete = { topMethods, sampleCount ->
                    onSamplingResult(topMethods, sampleCount)
                }
            }
        } else {
            null
        }
        Looper.getMainLooper().setMessageLogging(looperPrinter)
        // 初始化 ASM 插桩 Tracer
        ApmSlowMethodTracer.init(config.thresholdMs)
        apmContext?.logger?.d("SlowMethod module started, threshold=${config.thresholdMs}ms, sampling=${config.enableStackSampling}")
    }

    /** 恢复原始 Printer，释放采样器与合并看门狗。 */
    @Synchronized
    override fun onStop() {
        if (!monitoring) {
            return
        }
        monitoring = false
        activeDispatchSeq = IDLE_DISPATCH_SEQ
        capturedDispatchSeq = IDLE_DISPATCH_SEQ
        capturedInFlightStack = null
        // 禁用 ASM Tracer
        ApmSlowMethodTracer.disable()
        // 恢复原始 Printer
        Looper.getMainLooper().setMessageLogging(originPrinter)
        originPrinter = null
        samplingProfiler?.removeWatchdog(watchdogRunnable)
        watchdogArmed.set(false)
        samplingProfiler?.destroy()
        samplingProfiler = null
    }

    /** 计算看门狗单次等待延迟，至少 1ms 避免零延迟自旋。 */
    private fun watchdogDelayMs(): Long = config.thresholdMs.coerceAtLeast(MIN_WATCHDOG_DELAY_MS)

    /**
     * 运行在采样线程上的粗粒度合并看门狗：
     * - 主线程空闲时自动解除武装，静态屏幕保持零唤醒；
     * - 主线程单次消息阻塞达到阈值时，在阻塞现场抓取主线程真实调用栈并立即启动热点采样。
     */
    private val watchdogRunnable: Runnable = object : Runnable {
        override fun run() {
            val profiler = samplingProfiler
            val seq = activeDispatchSeq
            val startedAtMs = dispatchStartTime
            val confirmSeq = activeDispatchSeq
            // 模块停止或主线程已空闲时解除武装，避免空闲轮询唤醒 CPU
            if (!monitoring || profiler == null || seq == IDLE_DISPATCH_SEQ || confirmSeq == IDLE_DISPATCH_SEQ) {
                watchdogArmed.set(false)
                // 二次校验消除解除武装瞬间恰好进入新消息的竞态
                if (monitoring && activeDispatchSeq != IDLE_DISPATCH_SEQ && watchdogArmed.compareAndSet(false, true)) {
                    if (!profilerOrNullPostWatchdog(watchdogDelayMs(), this)) {
                        watchdogArmed.set(false)
                    }
                }
                return
            }
            // 读取期间切换到了更新的消息，按完整阈值窗口延后复检
            if (confirmSeq != seq) {
                if (!profiler.postWatchdog(watchdogDelayMs(), this)) {
                    watchdogArmed.set(false)
                }
                return
            }

            val elapsed = ApmClock.elapsedMillisSince(startedAtMs)
            if (elapsed >= config.thresholdMs) {
                // 同一条慢消息只抓取一次阻塞现场栈并立即启动栈采样
                if (capturedDispatchSeq != seq) {
                    val stack = if (config.includeStackTrace) captureMainThreadStack() else null
                    // 抓栈完成后再次确认主线程仍处于同一条消息内，绝不把消息结束后的 Looper.loop 栈误归因给该消息
                    if (activeDispatchSeq == seq) {
                        capturedInFlightStack = stack
                        capturedDispatchSeq = seq
                        if (config.enableStackSampling) {
                            profiler.startSampling()
                        }
                    }
                }
                if (!profiler.postWatchdog(watchdogDelayMs(), this)) {
                    watchdogArmed.set(false)
                }
            } else {
                // 当前消息尚未超时，精确等待剩余阈值时间后再检查
                val remainingMs = (config.thresholdMs - elapsed).coerceAtLeast(MIN_WATCHDOG_DELAY_MS)
                if (!profiler.postWatchdog(remainingMs, this)) {
                    watchdogArmed.set(false)
                }
            }
        }
    }

    /** 安全向当前存活的采样器投递看门狗任务。 */
    private fun profilerOrNullPostWatchdog(delayMs: Long, task: Runnable): Boolean {
        return samplingProfiler?.postWatchdog(delayMs, task) == true
    }

    /**
     * 自定义 Printer，拦截主线程 Looper 消息分发日志。
     * 高频短消息仅更新单调时间戳与序号；每个阈值窗口最多投递一次后台看门狗。
     */
    private val looperPrinter: Printer = object : Printer {
        /**
         * Forwards the log to the previous printer before collecting timing.
         */
        override fun println(log: String) {
            originPrinter?.takeUnless { it === this }?.println(log)
            if (!monitoring) {
                return
            }

            if (log.startsWith(DISPATCH_PREFIX)) {
                // 先写开始时间再发布递增序号，保证后台线程读到序号时已能看见对应时间戳
                val seq = ++dispatchSequence
                dispatchStartTime = ApmClock.monotonicTimeMillis()
                activeDispatchSeq = seq
                // 仅在看门狗未武装时投递一次，避免逐消息加锁操作 Handler 队列
                if (samplingProfiler != null && !watchdogArmed.get() && watchdogArmed.compareAndSet(false, true)) {
                    if (!profilerOrNullPostWatchdog(watchdogDelayMs(), watchdogRunnable)) {
                        watchdogArmed.set(false)
                    }
                }
            } else if (log.startsWith(FINISH_PREFIX)) {
                val seq = activeDispatchSeq
                if (seq == IDLE_DISPATCH_SEQ) {
                    return
                }
                val startedAtMs = dispatchStartTime
                // 立即标记空闲，防止后台线程在消息已结束后抓取无效堆栈
                activeDispatchSeq = IDLE_DISPATCH_SEQ
                val duration = ApmClock.elapsedMillisSince(startedAtMs)

                // 超过阈值则上报，并附带后台看门狗在阻塞期间捕获到的真实堆栈
                if (duration >= config.thresholdMs) {
                    val inFlightStack = if (capturedDispatchSeq == seq) capturedInFlightStack else null
                    capturedInFlightStack = null
                    reportSlowMethod(duration, log, inFlightStack)
                    // 兜底：若系统线程调度延迟导致看门狗尚未在执行期触发采样，仍在慢分发确认时触发窗口采样
                    if (config.enableStackSampling && capturedDispatchSeq != seq) {
                        samplingProfiler?.startSampling()
                    }
                }
            }
        }
    }

    /**
     * 上报慢方法事件。
     * 仅附加在消息执行期抓取到的真实主线程阻塞堆栈，不把消息结束后的 Looper.loop 空栈混入字段。
     */
    private fun reportSlowMethod(durationMs: Long, logMsg: String, inFlightStack: String?) {
        val fields = mutableMapOf<String, Any?>(
            FIELD_DURATION_MS to durationMs,
            FIELD_IS_SEVERE to (durationMs >= config.severeThresholdMs),
            FIELD_LOOPER_MSG to logMsg.take(FIELD_MSG_MAX_LENGTH)
        )

        // 附加阻塞执行期捕获到的真实主线程堆栈
        if (config.includeStackTrace && !inFlightStack.isNullOrEmpty()) {
            fields[FIELD_STACK_TRACE] = inFlightStack
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
        if (topMethods.isEmpty()) {
            return
        }

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
        /** 空闲状态下的消息分发序号哨兵。 */
        private const val IDLE_DISPATCH_SEQ = 0L
        /** 看门狗最短调度间隔（毫秒）。 */
        private const val MIN_WATCHDOG_DELAY_MS = 1L
    }
}
