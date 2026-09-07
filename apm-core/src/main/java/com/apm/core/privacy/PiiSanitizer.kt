package com.apm.core.privacy

import com.apm.core.ApmLogger
import com.apm.model.ApmEvent
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Replacement used when a field name itself identifies credential or direct-contact data. */
private const val REDACTED_FIELD_VALUE = "***"

/** Maximum copied text from one mutable host field before asynchronous processing. */
private const val MAX_FIELD_TEXT_CHARS = 64 * 1024

/** Explicit replacement for unsupported host objects; never invokes arbitrary host toString code. */
private const val UNSUPPORTED_FIELD_VALUE = "[unsupported]"

/**
 * Freezes the supported scalar contract at admission. Mutable text is copied within a fixed bound;
 * other host objects are replaced, without traversing object graphs or calling arbitrary methods.
 * Exact big-number classes retain typed wire semantics without retaining mutable subclasses.
 */
internal fun freezeEventField(value: Any?): Any? = when (value) {
    null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is Char -> value
    is String -> value
    is StringBuilder -> value.substring(0, minOf(value.length, MAX_FIELD_TEXT_CHARS))
    is StringBuffer -> synchronized(value) {
        value.substring(0, minOf(value.length, MAX_FIELD_TEXT_CHARS))
    }
    is BigInteger -> if (value.javaClass == BigInteger::class.java) value else UNSUPPORTED_FIELD_VALUE
    is BigDecimal -> if (value.javaClass == BigDecimal::class.java) value else UNSUPPORTED_FIELD_VALUE
    else -> UNSUPPORTED_FIELD_VALUE
}

/**
 * Upper bound for the sensitive-name decision cache. Field names repeat on every event, so the
 * bounded memo approaches a 100% hit rate for real workloads while protecting hosts that emit
 * unbounded dynamic names from unbounded memory growth.
 */
private const val SENSITIVE_NAME_CACHE_CAPACITY = 256

/** Avoids retaining attacker-controlled or accidentally unbounded field names in the memo. */
private const val MAX_CACHED_SENSITIVE_NAME_CHARS = 128

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
 * 构造时复制 [SanitizationRule] 列表；脱敏器本身不修改输入事件。自定义规则若包含状态，
 * 其线程安全由规则实现负责。
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
     * @param event 原始事件
     * @return 脱敏后的事件副本；返回的 map 与调用方输入隔离
     */
    fun sanitize(event: ApmEvent): ApmEvent = sanitizeEvent(event, shareUnchangedMaps = false)

    /**
     * Dispatcher-only fast path for an event whose maps were already frozen at queue admission.
     * Clean maps may be shared because no host-owned reference can mutate them afterward.
     */
    internal fun sanitizeFrozen(event: ApmEvent): ApmEvent =
        sanitizeEvent(event, shareUnchangedMaps = true)

    /** Applies all sanitization stages with an explicit clean-map ownership policy. */
    private fun sanitizeEvent(event: ApmEvent, shareUnchangedMaps: Boolean): ApmEvent {
        // Field-name protection also covers numeric identifiers and credentials that regexes
        // cannot recognize from value shape alone.
        val sanitizedFields = sanitizeMap(event.fields, shareUnchangedMaps) { key, value ->
            sanitizeFieldValue(key, value)
        }

        // 对 globalContext 执行脱敏
        val sanitizedContext = sanitizeMap(event.globalContext, shareUnchangedMaps) { key, value ->
            sanitizeTextValue(key, value)
        }

        // 对 extras 执行脱敏
        val sanitizedExtras = sanitizeMap(event.extras, shareUnchangedMaps) { key, value ->
            sanitizeTextValue(key, value)
        }

        // 对 scene 执行脱敏
        val sanitizedScene = event.scene?.let { applyRules(it) }

        val sanitized = event.copy(
            fields = sanitizedFields,
            globalContext = sanitizedContext,
            extras = sanitizedExtras,
            scene = sanitizedScene
        )
        return event.occurrence?.let(sanitized::withOccurrenceContext) ?: sanitized
    }

    /**
     * Copy-on-write map sanitize. The first changed value is retained from the probe so custom
     * rules are invoked exactly once per entry. Public calls copy clean non-empty maps; the
     * dispatcher fast path may share maps whose ownership was already frozen.
     */
    private inline fun <V> sanitizeMap(
        source: Map<String, V>,
        shareUnchanged: Boolean,
        transform: (String, V) -> V,
    ): Map<String, V> {
        if (source.isEmpty()) {
            return if (shareUnchanged) source else emptyMap()
        }

        // Fast path: prove no entry changes before allocating any replacement map.
        var entryIndex = 0
        var firstChangedValue: Any? = null
        for ((key, value) in source) {
            val transformed = transform(key, value)
            if (transformed !== value) {
                firstChangedValue = transformed
                break
            }
            entryIndex++
        }
        if (entryIndex >= source.size) {
            return if (shareUnchanged) source else LinkedHashMap(source)
        }

        // Slow path: rebuild in original order without invoking the first changing rule twice.
        val result = LinkedHashMap<String, V>(source.size)
        var position = 0
        for ((key, value) in source) {
            @Suppress("UNCHECKED_CAST")
            result[key] = when {
                position < entryIndex -> value
                position == entryIndex -> firstChangedValue as V
                else -> transform(key, value)
            }
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
        val frozen = freezeEventField(value)
        return if (frozen is String) applyRules(frozen) else frozen
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
        if (key.length <= MAX_CACHED_SENSITIVE_NAME_CHARS &&
            sensitiveNameDecisions.size < SENSITIVE_NAME_CACHE_CAPACITY
        ) {
            sensitiveNameDecisions[key] = decision
        }
        return decision
    }
}
