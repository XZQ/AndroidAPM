package com.apm.memory.oom

import android.content.Context
import android.os.Debug
import com.apm.core.ApmClock
import com.apm.core.ApmExecutors
import com.apm.core.ApmLogger
import com.apm.memory.MemoryConfig
import com.apm.memory.MemoryReport
import com.apm.memory.MemoryReportSink
import com.apm.memory.ApmMemoryReportSink
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.io.File
import java.util.concurrent.RejectedExecutionException

/**
 * Hprof 文件 Dump 器。
 * 在 OOM 危险阈值触发时，将 Java Heap 导出为 hprof 文件。
 *
 * 默认使用直接 dump（Debug.dumpHprofData），会产生 1~3 秒的 STW。
 * fork 子进程 dump 依赖设备/ART 兼容性，需通过 [MemoryConfig.enableForkHprofDump] 显式开启。
 *
 * @param context 应用上下文，用于获取 hprof 缓存目录
 * @param config 内存模块配置（含 dump 阈值与 fork dump 开关）
 * @param logger 日志接口
 */
internal class HprofDumper(
    /** Application storage owner. */
    private val context: Context,
    /** Dump policy for this module session. */
    private val config: MemoryConfig,
    /** Session diagnostic destination. */
    private val logger: ApmLogger,
    /** Injectable ART boundary; slow dumps execute outside the shutdown/handoff lock. */
    private val directDump: (File) -> Unit = { Debug.dumpHprofData(it.absolutePath) },
    /** Destination used only while this dumper's original session remains open. */
    private val reportSink: MemoryReportSink = ApmMemoryReportSink,
    /** Production bridge resolves host context before asking this dumper to guard final admission. */
    private val guardedReport: ((MemoryReport, (() -> Boolean) -> Boolean) -> Unit)? = null,
    /** Filename timestamp source; exclusive creation also protects repeated dumps in the same millisecond. */
    private val wallTimeMillis: () -> Long = ApmClock::wallTimeMillis
) {
    /** Serializes permanent closure with final event delivery, never with heap IO. */
    private val sessionLock = Any()
    /** Closed dumpers cannot be revived by a new MemoryModule/SDK initialization. */
    @Volatile private var closed = false
    /** dump 工作线程：执行 Debug.dumpHprofData 会产生 1~3s STW，故使用最低优先级后台线程降低对宿主的影响。 */
    private val dumpExecutor = ApmExecutors.newSingleThreadExecutor(THREAD_NAME)

    /** hprof 文件存储目录。 */
    private val hprofDir = File(context.cacheDir, HPROF_DIR_PATH).apply { mkdirs() }

    /** fork 子进程 dump 是否可用。 */
    @Volatile
    private var forkDumpAvailable = false

    /**
     * 初始化：尝试加载 fork dump JNI 库。
     * 如果库加载成功则启用 fork 子进程 dump 模式。
     */
    fun init() {
        if (!config.enableForkHprofDump) {
            // fork dump 涉及 fork 后 ART/JNI 兼容性，生产默认关闭。
            logger.d("HprofDumper: fork dump disabled by config, using direct dump")
            return
        }
        try {
            // 加载 fork dump JNI 库
            System.loadLibrary(LIB_APM_DUMPER)
            // 库加载成功，标记 fork dump 可用
            forkDumpAvailable = true
            logger.d("HprofDumper: fork-based dump available")
        } catch (e: UnsatisfiedLinkError) {
            // JNI 库不存在，使用直接 dump 降级方案（有 STW 代价）
            logger.d("HprofDumper: fork lib not available, using direct dump")
        }
    }

    /**
     * 异步执行 dump。在独立线程中执行，不阻塞调用方。
     *
     * @param reason dump 触发原因
     */
    fun dumpAsync(reason: String) {
        if (!config.enableHprofDump) {
            return
        }
        synchronized(sessionLock) {
            if (closed) return
            try {
                dumpExecutor.execute { if (!closed) dumpInternal(reason) }
            } catch (_: RejectedExecutionException) {
                // Concurrent closure is a cancelled observation, not a host-visible exception.
            }
        }
    }

    /**
     * dump 执行逻辑。
     * 优先使用 fork 子进程 dump（无 STW），失败时降级为直接 dump。
     * 流程：生成文件 → dump → 可选 strip → 上报文件事件。
     */
    private fun dumpInternal(reason: String) {
        val hprofFile = try {
            // Reserve a unique path atomically: timestamps alone collide during repeated/manual dumps.
            File.createTempFile(
                "${wallTimeMillis()}_${reason.replaceNonAlpha().take(MAX_REASON_FILENAME_LENGTH)}_",
                HPROF_EXTENSION,
                hprofDir
            )
        } catch (e: Exception) {
            logger.e("HprofDumper failed to create output", e)
            return
        }

        try {
            // 优先使用 fork 子进程 dump（无 STW）
            if (forkDumpAvailable) {
                val pid = nativeForkAndDump(hprofFile.absolutePath)
                if (pid > 0) {
                    // 父进程：等待子进程完成 dump；超时/失败不得上报半成品 hprof
                    if (!waitForChild(pid)) {
                        hprofFile.delete()
                        return
                    }
                } else if (pid == FORK_ERROR) {
                    // fork 失败，降级为直接 dump
                    dumpDirectly(hprofFile)
                }
                // pid == 0 时不应该到这里（子进程已 _exit）
            } else {
                // 无 fork 能力，直接 dump（有 STW 代价）
                dumpDirectly(hprofFile)
            }
        } catch (e: Exception) {
            hprofFile.delete()
            logger.e("HprofDumper failed", e)
            return
        }

        // ART may finish after shutdown even when it ignores interruption. Remove its late artifact.
        if (discardIfClosed(hprofFile)) return

        // dump 失败或文件为空，不继续处理
        if (!hprofFile.exists() || hprofFile.length() == 0L) {
            hprofFile.delete()
            return
        }

        // Clear primitive payloads with equal-length zeros; this preserves file size and metadata.
        val uploadFile = if (config.enableHprofStrip) {
            val strippedFile = File(hprofDir, hprofFile.nameWithoutExtension + STRIPPED_SUFFIX)
            val processor = HprofStripProcessor()
            val success = processor.strip(hprofFile, strippedFile)
            if (success) {
                hprofFile.delete()
                strippedFile
            } else {
                strippedFile.delete()
                hprofFile
            }
        } else hprofFile

        if (discardIfClosed(uploadFile)) return
        // Bound long-lived sessions too. Preserve this exact report target even when mtimes tie or regress.
        pruneOldFiles(DEFAULT_MAX_FILES, uploadFile)
        val report = MemoryReport(
            name = EVENT_HPROF_DUMP,
            kind = ApmEventKind.FILE,
            severity = ApmSeverity.INFO, priority = ApmPriority.HIGH,
            fields = mapOf(
                "reason" to reason,
                "filePath" to uploadFile.absolutePath,
                "fileSizeKb" to uploadFile.length() / BYTES_PER_KB
            )
        )
        if (guardedReport != null) {
            // Business-context callbacks run outside our lock; only the final admission is guarded.
            guardedReport.invoke(report, ::handoff)
        } else {
            handoff { reportSink.emit(report); true }
        }
        discardIfClosed(uploadFile)
    }

    /** Discards only this job's artifact after permanent session cancellation. */
    private fun discardIfClosed(file: File): Boolean {
        if (!closed) return false
        file.delete()
        return true
    }

    /** Guards only final delivery; shutdown permanently closes the same token without waiting on heap IO. */
    private fun handoff(action: () -> Boolean): Boolean = synchronized(sessionLock) {
        if (closed) return false
        action()
    }

    /** 直接调用 Debug.dumpHprofData。 */
    private fun dumpDirectly(outputFile: File) {
        directDump(outputFile)
    }

    /**
     * 等待 fork 的子进程完成 hprof dump。
     * 使用循环 waitpid 带超时，避免无限阻塞。超时时必须杀死并回收子进程：
     * 否则子进程继续写文件（父进程却上报半成品 hprof），且成为僵尸进程持续占用 COW 内存。
     *
     * @param pid 子进程 PID
     * @return true 仅当子进程以状态 0 正常退出；超时/异常退出返回 false
     */
    private fun waitForChild(pid: Int): Boolean {
        var remainingMs = FORK_WAIT_TIMEOUT_MS
        while (remainingMs > 0) {
            // 非阻塞等待子进程，WNOHANG 立即返回
            val status = waitPidNonBlocking(pid)
            if (status != WAIT_PID_RUNNING) {
                // 正值 = 正常退出 0；FORK_ERROR = 异常退出/失败，产物不可信
                return status > 0
            }
            // 子进程仍在运行，短暂等待后重试
            try {
                Thread.sleep(FORK_WAIT_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                // shutdown 中断轮询：同样必须终止子进程，不能留它继续写已被清理的路径
                Thread.currentThread().interrupt()
                killAndReapChild(pid)
                return false
            }
            remainingMs -= FORK_WAIT_POLL_INTERVAL_MS
        }
        // 超时：杀死并回收子进程，绝不把可能写了一半的 hprof 当作有效产物上报
        logger.w("HprofDumper: fork child $pid did not finish within ${FORK_WAIT_TIMEOUT_MS}ms; killing")
        killAndReapChild(pid)
        return false
    }

    /**
     * Native 方法：SIGKILL 子进程并阻塞 waitpid 回收，避免僵尸进程。
     *
     * @param pid 子进程 PID
     */
    private external fun killAndReapChild(pid: Int)

    /**
     * Native 方法：fork 子进程并在子进程中执行 hprof dump。
     *
     * @param outputPath hprof 文件输出路径
     * @return 子进程 PID（>0 表示父进程，0 表示子进程，-1 表示 fork 失败）
     */
    private external fun nativeForkAndDump(outputPath: String): Int

    /**
     * 非阻塞 waitpid 的包装方法。
     * 调用原生 waitpid(pid, WNOHANG) 检查子进程状态。
     *
     * @param pid 子进程 PID
     * @return 0 表示子进程仍在运行，非 0 表示子进程已退出
     */
    private external fun waitPidNonBlocking(pid: Int): Int

    /** 替换文件名中的非字母数字字符，防止文件名非法。 */
    private fun String.replaceNonAlpha(): String =
        replace(Regex(NON_ALPHA_PATTERN), REPLACEMENT_CHAR)

    /** 关闭 dump 工作线程。 */
    fun shutdown() {
        synchronized(sessionLock) { closed = true }
        // Drop queued dumps and interrupt cooperative work; an in-flight ART dump cleans up on return.
        dumpExecutor.shutdownNow()
    }

    /**
     * 清理旧 hprof 文件，原始与 strip 产物共享 maxFiles 个文件的保留预算。
     * 在模块启动时调用；每次成功 dump 也会执行同一清理规则。
     */
    fun cleanupOldFiles(maxFiles: Int = DEFAULT_MAX_FILES) {
        pruneOldFiles(maxFiles.coerceAtLeast(0), null)
    }

    /** Retains the file about to be reported before ranking older raw/stripped artifacts together. */
    private fun pruneOldFiles(maxFiles: Int, reportFile: File?) {
        val files = hprofDir.listFiles { f -> f.isFile && f.name.endsWith(HPROF_EXTENSION) }
            ?.sortedWith(
                compareByDescending<File> { it == reportFile }
                    .thenByDescending { it.lastModified() }
                    .thenBy { it.name }
            )
            ?: return
        // 超出保留数量的旧文件删除
        if (files.size > maxFiles) {
            files.drop(maxFiles).forEach { it.delete() }
        }
    }

    companion object {
        /** 工作线程名。 */
        private const val THREAD_NAME = "hprof-dumper"
        /** hprof 文件存储路径。 */
        private const val HPROF_DIR_PATH = "apm/hprof"
        /** hprof 文件扩展名。 */
        private const val HPROF_EXTENSION = ".hprof"
        /** strip 后文件后缀。 */
        private const val STRIPPED_SUFFIX = "_stripped.hprof"
        /** dump 事件名。 */
        private const val EVENT_HPROF_DUMP = "hprof_dump"
        /** 文件名非法字符正则。 */
        private const val NON_ALPHA_PATTERN = "[^a-zA-Z0-9_.-]"
        /** 替换字符。 */
        private const val REPLACEMENT_CHAR = "_"
        /** 默认保留文件数。 */
        private const val DEFAULT_MAX_FILES = 5
        /** Bounds the host-supplied reason portion below common filesystem filename limits. */
        private const val MAX_REASON_FILENAME_LENGTH = 64
        /** 字节/KB。 */
        private const val BYTES_PER_KB = 1024L
        /** fork dump JNI 库名。 */
        private const val LIB_APM_DUMPER = "apm_dumper"
        /** fork 失败返回值。 */
        private const val FORK_ERROR = -1
        /** 等待子进程超时时间（毫秒）。 */
        private const val FORK_WAIT_TIMEOUT_MS = 10_000L
        /** 等待子进程轮询间隔（毫秒）。 */
        private const val FORK_WAIT_POLL_INTERVAL_MS = 100L
        /** waitpid 返回值：子进程仍在运行。 */
        private const val WAIT_PID_RUNNING = 0
    }
}
