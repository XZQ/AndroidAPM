package com.didi.apm.anr

import android.os.Handler
import android.os.Looper
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ANR 监控模块。
 * 使用 Watchdog 线程检测主线程阻塞：
 * 1. 定期向主线程 Handler 投递一个 tick 标记
 * 2. 等待 checkIntervalMs 后检查标记是否被消费
 * 3. 未被消费则判定主线程阻塞，抓取堆栈上报
 */
class AnrModule(
    /** 模块配置。 */
    private val config: AnrConfig = AnrConfig()
) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 主线程 Handler，用于投递 tick。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** ANR 状态标记：已检测到 ANR 且尚未恢复。 */
    private val anrDetected = AtomicBoolean(false)

    /** Watchdog 线程的 tick 标记：true 表示主线程已响应。 */
    private val tick = AtomicBoolean(false)
    /** 是否正在运行。 */
    @Volatile private var running = false
    /** Watchdog 线程引用。 */
    private var watchdogThread: Thread? = null

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /** 启动 Watchdog 线程。 */
    override fun onStart() {
        if (!config.enableAnrMonitor) return
        running = true
        anrDetected.set(false)
        watchdogThread = Thread({ watchdogLoop() }, THREAD_NAME).apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
        apmContext?.logger?.d("ANR module started")
    }

    /** 停止 Watchdog 线程。 */
    override fun onStop() {
        running = false
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /**
     * Watchdog 主循环。
     * 每轮：投递 tick → 等待 → 检查 tick 是否被消费。
     * 未消费则抓取主线程堆栈上报。
     */
    private fun watchdogLoop() {
        while (running) {
            // 重置 tick 标记
            tick.set(false)
            // 向主线程投递 tick，主线程消费后设为 true
            mainHandler.post { tick.set(true) }

            // 等待一个检查周期
            try {
                Thread.sleep(config.checkIntervalMs)
            } catch (_: InterruptedException) {
                break
            }

            if (!running) break

            // tick 未被消费且尚未报告过 ANR
            if (!tick.get() && !anrDetected.getAndSet(true)) {
                // 主线程阻塞，抓取堆栈
                val stackTrace = captureMainThreadStack()
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_ANR_DETECTED,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.ERROR,
                    fields = mapOf(
                        FIELD_MAIN_THREAD_STACK to stackTrace,
                        FIELD_ANR_DURATION_MS to config.checkIntervalMs,
                        FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
                    )
                )
                apmContext?.logger?.w("ANR detected: main thread blocked >${config.checkIntervalMs}ms")
            }

            // tick 已被消费，重置 ANR 状态
            if (tick.get()) {
                anrDetected.set(false)
            }
        }
    }

    /** 抓取主线程堆栈，截断到最大长度。 */
    private fun captureMainThreadStack(): String {
        val mainThread = Looper.getMainLooper().thread
        val trace = mainThread.stackTrace
        return trace.joinToString(LINE_SEPARATOR).take(config.maxStackTraceLength)
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "anr"
        /** Watchdog 线程名。 */
        private const val THREAD_NAME = "apm-anr-watchdog"
        /** ANR 检测事件名。 */
        private const val EVENT_ANR_DETECTED = "anr_detected"
        /** 字段：主线程堆栈。 */
        private const val FIELD_MAIN_THREAD_STACK = "mainThreadStack"
        /** 字段：ANR 持续时长。 */
        private const val FIELD_ANR_DURATION_MS = "anrDurationMs"
        /** 字段：进程名。 */
        private const val FIELD_PROCESS_NAME = "processName"
        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
    }
}
