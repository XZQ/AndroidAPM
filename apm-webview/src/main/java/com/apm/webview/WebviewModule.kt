package com.apm.webview

import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmExecutors
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

    /** Explicit WebView installations retained weakly for reversible shutdown. */
    private val installations = WeakHashMap<WebView, Installation>()

    /** Scheduler for bounded page-visibility timeout checks. */
    private var visibilityScheduler: ScheduledExecutorService? = null

    /** Pending page visibility checks keyed by URL. */
    private val visibilityChecks = ConcurrentHashMap<String, VisibilityCheck>()

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        started = config.enableWebviewMonitor
        // 仅在启用时创建资源瀑布图追踪器
        if (config.enableResourceWaterfall) {
            resourceWaterfall = ResourceWaterfall(config)
        }
        if (started) {
            visibilityScheduler = ApmExecutors.newSingleThreadScheduledExecutor(VISIBILITY_THREAD_NAME)
        }
        apmContext?.logger?.d("WebView module started")
    }

    override fun onStop() {
        started = false
        pageLoadStartMap.clear()
        visibilityChecks.values.forEach { check -> check.future?.cancel(false) }
        visibilityChecks.clear()
        visibilityScheduler?.shutdownNow()
        visibilityScheduler = null
        val installedViews = synchronized(installations) { installations.keys.toList() }
        for (webView in installedViews) {
            // Restore host clients before releasing installation metadata.
            uninstall(webView)
        }
        // 释放瀑布图追踪器
        resourceWaterfall = null
    }

    /**
     * Wraps and installs host clients on one explicit WebView instance.
     * Call this after the host has created its delegates; [uninstall] restores
     * the exact same objects.
     *
     * @param webView host-owned WebView
     * @param webViewClient host navigation client
     * @param webChromeClient host chrome client
     */
    fun install(
        webView: WebView,
        webViewClient: WebViewClient = WebViewClient(),
        webChromeClient: WebChromeClient = WebChromeClient()
    ) {
        uninstall(webView)
        val monitoringClient = MonitoringWebViewClient(this, webViewClient)
        val monitoringChrome = MonitoringWebChromeClient(this, webChromeClient)
        // Store metadata before assignment so an immediate stop can restore it.
        synchronized(installations) {
            installations[webView] = Installation(
                hostClient = webViewClient,
                hostChrome = webChromeClient,
                monitoringClient = monitoringClient,
                monitoringChrome = monitoringChrome
            )
        }
        webView.webViewClient = monitoringClient
        webView.webChromeClient = monitoringChrome
    }

    /**
     * Restores host clients for one previously installed WebView.
     *
     * @param webView host-owned WebView
     * @return true when an installation was removed
     */
    fun uninstall(webView: WebView): Boolean {
        val installation = synchronized(installations) { installations.remove(webView) } ?: return false
        // API 26+ can avoid overwriting a client the host replaced after
        // installation. API 24-25 lacks public getters, so restore directly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView.webViewClient === installation.monitoringClient) {
            webView.webViewClient = installation.hostClient
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView.webChromeClient === installation.monitoringChrome) {
            webView.webChromeClient = installation.hostChrome
        }
        return true
    }

    /** Creates a delegating navigation client without assigning it. */
    fun wrapWebViewClient(delegate: WebViewClient = WebViewClient()): WebViewClient =
        MonitoringWebViewClient(this, delegate)

    /** Creates a delegating chrome client without assigning it. */
    fun wrapWebChromeClient(delegate: WebChromeClient = WebChromeClient()): WebChromeClient =
        MonitoringWebChromeClient(this, delegate)

    /**
     * 页面开始加载时调用。
     * 记录开始时间，用于计算加载耗时。
     */
    fun onPageStarted(url: String) {
        if (!started) {
            return
        }
        // 记录页面加载起始时间
        pageLoadStartMap[url] = System.currentTimeMillis()
        // 通知瀑布图追踪器当前活跃页面
        resourceWaterfall?.setActivePage(url)
        scheduleVisibilityTimeout(url)
    }

    /**
     * 页面加载完成时调用。
     * 计算加载耗时，超阈值则上报。
     * 同时触发资源瀑布图完成回调。
     */
    fun onPageFinished(url: String) {
        if (!started) {
            return
        }
        val startTime = pageLoadStartMap.remove(url)
        cancelVisibilityTimeout(url)
        if (startTime != null) {
            val duration = System.currentTimeMillis() - startTime

            // 超过页面加载阈值时上报慢加载事件
            if (duration >= config.pageLoadThresholdMs) {
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_SLOW_PAGE_LOAD,
                    kind = ApmEventKind.ALERT,
                    severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                    fields = mapOf(
                        FIELD_URL to url.take(config.maxUrlLength),
                        FIELD_DURATION_MS to duration,
                        FIELD_THRESHOLD to config.pageLoadThresholdMs
                    )
                )
            }
        }

        // 触发瀑布图完成，输出资源加载统计数据
        resourceWaterfall?.onPageComplete(url)
    }

    /** Marks a page as visually committed and cancels its timeout alarm. */
    fun onPageVisible(url: String) {
        if (!started) {
            return
        }
        cancelVisibilityTimeout(url)
    }

    /**
     * Evaluates JavaScript through a measured, callback-preserving path.
     *
     * @param webView host WebView
     * @param script JavaScript source
     * @param callback optional host result callback
     */
    fun evaluateJavascript(
        webView: WebView,
        script: String,
        callback: android.webkit.ValueCallback<String>? = null
    ) {
        val startedAtNanos = System.nanoTime()
        webView.evaluateJavascript(script) { result ->
            val durationMs = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
            onJsEvalComplete(webView.url.orEmpty(), script, durationMs)
            callback?.onReceiveValue(result)
        }
    }

    /**
     * JS 执行完成时调用。
     * 超阈值则上报。
     */
    fun onJsEvalComplete(url: String, jsSnippet: String, durationMs: Long) {
        if (!started) {
            return
        }
        // 未达阈值时不报
        if (durationMs < config.jsExecutionThresholdMs) {
            return
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_JS,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
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
        if (!started) {
            return
        }
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_WHITE_SCREEN,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR, priority = ApmPriority.LOW,
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
    fun onJsBridgeCall(url: String, bridgeName: String, direction: String, durationMs: Long, success: Boolean) {
        if (!started) {
            return
        }
        // 未启用 JS Bridge 监控时跳过
        if (!config.enableJsBridgeMonitor) {
            return
        }
        // 未达阈值时不报
        if (durationMs < config.jsBridgeThresholdMs) {
            return
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_SLOW_JS_BRIDGE,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
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
    fun onJsConsoleError(url: String, errorMessage: String, sourceUrl: String?, line: Int) {
        if (!started) {
            return
        }
        // 未启用 JS Console 监控时跳过
        if (!config.enableJsConsoleMonitor) {
            return
        }

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
            severity = ApmSeverity.ERROR, priority = ApmPriority.LOW,
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

    /** Schedules one suspected-white-screen timeout for a started page. */
    private fun scheduleVisibilityTimeout(url: String) {
        cancelVisibilityTimeout(url)
        val scheduler = visibilityScheduler ?: return
        val check = VisibilityCheck()
        visibilityChecks[url] = check
        check.future = scheduler.schedule(
            {
                // Remove only the still-current task; replaced navigations must
                // not emit stale white-screen events.
                if (visibilityChecks.remove(url, check) && started) {
                    onWhiteScreen(url, config.whiteScreenThresholdMs)
                }
            },
            config.whiteScreenThresholdMs.coerceAtLeast(MIN_VISIBILITY_TIMEOUT_MS),
            TimeUnit.MILLISECONDS
        )
    }

    /** Cancels one pending page visibility timeout. */
    private fun cancelVisibilityTimeout(url: String) {
        visibilityChecks.remove(url)?.future?.cancel(false)
    }

    /** Mutable future holder inserted before scheduling to close fast-timeout races. */
    private class VisibilityCheck {
        /** Scheduled timeout once the scheduler accepts it. */
        @Volatile
        var future: ScheduledFuture<*>? = null
    }

    /** Clients associated with one explicit WebView installation. */
    private data class Installation(
        /** Original navigation client. */
        val hostClient: WebViewClient,
        /** Original chrome client. */
        val hostChrome: WebChromeClient,
        /** Assigned monitoring navigation client. */
        val monitoringClient: MonitoringWebViewClient,
        /** Assigned monitoring chrome client. */
        val monitoringChrome: MonitoringWebChromeClient
    )

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
        /** Background thread used only for page-visibility deadlines. */
        private const val VISIBILITY_THREAD_NAME = "apm-webview-visibility"
        /** Lower bound preventing a hot-loop timeout configuration. */
        private const val MIN_VISIBILITY_TIMEOUT_MS = 1L
        /** Nanoseconds contained in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
