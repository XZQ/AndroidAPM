package com.apm.webview

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.apm.core.Apm
import com.apm.core.ApmClock
import com.apm.core.ApmContext
import com.apm.core.ApmExecutors
import com.apm.core.ApmModule
import com.apm.core.diagnostics.HostIntegrationPoint
import com.apm.model.ApmEventKind
import com.apm.model.ApmEvent
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.WeakHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * WebView 监控模块。
 * 监控 WebView 页面加载耗时、JS 执行耗时、白屏等异常。
 * 支持扩展的 JS Bridge 调用监控、JS Console 错误监控、资源加载瀑布图。
 *
 * 使用方式（外部回调）：
 * ```kotlin
 * val webviewModule = WebviewModule()
 * Apm.init(application, ApmConfig())
 * Apm.register(webviewModule)
 * // 显式绑定单个 WebView，或在 WebViewClient 回调中调用
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

    /** Coordinates page generations, bounded admission, and permanent module-run cancellation. */
    private val pageStateLock = Any()

    /** Bounded navigation records; visible pages retain timing until completion or capacity eviction. */
    private val pageLoadStartMap = LinkedHashMap<String, PageLoad>()

    /** Current module run; replaced runs can never deliver a late timeout. */
    private var pageSession: PageSession? = null

    /** 资源加载瀑布图追踪器，仅在配置启用时创建。 */
    @Volatile
    private var resourceWaterfall: ResourceWaterfall? = null

    /** Explicit WebView installations retained weakly for reversible shutdown. */
    private val installations = WeakHashMap<WebView, Installation>()

    /** Scheduler for bounded page-visibility timeout checks. */
    private var visibilityScheduler: ScheduledExecutorService? = null

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    override fun onStart() {
        val previousScheduler = synchronized(pageStateLock) {
            pageSession?.active = false
            clearPagesLocked()
            val previous = visibilityScheduler
            started = config.enableWebviewMonitor
            pageSession = if (started) PageSession(apmContext) else null
            visibilityScheduler = if (started) {
                ScheduledThreadPoolExecutor(VISIBILITY_THREAD_COUNT,
                    ApmExecutors.threadFactory(VISIBILITY_THREAD_NAME)).apply {
                    // Canceled navigations must release their URL/token closures immediately, too.
                    removeOnCancelPolicy = true
                }
            } else null
            previous
        }
        previousScheduler?.shutdownNow()
        // 资源瀑布图与 JS Bridge/Console 一致尊重总开关：仅当模块启用且配置启用时创建。
        // 禁用总开关时不得留下仍会发事件的活跃 recorder；独立使用请走 ResourceWaterfall 公开构造器。
        resourceWaterfall?.stop()
        resourceWaterfall = if (started && config.enableResourceWaterfall) {
            ResourceWaterfall(config, apmContext)
        } else null
        apmContext?.setHostIntegrationModuleActive(HostIntegrationPoint.WEBVIEW, started)
        if (started) {
            apmContext?.setHostIntegrationActiveRegistrations(
                HostIntegrationPoint.WEBVIEW,
                synchronized(installations) { installations.size }
            )
        }
        apmContext?.logger?.d("WebView module started")
    }

    override fun onStop() {
        val scheduler = synchronized(pageStateLock) {
            started = false
            pageSession?.active = false
            pageSession = null
            clearPagesLocked()
            visibilityScheduler.also { visibilityScheduler = null }
        }
        apmContext?.setHostIntegrationModuleActive(HostIntegrationPoint.WEBVIEW, false)
        scheduler?.shutdownNow()
        val installedViews = synchronized(installations) { installations.keys.toList() }
        for (webView in installedViews) {
            // Restore host clients before releasing installation metadata. Isolate each
            // view: stop/撤回可能在非 WebView 创建线程执行，单个 restore 抛错不得中断
            // 其余视图的清理，否则它们会带着监控 wrapper 却失去安装记录。
            try {
                uninstall(webView)
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_UNINSTALL, error)
            }
        }
        // 释放瀑布图追踪器
        resourceWaterfall?.stop()
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
        // 捕获安装线程 Looper：WebView 的 setWebViewClient/setWebChromeClient 强制创建线程，
        // 卸载若发生在其他线程（如 stop/撤回在工作线程）须 post 回该线程恢复。
        val installLooper = Looper.myLooper()
        synchronized(installations) {
            installations[webView] = Installation(
                hostClient = webViewClient,
                hostChrome = webChromeClient,
                monitoringClient = monitoringClient,
                monitoringChrome = monitoringChrome,
                looper = installLooper
            )
        }
        try {
            webView.webViewClient = monitoringClient
            webView.webChromeClient = monitoringChrome
        } catch (error: RuntimeException) {
            // 安装异常（如非 UI 线程调用）时回滚注册记录，防止遗留失真状态。
            synchronized(installations) { installations.remove(webView) }
            throw error
        }
        if (started) {
            apmContext?.setHostIntegrationActiveRegistrations(
                HostIntegrationPoint.WEBVIEW,
                synchronized(installations) { installations.size }
            )
        }
    }

    /**
     * Restores host clients for one previously installed WebView.
     *
     * @param webView host-owned WebView
     * @return true when an installation was removed
     */
    fun uninstall(webView: WebView): Boolean {
        val installation = synchronized(installations) { installations.remove(webView) } ?: return false
        val webViewRef = java.lang.ref.WeakReference(webView)
        // WebView 客户端 setter 强制创建线程；恢复（含 API 26+ 的当前客户端判定）必须整体
        // 在安装线程执行。跨线程卸载时用弱引用 post 回安装线程，避免慢队列长期强引用宿主视图。
        val restore = Runnable {
            val targetView = webViewRef.get() ?: return@Runnable
            try {
                // API 26+ can avoid overwriting a client the host replaced after
                // installation. API 24-25 lacks public getters, so restore directly.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || targetView.webViewClient === installation.monitoringClient) {
                    targetView.webViewClient = installation.hostClient
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || targetView.webChromeClient === installation.monitoringChrome) {
                    targetView.webChromeClient = installation.hostChrome
                }
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_UNINSTALL, error)
            }
        }
        val targetLooper = installation.looper
        if (targetLooper != null && Looper.myLooper() != targetLooper) {
            Handler(targetLooper).post(restore)
        } else {
            restore.run()
        }
        if (started) {
            apmContext?.setHostIntegrationActiveRegistrations(
                HostIntegrationPoint.WEBVIEW,
                synchronized(installations) { installations.size }
            )
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
        synchronized(pageStateLock) {
            val session = pageSession?.takeIf { it.active } ?: return
            val scheduler = visibilityScheduler ?: return
            pageLoadStartMap.remove(url)?.future?.cancel(false)
            if (pageLoadStartMap.size >= MAX_TRACKED_PAGES) {
                val oldest = pageLoadStartMap.entries.iterator()
                val evicted = oldest.next()
                evicted.value.future?.cancel(false)
                oldest.remove()
            }
            val page = PageLoad(ApmClock.monotonicTimeMillis(), session)
            pageLoadStartMap[url] = page
            // Publish the token before scheduling; the same lock prevents stop from shutting down midway.
            page.future = scheduler.schedule(
                { onVisibilityTimeout(url, page) },
                config.whiteScreenThresholdMs.coerceAtLeast(MIN_VISIBILITY_TIMEOUT_MS),
                TimeUnit.MILLISECONDS
            )
        }
        recordIntegrationObservation()
        // 通知瀑布图追踪器当前活跃页面
        resourceWaterfall?.setActivePage(url)
    }

    /**
     * 页面加载完成时调用。
     * 计算加载耗时，超阈值则上报。
     * 同时触发资源瀑布图完成回调。
     */
    fun onPageFinished(url: String) {
        val waterfall = resourceWaterfall
        val page = synchronized(pageStateLock) {
            if (!started) return
            pageLoadStartMap.remove(url)?.also { it.future?.cancel(false) }
        }
        recordIntegrationObservation()
        if (page != null) {
            val duration = ApmClock.elapsedMillisSince(page.startedAtMillis)

            // 超过页面加载阈值时上报慢加载事件
            if (duration >= config.pageLoadThresholdMs) {
                emitForPageSession(
                    session = page.session,
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
        waterfall?.onPageComplete(url)
    }

    /** Marks a page as visually committed and cancels its timeout alarm. */
    fun onPageVisible(url: String) {
        synchronized(pageStateLock) {
            if (!started) return
            pageLoadStartMap[url]?.let { page ->
                page.future?.cancel(false)
                page.future = null
            }
        }
        recordIntegrationObservation()
    }

    /** Discards a superseded navigation without reporting it as a completed page. */
    internal fun onNavigationReplaced(url: String) {
        synchronized(pageStateLock) {
            pageLoadStartMap.remove(url)?.future?.cancel(false)
        }
        resourceWaterfall?.discardPage(url)
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
        val startedAtNanos = ApmClock.monotonicTimeNanos()
        webView.evaluateJavascript(script) { result ->
            val durationMs = (ApmClock.monotonicTimeNanos() - startedAtNanos)
                .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
            try {
                onJsEvalComplete(webView.url.orEmpty(), script, durationMs)
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_JS_EVAL, error)
            }
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
        recordIntegrationObservation()
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
        recordIntegrationObservation()
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
        recordIntegrationObservation()
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
        recordIntegrationObservation()
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

    /** Records value-free evidence that a WebView integration callback executed. */
    private fun recordIntegrationObservation() {
        apmContext?.recordHostIntegrationObservation(HostIntegrationPoint.WEBVIEW)
    }

    /**
     * 获取资源加载瀑布图追踪器实例。
     * 用于在 WebViewClient.shouldInterceptRequest 中调用 onResourceStart/onResourceEnd。
     *
     * @return 瀑布图追踪器，未启用时返回 null
     */
    fun getResourceWaterfall(): ResourceWaterfall? {
        val waterfall = resourceWaterfall ?: return null
        if (started) {
            recordIntegrationObservation()
        }
        return waterfall
    }

    /** Removes all timing state only when the scheduled task still owns this navigation generation. */
    private fun onVisibilityTimeout(url: String, page: PageLoad) {
        synchronized(pageStateLock) {
            if (!page.session.active || pageSession !== page.session || pageLoadStartMap[url] !== page ||
                page.future == null) return
            pageLoadStartMap.remove(url)
            page.future = null
        }
        recordIntegrationObservation()
        emitForPageSession(
            session = page.session,
            name = EVENT_WHITE_SCREEN,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.ERROR,
            priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_URL to url.take(config.maxUrlLength),
                FIELD_DURATION_MS to config.whiteScreenThresholdMs
            )
        )
    }

    /** Cancels and clears bounded navigation records while holding [pageStateLock]. */
    private fun clearPagesLocked() {
        pageLoadStartMap.values.forEach { it.future?.cancel(false) }
        pageLoadStartMap.clear()
    }

    /** Delivers a page observation only to the runtime and permanent module run which produced it. */
    private fun emitForPageSession(
        session: PageSession,
        name: String,
        kind: ApmEventKind,
        severity: ApmSeverity,
        priority: ApmPriority,
        fields: Map<String, Any?>
    ) {
        val event = ApmEvent(module = MODULE_NAME, name = name, kind = kind,
            severity = severity, priority = priority, fields = fields)
        val context = session.context ?: return
        // Business context is captured outside our lock; final admission rechecks the original run.
        context.emitInSession(event, critical = false) { handoff ->
            synchronized(pageStateLock) {
                if (session.active && pageSession === session) handoff() else false
            }
        }
    }

    /** One navigation generation with a bounded lifetime and its owning module run. */
    private class PageLoad(
        /** Monotonic navigation start for the slow-page measurement. */
        val startedAtMillis: Long,
        /** Permanent module-run identity used by the timeout and completion paths. */
        val session: PageSession
    ) {
        /** Scheduled timeout once the scheduler accepts it. */
        var future: ScheduledFuture<*>? = null
    }

    /** Immutable runtime ownership with a gate which is never reopened after stop. */
    private class PageSession(
        /** Runtime captured at module start. */
        val context: ApmContext?
    ) {
        /** Guarded by [pageStateLock]; old timeout callbacks remain permanently canceled. */
        var active = true
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
        val monitoringChrome: MonitoringWebChromeClient,
        /** Install-thread looper used to restore clients on the WebView's own thread; null when absent. */
        val looper: Looper?
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
        /** Visibility tasks are serialized on one daemon worker. */
        private const val VISIBILITY_THREAD_COUNT = 1
        /** Lower bound preventing a hot-loop timeout configuration. */
        private const val MIN_VISIBILITY_TIMEOUT_MS = 1L
        /** Maximum simultaneous unfinished or visible navigation records across direct callbacks. */
        private const val MAX_TRACKED_PAGES = 128
        /** Nanoseconds contained in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** 自监控 tag：停止阶段单个 WebView 卸载失败（不中断其余视图清理）。 */
        private const val ERROR_TAG_UNINSTALL = "webview_uninstall"
        /** 自监控 tag：evaluateJavascript 监控上报异常（不得阻断宿主结果回调）。 */
        private const val ERROR_TAG_JS_EVAL = "webview_js_eval"
    }
}
