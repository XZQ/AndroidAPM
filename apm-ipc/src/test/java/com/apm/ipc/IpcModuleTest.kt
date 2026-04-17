package com.apm.ipc

import org.junit.Assert.*
import org.junit.Test

/**
 * IpcModule 核心逻辑测试。
 * 验证模块生命周期、Binder 调用阈值、配置参数等行为。
 */
class IpcModuleTest {

    /** 模块名正确。 */
    @Test
    fun `module name is ipc`() {
        val module = IpcModule()
        assertEquals("ipc", module.name)
    }

    /** 默认配置开启监控。 */
    @Test
    fun `default config enables ipc monitor`() {
        val config = IpcConfig()
        assertTrue(config.enableIpcMonitor)
    }

    /** 默认 Binder 阈值 500ms。 */
    @Test
    fun `default binder threshold is 500ms`() {
        val config = IpcConfig()
        assertEquals(500L, config.binderThresholdMs)
    }

    /** 默认主线程 Binder 阈值 100ms。 */
    @Test
    fun `default main thread binder threshold is 100ms`() {
        val config = IpcConfig()
        assertEquals(100L, config.mainThreadBinderThresholdMs)
    }

    /** 默认堆栈长度 4000。 */
    @Test
    fun `default max stack trace length is 4000`() {
        val config = IpcConfig()
        assertEquals(4000, config.maxStackTraceLength)
    }

    /** 默认开启 Binder Hook。 */
    @Test
    fun `default enable binder hook is true`() {
        val config = IpcConfig()
        assertTrue(config.enableBinderHook)
    }

    /** 默认开启聚合。 */
    @Test
    fun `default enable binder aggregation is true`() {
        val config = IpcConfig()
        assertTrue(config.enableBinderAggregation)
    }

    /** 默认聚合窗口 50。 */
    @Test
    fun `default aggregation window size is 50`() {
        val config = IpcConfig()
        assertEquals(50, config.aggregationWindowSize)
    }

    /** 未 start 时 onBinderCallComplete 不上报。 */
    @Test
    fun `onBinderCallComplete is ignored when not started`() {
        val module = IpcModule()
        // 未调用 onStart，started = false
        module.onBinderCallComplete("IServiceManager", "getService", 600L)
        // 无异常即通过
    }

    /** onStop 后模块停止。 */
    @Test
    fun `module stops cleanly`() {
        val module = IpcModule()
        module.onStop()
        module.onBinderCallComplete("IServiceManager", "getService", 600L)
        // 无异常即通过
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val config = IpcConfig(
            enableIpcMonitor = false,
            binderThresholdMs = 1000L,
            mainThreadBinderThresholdMs = 200L,
            maxStackTraceLength = 2000
        )
        assertFalse(config.enableIpcMonitor)
        assertEquals(1000L, config.binderThresholdMs)
        assertEquals(200L, config.mainThreadBinderThresholdMs)
        assertEquals(2000, config.maxStackTraceLength)
    }

    /** 主线程阈值低于后台线程阈值。 */
    @Test
    fun `main thread threshold is stricter than background`() {
        val config = IpcConfig()
        assertTrue(config.mainThreadBinderThresholdMs < config.binderThresholdMs)
    }
}
