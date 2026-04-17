package com.apm.battery

import org.junit.Assert.*
import org.junit.Test

/**
 * BatteryConfig 默认值测试。
 */
class BatteryConfigTest {

    /** 默认开启。 */
    @Test
    fun `default enableBatteryMonitor is true`() {
        val config = BatteryConfig()
        assertTrue(config.enableBatteryMonitor)
    }

    /** 默认 WakeLock 阈值 60 秒。 */
    @Test
    fun `default wakeLockThresholdMs is 60 seconds`() {
        val config = BatteryConfig()
        assertEquals(60_000L, config.wakeLockThresholdMs)
    }

    /** 默认 GPS 阈值 30 秒。 */
    @Test
    fun `default gpsThresholdMs is 30 seconds`() {
        val config = BatteryConfig()
        assertEquals(30_000L, config.gpsThresholdMs)
    }

    /** 默认电量下降告警 5%。 */
    @Test
    fun `default batteryDrainPercent is 5`() {
        val config = BatteryConfig()
        assertEquals(5, config.batteryDrainPercent)
    }

    /** 自定义参数覆盖。 */
    @Test
    fun `custom values override defaults`() {
        val config = BatteryConfig(wakeLockThresholdMs = 120_000L, batteryDrainPercent = 10)
        assertEquals(120_000L, config.wakeLockThresholdMs)
        assertEquals(10, config.batteryDrainPercent)
    }
}
