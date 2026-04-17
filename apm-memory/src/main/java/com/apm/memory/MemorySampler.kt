package com.apm.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.apm.core.currentProcessNameCompat
import java.io.File

/**
 * 内存指标采集器。
 * 通过 Android API 和 /proc 文件系统采集多维度内存数据。
 *
 * 注意：PSS 采集耗时 5~20ms，必须在子线程调用。
 */
internal class MemorySampler(
    /** 应用上下文，用于获取系统服务。 */
    private val context: Context
) {
    /** ActivityManager 实例，用于查询系统内存状态。 */
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    /** Java Runtime 实例，用于查询 Java Heap。 */
    private val runtime = Runtime.getRuntime()

    /**
     * 构建一次完整的内存快照。采集 Java Heap + PSS + Native + 系统 + /proc + GC。
     *
     * @param scene 当前场景标签
     * @param foreground 是否前台
     * @return 完整的内存快照
     */
    fun buildSnapshot(scene: String, foreground: Boolean): MemorySnapshot {
        // PSS 采集：耗时 5~20ms
        val pssInfo = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
        // 系统内存状态
        val activityManagerInfo = ActivityManager.MemoryInfo().apply {
            activityManager.getMemoryInfo(this)
        }
        // /proc/self/status 中的 VmRSS/VmPeak
        val procStatus = collectProcStatus()
        // GC 统计（API 23+）
        val (gcCount, gcTime) = collectGcStats()

        // Java Heap 指标（转换为 MB）
        val maxMb = runtime.maxMemory().toMb()
        val totalMb = runtime.totalMemory().toMb()
        val freeMb = runtime.freeMemory().toMb()

        return MemorySnapshot(
            processName = context.currentProcessNameCompat(),
            javaHeapUsedMb = totalMb - freeMb,
            javaHeapMaxMb = maxMb,
            javaHeapFreeMb = freeMb,
            totalPssKb = pssInfo.totalPss,
            dalvikPssKb = pssInfo.dalvikPss,
            nativePssKb = pssInfo.nativePss,
            otherPssKb = pssInfo.otherPss,
            // GPU 内存仅 API 23+ 可获取
            graphicsPssKb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pssInfo.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0
            } else {
                0
            },
            nativeHeapSizeKb = Debug.getNativeHeapSize() / BYTES_PER_KB,
            nativeHeapAllocatedKb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_KB,
            nativeHeapFreeKb = Debug.getNativeHeapFreeSize() / BYTES_PER_KB,
            systemAvailMemKb = activityManagerInfo.availMem / BYTES_PER_KB,
            systemTotalMemKb = activityManagerInfo.totalMem / BYTES_PER_KB,
            isLowMemory = activityManagerInfo.lowMemory,
            lowMemThresholdKb = activityManagerInfo.threshold / BYTES_PER_KB,
            vmRssKb = procStatus.vmRssKb,
            vmPeakKb = procStatus.vmPeakKb,
            gcCount = gcCount,
            gcTimeMs = gcTime,
            scene = scene,
            foreground = foreground
        )
    }

    /**
     * 采集 GC 统计信息。API 23+ 可用。
     * @return (gcCount, gcTimeMs)
     */
    private fun collectGcStats(): Pair<Long, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0L to 0L
        val count = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        val time = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L
        return count to time
    }

    /**
     * 解析 /proc/self/status 获取 VmRSS 和 VmPeak。
     * 文件格式示例：VmRSS:    123456 kB
     */
    private fun collectProcStatus(): ProcStatus {
        return runCatching {
            var vmRss = 0L
            var vmPeak = 0L
            File(PROC_STATUS_PATH).forEachLine { line ->
                when {
                    line.startsWith(VMRSS_PREFIX) -> vmRss = line.kbValue()
                    line.startsWith(VMPEAK_PREFIX) -> vmPeak = line.kbValue()
                }
            }
            ProcStatus(vmRss, vmPeak)
        }.getOrDefault(ProcStatus(0L, 0L))
    }

    /** 从 /proc 行中提取 KB 数值。格式："VmRSS:    123456 kB" */
    private fun String.kbValue(): Long {
        return trim().split(Regex("\\s+")).getOrNull(KB_VALUE_INDEX)?.toLongOrNull() ?: 0L
    }

    /** 字节转 MB。 */
    private fun Long.toMb(): Long = this / BYTES_PER_MB

    /** /proc/self/status 的解析结果。 */
    private data class ProcStatus(
        val vmRssKb: Long,
        val vmPeakKb: Long
    )

    companion object {
        /** 字节/KB。 */
        private const val BYTES_PER_KB = 1024L
        /** 字节/MB。 */
        private const val BYTES_PER_MB = 1024L * 1024L
        /** /proc/self/status 文件路径。 */
        private const val PROC_STATUS_PATH = "/proc/self/status"
        /** VmRSS 行前缀。 */
        private const val VMRSS_PREFIX = "VmRSS:"
        /** VmPeak 行前缀。 */
        private const val VMPEAK_PREFIX = "VmPeak:"
        /** KB 值在 split 结果中的索引。 */
        private const val KB_VALUE_INDEX = 1
    }
}
