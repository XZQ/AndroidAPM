package com.apm.core

import android.content.Context
import android.util.Base64
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.ApmPriority
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * IPC artifacts removed during process-local consent revocation.
 *
 * @property clearedFileCount ready or temporary hand-off files successfully removed
 * @property allFilesCleared whether every observed SDK hand-off file was removed
 */
internal data class IpcConsentCleanupResult(
    val clearedFileCount: Int,
    val allFilesCleared: Boolean
)

/** Internal result preserving the exact reason for a synchronous IPC hand-off rejection. */
internal data class IpcWriteResult(
    /** Whether every supplied event reached an atomically published ready file. */
    val success: Boolean,
    /** Stable loss reason when [success] is false. */
    val dropReason: SdkDropReason? = null
)

/**
 * 多进程事件协调器。
 *
 * 解决非上传进程（子进程）的事件上报问题：
 * - 非上传进程将事件写入临时文件，再原子发布为可消费 IPC 文件
 * - 上传进程定期扫描并消费已发布的 IPC 文件
 *
 * 文件命名：apm-ipc-{sessionId}-{sequence}.ipc
 * 每个发布文件保存至多 [maxLinesPerFile] 行 Base64 包裹的可逆事件 payload：
 * 普通事件先进入带字节预算的 lock-free 缓冲，由唯一 [WRITE_FLUSH_DELAY_MS] 周期任务
 * 取出并按行数/文件字节拆批，大幅减少高频子进程事件的文件数、任务数与扫描开销；
 * critical 事件立即单文件发布。
 *
 * 线程安全：普通 producer 在非等待锁内合并缓冲，单线程执行器序列化 flush；critical
 * 写操作同步发布。ready-directory 预算检查、发布和 consent 清理共用跨进程文件锁。
 */
class ProcessEventCoordinator internal constructor(
    /** 共享 IPC 目录：{cacheDir}/apm-ipc/。 */
    private val ipcDir: File,
    /** 当前进程是否为上传进程（默认主进程负责上传）。 */
    private val isUploaderProcess: Boolean,
    /** IPC 文件扫描间隔（毫秒）。 */
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    /** 单个 IPC 文件最大行数：缓冲达到该行数立即合批发布。 */
    private val maxLinesPerFile: Int = DEFAULT_MAX_LINES_PER_FILE,
    /** Incomplete `.tmp` artifact retention before cleanup; published `.ipc` awaits consumption. */
    private val maxFileAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS,
    /** Maximum retained bytes waiting for the single IPC writer. */
    maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES,
    /** Maximum durable-codec bytes accepted for one IPC event. */
    maxEventPayloadBytes: Int = DEFAULT_MAX_EVENT_PAYLOAD_BYTES,
    /** Maximum ASCII payload bytes in one ready IPC file. */
    maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Maximum bytes retained by all ready IPC files in the shared directory. */
    maxDirectoryBytes: Long = DEFAULT_MAX_DIRECTORY_BYTES
) {
    /**
     * Creates a coordinator rooted at the app cache directory.
     *
     * @param context 应用上下文，用于获取共享文件目录
     * @param isUploaderProcess 当前进程是否为上传进程
     * @param scanIntervalMs IPC 文件扫描间隔（毫秒）
     * @param maxLinesPerFile 单个 IPC 文件最大行数（合批阈值）
     * @param maxFileAgeMs incomplete `.tmp` artifact retention before cleanup
     * @param maxPendingBytes pending-event retained-byte budget
     * @param maxEventPayloadBytes exact durable-codec limit for one event
     * @param maxFileBytes atomic ready-file byte limit
     * @param maxDirectoryBytes aggregate ready-file byte limit
     */
    constructor(
        context: Context,
        isUploaderProcess: Boolean,
        scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
        maxLinesPerFile: Int = DEFAULT_MAX_LINES_PER_FILE,
        maxFileAgeMs: Long = DEFAULT_MAX_FILE_AGE_MS,
        maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES,
        maxEventPayloadBytes: Int = DEFAULT_MAX_EVENT_PAYLOAD_BYTES,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        maxDirectoryBytes: Long = DEFAULT_MAX_DIRECTORY_BYTES
    ) : this(
        ipcDir = File(context.cacheDir, IPC_DIR_NAME).apply { mkdirs() },
        isUploaderProcess = isUploaderProcess,
        scanIntervalMs = scanIntervalMs,
        maxLinesPerFile = maxLinesPerFile,
        maxFileAgeMs = maxFileAgeMs,
        maxPendingBytes = maxPendingBytes,
        maxEventPayloadBytes = maxEventPayloadBytes,
        maxFileBytes = maxFileBytes,
        maxDirectoryBytes = maxDirectoryBytes
    )

    /** Effective positive pending-event budget. */
    private val effectiveMaxPendingBytes = maxPendingBytes.coerceAtLeast(MIN_BYTE_BUDGET)

    /** Effective positive line-count split threshold. */
    private val effectiveMaxLinesPerFile = maxLinesPerFile.coerceAtLeast(1)

    /** Effective positive exact binary event budget. */
    private val effectiveMaxEventPayloadBytes = maxEventPayloadBytes.coerceAtLeast(1)

    /** Effective positive per-file budget. */
    private val effectiveMaxFileBytes = maxFileBytes.coerceAtLeast(MIN_BYTE_BUDGET)

    /** Effective directory budget, never smaller than one allowed file. */
    private val effectiveMaxDirectoryBytes = maxDirectoryBytes.coerceAtLeast(effectiveMaxFileBytes)

    /** 写操作执行器，保证串行写入。 */
    private val writeExecutor: ScheduledExecutorService =
        ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME_WRITE)

    /** 扫描执行器，仅上传进程使用。 */
    private var scanExecutor: ScheduledExecutorService? = null

    /** 上传进程消费事件时的回调。 */
    var onRemoteEvent: ((ApmEvent) -> Unit)? = null

    /**
     * Core-owned acknowledged consumer.
     *
     * Returning false retains the complete ready file for at-least-once retry. The public
     * [onRemoteEvent] callback remains unchanged for source and binary compatibility.
     */
    internal var onRemoteEventResult: ((ApmEvent) -> Boolean)? = null

    /** Records one asynchronously rejected event with its exact priority and budget boundary. */
    internal var onDrop: ((ApmPriority, SdkDropReason) -> Unit)? = null

    /** 是否已启动。 */
    @Volatile
    private var started = false

    /** Sticky local gate preventing a writer from publishing after consent erase begins. */
    @Volatile
    private var consentRevoked = false

    /** One pending event and the conservative retained-byte reservation charged at admission. */
    private data class PendingEvent(
        /** Frozen event awaiting writer-thread serialization. */
        val event: ApmEvent,
        /** Retained-byte reservation released when the batch leaves memory. */
        val estimatedBytes: Long
    )

    /** Lock-free pending buffer: producers never wait behind writer disk IO. */
    private val pendingEvents = ConcurrentLinkedQueue<PendingEvent>()

    /** Exact pending-event count paired with [pendingEvents] for O(1) diagnostics/tests. */
    private val pendingEventCount = AtomicInteger()

    /** Retained bytes currently reserved by [pendingEvents]. */
    private val pendingBytes = AtomicLong()

    /** Separates normal producer/flush readers from consent-revocation cleanup. */
    private val lifecycleLock = ReentrantReadWriteLock()

    /** Serializes scheduled and explicit drains without involving producer admission. */
    private val flushExecutionLock = ReentrantLock()

    /** One fixed-delay writer task replaces one executor task per event. */
    @Volatile
    private var pendingFlush: ScheduledFuture<*>? = null

    init {
        // 确保显式目录构造器和 Context 构造器都拥有可写目录。
        ipcDir.mkdirs()
    }

    /**
     * 启动协调器。
     * 上传进程启动定期扫描，非上传进程准备写入目录。
     */
    fun start() {
        if (started) {
            return
        }
        started = true

        if (isUploaderProcess) {
            // 上传进程：定期扫描 IPC 目录消费其他进程的事件
            scanExecutor = ApmExecutors.newSingleThreadScheduledExecutor(THREAD_NAME_SCAN)
            scanExecutor?.scheduleWithFixedDelay(
                { scanAndConsume() },
                scanIntervalMs,
                scanIntervalMs,
                TimeUnit.MILLISECONDS
            )
        } else {
            // One recurring task bounds executor work independently of event rate.
            try {
                pendingFlush = writeExecutor.scheduleWithFixedDelay(
                    { flushPendingEvents() },
                    WRITE_FLUSH_DELAY_MS,
                    WRITE_FLUSH_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
            } catch (error: RejectedExecutionException) {
                started = false
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, error)
            }
        }
    }

    /**
     * 将事件写入 IPC 文件（非上传进程调用）。
     * 事件先写入临时文件，再发布为上传进程可消费的 IPC 文件。
     *
     * @param event 待传输的事件
     */
    fun writeEvent(event: ApmEvent) {
        if (isUploaderProcess) {
            return // 上传进程直接走主通道，无需 IPC
        }
        if (!started) {
            return
        }

        // Serialization runs later on the IPC writer, so freeze producer-owned maps now.
        val eventSnapshot = snapshotEvent(event)
        val estimatedBytes = ApmEventSizeEstimator.estimate(eventSnapshot)
        // Consent cleanup takes the write side only after closing the volatile admission gate.
        val producerLock = lifecycleLock.readLock()
        if (!producerLock.tryLock()) {
            val reason = if (consentRevoked) {
                SdkDropReason.CONSENT_REVOKED
            } else {
                SdkDropReason.IPC_HANDOFF_FAILURE
            }
            onDrop?.invoke(eventSnapshot.priority, reason)
            return
        }
        try {
            if (!started || consentRevoked) {
                return
            }
            if (!reservePendingBytes(estimatedBytes)) {
                onDrop?.invoke(eventSnapshot.priority, SdkDropReason.IPC_PENDING_BYTE_BUDGET)
                return
            }
            pendingEvents.offer(PendingEvent(eventSnapshot, estimatedBytes))
            pendingEventCount.incrementAndGet()
        } finally {
            producerLock.unlock()
        }
    }

    /** Atomically reserves pending retained bytes without locking a host producer. */
    private fun reservePendingBytes(estimatedBytes: Long): Boolean {
        if (estimatedBytes > effectiveMaxPendingBytes) {
            return false
        }
        while (true) {
            val current = pendingBytes.get()
            if (current > effectiveMaxPendingBytes - estimatedBytes) {
                return false
            }
            if (pendingBytes.compareAndSet(current, current + estimatedBytes)) {
                return true
            }
        }
    }

    /** Releases one previously reserved pending-event weight. */
    private fun releasePendingBytes(estimatedBytes: Long) {
        pendingBytes.updateAndGet { current -> (current - estimatedBytes).coerceAtLeast(0L) }
    }

    /** Drops every pending event after the writer becomes unavailable or consent is revoked. */
    private fun dropAllPending(reason: SdkDropReason) {
        while (true) {
            val pending = pendingEvents.poll() ?: break
            pendingEventCount.decrementAndGet()
            releasePendingBytes(pending.estimatedBytes)
            onDrop?.invoke(pending.event.priority, reason)
        }
        pendingEventCount.set(0)
        pendingBytes.set(0L)
    }

    /**
     * 把缓冲中的事件合批发布为一个 IPC 文件。
     * 缓冲为空时为 no-op；发布失败记入自监控。
     */
    private fun flushPendingEvents() {
        val lifecycleRead = lifecycleLock.readLock()
        lifecycleRead.lock()
        flushExecutionLock.lock()
        try {
            if (pendingEvents.isEmpty()) {
                return
            }
            val batch = ArrayList<ApmEvent>()
            var batchReservedBytes = 0L
            while (batch.size < MAX_PENDING_EVENTS_PER_FLUSH) {
                val pending = pendingEvents.poll() ?: break
                pendingEventCount.decrementAndGet()
                batchReservedBytes += pending.estimatedBytes
                batch += pending.event
            }
            try {
                publishBatchFiles(batch, recordAsyncDrops = true)
            } catch (e: Exception) {
                // 发布失败丢弃该批（IPC 尽力而为），记入自监控
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, e)
                for (event in batch) {
                    onDrop?.invoke(event.priority, SdkDropReason.IPC_HANDOFF_FAILURE)
                }
            } finally {
                // Keep in-flight objects charged so queue + active batch never exceed 4 MiB.
                releasePendingBytes(batchReservedBytes)
            }
        } finally {
            flushExecutionLock.unlock()
            lifecycleRead.unlock()
        }
    }

    /**
     * 立即执行一次合批发布。
     * 供测试和受控调用方使用，不等待定时器。
     */
    internal fun flushPendingNow() {
        flushPendingEvents()
    }

    /**
     * 当前缓冲中待发布的事件数。
     * 供测试观测写线程的消化进度。
     *
     * @return 缓冲事件数
     */
    internal fun pendingBufferSize(): Int {
        return pendingEventCount.get().coerceAtLeast(0)
    }

    /** Returns the retained-byte reservation currently waiting for IPC serialization. */
    internal fun pendingBufferBytes(): Long {
        return pendingBytes.get().coerceAtLeast(0L)
    }

    /**
     * 同步发布 critical 事件到 IPC 文件。
     *
     * @param event 待传输的 critical 事件
     * @return true 表示事件文件已完整发布
     */
    fun writeEventSync(event: ApmEvent): Boolean {
        return writeEventSyncWithResult(event).success
    }

    /** Synchronously publishes one critical event while preserving a stable rejection reason. */
    internal fun writeEventSyncWithResult(event: ApmEvent): IpcWriteResult {
        if (isUploaderProcess) {
            return IpcWriteResult(false, SdkDropReason.IPC_HANDOFF_FAILURE)
        }
        val lifecycleRead = lifecycleLock.readLock()
        lifecycleRead.lock()
        try {
            if (!started || consentRevoked) {
                return IpcWriteResult(false, SdkDropReason.IPC_HANDOFF_FAILURE)
            }
            // Serialize against consent cleanup so every completed file is deleted before return.
            return try {
                publishBatchFiles(listOf(snapshotEvent(event)), recordAsyncDrops = false)
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, error)
                IpcWriteResult(false, SdkDropReason.IPC_HANDOFF_FAILURE)
            }
        } finally {
            lifecycleRead.unlock()
        }
    }

    /**
     * Encodes a batch once, isolates oversized events, and splits ready files by line and bytes.
     *
     * @param events events awaiting publication
     * @param recordAsyncDrops whether rejected events should be recorded through [onDrop]
     * @return complete publication result and first stable failure reason
     */
    private fun publishBatchFiles(events: List<ApmEvent>, recordAsyncDrops: Boolean): IpcWriteResult {
        if (events.isEmpty()) {
            return IpcWriteResult(true)
        }
        val currentLines = ArrayList<EncodedLine>()
        var currentFileBytes = 0L
        var firstFailure: SdkDropReason? = null

        /** Records one isolated failure without preventing valid peer publication. */
        fun reject(event: ApmEvent, reason: SdkDropReason) {
            if (firstFailure == null) {
                firstFailure = reason
            }
            if (recordAsyncDrops) {
                onDrop?.invoke(event.priority, reason)
            }
        }

        /** Publishes the current bounded file and attributes any rejection to every member. */
        fun flushCurrent() {
            if (currentLines.isEmpty()) {
                return
            }
            val reason = publishEncodedFile(currentLines)
            if (reason != null) {
                for (line in currentLines) {
                    reject(line.event, reason)
                }
            }
            currentLines.clear()
            currentFileBytes = 0L
        }

        for (event in events) {
            val payload = try {
                ApmEventCodec.encode(event)
            } catch (error: Exception) {
                Apm.recordInternalError(ERROR_TAG_IPC_WRITE, error)
                reject(event, SdkDropReason.IPC_HANDOFF_FAILURE)
                continue
            }
            if (payload.size > effectiveMaxEventPayloadBytes) {
                reject(event, SdkDropReason.IPC_FILE_BYTE_BUDGET)
                continue
            }
            val text = encodePayload(payload)
            val encodedLineBytes = text.length.toLong() + LINE_TERMINATOR_BYTES
            if (encodedLineBytes > effectiveMaxFileBytes) {
                reject(event, SdkDropReason.IPC_FILE_BYTE_BUDGET)
                continue
            }
            if (currentLines.isNotEmpty() &&
                (currentLines.size >= effectiveMaxLinesPerFile ||
                    currentFileBytes > effectiveMaxFileBytes - encodedLineBytes)
            ) {
                flushCurrent()
            }
            currentLines += EncodedLine(event, text)
            currentFileBytes += encodedLineBytes
        }
        flushCurrent()
        return IpcWriteResult(firstFailure == null, firstFailure)
    }

    /** One already-encoded ASCII IPC line and its source event for exact loss attribution. */
    private data class EncodedLine(
        /** Source event retained only until the bounded file is published. */
        val event: ApmEvent,
        /** Base64 text without a line terminator. */
        val text: String
    )

    /** Writes one bounded temp file and atomically publishes it under the directory budget lock. */
    private fun publishEncodedFile(lines: List<EncodedLine>): SdkDropReason? {
        val fileStem = nextFileStem()
        val tempFile = File(ipcDir, "$fileStem$IPC_TEMP_EXTENSION")
        val readyFile = File(ipcDir, "$fileStem$IPC_FILE_EXTENSION")
        var fallbackTempFile: File? = null
        return try {
            FileOutputStream(tempFile, false).use { output ->
                val writer = OutputStreamWriter(output, Charsets.US_ASCII)
                for (line in lines) {
                    writer.append(line.text)
                    writer.append('\n')
                }
                writer.flush()
                // A published critical file must not depend only on userspace buffering.
                output.fd.sync()
            }
            val incomingBytes = tempFile.length()
            if (incomingBytes > effectiveMaxFileBytes) {
                return SdkDropReason.IPC_FILE_BYTE_BUDGET
            }
            val lockFile = File(ipcDir, IPC_BUDGET_LOCK_FILE)
            RandomAccessFile(lockFile, LOCK_FILE_MODE).channel.use { channel ->
                channel.lock().use {
                    // Revocation sets the sticky gate before waiting on this publication lock.
                    if (consentRevoked) {
                        return SdkDropReason.CONSENT_REVOKED
                    }
                    var publishedBytes = 0L
                    val readyFiles = ipcDir.listFiles { file ->
                        file.name.startsWith(IPC_FILE_PREFIX) && file.name.endsWith(IPC_FILE_EXTENSION)
                    }.orEmpty()
                    for (file in readyFiles) {
                        val length = file.length()
                        if (length > effectiveMaxDirectoryBytes - publishedBytes) {
                            return SdkDropReason.IPC_DIRECTORY_BYTE_BUDGET
                        }
                        publishedBytes += length
                    }
                    if (publishedBytes > effectiveMaxDirectoryBytes - incomingBytes) {
                        return SdkDropReason.IPC_DIRECTORY_BYTE_BUDGET
                    }
                    if (!tempFile.renameTo(readyFile)) {
                        // Never expose a partially copied ready file: copy and sync another temp,
                        // then publish it only after its length has been verified.
                        val fallback = File(ipcDir, "$fileStem$IPC_COPY_TEMP_SUFFIX$IPC_TEMP_EXTENSION")
                        fallbackTempFile = fallback
                        FileInputStream(tempFile).use { input ->
                            FileOutputStream(fallback, false).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        if (fallback.length() != incomingBytes || !fallback.renameTo(readyFile)) {
                            return SdkDropReason.IPC_HANDOFF_FAILURE
                        }
                        tempFile.delete()
                    }
                }
            }
            null
        } finally {
            // Every failed path removes payload-bearing temporary data.
            if (tempFile.exists()) {
                tempFile.delete()
            }
            fallbackTempFile?.let { fallback ->
                if (fallback.exists()) {
                    fallback.delete()
                }
            }
        }
    }

    /**
     * 生成单调递增的事件文件名。
     *
     * @return 不含扩展名的文件名
     */
    private fun nextFileStem(): String {
        val sessionId = ProcessSessionId.get().replace(NON_ALPHA_REGEX, REPLACEMENT_UNDERSCORE)
        val sequence = FILE_SEQUENCE.incrementAndGet()
        return "$IPC_FILE_PREFIX${sessionId}_$sequence"
    }

    /**
     * 扫描并消费 IPC 目录中的事件文件。
     * 仅上传进程调用。读取后删除已消费的文件。
     */
    private fun scanAndConsume() {
        if (!started) {
            return
        }
        try {
            val files = ipcDir.listFiles { file ->
                file.name.endsWith(IPC_FILE_EXTENSION)
            } ?: return

            cleanupExpiredTempFiles(ApmClock.wallTimeMillis())
            for (file in files) {
                try {
                    if (file.length() > effectiveMaxFileBytes) {
                        // Never allocate an unbounded read buffer for a corrupt or foreign file.
                        Apm.recordInternalError(
                            ERROR_TAG_IPC_CONSUME_FILE,
                            IllegalArgumentException("IPC file exceeds byte budget")
                        )
                        file.delete()
                        continue
                    }
                    // Delete only after every decodable event reached the downstream hand-off.
                    // A retry may redeliver earlier lines from the same file; stable eventId makes
                    // that deliberate at-least-once behavior safe for the durable store.
                    if (consumeFile(file)) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    // 单文件消费失败不影响后续文件，但记入自监控
                    Apm.recordInternalError(ERROR_TAG_IPC_CONSUME_FILE, e)
                }
            }
        } catch (e: Exception) {
            // 扫描失败不中断调度，但记入自监控
            Apm.recordInternalError(ERROR_TAG_IPC_SCAN, e)
        }
    }

    /**
     * Runs one immediate scan cycle.
     * Used by tests and controlled callers that do not want to wait for the scheduled delay.
     */
    internal fun scanAndConsumeNow() {
        scanAndConsume()
    }

    /**
     * 清理异常退出遗留的临时文件。
     *
     * @param now 当前时间戳
     */
    private fun cleanupExpiredTempFiles(now: Long) {
        val tempFiles = ipcDir.listFiles { file ->
            file.name.endsWith(IPC_TEMP_EXTENSION)
        } ?: return
        for (file in tempFiles) {
            val fileAgeMs = now - file.lastModified()
            // 仅清理明显过期的临时文件，避免误删正在写入的事件。
            if (fileAgeMs > maxFileAgeMs) {
                file.delete()
            }
        }
    }

    /**
     * 读取单个 IPC 文件并回调每行事件。
     * 每行保存一个 Base64 包裹的可逆 ApmEvent payload。
     *
     * @param file IPC 文件
     */
    private fun consumeFile(file: File): Boolean {
        var observedLines = 0
        var downstreamAccepted = true
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) {
                    continue
                }
                observedLines += 1
                if (observedLines > effectiveMaxLinesPerFile || line.length.toLong() > effectiveMaxFileBytes) {
                    Apm.recordInternalError(
                        ERROR_TAG_IPC_PARSE_LINE,
                        IllegalArgumentException("IPC line exceeds configured bounds")
                    )
                    break
                }
                val event = try {
                    // Decode the complete event so no fields are lost across processes.
                    parseLineProtocol(line)
                } catch (e: Exception) {
                    // 单行解析失败跳过该行，但记入自监控（可能是编码不兼容）
                    Apm.recordInternalError(ERROR_TAG_IPC_PARSE_LINE, e)
                    null
                }
                if (event == null) {
                    continue
                }
                try {
                    val accepted = onRemoteEventResult?.invoke(event) ?: run {
                        val legacyCallback = onRemoteEvent
                        if (legacyCallback == null) {
                            false
                        } else {
                            legacyCallback(event)
                            true
                        }
                    }
                    if (!accepted) {
                        downstreamAccepted = false
                        break
                    }
                } catch (e: Exception) {
                    downstreamAccepted = false
                    Apm.recordInternalError(ERROR_TAG_IPC_HANDOFF, e)
                    break
                }
            }
        }
        return downstreamAccepted
    }

    /**
     * Decodes one Base64-wrapped durable event payload.
     *
     * @param line line protocol 格式的行
     * @return 解析后的 ApmEvent，解析失败返回 null
     */
    private fun parseLineProtocol(line: String): ApmEvent? {
        val payload = decodePayload(line)
        require(payload.size <= effectiveMaxEventPayloadBytes) {
            "IPC event payload exceeds byte budget"
        }
        val event = ApmEventCodec.decode(payload)
        val annotated = event.copy(extras = event.extras + ("ipc_source" to "remote_process"))
        return event.occurrence?.let(annotated::withOccurrenceContext) ?: annotated
    }

    /**
     * Encodes payload for one IPC line.
     *
     * @param payload binary event payload
     * @return Base64 text without line wraps
     */
    private fun encodePayload(payload: ByteArray): String {
        return try {
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (_: RuntimeException) {
            // Local JVM unit tests use Android stubs, so fall back to a small local codec there.
            encodePayloadFallback(payload)
        }
    }

    /**
     * Decodes payload from one IPC line.
     *
     * @param line Base64 text without line wraps
     * @return binary event payload
     */
    private fun decodePayload(line: String): ByteArray {
        return try {
            Base64.decode(line, Base64.NO_WRAP)
        } catch (_: RuntimeException) {
            // Local JVM unit tests use Android stubs, so fall back to a small local codec there.
            decodePayloadFallback(line)
        }
    }

    /**
     * Encodes bytes with standard Base64 without line wrapping.
     * 仅 JVM 单元测试路径可达（设备上 android.util.Base64 恒可用且优先命中，
     * 本方法只在其抛 RuntimeException 的 Android 桩环境执行），
     * 因此 java.util.Base64 的 API 26 要求不影响 minSdk 24 设备。
     *
     * @param payload binary event payload
     * @return Base64 text
     */
    @android.annotation.SuppressLint("NewApi")
    private fun encodePayloadFallback(payload: ByteArray): String {
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Decodes standard Base64 text without line wrapping.
     * 仅 JVM 单元测试路径可达（同 [encodePayloadFallback] 的可达性说明）。
     *
     * @param line Base64 text
     * @return decoded bytes
     */
    @android.annotation.SuppressLint("NewApi")
    private fun decodePayloadFallback(line: String): ByteArray {
        return java.util.Base64.getDecoder().decode(line)
    }

    /**
     * 停止协调器，释放线程资源。
     */
    fun stop() {
        started = false
        pendingFlush?.cancel(false)
        pendingFlush = null
        // 关闭前提交最终 flush，避免缓冲中的事件丢失
        runCatching { writeExecutor.execute { flushPendingEvents() } }
        writeExecutor.shutdown()
        scanExecutor?.shutdown()
        try {
            writeExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            scanExecutor?.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            writeExecutor.shutdownNow()
            scanExecutor?.shutdownNow()
        }
        scanExecutor = null
    }

    /**
     * Stops IPC without flushing buffered telemetry and deletes process-shared hand-off artifacts.
     *
     * @return deletion count and completeness for the local IPC directory
     */
    internal fun stopAndClearForConsentRevocation(): IpcConsentCleanupResult {
        consentRevoked = true
        started = false
        pendingFlush?.cancel(false)
        pendingFlush = null
        val lifecycleWrite = lifecycleLock.writeLock()
        lifecycleWrite.lock()
        try {
            dropAllPending(SdkDropReason.CONSENT_REVOKED)
            // Cancel queued writes after every active producer/flush has left the read side.
            writeExecutor.shutdownNow()
            scanExecutor?.shutdownNow()
            var writersStopped = false
            var scannerStopped = scanExecutor == null
            try {
                writersStopped = writeExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                scannerStopped = scanExecutor?.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            scanExecutor = null
            dropAllPending(SdkDropReason.CONSENT_REVOKED)
            val cleanup = clearIpcFilesForConsentRevocation()
            return cleanup.copy(allFilesCleared = cleanup.allFilesCleared && writersStopped && scannerStopped)
        } finally {
            lifecycleWrite.unlock()
        }
    }

    /** Deletes every ready or temporary SDK hand-off file after IPC executors have stopped. */
    private fun clearIpcFilesForConsentRevocation(): IpcConsentCleanupResult {
        return clearIpcFilesForConsentRevocation(ipcDir)
    }

    companion object {
        /** Clears dormant ready/temp IPC files when no coordinator runtime is available. */
        internal fun clearConsentArtifacts(context: Context): IpcConsentCleanupResult {
            return clearIpcFilesForConsentRevocation(File(context.cacheDir, IPC_DIR_NAME))
        }

        /** Performs best-effort deletion after active writers have stopped or before SDK init. */
        private fun clearIpcFilesForConsentRevocation(ipcDirectory: File): IpcConsentCleanupResult {
            ipcDirectory.mkdirs()
            val lockFile = File(ipcDirectory, IPC_BUDGET_LOCK_FILE)
            return try {
                RandomAccessFile(lockFile, LOCK_FILE_MODE).channel.use { channel ->
                    channel.lock().use {
                        clearIpcPayloadFiles(ipcDirectory)
                    }
                }
            } catch (_: Exception) {
                // A failed coordination lock makes completeness unknowable even if fallback
                // deletion currently observes no payload files.
                clearIpcPayloadFiles(ipcDirectory).copy(allFilesCleared = false)
            }
        }

        /** Deletes currently visible payload-bearing IPC artifacts while publication is excluded. */
        private fun clearIpcPayloadFiles(ipcDirectory: File): IpcConsentCleanupResult {
            val files = ipcDirectory.listFiles { file ->
                file.name.startsWith(IPC_FILE_PREFIX) &&
                    (file.name.endsWith(IPC_FILE_EXTENSION) || file.name.endsWith(IPC_TEMP_EXTENSION))
            }.orEmpty()
            var cleared = 0
            var complete = true
            for (file in files) {
                if (file.delete() || !file.exists()) {
                    cleared += 1
                } else {
                    complete = false
                }
            }
            return IpcConsentCleanupResult(clearedFileCount = cleared, allFilesCleared = complete)
        }

        /** 自监控 tag：IPC 写入失败。 */
        private const val ERROR_TAG_IPC_WRITE = "ipc_write"

        /** 自监控 tag：单个 IPC 文件消费失败。 */
        private const val ERROR_TAG_IPC_CONSUME_FILE = "ipc_consume_file"

        /** 自监控 tag：IPC 目录扫描失败。 */
        private const val ERROR_TAG_IPC_SCAN = "ipc_scan"

        /** 自监控 tag：IPC 行解析失败。 */
        private const val ERROR_TAG_IPC_PARSE_LINE = "ipc_parse_line"

        /** Stable tag for a recoverable downstream hand-off failure. */
        private const val ERROR_TAG_IPC_HANDOFF = "ipc_handoff"

        /** IPC 目录名。 */
        private const val IPC_DIR_NAME = "apm-ipc"
        /** IPC 文件前缀。 */
        private const val IPC_FILE_PREFIX = "apm-ipc-"
        /** IPC 文件扩展名。 */
        private const val IPC_FILE_EXTENSION = ".ipc"
        /** 临时文件扩展名。 */
        private const val IPC_TEMP_EXTENSION = ".tmp"
        /** Distinguishes the fully copied fallback temp from the original writer temp. */
        private const val IPC_COPY_TEMP_SUFFIX = "-copy"
        /** Cross-process file lock protecting the aggregate ready-file byte budget. */
        private const val IPC_BUDGET_LOCK_FILE = "apm-ipc-budget.lock"
        /** Read/write mode needed by the shared file-lock channel. */
        private const val LOCK_FILE_MODE = "rw"
        /** 默认扫描间隔：5 秒。 */
        private const val DEFAULT_SCAN_INTERVAL_MS = 5000L
        /** 合批缓冲的定时 flush 延迟（毫秒）。 */
        private const val WRITE_FLUSH_DELAY_MS = 500L
        /** 单文件最大行数：100 条。 */
        private const val DEFAULT_MAX_LINES_PER_FILE = 100
        /** Incomplete `.tmp` artifact retention: 5 minutes. */
        private const val DEFAULT_MAX_FILE_AGE_MS = 300_000L
        /** Default pending-event retained-byte budget: 4 MiB. */
        private const val DEFAULT_MAX_PENDING_BYTES = 4L * 1024L * 1024L
        /** Hard per-tick drain count paired with the 256-byte minimum retained estimate. */
        private const val MAX_PENDING_EVENTS_PER_FLUSH = 16_384
        /** Default exact binary event budget aligned with SQLite soft admission. */
        private const val DEFAULT_MAX_EVENT_PAYLOAD_BYTES = 256 * 1024
        /** Default atomic ready-file budget: 1 MiB. */
        private const val DEFAULT_MAX_FILE_BYTES = 1L * 1024L * 1024L
        /** Default shared ready-file directory budget: 16 MiB. */
        private const val DEFAULT_MAX_DIRECTORY_BYTES = 16L * 1024L * 1024L
        /** Smallest effective configurable byte budget. */
        private const val MIN_BYTE_BUDGET = 1L
        /** One ASCII line-feed byte appended after every Base64 payload. */
        private const val LINE_TERMINATOR_BYTES = 1L
        /** 写线程名。 */
        private const val THREAD_NAME_WRITE = "apm-ipc-write"
        /** 扫描线程名。 */
        private const val THREAD_NAME_SCAN = "apm-ipc-scan"
        /** 非字母数字正则。 */
        private const val NON_ALPHA_REGEX = "[^a-zA-Z0-9_.-]"
        /** 替换字符。 */
        private const val REPLACEMENT_UNDERSCORE = "_"
        /** Maximum time allowed for pending IPC writes. */
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
        /** Global file sequence used to avoid filename collisions within one process. */
        private val FILE_SEQUENCE = AtomicLong()
    }
}
