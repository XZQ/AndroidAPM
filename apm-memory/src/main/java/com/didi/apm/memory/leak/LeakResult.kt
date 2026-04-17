package com.didi.apm.memory.leak

/** 泄漏类型枚举。 */
enum class LeakType {
    /** Activity 泄漏。 */
    ACTIVITY,
    /** Fragment 泄漏。 */
    FRAGMENT,
    /** ViewModel 泄漏。 */
    VIEW_MODEL,
    /** 自定义类型。 */
    CUSTOM
}

/**
 * 泄漏检测结果。
 * 记录泄漏的类名、类型、疑似原因等信息。
 */
data class LeakResult(
    /** 泄漏对象的完整类名。 */
    val leakClass: String,
    /** 泄漏类型。 */
    val type: LeakType,
    /** 泄漏对象存活数量（通常为 1）。 */
    val retainedCount: Int = 1,
    /** 泄漏发生时的场景。 */
    val scene: String = "",
    /** 疑似导致泄漏的字段列表（如 ViewModel 持有的 Context/View 字段）。 */
    val suspectFields: List<String> = emptyList(),
    /** GC Root 引用链（从 root 到泄漏对象的路径）。 */
    val referenceChain: List<String> = emptyList(),
    /** 检测时间戳。 */
    val timestamp: Long = System.currentTimeMillis()
)
