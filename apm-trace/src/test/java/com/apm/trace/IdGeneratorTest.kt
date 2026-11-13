package com.apm.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * IdGenerator 直接契约测试。
 *
 * traceId/spanId 的长度在 ApmSpanTest 中经由 Span 间接锁定；
 * 本套件直接锁定字符集（小写十六进制）、长度与并发唯一性。
 */
class IdGeneratorTest {

    /** 断言字符串只含小写十六进制字符。 */
    private fun assertLowerHex(value: String) {
        assertTrue(
            "Expected lowercase hex, was: $value",
            value.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }

    /** traceId：32 位小写十六进制，格式稳定。 */
    @Test
    fun generateTraceId_format() {
        repeat(100) {
            val traceId = IdGenerator.generateTraceId()
            assertEquals(32, traceId.length)
            assertLowerHex(traceId)
        }
    }

    /** spanId：16 位小写十六进制，格式稳定。 */
    @Test
    fun generateSpanId_format() {
        repeat(100) {
            val spanId = IdGenerator.generateSpanId()
            assertEquals(16, spanId.length)
            assertLowerHex(spanId)
        }
    }

    /** 并发生成下 spanId 不重复（序列计数 + 随机数混合）。 */
    @Test
    fun generateSpanId_concurrentGenerationStaysUnique() {
        val threads = 4
        val perThread = 1_000
        val seen = ConcurrentHashMap.newKeySet<String>()
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                executor.submit {
                    startGate.await()
                    repeat(perThread) {
                        assertTrue(seen.add(IdGenerator.generateSpanId()))
                    }
                }
            }
            startGate.countDown()
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertEquals(threads * perThread, seen.size)
    }

    /** 并发生成下 traceId 不重复。 */
    @Test
    fun generateTraceId_concurrentGenerationStaysUnique() {
        val threads = 4
        val perThread = 500
        val seen = ConcurrentHashMap.newKeySet<String>()
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                executor.submit {
                    startGate.await()
                    repeat(perThread) {
                        assertTrue(seen.add(IdGenerator.generateTraceId()))
                    }
                }
            }
            startGate.countDown()
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertEquals(threads * perThread, seen.size)
    }
}
