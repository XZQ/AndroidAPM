package com.apm.render

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.WeakHashMap

/**
 * 渲染监控模块。
 * 检测 View 层级过深、View 数量过多导致的渲染性能问题。
 *
 * 监控策略：
 * 1. 在 Activity onActivityCreated 后延迟检测 View 树
 * 2. 遍历 View 树统计数量和最大深度
 * 3. 超阈值时上报告警
 *
 * 注意：过度绘制的精确检测需要配合系统开发者选项（Show HW Overdraw），
 * 本模块侧重 View 层级分析，帮助减少不必要的嵌套。
 */
class RenderModule(private val config: RenderConfig = RenderConfig()) : ApmModule, Application.ActivityLifecycleCallbacks {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** 主线程 Handler。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false

    /** Active frame listeners retained weakly by Activity. */
    private val frameListeners = WeakHashMap<Activity, Window.OnFrameMetricsAvailableListener>()

    override fun onInitialize(context: ApmContext) {
        apmContext = context
    }

    /** 注册 Activity 生命周期回调。 */
    override fun onStart() {
        if (!config.enableRenderMonitor) {
            return
        }
        monitoring = true
        apmContext?.application?.registerActivityLifecycleCallbacks(this)
        apmContext?.logger?.d("Render module started")
    }

    /** 注销回调。 */
    override fun onStop() {
        monitoring = false
        val activities = synchronized(frameListeners) { frameListeners.keys.toList() }
        for (activity in activities) {
            detachFrameMetrics(activity)
        }
        mainHandler.removeCallbacksAndMessages(null)
        apmContext?.application?.unregisterActivityLifecycleCallbacks(this)
    }

    /**
     * Activity 创建后，延迟检测 View 树。
     * 延迟是为了等布局完成。
     */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!monitoring) {
            return
        }
        mainHandler.postDelayed({
            inspectViewTree(activity)
        }, INSPECT_DELAY_MS)
    }

    /** Starts supported API 24+ frame-duration collection for one visible Activity. */
    override fun onActivityStarted(activity: Activity) {
        if (!monitoring) {
            return
        }
        attachFrameMetrics(activity)
    }

    /** Stops frame callbacks when the Activity leaves the visible state. */
    override fun onActivityStopped(activity: Activity) {
        detachFrameMetrics(activity)
    }

    /**
     * 检测 Activity 的 View 树。
     * 遍历 DecorView，统计 View 数量和最大深度。
     */
    private fun inspectViewTree(activity: Activity) {
        val rootView = activity.window?.decorView ?: return
        val result = traverseViewTree(rootView, depth = 0)

        val stats = RenderStats(
            viewCount = result.viewCount,
            maxDepth = result.maxDepth,
            activityName = activity.javaClass.simpleName
        )

        // View 数量过多
        if (stats.viewCount >= config.viewCountThreshold) {
            reportViewCountSpike(stats)
        }

        // 层级过深
        if (stats.maxDepth >= config.viewDepthThreshold) {
            reportDeepHierarchy(stats)
        }
    }

    /**
     * 递归遍历 View 树。
     * 统计 View 总数和最大深度。
     */
    private fun traverseViewTree(view: View, depth: Int): ViewTraversalResult {
        var viewCount = 1
        var maxDepth = depth

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val childResult = traverseViewTree(child, depth + 1)
                viewCount += childResult.viewCount
                if (childResult.maxDepth > maxDepth) {
                    maxDepth = childResult.maxDepth
                }
            }
        }

        return ViewTraversalResult(viewCount, maxDepth)
    }

    /** 上报 View 数量过多。 */
    private fun reportViewCountSpike(stats: RenderStats) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_VIEW_COUNT_SPIKE,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_VIEW_COUNT to stats.viewCount,
                FIELD_ACTIVITY to stats.activityName,
                FIELD_THRESHOLD to config.viewCountThreshold
            )
        )
    }

    /** 上报层级过深。 */
    private fun reportDeepHierarchy(stats: RenderStats) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_DEEP_HIERARCHY,
            kind = ApmEventKind.ALERT,
            severity = ApmSeverity.WARN, priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_MAX_DEPTH to stats.maxDepth,
                FIELD_VIEW_COUNT to stats.viewCount,
                FIELD_ACTIVITY to stats.activityName,
                FIELD_THRESHOLD to config.viewDepthThreshold
            )
        )
    }

    /** Attaches one listener without duplicating callbacks across repeated starts. */
    private fun attachFrameMetrics(activity: Activity) {
        synchronized(frameListeners) {
            if (frameListeners.containsKey(activity)) {
                return
            }
            val accumulator = FrameMetricsAccumulator(
                windowSize = FRAME_METRICS_WINDOW_SIZE,
                slowFrameThresholdNanos = slowFrameThresholdNanos()
            )
            val activityName = activity.javaClass.simpleName
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedSinceLast ->
                val totalDurationNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val snapshot = accumulator.record(totalDurationNanos, droppedSinceLast)
                if (snapshot != null) {
                    reportFrameMetrics(activityName, snapshot)
                }
            }
            frameListeners[activity] = listener
            try {
                activity.window.addOnFrameMetricsAvailableListener(listener, mainHandler)
            } catch (error: RuntimeException) {
                // A vendor Window implementation may reject listener setup;
                // isolate that Activity without disabling hierarchy checks.
                frameListeners.remove(activity)
                apmContext?.logger?.e("Failed to attach FrameMetrics for $activityName", error)
                Apm.recordInternalError(ERROR_FRAME_METRICS_ATTACH, error)
            }
        }
    }

    /** Removes one previously attached frame listener. */
    private fun detachFrameMetrics(activity: Activity) {
        val listener = synchronized(frameListeners) { frameListeners.remove(activity) } ?: return
        try {
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        } catch (error: RuntimeException) {
            apmContext?.logger?.e("Failed to detach FrameMetrics for ${activity.javaClass.simpleName}", error)
            Apm.recordInternalError(ERROR_FRAME_METRICS_DETACH, error)
        }
    }

    /** Converts the configured millisecond threshold without long overflow. */
    private fun slowFrameThresholdNanos(): Long = config.slowFrameThresholdMs
        .coerceAtLeast(1L)
        .coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) * NANOS_PER_MILLISECOND

    /** Emits one bounded frame-metrics summary. */
    private fun reportFrameMetrics(activityName: String, snapshot: FrameMetricsSnapshot) {
        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_FRAME_METRICS,
            kind = ApmEventKind.METRIC,
            severity = if (snapshot.slowFrameCount > 0) ApmSeverity.WARN else ApmSeverity.INFO,
            priority = ApmPriority.LOW,
            fields = mapOf(
                FIELD_ACTIVITY to activityName,
                FIELD_FRAME_COUNT to snapshot.frameCount,
                FIELD_SLOW_FRAME_COUNT to snapshot.slowFrameCount,
                FIELD_AVERAGE_FRAME_MS to
                    (snapshot.totalDurationNanos.toDouble() / snapshot.frameCount / NANOS_PER_MILLISECOND),
                FIELD_MAX_FRAME_MS to snapshot.maxDurationNanos.toDouble() / NANOS_PER_MILLISECOND,
                FIELD_DROPPED_FRAMES to snapshot.droppedFrames,
                FIELD_THRESHOLD to config.slowFrameThresholdMs
            )
        )
    }

    // 空实现的回调
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        detachFrameMetrics(activity)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** View 树遍历结果。 */
    private data class ViewTraversalResult(val viewCount: Int, val maxDepth: Int)

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "render"
        /** View 数量告警事件。 */
        private const val EVENT_VIEW_COUNT_SPIKE = "view_count_spike"
        /** 层级过深事件。 */
        private const val EVENT_DEEP_HIERARCHY = "deep_hierarchy"
        /** Fixed-window FrameMetrics summary event. */
        private const val EVENT_FRAME_METRICS = "frame_metrics"
        /** 字段：View 数量。 */
        private const val FIELD_VIEW_COUNT = "viewCount"
        /** 字段：最大深度。 */
        private const val FIELD_MAX_DEPTH = "maxDepth"
        /** 字段：Activity 名。 */
        private const val FIELD_ACTIVITY = "activity"
        /** 字段：阈值。 */
        private const val FIELD_THRESHOLD = "threshold"
        /** Field: number of frames in one metrics window. */
        private const val FIELD_FRAME_COUNT = "frameCount"
        /** Field: slow frames in one metrics window. */
        private const val FIELD_SLOW_FRAME_COUNT = "slowFrameCount"
        /** Field: average total frame duration in milliseconds. */
        private const val FIELD_AVERAGE_FRAME_MS = "averageFrameMs"
        /** Field: maximum total frame duration in milliseconds. */
        private const val FIELD_MAX_FRAME_MS = "maxFrameMs"
        /** Field: platform-reported dropped listener callbacks. */
        private const val FIELD_DROPPED_FRAMES = "droppedFrames"
        /** 延迟检测时间（毫秒），等待布局完成。 */
        private const val INSPECT_DELAY_MS = 1000L
        /** Frames summarized in one bounded metrics event. */
        private const val FRAME_METRICS_WINDOW_SIZE = 60
        /** Nanoseconds contained in one millisecond. */
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** Internal-error tag for vendor listener setup failures. */
        private const val ERROR_FRAME_METRICS_ATTACH = "render_frame_metrics_attach"
        /** Internal-error tag for vendor listener removal failures. */
        private const val ERROR_FRAME_METRICS_DETACH = "render_frame_metrics_detach"
    }
}
