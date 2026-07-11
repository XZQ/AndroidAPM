package com.apm.core.diagnostics

import android.app.Application
import com.apm.core.ProcessSessionId
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Public local support facade for AndroidAPM self-diagnostics. */
object ApmDiagnostics {

    /** Active or most recently stopped diagnostics runtime. */
    private val runtime = AtomicReference<RuntimeHandle?>(null)
    /** Monotonic diagnostics initialization sequence within the process. */
    private val sessionSequence = AtomicLong(0L)

    /** Returns current diagnostics health without touching the filesystem. */
    fun status(): DiagnosticStatus = runtime.get()?.recorder?.status() ?: DiagnosticStatus.INACTIVE

    /**
     * Returns newest-first local diagnostic entries. Call from a worker thread because persisted journals are parsed.
     *
     * @param limit requested maximum entries
     */
    fun snapshot(limit: Int = DEFAULT_SNAPSHOT_LIMIT): List<DiagnosticEntry> {
        return runtime.get()?.recorder?.snapshot(limit).orEmpty()
    }

    /** Executes [snapshot] on a caller-selected executor. */
    fun snapshotAsync(
        executor: Executor,
        limit: Int = DEFAULT_SNAPSHOT_LIMIT,
        callback: (List<DiagnosticEntry>) -> Unit
    ) {
        executor.execute {
            // Deliver exactly one immutable result from the selected worker.
            callback(snapshot(limit))
        }
    }

    /**
     * Exports a bounded ZIP package. Call from a worker thread.
     *
     * Multi-process runtimes merge every process-specific journal under the diagnostics root.
     *
     * @param targetFile app-selected export target
     */
    fun exportTo(targetFile: File): DiagnosticExportResult {
        val handle = runtime.get() ?: return inactiveExportResult()
        return if (handle.isolatedByProcess) {
            exportAllProcesses(handle, targetFile)
        } else {
            handle.recorder.exportTo(targetFile)
        }
    }

    /** Executes [exportTo] on a caller-selected executor. */
    fun exportToAsync(
        executor: Executor,
        targetFile: File,
        callback: (DiagnosticExportResult) -> Unit
    ) {
        executor.execute {
            // ZIP creation and journal parsing stay off the calling thread.
            callback(exportTo(targetFile))
        }
    }

    /** Clears retained records belonging to the current process. */
    fun clear(): Boolean = runtime.get()?.recorder?.clear() ?: false

    /** Clears journals for every process under the current diagnostics root. */
    fun clearAllProcesses(): Boolean {
        val handle = runtime.get() ?: return false
        if (!handle.isolatedByProcess) {
            return handle.recorder.clear()
        }
        var cleared = handle.recorder.clear()
        for (directory in processDirectories(handle.rootDirectory)) {
            if (directory.absoluteFile == handle.processDirectory.absoluteFile) {
                continue
            }
            // Continue after one process fails so the explicit operation removes as much evidence as possible.
            val processCleared = try {
                DiagnosticFileStore(directory, handle.config).clear()
            } catch (error: Exception) {
                handle.recorder.noteWriteFailure(error)
                false
            }
            cleared = processCleared && cleared
        }
        return cleared
    }

    /** Initializes diagnostics in the default app-private, process-isolated directory. */
    internal fun initialize(
        application: Application,
        config: DiagnosticsConfig,
        processName: String
    ): DiagnosticRecorder? {
        val rootDirectory = File(application.filesDir, DIAGNOSTICS_DIRECTORY)
        // Keep the process identity prefix while distinguishing stop/init cycles in the same process.
        val sessionId = "${ProcessSessionId.get()}_${sessionSequence.incrementAndGet()}"
        return initialize(rootDirectory, config, processName, sessionId, isolateProcess = true)
    }

    /** Initializes diagnostics with an explicit storage root and identity. */
    internal fun initialize(
        directory: File,
        config: DiagnosticsConfig,
        processName: String,
        sessionId: String,
        isolateProcess: Boolean = false
    ): DiagnosticRecorder? {
        val previous = runtime.getAndSet(null)
        previous?.recorder?.shutdown()
        if (!config.enabled) {
            return null
        }
        val processDirectory = if (isolateProcess) processDirectory(directory, processName) else directory
        val created = DiagnosticRecorder(
            config = config,
            processName = processName,
            sessionId = sessionId,
            store = DiagnosticFileStore(processDirectory, config)
        )
        runtime.set(RuntimeHandle(created, directory, processDirectory, config, isolateProcess))
        return created
    }

    /** Resolves a deterministic collision-resistant directory for one Android process. */
    internal fun processDirectory(rootDirectory: File, processName: String): File {
        val safePrefix = processName.replace(UNSAFE_DIRECTORY_CHARACTERS, DIRECTORY_REPLACEMENT)
            .take(MAX_PROCESS_PREFIX_CHARS)
            .ifEmpty { UNKNOWN_PROCESS_PREFIX }
        val digest = MessageDigest.getInstance(SHA_256).digest(processName.toByteArray(Charsets.UTF_8))
        val hash = digest.take(PROCESS_HASH_BYTES).joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(rootDirectory, "$PROCESS_DIRECTORY_PREFIX$safePrefix-$hash")
    }

    /** Records one structured entry when diagnostics are active. */
    internal fun record(
        level: DiagnosticLevel,
        component: String,
        code: String?,
        message: String,
        error: Throwable?
    ) {
        runtime.get()?.recorder?.record(level, component, code, message, error)
    }

    /** Flushes accepted records within a caller-selected bound. */
    internal fun flush(timeoutMs: Long): Boolean = runtime.get()?.recorder?.flush(timeoutMs) ?: true

    /** Stops file persistence while preserving snapshot and export access. */
    internal fun shutdown() {
        runtime.get()?.recorder?.shutdown()
    }

    /** Merges decoded process journals and current unpersisted memory into one archive. */
    private fun exportAllProcesses(handle: RuntimeHandle, targetFile: File): DiagnosticExportResult {
        handle.recorder.flush(EXPORT_FLUSH_TIMEOUT_MS)
        return try {
            val accumulator = ExportAccumulator()
            val protectedFiles = DiagnosticFileStore(handle.processDirectory, handle.config)
                .segmentFiles()
                .toMutableList()
            var corruptRecords = 0L
            val allDirectories = processDirectories(handle.rootDirectory)
            for (directory in allDirectories) {
                protectedFiles += DiagnosticFileStore(directory, handle.config).segmentFiles()
            }
            val selectedDirectories = allDirectories.takeLast(MAX_EXPORT_PROCESS_DIRECTORIES)
            for (directory in selectedDirectories) {
                val store = DiagnosticFileStore(directory, handle.config)
                val read = store.readAll()
                accumulator.addAll(read.entries)
                corruptRecords += read.corruptRecords
            }
            // Preserve accepted current-process evidence even when persistence is cooling down or unavailable.
            accumulator.addAll(handle.recorder.memorySnapshot())
            val merged = accumulator.entries()
                .distinctBy { entry -> Triple(entry.processName, entry.sessionId, entry.sequence) }
                .sortedWith(compareBy<DiagnosticEntry> { entry -> entry.timestampMs }.thenBy { entry -> entry.sequence })
            DiagnosticArchiveExporter.export(
                target = targetFile,
                read = DiagnosticReadResult(
                    entries = merged,
                    corruptRecords = corruptRecords,
                    truncated = accumulator.truncated || selectedDirectories.size < allDirectories.size,
                    omittedProcessCount = allDirectories.size - selectedDirectories.size
                ),
                status = handle.recorder.status(),
                protectedFiles = protectedFiles
            )
        } catch (error: Exception) {
            handle.recorder.noteReadFailure(error)
            DiagnosticExportResult(false, null, 0, DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name))
        }
    }

    /** Lists only deterministic process-owned child directories. */
    private fun processDirectories(rootDirectory: File): List<File> {
        return rootDirectory.listFiles { file -> file.isDirectory && file.name.startsWith(PROCESS_DIRECTORY_PREFIX) }
            .orEmpty()
            .sortedWith(compareBy<File> { file -> file.lastModified() }.thenBy(File::getName))
    }

    /** Creates the stable pre-initialization export failure. */
    private fun inactiveExportResult(): DiagnosticExportResult {
        return DiagnosticExportResult(false, null, 0, NOT_INITIALIZED_MESSAGE)
    }

    /** Runtime state required for process-aware support operations. */
    private data class RuntimeHandle(
        /** Current process recorder. */
        val recorder: DiagnosticRecorder,
        /** Shared diagnostics root. */
        val rootDirectory: File,
        /** Current process-owned directory. */
        val processDirectory: File,
        /** Validated storage bounds. */
        val config: DiagnosticsConfig,
        /** Whether storage is partitioned by Android process. */
        val isolatedByProcess: Boolean
    )

    /** Bounded oldest-evicting accumulator for cross-process export evidence. */
    private class ExportAccumulator {
        /** Retained entries in aggregate traversal order. */
        private val retained = ArrayDeque<SizedExportEntry>()
        /** Encoded JSONL bytes retained by [retained]. */
        private var retainedBytes = 0L
        /** Whether any record was omitted by count or byte pressure. */
        var truncated: Boolean = false
            private set

        /** Adds entries while enforcing both aggregate count and byte limits. */
        fun addAll(entries: List<DiagnosticEntry>) {
            for (entry in entries) {
                val encodedBytes = (DiagnosticJsonCodec.encode(entry) + NEWLINE)
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
                if (encodedBytes > MAX_EXPORT_JOURNAL_BYTES) {
                    truncated = true
                    continue
                }
                while (retained.isNotEmpty() &&
                    (retained.size >= MAX_EXPORT_RECORDS || retainedBytes + encodedBytes > MAX_EXPORT_JOURNAL_BYTES)
                ) {
                    // Retain newer traversed evidence when the aggregate budget is exhausted.
                    retainedBytes -= retained.removeFirst().encodedBytes
                    truncated = true
                }
                retained.addLast(SizedExportEntry(entry, encodedBytes))
                retainedBytes += encodedBytes
            }
        }

        /** Returns a stable copy of retained evidence. */
        fun entries(): List<DiagnosticEntry> = retained.map(SizedExportEntry::entry)
    }

    /** Aggregate export entry paired with its encoded byte footprint. */
    private data class SizedExportEntry(
        /** Structured diagnostic entry. */
        val entry: DiagnosticEntry,
        /** Encoded JSONL byte footprint. */
        val encodedBytes: Long
    )

    /** Default public snapshot size. */
    private const val DEFAULT_SNAPSHOT_LIMIT = 100
    /** Export flush bound before including current memory evidence. */
    private const val EXPORT_FLUSH_TIMEOUT_MS = 1_000L
    /** Maximum process directories parsed by one aggregate export. */
    private const val MAX_EXPORT_PROCESS_DIRECTORIES = 16
    /** Maximum records retained in one aggregate export. */
    private const val MAX_EXPORT_RECORDS = 10_000
    /** Maximum uncompressed JSONL bytes retained in one aggregate export. */
    private const val MAX_EXPORT_JOURNAL_BYTES = 16L * 1024L * 1024L
    /** App-private diagnostics directory relative to filesDir. */
    private const val DIAGNOSTICS_DIRECTORY = "android-apm/diagnostics"
    /** Prefix identifying process-owned child directories. */
    private const val PROCESS_DIRECTORY_PREFIX = "process-"
    /** Replacement for unsafe filesystem characters. */
    private const val DIRECTORY_REPLACEMENT = "_"
    /** Fallback prefix for an empty process name. */
    private const val UNKNOWN_PROCESS_PREFIX = "unknown"
    /** Maximum readable process-name prefix length. */
    private const val MAX_PROCESS_PREFIX_CHARS = 48
    /** Digest bytes retained in a process-directory name. */
    private const val PROCESS_HASH_BYTES = 8
    /** SHA-256 algorithm name. */
    private const val SHA_256 = "SHA-256"
    /** Failure returned before diagnostics initialization. */
    private const val NOT_INITIALIZED_MESSAGE = "Diagnostics are not initialized"
    /** JSONL delimiter included in aggregate byte accounting. */
    private const val NEWLINE = "\n"
    /** Characters that are unsafe or ambiguous in process directory names. */
    private val UNSAFE_DIRECTORY_CHARACTERS = Regex("[^A-Za-z0-9._-]")
}
