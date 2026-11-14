package com.apm.threadmonitor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [enumerateLiveThreads] 的纯 JUnit 确定性测试。
 * 验证沿 ThreadGroup 层级枚举能捕获当前线程与显式创建的存活线程，且无重复。
 */
class ThreadEnumerationTest {

    /** 枚举结果必须包含调用线程自身。 */
    @Test
    fun `enumeration includes the current thread`() {
        val threads = enumerateLiveThreads()
        assertTrue(threads.contains(Thread.currentThread()))
    }

    /** 枚举捕获显式创建并存活的命名线程，且不产生重复。 */
    @Test
    fun `enumeration captures live named threads without duplicates`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(3)
        val names = listOf("apm-enum-test-a", "apm-enum-test-b", "apm-enum-test-c")
        val workers = names.map { name ->
            Thread({
                started.countDown()
                // 阻塞保持存活，确保枚举期间线程一定在场。
                release.await(5, TimeUnit.SECONDS)
            }, name).apply { isDaemon = true; start() }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val threads = enumerateLiveThreads()
            for (name in names) {
                assertTrue("missing $name", threads.any { it.name == name })
            }
            // 同一线程不应被重复收集（每个线程恰属一个线程组）。
            assertEquals(threads.size, threads.distinct().size)
        } finally {
            release.countDown()
            workers.forEach { it.join(5_000) }
        }
    }

    /** 子线程组中的线程也能被递归枚举捕获。 */
    @Test
    fun `enumeration recurses into child thread groups`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val group = ThreadGroup("apm-enum-child-group")
        val worker = Thread(group, {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, "apm-enum-child-thread").apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val threads = enumerateLiveThreads()
            assertTrue(threads.any { it.name == "apm-enum-child-thread" })
        } finally {
            release.countDown()
            worker.join(5_000)
            group.destroy()
        }
    }
}