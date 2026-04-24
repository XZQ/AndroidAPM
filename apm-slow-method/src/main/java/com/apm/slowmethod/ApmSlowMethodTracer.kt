package com.apm.slowmethod

import android.os.SystemClock
import com.apm.core.Apm
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import com.apm.model.ApmPriority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ASM 插桩运行时 Tracer。
 * 由 Gradle 插件在编译期注入的 methodEnter/methodExit 调用。
 *
 * ## 工作原理
 * 1. 插桩后的代码在方法入口调用 methodEnter(methodSignature)
 * 2. 在方法出口调用 methodExit(methodSignature)
 * 3. Tracer 自动配对 enter/exit，计算方法耗时
 * 4. 超过阈值的方法自动上报
 * 5. 同时维护热点方法统计（调用次数 + 累计耗时）
 *
 * ## 线程安全
 * 使用 ConcurrentHashMap + ThreadLocal 确保多线程安全。
 */
object ApmSlowMethodTracer {

    // --- 常量 ---
    /** 默认阈值：300ms。 */
    private const val DEFAULT_THRESHOLD_MS = 300L
    /** 默认热点方法最大数。 */
    private const val DEFAULT_MAX_HOT_METHODS = 100
    /** 严重告警阈值：800ms。 */
    private const val SEVERE_THRESHOLD_MS = 800L
    /** 纳秒转毫秒。 */
    private const val NANOS_PER_MILLIS = 1_000_000L
    /** ASM 插桩检测到的慢方法事件。 */
    private const val EVENT_SLOW_METHOD_INSTRUMENTED = "slow_method_instrumented"
    /** 字段：方法签名。 */
    private const val FIELD_METHOD = "method"
    /** 字段：耗时。 */
    private const val FIELD_DURATION_MS = "durationMs"
    /** 字段：阈值。 */
    private const val FIELD_THRESHOLD = "threshold"
    /** 字段：检测类型。 */
    private const val FIELD_DETECTION_TYPE = "detectionType"
    /** 检测类型值：ASM 插桩。 */
    private const val DETECTION_ASM = "asm"

    /** 方法耗时阈值（毫秒），与 SlowMethodConfig 同步。 */
    private var thresholdMs: Long = DEFAULT_THRESHOLD_MS

    /** 是否已启用。 */
    @Volatile
    private var enabled: Boolean = false

    /** 每个线程的方法进入时间戳栈。 */
    private val enterTimes = ThreadLocal<java.util.Stack<Pair<String, Long>>>()

    /** 热点方法统计：methodSignature → HotMethodInfo。 */
    private val hotMethods = ConcurrentHashMap<String, HotMethodInfo>()

    /** 热点方法最大记录数，防止内存泄漏。 */
    private var maxHotMethods: Int = DEFAULT_MAX_HOT_METHODS

    /**
     * 初始化 Tracer。
     * 由 SlowMethodModule.onStart() 调用。
     *
     * @param thresholdMs 方法耗时阈值。
     * @param maxHotMethods 热点方法最大记录数。
     */
    fun init(thresholdMs: Long, maxHotMethods: Int = DEFAULT_MAX_HOT_METHODS) {
        this.thresholdMs = thresholdMs
        this.maxHotMethods = maxHotMethods
        this.enabled = true
    }

    /** 禁用 Tracer。 */
    fun disable() {
        enabled = false
    }

    /**
     * ASM 插桩注入：方法入口调用。
     * 记录当前方法的进入时间戳。
     *
     * @param methodSignature 方法签名（格式：className#methodName）。
     */
    @JvmStatic
    fun methodEnter(methodSignature: String) {
        if (!enabled) return
        val stack = getOrCreateStack()
        stack.push(Pair(methodSignature, SystemClock.elapsedRealtimeNanos()))
    }

    /**
     * ASM 插桩注入：方法出口调用。
     * 计算方法耗时，超阈值上报，同时更新热点统计。
     *
     * @param methodSignature 方法签名（格式：className#methodName）。
     */
    @JvmStatic
    fun methodExit(methodSignature: String) {
        if (!enabled) return
        val stack = getOrCreateStack()

        // 栈为空或栈顶不匹配，忽略（防止异常情况）
        if (stack.isEmpty()) return
        val top = stack.pop()
        if (top.first != methodSignature) return

        // 计算方法耗时
        val durationNs = SystemClock.elapsedRealtimeNanos() - top.second
        val durationMs = durationNs / NANOS_PER_MILLIS

        // 更新热点方法统计
        updateHotMethod(methodSignature, durationMs)

        // 超阈值上报
        if (durationMs >= thresholdMs) {
            reportSlowMethod(methodSignature, durationMs)
        }
    }

    /**
     * 获取热点方法列表（按调用次数降序）。
     */
    fun getHotMethods(): List<HotMethodInfo> {
        return hotMethods.values.sortedByDescending { it.hitCount.get() }
    }

    /** 清除热点方法统计。 */
    fun clearHotMethods() {
        hotMethods.clear()
    }

    /** 上报慢方法事件。 */
    private fun reportSlowMethod(methodSignature: String, durationMs: Long) {
        val severity = if (durationMs >= SEVERE_THRESHOLD_MS) {
            ApmSeverity.ERROR
        } else {
            ApmSeverity.WARN
        }

        Apm.emit(
            module = SlowMethodModule.MODULE_NAME_REF,
            name = EVENT_SLOW_METHOD_INSTRUMENTED,
            kind = ApmEventKind.ALERT,
            severity = severity, priority = ApmPriority.NORMAL,
            fields = mapOf(
                FIELD_METHOD to methodSignature,
                FIELD_DURATION_MS to durationMs,
                FIELD_THRESHOLD to thresholdMs,
                FIELD_DETECTION_TYPE to DETECTION_ASM
            )
        )
    }

    /** 更新热点方法统计。 */
    private fun updateHotMethod(methodSignature: String, durationMs: Long) {
        // 防止热点方法表过大
        if (hotMethods.size >= maxHotMethods && !hotMethods.containsKey(methodSignature)) {
            return
        }
        val info = hotMethods.getOrPut(methodSignature) {
            HotMethodInfo(methodSignature)
        }
        info.hitCount.incrementAndGet()
        info.totalDurationMs.addAndGet(durationMs)
    }

    /** 获取或创建当前线程的栈。 */
    private fun getOrCreateStack(): java.util.Stack<Pair<String, Long>> {
        var stack = enterTimes.get()
        if (stack == null) {
            stack = java.util.Stack()
            enterTimes.set(stack)
        }
        return stack
    }

    /**
     * 热点方法信息。
     *
     * @property methodSignature 方法签名。
     * @property hitCount 命中次数。
     * @property totalDurationMs 累计耗时（毫秒）。
     */
    class HotMethodInfo(
        val methodSignature: String,
        val hitCount: AtomicInteger = AtomicInteger(0),
        val totalDurationMs: AtomicLong = AtomicLong(0L)
    )
}
