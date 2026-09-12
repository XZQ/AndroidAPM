package com.apm.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes final delivery and erasure, including dormant cleanup after a timed-out session. */
internal object EventDeliveryBarrier {
    /** Process-wide because a stopped dispatcher and a reopened store can share the same files. */
    private val lock = ReentrantLock()

    /** Maximum wait for an already executing host store/transport; callbacks cannot be cancelled. */
    private const val CLEANUP_TIMEOUT_MS = 3_000L

    /** Rechecks the permanent session gate after callbacks and immediately before ownership transfer. */
    fun <T> handoff(isClosed: () -> Boolean, block: () -> T): T? = lock.withLock {
        if (isClosed()) null else block()
    }

    /**
     * Runs cleanup only after all earlier transfers finish. Null means erasure is unproven;
     * callers must not bypass this result by reopening the same database through another helper.
     */
    fun <T> erase(block: () -> T): T? {
        // A host store can reenter revocation; its outer append has not finished in that case.
        if (lock.isHeldByCurrentThread) return null
        val acquired = try {
            lock.tryLock(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
