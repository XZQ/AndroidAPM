package com.didi.apm.fps

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.Display
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.model.ApmEventKind
import com.didi.apm.model.ApmSeverity

/**
 * FPS 监控模块。
 * 基于 [FpsMonitor] 双引擎采集（Choreographer + FrameMetrics），
 * 支持丢帧严重程度分级、设备刷新率自适应、渲染管线各阶段耗时拆分。
 *
 * 对标 Matrix TraceCanary：
 * - 丢帧严重程度分级（MINOR/MODERATE/SEVERE）
 * - Activity 场景感知
 * - API 24+ FrameMetrics 细粒度渲染分析
 */
class FpsModule(
    /** 模块配置。 */
    private val config: FpsConfig = FpsConfig()
) : ApmModule, Application.ActivityLifecycleCallbacks {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null
    /** FPS 监控器，核心帧率采集引擎。 */
    private val fpsMonitor = FpsMonitor(config)
    /** 当前前台 Activity 类名，用于场景标注。 */
    private var currentScene: String = ""

    override fun onInitialize(context: ApmContext) {
        apmContext = context
        // 设置帧率统计回调
        fpsMonitor.onFrameStats = { stats -> onFrameStats(stats) }
    }

    /** 注册 Activity 生命周期回调，用于场景感知。 */
    override fun onStart() {
        if (!config.enableFpsMonitor) return
        apmContext?.application?.registerActivityLifecycleCallbacks(this)
        apmContext?.logger?.d("FPS module started, frameMetrics=${config.enableFrameMetrics}")
    }

    /** 注销回调并停止监控。 */
    override fun onStop() {
        fpsMonitor.stop()
        apmContext?.application?.unregisterActivityLifecycleCallbacks(this)
    }

    /**
     * Activity 恢复时开始 FPS 监控。
     * 记录当前场景名，获取设备刷新率，绑定 FrameMetrics。
     */
    override fun onActivityResumed(activity: Activity) {
        currentScene = activity.javaClass.simpleName
        // 获取设备刷新率并设置到 FpsMonitor
        updateRefreshRate(activity)
        // 绑定 Window 用于 FrameMetrics（API 24+）
        fpsMonitor.bindWindow(activity.window)
        fpsMonitor.start()
    }

    /**
     * Activity 暂停时停止 FPS 监控。
     * 避免后台采集浪费资源。
     */
    override fun onActivityPaused(activity: Activity) {
        fpsMonitor.stop()
    }

    // 空实现的回调
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /**
     * 获取设备刷新率并设置到 FpsMonitor。
     * 支持 60Hz/90Hz/120Hz/144Hz 等不同刷新率设备。
     */
    private fun updateRefreshRate(activity: Activity) {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            display?.let { refreshRateFromDisplay(it) }
        } catch (_: Exception) {
            // 获取刷新率失败，使用默认 60Hz
        }
    }

    /**
     * 从 Display 对象提取刷新率。
     */
    private fun refreshRateFromDisplay(display: Display) {
        val rate = display.refreshRate
        fpsMonitor.setRefreshRate(rate)
        apmContext?.logger?.d("Display refresh rate: ${rate}Hz")
    }

    /**
     * 处理帧率统计数据。
     * 当 FPS 低于告警阈值、有卡顿或有丢帧严重程度时上报事件。
     */
    private fun onFrameStats(stats: FrameStats) {
        // 只在有异常时上报
        val needReport = stats.fps < config.fpsWarnThreshold
                || stats.jankCount > 0
                || stats.frozenCount > 0
                || stats.dropSeverity > FrameStats.DROP_SEVERITY_NONE
        if (!needReport) return

        val fields = mutableMapOf<String, Any?>(
            FIELD_FPS to stats.fps,
            FIELD_DROPPED_FRAMES to stats.droppedFrames,
            FIELD_JANK_COUNT to stats.jankCount,
            FIELD_FROZEN_COUNT to stats.frozenCount,
            FIELD_FRAME_COUNT to stats.frameCount,
            FIELD_REFRESH_RATE to stats.refreshRate,
            FIELD_DROP_SEVERITY to stats.dropSeverity
        )

        // 附加场景信息
        if (config.enableSceneDetect && currentScene.isNotEmpty()) {
            fields[FIELD_SCENE] = currentScene
        }

        // 附加 FrameMetrics 各阶段耗时（API 24+）
        stats.frameMetricsBreakdown?.let { breakdown ->
            fields[FIELD_METRICS_MEASURE_LAYOUT_NS] = breakdown.measureLayoutNanos
            fields[FIELD_METRICS_DRAW_NS] = breakdown.drawNanos
            fields[FIELD_METRICS_SYNC_NS] = breakdown.syncNanos
            fields[FIELD_METRICS_SWAP_NS] = breakdown.swapBuffersNanos
            fields[FIELD_METRICS_DELAYED_FRAMES] = breakdown.delayedFrames
        }

        // 判断事件级别：丢帧严重程度越高，级别越高
        val severity = when {
            stats.frozenCount > 0 || stats.dropSeverity >= FrameStats.DROP_SEVERITY_SEVERE -> ApmSeverity.ERROR
            stats.jankCount > 0 || stats.dropSeverity >= FrameStats.DROP_SEVERITY_MODERATE -> ApmSeverity.WARN
            else -> ApmSeverity.INFO
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_FPS_STATS,
            kind = ApmEventKind.METRIC,
            severity = severity,
            fields = fields
        )
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "fps"
        /** FPS 统计事件名。 */
        private const val EVENT_FPS_STATS = "fps_stats"
        /** 字段：FPS 值。 */
        private const val FIELD_FPS = "fps"
        /** 字段：掉帧数。 */
        private const val FIELD_DROPPED_FRAMES = "droppedFrames"
        /** 字段：卡顿次数。 */
        private const val FIELD_JANK_COUNT = "jankCount"
        /** 字段：冻结次数。 */
        private const val FIELD_FROZEN_COUNT = "frozenCount"
        /** 字段：总帧数。 */
        private const val FIELD_FRAME_COUNT = "frameCount"
        /** 字段：场景名。 */
        private const val FIELD_SCENE = "scene"
        /** 字段：刷新率。 */
        private const val FIELD_REFRESH_RATE = "refreshRate"
        /** 字段：丢帧严重程度。 */
        private const val FIELD_DROP_SEVERITY = "dropSeverity"
        /** 字段：FrameMetrics measure+layout 耗时。 */
        private const val FIELD_METRICS_MEASURE_LAYOUT_NS = "metricsMeasureLayoutNs"
        /** 字段：FrameMetrics draw 耗时。 */
        private const val FIELD_METRICS_DRAW_NS = "metricsDrawNs"
        /** 字段：FrameMetrics sync 耗时。 */
        private const val FIELD_METRICS_SYNC_NS = "metricsSyncNs"
        /** 字段：FrameMetrics swapBuffers 耗时。 */
        private const val FIELD_METRICS_SWAP_NS = "metricsSwapNs"
        /** 字段：FrameMetrics 延迟帧数。 */
        private const val FIELD_METRICS_DELAYED_FRAMES = "metricsDelayedFrames"
    }
}
