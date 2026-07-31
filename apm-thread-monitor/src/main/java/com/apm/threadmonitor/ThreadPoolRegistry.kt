package com.apm.threadmonitor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor

/** Immutable metrics from one explicitly registered executor. */
internal data class ThreadPoolSnapshot(
    /** Integrator-provided pool name. */
    val name: String,
    /** Current worker count. */
    val poolSize: Int,
    /** Current actively executing worker count. */
    val activeCount: Int,
    /** Configured maximum worker count. */
    val maxPoolSize: Int,
    /** Current work-queue size. */
    val queuedTasks: Int,
    /** Total completed task count. */
    val completedTaskCount: Long
)

/** Thread-safe registry for executors the host explicitly chooses to monitor. */
internal class ThreadPoolRegistry {
    /** Registered executors keyed by stable display name. */
    private val executors = ConcurrentHashMap<String, ThreadPoolExecutor>()

    /** Registers or replaces one executor. */
    fun register(name: String, executor: ThreadPoolExecutor) {
        require(name.isNotBlank()) { "Thread pool name must not be blank" }
        executors[name] = executor
    }

    /** Removes a registered executor by name. */
    fun unregister(name: String): Boolean = executors.remove(name) != null

    /** Reads point-in-time metrics from every currently registered executor. */
    fun snapshots(): List<ThreadPoolSnapshot> = executors.map { (name, executor) ->
        ThreadPoolSnapshot(
            name = name,
            poolSize = executor.poolSize,
            activeCount = executor.activeCount,
            maxPoolSize = executor.maximumPoolSize,
            queuedTasks = executor.queue.size,
            completedTaskCount = executor.completedTaskCount
        )
    }

    /** Returns the exact current registration count. */
    fun size(): Int = executors.size

    /** Releases strong references to every registered executor. */
    fun clear() {
        executors.clear()
    }
}
