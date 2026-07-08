package com.apm.crash

import com.apm.model.ApmSeverity
import java.io.InputStream

/**
 * 单条进程退出记录的平台无关快照。
 * 与 android.app.ApplicationExitInfo 解耦，便于 JVM 单元测试。
 *
 * @property timestampMs 退出时间戳（epoch 毫秒）
 * @property reasonCode 退出原因码（与 ApplicationExitInfo.REASON_* 对齐）
 * @property description 系统提供的退出描述，可为 null
 * @property importance 退出时的进程重要性
 * @property traceSupplier ANR 退出时的系统 trace 流工厂，可为 null
 */
internal data class ExitRecord(
    val timestampMs: Long,
    val reasonCode: Int,
    val description: String?,
    val importance: Int,
    val traceSupplier: (() -> InputStream?)? = null
)

/**
 * 退出记录数据源。
 * Android 侧由 ApplicationExitInfo 实现（API 30+），测试注入替身。
 */
internal fun interface ExitInfoSource {

    /**
     * 读取最近的进程退出记录。
     *
     * @param maxRecords 最大读取条数
     * @return 退出记录列表（顺序不限）
     */
    fun latestExitRecords(maxRecords: Int): List<ExitRecord>
}

/**
 * 已处理退出记录时间戳的持久化存储。
 * 用于跨启动去重：只上报比上次处理更新的记录。
 */
internal interface ExitTimestampStore {

    /** 读取上次处理到的时间戳（毫秒），无记录返回 0。 */
    fun lastProcessedMs(): Long

    /**
     * 保存本次处理到的最新时间戳。
     *
     * @param value 时间戳（毫秒）
     */
    fun saveLastProcessedMs(value: Long)
}

/**
 * ApplicationExitInfo 退出原因采集器。
 *
 * 在应用启动后读取系统记录的历史进程退出原因（ANR、native crash、
 * OOM 被杀、系统信号等）。这是 Android 官方（API 30+）提供的退因
 * 数据源，无需任何 hook，且能捕获 SDK 在崩溃现场无法感知的退出
 * （如 LMK OOM kill、系统 stop）。ANR 记录会附带系统 trace 摘要。
 */
internal class ExitReasonCollector(
    /** 退出记录来源。 */
    private val source: ExitInfoSource,
    /** 去重时间戳存储。 */
    private val timestampStore: ExitTimestampStore,
    /** ANR trace 附带内容的最大字节数。 */
    private val maxTraceBytes: Int = DEFAULT_MAX_TRACE_BYTES,
    /** 事件上报回调：(事件名, 严重级别, 字段)。 */
    private val emit: (String, ApmSeverity, Map<String, Any?>) -> Unit
) {

    /**
     * 执行一次采集。
     * 读取历史退出记录，跳过已处理时间戳之前的记录，逐条上报后
     * 持久化最新处理位置。方法自身吞掉所有异常（尽力而为语义由调用方兜底）。
     */
    fun collectOnce() {
        val lastProcessed = timestampStore.lastProcessedMs()
        val records = source.latestExitRecords(MAX_EXIT_RECORDS)
        var newestProcessed = lastProcessed

        // 按时间升序处理，保证 newestProcessed 单调推进
        for (record in records.sortedBy(ExitRecord::timestampMs)) {
            // 去重：跳过上次已处理的记录
            if (record.timestampMs <= lastProcessed) {
                continue
            }
            emit(EVENT_APP_EXIT, severityFor(record.reasonCode), buildFields(record))
            newestProcessed = maxOf(newestProcessed, record.timestampMs)
        }

        // 有新记录时才写存储
        if (newestProcessed > lastProcessed) {
            timestampStore.saveLastProcessedMs(newestProcessed)
        }
    }

    /**
     * 构建单条退出记录的上报字段。
     *
     * @param record 退出记录
     * @return 事件字段
     */
    private fun buildFields(record: ExitRecord): Map<String, Any?> {
        val fields = mutableMapOf<String, Any?>(
            FIELD_EXIT_TIMESTAMP to record.timestampMs,
            FIELD_REASON_CODE to record.reasonCode,
            FIELD_REASON_NAME to reasonName(record.reasonCode),
            FIELD_IMPORTANCE to record.importance
        )
        // 系统描述非空时附带
        record.description?.takeIf(String::isNotBlank)?.let {
            fields[FIELD_DESCRIPTION] = it
        }
        // ANR 退出附带系统 trace 摘要（截断到 maxTraceBytes）
        if (record.reasonCode == REASON_ANR) {
            readTrace(record)?.let { fields[FIELD_TRACE] = it }
        }
        return fields
    }

    /**
     * 读取并截断 ANR trace。
     *
     * @param record 退出记录
     * @return trace 文本，读取失败或不存在返回 null
     */
    private fun readTrace(record: ExitRecord): String? {
        val supplier = record.traceSupplier ?: return null
        return runCatching {
            supplier()?.use { stream ->
                // 只读取前 maxTraceBytes 字节，避免超大 trace 撑爆事件
                val buffer = ByteArray(maxTraceBytes)
                val read = stream.read(buffer)
                if (read > 0) String(buffer, 0, read, Charsets.UTF_8) else null
            }
        }.getOrNull()
    }

    /**
     * 退出原因码到语义名称的映射。
     * 常量值与 android.app.ApplicationExitInfo.REASON_* 对齐。
     *
     * @param reasonCode 退出原因码
     * @return 语义名称
     */
    private fun reasonName(reasonCode: Int): String = when (reasonCode) {
        REASON_EXIT_SELF -> "EXIT_SELF"
        REASON_SIGNALED -> "SIGNALED"
        REASON_LOW_MEMORY -> "LOW_MEMORY"
        REASON_CRASH -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        REASON_USER_REQUESTED -> "USER_REQUESTED"
        REASON_USER_STOPPED -> "USER_STOPPED"
        REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN"
    }

    /**
     * 按退出原因确定事件严重级别。
     *
     * @param reasonCode 退出原因码
     * @return 严重级别
     */
    private fun severityFor(reasonCode: Int): ApmSeverity = when (reasonCode) {
        // 崩溃/ANR：错误级
        REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR -> ApmSeverity.ERROR
        // 内存/资源被杀：警告级
        REASON_LOW_MEMORY, REASON_EXCESSIVE_RESOURCE_USAGE, REASON_SIGNALED -> ApmSeverity.WARN
        // 正常/用户主动退出：信息级
        else -> ApmSeverity.INFO
    }

    companion object {
        /** 退出事件名。 */
        internal const val EVENT_APP_EXIT = "app_exit"

        /** 单次读取的最大退出记录数。 */
        private const val MAX_EXIT_RECORDS = 16

        /** ANR trace 默认最大附带字节数：64 KB。 */
        internal const val DEFAULT_MAX_TRACE_BYTES = 64 * 1024

        // --- 字段名 ---
        /** 字段：退出时间戳。 */
        private const val FIELD_EXIT_TIMESTAMP = "exitTimestamp"
        /** 字段：退出原因码。 */
        private const val FIELD_REASON_CODE = "reasonCode"
        /** 字段：退出原因名称。 */
        private const val FIELD_REASON_NAME = "reasonName"
        /** 字段：进程重要性。 */
        private const val FIELD_IMPORTANCE = "importance"
        /** 字段：系统描述。 */
        private const val FIELD_DESCRIPTION = "description"
        /** 字段：ANR trace 摘要。 */
        private const val FIELD_TRACE = "trace"

        // --- ApplicationExitInfo.REASON_* 对齐常量 ---
        /** 主动 exit()。 */
        private const val REASON_EXIT_SELF = 1
        /** 被信号杀死。 */
        private const val REASON_SIGNALED = 2
        /** 低内存被杀（LMK）。 */
        private const val REASON_LOW_MEMORY = 3
        /** Java 崩溃。 */
        private const val REASON_CRASH = 4
        /** Native 崩溃。 */
        private const val REASON_CRASH_NATIVE = 5
        /** ANR。 */
        internal const val REASON_ANR = 6
        /** 初始化失败。 */
        private const val REASON_INITIALIZATION_FAILURE = 7
        /** 权限变更。 */
        private const val REASON_PERMISSION_CHANGE = 8
        /** 资源滥用被杀。 */
        private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        /** 用户请求（如强制停止）。 */
        private const val REASON_USER_REQUESTED = 10
        /** 用户停止（多用户场景）。 */
        private const val REASON_USER_STOPPED = 11
        /** 依赖进程死亡。 */
        private const val REASON_DEPENDENCY_DIED = 12
        /** 其他系统原因。 */
        private const val REASON_OTHER = 13
        /** 进程冻结（API 31+）。 */
        private const val REASON_FREEZER = 14
    }
}
