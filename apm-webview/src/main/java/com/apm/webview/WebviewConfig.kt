package com.apm.webview

/**
 * WebView 监控模块配置。
 */
data class WebviewConfig(
    /** 是否开启 WebView 监控。 */
    val enableWebviewMonitor: Boolean = true,
    /** 页面加载超时阈值（毫秒）。 */
    val pageLoadThresholdMs: Long = DEFAULT_PAGE_LOAD_THRESHOLD_MS,
    /** JS 执行超时阈值（毫秒）。 */
    val jsExecutionThresholdMs: Long = DEFAULT_JS_EXECUTION_THRESHOLD_MS,
    /** 白屏检测阈值（毫秒）。 */
    val whiteScreenThresholdMs: Long = DEFAULT_WHITE_SCREEN_THRESHOLD_MS,
    /** 最大 URL 长度。 */
    val maxUrlLength: Int = DEFAULT_MAX_URL_LENGTH,
    /** 是否启用自动注册 WebViewClient。 */
    val enableAutoRegister: Boolean = true,
    /** 是否开启 JS Bridge 性能监控。 */
    val enableJsBridgeMonitor: Boolean = true,
    /** JS Bridge 调用超时阈值（毫秒）。 */
    val jsBridgeThresholdMs: Long = DEFAULT_JS_BRIDGE_THRESHOLD_MS,
    /** 是否开启资源加载瀑布图。 */
    val enableResourceWaterfall: Boolean = true,
    /** 资源加载超时阈值（毫秒）。 */
    val resourceSlowThresholdMs: Long = DEFAULT_RESOURCE_SLOW_THRESHOLD_MS,
    /** 是否开启 JS Console 错误监控。 */
    val enableJsConsoleMonitor: Boolean = true,
    /** 瀑布图最大追踪资源数。 */
    val maxTrackedResources: Int = DEFAULT_MAX_TRACKED_RESOURCES
) {
    companion object {
        /** 默认页面加载阈值：5 秒。 */
        private const val DEFAULT_PAGE_LOAD_THRESHOLD_MS = 5000L

        /** 默认 JS 执行阈值：2 秒。 */
        private const val DEFAULT_JS_EXECUTION_THRESHOLD_MS = 2000L

        /** 默认白屏阈值：3 秒。 */
        private const val DEFAULT_WHITE_SCREEN_THRESHOLD_MS = 3000L

        /** 默认 URL 最大长度。 */
        private const val DEFAULT_MAX_URL_LENGTH = 500

        /** 默认 JS Bridge 超时阈值：500 毫秒。 */
        private const val DEFAULT_JS_BRIDGE_THRESHOLD_MS = 500L

        /** 默认资源加载超时阈值：3 秒。 */
        private const val DEFAULT_RESOURCE_SLOW_THRESHOLD_MS = 3000L

        /** 默认瀑布图最大追踪资源数。 */
        private const val DEFAULT_MAX_TRACKED_RESOURCES = 200
    }
}
