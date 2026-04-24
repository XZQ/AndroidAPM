package com.apm.uploader

import android.util.Log
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 重试策略：指数退避参数。
 */
data class RetryPolicy(
    /** 最大重试次数。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 基础延迟（毫秒）。 */
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    /** 最大延迟上限（毫秒），防止指数增长过大。 */
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    /** 退避倍数。delay = baseDelay * (multiplier ^ attempt)。 */
    val backoffMultiplier: Float = DEFAULT_BACKOFF_MULTIPLIER
) {
    /**
     * 计算第 N 次重试的等待时间。
     * @param attempt 重试序号（从 1 开始）
     * @return 延迟毫秒数，不超过 [maxDelayMs]
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var delay = baseDelayMs.toFloat()
        repeat(attempt) { delay *= backoffMultiplier }
        return delay.toLong().coerceAtMost(maxDelayMs)
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_BASE_DELAY_MS = 1000L
        private const val DEFAULT_MAX_DELAY_MS = 30_000L
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0f
    }
}

/**
 * 带重试和批量的上传器。
 * 包装底层上传器，提供：优先级队列缓冲、批量发送、指数退避重试。
 *
 * 线程安全：内部使用单线程执行器 + PriorityBlockingQueue。
 * 队列按 priority.value DESC 排序（CRITICAL 优先出队），
 * 队列超容量时丢弃 LOW 优先级事件。
 */
class RetryingApmUploader(
    /** 被包装的底层上传器。 */
    private val delegate: ApmUploader,
    /** 重试策略。 */
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    /** 批量大小：从队列中一次取出的最大事件数。 */
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** 刷盘间隔（毫秒）：队列无数据时的最大等待时间。 */
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS
) : ApmUploader {

    /**
     * 事件优先级队列，按 priority.value DESC 排序。
     * CRITICAL(3) 最先出队，LOW(0) 最后出队。
     * PriorityBlockingQueue 是无界的，通过显式容量检查控制内存。
     */
    private val queue = PriorityBlockingQueue<ApmEvent>(
        QUEUE_INITIAL_CAPACITY,
        compareByDescending<ApmEvent> { it.priority.value }
            .thenByDescending { it.timestamp }
    )

    /** 单线程执行器，保证上传顺序。 */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME)
    }

    /** 运行标志。volatile 保证工作线程可见性。 */
    @Volatile
    private var running = true

    init {
        // 启动上传工作线程
        executor.execute { processLoop() }
    }

    /**
     * 将事件加入上传队列。
     * 队列超容量时丢弃 LOW 优先级事件，非 LOW 优先级仍接受。
     *
     * @param event 待上传事件
     * @return true 表示成功入队，false 表示被丢弃
     */
    override fun upload(event: ApmEvent): Boolean {
        // 关闭后拒绝新的上传请求，避免 stop 之后继续积压事件。
        if (!running) {
            return false
        }
        // 容量控制：超过阈值时丢弃 LOW 优先级事件
        if (queue.size >= QUEUE_CAPACITY && event.priority == ApmPriority.LOW) {
            Log.w(TAG, "Queue over capacity ($QUEUE_CAPACITY), dropping LOW priority event: ${event.name}")
            return false
        }
        queue.put(event)
        return true
    }

    /** 关闭上传器，停止工作线程。 */
    override fun shutdown() {
        running = false
        // 立即打断 poll/sleep，避免 stop 后后台线程继续持有资源。
        executor.shutdownNow()
        delegate.shutdown()
    }

    /**
     * 上传工作线程主循环。
     * 1. 等待队列中有数据（最长 flushIntervalMs）
     * 2. 取出第一条，再 drain 最多 batchSize-1 条组成批量
     * 3. 调用底层上传器发送，失败时按重试策略重试
     */
    private fun processLoop() {
        val batch = mutableListOf<ApmEvent>()
        // running=false 后仍处理完队列中剩余事件
        while (running || queue.isNotEmpty()) {
            try {
                batch.clear()
                // 阻塞等待第一条（超时后继续循环检查 running）
                val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    // 非阻塞取出更多事件凑够一批
                    queue.drainTo(batch, batchSize - 1)
                }
                if (batch.isEmpty()) continue
                uploadBatchWithRetry(batch.toList())
            } catch (_: InterruptedException) {
                // 被中断，退出循环
                break
            }
        }
    }

    /**
     * 带重试的批量上传。
     * 每次重试都会重新发送整批事件。
     * 超过最大重试次数后丢弃。
     */
    private fun uploadBatchWithRetry(events: List<ApmEvent>) {
        var attempt = 0
        while (attempt <= retryPolicy.maxRetries) {
            try {
                // 只有整批事件都被底层 uploader 成功接收时才算本轮成功。
                val uploadSucceeded = events.all { delegate.upload(it) }
                if (uploadSucceeded) {
                    return
                }
            } catch (e: Exception) {
                if (attempt >= retryPolicy.maxRetries) {
                    Log.e(TAG, "Upload failed after ${retryPolicy.maxRetries} retries", e)
                    return
                }
            }

            attempt++
            if (attempt > retryPolicy.maxRetries) {
                Log.e(TAG, "Upload failed after ${retryPolicy.maxRetries} retries")
                return
            }

            try {
                // 按指数退避等待后重试。
                val delay = retryPolicy.delayForAttempt(attempt)
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                // 关闭上传器时立即结束重试循环。
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        /** Logcat tag。 */
        private const val TAG = "ApmUploader"

        /** 工作线程名。 */
        private const val THREAD_NAME = "apm-upload-retry"

        /** 队列初始容量（PriorityBlockingQueue 会自动扩容）。 */
        private const val QUEUE_INITIAL_CAPACITY = 500

        /** 队列最大容量阈值，超过时丢弃 LOW 优先级事件。 */
        private const val QUEUE_CAPACITY = 500

        /** 默认批量大小。 */
        private const val DEFAULT_BATCH_SIZE = 10

        /** 默认刷盘间隔：30 秒。 */
        private const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L
    }
}
