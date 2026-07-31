package com.apm.core.privacy

import com.apm.core.ApmLogger
import com.apm.model.ApmEvent
import java.util.Locale

/** Replacement used when a field name itself identifies credential or direct-contact data. */
private const val REDACTED_FIELD_VALUE = "***"

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
     * 对事件执行 PII 脱敏。
     *
     * 遍历事件的 fields、globalContext、extras、scene 等文本字段，
     * 对每个字符串值应用所有脱敏规则。
     * 返回脱敏后的事件副本，原始事件不会被修改。
     *
     * @param event 原始事件
     * @return 脱敏后的事件副本
     */
    fun sanitize(event: ApmEvent): ApmEvent {
        // Field-name protection also covers numeric identifiers and credentials that regexes
        // cannot recognize from value shape alone.
        val sanitizedFields = event.fields.mapValues { (key, value) ->
            sanitizeFieldValue(key, value)
        }

        // 对 globalContext 执行脱敏
        val sanitizedContext = event.globalContext.mapValues { (key, value) ->
            sanitizeTextValue(key, value)
        }

        // 对 extras 执行脱敏
        val sanitizedExtras = event.extras.mapValues { (key, value) ->
            sanitizeTextValue(key, value)
        }

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
        val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return normalized in SENSITIVE_FIELD_NAMES ||
            SENSITIVE_FIELD_FRAGMENTS.any(normalized::contains) ||
            SENSITIVE_FIELD_SUFFIXES.any(normalized::endsWith)
    }
}
