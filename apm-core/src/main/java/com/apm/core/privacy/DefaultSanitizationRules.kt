package com.apm.core.privacy

/**
 * 内置脱敏规则集。
 *
 * 覆盖常见的 PII 类型：手机号、邮箱、身份证号、URL 中的敏感参数（token、password）。
 * 所有规则使用正则表达式匹配，匹配到的内容被部分掩码处理（保留前后几位）。
 *
 * 使用方式：
 * ```kotlin
 * val rules = DefaultSanitizationRules.all()
 * val sanitizer = PiiSanitizer(rules)
 * ```
 */
object DefaultSanitizationRules {

    /**
     * 返回所有内置脱敏规则。
     */
    fun all(): List<SanitizationRule> = listOf(
        phoneRule(),
        emailRule(),
        idCardRule(),
        urlTokenRule(),
        urlPasswordRule()
    )

    /**
     * 手机号脱敏规则。
     *
     * 匹配中国大陆手机号（1 开头，11 位）。
     * 脱敏策略：保留前 3 位和后 4 位，中间用 **** 替代。
     *
     * 示例：13812345678 → 138****5678
     */
    fun phoneRule(): SanitizationRule = SanitizationRule { input ->
        PHONE_PATTERN.toRegex().replace(input) { match ->
            val phone = match.value
            "${phone.substring(0, 3)}****${phone.substring(7)}"
        }
    }

    /**
     * 邮箱脱敏规则。
     *
     * 匹配标准邮箱格式。
     * 脱敏策略：用户名只保留首字符 + ***，域名保留。
     *
     * 示例：user@example.com → u***@example.com
     */
    fun emailRule(): SanitizationRule = SanitizationRule { input ->
        EMAIL_PATTERN.toRegex().replace(input) { match ->
            val email = match.value
            val atIndex = email.indexOf('@')
            if (atIndex > 1) {
                "${email.first()}***${email.substring(atIndex)}"
            } else {
                "***${email.substring(atIndex)}"
            }
        }
    }

    /**
     * 身份证号脱敏规则。
     *
     * 匹配 18 位身份证号（1-9 开头）。
     * 脱敏策略：保留前 4 位和后 4 位，中间用 ******** 替代。
     *
     * 示例：330102199001011234 → 3301************1234
     */
    fun idCardRule(): SanitizationRule = SanitizationRule { input ->
        ID_CARD_PATTERN.toRegex().replace(input) { match ->
            val id = match.value
            "${id.substring(0, 4)}**********${id.substring(14)}"
        }
    }

    /**
     * URL token 参数脱敏规则。
     *
     * 匹配 URL query 中的 token/session/key/api_key 等敏感参数。
     * 脱敏策略：将值替换为 ***。
     *
     * 示例：?token=abc123&user=test → ?token=***&user=test
     */
    fun urlTokenRule(): SanitizationRule = SanitizationRule { input ->
        URL_TOKEN_PATTERN.toRegex().replace(input) { match ->
            // 保留参数名，替换值
            val group = match.value
            val eqIndex = group.indexOf('=')
            if (eqIndex >= 0) {
                "${group.substring(0, eqIndex + 1)}***"
            } else {
                group
            }
        }
    }

    /**
     * URL password 参数脱敏规则。
     *
     * 匹配 URL query 中的 password/passwd/pwd 参数。
     * 脱敏策略：将值替换为 ***。
     *
     * 示例：?password=secret123 → ?password=***
     */
    fun urlPasswordRule(): SanitizationRule = SanitizationRule { input ->
        URL_PASSWORD_PATTERN.toRegex().replace(input) { match ->
            val group = match.value
            val eqIndex = group.indexOf('=')
            if (eqIndex >= 0) {
                "${group.substring(0, eqIndex + 1)}***"
            } else {
                group
            }
        }
    }

    // --- 正则模式 ---

    /** 中国大陆手机号：1 开头，第二位 3-9，共 11 位。 */
    private const val PHONE_PATTERN = """(?<!\d)1[3-9]\d{9}(?!\d)"""

    /** 邮箱：标准格式 user@domain.tld。 */
    private const val EMAIL_PATTERN = """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""

    /** 18 位身份证号：1-9 开头，6 位地区码 + 8 位生日 + 3 位序号 + 1 位校验。 */
    private const val ID_CARD_PATTERN = """(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)"""

    /** URL 敏感参数名模式：token、session、key、secret、api_key、access_token 等。 */
    private const val URL_TOKEN_PATTERN = """(?i)(?:token|session[_-]?id?|api[_-]?key|secret|access[_-]?token|auth|credential)=[^&\s#]+"""

    /** URL 密码参数名模式。 */
    private const val URL_PASSWORD_PATTERN = """(?i)(?:password|passwd|pwd)=[^&\s#]+"""
}
