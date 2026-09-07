package com.apm.model

/** 事件类型：指标、告警、文件。 */
enum class ApmEventKind {
    /** 常规指标数据，如内存水位、启动耗时。 */
    METRIC,
    /** 告警事件，如内存超阈值、泄漏检测。 */
    ALERT,
    /** 文件事件，如 hprof dump 文件生成。 */
    FILE
}

/** 严重级别：从 DEBUG 到 FATAL 递增。 */
enum class ApmSeverity {
    /** 调试信息。 */
    DEBUG,
    /** 一般信息。 */
    INFO,
    /** 警告，需要关注。 */
    WARN,
    /** 错误，如崩溃、ANR。 */
    ERROR,
    /** 致命错误，进程可能即将终止。 */
    FATAL
}

/**
 * APM 统一事件模型。
 * 所有模块通过 [com.apm.core.Apm.emit] 构造此对象，
 * 经过分发器存储和上传。
 */
data class ApmEvent(
    /** 模块名，如 "memory"、"crash"。 */
    val module: String,
    /** 事件名，如 "memory_snapshot"、"java_crash"。 */
    val name: String,
    /** 事件类型。 */
    val kind: ApmEventKind = ApmEventKind.METRIC,
    /** 严重级别。 */
    val severity: ApmSeverity = ApmSeverity.INFO,
    /** 事件优先级，决定上传队列排序和丢弃策略。 */
    val priority: ApmPriority = ApmPriority.NORMAL,
    /** 事件时间戳（毫秒）。 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 产生事件的进程名。 */
    val processName: String = "",
    /** 产生事件的线程名（在调用 emit 时捕获）。 */
    val threadName: String = Thread.currentThread().name,
    /** 当前场景（如 Activity 类名）。 */
    val scene: String? = null,
    /** 是否前台。 */
    val foreground: Boolean? = null,
    /** 事件指标数据，键值对形式。 */
    val fields: Map<String, Any?> = emptyMap(),
    /** 全局上下文（默认上下文 + 业务上下文合并）。 */
    val globalContext: Map<String, String> = emptyMap(),
    /** 附加键值对，用于扩展信息。 */
    val extras: Map<String, String> = emptyMap(),
    /** Stable opaque identity retained across storage, forwarding, and upload retry. */
    val eventId: String = ApmEventIdGenerator.next()
) {
    /** Backing field kept outside the primary constructor to preserve the published data-class ABI. */
    private var occurrenceSnapshot: ApmOccurrenceContext? = null

    /** Occurrence-bound release/installation/native identity for schema V3 delivery. */
    val occurrence: ApmOccurrenceContext?
        get() = occurrenceSnapshot

    /**
     * Returns a new event carrying an immutable occurrence snapshot.
     *
     * Keeping this additive method outside the primary constructor preserves the existing JVM
     * constructor, generated `copy`, `copy$default`, and component signatures for 0.1.x callers.
     */
    fun withOccurrenceContext(value: ApmOccurrenceContext): ApmEvent {
        return copy().also { event ->
            event.occurrenceSnapshot = value.copy(nativeFrames = value.nativeFrames.toList())
        }
    }
}

/**
 * 将事件序列化为 line protocol 格式。
 * 格式：ts=xxx|module=xxx|name=xxx|kind=xxx|severity=xxx|...
 * 特殊字符（| , \n）会被转义，保证单行输出。
 *
 * 直写预容量的单一 StringBuilder，替代"逐段拼接临时 String + joinToString 默认
 * 16 字符构建器"的多次扩容；各段内容与顺序不变，输出与历史实现逐字节一致。
 */
fun ApmEvent.toLineProtocol(): String {
    val builder = StringBuilder(estimateLineProtocolChars())
    builder.append("ts=").append(timestamp)
    builder.append(SEPARATOR).append("eventId=").append(eventId.sanitize())
    builder.append(SEPARATOR).append("module=").append(module.sanitize())
    builder.append(SEPARATOR).append("name=").append(name.sanitize())
    builder.append(SEPARATOR).append("kind=").append(kind.name)
    builder.append(SEPARATOR).append("severity=").append(severity.name)
    builder.append(SEPARATOR).append("priority=").append(priority.name)
    builder.append(SEPARATOR).append("process=").append(processName.sanitize())
    builder.append(SEPARATOR).append("thread=").append(threadName.sanitize())
    // 可选字段：非空时才输出
    scene?.let {
        builder.append(SEPARATOR).append("scene=").append(it.sanitize())
    }
    foreground?.let {
        builder.append(SEPARATOR).append("foreground=").append(it)
    }
    if (fields.isNotEmpty()) {
        builder.append(SEPARATOR).append("fields=").append(fields.toSortedText())
    }
    if (globalContext.isNotEmpty()) {
        builder.append(SEPARATOR).append("context=").append(globalContext.toSortedText())
    }
    if (extras.isNotEmpty()) {
        builder.append(SEPARATOR).append("extras=").append(extras.toSortedText())
    }
    return builder.toString()
}

/**
 * 字符数下界预估：头部字面量 + 各原始字符串长度 + map 段粗估。
 * 预估值只作扩容提示，偏小仅触发一次扩容，不影响输出。
 */
private fun ApmEvent.estimateLineProtocolChars(): Int {
    var total = LINE_HEADER_LITERAL_CHARS
    total += eventId.length + module.length + name.length + processName.length + threadName.length
    scene?.let { total += it.length }
    total += estimateMapTextChars(fields)
    total += estimateMapTextChars(globalContext)
    total += estimateMapTextChars(extras)
    return total
}

/** One map section's character-count lower bound without invoking host `toString`. */
private fun estimateMapTextChars(values: Map<String, *>): Int {
    if (values.isEmpty()) {
        return 0
    }
    var total = MAP_SECTION_LITERAL_CHARS
    for ((key, value) in values) {
        total += key.length + PAIR_SEPARATOR.length + EQUALS_CHAR_COUNT
        total += (value as? String)?.length ?: ESTIMATE_NON_STRING_VALUE_CHARS
    }
    return total
}

/**
 * 将 Map 序列化为排序后的文本。按键名字典序排列。
 *
 * 预容量直写实现；每个值的 `toString()` 恰好调用一次（与历史实现一致，
 * 避免对宿主对象产生可观察的额外调用），排序键与分隔符不变。
 */
private fun Map<String, *>.toSortedText(): String {
    val sortedEntries = entries.sortedBy { it.key }
    var estimate = MAP_SECTION_LITERAL_CHARS
    for ((key, value) in sortedEntries) {
        estimate += key.length + PAIR_SEPARATOR.length + EQUALS_CHAR_COUNT
        estimate += (value as? String)?.length ?: ESTIMATE_NON_STRING_VALUE_CHARS
    }
    val builder = StringBuilder(estimate)
    var firstEntry = true
    for (entry in sortedEntries) {
        if (!firstEntry) {
            builder.append(PAIR_SEPARATOR)
        }
        firstEntry = false
        builder.append(entry.key.sanitize()).append('=').append(entry.value.toString().sanitize())
    }
    return builder.toString()
}

/**
 * 清理特殊字符，防止破坏 line protocol 格式。
 * | → /，, → ;，换行 → 空格。
 *
 * 单趟扫描实现：不含保留字符时返回原引用（零分配）；含保留字符时一次遍历完成全部
 * 三类替换。替换结果（/、;、空格）不会再次命中任何保留字符，因此输出与历史
 * 链式三趟 replace 逐字节一致。
 */
private fun String.sanitize(): String {
    // 快路径：找到第一个保留字符的位置；不存在则原样返回
    var scanIndex = 0
    while (scanIndex < length) {
        val character = this[scanIndex]
        if (character == SEPARATOR_CHAR || character == PAIR_SEPARATOR_CHAR || character == NEWLINE_CHAR) {
            break
        }
        scanIndex++
    }
    if (scanIndex >= length) {
        return this
    }

    // 慢路径：从首个保留字符起单趟完成全部替换
    val result = StringBuilder(length + SANITIZE_BUILDER_SLACK)
    result.append(this, 0, scanIndex)
    for (position in scanIndex until length) {
        val character = this[position]
        result.append(
            when (character) {
                SEPARATOR_CHAR -> REPLACE_SEPARATOR
                PAIR_SEPARATOR_CHAR -> REPLACE_PAIR_SEPARATOR
                NEWLINE_CHAR -> REPLACE_NEWLINE
                else -> character
            }
        )
    }
    return result.toString()
}

/** line protocol 序列化常量。 */
private const val SEPARATOR = "|"
private const val SEPARATOR_CHAR = '|'
private const val REPLACE_SEPARATOR = "/"
private const val PAIR_SEPARATOR = ","
private const val PAIR_SEPARATOR_CHAR = ','
private const val REPLACE_PAIR_SEPARATOR = ";"
private const val NEWLINE_CHAR = '\n'
private const val REPLACE_NEWLINE = " "

/** 预留的替换膨胀空间，覆盖单字符替换最坏情况下的构建器扩容。 */
private const val SANITIZE_BUILDER_SLACK = 8

/** 头部九段的字面量字符数（键名、分隔符与枚举名的保守和）。 */
private const val LINE_HEADER_LITERAL_CHARS = 96

/** 单个 map 段的字面量字符数（段键名 + 等号 + 余量）。 */
private const val MAP_SECTION_LITERAL_CHARS = 16

/** 每个键值对的等号字符数。 */
private const val EQUALS_CHAR_COUNT = 1

/** 非字符串值不调用宿主 toString 时的固定保守字符估计。 */
private const val ESTIMATE_NON_STRING_VALUE_CHARS = 24
