package com.apm.battery

import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * CPU Jiffies 采样器。
 * 通过读取 /proc 文件系统获取进程和线程的 CPU 使用信息。
 * 计算 CPU 使用率 = jiffies delta / (采样间隔 * 时钟频率 * CPU 核数)。
 *
 * 对标 Matrix BatteryCanary 的线程 CPU 监控方案。
 */
class CpuJiffiesSampler(
    /** 模块配置。 */
    private val config: BatteryConfig
) {

    /** 上次采样的进程 jiffies。 */
    private var lastProcessJiffies: Long = 0L
    /** 上次采样时间（毫秒）。 */
    private var lastSampleTime: Long = 0L
    /** 持续高 CPU 的起始时间。 */
    private var highCpuSince: Long = 0L
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
        lastSampleTime = System.currentTimeMillis()
        highCpuSince = 0L
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
        if (!sampling) return

        val currentJiffies = readProcessJiffies()
        val currentTime = System.currentTimeMillis()
        // 计算间隔
        val intervalMs = currentTime - lastSampleTime
        if (intervalMs <= 0L) {
            lastProcessJiffies = currentJiffies
            lastSampleTime = currentTime
            return
        }
        // 计算 jiffies 增量
        val jiffiesDelta = currentJiffies - lastProcessJiffies
        // 估算 CPU 使用率（简化计算，假设 100 Hz 时钟频率）
        val cpuPercent = jiffiesDelta.toFloat() / (intervalMs / JIFFIES_TO_MS_FACTOR)
        // 更新采样基准
        lastProcessJiffies = currentJiffies
        lastSampleTime = currentTime
        // 检测高 CPU
        if (cpuPercent >= config.cpuThresholdPercent) {
            if (highCpuSince == 0L) {
                highCpuSince = currentTime
            }
            // 持续高 CPU 超过阈值
            val sustainedSec = (currentTime - highCpuSince) / 1000L
            if (sustainedSec >= config.cpuSustainedSeconds) {
                onCpuHigh?.invoke(cpuPercent, sustainedSec)
                // 重置，避免重复上报
                highCpuSince = currentTime
            }
        } else {
            // CPU 恢复正常，重置计时
            highCpuSince = 0L
        }
    }

    /**
     * 读取进程 jiffies。
     * 从 /proc/self/stat 第 14-15 列获取 utime + stime。
     */
    private fun readProcessJiffies(): Long {
        try {
            val file = File(PROC_SELF_STAT)
            if (!file.exists()) return 0L
            val reader = BufferedReader(FileReader(file), BUFFER_SIZE)
            reader.use {
                val line = it.readLine() ?: return 0L
                val parts = line.split(" ")
                if (parts.size < FIELD_STIME_INDEX + 1) return 0L
                // utime = index 13, stime = index 14
                val utime = parts[FIELD_UTIME_INDEX].toLongOrNull() ?: 0L
                val stime = parts[FIELD_STIME_INDEX].toLongOrNull() ?: 0L
                return utime + stime
            }
        } catch (_: Exception) {
            return 0L
        }
    }

    companion object {
        /** /proc/self/stat 路径。 */
        private const val PROC_SELF_STAT = "/proc/self/stat"
        /** utime 在 stat 中的索引（0-based）。 */
        private const val FIELD_UTIME_INDEX = 13
        /** stime 在 stat 中的索引（0-based）。 */
        private const val FIELD_STIME_INDEX = 14
        /** jiffies 转毫秒因子（100 Hz → 10ms per jiffie）。 */
        private const val JIFFIES_TO_MS_FACTOR = 10.0f
        /** 读取 buffer 大小。 */
        private const val BUFFER_SIZE = 1024
    }
}
