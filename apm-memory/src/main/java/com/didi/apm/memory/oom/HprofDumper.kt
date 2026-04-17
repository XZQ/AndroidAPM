package com.didi.apm.memory.oom

import android.content.Context
import android.os.Debug
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmLogger
import com.didi.apm.memory.MemoryConfig
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity
import java.io.File
import java.util.concurrent.Executors

/**
 * Hprof 文件 Dump 器。
 * 在 OOM 危险阈值触发时，将 Java Heap 导出为 hprof 文件。
 *
 * MVP 版本使用直接 dump（Debug.dumpHprofData），会产生 1~3 秒的 STW。
 * 生产环境应升级为 fork 子进程 dump 方案。
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
     * 流程：生成文件 → dump → 可选 strip → 上报文件事件。
     */
    private fun dumpInternal(reason: String) {
        val hprofFile = File(hprofDir, "${System.currentTimeMillis()}_${reason.replaceNonAlpha()}$HPROF_EXTENSION")

        try {
            // MVP: 直接 dump（有 STW 代价）
            dumpDirectly(hprofFile)
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
    }
}
