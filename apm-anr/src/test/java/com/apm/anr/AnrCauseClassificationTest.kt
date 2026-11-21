package com.apm.anr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ANR 原因分类纯函数测试。
 * 覆盖 LOCK/DEADLOCK/IO/BINDER/CPU/UNKNOWN 全部出口，
 * 重点钉住 BINDER 先于 CPU 的判定顺序（Binder 阻塞时采样堆栈常有变化，
 * 旧顺序会把它误分类为 CPU 密集）。
 */
class AnrCauseClassificationTest {

    /** 锁关键词 + 采样不同：LOCK。 */
    @Test
    fun `lock stack with varying samples is LOCK`() {
        val stack = "java.lang.Object.wait(Native Method)\ncom.example.Foo.bar(Foo.kt:1)"
        assertEquals(ANR_CAUSE_LOCK, classifyAnrCauseInternal(stack, listOf("a", "b")))
    }

    /** 锁关键词 + 采样完全相同：疑似 DEADLOCK。 */
    @Test
    fun `lock stack with identical samples is DEADLOCK`() {
        val stack = "sun.misc.Unsafe.park(Native Method)\njava.util.concurrent.locks.LockSupport.park(LockSupport.java:1)"
        assertEquals(ANR_CAUSE_DEADLOCK, classifyAnrCauseInternal(stack, listOf("a", "a", "a")))
    }

    /** 锁关键词但采样不足：LOCK（不满足死锁最少采样数）。 */
    @Test
    fun `lock stack with single sample stays LOCK`() {
        val stack = "java.lang.Object.wait(Native Method)"
        assertEquals(ANR_CAUSE_LOCK, classifyAnrCauseInternal(stack, listOf("a")))
    }

    /** IO 关键词优先于采样变化：IO。 */
    @Test
    fun `io stack is IO regardless of samples`() {
        val stack = "java.io.FileInputStream.readBytes(Native Method)"
        assertEquals(ANR_CAUSE_IO, classifyAnrCauseInternal(stack, listOf("a", "b")))
    }

    /** Binder 阻塞 + 采样有变化：BINDER 而非 CPU（判定顺序回归）。 */
    @Test
    fun `binder stack with varying samples is BINDER not CPU`() {
        val stack = "android.os.BinderProxy.transactNative(Native Method)\nandroid.os.BinderProxy.transact(BinderProxy.java:1)"
        assertEquals(ANR_CAUSE_BINDER, classifyAnrCauseInternal(stack, listOf("a", "b")))
    }

    /** Binder 服务端帧同样判 BINDER。 */
    @Test
    fun `binder exec transact frame is BINDER`() {
        val stack = "android.os.Binder.execTransactInternal(Binder.java:1)"
        assertEquals(ANR_CAUSE_BINDER, classifyAnrCauseInternal(stack, emptyList()))
    }

    /** 无关键词 + 采样有变化：CPU。 */
    @Test
    fun `varying samples without keywords is CPU`() {
        val stack = "com.example.Heavy.compute(Heavy.kt:1)"
        assertEquals(ANR_CAUSE_CPU, classifyAnrCauseInternal(stack, listOf("a", "b")))
    }

    /** 无关键词 + 采样完全相同：证据不足，UNKNOWN。 */
    @Test
    fun `identical samples without keywords is UNKNOWN`() {
        val stack = "com.example.Heavy.compute(Heavy.kt:1)"
        assertEquals(ANR_CAUSE_UNKNOWN, classifyAnrCauseInternal(stack, listOf("a", "a")))
    }

    /** 无关键词且无采样：UNKNOWN。 */
    @Test
    fun `no keywords and no samples is UNKNOWN`() {
        assertEquals(ANR_CAUSE_UNKNOWN, classifyAnrCauseInternal("com.example.Foo.bar(Foo.kt:1)", emptyList()))
    }

    /** 关键词匹配大小写不敏感。 */
    @Test
    fun `keyword matching is case insensitive`() {
        val stack = "java.util.concurrent.locks.ReentrantLock.lock(ReentrantLock.java:1)"
        assertEquals(ANR_CAUSE_LOCK, classifyAnrCauseInternal(stack, emptyList()))
    }
}
