package com.didi.apm.battery

/**
 * 电量监控模块配置。
 * 包含 SystemService Hook 和 CPU jiffies 采样配置。
 */
data class BatteryConfig(
    /** 是否开启电量监控。 */
    val enableBatteryMonitor: Boolean = true,
    /** WakeLock 持有时长告警阈值（毫秒）。 */
    val wakeLockThresholdMs: Long = DEFAULT_WAKELOCK_THRESHOLD_MS,
    /** GPS 持续使用告警阈值（毫秒）。 */
    val gpsThresholdMs: Long = DEFAULT_GPS_THRESHOLD_MS,
    /** 电量消耗检测间隔（毫秒）。 */
    val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    /** 电量下降百分比告警阈值。 */
    val batteryDrainPercent: Int = DEFAULT_BATTERY_DRAIN_PERCENT,
    /** 最大堆栈截取长度。 */
    val maxStackTraceLength: Int = DEFAULT_MAX_STACK_LENGTH,
    /** 是否启用 WakeLock Hook（代理 PowerManager）。 */
    val enableWakeLockHook: Boolean = true,
    /** 是否启用后台闹钟监控。 */
    val enableAlarmMonitor: Boolean = true,
    /** 是否启用 GPS 监控。 */
    val enableGpsMonitor: Boolean = true,
    /** 是否启用 CPU jiffies 采样。 */
    val enableCpuMonitor: Boolean = true,
    /** CPU 使用率告警阈值（0-1）。 */
    val cpuThresholdPercent: Float = DEFAULT_CPU_THRESHOLD,
    /** CPU 高使用率持续时长告警阈值（秒）。 */
    val cpuSustainedSeconds: Long = DEFAULT_CPU_SUSTAINED_SECONDS,
    /** 后台闹钟频率告警阈值（次数）。 */
    val alarmFloodThreshold: Int = DEFAULT_ALARM_FLOOD_THRESHOLD
) {
    companion object {
        /** 默认 WakeLock 告警阈值：60 秒。 */
        private const val DEFAULT_WAKELOCK_THRESHOLD_MS = 60_000L
        /** 默认 GPS 告警阈值：30 秒。 */
        private const val DEFAULT_GPS_THRESHOLD_MS = 30_000L
        /** 默认检测间隔：60 秒。 */
        private const val DEFAULT_CHECK_INTERVAL_MS = 60_000L
        /** 默认电量下降告警：5%。 */
        private const val DEFAULT_BATTERY_DRAIN_PERCENT = 5
        /** 默认堆栈最大长度。 */
        private const val DEFAULT_MAX_STACK_LENGTH = 4000
        /** 默认 CPU 告警阈值：80%。 */
        private const val DEFAULT_CPU_THRESHOLD = 0.8f
        /** 默认 CPU 持续时长：30 秒。 */
        private const val DEFAULT_CPU_SUSTAINED_SECONDS = 30L
        /** 默认闹钟频率阈值：12 次。 */
        private const val DEFAULT_ALARM_FLOOD_THRESHOLD = 12
    }
}
