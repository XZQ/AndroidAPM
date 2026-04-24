package com.apm.anr

import android.os.Handler
import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR 监控模块。
 * 双重检测机制（对标微信 Matrix + Google 最佳实践）：
 *
 * 1. **SIGQUIT 信号检测**（主方案）：
 *    - 通过 JNI 注册 SIGQUIT 信号处理器
 *    - 系统 ANR 发生前会发送 SIGQUIT（Signal 3）
 *    - 比 Watchdog 更精准，延迟更低
 *    - JNI 不可用时自动降级为 Watchdog
 *
 * 2. **Watchdog 线程检测**（兜底方案）：
 *    - 定期向主线程投递 tick 标记
 *    - 等待 checkIntervalMs 后检查是否被消费
 *    - 未被消费则判定主线程阻塞
 *
 * 3. **ANR 原因分类**：
 *    - CPU：主线程大量计算
 *    - IO：主线程阻塞在 IO 操作
 *    - LOCK：主线程等待锁
 *    - DEADLOCK：检测到死锁环
 *
 * 4. **traces.txt 读取**：
 *    - ANR 后尝试读取 /data/anr/traces.txt
 *    - 获取系统视角的完整线程堆栈
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
    /** Watchdog 线程的 tick 标记。 */
    private val tick = AtomicBoolean(false)
    /** 是否正在运行。 */
    @Volatile private var running = false
    /** Watchdog 线程引用。 */
    private var watchdogThread: Thread? = null

    /** SIGQUIT 分析线程池。 */
    private var sigquitAnalysisExecutor: ExecutorService? = null

    /** SIGQUIT 调度器。 */
    private val sigquitAnalysisDispatcher = SigquitAnalysisDispatcher(::scheduleSigquitAnalysis)

    // --- ANR 去重 ---
    /** 上次 ANR 上报时间戳。 */
    private val lastReportTimeMs = AtomicLong(0L)
    /** 上次 ANR 堆栈指纹。 */
    @Volatile private var lastStackFingerprint: String = ""

    // --- 堆栈采样 ---
    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /** 启动 ANR 监控。优先 SIGQUIT，降级 Watchdog。 */
    override fun onStart() {
        if (!config.enableAnrMonitor) return
        running = true
        anrDetected.set(false)
        sigquitAnalysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, SIGQUIT_ANALYSIS_THREAD_NAME)
        }

        // 尝试注册 SIGQUIT 信号处理器
        val sigquitReady = if (config.enableSigquitDetection) {
            registerSigquitHandler()
        } else {
            false
        }

        // SIGQUIT 不可用时降级为 Watchdog
        if (!sigquitReady) {
            startWatchdog()
        }

        apmContext?.logger?.d("ANR module started, sigquit=$sigquitReady")
    }

    /** 停止监控。 */
    override fun onStop() {
        running = false
        watchdogThread?.interrupt()
        watchdogThread = null
        // 注销 SIGQUIT 处理器
        if (config.enableSigquitDetection) {
            unregisterSigquitHandler()
        }
        sigquitAnalysisExecutor?.shutdownNow()
        sigquitAnalysisExecutor = null
    }

    // ========================================================================
    // SIGQUIT 信号检测
    // ========================================================================

    /**
     * 注册 SIGQUIT 信号处理器。
     * 通过 JNI 注册 native 层信号回调。
     * JNI 不可用时返回 false，降级为 Watchdog。
     *
     * @return true 表示注册成功。
     */
    private fun registerSigquitHandler(): Boolean {
        return try {
            // 尝试加载 JNI 库
            System.loadLibrary("apm-anr")
            nativeRegisterSigquitHandler()
            true
        } catch (e: UnsatisfiedLinkError) {
            // JNI 库不存在，降级为 Watchdog
            apmContext?.logger?.d("SIGQUIT JNI not available, falling back to Watchdog")
            false
        } catch (e: Exception) {
            apmContext?.logger?.d("SIGQUIT registration failed: ${e.message}")
            false
        }
    }

    /**
     * 注销 SIGQUIT 信号处理器。
     */
    private fun unregisterSigquitHandler() {
        try {
            nativeUnregisterSigquitHandler()
        } catch (_: UnsatisfiedLinkError) {
            // JNI 不存在，无需注销
        } catch (_: Exception) {
            // 忽略注销失败
        }
    }

    /**
     * JNI 回调：SIGQUIT 信号触发时由 native 层调用。
     * 注意：此方法在信号处理器线程中执行，必须快速返回。
     */
    private fun onSigquitReceived() {
        // 将重分析工作切到独立线程，避免依赖已阻塞的主线程。
        sigquitAnalysisDispatcher.dispatch(
            running = running,
            anrDetected = anrDetected,
            analysis = { handleAnrDetection(DETECTION_SIGQUIT) }
        )
    }

    /**
     * 将 SIGQUIT 分析任务提交到独立线程。
     *
     * @param analysis 实际分析任务
     * @return true 表示任务已被线程池接管
     */
    private fun scheduleSigquitAnalysis(analysis: () -> Unit): Boolean {
        val executor = sigquitAnalysisExecutor ?: return false
        return try {
            executor.execute(analysis)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    // ========================================================================
    // Watchdog 检测（兜底）
    // ========================================================================

    /**
     * 启动 Watchdog 线程。
     * 当 SIGQUIT 不可用时作为降级方案。
     */
    private fun startWatchdog() {
        watchdogThread = Thread({ watchdogLoop() }, THREAD_NAME).apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Watchdog 主循环。
     * 每轮：投递 tick → 等待 → 检查 tick 是否被消费。
     */
    private fun watchdogLoop() {
        while (running) {
            tick.set(false)
            mainHandler.post { tick.set(true) }

            try {
                Thread.sleep(config.checkIntervalMs)
            } catch (_: InterruptedException) {
                break
            }

            if (!running) break

            // tick 未被消费且尚未报告过 ANR
            if (!tick.get() && !anrDetected.getAndSet(true)) {
                handleAnrDetection(DETECTION_WATCHDOG)
            }

            // tick 已被消费，重置 ANR 状态
            if (tick.get()) {
                anrDetected.set(false)
            }
        }
    }

    // ========================================================================
    // ANR 处理核心逻辑
    // ========================================================================

    /**
     * 处理 ANR 检测事件。
     * 统一处理 SIGQUIT 和 Watchdog 两种来源的 ANR。
     *
     * @param source 检测来源（"sigquit" 或 "watchdog"）。
     */
    private fun handleAnrDetection(source: String) {
        val now = System.currentTimeMillis()
        val mainStack = captureMainThreadStack()

        // ANR 去重：相同堆栈在窗口内不重复上报
        val fingerprint = computeStackFingerprint(mainStack)
        if (isDuplicateAnr(now, fingerprint)) {
            apmContext?.logger?.d("ANR deduplicated, skipping report")
            return
        }

        // 记录本次 ANR 信息
        lastReportTimeMs.set(now)
        lastStackFingerprint = fingerprint

        // 收集多次堆栈采样（用于原因分类）
        val samples = collectStackSamples()

        // ANR 原因分类
        val cause = if (config.enableAnrClassification) {
            classifyAnrCause(mainStack, samples)
        } else {
            ANR_CAUSE_UNKNOWN
        }

        // 读取 traces.txt（如果可访问）
        val tracesContent = if (config.enableTracesFileReading) {
            readTracesFile()
        } else {
            ""
        }

        // 判断严重级别
        val severity = if (config.anrSevereThresholdMs <= config.checkIntervalMs) {
            ApmSeverity.ERROR
        } else {
            ApmSeverity.ERROR
        }

        // 构建上报字段
        val fields = mutableMapOf<String, Any>(
            FIELD_MAIN_THREAD_STACK to mainStack,
            FIELD_ANR_SOURCE to source,
            FIELD_ANR_CAUSE to cause,
            FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
        )

        // 附加 traces.txt 内容（如果有）
        if (tracesContent.isNotEmpty()) {
            fields[FIELD_TRACES_CONTENT] = tracesContent.take(config.maxStackTraceLength)
        }

        // 附加堆栈采样（如果有）
        if (samples.isNotEmpty()) {
            fields[FIELD_STACK_SAMPLES] = samples.joinToString(SEPARATOR_SAMPLE)
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_ANR_DETECTED,
            kind = ApmEventKind.ALERT,
            severity = severity, priority = ApmPriority.CRITICAL,
            fields = fields
        )

        apmContext?.logger?.w("ANR detected: source=$source, cause=$cause")
    }

    // ========================================================================
    // 堆栈采样
    // ========================================================================

    /**
     * 收集多次主线程堆栈采样。
     * 用于分析 ANR 原因：多次采样堆栈相同=锁等待，不同=CPU/IO。
     *
     * @return 堆栈采样列表。
     */
    private fun collectStackSamples(): List<String> {
        if (config.stackSampleCount <= 1) return emptyList()

        val samples = mutableListOf<String>()
        // 注意：此处已在 ANR 回调中，主线程可能仍在阻塞
        // 直接采集当前主线程堆栈（不会阻塞）
        for (i in 0 until config.stackSampleCount) {
            val stack = captureMainThreadStack()
            // 取堆栈的前 5 行作为指纹
            val shortStack = stack.lines().take(STACK_FINGERPRINT_LINES).joinToString(LINE_SEPARATOR)
            samples.add(shortStack)
            if (i < config.stackSampleCount - 1) {
                try {
                    Thread.sleep(config.stackSampleIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return samples
    }

    // ========================================================================
    // ANR 原因分类
    // ========================================================================

    /**
     * 分类 ANR 原因。
     * 通过分析主线程堆栈和多次采样结果判断：
     * - LOCK：堆栈中包含 Object.wait / lock 等关键词
     * - IO：堆栈中包含 read/write/open / FileInputStream 等关键词
     * - CPU：堆栈频繁变化，无 IO/锁关键词
     * - DEADLOCK：多次采样堆栈完全相同 + BLOCKED 状态
     *
     * @param mainStack 主线程堆栈。
     * @param samples 多次堆栈采样。
     * @return ANR 原因分类字符串。
     */
    private fun classifyAnrCause(mainStack: String, samples: List<String>): String {
        val lowerStack = mainStack.lowercase()

        // 检测锁等待
        if (containsAny(lowerStack, LOCK_INDICATORS)) {
            // 检测是否为死锁（多次采样堆栈完全相同）
            if (samples.size >= MIN_SAMPLES_FOR_DEADLOCK && allSamplesIdentical(samples)) {
                return ANR_CAUSE_DEADLOCK
            }
            return ANR_CAUSE_LOCK
        }

        // 检测 IO 阻塞
        if (containsAny(lowerStack, IO_INDICATORS)) {
            return ANR_CAUSE_IO
        }

        // 检测 CPU 密集
        if (samples.size >= MIN_SAMPLES_FOR_DEADLOCK && !allSamplesIdentical(samples)) {
            return ANR_CAUSE_CPU
        }

        // 堆栈中包含 Binder 调用
        if (containsAny(lowerStack, BINDER_INDICATORS)) {
            return ANR_CAUSE_BINDER
        }

        return ANR_CAUSE_UNKNOWN
    }

    /**
     * 检查字符串是否包含任一关键词。
     */
    private fun containsAny(text: String, keywords: Array<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * 检查多次堆栈采样是否完全相同。
     * 相同表示主线程一直卡在同一个调用点（疑似死锁）。
     */
    private fun allSamplesIdentical(samples: List<String>): Boolean {
        if (samples.isEmpty()) return false
        return samples.all { it == samples[0] }
    }

    // ========================================================================
    // traces.txt 读取
    // ========================================================================

    /**
     * 读取 /data/anr/traces.txt 文件。
     * 需要 root 权限或 system 用户才能访问。
     *
     * @return traces 文件内容（截断到最大长度），或空字符串。
     */
    private fun readTracesFile(): String {
        // 尝试多个可能的 traces 文件路径
        for (path in TRACES_FILE_PATHS) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        return content
                    }
                }
            } catch (_: Exception) {
                // 读取失败，尝试下一个路径
            }
        }
        return ""
    }

    // ========================================================================
    // ANR 去重
    // ========================================================================

    /**
     * 计算堆栈指纹（前 N 行的哈希）。
     * 用于 ANR 去重判断。
     */
    private fun computeStackFingerprint(stack: String): String {
        return stack.lines()
            .take(FINGERPRINT_LINES)
            .joinToString(LINE_SEPARATOR)
            .hashCode()
            .toString()
    }

    /**
     * 判断是否为重复 ANR。
     * 相同堆栈指纹在去重窗口内视为重复。
     */
    private fun isDuplicateAnr(now: Long, fingerprint: String): Boolean {
        val lastTime = lastReportTimeMs.get()
        // 超出窗口，不算重复
        if (now - lastTime > config.anrDeduplicationWindowMs) return false
        // 窗口内相同指纹，视为重复
        return fingerprint == lastStackFingerprint
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /** 抓取主线程堆栈，截断到最大长度。 */
    private fun captureMainThreadStack(): String {
        val mainThread = Looper.getMainLooper().thread
        val trace = mainThread.stackTrace
        return trace.joinToString(LINE_SEPARATOR).take(config.maxStackTraceLength)
    }

    // ========================================================================
    // Native 方法声明
    // ========================================================================

    /** 注册 SIGQUIT 信号处理器。 */
    private external fun nativeRegisterSigquitHandler()

    /** 注销 SIGQUIT 信号处理器。 */
    private external fun nativeUnregisterSigquitHandler()

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "anr"
        /** Watchdog 线程名。 */
        private const val THREAD_NAME = "apm-anr-watchdog"
        /** SIGQUIT 分析线程名。 */
        private const val SIGQUIT_ANALYSIS_THREAD_NAME = "apm-anr-sigquit"

        // --- 事件名 ---
        /** ANR 检测事件名。 */
        private const val EVENT_ANR_DETECTED = "anr_detected"

        // --- 字段名 ---
        /** 字段：主线程堆栈。 */
        private const val FIELD_MAIN_THREAD_STACK = "mainThreadStack"
        /** 字段：ANR 检测来源。 */
        private const val FIELD_ANR_SOURCE = "anrSource"
        /** 字段：ANR 原因分类。 */
        private const val FIELD_ANR_CAUSE = "anrCause"
        /** 字段：进程名。 */
        private const val FIELD_PROCESS_NAME = "processName"
        /** 字段：traces.txt 内容。 */
        private const val FIELD_TRACES_CONTENT = "tracesContent"
        /** 字段：堆栈采样。 */
        private const val FIELD_STACK_SAMPLES = "stackSamples"

        // --- 检测来源 ---
        /** SIGQUIT 信号检测。 */
        private const val DETECTION_SIGQUIT = "sigquit"
        /** Watchdog 检测。 */
        private const val DETECTION_WATCHDOG = "watchdog"

        // --- ANR 原因分类 ---
        /** ANR 原因：CPU 密集。 */
        private const val ANR_CAUSE_CPU = "CPU"
        /** ANR 原因：IO 阻塞。 */
        private const val ANR_CAUSE_IO = "IO"
        /** ANR 原因：锁等待。 */
        private const val ANR_CAUSE_LOCK = "LOCK"
        /** ANR 原因：死锁。 */
        private const val ANR_CAUSE_DEADLOCK = "DEADLOCK"
        /** ANR 原因：Binder 调用阻塞。 */
        private const val ANR_CAUSE_BINDER = "BINDER"
        /** ANR 原因：未知。 */
        private const val ANR_CAUSE_UNKNOWN = "UNKNOWN"

        // --- 锁等待关键词 ---
        /** 锁等待堆栈指示关键词。 */
        private val LOCK_INDICATORS = arrayOf(
            "object.wait", "object.wait(",
            "locksupport.park", "locksupport.park(",
            "reentrantlock", "abstractqueuedsynchronizer",
            "wait(", "monitor.enter"
        )

        // --- IO 阻塞关键词 ---
        /** IO 阻塞堆栈指示关键词。 */
        private val IO_INDICATORS = arrayOf(
            "fileinputstream", "fileoutputstream",
            "randomaccessfile", "filechannel",
            "socketinputstream", "socketoutputstream",
            ".read(", ".write(", "openat"
        )

        // --- Binder 调用关键词 ---
        /** Binder 堆栈指示关键词。 */
        private val BINDER_INDICATORS = arrayOf(
            "binder.executetransaction",
            "binderproxy.transact",
            "serviceproxy", "activitymanager"
        )

        // --- traces.txt 路径 ---
        /** traces.txt 可能的文件路径。 */
        private val TRACES_FILE_PATHS = arrayOf(
            "/data/anr/traces.txt",
            "/data/anr/traces.txt.bugreport",
            "/data/system/dropbox/[ANR]"
        )

        // --- 常量 ---
        /** 行分隔符。 */
        private const val LINE_SEPARATOR = "\n"
        /** 采样分隔符。 */
        private const val SEPARATOR_SAMPLE = "\n---\n"
        /** 堆栈指纹行数。 */
        private const val FINGERPRINT_LINES = 5
        /** 堆栈采样指纹行数。 */
        private const val STACK_FINGERPRINT_LINES = 5
        /** 死锁检测最少采样数。 */
        private const val MIN_SAMPLES_FOR_DEADLOCK = 2
    }
}
