package com.apm.crash

import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃监控模块。
 * 通过 [Thread.setDefaultUncaughtExceptionHandler] 捕获 Java 层未处理异常，
 * 将崩溃信息上报到 APM 管道，然后委托给原始 Handler 保证现有崩溃处理不受影响。
 */
class CrashModule(private val config: CrashConfig = CrashConfig()) : ApmModule {

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
    override fun onStart() {
        if (!config.enableJavaCrash) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previousHandler))
        apmContext?.logger?.d("Crash module started")
    }

    /** 恢复原始 handler。 */
    override fun onStop() {
        if (Thread.getDefaultUncaughtExceptionHandler() is CrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    /**
     * 自定义崩溃处理器。
     * 包装原始 handler，在委托前先上报崩溃信息。
     */
    private inner class CrashHandler(private val delegate: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                // 将堆栈转为字符串并截断
                val stackTrace = stackTraceToString(throwable).take(config.maxStackTraceLength)
                Apm.emit(
                    module = MODULE_NAME, name = EVENT_JAVA_CRASH, kind = ApmEventKind.ALERT, severity = ApmSeverity.FATAL, fields = mapOf(
                        FIELD_EXCEPTION_CLASS to throwable.javaClass.name,
                        FIELD_EXCEPTION_MESSAGE to (throwable.message.orEmpty()),
                        FIELD_STACK_TRACE to stackTrace,
                        FIELD_THREAD_NAME to thread.name,
                        FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
                    )
                )
            } catch (_: Exception) {
                // 崩溃处理器中绝不能抛异常
            }

            // 始终委托给原始 handler，不破坏现有崩溃处理链
            delegate?.uncaughtException(thread, throwable)
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
