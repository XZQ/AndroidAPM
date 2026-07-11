package com.apm.core.diagnostics

import java.security.MessageDigest

/**
 * Applies privacy filtering and hard size bounds before diagnostics leave the calling thread.
 */
internal object DiagnosticSanitizer {

    /** Sanitizes and bounds one diagnostic message. */
    fun sanitizeMessage(value: String): String {
        // Authorization headers are handled before generic key/value credentials so bearer payloads cannot leak.
        val withoutAuthorization = AUTHORIZATION_PATTERN.replace(value) { match ->
            "${match.groupValues[1]}$REDACTED_VALUE"
        }
        val withoutBearer = BEARER_PATTERN.replace(withoutAuthorization, "Bearer $REDACTED_VALUE")
        val redacted = CREDENTIAL_PATTERN.replace(withoutBearer) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_VALUE"
        }
        return truncate(redacted, MAX_MESSAGE_CHARS)
    }

    /**
     * Converts a throwable into bounded safe fields.
     *
     * @param error throwable to sanitize
     * @param includeStack whether stack frames may be retained
     * @return controlled throwable fields
     */
    fun sanitizeThrowable(error: Throwable?, includeStack: Boolean): SanitizedThrowable {
        if (error == null) {
            return SanitizedThrowable(null, null, null, null)
        }
        // Retain only the first bounded frame set; causes and suppressed exceptions are intentionally excluded.
        val stack = if (includeStack) {
            error.stackTrace
                .take(MAX_STACK_FRAMES)
                .joinToString(separator = NEWLINE) { frame -> frame.toString() }
                .takeIf { value -> value.isNotEmpty() }
                ?.let { value -> truncate(value, MAX_EXCEPTION_CHARS) }
        } else {
            null
        }
        return SanitizedThrowable(
            className = error.javaClass.name,
            message = error.message?.let(::sanitizeMessage),
            stackTrace = stack,
            stackHash = stack?.let(::sha256Prefix)
        )
    }

    /** Truncates a string and marks the loss explicitly. */
    private fun truncate(value: String, maxChars: Int): String {
        return if (value.length <= maxChars) {
            value
        } else {
            value.take(maxChars - TRUNCATED_SUFFIX.length) + TRUNCATED_SUFFIX
        }
    }

    /** Creates a compact stable fingerprint for a retained stack trace. */
    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(value.toByteArray(Charsets.UTF_8))
        // Two hex characters per byte; the first eight bytes provide a compact stable correlation key.
        return digest.take(HASH_PREFIX_BYTES).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Generic credential-shaped key/value pairs. */
    private val CREDENTIAL_PATTERN = Regex(
        pattern = "(?i)(token|access_token|refresh_token|password)(\\s*[:=]\\s*)([^&\\s]+)"
    )
    /** HTTP Authorization header with an optional scheme. */
    private val AUTHORIZATION_PATTERN = Regex("(?i)(Authorization\\s*:\\s*)(?:Bearer\\s+)?[^\\s&]+")
    /** Standalone bearer credentials. */
    private val BEARER_PATTERN = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    /** Maximum stored message characters. */
    private const val MAX_MESSAGE_CHARS = 4_096
    /** Maximum stored exception stack characters. */
    private const val MAX_EXCEPTION_CHARS = 16_384
    /** Maximum stored exception stack frames. */
    private const val MAX_STACK_FRAMES = 64
    /** Number of SHA-256 bytes retained in a stack fingerprint. */
    private const val HASH_PREFIX_BYTES = 8
    /** Explicit suffix added to truncated strings. */
    private const val TRUNCATED_SUFFIX = "...[truncated]"
    /** Replacement used for credential values. */
    private const val REDACTED_VALUE = "[REDACTED]"
    /** SHA-256 algorithm name. */
    private const val SHA_256 = "SHA-256"
    /** Platform-independent persisted stack separator. */
    private const val NEWLINE = "\n"
}

/** Controlled throwable fields stored in a diagnostic entry. */
internal data class SanitizedThrowable(
    /** Throwable class name. */
    val className: String?,
    /** Sanitized throwable message. */
    val message: String?,
    /** Bounded stack trace. */
    val stackTrace: String?,
    /** Stable stack fingerprint. */
    val stackHash: String?
)
