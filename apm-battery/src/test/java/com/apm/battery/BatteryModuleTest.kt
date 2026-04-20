package com.apm.battery

import org.junit.Assert.*
import org.junit.Test

/**
 * BatteryModule 配置和参数测试。
 *
 * 注：BatteryModule 构造函数内属性初始化使用 Handler(Looper.getMainLooper())
 * 和 BroadcastReceiver（依赖 Context），纯 JUnit 环境无法实例化 Module。
 * Module 集成测试应在 Android Instrumentation 环境中执行。
 * 此文件验证 Config 层默认值和自定义值覆盖。
 */
class BatteryModuleTest {

    /** 默认配置开启电量监控。 */
    @Test
    fun `default config enables battery monitor`() {
        val config = BatteryConfig()
        assertTrue(config.enableBatteryMonitor)
    }

    /** 默认 WakeLock 告警阈值 60 秒。 */
    @Test
    fun `default wakeLock threshold is 60 seconds`() {
        val config = BatteryConfig()
        assertEquals(60_000L, config.wakeLockThresholdMs)
    }

    /** 默认 GPS 告警阈值 30 秒。 */
    @Test
    fun `default gps threshold is 30 seconds`() {
        val config = BatteryConfig()
        assertEquals(30_000L, config.gpsThresholdMs)
    }

    /** 默认检测间隔 60 秒。 */
    @Test
    fun `default check interval is 60 seconds`() {
        val config = BatteryConfig()
        assertEquals(60_000L, config.checkIntervalMs)
    }

    /** 默认电量下降告警 5%。 */
    @Test
    fun `default battery drain percent is 5`() {
        val config = BatteryConfig()
        assertEquals(5, config.batteryDrainPercent)
    }

    /** 默认堆栈最大长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = BatteryConfig()
        assertEquals(4_000, config.maxStackTraceLength)
    }

    /** 默认开启 WakeLock Hook。 */
    @Test
    fun `default wakeLock hook is enabled`() {
        val config = BatteryConfig()
        assertTrue(config.enableWakeLockHook)
    }

    /** 默认开启后台闹钟监控。 */
    @Test
    fun `default alarm monitor is enabled`() {
        val config = BatteryConfig()
        assertTrue(config.enableAlarmMonitor)
    }

    /** 默认开启 GPS 监控。 */
    @Test
    fun `default gps monitor is enabled`() {
        val config = BatteryConfig()
        assertTrue(config.enableGpsMonitor)
    }

    /** 默认开启 CPU 监控。 */
    @Test
    fun `default cpu monitor is enabled`() {
        val config = BatteryConfig()
        assertTrue(config.enableCpuMonitor)
    }

    /** 默认 CPU 使用率告警阈值 80%。 */
    @Test
    fun `default cpu threshold is 80 percent`() {
        val config = BatteryConfig()
        assertEquals(0.8f, config.cpuThresholdPercent, 0.001f)
    }

    /** 默认 CPU 高使用率持续时长 30 秒。 */
    @Test
    fun `default cpu sustained seconds is 30`() {
        val config = BatteryConfig()
        assertEquals(30L, config.cpuSustainedSeconds)
    }

    /** 默认后台闹钟频率阈值 12 次。 */
    @Test
    fun `default alarm flood threshold is 12`() {
        val config = BatteryConfig()
        assertEquals(12, config.alarmFloodThreshold)
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = BatteryConfig(
            enableBatteryMonitor = false,
            wakeLockThresholdMs = 120_000L,
            gpsThresholdMs = 60_000L,
            checkIntervalMs = 30_000L,
            batteryDrainPercent = 10,
            enableWakeLockHook = false,
            enableAlarmMonitor = false,
            enableGpsMonitor = false,
            enableCpuMonitor = false,
            cpuThresholdPercent = 0.9f,
            cpuSustainedSeconds = 60L,
            alarmFloodThreshold = 20
        )
        // 验证所有自定义值已生效
        assertFalse(config.enableBatteryMonitor)
        assertEquals(120_000L, config.wakeLockThresholdMs)
        assertEquals(60_000L, config.gpsThresholdMs)
        assertEquals(30_000L, config.checkIntervalMs)
        assertEquals(10, config.batteryDrainPercent)
        assertFalse(config.enableWakeLockHook)
        assertFalse(config.enableAlarmMonitor)
        assertFalse(config.enableGpsMonitor)
        assertFalse(config.enableCpuMonitor)
        assertEquals(0.9f, config.cpuThresholdPercent, 0.001f)
        assertEquals(60L, config.cpuSustainedSeconds)
        assertEquals(20, config.alarmFloodThreshold)
    }

    /** CPU 阈值在合法范围 0~1。 */
    @Test
    fun `cpu threshold is in valid range`() {
        val config = BatteryConfig()
        // CPU 使用率阈值应为 0~1 之间的浮点数
        assertTrue(config.cpuThresholdPercent in 0f..1f)
    }

    /** 电量下降百分比大于 0。 */
    @Test
    fun `battery drain percent is positive`() {
        val config = BatteryConfig()
        // 电量下降百分比应为正整数
        assertTrue(config.batteryDrainPercent > 0)
    }
}
