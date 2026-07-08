package com.apm.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 电量监控模块。
 * 监控电量消耗异常：WakeLock 持有时间过长、GPS 持续使用、电量快速下降等。
 *
 * 监控策略：
 * 1. 注册电量变化广播，跟踪电量下降速度
 * 2. 跟踪活跃 WakeLock 的持有时长
 * 3. 检测 GPS/Sensor 等高耗电硬件的持续使用
 */
class BatteryModule(private val config: BatteryConfig = BatteryConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 是否已启动。 */
    @Volatile
    private var started = false

    // --- 电量跟踪 ---
    /** 上一次记录的电量百分比。 */
    private var lastBatteryLevel: Int = -1
    /** 上一次记录电量的时间。 */
    private var lastBatteryTime: Long = 0L

    // --- WakeLock 跟踪 ---
    /** 活跃的 WakeLock 记录：tag → acquire 时间。 */
    private val activeWakeLocks = ConcurrentHashMap<String, Long>()

    /** Active GPS sessions mapped from caller tag to start time. */
    private val activeGpsSessions = ConcurrentHashMap<String, Long>()

    /** Alarm scheduling timestamps retained for one detection window. */
    private val alarmTimestamps = ConcurrentLinkedQueue<Long>()

    /** Optional process CPU sampler. */
    private var cpuJiffiesSampler: CpuJiffiesSampler? = null

    /** 主线程 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 定时检测任务。 */
    private val checkTask = object : Runnable {
        override fun run() {
            if (!started) {
                return
            }
            checkBatteryDrain()
            checkWakeLocks()
            checkGpsSessions()
            cpuJiffiesSampler?.sample()
            mainHandler.postDelayed(this, config.checkIntervalMs)
        }
    }

    /** 电量变化广播接收器。 */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (scale > 0) {
                    val percent = level * 100 / scale
                    onBatteryLevelChanged(percent)
                }
            }
        }
    }

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /** 注册电量广播和定时检测。 */
    override fun onStart() {
        if (!config.enableBatteryMonitor) {
            return
        }
        started = true
        // 注册电量变化广播
        apmContext?.application?.registerReceiver(batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 启动定时检测
        mainHandler.postDelayed(checkTask, config.checkIntervalMs)
        if (config.enableCpuMonitor) {
            cpuJiffiesSampler = CpuJiffiesSampler(config).apply {
                onCpuHigh = { cpuPercent, durationSec ->
                    Apm.emit(
                        module = MODULE_NAME,
                        name = EVENT_CPU_HIGH,
                        kind = ApmEventKind.ALERT,
                        severity = ApmSeverity.WARN,
                        priority = ApmPriority.LOW,
                        fields = mapOf(
                            FIELD_CPU_PERCENT to cpuPercent,
                            FIELD_DURATION_SECONDS to durationSec,
                            FIELD_THRESHOLD to config.cpuThresholdPercent
                        )
                    )
                }
                start()
            }
        }
        apmContext?.logger?.d("Battery module started")
    }

    /** 注销广播和定时检测。 */
    override fun onStop() {
        started = false
        mainHandler.removeCallbacks(checkTask)
        cpuJiffiesSampler?.stop()
        cpuJiffiesSampler = null
        activeWakeLocks.clear()
        activeGpsSessions.clear()
        alarmTimestamps.clear()
        // A later restart must establish a fresh battery drain baseline.
        lastBatteryLevel = -1
        lastBatteryTime = 0L
        try {
            apmContext?.application?.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
            // 忽略未注册异常
        }
    }

    /**
     * 记录 WakeLock 获取。
     * 由外部（如代理 PowerManager）调用。
     */
    fun onWakeLockAcquired(tag: String) {
        if (!started || !config.enableWakeLockHook) {
            return
        }
        activeWakeLocks[tag] = System.currentTimeMillis()
    }

    /**
     * 记录 WakeLock 释放。
     * 检查持有时长是否超过阈值。
     */
    fun onWakeLockReleased(tag: String) {
        if (!started || !config.enableWakeLockHook) {
            return
        }
        val acquireTime = activeWakeLocks.remove(tag) ?: return
        val duration = System.currentTimeMillis() - acquireTime
        if (duration >= config.wakeLockThresholdMs) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_WAKELOCK_HELD,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                fields = mapOf(
                    FIELD_WAKELOCK_TAG to tag,
                    FIELD_DURATION_MS to duration,
                    FIELD_THRESHOLD to config.wakeLockThresholdMs
                )
            )
        }
    }

    /**
     * Records the beginning of an application-owned GPS session.
     *
     * @param tag stable location request identifier
     */
    fun onGpsStarted(tag: String) {
        if (!started || !config.enableGpsMonitor) {
            return
        }
        activeGpsSessions[tag] = System.currentTimeMillis()
    }

    /**
     * Records the end of an application-owned GPS session.
     *
     * @param tag stable location request identifier
     */
    fun onGpsStopped(tag: String) {
        if (!started || !config.enableGpsMonitor) {
            return
        }
        val startedAt = activeGpsSessions.remove(tag) ?: return
        val duration = System.currentTimeMillis() - startedAt
        if (duration >= config.gpsThresholdMs) {
            emitGpsUsage(tag, duration, EVENT_GPS_USED_TOO_LONG)
        }
    }

    /**
     * Records one application alarm schedule operation.
     *
     * Call this from the application's AlarmManager wrapper. A flood event is
     * emitted when the configured count is reached within one check interval.
     */
    fun onAlarmScheduled() {
        if (!started || !config.enableAlarmMonitor || config.alarmFloodThreshold <= 0) {
            return
        }
        val now = System.currentTimeMillis()
        alarmTimestamps += now
        removeExpiredAlarms(now)
        if (alarmTimestamps.size >= config.alarmFloodThreshold) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_ALARM_FLOOD,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                priority = ApmPriority.LOW,
                fields = mapOf(
                    FIELD_ALARM_COUNT to alarmTimestamps.size,
                    FIELD_DURATION_MS to config.checkIntervalMs,
                    FIELD_THRESHOLD to config.alarmFloodThreshold
                )
            )
            alarmTimestamps.clear()
        }
    }

    /**
     * 电量变化回调。
     * 跟踪电量下降速度。
     */
    private fun onBatteryLevelChanged(percent: Int) {
        if (lastBatteryLevel < 0) {
            // 首次记录
            lastBatteryLevel = percent
            lastBatteryTime = System.currentTimeMillis()
            return
        }

        val drop = lastBatteryLevel - percent
        if (drop >= config.batteryDrainPercent) {
            val duration = System.currentTimeMillis() - lastBatteryTime
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_BATTERY_DRAIN,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                fields = mapOf(
                    FIELD_DROP_PERCENT to drop,
                    FIELD_DURATION_MS to duration,
                    FIELD_CURRENT_LEVEL to percent
                )
            )
            // 重置基准
            lastBatteryLevel = percent
            lastBatteryTime = System.currentTimeMillis()
        }
    }

    /** 检查长时间持有的 WakeLock。 */
    private fun checkWakeLocks() {
        if (!config.enableWakeLockHook) {
            return
        }
        val now = System.currentTimeMillis()
        for ((tag, acquireTime) in activeWakeLocks.toMap()) {
            val duration = now - acquireTime
            if (duration >= config.wakeLockThresholdMs) {
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_WAKELOCK_STILL_HELD,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                    fields = mapOf(
                        FIELD_WAKELOCK_TAG to tag,
                        FIELD_DURATION_MS to duration
                    )
                )
            }
        }
    }

    /** Checks application-reported GPS sessions that are still active. */
    private fun checkGpsSessions() {
        if (!config.enableGpsMonitor) {
            return
        }
        val now = System.currentTimeMillis()
        for ((tag, startedAt) in activeGpsSessions) {
            val duration = now - startedAt
            if (duration >= config.gpsThresholdMs) {
                emitGpsUsage(tag, duration, EVENT_GPS_STILL_ACTIVE)
            }
        }
    }

    /**
     * Emits a GPS duration event.
     *
     * @param tag caller-defined session tag
     * @param durationMs session duration
     * @param eventName event type
     */
    private fun emitGpsUsage(tag: String, durationMs: Long, eventName: String) {
        Apm.emit(
            module = MODULE_NAME,
            name = eventName,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_GPS_TAG to tag,
                FIELD_DURATION_MS to durationMs,
                FIELD_THRESHOLD to config.gpsThresholdMs
            )
        )
    }

    /**
     * Removes alarm timestamps outside the current detection window.
     *
     * @param now current wall-clock time
     */
    private fun removeExpiredAlarms(now: Long) {
        val cutoff = now - config.checkIntervalMs
        while (alarmTimestamps.peek()?.let { it < cutoff } == true) {
            alarmTimestamps.poll()
        }
    }

    /** 检查电量消耗速度。 */
    private fun checkBatteryDrain() {
        // 依赖广播更新，此处无需额外操作
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "battery"
        /** WakeLock 持有告警事件。 */
        private const val EVENT_WAKELOCK_HELD = "wakelock_held_too_long"
        /** WakeLock 仍在持有告警事件。 */
        private const val EVENT_WAKELOCK_STILL_HELD = "wakelock_still_held"
        /** 电量快速下降事件。 */
        private const val EVENT_BATTERY_DRAIN = "battery_drain"
        /** GPS session completed above the configured threshold. */
        private const val EVENT_GPS_USED_TOO_LONG = "gps_used_too_long"
        /** GPS session remains active above the configured threshold. */
        private const val EVENT_GPS_STILL_ACTIVE = "gps_still_active"
        /** Too many alarms were scheduled in one detection window. */
        private const val EVENT_ALARM_FLOOD = "alarm_schedule_flood"
        /** Sustained high process CPU event. */
        private const val EVENT_CPU_HIGH = "cpu_high_usage"
        /** 字段：WakeLock 标签。 */
        private const val FIELD_WAKELOCK_TAG = "wakeLockTag"
        /** Field: caller-defined GPS session tag. */
        private const val FIELD_GPS_TAG = "gpsTag"
        /** Field: number of alarms in the current window. */
        private const val FIELD_ALARM_COUNT = "alarmCount"
        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"
        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"
        /** 字段：下降百分比。 */
        private const val FIELD_DROP_PERCENT = "dropPercent"
        /** 字段：当前电量。 */
        private const val FIELD_CURRENT_LEVEL = "currentLevel"
        /** Field: process CPU ratio. */
        private const val FIELD_CPU_PERCENT = "cpuPercent"
        /** Field: sustained duration in seconds. */
        private const val FIELD_DURATION_SECONDS = "durationSec"
    }
}
