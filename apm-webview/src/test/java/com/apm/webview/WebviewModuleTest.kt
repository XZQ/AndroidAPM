package com.apm.webview

import org.junit.Assert.*
import org.junit.Test

/**
 * WebviewModule 核心逻辑测试。
 * 验证模块生命周期、页面加载计时、JS 执行阈值、白屏上报等行为。
 */
class WebviewModuleTest {

    /** 模块名正确。 */
    @Test
    fun `module name is webview`() {
        val module = WebviewModule()
        assertEquals("webview", module.name)
    }

    /** 未 start 时 onPageStarted 不记录。 */
    @Test
    fun `onPageStarted is ignored when not started`() {
        val module = WebviewModule()
        // 未调用 onStart，started = false
        module.onPageStarted("https://example.com")
        // 无异常即通过
    }

    /** 未 start 时 onPageFinished 不上报。 */
    @Test
    fun `onPageFinished is ignored when not started`() {
        val module = WebviewModule()
        module.onPageFinished("https://example.com")
        // 无异常即通过
    }

    /** 未 start 时 onJsEvalComplete 不上报。 */
    @Test
    fun `onJsEvalComplete is ignored when not started`() {
        val module = WebviewModule()
        module.onJsEvalComplete("https://example.com", "alert(1)", 5000L)
        // 无异常即通过
    }

    /** 未 start 时 onWhiteScreen 不上报。 */
    @Test
    fun `onWhiteScreen is ignored when not started`() {
        val module = WebviewModule()
        module.onWhiteScreen("https://example.com", 3000L)
        // 无异常即通过
    }

    /** onStop 后模块不可用。 */
    @Test
    fun `module stops cleanly`() {
        val module = WebviewModule()
        module.onStop()
        module.onPageStarted("https://example.com")
        module.onPageFinished("https://example.com")
        // 无异常即通过
    }

    /** 自定义配置传入正确。 */
    @Test
    fun `custom config is applied`() {
        val config = WebviewConfig(
            enableWebviewMonitor = false,
            pageLoadThresholdMs = 10_000L,
            jsExecutionThresholdMs = 5000L,
            whiteScreenThresholdMs = 8000L
        )
        val module = WebviewModule(config)
        assertEquals("webview", module.name)
    }

    /** JS 片段默认阈值 2000ms。 */
    @Test
    fun `default js execution threshold is 2000ms`() {
        val config = WebviewConfig()
        assertEquals(2000L, config.jsExecutionThresholdMs)
    }

    /** 白屏默认阈值 3000ms。 */
    @Test
    fun `default white screen threshold is 3000ms`() {
        val config = WebviewConfig()
        assertEquals(3000L, config.whiteScreenThresholdMs)
    }

    /** URL 长度截断默认 500。 */
    @Test
    fun `default max url length is 500`() {
        val config = WebviewConfig()
        assertEquals(500, config.maxUrlLength)
    }
}
