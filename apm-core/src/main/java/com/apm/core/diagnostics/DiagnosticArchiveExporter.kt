package com.apm.core.diagnostics

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Writes bounded support archives from already decoded diagnostic evidence. */
internal object DiagnosticArchiveExporter {

    /**
     * Writes one archive without allowing the destination to replace a live source segment.
     *
     * @param target requested ZIP destination
     * @param read decoded entries and corrupt-line count
     * @param status current diagnostics health totals
     * @param protectedFiles journal files that must not be replaced
     */
    fun export(
        target: File,
        read: DiagnosticReadResult,
        status: DiagnosticStatus,
        protectedFiles: Collection<File>
    ): DiagnosticExportResult {
        val temporary = File(target.parentFile ?: File("."), target.name + EXPORT_TEMP_SUFFIX)
        return try {
            ensureDirectory(target.parentFile)
            rejectProtectedTarget(target, protectedFiles)
            // Remove a stale temporary archive only after validating the requested destination.
            if (temporary.exists() && !temporary.delete()) {
                throw IOException("Unable to replace temporary diagnostics export")
            }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                writeZipEntry(zip, ZIP_MANIFEST_NAME, manifest(status, read).toString())
                val jsonl = read.entries.joinToString(
                    separator = NEWLINE,
                    postfix = if (read.entries.isEmpty()) "" else NEWLINE
                ) { entry -> DiagnosticJsonCodec.encode(entry) }
                writeZipEntry(zip, ZIP_JOURNAL_NAME, jsonl)
            }
            replaceTarget(temporary, target)
            DiagnosticExportResult(true, target, read.entries.size, null)
        } catch (error: Exception) {
            // Export failures are returned as data and never fed into the diagnostics logger.
            temporary.delete()
            DiagnosticExportResult(
                success = false,
                file = null,
                exportedRecords = 0,
                errorMessage = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.name)
            )
        }
    }

    /** Rejects canonical destination collisions with active or rotated journals. */
    private fun rejectProtectedTarget(target: File, protectedFiles: Collection<File>) {
        val targetPath = target.canonicalFile
        if (protectedFiles.any { file -> file.canonicalFile == targetPath }) {
            throw IOException("Diagnostics export target overlaps a live journal segment")
        }
    }

    /** Creates an optional destination directory hierarchy. */
    private fun ensureDirectory(directory: File?) {
        if (directory == null) {
            return
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create diagnostics export directory")
        }
        if (!directory.isDirectory) {
            throw IOException("Diagnostics export parent is not a directory")
        }
    }

    /** Atomically replaces the target where possible and falls back to copy/delete. */
    private fun replaceTarget(temporary: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("Unable to replace diagnostics export target")
        }
        if (temporary.renameTo(target)) {
            return
        }
        temporary.copyTo(target, overwrite = true)
        if (!temporary.delete()) {
            throw IOException("Unable to remove temporary diagnostics export")
        }
    }

    /** Writes one UTF-8 ZIP member. */
    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Creates the privacy-controlled support manifest. */
    private fun manifest(status: DiagnosticStatus, read: DiagnosticReadResult): JSONObject {
        val processNames = read.entries.map(DiagnosticEntry::processName).distinct().sorted()
        val sessionIds = read.entries.map(DiagnosticEntry::sessionId).distinct().sorted()
        return JSONObject()
            .put(MANIFEST_FORMAT_VERSION, EXPORT_FORMAT_VERSION)
            .put(MANIFEST_SDK_VERSION, SDK_VERSION)
            .put(MANIFEST_EXPORTED_AT_MS, System.currentTimeMillis())
            .put(MANIFEST_PROCESS_NAMES, JSONArray(processNames))
            .put(MANIFEST_SESSION_IDS, JSONArray(sessionIds))
            .put(MANIFEST_EXPORTED_RECORDS, read.entries.size)
            .put(MANIFEST_CORRUPT_RECORDS, read.corruptRecords)
            .put(MANIFEST_TRUNCATED, read.truncated)
            .put(MANIFEST_OMITTED_PROCESS_COUNT, read.omittedProcessCount)
            .put(MANIFEST_FILE_SINK_HEALTHY, status.fileSinkHealthy)
            .put(MANIFEST_DROPPED_RECORDS, status.droppedRecords)
            .put(MANIFEST_READ_FAILURES, status.readFailures)
            .put(MANIFEST_WRITE_FAILURES, status.writeFailures)
            .put(MANIFEST_LAST_FAILURE, status.lastFailure ?: JSONObject.NULL)
    }

    /** Temporary export suffix. */
    private const val EXPORT_TEMP_SUFFIX = ".tmp"
    /** ZIP manifest member. */
    private const val ZIP_MANIFEST_NAME = "manifest.json"
    /** ZIP merged journal member. */
    private const val ZIP_JOURNAL_NAME = "diagnostics.jsonl"
    /** Platform-independent JSONL separator. */
    private const val NEWLINE = "\n"
    /** Export schema version. */
    private const val EXPORT_FORMAT_VERSION = 2
    /** Published SDK version. */
    private const val SDK_VERSION = "0.1.0"
    /** Manifest field: format version. */
    private const val MANIFEST_FORMAT_VERSION = "formatVersion"
    /** Manifest field: SDK version. */
    private const val MANIFEST_SDK_VERSION = "sdkVersion"
    /** Manifest field: export timestamp. */
    private const val MANIFEST_EXPORTED_AT_MS = "exportedAtMs"
    /** Manifest field: contributing process names. */
    private const val MANIFEST_PROCESS_NAMES = "processNames"
    /** Manifest field: contributing session identifiers. */
    private const val MANIFEST_SESSION_IDS = "sessionIds"
    /** Manifest field: readable exported record count. */
    private const val MANIFEST_EXPORTED_RECORDS = "exportedRecords"
    /** Manifest field: corrupt skipped record count. */
    private const val MANIFEST_CORRUPT_RECORDS = "corruptRecords"
    /** Manifest field: whether bounded aggregation omitted older evidence. */
    private const val MANIFEST_TRUNCATED = "truncated"
    /** Manifest field: process directories omitted by the aggregate bound. */
    private const val MANIFEST_OMITTED_PROCESS_COUNT = "omittedProcessCount"
    /** Manifest field: write-sink health. */
    private const val MANIFEST_FILE_SINK_HEALTHY = "fileSinkHealthy"
    /** Manifest field: bounded-buffer losses. */
    private const val MANIFEST_DROPPED_RECORDS = "droppedRecords"
    /** Manifest field: journal read failures. */
    private const val MANIFEST_READ_FAILURES = "readFailures"
    /** Manifest field: journal write failures. */
    private const val MANIFEST_WRITE_FAILURES = "writeFailures"
    /** Manifest field: last sanitized failure. */
    private const val MANIFEST_LAST_FAILURE = "lastFailure"
}
