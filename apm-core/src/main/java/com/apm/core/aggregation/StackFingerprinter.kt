package com.apm.core.aggregation

import com.apm.model.ApmEvent

/**
 * 栈指纹去重器。
 *
 * 对 crash/ANR 类事件按栈指纹去重，相同调用栈只上报一次 + 计数。
 * 避免同一类崩溃被重复上报数千次。
 *
 * 栈指纹 = 栈哈希的前 N 行（默认取前 3 行，可配置）。
 * 在时间窗口内（默认 5 分钟）相同指纹只保留一条，其余合并计数。
 */
class StackFingerprinter(
    /** 栈指纹取前几行参与哈希。 */
    private val fingerprintLines: Int = DEFAULT_FINGERPRINT_LINES,
    /** 去重时间窗口（毫秒）。 */
    private val dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS
) {
    /**
     * 已见过的栈指纹 → (最后出现时间戳, 出现次数)。
     * 使用 LinkedHashMap 保持插入顺序，方便淘汰过期条目。
     */
    private val seenFingerprints = LinkedHashMap<String, FingerprintEntry>()

    /**
     * 检查事件是否为重复事件。
     *
     * @param event 要检查的事件
     * @return 去重结果：[DedupResult.New] 表示首次出现，[DedupResult.Duplicate] 表示重复
     */
    fun check(event: ApmEvent): DedupResult {
        val stackTrace = event.fields["stack_trace"]?.toString()
            ?: event.fields["stacktrace"]?.toString()

        // 没有栈信息的事件不做去重
        if (stackTrace.isNullOrBlank()) {
            return DedupResult.New
        }

        val fingerprint = computeFingerprint(stackTrace)
        val now = System.currentTimeMillis()

        // 清理过期的指纹条目
        evictExpired(now)

        val existing = seenFingerprints[fingerprint]
        if (existing != null && (now - existing.lastSeenMs) < dedupWindowMs) {
            // 在窗口内重复出现，增加计数
            existing.increment(now)
            return DedupResult.Duplicate(existing.count)
        }

        // 新指纹或窗口外重复，记录
        seenFingerprints[fingerprint] = FingerprintEntry(now)
        return DedupResult.New
    }

    /**
     * 计算栈指纹。
     * 取栈的前 [fingerprintLines] 行，拼接后做哈希。
     */
    private fun computeFingerprint(stackTrace: String): String {
        val lines = stackTrace.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("at ") }
            .take(fingerprintLines)
            .joinToString("|")

        return lines.hashCode().toString()
    }

    /** 淘汰过期的指纹条目。 */
    private fun evictExpired(now: Long) {
        val iterator = seenFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenMs > dedupWindowMs) {
                iterator.remove()
            } else {
                // LinkedHashMap 按插入序，后面的更新，所以遇到未过期的可以停止
                break
            }
        }
    }

    /** 指纹条目。 */
    private class FingerprintEntry(
        /** 最后出现时间戳。 */
        var lastSeenMs: Long,
        /** 出现次数。 */
        var count: Int = 1
    ) {
        /** 增加计数。 */
        fun increment(now: Long) {
            count++
            lastSeenMs = now
        }
    }

    /** 去重结果。 */
    sealed class DedupResult {
        /** 首次出现（或窗口外再次出现），应正常上报。 */
        object New : DedupResult()

        /** 重复事件。totalCount 为包含首次在内的总出现次数。 */
        data class Duplicate(val totalCount: Int) : DedupResult()
    }

    companion object {
        /** 默认栈指纹行数。 */
        private const val DEFAULT_FINGERPRINT_LINES = 3

        /** 默认去重窗口：5 分钟。 */
        private const val DEFAULT_DEDUP_WINDOW_MS = 300_000L
    }
}
