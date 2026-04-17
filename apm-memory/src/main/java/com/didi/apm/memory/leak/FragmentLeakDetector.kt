package com.didi.apm.memory.leak

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Fragment 泄漏检测器。
 * 每个 FragmentActivity 创建一个实例，监控该 Activity 下所有 Fragment。
 *
 * 检测时机：onFragmentViewDestroyed / onFragmentDestroyed 后，
 * 在主线程 IdleHandler 中检查 Fragment 是否被回收。
 */
internal class FragmentLeakDetector(
    /** 所属 Activity，用于获取场景信息。 */
    private val activity: Activity,
    /** 泄漏发现回调。 */
    private val onLeakFound: (LeakResult) -> Unit
) : FragmentManager.FragmentLifecycleCallbacks() {

    /** 已监控的 Fragment：key → WeakReference。 */
    private val watchedFragments = ConcurrentHashMap<String, WeakReference<Fragment>>()
    /** 主线程 Handler，用于调度 IdleHandler。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 注册到 SupportFragmentManager。 */
    fun register(supportFragmentManager: FragmentManager) {
        supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
    }

    /** 从 SupportFragmentManager 注销。 */
    fun unregister(supportFragmentManager: FragmentManager) {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
    }

    /**
     * Fragment View 销毁时开始监控。
     * View 销毁后 Fragment 应该可以被回收。
     */
    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
        scheduleLeakCheck(f)
    }

    /**
     * Fragment 完全销毁时开始监控。
     */
    override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
        scheduleLeakCheck(f)
    }

    /**
     * 调度泄漏检查：在主线程空闲时执行 GC + 检查。
     * 使用 IdleHandler 避免影响用户操作。
     */
    private fun scheduleLeakCheck(fragment: Fragment) {
        val key = "${fragment.javaClass.name}@${System.identityHashCode(fragment)}"
        watchedFragments[key] = WeakReference(fragment)

        // 在主线程空闲时检查，低侵入
        mainHandler.post {
            Looper.myQueue().addIdleHandler {
                checkFragment(key, fragment.javaClass.name)
                false // 只执行一次
            }
        }
    }

    /**
     * 检查单个 Fragment 是否泄漏。
     * 触发 GC 后检查引用是否仍存活。
     */
    private fun checkFragment(key: String, className: String) {
        // 触发 GC（仅在 IdleHandler 时机，不影响用户操作）
        Runtime.getRuntime().gc()
        val ref = watchedFragments[key] ?: return
        if (ref.get() != null) {
            // GC 后仍存活 → 疑似泄漏
            onLeakFound(
                LeakResult(
                    leakClass = className,
                    type = LeakType.FRAGMENT,
                    scene = activity.javaClass.simpleName
                )
            )
        }
        watchedFragments.remove(key)
    }
}
