package com.didi.apm.memory

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.didi.apm.core.Apm
import com.didi.apm.core.ApmContext
import com.didi.apm.core.ApmModule
import com.didi.apm.memory.leak.ActivityLeakDetector
import com.didi.apm.memory.leak.FragmentLeakDetector
import com.didi.apm.memory.leak.ViewModelLeakDetector
import com.didi.apm.memory.nativeheap.NativeHeapMonitor
import com.didi.apm.memory.oom.HprofDumper
import com.didi.apm.memory.oom.OomMonitor
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 内存监控模块。
 * 实现 [ApmModule]，提供 Java Heap 采样、泄漏检测、OOM 预警、Native 监控等能力。
 *
 * 注册方式：
 * ```kotlin
 * Apm.register(MemoryModule(MemoryConfig()))
 * ```
 */
class MemoryModule(
    /** 内存模块配置。 */
    private val config: MemoryConfig = MemoryConfig()
) : ApmModule, Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    override val name: String = MODULE_NAME

    // --- 核心组件 ---
    /** APM 上下文，在 onInitialize 时注入。 */
    private lateinit var apmContext: ApmContext
    /** 内存指标采集器。 */
    private lateinit var sampler: MemorySampler
    /** 事件上报器。 */
    private lateinit var reporter: MemoryReporter
    /** 定时采样调度器。 */
    private lateinit var scheduler: MemorySampleScheduler

    // --- 状态 ---
    /** 当前场景标签（如 Activity 类名）。 */
    private var currentScene: String = DEFAULT_SCENE
    /** 是否前台。 */
    private var foreground: Boolean = true
    /** 模块是否已启动。 */
    private var started: Boolean = false
    /** 当前设备是否命中采样（由 sampleRate 决定）。 */
    private var samplingEnabled: Boolean = true

    // --- Phase 2: 泄漏检测 ---
    /** Activity 泄漏检测器。 */
    private var activityLeakDetector: ActivityLeakDetector? = null
    /** ViewModel 泄漏检测器。 */
    private var viewModelLeakDetector: ViewModelLeakDetector? = null
    /** 每个 Activity 对应的 Fragment 泄漏检测器。线程安全 Map。 */
    private val fragmentLeakDetectors = ConcurrentHashMap<Activity, FragmentLeakDetector>()

    // --- Phase 3: OOM ---
    /** OOM 预警监控器。 */
    private var oomMonitor: OomMonitor? = null
    /** Hprof 文件 dump 器。 */
    private var hprofDumper: HprofDumper? = null

    // --- Phase 4: Native ---
    /** Native Heap 监控器。 */
    private var nativeHeapMonitor: NativeHeapMonitor? = null

    /** 系统内存回调：onTrimMemory / onLowMemory。 */
    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        /** 系统低内存回调，触发一次即时采样。 */
        override fun onLowMemory() {
            captureOnce(REASON_LOW_MEMORY)
        }

        /** onTrimMemory 回调，高危级别触发即时采样。 */
        override fun onTrimMemory(level: Int) {
            if (!config.enableTrimCallbacks) return
            // 仅响应高危级别
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) {
                captureOnce("$REASON_TRIM_MEMORY_PREFIX$level")
            }
        }
    }

    /**
     * 模块初始化。创建核心组件，决定设备采样。
     */
    override fun onInitialize(context: ApmContext) {
        apmContext = context
        sampler = MemorySampler(context.application)
        reporter = MemoryReporter(config)
        scheduler = MemorySampleScheduler {
            captureOnce(REASON_PERIODIC)
        }
        // 根据采样率决定当前设备是否启用
        samplingEnabled = Random.nextFloat() <= config.sampleRate
    }

    /**
     * 启动模块。注册生命周期回调，启动各子功能。
     */
    override fun onStart() {
        if (started || !samplingEnabled) return
        started = true

        // 注册生命周期回调
        apmContext.application.registerActivityLifecycleCallbacks(this)
        apmContext.application.registerComponentCallbacks(trimCallbacks)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // 启动定时采样
        scheduler.start(config.foregroundIntervalMs)

        // Phase 2: Activity 泄漏检测
        if (config.enableActivityLeak) {
            activityLeakDetector = ActivityLeakDetector(
                checkDelayMs = config.leakCheckDelayMs,
                onLeakFound = { reporter.onLeakFound(it) }
            )
            apmContext.application.registerActivityLifecycleCallbacks(activityLeakDetector)
        }

        // Phase 2: ViewModel 泄漏检测
        if (config.enableViewModelLeak) {
            viewModelLeakDetector = ViewModelLeakDetector()
        }

        // Phase 3: OOM 预警 + Hprof Dump
        if (config.enableOomMonitor) {
            if (config.enableHprofDump) {
                hprofDumper = HprofDumper(apmContext.application, config, apmContext.logger)
                hprofDumper?.cleanupOldFiles()
            }
            oomMonitor = OomMonitor(config, hprofDumper)
        }

        // Phase 4: Native Heap 监控
        if (config.enableNativeMonitor) {
            nativeHeapMonitor = NativeHeapMonitor()
            nativeHeapMonitor?.enable()
        }

        apmContext.logger.d("Memory module started")
    }

    /**
     * 停止模块。释放所有资源。
     */
    override fun onStop() {
        if (!started) return
        started = false

        // 注销生命周期回调
        runCatching {
            activityLeakDetector?.let {
                apmContext.application.unregisterActivityLifecycleCallbacks(it)
            }
            apmContext.application.unregisterActivityLifecycleCallbacks(this)
            apmContext.application.unregisterComponentCallbacks(trimCallbacks)
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }

        // 释放泄漏检测器
        activityLeakDetector?.shutdown()
        activityLeakDetector = null

        // 注销 Fragment 泄漏检测器
        fragmentLeakDetectors.forEach { (activity, detector) ->
            if (activity is FragmentActivity) {
                runCatching { detector.unregister(activity.supportFragmentManager) }
            }
        }
        fragmentLeakDetectors.clear()

        // 释放 OOM/Dump 资源
        hprofDumper?.shutdown()
        hprofDumper = null
        oomMonitor = null

        // 释放 Native 监控
        nativeHeapMonitor?.disable()
        nativeHeapMonitor = null

        scheduler.stop()
    }

    // --- ProcessLifecycleObserver: 前后台切换 ---

    /** 进入前台：切换到前台采样间隔。 */
    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        scheduler.reschedule(config.foregroundIntervalMs)
    }

    /** 进入后台：切换到后台采样间隔（降低频率）。 */
    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        scheduler.reschedule(config.backgroundIntervalMs)
    }

    /**
     * 执行一次即时采样。
     * 可手动调用，也可由调度器/TrimMemory 触发。
     *
     * @param reason 采样原因标识
     */
    fun captureOnce(reason: String = REASON_MANUAL) {
        if (!samplingEnabled || !Apm.isInitialized()) return
        val snapshot = sampler.buildSnapshot(currentScene, foreground)
        reporter.onSnapshot(snapshot, reason)
        // OOM 检查独立于上报逻辑，始终执行
        oomMonitor?.check(snapshot)
        nativeHeapMonitor?.reportStats(currentScene)
    }

    // --- ActivityLifecycleCallbacks ---

    /** Activity 创建时注册 Fragment 泄漏检测器。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (config.enableFragmentLeak && activity is FragmentActivity) {
            val detector = FragmentLeakDetector(activity) { result ->
                reporter.onLeakFound(result)
            }
            detector.register(activity.supportFragmentManager)
            fragmentLeakDetectors[activity] = detector
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    /** Activity onResume 时更新当前场景。 */
    override fun onActivityResumed(activity: Activity) {
        currentScene = activity.javaClass.simpleName
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Activity 销毁时检查 ViewModel 泄漏，并清理 Fragment 检测器。 */
    override fun onActivityDestroyed(activity: Activity) {
        // Phase 2: ViewModel 泄漏检查
        if (config.enableViewModelLeak && activity is FragmentActivity) {
            try {
                val store = activity.viewModelStore
                for (key in store.keys()) {
                    val viewModel = store[key] ?: continue
                    viewModelLeakDetector?.checkViewModel(viewModel)?.let { result ->
                        reporter.onLeakFound(result)
                    }
                }
            } catch (_: Exception) {
                // ViewModelStore 访问可能失败，非关键路径
            }
        }

        // 清理该 Activity 的 Fragment 泄漏检测器
        fragmentLeakDetectors.remove(activity)?.let { detector ->
            if (activity is FragmentActivity) {
                runCatching { detector.unregister(activity.supportFragmentManager) }
            }
        }
    }

    companion object {
        /** 模块名。 */
        private const val MODULE_NAME = "memory"
        /** 默认场景标签。 */
        private const val DEFAULT_SCENE = "unknown"
        /** 采样原因：定时周期采样。 */
        private const val REASON_PERIODIC = "periodic"
        /** 采样原因：手动触发。 */
        private const val REASON_MANUAL = "manual"
        /** 采样原因：系统低内存回调。 */
        private const val REASON_LOW_MEMORY = "low_memory"
        /** 采样原因：TrimMemory 回调前缀。 */
        private const val REASON_TRIM_MEMORY_PREFIX = "trim_memory_"
    }
}
