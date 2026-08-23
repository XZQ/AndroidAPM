package com.apm.core.privacy

import com.apm.model.ApmEvent
import com.apm.model.ApmEventKind
import com.apm.model.ApmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    /** Sensitive field names redact numeric PII and credential text before serialization. */
    @Test
    fun `sanitize values by sensitive field name`() {
        val event = createEvent(
            fields = mapOf(
                "contact_phone" to 13812345678L,
                "request_token_count" to 3
            ),
            extras = mapOf(
                "authorization" to "Bearer raw-secret",
                "token_count" to "2"
            )
        )

        val result = sanitizer.sanitize(event)

        assertEquals("***", result.fields["contact_phone"])
        assertEquals(3L, result.fields["request_token_count"])
        assertEquals("***", result.extras["authorization"])
        assertEquals("2", result.extras["token_count"])
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

    /** Separator and case variants of exact auth fields remain protected without matching author. */
    @Test
    fun `fixed seed auth field variants are redacted without author false positive`() {
        val random = Random(AUTH_FIELD_CORPUS_SEED)
        repeat(AUTH_FIELD_CORPUS_SAMPLES) { index ->
            val source = when (index % 3) {
                0 -> "auth"
                1 -> "authheader"
                else -> "authentication"
            }
            val variant = buildString {
                source.forEachIndexed { characterIndex, character ->
                    append(if (random.nextBoolean()) character.uppercaseChar() else character)
                    if (characterIndex < source.lastIndex && random.nextBoolean()) {
                        append(AUTH_FIELD_SEPARATORS[random.nextInt(AUTH_FIELD_SEPARATORS.size)])
                    }
                }
            }
            val result = sanitizer.sanitize(
                createEvent(fields = mapOf(variant to "Bearer raw-secret"))
            )
            assertEquals("***", result.fields[variant])
        }

        val author = sanitizer.sanitize(createEvent(fields = mapOf("author" to "Ada")))
        assertEquals("Ada", author.fields["author"])
    }

    /** A fixed mixed-PII corpus proves redaction and source-event immutability together. */
    @Test
    fun `fixed seed mixed pii corpus never retains raw values`() {
        val random = Random(MIXED_PII_CORPUS_SEED)
        repeat(MIXED_PII_CORPUS_SAMPLES) { index ->
            val token = "secret-${random.nextLong().toULong()}-$index"
            val raw = "phone=13812345678 email=user$index@example.com token=$token"
            val event = createEvent(fields = mapOf("message" to raw))

            val sanitized = sanitizer.sanitize(event).fields["message"] as String

            assertFalse(sanitized.contains("13812345678"))
            assertFalse(sanitized.contains("user$index@example.com"))
            assertFalse(sanitized.contains(token))
            assertEquals(raw, event.fields["message"])
        }
    }

    // --- 性能优化回归 ---

    /** Dispatcher frozen-map fast path：零命中事件不做整表复制。 */
    @Test
    fun `clean frozen event maps are shared not copied`() {
        val fields = mapOf("fps" to 60, "module" to "render")
        val extras = mapOf("version" to "1.0.0")
        val globalContext = mapOf("channel" to "test")
        val event = createEvent(fields = fields, extras = extras, globalContext = globalContext)

        val result = sanitizer.sanitizeFrozen(event)

        // dispatcher 入队时已经冻结所有权，未修改 map 可原样共享
        assertTrue(result.fields === fields)
        assertTrue(result.extras === extras)
        assertTrue(result.globalContext === globalContext)
    }

    /** Public API must not return host-owned mutable maps even when no rule changes a value. */
    @Test
    fun `public sanitize isolates clean mutable maps`() {
        val fields = linkedMapOf<String, Any?>("status" to "ok")
        val extras = linkedMapOf("version" to "1.0.0")
        val globalContext = linkedMapOf("channel" to "test")

        val result = sanitizer.sanitize(
            createEvent(fields = fields, extras = extras, globalContext = globalContext)
        )
        fields["password"] = "raw-secret"
        extras["authorization"] = "Bearer raw-secret"
        globalContext["user_email"] = "raw@example.com"

        assertFalse(result.fields === fields)
        assertFalse(result.extras === extras)
        assertFalse(result.globalContext === globalContext)
        assertFalse(result.fields.containsKey("password"))
        assertFalse(result.extras.containsKey("authorization"))
        assertFalse(result.globalContext.containsKey("user_email"))
    }

    /** Copy-on-write：有命中的 map 才物化新副本，且保持原始迭代顺序。 */
    @Test
    fun `dirty map is copied and preserves iteration order`() {
        val fields = LinkedHashMap<String, Any?>()
        fields["alpha"] = 1
        fields["message"] = "user 13812345678 called"
        fields["omega"] = 2

        val result = sanitizer.sanitize(createEvent(fields = fields))

        assertFalse(result.fields === fields)
        assertEquals(listOf("alpha", "message", "omega"), result.fields.keys.toList())
        assertEquals(1, result.fields["alpha"])
        assertEquals(2, result.fields["omega"])
    }

    /** Stateful custom rules still observe exactly one invocation for the first changed entry. */
    @Test
    fun `first changed entry is transformed exactly once`() {
        var invocationCount = 0
        val statefulSanitizer = PiiSanitizer(
            listOf(
                SanitizationRule { input ->
                    invocationCount += 1
                    "$input#$invocationCount"
                }
            )
        )

        val result = statefulSanitizer.sanitize(
            createEvent(fields = linkedMapOf("message" to "clean"))
        )

        assertEquals(1, invocationCount)
        assertEquals("clean#1", result.fields["message"])
    }

    /** 敏感键名判定缓存：重复键的判定结果与首次一致，混合敏感/非敏感键不串扰。 */
    @Test
    fun `repeated field name decisions stay consistent`() {
        repeat(3) {
            val dirty = sanitizer.sanitize(createEvent(fields = mapOf("session_id" to "abc")))
            val clean = sanitizer.sanitize(createEvent(fields = mapOf("author" to "Ada")))
            assertEquals("***", dirty.fields["session_id"])
            assertEquals("Ada", clean.fields["author"])
        }
    }

    /** Oversized host-controlled keys are evaluated but not retained by the bounded memo. */
    @Test
    fun `sensitive name cache rejects oversized keys`() {
        val shortKey = "author"
        val oversizedKey = "x".repeat(1_024)

        sanitizer.sanitize(
            createEvent(fields = linkedMapOf(shortKey to "Ada", oversizedKey to "safe"))
        )

        val cacheField = PiiSanitizer::class.java.getDeclaredField("sensitiveNameDecisions")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(sanitizer) as Map<String, Boolean>
        assertTrue(cache.containsKey(shortKey))
        assertFalse(cache.containsKey(oversizedKey))
    }

    /** 长度快拒边界：恰好达到最短命中长度的值仍被脱敏，差一字符的短值原样保留。 */
    @Test
    fun `length quick-reject boundary values behave identically`() {
        // 各规则最短可命中形式
        assertEquals("pwd=***", sanitizer.sanitize(createEvent(fields = mapOf("u" to "pwd=x"))).fields["u"])
        assertEquals("auth=***", sanitizer.sanitize(createEvent(fields = mapOf("u" to "auth=x"))).fields["u"])
        assertEquals("***@b.co", sanitizer.sanitize(createEvent(fields = mapOf("u" to "a@b.co"))).fields["u"])
        assertEquals(
            "138****5678",
            sanitizer.sanitize(createEvent(fields = mapOf("u" to "13812345678"))).fields["u"]
        )

        // 低于最短命中长度的短值原样返回
        for (short in listOf("true", "main", "1", "pwd=", "auth=")) {
            assertEquals(short, sanitizer.sanitize(createEvent(fields = mapOf("u" to short))).fields["u"])
        }
    }

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

    private companion object {
        /** Deterministic privacy regression corpus parameters. */
        private const val AUTH_FIELD_CORPUS_SEED = 0x50_49_49
        private const val AUTH_FIELD_CORPUS_SAMPLES = 256
        private const val MIXED_PII_CORPUS_SEED = 0x53_41_4E
        private const val MIXED_PII_CORPUS_SAMPLES = 256
        private val AUTH_FIELD_SEPARATORS = charArrayOf('_', '-', '.', ' ')
    }
}
