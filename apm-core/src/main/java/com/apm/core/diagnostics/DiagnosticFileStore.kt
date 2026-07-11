package com.apm.core.diagnostics

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

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
    val corruptRecords: Long
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
            val temporary = File(target.parentFile ?: directory, target.name + EXPORT_TEMP_SUFFIX)
            return try {
                ensureDirectory()
                target.parentFile?.let(::ensureDirectory)
                // Export only decoded entries so corrupt persisted tails never contaminate the support package.
                val read = readAllLocked()
                if (temporary.exists() && !temporary.delete()) {
                    throw IOException("Unable to replace temporary diagnostics export")
                }
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                    writeZipEntry(zip, ZIP_MANIFEST_NAME, manifest(status, read).toString())
                    val jsonl = read.entries.joinToString(separator = NEWLINE, postfix = if (read.entries.isEmpty()) "" else NEWLINE) {
                        entry -> DiagnosticJsonCodec.encode(entry)
                    }
                    writeZipEntry(zip, ZIP_JOURNAL_NAME, jsonl)
                }
                replaceTarget(temporary, target)
                DiagnosticExportResult(
                    success = true,
                    file = target,
                    exportedRecords = read.entries.size,
                    errorMessage = null
                )
            } catch (error: Exception) {
                // Export is an explicit host support action, but it still must return failure as data.
                temporary.delete()
                DiagnosticExportResult(
                    success = false,
                    file = null,
                    exportedRecords = 0,
                    errorMessage = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name)
                )
            }
        }
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

    /** Replaces the requested export target after the ZIP has closed successfully. */
    private fun replaceTarget(temporary: File, target: File) {
        deleteOrThrow(target)
        if (temporary.renameTo(target)) {
            return
        }
        temporary.copyTo(target, overwrite = true)
        if (!temporary.delete()) {
            throw IOException("Unable to remove temporary diagnostics export")
        }
    }

    /** Writes one UTF-8 ZIP entry. */
    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Creates a controlled export manifest without host business context. */
    private fun manifest(status: DiagnosticStatus, read: DiagnosticReadResult): JSONObject {
        return JSONObject()
            .put(MANIFEST_FORMAT_VERSION, EXPORT_FORMAT_VERSION)
            .put(MANIFEST_EXPORTED_AT_MS, System.currentTimeMillis())
            .put(MANIFEST_EXPORTED_RECORDS, read.entries.size)
            .put(MANIFEST_CORRUPT_RECORDS, read.corruptRecords)
            .put(MANIFEST_FILE_SINK_HEALTHY, status.fileSinkHealthy)
            .put(MANIFEST_DROPPED_RECORDS, status.droppedRecords)
            .put(MANIFEST_WRITE_FAILURES, status.writeFailures)
            .put(MANIFEST_LAST_FAILURE, status.lastFailure ?: JSONObject.NULL)
    }

    private companion object {
        /** Active JSONL segment name. */
        private const val ACTIVE_FILE_NAME = "diagnostics.jsonl"
        /** ZIP entry containing export metadata. */
        private const val ZIP_MANIFEST_NAME = "manifest.json"
        /** ZIP entry containing merged readable records. */
        private const val ZIP_JOURNAL_NAME = "diagnostics.jsonl"
        /** Temporary export suffix. */
        private const val EXPORT_TEMP_SUFFIX = ".tmp"
        /** Platform-independent JSONL separator. */
        private const val NEWLINE = "\n"
        /** Export manifest format version. */
        private const val EXPORT_FORMAT_VERSION = 1
        /** Manifest field: format version. */
        private const val MANIFEST_FORMAT_VERSION = "formatVersion"
        /** Manifest field: export timestamp. */
        private const val MANIFEST_EXPORTED_AT_MS = "exportedAtMs"
        /** Manifest field: readable exported records. */
        private const val MANIFEST_EXPORTED_RECORDS = "exportedRecords"
        /** Manifest field: corrupt skipped records. */
        private const val MANIFEST_CORRUPT_RECORDS = "corruptRecords"
        /** Manifest field: file-sink health. */
        private const val MANIFEST_FILE_SINK_HEALTHY = "fileSinkHealthy"
        /** Manifest field: dropped records. */
        private const val MANIFEST_DROPPED_RECORDS = "droppedRecords"
        /** Manifest field: file write failures. */
        private const val MANIFEST_WRITE_FAILURES = "writeFailures"
        /** Manifest field: last sanitized sink failure. */
        private const val MANIFEST_LAST_FAILURE = "lastFailure"
    }
}
