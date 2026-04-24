package com.apm.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView 资源加载瀑布图追踪器。
 *
 * 通过 WebViewClient.shouldInterceptRequest 和 onPageFinished 配合，
 * 记录每个页面加载过程中所有子资源的加载时序。
 *
 * 产出资源瀑布图数据：
 * - 每个资源的 URL、类型、开始时间、结束时间、耗时
 * - 关键路径分析（阻塞渲染的资源）
 * - 慢资源告警
 *
 * 使用方式：
 * ```kotlin
 * // 在 WebViewClient.shouldInterceptRequest 中
 * waterfall.onResourceStart(request)
 * // 资源加载完成后
 * waterfall.onResourceEnd(url, response)
 * // 页面加载完成时
 * waterfall.onPageComplete(url)
 * ```
 */
class ResourceWaterfall(private val config: WebviewConfig) {

    /**
     * 当前页面的资源记录集合：pageUrl → 资源列表。
     * 使用 ConcurrentHashMap 保证线程安全（资源加载可能在不同线程完成）。
     */
    private val pageResources = ConcurrentHashMap<String, MutableList<ResourceRecord>>()

    /**
     * 资源开始加载时间缓存：resourceUrl → startTimeMs。
     * 用于在 onResourceEnd 时计算加载耗时。
     */
    private val resourceStartTimes = ConcurrentHashMap<String, Long>()

    /**
     * 当前活跃页面的 URL。
     * 用于在未明确指定 pageUrl 时关联资源记录。
     */
    @Volatile
    private var activePageUrl: String = ""

    /**
     * 资源加载开始时调用。
     * 在 WebViewClient.shouldInterceptRequest 中调用。
     *
     * @param request WebView 资源请求对象
     */
    fun onResourceStart(request: WebResourceRequest) {
        // 忽略非 HTTP 请求（如 data:、blob: 等）
        val url = request.url.toString()
        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX)) return

        // 记录资源开始加载时间
        resourceStartTimes[url] = System.currentTimeMillis()

        // 推断资源类型
        val resourceType = inferResourceType(url)

        // 获取或创建当前页面的资源列表
        val pageUrl = activePageUrl
        if (pageUrl.isEmpty()) return

        val records = pageResources.getOrPut(pageUrl) {
            // 限制最大追踪资源数，防止内存溢出
            mutableListOf()
        }

        // 检查资源数量是否已达上限
        if (records.size >= config.maxTrackedResources) return

        // 添加资源记录
        records.add(
            ResourceRecord(
                url = url,
                resourceType = resourceType,
                startTimeMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * 资源加载完成时调用。
     *
     * @param url 资源 URL
     * @param response 资源响应，可为 null（表示加载失败）
     */
    fun onResourceEnd(url: String, response: WebResourceResponse?) {
        // 查找并移除开始时间
        val startTime = resourceStartTimes.remove(url) ?: return
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // 查找对应的资源记录并更新
        val pageUrl = activePageUrl
        if (pageUrl.isEmpty()) return

        val records = pageResources[pageUrl] ?: return
        // 遍历查找匹配的资源记录
        val record = records.find { it.url == url }
        if (record != null) {
            record.endTimeMs = endTime
            record.durationMs = duration
            // 提取 HTTP 状态码（响应不为空时）
            if (response != null) {
                record.httpStatus = extractHttpStatusCode(response)
            }
            // 提取资源大小（从响应头获取）
            record.sizeBytes = extractContentLength(response)
        }

        // 慢资源实时告警
        if (duration >= config.resourceSlowThresholdMs) {
            Apm.emit(
                module = MODULE_WEBVIEW,
                name = EVENT_SLOW_RESOURCE,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
                fields = mapOf(
                    FIELD_URL to url.take(config.maxUrlLength),
                    FIELD_PAGE_URL to pageUrl.take(config.maxUrlLength),
                    FIELD_RESOURCE_TYPE to (record?.resourceType ?: RESOURCE_TYPE_OTHER),
                    FIELD_DURATION_MS to duration,
                    FIELD_THRESHOLD to config.resourceSlowThresholdMs
                )
            )
        }
    }

    /**
     * 页面加载完成时，输出完整的瀑布图数据。
     * 在 onPageFinished 中调用。
     *
     * @param url 页面 URL
     */
    fun onPageComplete(url: String) {
        // 取出该页面的所有资源记录
        val records = pageResources.remove(url) ?: return
        // 清理该页面相关的开始时间缓存
        cleanupStartTimes(records)

        // 过滤出已完成加载的资源（有有效结束时间）
        val completedRecords = records.filter { it.endTimeMs > 0 }
        if (completedRecords.isEmpty()) return

        // 识别慢资源
        val slowResources = completedRecords.filter {
            it.durationMs >= config.resourceSlowThresholdMs
        }

        // 关键路径分析：识别阻塞渲染的资源（CSS 和同步 JS）
        val criticalPathResources = completedRecords.filter {
            it.resourceType == RESOURCE_TYPE_STYLESHEET ||
                (it.resourceType == RESOURCE_TYPE_SCRIPT && it.startTimeMs < completedRecords.minOf { r -> r.startTimeMs })
        }

        // 计算总加载时间（从最早开始到最后结束）
        val totalDurationMs = completedRecords.maxOf { it.endTimeMs } -
            completedRecords.minOf { it.startTimeMs }

        // 构建瀑布图统计结果
        val result = WaterfallResult(
            pageUrl = url,
            totalCount = completedRecords.size,
            slowResources = slowResources,
            criticalPathResources = criticalPathResources,
            totalDurationMs = totalDurationMs
        )

        // 上报瀑布图摘要事件
        Apm.emit(
            module = MODULE_WEBVIEW,
            name = EVENT_RESOURCE_WATERFALL,
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO, priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_PAGE_URL to url.take(config.maxUrlLength),
                FIELD_TOTAL_COUNT to result.totalCount,
                FIELD_SLOW_COUNT to slowResources.size,
                FIELD_CRITICAL_COUNT to criticalPathResources.size,
                FIELD_TOTAL_DURATION_MS to totalDurationMs
            )
        )
    }

    /**
     * 设置当前活跃页面 URL。
     * 在 onPageStarted 中调用以关联后续资源请求。
     *
     * @param url 页面 URL
     */
    fun setActivePage(url: String) {
        activePageUrl = url
    }

    /**
     * 清理指定资源列表对应的开始时间缓存。
     *
     * @param records 需要清理的资源记录列表
     */
    private fun cleanupStartTimes(records: List<ResourceRecord>) {
        // 遍历移除每个资源的开始时间缓存
        records.forEach { record ->
            resourceStartTimes.remove(record.url)
        }
    }

    /**
     * 根据 URL 路径推断资源类型。
     *
     * @param url 资源 URL
     * @return 资源类型字符串
     */
    private fun inferResourceType(url: String): String {
        val path = url.lowercase()
        return when {
            // JS 脚本文件
            path.endsWith(JS_EXTENSION) || path.contains(JS_QUERY_PARAM) ->
                RESOURCE_TYPE_SCRIPT
            // CSS 样式文件
            path.endsWith(CSS_EXTENSION) || path.contains(CSS_QUERY_PARAM) ->
                RESOURCE_TYPE_STYLESHEET
            // 图片文件
            path.endsWith(PNG_EXTENSION) || path.endsWith(JPG_EXTENSION) ||
                path.endsWith(JPEG_EXTENSION) || path.endsWith(GIF_EXTENSION) ||
                path.endsWith(WEBP_EXTENSION) || path.endsWith(SVG_EXTENSION) ->
                RESOURCE_TYPE_IMAGE
            // XHR/Fetch 请求（常见 API 路径）
            path.contains(API_PATH_SEGMENT) || path.contains(XHR_QUERY_PARAM) ->
                RESOURCE_TYPE_XHR
            // 其他类型
            else -> RESOURCE_TYPE_OTHER
        }
    }

    /**
     * 从 WebResourceResponse 提取 HTTP 状态码。
     * 某些 Android 版本可能无法获取真实状态码，此时返回 0。
     *
     * @param response WebResourceResponse 对象
     * @return HTTP 状态码，获取失败返回 0
     */
    private fun extractHttpStatusCode(response: WebResourceResponse): Int {
        return try {
            // API 21+ 可获取状态码
            response.statusCode
        } catch (_: Exception) {
            // 低版本或异常情况下返回 0 表示未知
            0
        }
    }

    /**
     * 从 WebResourceResponse 的响应头中提取 Content-Length。
     *
     * @param response WebResourceResponse 对象，可为 null
     * @return 内容长度字节数，获取失败返回 0
     */
    private fun extractContentLength(response: WebResourceResponse?): Long {
        if (response == null) return 0L
        return try {
            val headers = response.responseHeaders ?: return 0L
            // 遍历响应头查找 Content-Length
            val contentLength = headers[HEADER_CONTENT_LENGTH] ?: return 0L
            contentLength.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_WEBVIEW = "webview"

        /** 慢资源事件。 */
        private const val EVENT_SLOW_RESOURCE = "slow_resource"

        /** 瀑布图摘要事件。 */
        private const val EVENT_RESOURCE_WATERFALL = "resource_waterfall"

        /** 字段：页面 URL。 */
        private const val FIELD_PAGE_URL = "pageUrl"

        /** 字段：URL。 */
        private const val FIELD_URL = "url"

        /** 字段：资源类型。 */
        private const val FIELD_RESOURCE_TYPE = "resourceType"

        /** 字段：耗时。 */
        private const val FIELD_DURATION_MS = "durationMs"

        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"

        /** 字段：总数量。 */
        private const val FIELD_TOTAL_COUNT = "totalCount"

        /** 字段：慢资源数。 */
        private const val FIELD_SLOW_COUNT = "slowCount"

        /** 字段：关键路径资源数。 */
        private const val FIELD_CRITICAL_COUNT = "criticalCount"

        /** 字段：总耗时。 */
        private const val FIELD_TOTAL_DURATION_MS = "totalDurationMs"

        // --- 资源类型常量 ---
        /** 资源类型：脚本。 */
        private const val RESOURCE_TYPE_SCRIPT = "script"

        /** 资源类型：样式表。 */
        private const val RESOURCE_TYPE_STYLESHEET = "stylesheet"

        /** 资源类型：图片。 */
        private const val RESOURCE_TYPE_IMAGE = "image"

        /** 资源类型：XHR 请求。 */
        private const val RESOURCE_TYPE_XHR = "xhr"

        /** 资源类型：其他。 */
        private const val RESOURCE_TYPE_OTHER = "other"

        // --- URL 模式常量 ---
        /** HTTP 协议前缀。 */
        private const val HTTP_PREFIX = "http://"

        /** HTTPS 协议前缀。 */
        private const val HTTPS_PREFIX = "https://"

        /** JS 文件扩展名。 */
        private const val JS_EXTENSION = ".js"

        /** JS 查询参数标识。 */
        private const val JS_QUERY_PARAM = ".js?"

        /** CSS 文件扩展名。 */
        private const val CSS_EXTENSION = ".css"

        /** CSS 查询参数标识。 */
        private const val CSS_QUERY_PARAM = ".css?"

        /** PNG 图片扩展名。 */
        private const val PNG_EXTENSION = ".png"

        /** JPG 图片扩展名。 */
        private const val JPG_EXTENSION = ".jpg"

        /** JPEG 图片扩展名。 */
        private const val JPEG_EXTENSION = ".jpeg"

        /** GIF 图片扩展名。 */
        private const val GIF_EXTENSION = ".gif"

        /** WebP 图片扩展名。 */
        private const val WEBP_EXTENSION = ".webp"

        /** SVG 图片扩展名。 */
        private const val SVG_EXTENSION = ".svg"

        /** API 路径片段。 */
        private const val API_PATH_SEGMENT = "/api/"

        /** XHR 查询参数标识。 */
        private const val XHR_QUERY_PARAM = "xhr=1"

        /** Content-Length 响应头名称。 */
        private const val HEADER_CONTENT_LENGTH = "Content-Length"
    }
}

/**
 * 单个资源加载记录。
 * 记录每个子资源的 URL、类型、开始/结束时间、耗时等信息。
 */
data class ResourceRecord(
    /** 资源 URL。 */
    val url: String,
    /** 资源类型：script、stylesheet、image、xhr、other。 */
    val resourceType: String,
    /** 加载开始时间（毫秒时间戳）。 */
    val startTimeMs: Long,
    /** 加载结束时间（毫秒时间戳），0 表示未完成。 */
    var endTimeMs: Long = 0,
    /** 加载耗时（毫秒），0 表示未完成。 */
    var durationMs: Long = 0,
    /** HTTP 状态码，0 表示未知。 */
    var httpStatus: Int = 0,
    /** 资源大小（字节），0 表示未知。 */
    var sizeBytes: Long = 0
)

/**
 * 瀑布图统计结果。
 * 汇总一个页面加载过程中所有子资源的加载情况。
 */
data class WaterfallResult(
    /** 页面 URL。 */
    val pageUrl: String,
    /** 总资源加载数量。 */
    val totalCount: Int,
    /** 慢资源列表。 */
    val slowResources: List<ResourceRecord>,
    /** 关键路径资源列表（阻塞渲染的资源）。 */
    val criticalPathResources: List<ResourceRecord>,
    /** 页面整体加载耗时（毫秒）。 */
    val totalDurationMs: Long
)
