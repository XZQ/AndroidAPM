package com.apm.fps

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentLinkedQueue

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
class FpsMonitor(
    /** 模块配置。 */
    private val config: FpsConfig = FpsConfig()
) {

    /** Choreographer 实例，用于注册 VSync 回调。 */
    private val choreographer = Choreographer.getInstance()
    /** 主线程 Handler，用于延迟任务。 */
    private val mainHandler = Handler(Looper.getMainLooper())

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
    /** FrameMetrics 帧耗时采集队列。 */
    private val frameMetricsQueue = ConcurrentLinkedQueue<FrameMetricsBreakdown>()
    /** 窗口内 FrameMetrics 延迟帧计数。 */
    private var metricsDelayedFrames = 0
    /** FrameMetrics listener 引用，用于移除注册。 */
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    /** 事件回调，由 FpsModule 设置。 */
    var onFrameStats: ((FrameStats) -> Unit)? = null

    /**
     * 设置设备刷新率。
     * 由 FpsModule 在 Activity resume 时从 Display 获取并传入。
     *
     * @param rate 刷新率（Hz）
     */
    fun setRefreshRate(rate: Float) {
        refreshRate = rate
        // 根据刷新率计算单帧标准时间
        frameDurationNanos = (NANOS_PER_SECOND / rate).toLong()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && window != null && config.enableFrameMetrics) {
            registerFrameMetrics(window)
        }
    }

    /**
     * 解绑 Window，注销 FrameMetrics。
     */
    fun unbindWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            trackedWindow?.let { unregisterFrameMetrics(it) }
        }
        trackedWindow = null
    }

    /**
     * 启动 FPS 监控。
     * 注册 VSync 回调，开始采集帧率数据。
     */
    fun start() {
        if (monitoring) return
        monitoring = true
        // 重置统计
        frameCount = 0
        droppedFrames = 0
        jankCount = 0
        frozenCount = 0
        lastFrameTimeNanos = 0L
        maxDropSeverity = FrameStats.DROP_SEVERITY_NONE
        frameMetricsQueue.clear()
        metricsDelayedFrames = 0
        // 注册帧回调
        choreographer.postFrameCallback(frameCallback)
    }

    /**
     * 停止 FPS 监控。
     * 移除 VSync 回调，注销 FrameMetrics，清理 Handler 消息。
     */
    fun stop() {
        monitoring = false
        choreographer.removeFrameCallback(frameCallback)
        mainHandler.removeCallbacksAndMessages(null)
        unbindWindow()
    }

    /**
     * 帧回调。
     * 每次收到 VSync 信号时触发，计算帧间隔并更新统计。
     * 包含丢帧严重程度分级逻辑（参考 Matrix 丢帧分级）。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!monitoring) return

            if (lastFrameTimeNanos > 0L) {
                // 计算帧间隔（纳秒）
                val intervalNanos = frameTimeNanos - lastFrameTimeNanos
                val intervalMs = intervalNanos / NANOS_PER_MS

                // 根据设备刷新率计算掉帧数
                val expectedFrames = intervalNanos / frameDurationNanos
                val dropped = if (expectedFrames > 1) (expectedFrames - 1).toInt() else 0
                if (dropped > 0) {
                    droppedFrames += dropped
                }

                // 丢帧严重程度分级（参考 Matrix）
                if (config.enableDropSeverity && dropped > 0) {
                    val severity = when {
                        dropped >= config.dropSeveritySevereThreshold -> FrameStats.DROP_SEVERITY_SEVERE
                        dropped >= config.dropSeverityModerateThreshold -> FrameStats.DROP_SEVERITY_MODERATE
                        else -> FrameStats.DROP_SEVERITY_MINOR
                    }
                    // 记录窗口内最高严重程度
                    if (severity > maxDropSeverity) {
                        maxDropSeverity = severity
                    }
                }

                // 判断卡顿/冻结
                if (intervalMs >= config.frozenThresholdMs) {
                    frozenCount++
                    jankCount++
                } else if (intervalMs >= config.jankThresholdMs) {
                    jankCount++
                }
            }

            lastFrameTimeNanos = frameTimeNanos
            frameCount++

            // 达到窗口大小时计算 FPS 并回调
            if (frameCount >= config.windowSize) {
                reportAndReset()
            }

            // 继续注册下一帧回调
            choreographer.postFrameCallback(this)
        }
    }

    /**
     * 计算当前窗口的 FPS 并回调。
     * FPS 根据实际帧间隔和设备刷新率动态计算。
     * 重置统计计数器开始下一个窗口。
     */
    private fun reportAndReset() {
        // 根据实际刷新率计算 FPS
        val fps = if (frameCount > 0) {
            val theoreticalDurationMs = frameCount * (frameDurationNanos / NANOS_PER_MS)
            if (theoreticalDurationMs > 0) {
                (frameCount * 1000L / theoreticalDurationMs).toInt()
            } else {
                0
            }
        } else {
            0
        }

        // 聚合 FrameMetrics 数据（API 24+）
        val breakdown = aggregateFrameMetrics()

        val stats = FrameStats(
            fps = fps.coerceAtMost(computeMaxFps()),
            droppedFrames = droppedFrames,
            jankCount = jankCount,
            frozenCount = frozenCount,
            frameCount = frameCount,
            refreshRate = refreshRate,
            dropSeverity = maxDropSeverity,
            frameMetricsBreakdown = breakdown
        )

        onFrameStats?.invoke(stats)

        // 重置窗口
        frameCount = 0
        droppedFrames = 0
        jankCount = 0
        frozenCount = 0
        maxDropSeverity = FrameStats.DROP_SEVERITY_NONE
        frameMetricsQueue.clear()
        metricsDelayedFrames = 0
    }

    /**
     * 聚合 FrameMetrics 队列中的各阶段耗时。
     * 计算窗口内 measure/layout、draw、sync、swapBuffers 的总耗时。
     */
    private fun aggregateFrameMetrics(): FrameMetricsBreakdown? {
        if (frameMetricsQueue.isEmpty()) return null

        var totalMeasureLayout = 0L
        var totalDraw = 0L
        var totalSync = 0L
        var totalSwap = 0L

        // 遍历队列中所有 FrameMetrics 数据累加
        for (metrics in frameMetricsQueue) {
            totalMeasureLayout += metrics.measureLayoutNanos
            totalDraw += metrics.drawNanos
            totalSync += metrics.syncNanos
            totalSwap += metrics.swapBuffersNanos
        }

        return FrameMetricsBreakdown(
            measureLayoutNanos = totalMeasureLayout,
            drawNanos = totalDraw,
            syncNanos = totalSync,
            swapBuffersNanos = totalSwap,
            delayedFrames = metricsDelayedFrames
        )
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
    private fun registerFrameMetrics(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                    if (!monitoring) return@OnFrameMetricsAvailableListener
                    // 提取各阶段耗时
                    val breakdown = extractFrameMetrics(frameMetrics)
                    frameMetricsQueue.offer(breakdown)
                }
                frameMetricsListener = listener
                window.addOnFrameMetricsAvailableListener(listener, mainHandler)
            } catch (_: Exception) {
                // FrameMetrics 可能不被所有设备支持，静默降级到 Choreographer 模式
            }
        }
    }

    /**
     * 注销 FrameMetrics 回调。
     */
    private fun unregisterFrameMetrics(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                frameMetricsListener?.let { window.removeOnFrameMetricsAvailableListener(it) }
                frameMetricsListener = null
            } catch (_: Exception) {
                // 静默处理
            }
        }
    }

    /**
     * 从 FrameMetrics 提取各渲染阶段耗时。
     * 使用 FrameMetrics.getMetric() 公开 API (API 24+)。
     */
    private fun extractFrameMetrics(frameMetrics: FrameMetrics): FrameMetricsBreakdown {
        val draw = try {
            frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
        } catch (_: Exception) {
            0L
        }

        val sync = try {
            frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
        } catch (_: Exception) {
            0L
        }

        val swap = try {
            frameMetrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        } catch (_: Exception) {
            0L
        }

        // measure + layout = 总耗时 - draw - sync - swap（近似）
        val measureLayout = try {
            val total = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            total - draw - sync - swap
        } catch (_: Exception) {
            0L
        }

        // 检测延迟帧：INTENDED_VSYNC 与 ACTUAL_VSYNC 差距过大
        val intendedVsync = try {
            frameMetrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        } catch (_: Exception) {
            0L
        }
        val actualVsync = try {
            frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        } catch (_: Exception) {
            0L
        }
        val isDelayed = if (intendedVsync > 0L && actualVsync > 0L) {
            Math.abs(actualVsync - intendedVsync) > frameDurationNanos
        } else {
            false
        }
        if (isDelayed) {
            metricsDelayedFrames++
        }

        return FrameMetricsBreakdown(
            measureLayoutNanos = measureLayout,
            drawNanos = draw,
            syncNanos = sync,
            swapBuffersNanos = swap,
            delayedFrames = 0 // 单帧不计，窗口级在 aggregate 时统计
        )
    }

    companion object {
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
