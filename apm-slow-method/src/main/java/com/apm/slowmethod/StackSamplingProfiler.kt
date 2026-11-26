package com.apm.slowmethod

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.apm.core.ApmExecutors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 栈采样分析器。
 * 当 Looper hook 检测到慢方法时，触发式启动栈采样。
 * 定期抓取主线程堆栈，统计方法出现在栈中的频率，
 * 从而精确定位热点方法（无需 ASM 字节码插桩）。
 *
 * 采样模式是 Matrix 全量插桩和 Looper hook 之间的折中方案：
 * - 开销远低于全量插桩（只在触发时采样）
 * - 精度高于纯 Looper hook（能定位到具体方法）
 *
 * @param config 慢方法配置（含采样开关、采样间隔与窗口、Top N 数量）
 */
class StackSamplingProfiler(private val config: SlowMethodConfig) {

    /**
     * 采样线程，避免在主线程上做采样操作。
     * 显式设为普通优先级：栈采样是时间敏感型测量，若被宿主负载饿死会导致采样间隔漂移、
     * 热点统计失真。HandlerThread 默认即为普通优先级，此处显式声明以固化该契约。
     */
    private val samplingThread = HandlerThread(THREAD_NAME).apply {
        // 复用 SDK 两级优先级常量：栈采样是时间敏感型测量，用普通优先级避免被宿主负载饿死而采样失真。
        priority = ApmExecutors.PRIORITY_MEASUREMENT
        // daemon：与 SDK 线程治理一致，绝不阻止宿主进程/JVM 退出。
        isDaemon = true
        start()
    }
    /** 采样线程 Handler。 */
    private val samplingHandler = Handler(samplingThread.looper)
    /** 方法热点计数器：方法签名 → 出现次数。 */
    private val hotMethods = ConcurrentHashMap<String, AtomicInteger>()
    /** 总采样次数（跨主线程与采样线程原子访问）。 */
    private val sampleCount = AtomicInteger(0)
    /** 是否正在采样。 */
    @Volatile
    private var sampling = false
    /** 当前活跃的周期采样任务引用，便于独立取消而不误删同线程看门狗任务。 */
    private var activeSamplingRunnable: Runnable? = null
    /** 当前活跃的采样窗口截止任务引用。 */
    private var activeWindowStopRunnable: Runnable? = null
    /** 供模块内部测试与看门狗协作读取采样线程 Looper。 */
    internal val samplingLooper: Looper
        get() = samplingThread.looper
    /** 供模块内部查询当前是否处于活跃采样窗口。 */
    internal val isSampling: Boolean
        get() = sampling
    /** 采样结果回调。 */
    var onSamplingComplete: ((topMethods: List<MethodSample>, sampleCount: Int) -> Unit)? = null

    /**
     * 在采样工作线程上延迟调度慢分发看门狗任务。
     *
     * @param delayMs 延迟毫秒数
     * @param task 看门狗回调
     * @return 若线程仍存活并成功入队则返回 true
     */
    internal fun postWatchdog(delayMs: Long, task: Runnable): Boolean {
        return samplingHandler.postDelayed(task, delayMs)
    }

    /**
     * 从采样工作线程移除指定的看门狗任务。
     *
     * @param task 待移除的看门狗回调
     */
    internal fun removeWatchdog(task: Runnable) {
        samplingHandler.removeCallbacks(task)
    }

    /**
     * 启动栈采样。
     * 在独立线程上定期抓取主线程堆栈，持续 samplingWindowMs 毫秒。
     */
    @Synchronized
    fun startSampling() {
        if (sampling) {
            return
        }
        if (!config.enableStackSampling) {
            return
        }
        sampling = true
        sampleCount.set(0)
        hotMethods.clear()

        // 定期采样任务
        val samplingRunnable = object : Runnable {
            override fun run() {
                if (!sampling) {
                    return
                }
                // 抓取主线程堆栈
                captureMainThreadStack()
                sampleCount.incrementAndGet()
                // 继续下一次采样
                samplingHandler.postDelayed(this, config.samplingIntervalMs)
            }
        }
        val windowStopRunnable = Runnable {
            if (sampling) {
                stopSampling()
            }
        }
        activeSamplingRunnable = samplingRunnable
        activeWindowStopRunnable = windowStopRunnable
        samplingHandler.post(samplingRunnable)

        // 设置窗口结束回调（仅在仍处于本次活跃采样时自动停止并汇报）
        samplingHandler.postDelayed(windowStopRunnable, config.samplingWindowMs)
    }

    /**
     * 停止栈采样并输出结果。
     * 按出现次数排序，取 Top N 热点方法。
     */
    @Synchronized
    fun stopSampling() {
        sampling = false
        // 仅移除采样自身任务，避免清除同 Handler 上正在运行的慢消息看门狗
        activeSamplingRunnable?.let { samplingHandler.removeCallbacks(it) }
        activeWindowStopRunnable?.let { samplingHandler.removeCallbacks(it) }
        activeSamplingRunnable = null
        activeWindowStopRunnable = null

        // 排序并取 Top N
        val topMethods = hotMethods.entries
            .sortedByDescending { it.value.get() }
            .take(config.topMethodCount)
            .map { MethodSample(it.key, it.value.get()) }

        // 回调结果
        onSamplingComplete?.invoke(topMethods, sampleCount.get())
    }

    /**
     * 抓取主线程堆栈并统计方法热点。
     * 遍历堆栈元素，将每个方法签名计数 +1。
     */
    private fun captureMainThreadStack() {
        val mainThread = Looper.getMainLooper().thread
        val stackTrace = mainThread.stackTrace
        // 遍历堆栈元素，跳过前几个系统方法
        for (element in stackTrace) {
            val signature = "${element.className}.${element.methodName}"
            // 跳过系统类
            if (shouldSkip(signature)) {
                continue
            }
            hotMethods.getOrPut(signature) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    /**
     * 判断方法签名是否应跳过。
     * 过滤掉系统类和框架类，只保留业务方法。
     */
    private fun shouldSkip(signature: String): Boolean {
        // 跳过 Android 系统类、Java 标准库及 APM 慢方法模块自身包装帧
        return signature.startsWith("android.")
                || signature.startsWith("androidx.")
                || signature.startsWith("java.")
                || signature.startsWith("javax.")
                || signature.startsWith("kotlin.")
                || signature.startsWith("com.android.")
                || signature.startsWith("sun.")
                || signature.startsWith(APM_SLOW_METHOD_PACKAGE_PREFIX)
    }

    /**
     * 释放采样线程资源。
     */
    fun destroy() {
        sampling = false
        activeSamplingRunnable = null
        activeWindowStopRunnable = null
        samplingHandler.removeCallbacksAndMessages(null)
        samplingThread.quitSafely()
    }

    /** 方法采样结果。 */
    data class MethodSample(
        /** 方法签名（ClassName.methodName）。 */
        val methodSignature: String,
        /** 出现在采样堆栈中的次数。 */
        val hitCount: Int
    )

    companion object {
        /** 采样线程名。 */
        private const val THREAD_NAME = "apm-stack-sampler"
        /** APM 慢方法模块包名前缀，避免把监控框架帧计入业务热点。 */
        private const val APM_SLOW_METHOD_PACKAGE_PREFIX = "com.apm.slowmethod."
    }
}
