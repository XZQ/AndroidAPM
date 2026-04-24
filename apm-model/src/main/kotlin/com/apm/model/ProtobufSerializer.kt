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
     *
     * @param event 要序列化的 APM 事件
     * @return protobuf 编码的字节数组
     */
    fun serialize(event: ApmEvent): ByteArray {
        val buffer = ByteArrayOutputStream()
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

        // fields map：编码为 repeated {string key=1; string value=2}
        for ((key, value) in event.fields) {
            writer.writeStringMapEntry(FIELD_FIELDS, key, value?.toString() ?: "")
        }

        // globalContext map
        for ((key, value) in event.globalContext) {
            writer.writeStringMapEntry(FIELD_GLOBAL_CONTEXT, key, value)
        }

        // extras map
        for ((key, value) in event.extras) {
            writer.writeStringMapEntry(FIELD_EXTRAS, key, value)
        }

        writer.flush()
        return buffer.toByteArray()
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
        val buffer = ByteArrayOutputStream()
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

    /** 字节掩码。 */
    private const val BYTE_MASK = 0xFF
}
