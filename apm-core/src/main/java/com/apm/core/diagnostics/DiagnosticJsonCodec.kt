package com.apm.core.diagnostics

import org.json.JSONObject

/**
 * Stable JSON Lines codec for controlled diagnostic records.
 */
internal object DiagnosticJsonCodec {

    /** Encodes one entry as a single-line JSON object. */
    fun encode(entry: DiagnosticEntry): String {
        return JSONObject()
            .put(FIELD_FORMAT_VERSION, FORMAT_VERSION)
            .put(FIELD_SEQUENCE, entry.sequence)
            .put(FIELD_TIMESTAMP_MS, entry.timestampMs)
            .put(FIELD_SESSION_ID, entry.sessionId)
            .put(FIELD_LEVEL, entry.level.name)
            .put(FIELD_COMPONENT, entry.component)
            .put(FIELD_CODE, entry.code ?: JSONObject.NULL)
            .put(FIELD_MESSAGE, entry.message)
            .put(FIELD_PROCESS_NAME, entry.processName)
            .put(FIELD_THREAD_NAME, entry.threadName)
            .put(FIELD_EXCEPTION_CLASS, entry.exceptionClass ?: JSONObject.NULL)
            .put(FIELD_EXCEPTION_MESSAGE, entry.exceptionMessage ?: JSONObject.NULL)
            .put(FIELD_STACK_TRACE, entry.stackTrace ?: JSONObject.NULL)
            .put(FIELD_STACK_HASH, entry.stackHash ?: JSONObject.NULL)
            .toString()
    }

    /** Decodes one JSONL line, returning null when the line is corrupt or unsupported. */
    fun decode(line: String): DiagnosticEntry? {
        return try {
            val json = JSONObject(line)
            if (json.getInt(FIELD_FORMAT_VERSION) != FORMAT_VERSION) {
                return null
            }
            DiagnosticEntry(
                sequence = json.getLong(FIELD_SEQUENCE),
                timestampMs = json.getLong(FIELD_TIMESTAMP_MS),
                sessionId = json.getString(FIELD_SESSION_ID),
                level = DiagnosticLevel.valueOf(json.getString(FIELD_LEVEL)),
                component = json.getString(FIELD_COMPONENT),
                code = json.nullableString(FIELD_CODE),
                message = json.getString(FIELD_MESSAGE),
                processName = json.getString(FIELD_PROCESS_NAME),
                threadName = json.getString(FIELD_THREAD_NAME),
                exceptionClass = json.nullableString(FIELD_EXCEPTION_CLASS),
                exceptionMessage = json.nullableString(FIELD_EXCEPTION_MESSAGE),
                stackTrace = json.nullableString(FIELD_STACK_TRACE),
                stackHash = json.nullableString(FIELD_STACK_HASH)
            )
        } catch (_: Exception) {
            // A corrupt final line or future unsupported shape must not escape into the host application.
            null
        }
    }

    /** Reads a nullable JSON string without converting JSON null into the literal string "null". */
    private fun JSONObject.nullableString(name: String): String? {
        return if (isNull(name)) null else getString(name)
    }

    /** Persisted format version. */
    private const val FORMAT_VERSION = 1
    /** JSON field: format version. */
    private const val FIELD_FORMAT_VERSION = "formatVersion"
    /** JSON field: sequence. */
    private const val FIELD_SEQUENCE = "sequence"
    /** JSON field: timestamp. */
    private const val FIELD_TIMESTAMP_MS = "timestampMs"
    /** JSON field: session identifier. */
    private const val FIELD_SESSION_ID = "sessionId"
    /** JSON field: level. */
    private const val FIELD_LEVEL = "level"
    /** JSON field: component. */
    private const val FIELD_COMPONENT = "component"
    /** JSON field: code. */
    private const val FIELD_CODE = "code"
    /** JSON field: message. */
    private const val FIELD_MESSAGE = "message"
    /** JSON field: process name. */
    private const val FIELD_PROCESS_NAME = "processName"
    /** JSON field: thread name. */
    private const val FIELD_THREAD_NAME = "threadName"
    /** JSON field: exception class. */
    private const val FIELD_EXCEPTION_CLASS = "exceptionClass"
    /** JSON field: exception message. */
    private const val FIELD_EXCEPTION_MESSAGE = "exceptionMessage"
    /** JSON field: stack trace. */
    private const val FIELD_STACK_TRACE = "stackTrace"
    /** JSON field: stack fingerprint. */
    private const val FIELD_STACK_HASH = "stackHash"
}
