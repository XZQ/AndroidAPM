package com.apm.model

import java.io.ByteArrayOutputStream

/**
 * APM 事件 Protobuf 序列化器。
 *
 * 将 [ApmEvent] 序列化为 protobuf 二进制格式，体积约为 Line Protocol 的 1/3~1/5。
 * 使用零依赖的 [ProtobufWriter] 直接写入 wire format，无需 protoc 生成的代码。
 *
 * 编码格式与 [apm_event.proto](proto/apm_event.proto) 定义完全兼容，
 * 服务端可用标准 protobuf 库（Java/C++/Go/Python）反序列化。
 *
 * 使用方式：
 * ```kotlin
 * val bytes: ByteArray = ProtobufSerializer.serialize(event)
 * // bytes 可通过 HTTP POST 发送至服务端
 * ```
 */
object ProtobufSerializer {

    /**
     * 将 [ApmEvent] 序列化为 protobuf 二进制格式。
     *
     * 字段编号与 apm_event.proto 一一对应：
     * - 1: timestamp (int64)
     * - 2: module (string)
     * - 3: name (string)
     * - 4: kind (string enum)
     * - 5: severity (string enum)
     * - 6: process_name (string)
     * - 7: thread_name (string)
     * - 8: scene (string, optional)
     * - 9: foreground (bool, optional)
     * - 10: fields (map<string,string>)
     * - 11: global_context (map<string,string>)
     * - 12: extras (map<string,string>)
     * - 13: priority (string enum)
     * - 14: event_id (string)
     *
     * @param event 要序列化的 APM 事件
     * @return protobuf 编码的字节数组
     */
    fun serialize(event: ApmEvent): ByteArray {
        return serializeEvent(event, typedFields = false, includeOccurrence = false)
    }

    /** Encodes one event for the schema-v2 batch envelope using explicit typed fields. */
    internal fun serializeVersionedEvent(event: ApmEvent): ByteArray {
        require(event.occurrence == null) {
            "Schema V2 must not carry an occurrence snapshot; select PROTOBUF_ENVELOPE_V3"
        }
        return serializeEvent(event, typedFields = true, includeOccurrence = false)
    }

    /** Encodes one event for schema V3 and requires complete occurrence-bound identity. */
    internal fun serializeVersionedEventV3(event: ApmEvent): ByteArray {
        validateOccurrence(event.occurrence)
        return serializeEvent(event, typedFields = true, includeOccurrence = true)
    }

    /** Writes common event fields while selecting either legacy or versioned field semantics. */
    private fun serializeEvent(
        event: ApmEvent,
        typedFields: Boolean,
        includeOccurrence: Boolean
    ): ByteArray {
        // 容量预估摊薄默认 32 字节缓冲的多次扩容；字符数下界足够作为增长提示
        val buffer = ByteArrayOutputStream(estimateEventBytes(event))
        val writer = ProtobufWriter(buffer)

        // 按字段编号顺序写入，与 apm_event.proto 定义对应
        writer.writeInt64(FIELD_TIMESTAMP, event.timestamp)
        writer.writeString(FIELD_MODULE, event.module)
        writer.writeString(FIELD_NAME, event.name)
        writer.writeString(FIELD_KIND, event.kind.name)
        writer.writeString(FIELD_SEVERITY, event.severity.name)
        writer.writeString(FIELD_PROCESS_NAME, event.processName)
        writer.writeString(FIELD_THREAD_NAME, event.threadName)

        // 可选字段：非空时写入
        event.scene?.let { writer.writeString(FIELD_SCENE, it) }
        event.foreground?.let { writer.writeBool(FIELD_FOREGROUND, it) }

        if (typedFields) {
            for ((key, value) in event.fields) {
                // V2 uses a map entry whose value is an embedded discriminator/value message;
                // writeTypedMapEntry streams it directly without intermediate buffers.
                val typedValue = ApmTypedValue.from(value)
                writer.writeTypedMapEntry(FIELD_TYPED_FIELDS, key, typedValue.type.name, typedValue.value)
            }
        } else {
            // Legacy standalone messages retain map<string,string> field 10 exactly.
            for ((key, value) in event.fields) {
                writer.writeStringMapEntry(FIELD_FIELDS, key, value?.toString() ?: "")
            }
        }

        // globalContext map
        for ((key, value) in event.globalContext) {
            writer.writeStringMapEntry(FIELD_GLOBAL_CONTEXT, key, value)
        }

        // extras map
        for ((key, value) in event.extras) {
            writer.writeStringMapEntry(FIELD_EXTRAS, key, value)
        }

        // 上传优先级：与 Line Protocol 一样输出枚举名，保证两种 wire format 字段一致
        writer.writeString(FIELD_PRIORITY, event.priority.name)
        // Event identity is append-only field 14 for server-side deduplication.
        writer.writeString(FIELD_EVENT_ID, event.eventId)
        if (includeOccurrence) {
            writer.writeMessage(FIELD_OCCURRENCE, serializeOccurrence(requireNotNull(event.occurrence)))
        }

        writer.flush()
        return buffer.toByteArray()
    }

    /** Encodes occurrence identity independently so V2 cannot accidentally emit field 16. */
    private fun serializeOccurrence(occurrence: ApmOccurrenceContext): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = ProtobufWriter(buffer)
        writer.writeString(OCCURRENCE_SERVICE_VERSION, occurrence.serviceVersion)
        writer.writeString(OCCURRENCE_VERSION_CODE, occurrence.versionCode)
        writer.writeString(OCCURRENCE_APP_BUILD, occurrence.appBuild)
        writer.writeString(OCCURRENCE_VARIANT, occurrence.variant)
        writer.writeString(OCCURRENCE_INSTALLATION_ID, occurrence.installationId)
        for (frame in occurrence.nativeFrames) {
            writer.writeMessage(OCCURRENCE_NATIVE_FRAMES, serializeNativeFrame(frame))
        }
        writer.flush()
        return buffer.toByteArray()
    }

    /** Encodes one build-relative native frame without an absolute process address. */
    private fun serializeNativeFrame(frame: ApmNativeFrameIdentity): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = ProtobufWriter(buffer)
        writer.writeString(NATIVE_FRAME_ABI, frame.abi)
        writer.writeString(NATIVE_FRAME_BUILD_ID, frame.moduleBuildId)
        writer.writeString(NATIVE_FRAME_MODULE_NAME, frame.moduleName)
        writer.writeInt64(NATIVE_FRAME_RELATIVE_PC, frame.moduleRelativePc)
        frame.loadBias?.let { writer.writeInt64(NATIVE_FRAME_LOAD_BIAS, it) }
        writer.flush()
        return buffer.toByteArray()
    }

    /** Validates strict V3 identity without encoding, allowing durable replay to isolate invalid rows. */
    fun validateOccurrence(occurrence: ApmOccurrenceContext?) {
        requireNotNull(occurrence) { "Schema V3 requires an occurrence snapshot on every event" }
        val requiredValues = listOf(
            occurrence.serviceVersion,
            occurrence.versionCode,
            occurrence.appBuild,
            occurrence.variant,
            occurrence.installationId
        )
        require(requiredValues.all { it.isNotBlank() && it == it.trim() && it.length <= MAX_IDENTITY_CHARS }) {
            "Schema V3 occurrence values must be non-blank, trimmed, and bounded"
        }
        require(occurrence.versionCode.all { it in '0'..'9' }) {
            "Schema V3 versionCode must use canonical unsigned decimal text"
        }
        require(occurrence.versionCode.length == 1 || occurrence.versionCode.first() != '0') {
            "Schema V3 versionCode must use canonical unsigned decimal text"
        }
        require(occurrence.nativeFrames.size <= MAX_NATIVE_FRAMES) {
            "Schema V3 occurrence exceeds the native-frame limit"
        }
        for (frame in occurrence.nativeFrames) {
            require(
                frame.abi.isNotBlank() && frame.moduleBuildId.isNotBlank() &&
                    frame.moduleName.isNotBlank() && frame.abi.length <= MAX_IDENTITY_CHARS &&
                    frame.moduleBuildId.length <= MAX_IDENTITY_CHARS &&
                    frame.moduleName.length <= MAX_IDENTITY_CHARS
            ) { "Schema V3 native frame identity is incomplete or oversized" }
            require(frame.moduleRelativePc >= 0L && (frame.loadBias == null || frame.loadBias >= 0L)) {
                "Schema V3 native addresses must be non-negative and module-relative"
            }
        }
    }

    /**
     * 批量序列化：将多个事件编码为一个连续的字节数组。
     * 每个事件前添加 4 字节 big-endian 长度前缀。
     *
     * 格式：[length1(4B)][event1 bytes][length2(4B)][event2 bytes]...
     *
     * @param events 要批量序列化的事件列表
     * @return 带长度前缀的连续字节数组
     */
    fun serializeBatch(events: List<ApmEvent>): ByteArray {
        // 容量预估摊薄批量缓冲的多次扩容复制
        val buffer = ByteArrayOutputStream(events.size * ESTIMATE_BATCH_EVENT_BYTES)
        for (event in events) {
            val eventBytes = serialize(event)
            // 写入 4 字节 big-endian 长度前缀
            buffer.write((eventBytes.size shr 24) and BYTE_MASK)
            buffer.write((eventBytes.size shr 16) and BYTE_MASK)
            buffer.write((eventBytes.size shr 8) and BYTE_MASK)
            buffer.write(eventBytes.size and BYTE_MASK)
            // 写入事件数据
            buffer.write(eventBytes)
        }
        return buffer.toByteArray()
    }

    /**
     * 单事件编码大小的字符数下界预估。
     *
     * 只统计键名与字符串值的长度，不对非字符串字段值调用宿主 `toString`
     * （避免可观察的额外调用），偏小仅触发一次扩容。
     */
    private fun estimateEventBytes(event: ApmEvent): Int {
        var total = ESTIMATE_EVENT_FIXED_BYTES
        total += event.module.length + event.name.length + event.processName.length +
            event.threadName.length + event.eventId.length + (event.scene?.length ?: 0)
        for ((key, value) in event.fields) {
            total += key.length + ((value as? String)?.length ?: ESTIMATE_NON_STRING_VALUE_BYTES)
        }
        for ((key, value) in event.globalContext) {
            total += key.length + value.length
        }
        for ((key, value) in event.extras) {
            total += key.length + value.length
        }
        return total
    }

    // --- Proto field numbers (must match apm_event.proto) ---

    /** 字段 1：时间戳（毫秒 epoch）。 */
    private const val FIELD_TIMESTAMP = 1
    /** 字段 2：模块名。 */
    private const val FIELD_MODULE = 2
    /** 字段 3：事件名。 */
    private const val FIELD_NAME = 3
    /** 字段 4：事件类型枚举名。 */
    private const val FIELD_KIND = 4
    /** 字段 5：严重级别枚举名。 */
    private const val FIELD_SEVERITY = 5
    /** 字段 6：进程名。 */
    private const val FIELD_PROCESS_NAME = 6
    /** 字段 7：线程名。 */
    private const val FIELD_THREAD_NAME = 7
    /** 字段 8：场景标识。 */
    private const val FIELD_SCENE = 8
    /** 字段 9：是否前台。 */
    private const val FIELD_FOREGROUND = 9
    /** 字段 10：指标数据 map。 */
    private const val FIELD_FIELDS = 10
    /** 字段 11：全局上下文 map。 */
    private const val FIELD_GLOBAL_CONTEXT = 11
    /** 字段 12：附加键值对 map。 */
    private const val FIELD_EXTRAS = 12
    /** 字段 13：上传优先级枚举名。 */
    private const val FIELD_PRIORITY = 13
    /** Field 14: stable client-generated event identity. */
    private const val FIELD_EVENT_ID = 14
    /** Field 15: versioned typed event fields. */
    private const val FIELD_TYPED_FIELDS = 15
    /** Field 16: schema-V3 occurrence-bound identity. */
    private const val FIELD_OCCURRENCE = 16

    /** Occurrence field: application version name. */
    private const val OCCURRENCE_SERVICE_VERSION = 1
    /** Occurrence field: versionCode. */
    private const val OCCURRENCE_VERSION_CODE = 2
    /** Occurrence field: immutable build identity. */
    private const val OCCURRENCE_APP_BUILD = 3
    /** Occurrence field: build variant. */
    private const val OCCURRENCE_VARIANT = 4
    /** Occurrence field: anonymous installation identity. */
    private const val OCCURRENCE_INSTALLATION_ID = 5
    /** Occurrence field: repeated build-relative native frames. */
    private const val OCCURRENCE_NATIVE_FRAMES = 6

    /** Native-frame field: ABI. */
    private const val NATIVE_FRAME_ABI = 1
    /** Native-frame field: module build ID. */
    private const val NATIVE_FRAME_BUILD_ID = 2
    /** Native-frame field: module name. */
    private const val NATIVE_FRAME_MODULE_NAME = 3
    /** Native-frame field: module-relative PC. */
    private const val NATIVE_FRAME_RELATIVE_PC = 4
    /** Native-frame field: optional load bias. */
    private const val NATIVE_FRAME_LOAD_BIAS = 5

    /** Protobuf map-entry key field. */
    private const val MAP_ENTRY_KEY = 1
    /** Protobuf map-entry value field. */
    private const val MAP_ENTRY_VALUE = 2
    /** Typed-value discriminator field. */
    private const val TYPED_VALUE_TYPE = 1
    /** Typed-value canonical text field. */
    private const val TYPED_VALUE_TEXT = 2

    /** 字节掩码。 */
    private const val BYTE_MASK = 0xFF

    /** 批量序列化缓冲的每事件容量预估（长度前缀 + 典型事件大小的粗下界）。 */
    private const val ESTIMATE_BATCH_EVENT_BYTES = 512

    /** 单事件固定头部的容量预估（字段 tag、varint 前缀与标量字段）。 */
    private const val ESTIMATE_EVENT_FIXED_BYTES = 64

    /** 非字符串字段值不调用宿主 toString 时的固定保守字节估计。 */
    private const val ESTIMATE_NON_STRING_VALUE_BYTES = 24

    /** Public protocol identity bound shared with the collector. */
    private const val MAX_IDENTITY_CHARS = 256

    /** Per-event native frame bound preventing unreviewed allocation growth. */
    private const val MAX_NATIVE_FRAMES = 256
}
