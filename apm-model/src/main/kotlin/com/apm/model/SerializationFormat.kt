package com.apm.model

/**
 * 事件序列化格式。
 *
 * 控制 APM 事件在上传时使用的编码格式。
 * LINE_PROTOCOL 为默认格式（文本），PROTOBUF 为二进制格式（体积约 1/3~1/5）。
 */
enum class SerializationFormat {
    /**
     * Line Protocol 文本格式。
     * 格式：ts=xxx|module=xxx|name=xxx|kind=xxx|severity=xxx|...
     * 兼容性最好，便于调试和日志查看。
     */
    LINE_PROTOCOL,

    /**
     * Protobuf 二进制格式。
     * 基于 proto3 wire format，与 apm_event.proto 定义兼容。
     * 体积约为 LINE_PROTOCOL 的 1/3~1/5，适合生产环境大规模上报。
     * Content-Type: application/x-protobuf
     */
    PROTOBUF
}
