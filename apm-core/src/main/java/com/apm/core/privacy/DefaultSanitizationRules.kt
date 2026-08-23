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

    // --- 长度快拒阈值 ---
    // 每条规则的最短可能命中长度：短于该长度的输入在数学上不可能命中模式，
    // 直接返回原值可跳过正则执行。绝大多数 metric 短值（"true"/"main"/数字文本）命中此路径。

    /** 手机号模式最少 11 位数字。 */
    private const val MIN_PHONE_INPUT_LENGTH = 11

    /** 邮箱模式最短形式为 `a@b.co`（6 字符）。 */
    private const val MIN_EMAIL_INPUT_LENGTH = 6

    /** 身份证模式固定 18 字符。 */
    private const val MIN_ID_CARD_INPUT_LENGTH = 18

    /** URL token 模式最短形式为 `auth=x`（6 字符）。 */
    private const val MIN_URL_TOKEN_INPUT_LENGTH = 6

    /** URL 密码模式最短形式为 `pwd=x`（5 字符）。 */
    private const val MIN_URL_PASSWORD_INPUT_LENGTH = 5

    // --- 规则实现 ---

    /**
     * 手机号脱敏规则。
     *
     * 匹配中国大陆手机号（1 开头，11 位）。
     * 脱敏策略：保留前 3 位和后 4 位，中间用 **** 替代。
     *
     * 示例：13812345678 → 138****5678
     */
    fun phoneRule(): SanitizationRule = SanitizationRule { input ->
        // 长度快拒：不足 11 字符的输入不可能包含 11 位手机号。
        if (input.length < MIN_PHONE_INPUT_LENGTH) input else PHONE_REGEX.replace(input) { match ->
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
        // 长度快拒：短于最短合法邮箱形式 `a@b.co` 的输入无需进入正则。
        if (input.length < MIN_EMAIL_INPUT_LENGTH) input else EMAIL_REGEX.replace(input) { match ->
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
        // 长度快拒：身份证模式固定 18 字符。
        if (input.length < MIN_ID_CARD_INPUT_LENGTH) input else ID_CARD_REGEX.replace(input) { match ->
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
        // 长度快拒：最短命中形式是 `auth=x`（最短参数名 + 等号 + 至少 1 个值字符）。
        if (input.length < MIN_URL_TOKEN_INPUT_LENGTH) input else URL_TOKEN_REGEX.replace(input) { match ->
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
        // 长度快拒：最短命中形式是 `pwd=x`。
        if (input.length < MIN_URL_PASSWORD_INPUT_LENGTH) input else URL_PASSWORD_REGEX.replace(input) { match ->
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

    /** Precompiled immutable regexes avoid reparsing attacker-influenced text on every event. */
    private val PHONE_REGEX = Regex(PHONE_PATTERN)
    private val EMAIL_REGEX = Regex(EMAIL_PATTERN)
    private val ID_CARD_REGEX = Regex(ID_CARD_PATTERN)
    private val URL_TOKEN_REGEX = Regex(URL_TOKEN_PATTERN)
    private val URL_PASSWORD_REGEX = Regex(URL_PASSWORD_PATTERN)
}
