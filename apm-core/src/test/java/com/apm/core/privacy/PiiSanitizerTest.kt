package com.apm.core.privacy

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [PiiSanitizer] 单元测试。
 *
 * 验证内置脱敏规则：
 * 1. 手机号脱敏
 * 2. 邮箱脱敏
 * 3. 身份证号脱敏
 * 4. URL token 参数脱敏
 * 5. URL password 参数脱敏
 * 6. 多规则组合脱敏
 * 7. 无 PII 事件不受影响
 */
class PiiSanitizerTest {

    private val sanitizer = PiiSanitizer(DefaultSanitizationRules.all())

    // --- 手机号脱敏 ---

    @Test
    fun `sanitize phone number in fields`() {
        val event = createEvent(fields = mapOf("message" to "user 13812345678 called"))
        val result = sanitizer.sanitize(event)

        assertEquals("user 138****5678 called", result.fields["message"])
    }

    @Test
    fun `sanitize multiple phone numbers`() {
        val event = createEvent(fields = mapOf("text" to "13812345678 and 15098765432"))
        val result = sanitizer.sanitize(event)

        assertEquals("138****5678 and 150****5432", result.fields["text"])
    }

    // --- 邮箱脱敏 ---

    @Test
    fun `sanitize email in fields`() {
        val event = createEvent(fields = mapOf("user_info" to "contact: john.doe@example.com"))
        val result = sanitizer.sanitize(event)

        assertEquals("contact: j***@example.com", result.fields["user_info"])
    }

    // --- 身份证号脱敏 ---

    @Test
    fun `sanitize ID card number`() {
        val event = createEvent(fields = mapOf("id_number" to "330102199001011234"))
        val result = sanitizer.sanitize(event)

        assertEquals("3301**********1234", result.fields["id_number"])
    }

    // --- URL 参数脱敏 ---

    @Test
    fun `sanitize token in URL`() {
        val event = createEvent(fields = mapOf("url" to "https://api.example.com/data?token=abc123def&id=5"))
        val result = sanitizer.sanitize(event)

        val sanitizedUrl = result.fields["url"] as String
        assertFalse("Token value should be masked", sanitizedUrl.contains("abc123def"))
        assertEquals("https://api.example.com/data?token=***&id=5", sanitizedUrl)
    }

    @Test
    fun `sanitize password in URL`() {
        val event = createEvent(fields = mapOf("url" to "https://api.example.com/login?password=secret123"))
        val result = sanitizer.sanitize(event)

        assertEquals("https://api.example.com/login?password=***", result.fields["url"])
    }

    // --- 多规则组合 ---

    @Test
    fun `sanitize multiple PII types in same field`() {
        val event = createEvent(
            fields = mapOf("info" to "user 13812345678 email: test@mail.com")
        )
        val result = sanitizer.sanitize(event)

        val sanitized = result.fields["info"] as String
        assertFalse("Phone should be masked", sanitized.contains("13812345678"))
        assertFalse("Email should be masked", sanitized.contains("test@mail.com"))
    }

    // --- extras 和 globalContext 脱敏 ---

    @Test
    fun `sanitize extras map`() {
        val event = createEvent(
            extras = mapOf("debug_url" to "https://app.com/api?token=sensitive_value")
        )
        val result = sanitizer.sanitize(event)

        assertEquals("https://app.com/api?token=***", result.extras["debug_url"])
    }

    @Test
    fun `sanitize globalContext map`() {
        val event = createEvent(
            globalContext = mapOf("user_contact" to "13812345678")
        )
        val result = sanitizer.sanitize(event)

        assertEquals("138****5678", result.globalContext["user_contact"])
    }

    // --- 无 PII 事件不受影响 ---

    @Test
    fun `event without PII is not modified`() {
        val event = createEvent(
            fields = mapOf("fps" to 60, "module" to "render"),
            extras = mapOf("version" to "1.0.0")
        )
        val result = sanitizer.sanitize(event)

        // 数值字段不应被修改
        assertEquals(60, result.fields["fps"])
        assertEquals("render", result.fields["module"])
        assertEquals("1.0.0", result.extras["version"])
    }

    @Test
    fun `original event is not modified`() {
        val event = createEvent(fields = mapOf("phone" to "13812345678"))
        sanitizer.sanitize(event)

        // 原始事件不应被修改
        assertEquals("13812345678", event.fields["phone"])
    }

    // --- 辅助方法 ---

    /** 创建测试用 APM 事件。 */
    private fun createEvent(
        fields: Map<String, Any?> = emptyMap(),
        extras: Map<String, String> = emptyMap(),
        globalContext: Map<String, String> = emptyMap()
    ): ApmEvent {
        return ApmEvent(
            module = "test",
            name = "sanitization_test",
            kind = ApmEventKind.METRIC,
            severity = ApmSeverity.INFO,
            fields = fields,
            extras = extras,
            globalContext = globalContext
        )
    }
}
