package com.apm.memory.oom

import android.content.Context
import android.os.Debug
import com.apm.core.Apm
import com.apm.core.ApmLogger
import com.apm.memory.MemoryConfig
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import java.io.File
import java.util.concurrent.Executors

/**
 * Hprof 文件 Dump 器。
 * 在 OOM 危险阈值触发时，将 Java Heap 导出为 hprof 文件。
 *
 * 默认使用直接 dump（Debug.dumpHprofData），会产生 1~3 秒的 STW。
 * fork 子进程 dump 依赖设备/ART 兼容性，需通过 [MemoryConfig.enableForkHprofDump] 显式开启。
 */
internal class HprofDumper(
    /** 应用上下文，用于获取缓存目录。 */
    private val context: Context,
    /** 内存模块配置。 */
    private val config: MemoryConfig,
    /** 日志接口。 */
    private val logger: ApmLogger
) {
    /** dump 工作线程。 */
    private val dumpExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME)
    }

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
        if (!config.enableHprofDump) return
        dumpExecutor.execute { dumpInternal(reason) }
    }

    /**
     * dump 执行逻辑。
     * 优先使用 fork 子进程 dump（无 STW），失败时降级为直接 dump。
     * 流程：生成文件 → dump → 可选 strip → 上报文件事件。
     */
    private fun dumpInternal(reason: String) {
        val hprofFile = File(hprofDir, "${System.currentTimeMillis()}_${reason.replaceNonAlpha()}$HPROF_EXTENSION")

        try {
            // 优先使用 fork 子进程 dump（无 STW）
            if (forkDumpAvailable) {
                val pid = nativeForkAndDump(hprofFile.absolutePath)
                if (pid > 0) {
                    // 父进程：等待子进程完成 dump
                    waitForChild(pid)
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
            logger.e("HprofDumper failed", e)
            return
        }

        // dump 失败或文件为空，不继续处理
        if (!hprofFile.exists() || hprofFile.length() == 0L) return

        // 可选 strip 裁剪（减少 60%~80% 文件大小）
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

        // 上报文件生成事件
        Apm.emit(
            module = MODULE,
            name = EVENT_HPROF_DUMP,
            kind = ApmEventKind.FILE,
            severity = ApmSeverity.INFO,
            fields = mapOf(
                "reason" to reason,
                "filePath" to uploadFile.absolutePath,
                "fileSizeKb" to uploadFile.length() / BYTES_PER_KB
            )
        )
    }

    /** 直接调用 Debug.dumpHprofData。 */
    private fun dumpDirectly(outputFile: File) {
        Debug.dumpHprofData(outputFile.absolutePath)
    }

    /**
     * 等待 fork 的子进程完成 hprof dump。
     * 使用循环 waitpid 带超时，避免无限阻塞。
     *
     * @param pid 子进程 PID
     */
    private fun waitForChild(pid: Int) {
        var remainingMs = FORK_WAIT_TIMEOUT_MS
        while (remainingMs > 0) {
            // 非阻塞等待子进程，WNOHANG 立即返回
            val status = waitPidNonBlocking(pid)
            if (status != WAIT_PID_RUNNING) {
                // 子进程已退出（正常或异常）
                return
            }
            // 子进程仍在运行，短暂等待后重试
            Thread.sleep(FORK_WAIT_POLL_INTERVAL_MS)
            remainingMs -= FORK_WAIT_POLL_INTERVAL_MS
        }
        // 超时，子进程可能仍在运行
        logger.w("HprofDumper: fork child $pid did not finish within ${FORK_WAIT_TIMEOUT_MS}ms")
    }

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
        dumpExecutor.shutdown()
    }

    /**
     * 清理旧 hprof 文件，保留最近的 maxFiles 个。
     * 在模块启动时调用。
     */
    fun cleanupOldFiles(maxFiles: Int = DEFAULT_MAX_FILES) {
        val files = hprofDir.listFiles { f -> f.name.endsWith(HPROF_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        // 超出保留数量的旧文件删除
        if (files.size > maxFiles) {
            files.drop(maxFiles).forEach { it.delete() }
        }
    }

    companion object {
        /** 模块名。 */
        private const val MODULE = "memory"
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
