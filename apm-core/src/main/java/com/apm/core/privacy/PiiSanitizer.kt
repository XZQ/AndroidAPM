package com.apm.core.privacy

import com.apm.model.ApmEvent
import com.apm.core.ApmLogger

/**
 * PII（个人身份信息）脱敏器。
 *
 * 在事件上报前对文本字段执行脱敏，满足 GDPR/CCPA/《个人信息保护法》合规要求。
 *
 * 脱敏流程：
 * 1. 遍历事件的所有文本字段（fields、globalContext、extras、scene）
 * 2. 对每个字符串值按序执行所有 [SanitizationRule]
 * 3. 返回脱敏后的事件副本
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
 * 线程安全：[SanitizationRule] 列表在构造后不可变，sanitize 方法无副作用。
 */
class PiiSanitizer(
    /** 脱敏规则列表，按序执行。 */
    private val rules: List<SanitizationRule> = DefaultSanitizationRules.all(),
    /** 日志接口。 */
    private val logger: ApmLogger? = null
) {

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
        if (rules.isEmpty()) return event

        // 对 fields 中的字符串值执行脱敏
        val sanitizedFields = event.fields.mapValues { (_, value) ->
            when (value) {
                is String -> applyRules(value)
                else -> value
            }
        }

        // 对 globalContext 执行脱敏
        val sanitizedContext = event.globalContext.mapValues { (_, value) ->
            applyRules(value)
        }

        // 对 extras 执行脱敏
        val sanitizedExtras = event.extras.mapValues { (_, value) ->
            applyRules(value)
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
            result = rule.sanitize(result)
        }
        return result
    }
}
