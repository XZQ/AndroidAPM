package com.didi.apm.memory.leak

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Activity 泄漏检测器。
 * 核心思路：Activity onDestroy 后，用 WeakReference 持有引用，
 * 延迟 checkDelayMs 后主动触发 GC，检查引用是否被回收。
 * 未被回收则判定为疑似泄漏。
 *
 * 基于 Application.ActivityLifecycleCallbacks，全局注册。
 */
internal class ActivityLeakDetector(
    /** onDestroy 后延迟多久检查泄漏（毫秒）。给 GC 足够回收时间。 */
    private val checkDelayMs: Long = DEFAULT_CHECK_DELAY_MS,
    /** 泄漏发现回调。 */
    private val onLeakFound: (LeakResult) -> Unit
) : android.app.Application.ActivityLifecycleCallbacks {

    /** 已监控的 Activity：key → WeakReference。 */
    private val watchedActivities = ConcurrentHashMap<String, WeakReference<Activity>>()
    /** 已 destroy 但尚未检查的 key 集合。 */
    private val destroyedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 泄漏检查线程。独立线程避免阻塞主线程或采样线程。 */
    private val checkThread = HandlerThread(THREAD_NAME).apply { start() }
    /** 检查线程的 Handler。 */
    private val checkHandler = Handler(checkThread.looper)

    /** 当前场景标签。 */
    private var currentScene: String = DEFAULT_SCENE

    /**
     * Activity 销毁时触发监控。
     * 创建 WeakReference 并延迟调度泄漏检查。
     */
    override fun onActivityDestroyed(activity: Activity) {
        val key = "${activity.javaClass.name}@${System.identityHashCode(activity)}"
        watchedActivities[key] = WeakReference(activity)
        destroyedKeys.add(key)

        // 延迟检查，给 GC 足够时间回收
        checkHandler.postDelayed({
            triggerGcAndCheck(key, activity.javaClass.name)
        }, checkDelayMs)
    }

    /** 更新当前场景。 */
    override fun onActivityResumed(activity: Activity) {
        currentScene = activity.javaClass.simpleName
    }

    /**
     * GC 后检查泄漏。
     * 流程：触发 GC → 等待 finalize → 检查引用是否仍存活。
     */
    private fun triggerGcAndCheck(key: String, className: String) {
        // 如果 key 已被移除（如模块 stop），跳过检查
        if (!destroyedKeys.contains(key)) return

        // 主动触发 GC（仅在泄漏检查线程，不影响用户操作）
        Runtime.getRuntime().gc()
        SystemClock.sleep(GC_WAIT_MS)
        System.runFinalization()

        // GC 后引用仍存活 → 疑似泄漏，分析引用链
        val ref = watchedActivities[key]
        val leakedActivity = ref?.get()
        if (leakedActivity != null) {
            // 分析引用链：检查 Activity 的字段引用
            val refChain = analyzeReferenceChain(leakedActivity)
            onLeakFound(
                LeakResult(
                    leakClass = className,
                    type = LeakType.ACTIVITY,
                    scene = currentScene,
                    referenceChain = refChain
                )
            )
        }
        // 清理监控记录
        watchedActivities.remove(key)
        destroyedKeys.remove(key)
    }

    /** 关闭检查线程。模块 stop 时调用。 */
    fun shutdown() {
        checkThread.quitSafely()
    }

    /**
     * 分析泄漏 Activity 的引用链。
     * 通过反射遍历 Activity 的字段，识别持有的外部引用。
     * 标记 Context、View、Handler 等常见泄漏源。
     */
    private fun analyzeReferenceChain(activity: Activity): List<String> {
        val chain = mutableListOf<String>()
        try {
            var clazz: Class<*>? = activity.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    field.isAccessible = true
                    val value = try { field.get(activity) } catch (e: Exception) { null }
                    if (value == null) continue
                    // 检查常见泄漏源
                    val fieldName = "${clazz.simpleName}.${field.name}"
                    when (value) {
                        is android.content.Context -> {
                            // 非自身 Activity 的 Context 引用
                            if (value !== activity) {
                                chain.add("$fieldName -> Context(${value.javaClass.simpleName})")
                            }
                        }
                        is android.view.View -> {
                            chain.add("$fieldName -> View(${value.javaClass.simpleName})")
                        }
                        is android.os.Handler -> {
                            chain.add("$fieldName -> Handler(${value.javaClass.simpleName})")
                        }
                        is Runnable -> {
                            chain.add("$fieldName -> Runnable(${value.javaClass.simpleName})")
                        }
                        is Thread -> {
                            chain.add("$fieldName -> Thread(\"${value.name}\")")
                        }
                    }
                }
                // 向上遍历父类
                clazz = clazz.superclass
            }
        } catch (e: Exception) {
            // 反射失败不影响泄漏检测结果
        }
        return chain
    }

    // 空实现的回调
    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    companion object {
        /** 检查线程名。 */
        private const val THREAD_NAME = "leak-check-activity"
        /** 默认检查延迟：5 秒。 */
        private const val DEFAULT_CHECK_DELAY_MS = 5_000L
        /** 默认场景。 */
        private const val DEFAULT_SCENE = "unknown"
        /** GC 后等待 finalize 的时间（毫秒）。 */
        private const val GC_WAIT_MS = 100L
    }
}
