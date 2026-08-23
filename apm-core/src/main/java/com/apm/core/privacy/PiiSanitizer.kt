package com.apm.core.privacy

import com.apm.core.ApmLogger
import com.apm.model.ApmEvent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Replacement used when a field name itself identifies credential or direct-contact data. */
private const val REDACTED_FIELD_VALUE = "***"

/**
 * Upper bound for the sensitive-name decision cache. Field names repeat on every event, so the
 * bounded memo approaches a 100% hit rate for real workloads while protecting hosts that emit
 * unbounded dynamic names from unbounded memory growth.
 */
private const val SENSITIVE_NAME_CACHE_CAPACITY = 256

/** Exact normalized names that are sensitive but unsafe to match as arbitrary fragments. */
private val SENSITIVE_FIELD_NAMES = setOf(
    "auth",
    "authheader",
    "authentication"
)

/** High-confidence fragments that remain sensitive even when surrounded by descriptive text. */
private val SENSITIVE_FIELD_FRAGMENTS = setOf(
    "password",
    "passwd",
    "authorization",
    "accesstoken",
    "refreshtoken",
    "authtoken",
    "apikey",
    "credential",
    "setcookie"
)

/** High-confidence suffixes used for common names such as user_email and auth_token. */
private val SENSITIVE_FIELD_SUFFIXES = setOf(
    "email",
    "phone",
    "mobile",
    "idcard",
    "nationalid",
    "sessionid",
    "token",
    "secret",
    "cookie",
    "pwd"
)

/**
 * PII（个人身份信息）脱敏器。
 *
 * 在事件持久化/上报前按字段名和文本模式降低常见 PII/凭据泄露风险。
 * 默认规则只是纵深防御，不替代接入方的数据盘点、最小化采集和合规评审。
 *
 * 脱敏流程：
 * 1. 按高置信敏感字段名直接遮蔽值，包括非字符串值
 * 2. 遍历其余文本字段（fields、globalContext、extras、scene）
 * 3. 对每个字符串值按序执行所有 [SanitizationRule]
 * 4. 返回脱敏后的事件副本
 *
 * 使用方式：
 * ```kotlin
 * val sanitizer = PiiSanitizer(
 *     rules = DefaultSanitizationRules.all(),
 *     logger = logger
 * )
 * val sanitizedEvent = sanitizer.sanitize(event)
 * ```
 *
 * 线程安全：构造时复制 [SanitizationRule] 列表，sanitize 方法无副作用。
 *
 * @param rules 按顺序应用的文本脱敏规则
 * @param logger 保留给兼容接入和未来安全诊断使用；当前不会记录事件内容
 */
class PiiSanitizer(
    rules: List<SanitizationRule> = DefaultSanitizationRules.all(),
    @Suppress("UNUSED_PARAMETER") logger: ApmLogger? = null
) {
    /** Immutable ordered sanitizer rule snapshot. */
    private val rules = rules.toList()

    /**
     * Bounded memo of the pure `key -> isSensitive` decision. Normalization allocates two
     * intermediate strings per key, and the same field names arrive on every event, so caching
     * removes that per-key cost entirely on the dispatcher worker hot path.
     */
    private val sensitiveNameDecisions = ConcurrentHashMap<String, Boolean>()

    /**
     * 对事件执行 PII 脱敏。
     *
     * 遍历事件的 fields、globalContext、extras、scene 等文本字段，
     * 对每个字符串值应用所有脱敏规则。
     * 返回脱敏后的事件副本，原始事件不会被修改。
     *
     * 未被任何规则修改的 map 会与输入共享同一引用：输入 map 必须遵循 SDK 的
     * 异步边界冻结约定（emit 时已快照，sanitize 后不得再被调用方修改）。
     *
     * @param event 原始事件
     * @return 脱敏后的事件副本
     */
    fun sanitize(event: ApmEvent): ApmEvent {
        // Field-name protection also covers numeric identifiers and credentials that regexes
        // cannot recognize from value shape alone.
        val sanitizedFields = sanitizeMap(event.fields) { key, value -> sanitizeFieldValue(key, value) }

        // 对 globalContext 执行脱敏
        val sanitizedContext = sanitizeMap(event.globalContext) { key, value -> sanitizeTextValue(key, value) }

        // 对 extras 执行脱敏
        val sanitizedExtras = sanitizeMap(event.extras) { key, value -> sanitizeTextValue(key, value) }

        // 对 scene 执行脱敏
        val sanitizedScene = event.scene?.let { applyRules(it) }

        return event.copy(
            fields = sanitizedFields,
            globalContext = sanitizedContext,
            extras = sanitizedExtras,
            scene = sanitizedScene
        )
    }

    /**
     * Copy-on-write map sanitize: when no entry changes (the common case for numeric METRIC
     * fields) the original map reference is returned without any allocation; when at least one
     * entry changes, a replacement map is materialized preserving the original iteration order.
     */
    private inline fun <V> sanitizeMap(
        source: Map<String, V>,
        transform: (String, V) -> V,
    ): Map<String, V> {
        // Fast path: prove no entry changes before allocating any replacement map.
        var entryIndex = 0
        for ((key, value) in source) {
            if (transform(key, value) !== value) break
            entryIndex++
        }
        if (entryIndex >= source.size) return source

        // Slow path: rebuild in original order; entries proven unchanged need no recompute
        // because the transform is side-effect free.
        val result = LinkedHashMap<String, V>(source.size)
        var position = 0
        for ((key, value) in source) {
            result[key] = if (position < entryIndex) value else transform(key, value)
            position++
        }
        return result
    }

    /**
     * 对单个字符串按序应用所有脱敏规则。
     */
    private fun applyRules(input: String): String {
        var result = input
        for (rule in rules) {
            // Rules intentionally compose so custom policies see the output of built-in rules.
            result = rule.sanitize(result)
        }
        return result
    }

    /** Redacts a sensitive field by name, otherwise preserves non-text metric types. */
    private fun sanitizeFieldValue(key: String, value: Any?): Any? {
        if (isSensitiveFieldName(key)) {
            return REDACTED_FIELD_VALUE
        }
        return if (value is String) applyRules(value) else value
    }

    /** Redacts a sensitive string-map entry by name, otherwise applies textual rules. */
    private fun sanitizeTextValue(key: String, value: String): String {
        return if (isSensitiveFieldName(key)) REDACTED_FIELD_VALUE else applyRules(value)
    }

    /** Returns true for normalized field names that strongly imply direct PII or credentials. */
    private fun isSensitiveFieldName(key: String): Boolean {
        val cached = sensitiveNameDecisions[key]
        if (cached != null) return cached

        val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        val decision = normalized in SENSITIVE_FIELD_NAMES ||
            SENSITIVE_FIELD_FRAGMENTS.any(normalized::contains) ||
            SENSITIVE_FIELD_SUFFIXES.any(normalized::endsWith)

        // Cache only while below the capacity bound; the decision stays correct either way.
        if (sensitiveNameDecisions.size < SENSITIVE_NAME_CACHE_CAPACITY) {
            sensitiveNameDecisions[key] = decision
        }
        return decision
    }
}
