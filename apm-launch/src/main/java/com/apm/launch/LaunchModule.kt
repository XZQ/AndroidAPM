package com.apm.launch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Window
import com.apm.core.Apm
import com.apm.core.ApmContext
import com.apm.core.ApmModule
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity

/**
 * 启动监控模块。
 * 测量应用冷启动、热启动、温启动耗时，支持分阶段追踪。
 *
 * ## 启动类型定义（对标 Google最佳实践 + 微信Matrix）
 * - **冷启动**：进程创建 → 首帧渲染完成
 *   - Phase 1: processStart → attachBaseContext
 *   - Phase 2: attachBaseContext → ContentProvider.onCreate
 *   - Phase 3: ContentProvider.onCreate → Application.onCreate
 *   - Phase 4: Application.onCreate → firstActivity.onCreate
 *   - Phase 5: firstActivity.onCreate → firstActivity.onResume
 *   - Phase 6: firstActivity.onResume → firstFrameRendered
 *
 * - **热启动**：所有 Activity stop 后短时间恢复（Activity 仍在内存）
 * - **温启动**：所有 Activity stop 后长时间恢复（Activity 被回收，进程仍在）
 *
 * 使用 SystemClock.elapsedRealtime() 确保不受系统时钟调整影响。
 */
class LaunchModule(
    /** 模块配置。 */
    private val config: LaunchConfig = LaunchConfig()
) : ApmModule, Application.ActivityLifecycleCallbacks {

    override val name: String = MODULE_NAME

    /** APM 上下文引用。 */
    private var apmContext: ApmContext? = null

    /** 主线程 Handler，用于首帧渲染回调。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- 冷启动时间戳 ---
    /** 模块初始化时间（近似 processStart / attachBaseContext）。 */
    private var processStartMs: Long = 0L
    /** Application.onCreate 开始时间。 */
    private var appOnCreateStartMs: Long = 0L
    /** Application.onCreate 结束时间。 */
    private var appOnCreateEndMs: Long = 0L
    /** 是否已记录过冷启动（只记录一次）。 */
    private var firstActivityCreated: Boolean = false

    // --- 首帧渲染追踪 ---
    /** 第一个 Activity 的 onCreate 开始时间。 */
    private var firstActivityOnCreateMs: Long = 0L
    /** 第一个 Activity 的 onResume 开始时间。 */
    private var firstActivityOnResumeMs: Long = 0L
    /** 首帧渲染完成时间。 */
    private var firstFrameRenderedMs: Long = 0L
    /** 首帧是否已渲染。 */
    private var firstFrameRendered: Boolean = false

    // --- 热启动状态 ---
    /** 上一次所有 Activity 都进入 stopped 的时间点。 */
    private var activityStoppedTime: Long = 0L
    /** 当前是否处于 stopped 状态。 */
    private var isStopped: Boolean = false
    /** 当前正在运行的 Activity 计数。 */
    private var startedActivityCount: Int = 0

    // --- ContentProvider 追踪 ---
    /** ContentProvider.onCreate 累计耗时。 */
    private var contentProviderTotalMs: Long = 0L
    /** ContentProvider 数量。 */
    private var contentProviderCount: Int = 0

    /**
     * 记录模块初始化时间作为冷启动基准。
     * 在 Apm.register() 时调用，近似等于 attachBaseContext 时间。
     */
    override fun onInitialize(context: ApmContext) {
        apmContext = context
        processStartMs = SystemClock.elapsedRealtime()
    }

    /**
     * 注册 Activity 生命周期回调，启动监控。
     */
    override fun onStart() {
        apmContext?.application?.registerActivityLifecycleCallbacks(this)
        apmContext?.logger?.d("Launch module started, processStartMs=$processStartMs")
    }

    /**
     * 注销回调，清理资源。
     */
    override fun onStop() {
        apmContext?.application?.unregisterActivityLifecycleCallbacks(this)
        mainHandler.removeCallbacksAndMessages(null)
    }

    // --- ContentProvider 追踪 API ---

    /**
     * 记录 ContentProvider.onCreate 开始。
     * 需要在 BaseContentProvider.onCreate 入口手动调用。
     *
     * @param providerName ContentProvider 类名。
     */
    fun onContentProviderCreateStart(providerName: String) {
        if (!config.enablePhaseTracking || !config.enableContentProviderTracking) return
        // 记录开始时间到 ThreadLocal 避免并发问题
        providerStartTimes[providerName] = SystemClock.elapsedRealtime()
    }

    /**
     * 记录 ContentProvider.onCreate 结束。
     *
     * @param providerName ContentProvider 类名。
     */
    fun onContentProviderCreateEnd(providerName: String) {
        if (!config.enablePhaseTracking || !config.enableContentProviderTracking) return
        val startTime = providerStartTimes.remove(providerName) ?: return
        val duration = SystemClock.elapsedRealtime() - startTime
        contentProviderTotalMs += duration
        contentProviderCount++
    }

    /**
     * 记录 Application.onCreate 开始。
     * 在 Application.onCreate() 入口调用。
     */
    fun onAppOnCreateStart() {
        if (!config.enablePhaseTracking) return
        appOnCreateStartMs = SystemClock.elapsedRealtime()
    }

    /**
     * 记录 Application.onCreate 结束。
     * 在 Application.onCreate() 出口调用。
     */
    fun onAppOnCreateEnd() {
        if (!config.enablePhaseTracking) return
        appOnCreateEndMs = SystemClock.elapsedRealtime()
    }

    // --- ActivityLifecycleCallbacks ---

    /**
     * 第一个 Activity 创建时计算冷启动耗时。
     * 只执行一次，触发首帧渲染监听。
     */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (firstActivityCreated) return

        if (config.enablePhaseTracking) {
            // 记录 Phase 4: firstActivity.onCreate 开始
            firstActivityOnCreateMs = SystemClock.elapsedRealtime()
        }

        if (!config.enableColdStart) return
        firstActivityCreated = true

        val now = SystemClock.elapsedRealtime()
        val coldStartMs = now - processStartMs

        // 超时阈值内的才算有效冷启动
        if (coldStartMs > config.launchTimeoutMs) return

        // 判断告警级别
        val severity = when {
            coldStartMs >= config.coldStartSevereThresholdMs -> ApmSeverity.ERROR
            coldStartMs >= config.coldStartWarnThresholdMs -> ApmSeverity.WARN
            else -> ApmSeverity.INFO
        }

        // 基础冷启动事件
        val fields = mutableMapOf<String, Any>(
            FIELD_LAUNCH_DURATION_MS to coldStartMs,
            FIELD_FIRST_ACTIVITY to activity.javaClass.simpleName,
            FIELD_IS_COLD_START to true,
            FIELD_LAUNCH_TYPE to LAUNCH_TYPE_COLD,
            FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
        )

        // 分阶段耗时（如果有追踪数据）
        if (config.enablePhaseTracking) {
            fields[FIELD_PHASE_APP_CREATE_MS] = if (appOnCreateEndMs > 0L) {
                appOnCreateEndMs - processStartMs
            } else {
                now - processStartMs
            }
            fields[FIELD_PHASE_CONTENT_PROVIDER_MS] = contentProviderTotalMs
            fields[FIELD_PHASE_CONTENT_PROVIDER_COUNT] = contentProviderCount
            fields[FIELD_PHASE_ACTIVITY_CREATE_MS] = now - (if (appOnCreateEndMs > 0L) appOnCreateEndMs else processStartMs)
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_COLD_START,
            kind = ApmEventKind.METRIC,
            severity = severity,
            fields = fields
        )

        // 注册首帧渲染监听
        if (config.enableFirstFrameTracking) {
            registerFirstFrameListener(activity)
        }
    }

    /**
     * Activity onResume 时记录 Phase 5。
     * 对首帧渲染追踪至关重要。
     */
    override fun onActivityResumed(activity: Activity) {
        if (!firstActivityCreated || firstActivityOnResumeMs > 0L) return
        if (config.enablePhaseTracking) {
            // 记录 Phase 5: firstActivity.onResume
            firstActivityOnResumeMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Activity 启动时检查是否为热启动/温启动。
     * 热启动条件：之前所有 Activity 都已 stop，且这是第一个重新启动的。
     */
    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++

        // 热启动/温启动判定：之前处于 stopped 状态，且这是第一个重新 start 的
        if (isStopped && startedActivityCount == 1) {
            val now = SystemClock.elapsedRealtime()
            val durationMs = now - activityStoppedTime
            isStopped = false

            if (durationMs >= config.launchTimeoutMs) return

            // 区分热启动和温启动
            if (config.enableHotStart && durationMs < config.warmStartThresholdMs) {
                // 热启动：stop 后很快恢复（Activity 仍在内存中）
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_HOT_START,
                    kind = ApmEventKind.METRIC,
                    severity = ApmSeverity.INFO,
                    fields = mapOf(
                        FIELD_LAUNCH_DURATION_MS to durationMs,
                        FIELD_FIRST_ACTIVITY to activity.javaClass.simpleName,
                        FIELD_IS_COLD_START to false,
                        FIELD_LAUNCH_TYPE to LAUNCH_TYPE_HOT,
                        FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
                    )
                )
            } else if (config.enableWarmStart && durationMs >= config.warmStartThresholdMs) {
                // 温启动：stop 后较长时间恢复（Activity 被回收但进程仍在）
                Apm.emit(
                    module = MODULE_NAME,
                    name = EVENT_WARM_START,
                    kind = ApmEventKind.METRIC,
                    severity = ApmSeverity.INFO,
                    fields = mapOf(
                        FIELD_LAUNCH_DURATION_MS to durationMs,
                        FIELD_FIRST_ACTIVITY to activity.javaClass.simpleName,
                        FIELD_IS_COLD_START to false,
                        FIELD_LAUNCH_TYPE to LAUNCH_TYPE_WARM,
                        FIELD_PROCESS_NAME to (apmContext?.processName.orEmpty())
                    )
                )
            }
        }
    }

    /**
     * Activity 停止时更新计数。
     * 所有 Activity 都 stop 后记录时间点，为热启动计算做准备。
     */
    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount <= 0) {
            startedActivityCount = 0
            isStopped = true
            activityStoppedTime = SystemClock.elapsedRealtime()
        }
    }

    // --- 首帧渲染追踪 ---

    /**
     * 注册首帧渲染监听。
     * 使用 Choreographer.FrameCallback 检测下一个 VSync 帧渲染完成。
     * 兼容所有 API 级别（API 16+）。
     */
    private fun registerFirstFrameListener(activity: Activity) {
        try {
            // 使用 Choreographer 监听下一帧渲染
            android.view.Choreographer.getInstance().postFrameCallback(object :
                android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (firstFrameRendered) return
                    firstFrameRendered = true
                    firstFrameRenderedMs = SystemClock.elapsedRealtime()
                    reportFirstFrame()
                }
            })
        } catch (e: Exception) {
            // Choreographer 不可用时降级为 Handler post
            fallbackFirstFrameDetection()
        }
    }

    /**
     * 降级首帧检测方案。
     * 通过 Handler.post 在下一个 VSync 后检测。
     */
    private fun fallbackFirstFrameDetection() {
        mainHandler.post {
            if (!firstFrameRendered) {
                firstFrameRendered = true
                firstFrameRenderedMs = SystemClock.elapsedRealtime()
                reportFirstFrame()
            }
        }
    }

    /**
     * 上报首帧渲染事件。
     * 包含完整的冷启动分阶段耗时。
     */
    private fun reportFirstFrame() {
        val totalColdStartMs = firstFrameRenderedMs - processStartMs
        val fields = mutableMapOf<String, Any>(
            FIELD_LAUNCH_DURATION_MS to totalColdStartMs,
            FIELD_IS_COLD_START to true,
            FIELD_LAUNCH_TYPE to LAUNCH_TYPE_COLD,
            FIELD_PHASE_NAME to PHASE_FIRST_FRAME
        )

        // 分阶段耗时详情
        if (config.enablePhaseTracking) {
            // Phase 1+2+3: processStart → appOnCreateEnd
            if (appOnCreateEndMs > 0L) {
                fields[FIELD_PHASE_APP_CREATE_MS] = appOnCreateEndMs - processStartMs
            }
            // Phase 2: ContentProvider 耗时
            if (contentProviderTotalMs > 0L) {
                fields[FIELD_PHASE_CONTENT_PROVIDER_MS] = contentProviderTotalMs
                fields[FIELD_PHASE_CONTENT_PROVIDER_COUNT] = contentProviderCount
            }
            // Phase 4: appOnCreateEnd → firstActivity.onCreate
            if (appOnCreateEndMs > 0L && firstActivityOnCreateMs > 0L) {
                fields[FIELD_PHASE_BEFORE_ACTIVITY_MS] = firstActivityOnCreateMs - appOnCreateEndMs
            }
            // Phase 5: firstActivity.onCreate → onResume
            if (firstActivityOnCreateMs > 0L && firstActivityOnResumeMs > 0L) {
                fields[FIELD_PHASE_ACTIVITY_LIFECYCLE_MS] = firstActivityOnResumeMs - firstActivityOnCreateMs
            }
            // Phase 6: onResume → firstFrameRendered
            if (firstActivityOnResumeMs > 0L) {
                fields[FIELD_PHASE_FIRST_FRAME_MS] = firstFrameRenderedMs - firstActivityOnResumeMs
            }
        }

        // 判断首帧渲染是否超时
        val severity = when {
            totalColdStartMs >= config.coldStartSevereThresholdMs -> ApmSeverity.ERROR
            totalColdStartMs >= config.coldStartWarnThresholdMs -> ApmSeverity.WARN
            else -> ApmSeverity.INFO
        }

        Apm.emit(
            module = MODULE_NAME,
            name = EVENT_FIRST_FRAME,
            kind = ApmEventKind.METRIC,
            severity = severity,
            fields = fields
        )

        // 上报启动瓶颈分析
        if (config.enablePhaseTracking) {
            reportLaunchBottleneck(fields)
        }
    }

    /**
     * 分析启动瓶颈阶段。
     * 找出耗时最长的阶段并上报。
     */
    private fun reportLaunchBottleneck(fields: Map<String, Any>) {
        // 收集各阶段耗时
        val phases = mutableMapOf<String, Long>()
        (fields[FIELD_PHASE_APP_CREATE_MS] as? Number)?.toLong()?.let {
            phases[PHASE_APP_INIT] = it
        }
        (fields[FIELD_PHASE_CONTENT_PROVIDER_MS] as? Number)?.toLong()?.let {
            phases[PHASE_CONTENT_PROVIDER] = it
        }
        (fields[FIELD_PHASE_BEFORE_ACTIVITY_MS] as? Number)?.toLong()?.let {
            phases[PHASE_BEFORE_ACTIVITY] = it
        }
        (fields[FIELD_PHASE_ACTIVITY_LIFECYCLE_MS] as? Number)?.toLong()?.let {
            phases[PHASE_ACTIVITY_LIFECYCLE] = it
        }
        (fields[FIELD_PHASE_FIRST_FRAME_MS] as? Number)?.toLong()?.let {
            phases[PHASE_FIRST_FRAME] = it
        }

        // 找出瓶颈阶段
        val bottleneck = phases.maxByOrNull { it.value } ?: return
        val totalDuration = (fields[FIELD_LAUNCH_DURATION_MS] as? Number)?.toLong() ?: return

        // 瓶颈阶段占比超过 40% 才上报
        val ratio = bottleneck.value.toDouble() / totalDuration.toDouble()
        if (ratio >= BOTTLENECK_REPORT_RATIO) {
            Apm.emit(
                module = MODULE_NAME,
                name = EVENT_LAUNCH_BOTTLENECK,
                kind = ApmEventKind.ALERT,
                severity = ApmSeverity.WARN,
                fields = mapOf(
                    FIELD_BOTTLENECK_PHASE to bottleneck.key,
                    FIELD_BOTTLENECK_DURATION_MS to bottleneck.value,
                    FIELD_BOTTLENECK_RATIO to (ratio * PERCENT_FACTOR).toInt(),
                    FIELD_LAUNCH_DURATION_MS to totalDuration
                )
            )
        }
    }

    // --- 空实现的回调 ---

    /** Activity 暂停回调（不使用）。 */
    override fun onActivityPaused(activity: Activity) = Unit
    /** Activity 销毁回调（不使用）。 */
    override fun onActivityDestroyed(activity: Activity) = Unit
    /** Activity 保存状态回调（不使用）。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "launch"
        /** 冷启动事件名。 */
        private const val EVENT_COLD_START = "cold_start"
        /** 热启动事件名。 */
        private const val EVENT_HOT_START = "hot_start"
        /** 温启动事件名。 */
        private const val EVENT_WARM_START = "warm_start"
        /** 首帧渲染事件名。 */
        private const val EVENT_FIRST_FRAME = "first_frame_rendered"
        /** 启动瓶颈事件名。 */
        private const val EVENT_LAUNCH_BOTTLENECK = "launch_bottleneck"

        // --- 字段常量 ---
        /** 字段：启动耗时。 */
        private const val FIELD_LAUNCH_DURATION_MS = "launchDurationMs"
        /** 字段：首个 Activity 类名。 */
        private const val FIELD_FIRST_ACTIVITY = "firstActivityClass"
        /** 字段：是否冷启动。 */
        private const val FIELD_IS_COLD_START = "isColdStart"
        /** 字段：进程名。 */
        private const val FIELD_PROCESS_NAME = "processName"
        /** 字段：启动类型。 */
        private const val FIELD_LAUNCH_TYPE = "launchType"
        /** 字段：Application 创建阶段耗时。 */
        private const val FIELD_PHASE_APP_CREATE_MS = "phaseAppCreateMs"
        /** 字段：ContentProvider 阶段耗时。 */
        private const val FIELD_PHASE_CONTENT_PROVIDER_MS = "phaseContentProviderMs"
        /** 字段：ContentProvider 数量。 */
        private const val FIELD_PHASE_CONTENT_PROVIDER_COUNT = "phaseContentProviderCount"
        /** 字段：Activity 创建阶段耗时。 */
        private const val FIELD_PHASE_ACTIVITY_CREATE_MS = "phaseActivityCreateMs"
        /** 字段：Application 创建到 Activity 之间的耗时。 */
        private const val FIELD_PHASE_BEFORE_ACTIVITY_MS = "phaseBeforeActivityMs"
        /** 字段：Activity 生命周期耗时（onCreate→onResume）。 */
        private const val FIELD_PHASE_ACTIVITY_LIFECYCLE_MS = "phaseActivityLifecycleMs"
        /** 字段：首帧渲染耗时（onResume→firstFrame）。 */
        private const val FIELD_PHASE_FIRST_FRAME_MS = "phaseFirstFrameMs"
        /** 字段：阶段名。 */
        private const val FIELD_PHASE_NAME = "phaseName"
        /** 字段：瓶颈阶段名。 */
        private const val FIELD_BOTTLENECK_PHASE = "bottleneckPhase"
        /** 字段：瓶颈阶段耗时。 */
        private const val FIELD_BOTTLENECK_DURATION_MS = "bottleneckDurationMs"
        /** 字段：瓶颈占比百分比。 */
        private const val FIELD_BOTTLENECK_RATIO = "bottleneckRatioPercent"

        // --- 启动类型值 ---
        /** 启动类型值：冷启动。 */
        private const val LAUNCH_TYPE_COLD = "cold"
        /** 启动类型值：热启动。 */
        private const val LAUNCH_TYPE_HOT = "hot"
        /** 启动类型值：温启动。 */
        private const val LAUNCH_TYPE_WARM = "warm"

        // --- 阶段名 ---
        /** 阶段名：Application 初始化。 */
        private const val PHASE_APP_INIT = "app_init"
        /** 阶段名：ContentProvider 初始化。 */
        private const val PHASE_CONTENT_PROVIDER = "content_provider"
        /** 阶段名：Application→Activity 间隔。 */
        private const val PHASE_BEFORE_ACTIVITY = "before_activity"
        /** 阶段名：Activity 生命周期。 */
        private const val PHASE_ACTIVITY_LIFECYCLE = "activity_lifecycle"
        /** 阶段名：首帧渲染。 */
        private const val PHASE_FIRST_FRAME = "first_frame"

        // --- 阈值常量 ---
        /** 瓶颈上报最低占比：40%。 */
        private const val BOTTLENECK_REPORT_RATIO = 0.4
        /** 百分比转换因子。 */
        private const val PERCENT_FACTOR = 100.0

        /** ContentProvider 开始时间记录（ThreadLocal 避免并发问题）。 */
        private val providerStartTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
