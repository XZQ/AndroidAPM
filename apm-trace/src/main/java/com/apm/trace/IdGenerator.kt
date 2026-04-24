package com.apm.trace

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Trace/Span ID 生成器。
 *
 * traceId: 32 位十六进制字符串（128 bit），与 OpenTelemetry/W3C TraceContext 兼容。
 * spanId: 16 位十六进制字符串（64 bit）。
 */
internal object IdGenerator {

    /** 序列计数器，用于 spanId 的低位部分。 */
    private val sequence = AtomicLong(0)

    /** 十六进制字符表。 */
    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * 生成 128 bit traceId（32 位十六进制字符串）。
     * 高 64 位为时间戳异或随机数，低 64 位为随机数，保证全局唯一。
     */
    fun generateTraceId(): String {
        val random = ThreadLocalRandom.current()
        val hi = System.currentTimeMillis() xor random.nextLong()
        val lo = random.nextLong()
        return StringBuilder(TRACE_ID_LENGTH).apply {
            appendHexLong(hi)
            appendHexLong(lo)
        }.toString()
    }

    /**
     * 生成 64 bit spanId（16 位十六进制字符串）。
     * 时间戳低 32 位异或序列计数 + 随机数混合。
     */
    fun generateSpanId(): String {
        val random = ThreadLocalRandom.current()
        val ts = (System.currentTimeMillis() ushr 16) and 0xFFFFFFFFL
        val seq = sequence.getAndIncrement() and 0xFFFFL
        val value = ts xor (seq shl 48) xor random.nextLong()
        return StringBuilder(SPAN_ID_LENGTH).apply {
            appendHexLong(value)
        }.toString()
    }

    /**
     * 将 Long 值追加为 16 位十六进制字符串。
     */
    private fun StringBuilder.appendHexLong(value: Long) {
        for (i in 60 downTo 0 step 4) {
            val nibble = ((value ushr i) and 0xF).toInt()
            append(HEX_CHARS[nibble])
        }
    }

    /** traceId 长度：32 个十六进制字符。 */
    private const val TRACE_ID_LENGTH = 32

    /** spanId 长度：16 个十六进制字符。 */
    private const val SPAN_ID_LENGTH = 16
}
