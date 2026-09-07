package com.apm.model

/** Public constants that freeze the versioned collector contract. */
object ApmWireProtocol {
    /** Legacy explicit envelope version retained for V2 compatibility. */
    const val ENVELOPE_SCHEMA_VERSION_V2: Int = 2

    /** Occurrence-bound explicit envelope version used by strict production delivery. */
    const val ENVELOPE_SCHEMA_VERSION_V3: Int = 3

    /** Source-compatible V2 alias; new integrations should select a concrete schema constant. */
    @Deprecated("Select ENVELOPE_SCHEMA_VERSION_V2 or ENVELOPE_SCHEMA_VERSION_V3 explicitly")
    const val ENVELOPE_SCHEMA_VERSION: Int = ENVELOPE_SCHEMA_VERSION_V2

    /** Stable SDK implementation name carried by every versioned batch. */
    const val SDK_NAME: String = "android-apm"

    /** SDK artifact version carried by every versioned batch. */
    const val SDK_VERSION: String = "0.1.0"

    /** Request/response header carrying the selected explicit envelope schema. */
    const val HEADER_SCHEMA_VERSION: String = "X-Apm-Schema-Version"

    /** Request header carrying [SDK_VERSION]. */
    const val HEADER_SDK_VERSION: String = "X-Apm-Sdk-Version"

    /** Request and response header carrying the stable batch identity. */
    const val HEADER_BATCH_ID: String = "X-Apm-Batch-Id"

    /** Request and response header carrying the complete event count. */
    const val HEADER_EVENT_COUNT: String = "X-Apm-Event-Count"

    /** Version 2 protobuf envelope media type. */
    const val CONTENT_TYPE_ENVELOPE_V2: String =
        "application/x-protobuf; message=ApmBatchEnvelope; version=2"

    /** Version 3 protobuf envelope media type with per-event occurrence identity. */
    const val CONTENT_TYPE_ENVELOPE_V3: String =
        "application/x-protobuf; message=ApmBatchEnvelope; version=3"
}

/** Scalar types supported by the versioned wire field contract. */
enum class ApmScalarType {
    /** Explicit null with no textual value. */
    NULL,
    /** UTF-8 text or deterministic fallback text for an unsupported object. */
    STRING,
    /** Boolean represented canonically as `true` or `false`. */
    BOOLEAN,
    /** Signed eight-bit integer. */
    BYTE,
    /** Signed sixteen-bit integer. */
    SHORT,
    /** Signed thirty-two-bit integer. */
    INT,
    /** Signed sixty-four-bit integer. */
    LONG,
    /** IEEE-754 single-precision value using Kotlin's canonical text. */
    FLOAT,
    /** IEEE-754 double-precision value using Kotlin's canonical text. */
    DOUBLE,
    /** One UTF-16 code unit. */
    CHAR,
    /** Arbitrary-precision integer in base-ten text. */
    BIG_INTEGER,
    /** Arbitrary-precision decimal using plain base-ten text. */
    BIG_DECIMAL
}

/**
 * Explicitly typed wire representation of one [ApmEvent.fields] value.
 *
 * @property type stable scalar discriminator
 * @property value canonical text, or null only when [type] is [ApmScalarType.NULL]
 */
data class ApmTypedValue(
    val type: ApmScalarType,
    val value: String?
) {
    companion object {
        /** Converts every supported Kotlin scalar without losing its runtime type. */
        fun from(source: Any?): ApmTypedValue {
            return when (source) {
                null -> ApmTypedValue(ApmScalarType.NULL, null)
                is String -> ApmTypedValue(ApmScalarType.STRING, source)
                is Boolean -> ApmTypedValue(ApmScalarType.BOOLEAN, source.toString())
                is Byte -> ApmTypedValue(ApmScalarType.BYTE, source.toString())
                is Short -> ApmTypedValue(ApmScalarType.SHORT, source.toString())
                is Int -> ApmTypedValue(ApmScalarType.INT, source.toString())
                is Long -> ApmTypedValue(ApmScalarType.LONG, source.toString())
                is Float -> ApmTypedValue(ApmScalarType.FLOAT, source.toString())
                is Double -> ApmTypedValue(ApmScalarType.DOUBLE, source.toString())
                is Char -> ApmTypedValue(ApmScalarType.CHAR, source.toString())
                is java.math.BigInteger -> ApmTypedValue(ApmScalarType.BIG_INTEGER, source.toString())
                is java.math.BigDecimal -> ApmTypedValue(ApmScalarType.BIG_DECIMAL, source.toPlainString())
                else -> ApmTypedValue(ApmScalarType.STRING, source.toString())
            }
        }
    }
}
