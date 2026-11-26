package com.apm.battery

import com.apm.core.Apm
import com.apm.core.ApmClock
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * CPU Jiffies 采样器。
 * 通过读取 /proc 文件系统获取进程的 CPU 使用信息。
 *
 * 使用率语义：单核占用分数 —— jiffies 增量换算的 CPU 时间 / 墙钟间隔，
 * 1.0 表示持续占满一个核，多线程负载可超过 1.0（上限为核数）。
 * 时钟频率通过 Os.sysconf(_SC_CLK_TCK) 读取（此前硬编码 100 Hz，
 * 在非 100 Hz 内核上会产生系统性偏差）。
 *
 * 对标 Matrix BatteryCanary 的线程 CPU 监控方案。
 */
class CpuJiffiesSampler(
    /** 模块配置。 */
    private val config: BatteryConfig,
    /** 内核时钟频率（jiffies/秒）；默认从系统读取，测试可注入。 */
    private val clockTickHz: Long = resolveClockTickHz(),
    /** 进程 jiffies 读取器；默认读 /proc/self/stat，测试可注入。 */
    private val jiffiesReader: (() -> Long)? = null,
    /** Monotonic time source in milliseconds; tests may inject deterministic values. */
    private val clock: () -> Long = ApmClock::monotonicTimeMillis
) {

    /** 上次采样的进程 jiffies。 */
    private var lastProcessJiffies: Long = 0L
    /** 上次采样时间（毫秒）。 */
    private var lastSampleTime: Long = 0L
    /** 持续高 CPU 的起始时间；[NO_HIGH_CPU_SINCE] 表示当前未处于高 CPU 区间。 */
    private var highCpuSince: Long = NO_HIGH_CPU_SINCE
    /** 是否正在采样。 */
    @Volatile
    private var sampling = false
    /** 事件回调。 */
    var onCpuHigh: ((cpuPercent: Float, durationSec: Long) -> Unit)? = null

    /**
     * 启动采样。
     */
    fun start() {
        sampling = true
        lastProcessJiffies = readProcessJiffies()
        lastSampleTime = clock()
        highCpuSince = NO_HIGH_CPU_SINCE
    }

    /**
     * 停止采样。
     */
    fun stop() {
        sampling = false
    }

    /**
     * 执行一次采样。
     * 计算当前 CPU 使用率并检测持续高 CPU。
     */
    fun sample() {
        if (!sampling) {
            return
        }

        val currentJiffies = readProcessJiffies()
        val currentTime = clock()
        // 计算间隔
        val intervalMs = currentTime - lastSampleTime
        if (intervalMs <= 0L) {
            lastProcessJiffies = currentJiffies
            lastSampleTime = currentTime
            return
        }
        // 计算 jiffies 增量
        val jiffiesDelta = currentJiffies - lastProcessJiffies
        // jiffies → CPU 毫秒：jiffies * (1000 / clockTickHz)
        val cpuTimeMs = jiffiesDelta.toFloat() * MILLIS_PER_SECOND / clockTickHz
        // 单核占用分数（1.0 = 占满一个核），按核数截断防御异常读数
        val maxFraction = Runtime.getRuntime().availableProcessors().toFloat()
        val cpuPercent = (cpuTimeMs / intervalMs).coerceIn(0f, maxFraction)
        // 更新采样基准
        lastProcessJiffies = currentJiffies
        lastSampleTime = currentTime
        // 检测高 CPU
        if (cpuPercent >= config.cpuThresholdPercent) {
            if (highCpuSince == NO_HIGH_CPU_SINCE) {
                // 本次采样窗口整体高于阈值，持续起点回溯到窗口起始时刻
                highCpuSince = currentTime - intervalMs
            }
            // 持续高 CPU 超过阈值
            val sustainedSec = (currentTime - highCpuSince) / MILLIS_PER_SECOND_LONG
            if (sustainedSec >= config.cpuSustainedSeconds) {
                onCpuHigh?.invoke(cpuPercent, sustainedSec)
                // 重置起点到当前时刻，下一轮需再次满足完整持续时长才重复上报
                highCpuSince = currentTime
            }
        } else {
            // CPU 恢复正常，重置计时
            highCpuSince = NO_HIGH_CPU_SINCE
        }
    }

    /**
     * 读取进程 jiffies。
     * 从 /proc/self/stat 获取 utime + stime。
     */
    private fun readProcessJiffies(): Long {
        // 测试注入的读取器优先
        jiffiesReader?.let { return it() }
        try {
            val file = File(PROC_SELF_STAT)
            if (!file.exists()) {
                return 0L
            }
            val reader = BufferedReader(FileReader(file), BUFFER_SIZE)
            reader.use {
                val line = it.readLine() ?: return 0L
                return parseProcStatJiffies(line)
            }
        } catch (e: Exception) {
            // /proc/self/stat 读取失败降级为 0，记入自监控便于发现持续性失效
            Apm.recordInternalError(ERROR_TAG_PROC_STAT_READ, e)
            return 0L
        }
    }

    companion object {
        /** 自监控 tag：/proc/self/stat 读取失败。 */
        private const val ERROR_TAG_PROC_STAT_READ = "battery_proc_stat_read"

        /** 未处于高 CPU 持续区间时的哨兵值（避免与单调时钟 0ms 混淆）。 */
        private const val NO_HIGH_CPU_SINCE = -1L

        /** /proc/self/stat 路径。 */
        private const val PROC_SELF_STAT = "/proc/self/stat"
        /** comm 右括号之后字段列表中 utime 的索引（对应 stat 第 14 列，0-based 为 11）。 */
        private const val POST_COMM_UTIME_INDEX = 11
        /** comm 右括号之后字段列表中 stime 的索引（对应 stat 第 15 列，0-based 为 12）。 */
        private const val POST_COMM_STIME_INDEX = 12
        /** 读取 buffer 大小。 */
        private const val BUFFER_SIZE = 1024
        /** 每秒毫秒数（浮点换算）。 */
        private const val MILLIS_PER_SECOND = 1000f
        /** 每秒毫秒数（整数持续秒换算）。 */
        private const val MILLIS_PER_SECOND_LONG = 1000L
        /** 时钟频率读取失败时的回退值（Linux 常见默认 100 Hz）。 */
        private const val DEFAULT_CLOCK_TICK_HZ = 100L

        /**
         * 从 `/proc/[pid]/stat` 单行文本解析 `utime + stime`。
         *
         * 第二列 `(comm)` 允许包含空格和括号（如 `(AsyncTask #1)`），因此必须定位最后一个 `)`
         * 之后再按空格切分，避免进程名含空格导致后续列整体偏移。
         */
        internal fun parseProcStatJiffies(statLine: String): Long {
            val commEnd = statLine.lastIndexOf(')')
            if (commEnd < 0 || commEnd + 2 >= statLine.length) {
                return 0L
            }
            val fieldsAfterComm = statLine.substring(commEnd + 2).trim().split(' ')
            if (fieldsAfterComm.size <= POST_COMM_STIME_INDEX) {
                return 0L
            }
            val utime = fieldsAfterComm[POST_COMM_UTIME_INDEX].toLongOrNull() ?: 0L
            val stime = fieldsAfterComm[POST_COMM_STIME_INDEX].toLongOrNull() ?: 0L
            return utime + stime
        }

        /**
         * 读取内核时钟频率（jiffies/秒）。
         * 优先 Os.sysconf(_SC_CLK_TCK)（API 21+ 官方接口），
         * JVM 测试环境或异常时回退 100 Hz。
         *
         * @return 时钟频率
         */
        private fun resolveClockTickHz(): Long {
            return try {
                val ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
                // 读到非正值同样回退默认
                if (ticks > 0L) ticks else DEFAULT_CLOCK_TICK_HZ
            } catch (_: Throwable) {
                // JVM 单测（android.system 不可用）或个别 ROM 异常时回退
                DEFAULT_CLOCK_TICK_HZ
            }
        }
    }
}
