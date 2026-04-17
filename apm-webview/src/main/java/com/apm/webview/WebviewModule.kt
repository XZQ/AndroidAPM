package com.apm.webview

import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity

/**
 * WebView 监控模块。
 * 监控 WebView 页面加载耗时、JS 执行耗时、白屏等异常。
 * 支持扩展的 JS Bridge 调用监控、JS Console 错误监控、资源加载瀑布图。
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
 * webviewModule.onJsBridgeCall(url, "getUserInfo", "js_to_native", 120L, true)
 * webviewModule.onJsConsoleError(url, "TypeError: ...", "app.js", 42)
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

    /** 资源加载瀑布图追踪器，仅在配置启用时创建。 */
    private var resourceWaterfall: ResourceWaterfall? = null

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableWebviewMonitor
        // 仅在启用时创建资源瀑布图追踪器
        if (config.enableResourceWaterfall) {
            resourceWaterfall = ResourceWaterfall(config)
        }
        apmContext?.logger?.d("WebView module started")
    }

    override fun onStop() {
        started = false
        pageLoadStartMap.clear()
        // 释放瀑布图追踪器
        resourceWaterfall = null
    }

    /**
     * 页面开始加载时调用。
     * 记录开始时间，用于计算加载耗时。
     */
    fun onPageStarted(url: String) {
        if (!started) return
        // 记录页面加载起始时间
        pageLoadStartMap[url] = System.currentTimeMillis()
        // 通知瀑布图追踪器当前活跃页面
        resourceWaterfall?.setActivePage(url)
    }

    /**
     * 页面加载完成时调用。
     * 计算加载耗时，超阈值则上报。
     * 同时触发资源瀑布图完成回调。
     */
    fun onPageFinished(url: String) {
        if (!started) return
        val startTime = pageLoadStartMap.remove(url) ?: return
        val duration = System.currentTimeMillis() - startTime

        // 超过页面加载阈值时上报慢加载事件
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

        // 触发瀑布图完成，输出资源加载统计数据
        resourceWaterfall?.onPageComplete(url)
    }

    /**
     * JS 执行完成时调用。
     * 超阈值则上报。
     */
    fun onJsEvalComplete(url: String, jsSnippet: String, durationMs: Long) {
        if (!started) return
        // 未达阈值时不报
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

    /**
     * JS Bridge 调用监控。
     *
     * 记录 JS → Native 和 Native → JS 的 Bridge 调用性能。
     * 在 WebView 的 JavaScriptInterface 或 JsBridge 回调中调用。
     *
     * @param url 当前页面 URL
     * @param bridgeName Bridge 方法名（如 "getUserInfo"）
     * @param direction 调用方向（"js_to_native" 或 "native_to_js"）
     * @param durationMs 调用耗时（毫秒）
     * @param success 是否调用成功
     */
    fun onJsBridgeCall(
        url: String,
        bridgeName: String,
        direction: String,
        durationMs: Long,
        success: Boolean
    ) {
        if (!started) return
        // 未启用 JS Bridge 监控时跳过
        if (!config.enableJsBridgeMonitor) return
        // 未达阈值时不报
        if (durationMs < config.jsBridgeThresholdMs) return

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_JS_BRIDGE,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN,
            fields = mapOf(
                FIELD_URL to url.take(config.maxUrlLength),
                FIELD_BRIDGE_NAME to bridgeName.take(MAX_BRIDGE_NAME_LENGTH),
                FIELD_DIRECTION to direction,
                FIELD_DURATION_MS to durationMs,
                FIELD_SUCCESS to success,
                FIELD_THRESHOLD to config.jsBridgeThresholdMs
            )
        )
    }

    /**
     * JS Console 错误监控。
     * 通过注入 JS 代码拦截 console.error 输出。
     *
     * @param url 当前页面 URL
     * @param errorMessage 错误信息内容
     * @param sourceUrl 错误来源文件 URL，可为 null
     * @param line 错误所在行号，0 表示未知
     */
    fun onJsConsoleError(
        url: String,
        errorMessage: String,
        sourceUrl: String?,
        line: Int
    ) {
        if (!started) return
        // 未启用 JS Console 监控时跳过
        if (!config.enableJsConsoleMonitor) return

        // 构建事件字段
        val fields = mutableMapOf<String, Any?>(
            FIELD_URL to url.take(config.maxUrlLength),
            FIELD_ERROR_MESSAGE to errorMessage.take(MAX_ERROR_MESSAGE_LENGTH)
        )
        // 附加源文件信息（可选）
        if (sourceUrl != null) {
            fields[FIELD_SOURCE_URL] = sourceUrl.take(config.maxUrlLength)
        }
        // 行号大于 0 才有意义
        if (line > 0) {
            fields[FIELD_LINE] = line
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_JS_CONSOLE_ERROR,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            fields = fields
        )
    }

    /**
     * 获取资源加载瀑布图追踪器实例。
     * 用于在 WebViewClient.shouldInterceptRequest 中调用 onResourceStart/onResourceEnd。
     *
     * @return 瀑布图追踪器，未启用时返回 null
     */
    fun getResourceWaterfall(): ResourceWaterfall? = resourceWaterfall

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "webview"

        /** 慢页面加载事件。 */
        private const val EVENT_SLOW_PAGE_LOAD = "slow_page_load"

        /** 慢 JS 执行事件。 */
        private const val EVENT_SLOW_JS = "slow_js_execution"

        /** 白屏事件。 */
        private const val EVENT_WHITE_SCREEN = "white_screen"

        /** 慢 JS Bridge 调用事件。 */
        private const val EVENT_SLOW_JS_BRIDGE = "slow_js_bridge"

        /** JS Console 错误事件。 */
        private const val EVENT_JS_CONSOLE_ERROR = "js_console_error"

        /** 字段：URL。 */
        private const val FIELD_URL = "url"

        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"

        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"

        /** 字段：JS 片段。 */
        private const val FIELD_JS_SNIPPET = "jsSnippet"

        /** 字段：Bridge 方法名。 */
        private const val FIELD_BRIDGE_NAME = "bridgeName"

        /** 字段：调用方向。 */
        private const val FIELD_DIRECTION = "direction"

        /** 字段：是否成功。 */
        private const val FIELD_SUCCESS = "success"

        /** 字段：错误信息。 */
        private const val FIELD_ERROR_MESSAGE = "errorMessage"

        /** 字段：源文件 URL。 */
        private const val FIELD_SOURCE_URL = "sourceUrl"

        /** 字段：行号。 */
        private const val FIELD_LINE = "line"

        /** JS 片段最大长度。 */
        private const val MAX_JS_SNIPPET_LENGTH = 200

        /** Bridge 方法名最大长度。 */
        private const val MAX_BRIDGE_NAME_LENGTH = 100

        /** 错误信息最大长度。 */
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
    }
}
