package com.apm.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import com.apm.model.SerializationFormat
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes one crash hand-off without breaking the host's uncaught-exception chain.
 *
 * Recoverable telemetry failures are reported once. Fatal VM errors stay visible, but the
 * original handler still runs from [finally] before such an error propagates.
 */
internal inline fun executeCriticalCrashHandoff(
    criticalHandoff: () -> Boolean,
    onFailure: (Exception?) -> Unit,
    delegate: () -> Unit
): Boolean {
    var handedOff = false
    var recoverableFailure: Exception? = null
    try {
        try {
            handedOff = criticalHandoff()
        } catch (error: Exception) {
            recoverableFailure = error
        }
        if (!handedOff) {
            onFailure(recoverableFailure)
        }
        return handedOff
    } finally {
        delegate()
    }
}

/**
 * 崩溃监控模块。
 * 通过 [Thread.setDefaultUncaughtExceptionHandler] 捕获 Java 层未处理异常，
 * 将崩溃信息上报到 APM 管道，然后委托给原始 Handler 保证现有崩溃处理不受影响。
 */
class CrashModule(private val config: CrashConfig = CrashConfig()) : ApmModule {

    /**
     * Allows hosts that reserve Android's process-state summary slot to disable SDK writes.
     * Existing constructors remain unchanged; history without an SDK identity stays unattributed.
     */
    constructor(config: CrashConfig, writeExitIdentitySummary: Boolean) : this(config) {
        this.writeExitIdentitySummary = writeExitIdentitySummary
    }

    /** Frozen constructor option; V3 exit collection owns the slot unless explicitly disabled. */
    private var writeExitIdentitySummary = true
    /** Whether this session wrote the process-state summary and must clear it on stop/revoke. */
    private var wroteExitIdentitySummary = false
    /** Cancels late collector callbacks across stop/reinitialize without using the global Apm emitter. */
    private val exitSession = AtomicLong()
    /** Current background collector, retained only until stop for cooperative cancellation. */
    private var exitCollectorThread: Thread? = null

    override val name: String = MODULE_NAME

    /** 原始的 UncaughtExceptionHandler，崩溃上报后委托给它。 */
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /**
     * 注册自定义 UncaughtExceptionHandler。
     * 保存原始 handler，崩溃发生时先上报再委托。
     */
    @Synchronized
    override fun onStart() {
        if (!config.enableJavaCrash && !config.enableNativeCrash && !config.collectExitInfo) {
            return
        }

        // 启用 Java 崩溃链路：替换默认未捕获异常处理器。
        if (config.enableJavaCrash) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previousHandler))
        }

        // 启用 Native 崩溃链路：尝试安装 JNI 信号处理器。
        if (config.enableNativeCrash) {
            // 启动时先扫描上一轮崩溃生成的 tombstone，再安装本轮信号处理器。
            NativeCrashMonitor.checkRecentTombstone()
            NativeCrashMonitor.init(config.enableUnsafeNativeSignalCallback)
        }

        // 启动退出原因采集：读取系统记录的上一进程退出原因（API 30+）
        startExitReasonCollection()

        apmContext?.logger?.d(
            "Crash module started, java=${config.enableJavaCrash}, native=${config.enableNativeCrash}"
        )
    }

    /**
     * 启动 ApplicationExitInfo 退出原因采集。
     * API 30+ 时在后台线程执行一次采集，逐条上报去重后的退出记录；
     * 低版本或关闭开关时为 no-op。
     */
    private fun startExitReasonCollection() {
        // 版本与开关守卫：ApplicationExitInfo 自 API 30 起可用
        if (!config.collectExitInfo || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val context = apmContext ?: return
        val application = context.application
        val session = exitSession.incrementAndGet()
        if (writeExitIdentitySummary && context.config.serializationFormat == SerializationFormat.PROTOBUF_ENVELOPE_V3) {
            try {
                val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val summary = context.captureExitOccurrence()?.let(ExitOccurrenceSummary::encode)
                manager?.setProcessStateSummary(summary)
                wroteExitIdentitySummary = manager != null
                if (summary == null) context.logger.w("Exit identity cannot fit the platform summary; future exit remains unknown")
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_EXIT_SUMMARY, error)
            }
        }

        val collector = ExitReasonCollector(
            source = AndroidExitInfoSource(application),
            timestampStore = PrefsExitTimestampStore(application),
            maxTraceBytes = config.maxExitTraceBytes,
            isActive = { exitSession.get() == session },
            emit = { record, eventName, severity, fields ->
                // Serialize final admission with module stop, including dynamic disable without an
                // SDK shutdown. Slow source/trace reads stay outside this lock.
                synchronized(this@CrashModule) {
                    if (exitSession.get() == session) {
                        val event = ApmEvent(
                            module = MODULE_NAME, name = eventName, kind = ApmEventKind.ALERT,
                            severity = severity, priority = ApmPriority.HIGH,
                            timestamp = record.timestampMs, processName = record.processName,
                            threadName = UNKNOWN_HISTORICAL_THREAD, fields = fields
                        )
                        val historical = if (context.config.serializationFormat == SerializationFormat.PROTOBUF_ENVELOPE_V3) {
                            record.occurrence?.let(event::withOccurrenceContext) ?: event
                        } else event
                        // A slow old collector must never emit into a newly initialized runtime.
                        context.emitHistorical(historical)
                    }
                }
            }
        )
        exitCollectorThread = Thread(
            {
                try {
                    collector.collectOnce()
                } catch (error: Exception) {
                    Apm.recordInternalError(ERROR_TAG_EXIT_INFO, error)
                }
            },
            EXIT_COLLECTOR_THREAD_NAME
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 恢复原始 handler。 */
    @Synchronized
    override fun onStop() {
        exitSession.incrementAndGet()
        exitCollectorThread?.interrupt()
        exitCollectorThread = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wroteExitIdentitySummary) {
            try {
                val manager = apmContext?.application?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.setProcessStateSummary(null)
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_EXIT_SUMMARY, error)
            }
            wroteExitIdentitySummary = false
        }
        if (config.enableJavaCrash && Thread.getDefaultUncaughtExceptionHandler() is CrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        if (config.enableNativeCrash) {
            NativeCrashMonitor.destroy()
        }
    }

    /**
     * 自定义崩溃处理器。
     * 包装原始 handler，在委托前先上报崩溃信息。
     */
    private inner class CrashHandler(private val delegate: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            executeCriticalCrashHandoff(
                criticalHandoff = {
                    // 将堆栈转为字符串并截断
                    val stackTrace = stackTraceToString(throwable).take(config.maxStackTraceLength)
                    Apm.emitCriticalSync(
                        module = MODULE_NAME, name = EVENT_JAVA_CRASH, kind = ApmEventKind.ALERT, severity = ApmSeverity.FATAL, priority = ApmPriority.CRITICAL, fields = mapOf(
                            FIELD_EXCEPTION_CLASS to throwable.javaClass.name,
                            FIELD_EXCEPTION_MESSAGE to (throwable.message.orEmpty()),
                            FIELD_STACK_TRACE to stackTrace,
                            FIELD_THREAD_NAME to thread.name,
                            FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
                        )
                    )
                },
                onFailure = { error ->
                    if (error == null) {
                        Apm.recordInternalError(
                            ERROR_TAG_CRASH_LOCAL_HANDOFF,
                            IllegalStateException("Crash event did not reach the local critical hand-off")
                        )
                    } else {
                        Apm.recordInternalError(ERROR_TAG_CRASH_HANDLER_EMIT, error)
                    }
                },
                delegate = {
                    // 始终委托给原始 handler，不破坏现有崩溃处理链
                    delegate?.uncaughtException(thread, throwable)
                }
            )
        }
    }

    /** 将 Throwable 堆栈转为字符串。 */
    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            throwable.printStackTrace(pw)
        }
        return sw.toString()
    }

    companion object {
        /** Historical emitter thread is not provided by ApplicationExitInfo. */
        private const val UNKNOWN_HISTORICAL_THREAD = "unknown"
        /** Platform summary failures carry no identity values in diagnostics. */
        private const val ERROR_TAG_EXIT_SUMMARY = "crash_exit_identity_summary"

        /** 自监控 tag：崩溃处理器内部上报失败。 */
        private const val ERROR_TAG_CRASH_HANDLER_EMIT = "crash_handler_emit"

        /** Stable tag for a false synchronous crash hand-off result. */
        private const val ERROR_TAG_CRASH_LOCAL_HANDOFF = "crash_local_handoff"

        /** 自监控 tag：退出原因采集失败。 */
        private const val ERROR_TAG_EXIT_INFO = "crash_exit_info"

        /** 退出原因采集线程名。 */
        private const val EXIT_COLLECTOR_THREAD_NAME = "apm-crash-exit-info"

        /** 模块名。 */
        private const val MODULE_NAME = "crash"

        /** Java 崩溃事件名。 */
        private const val EVENT_JAVA_CRASH = "java_crash"

        /** 字段：异常类名。 */
        private const val FIELD_EXCEPTION_CLASS = "exceptionClass"

        /** 字段：异常消息。 */
        private const val FIELD_EXCEPTION_MESSAGE = "exceptionMessage"

        /** 字段：堆栈信息。 */
        private const val FIELD_STACK_TRACE = "stackTrace"

        /** 字段：线程名。 */
        private const val FIELD_THREAD_NAME = "threadName"

        /** 字段：进程名。 */
        private const val FIELD_PROCESS_NAME = "processName"
    }
}
