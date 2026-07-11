package com.apm.core.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Storage contract used by the recorder and deterministic failure tests. */
internal interface DiagnosticStore {
    /** Appends one sanitized record. */
    fun append(entry: DiagnosticEntry)
    /** Reads every retained readable record in persisted order. */
    fun readAll(): DiagnosticReadResult
    /** Returns the current retained JSONL bytes. */
    fun retainedBytes(): Long
    /** Exports a controlled ZIP package. */
    fun exportTo(target: File, status: DiagnosticStatus): DiagnosticExportResult
    /** Removes all retained JSONL segments. */
    fun clear(): Boolean
}

/** Result of reading retained diagnostic segments. */
internal data class DiagnosticReadResult(
    /** Readable entries in persisted order. */
    val entries: List<DiagnosticEntry>,
    /** Corrupt lines skipped during the read. */
    val corruptRecords: Long,
    /** Whether a higher-level aggregate export omitted older evidence to remain bounded. */
    val truncated: Boolean = false,
    /** Number of process directories omitted by the aggregate export bound. */
    val omittedProcessCount: Int = 0
)

/**
 * App-private bounded JSONL store with deterministic rolling segments.
 */
internal class DiagnosticFileStore(
    /** Directory dedicated to AndroidAPM diagnostic files. */
    private val directory: File,
    /** Resource bounds for segment size and retention. */
    private val config: DiagnosticsConfig
) : DiagnosticStore {

    /** File-operation lock independent of APM event-store locks. */
    private val lock = Any()

    /** Appends one JSONL entry and rotates before crossing the segment budget. */
    override fun append(entry: DiagnosticEntry) {
        synchronized(lock) {
            ensureDirectory()
            val line = DiagnosticJsonCodec.encode(entry) + NEWLINE
            val encodedBytes = line.toByteArray(Charsets.UTF_8)
            val active = segmentFile(0)
            // Rotate before appending so every segment stays within the configured hard bound.
            if (active.exists() && active.length() > 0L && active.length() + encodedBytes.size > config.maxFileBytes) {
                rotateLocked()
            }
            FileOutputStream(segmentFile(0), true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(line)
            }
        }
    }

    /** Reads all existing segments oldest first and skips isolated corrupt lines. */
    override fun readAll(): DiagnosticReadResult {
        synchronized(lock) {
            return readAllLocked()
        }
    }

    /** Totals only retained JSONL segment bytes. */
    override fun retainedBytes(): Long {
        synchronized(lock) {
            return existingSegmentsLocked().sumOf { file -> file.length() }
        }
    }

    /** Exports a stable manifest and merged readable JSONL journal. */
    override fun exportTo(target: File, status: DiagnosticStatus): DiagnosticExportResult {
        synchronized(lock) {
            return try {
                ensureDirectory()
                // Export only decoded entries so corrupt persisted tails never contaminate the support package.
                val read = readAllLocked()
                DiagnosticArchiveExporter.export(target, read, status, segmentFiles())
            } catch (error: Exception) {
                // Export is an explicit host support action, but it still must return failure as data.
                DiagnosticExportResult(
                    success = false,
                    file = null,
                    exportedRecords = 0,
                    errorMessage = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name)
                )
            }
        }
    }

    /** Returns every possible journal segment path for export collision protection. */
    internal fun segmentFiles(): List<File> {
        return (0 until config.retainedFileCount).map(::segmentFile)
    }

    /** Deletes every known JSONL segment without touching exported ZIP packages. */
    override fun clear(): Boolean {
        synchronized(lock) {
            var cleared = true
            for (file in existingSegmentsLocked()) {
                // Continue after one deletion failure so the method removes as much stale data as possible.
                if (file.exists() && !file.delete()) {
                    cleared = false
                }
            }
            return cleared
        }
    }

    /** Reads segments while the caller owns [lock]. */
    private fun readAllLocked(): DiagnosticReadResult {
        val entries = mutableListOf<DiagnosticEntry>()
        var corruptRecords = 0L
        for (file in existingSegmentsOldestFirstLocked()) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val decoded = DiagnosticJsonCodec.decode(line)
                    if (decoded == null) {
                        // One bad line is isolated; later valid lines and segments remain readable.
                        corruptRecords++
                    } else {
                        entries += decoded
                    }
                }
            }
        }
        return DiagnosticReadResult(entries = entries, corruptRecords = corruptRecords)
    }

    /** Rotates active and retained segments while the caller owns [lock]. */
    private fun rotateLocked() {
        if (config.retainedFileCount == 1) {
            // Single-segment mode discards the full active file before accepting the new entry.
            deleteOrThrow(segmentFile(0))
            return
        }
        for (targetIndex in config.retainedFileCount - 1 downTo 1) {
            val source = segmentFile(targetIndex - 1)
            if (!source.exists()) {
                continue
            }
            val target = segmentFile(targetIndex)
            deleteOrThrow(target)
            moveOrCopy(source, target)
        }
    }

    /** Returns all existing segments from active to oldest. */
    private fun existingSegmentsLocked(): List<File> {
        return (0 until config.retainedFileCount)
            .map(::segmentFile)
            .filter(File::exists)
    }

    /** Returns all existing segments from oldest to active. */
    private fun existingSegmentsOldestFirstLocked(): List<File> {
        return (config.retainedFileCount - 1 downTo 0)
            .map(::segmentFile)
            .filter(File::exists)
    }

    /** Resolves the active file at index zero and numbered retained files thereafter. */
    private fun segmentFile(index: Int): File {
        val name = if (index == 0) ACTIVE_FILE_NAME else "diagnostics.$index.jsonl"
        return File(directory, name)
    }

    /** Creates the diagnostics directory or fails with an actionable IO error. */
    private fun ensureDirectory() {
        ensureDirectory(directory)
    }

    /** Creates one required directory hierarchy. */
    private fun ensureDirectory(required: File) {
        if (!required.exists() && !required.mkdirs()) {
            throw IOException("Unable to create diagnostics directory")
        }
        if (!required.isDirectory) {
            throw IOException("Diagnostics path is not a directory")
        }
    }

    /** Deletes an existing file or reports a rotation failure. */
    private fun deleteOrThrow(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to delete diagnostic segment")
        }
    }

    /** Moves a segment and falls back to copy/delete when rename is unavailable. */
    private fun moveOrCopy(source: File, target: File) {
        if (source.renameTo(target)) {
            return
        }
        source.copyTo(target, overwrite = true)
        if (!source.delete()) {
            throw IOException("Unable to remove diagnostic segment after copy")
        }
    }

    private companion object {
        /** Active JSONL segment name. */
        private const val ACTIVE_FILE_NAME = "diagnostics.jsonl"
        /** Platform-independent JSONL separator. */
        private const val NEWLINE = "\n"
    }
}
