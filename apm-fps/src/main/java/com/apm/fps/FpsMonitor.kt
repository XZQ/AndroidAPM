package com.apm.fps

import com.apm.core.Apm
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window

/** Returns whether FPS collection must use perpetual VSync callbacks for the current window. */
internal fun shouldUseChoreographerFallback(frameMetricsRegistered: Boolean): Boolean =
    !frameMetricsRegistered

/**
 * FPS 监控器。
 * 双引擎采集架构：
 * 1. Choreographer.FrameCallback — 通用方案，所有 API 级别可用
 * 2. Window.OnFrameMetricsProvider (API 24+) — 细粒度渲染管线各阶段耗时
 *
 * 新增能力（对标 Matrix）：
 * - 丢帧严重程度分级：单次掉 1-3 帧为 MINOR，4-9 帧为 MODERATE，10+ 帧为 SEVERE
 * - 设备刷新率自适应：90Hz/120Hz 设备自动调整帧时间基准
 * - FrameMetrics 各阶段耗时拆分：measure/layout、draw、sync、swapBuffers
 *
 * 线程安全：所有回调在主线程执行，统计数据通过 volatile/synchronized 保护。
 */
class FpsMonitor(private val config: FpsConfig = FpsConfig()) {

    /** Choreographer 实例，用于注册 VSync 回调。 */
    private val choreographer = Choreographer.getInstance()
    /** 主线程 Handler，用于延迟任务。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Monotonic wall-clock reporting policy shared by every display refresh rate. */
    private val frameReportWindow = FrameReportWindow(config.reportIntervalMs)

    /** 是否正在监控。 */
    @Volatile
    private var monitoring = false

    // --- 窗口统计 ---
    /** 窗口内已收集的帧数。 */
    private var frameCount = 0
    /** 窗口内掉帧总数。 */
    private var droppedFrames = 0
    /** 窗口内卡顿次数（单帧超 jankThresholdMs）。 */
    private var jankCount = 0
    /** 窗口内严重卡顿次数（单帧超 frozenThresholdMs）。 */
    private var frozenCount = 0
    /** 上一帧的时间戳（纳秒）。 */
    private var lastFrameTimeNanos: Long = 0L
    /** Sum of actual frame intervals in the current window. */
    private var totalFrameIntervalNanos: Long = 0L
    /** Number of actual frame intervals in the current window. */
    private var measuredFrameIntervals: Int = 0
    /** 窗口内最高丢帧严重程度。 */
    private var maxDropSeverity = FrameStats.DROP_SEVERITY_NONE
    /** 设备刷新率（Hz）。 */
    private var refreshRate: Float = FrameStats.DEFAULT_REFRESH_RATE
    /** 单帧标准时间（纳秒），根据刷新率动态计算。 */
    private var frameDurationNanos: Long = NANOS_PER_FRAME_60FPS

    // --- FrameMetrics 引擎（API 24+） ---
    /** 当前绑定的 Window，用于 FrameMetrics 注册。 */
    @Volatile
    private var trackedWindow: Window? = null
    /** 主线程回调更新的无逐帧分配 FrameMetrics 滚动统计器。 */
    private val frameMetricsAccumulator = FrameMetricsWindowAccumulator(MAX_PENDING_FRAMES)

    /** 当前监控会话是否已记录过 FrameMetrics 读取异常。 */
    private var frameMetricsReadErrorRecorded = false

    /** FrameMetrics listener 引用，用于移除注册。 */
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    /** Whether the current window has an active event-driven FrameMetrics listener. */
    private var frameMetricsRegistered = false

    /** Whether one Choreographer fallback callback is currently pending. */
    private var choreographerCallbackPosted = false

    /** 事件回调，由 FpsModule 设置。 */
    var onFrameStats: ((FrameStats) -> Unit)? = null

    /**
     * 设置设备刷新率。
     * 由 FpsModule 在 Activity resume 时从 Display 获取并传入。
     *
     * @param rate 刷新率（Hz）
     */
    fun setRefreshRate(rate: Float) {
        refreshRate = if (rate.isFinite() && rate > 0f) rate else FrameStats.DEFAULT_REFRESH_RATE
        // 根据刷新率计算单帧标准时间
        frameDurationNanos = (NANOS_PER_SECOND / refreshRate).toLong()
    }

    /**
     * 绑定 Window 用于 FrameMetrics 采集。
     * API 24+ 时由 FpsModule 在 onActivityResumed 时调用。
     *
     * @param window 当前 Activity 的 Window
     */
    fun bindWindow(window: Window?) {
        // 解绑旧 Window
        unbindWindow()
        trackedWindow = window
        // API 24+ 注册 FrameMetrics
        if (window != null && config.enableFrameMetrics) {
            frameMetricsRegistered = registerFrameMetrics(window)
        }
        if (monitoring) {
            updateFrameSource()
        }
    }

    /**
     * 解绑 Window，注销 FrameMetrics。
     */
    fun unbindWindow() {
        trackedWindow?.let { unregisterFrameMetrics(it) }
        trackedWindow = null
        frameMetricsRegistered = false
        if (monitoring) {
            updateFrameSource()
        }
    }

    /**
     * 启动 FPS 监控。
     * 注册 VSync 回调，开始采集帧率数据。
     */
    fun start() {
        if (monitoring) {
            return
        }
        monitoring = true
        // 重置统计
        frameCount = 0
        droppedFrames = 0
        jankCount = 0
        frozenCount = 0
        lastFrameTimeNanos = 0L
        totalFrameIntervalNanos = 0L
        measuredFrameIntervals = 0
        maxDropSeverity = FrameStats.DROP_SEVERITY_NONE
        frameReportWindow.reset()
        frameMetricsAccumulator.reset()
        frameMetricsReadErrorRecorded = false
        // Prefer event-driven rendered-frame callbacks; only unsupported windows use VSync polling.
        updateFrameSource()
    }

    /**
     * 停止 FPS 监控。
     * 移除 VSync 回调，注销 FrameMetrics，清理 Handler 消息。
     */
    fun stop() {
        monitoring = false
        choreographer.removeFrameCallback(frameCallback)
        choreographerCallbackPosted = false
        mainHandler.removeCallbacksAndMessages(null)
        unbindWindow()
    }

    /** Selects exactly one rendered-frame source without keeping an idle UI thread awake. */
    private fun updateFrameSource() {
        if (!monitoring) {
            return
        }
        if (!shouldUseChoreographerFallback(frameMetricsRegistered)) {
            // A real-render listener is active, so a perpetual VSync callback would be observer load.
            choreographer.removeFrameCallback(frameCallback)
            choreographerCallbackPosted = false
            return
        }
        if (!choreographerCallbackPosted) {
            choreographerCallbackPosted = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /**
     * 帧回调。
     * 每次收到 VSync 信号时触发，计算帧间隔并更新统计。
     * 包含丢帧严重程度分级逻辑（参考 Matrix 丢帧分级）。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographerCallbackPosted = false
            if (!monitoring || frameMetricsRegistered) {
                return
            }

            recordRenderedFrame(frameTimeNanos)
            updateFrameSource()
        }
    }

    /** Records one actual rendered-frame timestamp from either supported callback source. */
    private fun recordRenderedFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos > 0L) {
            // Calculate the actual interval rather than assuming the display refresh period.
            val intervalNanos = frameTimeNanos - lastFrameTimeNanos
            val intervalMs = intervalNanos / NANOS_PER_MS
            if (intervalNanos > 0L) {
                totalFrameIntervalNanos += intervalNanos
                measuredFrameIntervals++
            }

            val expectedFrames = intervalNanos / frameDurationNanos
            val dropped = if (expectedFrames > 1) (expectedFrames - 1).toInt() else 0
            if (dropped > 0) {
                droppedFrames += dropped
            }

            if (config.enableDropSeverity && dropped > 0) {
                val severity = when {
                    dropped >= config.dropSeveritySevereThreshold -> FrameStats.DROP_SEVERITY_SEVERE
                    dropped >= config.dropSeverityModerateThreshold -> FrameStats.DROP_SEVERITY_MODERATE
                    else -> FrameStats.DROP_SEVERITY_MINOR
                }
                if (severity > maxDropSeverity) {
                    maxDropSeverity = severity
                }
            }

            if (intervalMs >= config.frozenThresholdMs) {
                frozenCount++
                jankCount++
            } else if (intervalMs >= config.jankThresholdMs) {
                jankCount++
            }
        }

        lastFrameTimeNanos = frameTimeNanos
        frameCount++
        if (frameReportWindow.onFrame(frameTimeNanos)) {
            reportAndReset()
        }
    }

    /**
     * 计算当前窗口的 FPS 并回调。
     * FPS 根据实际帧间隔和设备刷新率动态计算。
     * 重置统计计数器开始下一个窗口。
     */
    private fun reportAndReset() {
        // 根据实际刷新率计算 FPS
        val fps = calculateRenderedFps(
            measuredIntervalCount = measuredFrameIntervals,
            totalIntervalNanos = totalFrameIntervalNanos,
            maximumFps = computeMaxFps()
        )

        // 聚合 FrameMetrics 数据（API 24+）
        val breakdown = aggregateFrameMetrics()

        val stats = FrameStats(
            fps = fps,
            droppedFrames = droppedFrames,
            jankCount = jankCount,
            frozenCount = frozenCount,
            frameCount = frameCount,
            windowDurationMs = totalFrameIntervalNanos / NANOS_PER_MS,
            refreshRate = refreshRate,
            dropSeverity = maxDropSeverity,
            frameMetricsBreakdown = breakdown
        )

        // 重置窗口
        frameCount = 0
        droppedFrames = 0
        jankCount = 0
        frozenCount = 0
        maxDropSeverity = FrameStats.DROP_SEVERITY_NONE
        totalFrameIntervalNanos = 0L
        measuredFrameIntervals = 0

        try {
            onFrameStats?.invoke(stats)
        } catch (error: Exception) {
            // 可恢复的消费端异常不能中断后续 Choreographer 回调。
            Apm.recordInternalError(ERROR_TAG_FRAME_STATS_CALLBACK, error)
        }
    }

    /**
     * 聚合 FrameMetrics 队列中的各阶段耗时。
     * 计算窗口内 measure/layout、draw、sync、swapBuffers 的总耗时。
     */
    private fun aggregateFrameMetrics(): FrameMetricsBreakdown? {
        return frameMetricsAccumulator.snapshotAndReset()
    }

    /**
     * 根据刷新率计算 FPS 上限。
     * 60Hz → 60fps, 90Hz → 90fps, 120Hz → 120fps。
     */
    private fun computeMaxFps(): Int {
        return refreshRate.toInt().coerceIn(MIN_FPS_CAP, MAX_FPS_CAP)
    }

    // ========== FrameMetrics 引擎（API 24+） ==========

    /**
     * 注册 FrameMetrics 回调。
     * 采集每帧的 draw/layout/sync/swapBuffers 各阶段耗时。
     */
    private fun registerFrameMetrics(window: Window): Boolean {
        return try {
            val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                if (!monitoring) {
                    return@OnFrameMetricsAvailableListener
                }
                // FrameMetrics fires only for real rendered frames, avoiding idle VSync wakeups.
                val frameTimeNanos = recordFrameMetrics(frameMetrics)
                recordRenderedFrame(frameTimeNanos)
            }
            frameMetricsListener = listener
            window.addOnFrameMetricsAvailableListener(listener, mainHandler)
            true
        } catch (e: Exception) {
            // FrameMetrics 可能不被所有设备支持，降级到 Choreographer 模式并记入自监控
            Apm.recordInternalError(ERROR_TAG_FRAME_METRICS_REGISTER, e)
            frameMetricsListener = null
            false
        }
    }

    /**
     * 注销 FrameMetrics 回调。
     */
    private fun unregisterFrameMetrics(window: Window) {
        try {
            frameMetricsListener?.let { window.removeOnFrameMetricsAvailableListener(it) }
            frameMetricsListener = null
        } catch (e: Exception) {
            // 注销失败不影响后续监控，但记入自监控
            Apm.recordInternalError(ERROR_TAG_FRAME_METRICS_UNREGISTER, e)
        }
    }

    /**
     * 从 FrameMetrics 提取各渲染阶段耗时并更新窗口统计。
     * 使用 FrameMetrics.getMetric() 公开 API (API 24+)。
     */
    private fun recordFrameMetrics(frameMetrics: FrameMetrics): Long {
        val draw = readFrameMetric(frameMetrics, FrameMetrics.DRAW_DURATION)
        val sync = readFrameMetric(frameMetrics, FrameMetrics.SYNC_DURATION)
        val swap = readFrameMetric(frameMetrics, FrameMetrics.SWAP_BUFFERS_DURATION)

        // measure + layout = 总耗时 - draw - sync - swap（近似）
        val total = readFrameMetric(frameMetrics, FrameMetrics.TOTAL_DURATION)
        val measureLayout = (total - draw - sync - swap).coerceAtLeast(0L)

        // 检测延迟帧：INTENDED_VSYNC 与 ACTUAL_VSYNC 差距过大
        val intendedVsync = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            readFrameMetric(frameMetrics, FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        } else {
            0L
        }
        val actualVsync = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            readFrameMetric(frameMetrics, FrameMetrics.VSYNC_TIMESTAMP)
        } else {
            0L
        }
        val isDelayed = if (intendedVsync > 0L && actualVsync > 0L) {
            val vsyncDelta = if (actualVsync >= intendedVsync) {
                actualVsync - intendedVsync
            } else {
                intendedVsync - actualVsync
            }
            vsyncDelta > frameDurationNanos
        } else {
            false
        }
        frameMetricsAccumulator.record(
            measureLayoutDurationNanos = measureLayout,
            drawDurationNanos = draw,
            syncDurationNanos = sync,
            swapBuffersDurationNanos = swap,
            delayed = isDelayed
        )
        // API 26+ exposes the rendered frame's monotonic VSync timestamp. Older or broken OEM
        // implementations fall back to callback time without scheduling artificial frames.
        return actualVsync.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
    }

    /** 读取一个平台指标；同一监控会话最多记录一次可恢复异常，避免逐帧诊断风暴。 */
    private fun readFrameMetric(frameMetrics: FrameMetrics, metric: Int): Long {
        return try {
            frameMetrics.getMetric(metric)
        } catch (error: Exception) {
            if (!frameMetricsReadErrorRecorded) {
                // OEM 实现异常后继续使用 Choreographer 主路径，不重复写入同类错误。
                frameMetricsReadErrorRecorded = true
                Apm.recordInternalError(ERROR_TAG_FRAME_METRICS_READ, error)
            }
            0L
        }
    }

    companion object {
        /** FrameMetrics 滚动窗口最多保留的最近帧数。 */
        private const val MAX_PENDING_FRAMES = 1_024

        /** 自监控 tag：FrameMetrics 监听注册失败。 */
        private const val ERROR_TAG_FRAME_METRICS_REGISTER = "fps_frame_metrics_register"

        /** 自监控 tag：FrameMetrics 监听注销失败。 */
        private const val ERROR_TAG_FRAME_METRICS_UNREGISTER = "fps_frame_metrics_unregister"

        /** 自监控 tag：OEM FrameMetrics 指标读取失败。 */
        private const val ERROR_TAG_FRAME_METRICS_READ = "fps_frame_metrics_read"

        /** 自监控 tag：帧统计消费回调失败。 */
        private const val ERROR_TAG_FRAME_STATS_CALLBACK = "fps_stats_callback"

        /** 每毫秒的纳秒数。 */
        private const val NANOS_PER_MS = 1_000_000L
        /** 每秒的纳秒数。 */
        private const val NANOS_PER_SECOND = 1_000_000_000L
        /** 60fps 一帧的纳秒数（约 16.67ms）。 */
        private const val NANOS_PER_FRAME_60FPS = 16_666_667L
        /** FPS 上限。 */
        private const val MAX_FPS_CAP = 240
        /** FPS 下限。 */
        private const val MIN_FPS_CAP = 1
    }
}
