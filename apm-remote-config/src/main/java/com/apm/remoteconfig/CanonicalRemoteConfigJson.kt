package com.apm.remoteconfig

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses the server envelope, validates its signed structure, and creates canonical JSON bytes. */
internal object CanonicalRemoteConfigJson {

    /**
     * Parses one bounded UTF-8 response into a document ready for signature verification.
     *
     * @param rawJson original response body
     * @return structurally validated document with canonical unsigned bytes
     */
    fun parse(rawJson: String): RemoteConfigDocument {
        val root = JsonParser.parseString(rawJson).asJsonObject
        require(root.keySet().containsAll(REQUIRED_ENVELOPE_KEYS)) {
            "Incomplete remote config envelope"
        }
        val revision = root.requiredLong(KEY_REVISION)
        val issuedAtMs = parseUtcTimestamp(root.requiredString(KEY_ISSUED_AT))
        val expiresAtMs = parseUtcTimestamp(root.requiredString(KEY_EXPIRES_AT))
        val rolloutBasisPoints = root.requiredInt(KEY_ROLLOUT_BASIS_POINTS)
        val payload = root.getAsJsonObject(KEY_PAYLOAD)
            ?: throw IllegalArgumentException("Missing payload")
        val keyId = root.requiredString(KEY_KEY_ID)
        val signatureBase64 = root.requiredString(KEY_SIGNATURE)
        require(revision > 0L) { "Revision must be positive" }
        require(expiresAtMs > issuedAtMs) { "Remote config expiry must follow issue time" }
        require(rolloutBasisPoints in MIN_BASIS_POINTS..MAX_BASIS_POINTS) {
            "Invalid rollout basis points"
        }
        require(keyId.isNotBlank()) { "Missing key id" }
        require(signatureBase64.isNotBlank()) { "Missing signature" }

        // Future optional signed fields remain verifiable and ignorable by older clients.
        val unsigned = root.deepCopy().apply { remove(KEY_SIGNATURE) }
        return RemoteConfigDocument(
            revision = revision,
            issuedAtMs = issuedAtMs,
            expiresAtMs = expiresAtMs,
            rolloutBasisPoints = rolloutBasisPoints,
            payload = payload.deepCopy(),
            keyId = keyId,
            signatureBase64 = signatureBase64,
            canonicalBytes = canonicalize(unsigned).toByteArray(Charsets.UTF_8),
            rawJson = rawJson
        )
    }

    /** Returns deterministic JSON matching server sort-keys/no-whitespace/UTF-8 behavior. */
    private fun canonicalize(element: JsonElement): String {
        return when {
            element.isJsonNull -> "null"
            element.isJsonArray -> element.asJsonArray.joinToString(",", "[", "]") { child ->
                canonicalize(child)
            }
            element.isJsonObject -> element.asJsonObject.entrySet()
                .sortedBy { entry -> entry.key }
                .joinToString(",", "{", "}") { entry ->
                    "${quote(entry.key)}:${canonicalize(entry.value)}"
                }
            element.asJsonPrimitive.isBoolean -> element.asBoolean.toString()
            element.asJsonPrimitive.isNumber -> canonicalNumber(element.asJsonPrimitive.asString)
            else -> quote(element.asString)
        }
    }

    /** Rejects non-finite JSON numbers while preserving the server-authored lexical value. */
    private fun canonicalNumber(value: String): String {
        require(value != "NaN" && value != "Infinity" && value != "-Infinity") {
            "Non-finite JSON number"
        }
        return value
    }

    /** Quotes one JSON string without ASCII-rewriting Unicode or enabling HTML escaping. */
    private fun quote(value: String): String = buildString(value.length + QUOTE_CAPACITY_PADDING) {
        append('"')
        for (character in value) {
            // Python json.dumps(ensure_ascii=False) uses these short escapes and lowercase hex.
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < CONTROL_CHARACTER_LIMIT) {
                    append("\\u")
                    append(character.code.toString(HEX_RADIX).padStart(UNICODE_HEX_WIDTH, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /** Parses the server's UTC ISO-8601 format without requiring API 26 java.time. */
    private fun parseUtcTimestamp(value: String): Long {
        val match = UTC_TIMESTAMP_PATTERN.matchEntire(value)
            ?: throw IllegalArgumentException("Invalid UTC timestamp")
        val base = match.groupValues[GROUP_BASE_TIMESTAMP]
        val fraction = match.groupValues[GROUP_FRACTION]
        val formatter = SimpleDateFormat(BASE_TIMESTAMP_PATTERN, Locale.US).apply {
            isLenient = false
            timeZone = UTC_TIME_ZONE
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(base, position)
            ?: throw IllegalArgumentException("Invalid UTC timestamp")
        require(position.index == base.length) { "Invalid UTC timestamp" }
        val millis = fraction.take(MILLISECOND_DIGITS).padEnd(MILLISECOND_DIGITS, '0')
            .ifEmpty { "0" }
            .toLong()
        return parsed.time + millis
    }

    /** Reads a required JSON string. */
    private fun JsonObject.requiredString(name: String): String {
        val value = get(name) ?: throw IllegalArgumentException("Missing $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid $name" }
        return value.asString
    }

    /** Reads a required integral JSON long. */
    private fun JsonObject.requiredLong(name: String): Long {
        val value = get(name) ?: throw IllegalArgumentException("Missing $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid $name" }
        return value.asString.toLong()
    }

    /** Reads a required integral JSON int. */
    private fun JsonObject.requiredInt(name: String): Int {
        val longValue = requiredLong(name)
        val parsed = longValue.toInt()
        require(parsed.toLong() == longValue) { "Invalid $name" }
        return parsed
    }

    /** Expected signed response fields including the detached signature. */
    private val REQUIRED_ENVELOPE_KEYS = setOf(
        "revision",
        "issuedAt",
        "expiresAt",
        "rolloutBasisPoints",
        "payload",
        "keyId",
        "signature"
    )

    /** UTC timestamp parser accepting optional one-to-six digit fractional seconds. */
    private val UTC_TIMESTAMP_PATTERN =
        Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,6}))?Z${'$'}")

    /** Shared immutable UTC time zone. */
    private val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")

    /** Envelope key constants. */
    private const val KEY_REVISION = "revision"
    private const val KEY_ISSUED_AT = "issuedAt"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_ROLLOUT_BASIS_POINTS = "rolloutBasisPoints"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_KEY_ID = "keyId"
    private const val KEY_SIGNATURE = "signature"

    /** Valid rollout range. */
    private const val MIN_BASIS_POINTS = 0
    private const val MAX_BASIS_POINTS = 10_000

    /** Timestamp parser group indexes. */
    private const val GROUP_BASE_TIMESTAMP = 1
    private const val GROUP_FRACTION = 2

    /** Timestamp/JSON formatting constants. */
    private const val BASE_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private const val MILLISECOND_DIGITS = 3
    private const val QUOTE_CAPACITY_PADDING = 2
    private const val CONTROL_CHARACTER_LIMIT = 0x20
    private const val HEX_RADIX = 16
    private const val UNICODE_HEX_WIDTH = 4
}
