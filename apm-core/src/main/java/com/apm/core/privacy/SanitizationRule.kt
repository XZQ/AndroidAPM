package com.apm.core.privacy

/**
 * 脱敏规则接口。
 *
 * 每条规则定义一个匹配模式（正则或字段名）和替换策略。
 * 规则由 [PiiSanitizer] 按序执行，匹配到的内容将被脱敏处理。
 */
fun interface SanitizationRule {
    /**
     * 对输入文本执行脱敏。
     *
     * @param input 原始文本
     * @return 脱敏后的文本，如果不需要脱敏则返回原始值
     */
    fun sanitize(input: String): String
}
