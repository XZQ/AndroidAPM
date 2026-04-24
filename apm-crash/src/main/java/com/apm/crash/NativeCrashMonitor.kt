package com.apm.crash

import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmPriority
import com.apm.model.ApmSeverity
import java.io.File

/**
 * Native 崩溃信号监控器。
 *
 * 双层策略：
 * 1. 默认安全模式：JNI 层恢复原始信号处理器并重抛，下一次启动通过 tombstone 解析上报
 * 2. 调试兼容模式：通过 [logNativeCrashSignal] 接收 JNI 层上报的信号
 * 3. Java 层降级：通过 tombstone 文件解析最近的 native crash
 *
 * 完整的 Native 崩溃捕获需要在 JNI 层注册信号处理器（SIGSEGV、SIGABRT 等）。
 * 生产环境推荐集成 Google Breakpad 或 Firebase Crashlytics NDK。
 *
 * 默认不在信号处理上下文回调 Java，避免异步信号不安全操作。
 * 如需调试实时回调，可在 [init] 中显式开启 unsafeSignalCallback。
 */
object NativeCrashMonitor {

    /** 是否已初始化。 */
    @Volatile
    private var initialized = false

    /** 信号处理器是否已安装。 */
    @Volatile
    private var signalHandlerInstalled = false

    /** 最近一次 tombstone 检查时间。 */
    private var lastCheckTime: Long = 0L

    // --- 常量 ---
    /** 模块名。 */
    private const val MODULE = "crash"

    /** Native 崩溃事件名。 */
    private const val EVENT_NATIVE_CRASH = "native_crash"

    /** Tombstone 崩溃事件名。 */
    private const val EVENT_TOMBSTONE_CRASH = "tombstone_crash"

    /** 字段：信号编号。 */
    private const val FIELD_SIGNAL = "signal"

    /** 字段：信号名称。 */
    private const val FIELD_SIGNAL_NAME = "signalName"

    /** 字段：线程名。 */
    private const val FIELD_THREAD_NAME = "threadName"

    /** 字段：调用栈。 */
    private const val FIELD_BACKTRACE = "backtrace"

    /** 字段：fault addr。 */
    private const val FIELD_FAULT_ADDR = "faultAddr"

    /** 字段：进程名。 */
    private const val FIELD_PROCESS_NAME = "processName"

    /** 调用栈最大长度。 */
    private const val MAX_BACKTRACE_LENGTH = 4000

    /** Tombstone 目录。 */
    private const val TOMBSTONE_DIR = "/data/tombstones/"

    /** Tombstone 检查间隔：60 秒。 */
    private const val TOMBSTONE_CHECK_INTERVAL_MS = 60_000L

    // 信号常量
    private const val SIGNAL_SIGABRT = 6
    private const val SIGNAL_SIGBUS = 7
    private const val SIGNAL_SIGKILL = 9
    private const val SIGNAL_SIGSEGV = 11
    private const val SIGNAL_SIGPIPE = 13
    private const val SIGNAL_SIGFPE = 8
    private const val SIGNAL_SIGSTKFLT = 16
    private const val SIGNAL_SIGTERM = 15

    /**
     * 初始化 Native Crash 监控。尝试加载 JNI 库并安装信号处理器。
     *
     * @param unsafeSignalCallback 是否允许在信号处理器内直接回调 Java；仅建议调试环境使用
     */
    fun init(unsafeSignalCallback: Boolean = false) {
        if (initialized) return
        initialized = true
        try {
            // 加载 JNI 库
            System.loadLibrary("apm_crash")
            // 安装信号处理器：默认只恢复原 handler 并重抛，避免信号上下文执行 Java。
            if (nativeInstallSignalHandlers(unsafeSignalCallback)) {
                signalHandlerInstalled = true
            }
        } catch (e: UnsatisfiedLinkError) {
            // JNI 库不存在，使用 Java 层降级方案（tombstone 解析）
        }
    }

    /**
     * 销毁信号处理器，恢复原始信号处理。
     * 在模块卸载或进程退出前调用。
     */
    fun destroy() {
        if (signalHandlerInstalled) {
            try {
                // 恢复原始信号处理器
                nativeUninstallSignalHandlers()
            } catch (e: UnsatisfiedLinkError) {
                // JNI 库已卸载，忽略
            }
            signalHandlerInstalled = false
        }
    }

    /**
     * 安装 Native 信号处理器。
     * 注册 SIGSEGV、SIGABRT、SIGBUS、SIGFPE、SIGPIPE、SIGSTKFLT 的信号处理函数。
     *
     * @param unsafeSignalCallback 是否允许信号处理器内直接回调 Java
     * @return true 表示安装成功
     */
    private external fun nativeInstallSignalHandlers(unsafeSignalCallback: Boolean): Boolean

    /**
     * 卸载 Native 信号处理器。
     * 恢复所有被拦截信号的原始处理函数。
     */
    private external fun nativeUninstallSignalHandlers()

    /**
     * 记录一次 Native 崩溃信号。
     * 由 JNI 层的信号处理器调用。
     *
     * @param signal 信号编号（如 SIGSEGV=11）
     * @param threadName 崩溃线程名
     * @param backtrace 调用栈字符串
     * @param faultAddr 故障地址
     */
    fun logNativeCrashSignal(
        signal: Int,
        threadName: String = "",
        backtrace: String = "",
        faultAddr: String = ""
    ) {
        Apm.emit(
            module = MODULE,
            name = EVENT_NATIVE_CRASH,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.FATAL, priority = ApmPriority.CRITICAL,
            fields = mutableMapOf<String, Any?>(
                FIELD_SIGNAL to signal,
                FIELD_SIGNAL_NAME to signalName(signal),
                FIELD_THREAD_NAME to threadName,
                FIELD_BACKTRACE to backtrace.take(MAX_BACKTRACE_LENGTH)
            ).apply {
                if (faultAddr.isNotEmpty()) {
                    put(FIELD_FAULT_ADDR, faultAddr)
                }
            }
        )
    }

    /**
     * 检查最近的 tombstone 文件。
     * 如果发现新的 tombstone（比上次检查更新的），解析并上报。
     *
     * 这是 JNI 层不可用时的降级方案。
     */
    fun checkRecentTombstone() {
        val now = System.currentTimeMillis()
        // 限制检查频率
        if (now - lastCheckTime < TOMBSTONE_CHECK_INTERVAL_MS) return
        lastCheckTime = now

        try {
            val tombstoneDir = File(TOMBSTONE_DIR)
            if (!tombstoneDir.exists() || !tombstoneDir.isDirectory) return

            // 查找最近修改的 tombstone 文件
            val recentFile = tombstoneDir.listFiles()
                ?.filter { it.name.startsWith("tombstone_") }
                ?.maxByOrNull { it.lastModified() }
                ?: return

            // 检查是否是上次之后的新 crash
            if (recentFile.lastModified() > lastCheckTime - TOMBSTONE_CHECK_INTERVAL_MS) {
                val content = recentFile.readText().take(MAX_BACKTRACE_LENGTH)
                parseAndReportTombstone(content)
            }
        } catch (e: Exception) {
            // tombstone 解析失败不影响主流程
        }
    }

    /**
     * 解析 tombstone 内容并上报。
     * tombstone 格式示例：
     * ```
     * Build fingerprint: '...'
     * pid: 12345, tid: 12345, name: xxx  >>> com.example <<<
     * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
     * Cause: [OPTIONAL]
     * Backtrace:
     *   #00 pc 00012345  /system/lib/libc.so
     *   #01 pc 00067890  /data/app/~~/lib/arm64-v8a/libnative.so
     * ```
     */
    private fun parseAndReportTombstone(content: String) {
        var signal = 0
        var signalName = ""
        var faultAddr = ""
        var threadName = ""
        val backtraceLines = mutableListOf<String>()
        var inBacktrace = false

        for (line in content.lines()) {
            val trimmed = line.trim()
            // 解析信号行
            if (trimmed.startsWith("signal ")) {
                val regex = Regex("signal (\\d+) \\((\\w+)\\).*fault addr (\\S+)")
                val match = regex.find(trimmed)
                if (match != null) {
                    signal = match.groupValues[1].toIntOrNull() ?: 0
                    signalName = match.groupValues[2]
                    faultAddr = match.groupValues[3]
                }
            }
            // 解析线程名
            if (trimmed.contains("name:") && trimmed.contains(">>>")) {
                val nameMatch = Regex("name:\\s*(\\S+)").find(trimmed)
                if (nameMatch != null) {
                    threadName = nameMatch.groupValues[1]
                }
            }
            // 解析 backtrace
            if (trimmed.startsWith("Backtrace:")) {
                inBacktrace = true
                continue
            }
            if (inBacktrace) {
                if (trimmed.startsWith("#")) {
                    backtraceLines.add(trimmed)
                } else if (trimmed.isEmpty() || trimmed.startsWith("Stack")) {
                    inBacktrace = false
                }
            }
        }

        if (signal > 0) {
            Apm.emit(
                module = MODULE,
                name = EVENT_TOMBSTONE_CRASH,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.FATAL, priority = ApmPriority.CRITICAL,
                fields = mutableMapOf<String, Any?>(
                    FIELD_SIGNAL to signal,
                    FIELD_SIGNAL_NAME to (signalName.ifEmpty { signalName(signal) }),
                    FIELD_THREAD_NAME to threadName,
                    FIELD_BACKTRACE to backtraceLines.joinToString("\n").take(MAX_BACKTRACE_LENGTH)
                ).apply {
                    if (faultAddr.isNotEmpty() && faultAddr != "--------") {
                        put(FIELD_FAULT_ADDR, faultAddr)
                    }
                }
            )
        }
    }

    /** 将信号编号转为可读名称。 */
    private fun signalName(signal: Int): String = when (signal) {
        SIGNAL_SIGABRT -> "SIGABRT"
        SIGNAL_SIGBUS -> "SIGBUS"
        SIGNAL_SIGKILL -> "SIGKILL"
        SIGNAL_SIGSEGV -> "SIGSEGV"
        SIGNAL_SIGPIPE -> "SIGPIPE"
        SIGNAL_SIGFPE -> "SIGFPE"
        SIGNAL_SIGSTKFLT -> "SIGSTKFLT"
        SIGNAL_SIGTERM -> "SIGTERM"
        else -> "UNKNOWN($signal)"
    }
}
