package com.didi.apm.uploader

import android.util.Log
import com.didi.apm.model.ApmEvent
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
 * 包装底层上传器，提供：队列缓冲、批量发送、指数退避重试。
 *
 * 线程安全：内部使用单线程执行器 + 阻塞队列。
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

    /** 事件缓冲队列，满时丢弃新事件。 */
    private val queue = LinkedBlockingQueue<ApmEvent>(QUEUE_CAPACITY)

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
     * 队列满时丢弃并打印警告，不阻塞调用方。
     */
    override fun upload(event: ApmEvent) {
        if (!queue.offer(event)) {
            Log.w(TAG, "Upload queue full, dropping event: ${event.name}")
        }
    }

    /** 关闭上传器，停止工作线程。 */
    fun shutdown() {
        running = false
        executor.shutdown()
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
                events.forEach { delegate.upload(it) }
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > retryPolicy.maxRetries) {
                    Log.e(TAG, "Upload failed after ${retryPolicy.maxRetries} retries", e)
                    return
                }
                // 按指数退避等待后重试
                val delay = retryPolicy.delayForAttempt(attempt)
                Thread.sleep(delay)
            }
        }
    }

    companion object {
        /** Logcat tag。 */
        private const val TAG = "ApmUploader"

        /** 工作线程名。 */
        private const val THREAD_NAME = "apm-upload-retry"

        /** 队列容量。 */
        private const val QUEUE_CAPACITY = 500

        /** 默认批量大小。 */
        private const val DEFAULT_BATCH_SIZE = 10

        /** 默认刷盘间隔：30 秒。 */
        private const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L
    }
}
