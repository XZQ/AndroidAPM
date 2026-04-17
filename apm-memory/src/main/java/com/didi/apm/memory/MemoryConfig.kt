package com.didi.apm.memory

/**
 * 内存模块配置项。
 * 涵盖采样间隔、阈值、泄漏检测、OOM dump、Native 监控等全部参数。
 */
data class MemoryConfig(
    // --- 采样调度 ---
    /** 前台采样间隔（毫秒）。 */
    val foregroundIntervalMs: Long = DEFAULT_FOREGROUND_INTERVAL_MS,
    /** 后台采样间隔（毫秒）。 */
    val backgroundIntervalMs: Long = DEFAULT_BACKGROUND_INTERVAL_MS,

    // --- 阈值告警 ---
    /** Java Heap 使用率告警阈值（0.0~1.0）。 */
    val javaHeapWarnRatio: Float = DEFAULT_JAVA_HEAP_WARN_RATIO,
    /** Java Heap 使用率危险阈值（0.0~1.0），达到后触发 dump。 */
    val javaHeapCriticalRatio: Float = DEFAULT_JAVA_HEAP_CRITICAL_RATIO,
    /** PSS 总量告警阈值（KB）。 */
    val totalPssWarnKb: Int = DEFAULT_TOTAL_PSS_WARN_KB,

    // --- 采样率 ---
    /** 设备采样率（0.0~1.0），用于灰度。1.0 = 全量。 */
    val sampleRate: Float = DEFAULT_SAMPLE_RATE,
    /** 是否每条 snapshot 都上报。false 时仅超阈值时上报。 */
    val reportEverySnapshot: Boolean = true,
    /** 是否监听 onTrimMemory 回调并触发采样。 */
    val enableTrimCallbacks: Boolean = true,

    // --- 泄漏检测 (Phase 2) ---
    /** 是否开启 Activity 泄漏检测。 */
    val enableActivityLeak: Boolean = true,
    /** 是否开启 Fragment 泄漏检测。 */
    val enableFragmentLeak: Boolean = true,
    /** 是否开启 ViewModel 泄漏检测。 */
    val enableViewModelLeak: Boolean = true,
    /** Activity 销毁后延迟多久检查泄漏（毫秒），给 GC 足够时间。 */
    val leakCheckDelayMs: Long = DEFAULT_LEAK_CHECK_DELAY_MS,

    // --- OOM / Dump (Phase 3) ---
    /** 是否开启 OOM 预警监控。 */
    val enableOomMonitor: Boolean = true,
    /** 是否开启 hprof dump。 */
    val enableHprofDump: Boolean = false,
    /** dump 冷却时间（毫秒），防止频繁 dump。 */
    val dumpCooldownMs: Long = DEFAULT_DUMP_COOLDOWN_MS,
    /** 是否开启 hprof strip 裁剪。 */
    val enableHprofStrip: Boolean = false,

    // --- Native (Phase 4) ---
    /** 是否开启 Native Heap 监控。 */
    val enableNativeMonitor: Boolean = false,
    /** Native Heap 分配量告警阈值（KB）。 */
    val nativeHeapWarnKb: Long = DEFAULT_NATIVE_HEAP_WARN_KB
) {
    companion object {
        private const val DEFAULT_FOREGROUND_INTERVAL_MS = 15_000L
        private const val DEFAULT_BACKGROUND_INTERVAL_MS = 60_000L
        private const val DEFAULT_JAVA_HEAP_WARN_RATIO = 0.80f
        private const val DEFAULT_JAVA_HEAP_CRITICAL_RATIO = 0.90f
        private const val DEFAULT_TOTAL_PSS_WARN_KB = 300 * 1024
        private const val DEFAULT_SAMPLE_RATE = 1.0f
        private const val DEFAULT_LEAK_CHECK_DELAY_MS = 5_000L
        private const val DEFAULT_DUMP_COOLDOWN_MS = 10 * 60 * 1000L
        private const val DEFAULT_NATIVE_HEAP_WARN_KB = 512 * 1024L
    }
}
