package com.apm.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileRewriteScheduler 重写节奏测试。
 * 验证文件裁剪只按追加次数触发，而不是按缓冲区当前大小触发。
 */
class FileRewriteSchedulerTest {

    /** 只有累计追加达到阈值时才触发重写。 */
    @Test
    fun `rewrite happens only on configured append interval`() {
        val scheduler = FileRewriteScheduler(rewriteInterval = 3)

        assertFalse(scheduler.onAppend())
        assertFalse(scheduler.onAppend())
        assertTrue(scheduler.onAppend())
        assertFalse(scheduler.onAppend())
    }

    /** reset 后应从新的追加周期重新计数。 */
    @Test
    fun `reset restarts append cadence`() {
        val scheduler = FileRewriteScheduler(rewriteInterval = 2)

        assertFalse(scheduler.onAppend())
        scheduler.reset()

        assertFalse(scheduler.onAppend())
        assertTrue(scheduler.onAppend())
    }
}
