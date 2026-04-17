package com.apm.slowmethod

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
 */
class StackSamplingProfiler(
    /** 模块配置。 */
    private val config: SlowMethodConfig
) {

    /** 采样线程，避免在主线程上做采样操作。 */
    private val samplingThread = HandlerThread(THREAD_NAME).apply { start() }
    /** 采样线程 Handler。 */
    private val samplingHandler = Handler(samplingThread.looper)
    /** 方法热点计数器：方法签名 → 出现次数。 */
    private val hotMethods = ConcurrentHashMap<String, AtomicInteger>()
    /** 总采样次数。 */
    @Volatile
    private var sampleCount = 0
    /** 是否正在采样。 */
    @Volatile
    private var sampling = false
    /** 采样结果回调。 */
    var onSamplingComplete: ((topMethods: List<MethodSample>, sampleCount: Int) -> Unit)? = null

    /**
     * 启动栈采样。
     * 在独立线程上定期抓取主线程堆栈，持续 samplingWindowMs 毫秒。
     */
    fun startSampling() {
        if (sampling) return
        if (!config.enableStackSampling) return
        sampling = true
        sampleCount = 0
        hotMethods.clear()

        // 定期采样任务
        val samplingRunnable = object : Runnable {
            override fun run() {
                if (!sampling) return
                // 抓取主线程堆栈
                captureMainThreadStack()
                sampleCount++
                // 继续下一次采样
                samplingHandler.postDelayed(this, config.samplingIntervalMs)
            }
        }
        samplingHandler.post(samplingRunnable)

        // 设置窗口结束回调
        samplingHandler.postDelayed({
            stopSampling()
        }, config.samplingWindowMs)
    }

    /**
     * 停止栈采样并输出结果。
     * 按出现次数排序，取 Top N 热点方法。
     */
    fun stopSampling() {
        sampling = false
        samplingHandler.removeCallbacksAndMessages(null)

        // 排序并取 Top N
        val topMethods = hotMethods.entries
            .sortedByDescending { it.value.get() }
            .take(config.topMethodCount)
            .map { MethodSample(it.key, it.value.get()) }

        // 回调结果
        onSamplingComplete?.invoke(topMethods, sampleCount)
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
            if (shouldSkip(signature)) continue
            hotMethods.getOrPut(signature) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    /**
     * 判断方法签名是否应跳过。
     * 过滤掉系统类和框架类，只保留业务方法。
     */
    private fun shouldSkip(signature: String): Boolean {
        // 跳过 Android 系统类和 Java 标准库
        return signature.startsWith("android.")
                || signature.startsWith("androidx.")
                || signature.startsWith("java.")
                || signature.startsWith("javax.")
                || signature.startsWith("kotlin.")
                || signature.startsWith("com.android.")
                || signature.startsWith("sun.")
    }

    /**
     * 释放采样线程资源。
     */
    fun destroy() {
        sampling = false
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
    }
}
