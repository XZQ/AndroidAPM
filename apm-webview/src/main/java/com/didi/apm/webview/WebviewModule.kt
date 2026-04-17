package com.didi.apm.webview

import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity

/**
 * WebView 监控模块。
 * 监控 WebView 页面加载耗时、JS 执行耗时、白屏等异常。
 *
 * 使用方式（外部回调）：
 * ```kotlin
 * Apm.init(this, ApmConfig()) {
 *     register(WebviewModule())
 * }
 * // 在 WebViewClient 回调中
 * webviewModule.onPageStarted(url)
 * webviewModule.onPageFinished(url)
 * webviewModule.onJsEvalComplete(url, js, durationMs)
 * ```
 */
class WebviewModule(private val config: WebviewConfig = WebviewConfig()) : ApmModule {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /** 页面加载开始时间记录：url → startTime。 */
    private val pageLoadStartMap = HashMap<String, Long>()

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableWebviewMonitor
        apmContext?.logger?.d("WebView module started")
    }

    override fun onStop() {
        started = false
        pageLoadStartMap.clear()
    }

    /**
     * 页面开始加载时调用。
     * 记录开始时间，用于计算加载耗时。
     */
    fun onPageStarted(url: String) {
        if (!started) return
        pageLoadStartMap[url] = System.currentTimeMillis()
    }

    /**
     * 页面加载完成时调用。
     * 计算加载耗时，超阈值则上报。
     */
    fun onPageFinished(url: String) {
        if (!started) return
        val startTime = pageLoadStartMap.remove(url) ?: return
        val duration = System.currentTimeMillis() - startTime

        if (duration >= config.pageLoadThresholdMs) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_SLOW_PAGE_LOAD,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                fields = mapOf(
                    FIELD_URL to url.take(config.maxUrlLength),
                    FIELD_DURATION_MS to duration,
                    FIELD_THRESHOLD to config.pageLoadThresholdMs
                )
            )
        }
    }

    /**
     * JS 执行完成时调用。
     * 超阈值则上报。
     */
    fun onJsEvalComplete(url: String, jsSnippet: String, durationMs: Long) {
        if (!started) return
        if (durationMs < config.jsExecutionThresholdMs) return

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_JS,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            fields = mapOf(
                FIELD_URL to url.take(config.maxUrlLength),
                FIELD_JS_SNIPPET to jsSnippet.take(MAX_JS_SNIPPET_LENGTH),
                FIELD_DURATION_MS to durationMs
            )
        )
    }

    /**
     * 白屏检测回调。
     * 页面加载超时后仍未有内容渲染时调用。
     */
    fun onWhiteScreen(url: String, durationMs: Long) {
        if (!started) return
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_WHITE_SCREEN,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = mapOf(
                FIELD_URL to url.take(config.maxUrlLength),
                FIELD_DURATION_MS to durationMs
            )
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "webview"

        /** 慢页面加载事件。 */
        private const val EVENT_SLOW_PAGE_LOAD = "slow_page_load"

        /** 慢 JS 执行事件。 */
        private const val EVENT_SLOW_JS = "slow_js_execution"

        /** 白屏事件。 */
        private const val EVENT_WHITE_SCREEN = "white_screen"

        /** 字段：URL。 */
        private const val FIELD_URL = "url"

        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"

        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"

        /** 字段：JS 片段。 */
        private const val FIELD_JS_SNIPPET = "jsSnippet"

        /** JS 片段最大长度。 */
        private const val MAX_JS_SNIPPET_LENGTH = 200
    }
}
