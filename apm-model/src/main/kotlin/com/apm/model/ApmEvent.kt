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
    val extras: Map<String, String> = emptyMap()
)

/**
 * 将事件序列化为 line protocol 格式。
 * 格式：ts=xxx|module=xxx|name=xxx|kind=xxx|severity=xxx|...
 * 特殊字符（| , \n）会被转义，保证单行输出。
 */
fun ApmEvent.toLineProtocol(): String {
    val segments = mutableListOf(
        "ts=$timestamp",
        "module=${module.sanitize()}",
        "name=${name.sanitize()}",
        "kind=${kind.name}",
        "severity=${severity.name}",
        "process=${processName.sanitize()}",
        "thread=${threadName.sanitize()}"
    )
    // 可选字段：非空时才输出
    scene?.let { segments += "scene=${it.sanitize()}" }
    foreground?.let { segments += "foreground=$it" }
    if (fields.isNotEmpty()) {
        segments += "fields=${fields.toSortedText()}"
    }
    if (globalContext.isNotEmpty()) {
        segments += "context=${globalContext.toSortedText()}"
    }
    if (extras.isNotEmpty()) {
        segments += "extras=${extras.toSortedText()}"
    }
    return segments.joinToString(SEPARATOR)
}

/**
 * 将 Map 序列化为排序后的文本。按键名字典序排列。
 */
private fun Map<String, *>.toSortedText(): String {
    return entries
        .sortedBy { it.key }
        .joinToString(PAIR_SEPARATOR) { (key, value) ->
            "${key.sanitize()}=${value.toString().sanitize()}"
        }
}

/**
 * 清理特殊字符，防止破坏 line protocol 格式。
 * | → /，, → ;，换行 → 空格。
 */
private fun String.sanitize(): String {
    return replace(SEPARATOR_CHAR, REPLACE_SEPARATOR)
        .replace(PAIR_SEPARATOR, REPLACE_PAIR_SEPARATOR)
        .replace(NEWLINE, REPLACE_NEWLINE)
}

/** line protocol 序列化常量。 */
private const val SEPARATOR = "|"
private const val SEPARATOR_CHAR = "|"
private const val REPLACE_SEPARATOR = "/"
private const val PAIR_SEPARATOR = ","
private const val REPLACE_PAIR_SEPARATOR = ";"
private const val NEWLINE = "\n"
private const val REPLACE_NEWLINE = " "
