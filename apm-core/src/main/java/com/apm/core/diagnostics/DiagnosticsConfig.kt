package com.apm.core.diagnostics

/**
 * Bounded configuration for the independent SDK diagnostics journal.
 */
data class DiagnosticsConfig(
    /** Whether local SDK diagnostics are enabled. */
    val enabled: Boolean = true,
    /** Maximum number of recent records retained in memory. */
    val memoryRecordLimit: Int = DEFAULT_MEMORY_RECORD_LIMIT,
    /** Maximum number of records waiting for file persistence. */
    val writerQueueCapacity: Int = DEFAULT_WRITER_QUEUE_CAPACITY,
    /** Maximum bytes per active or retained JSONL segment. */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Number of JSONL segments retained including the active file. */
    val retainedFileCount: Int = DEFAULT_RETAINED_FILE_COUNT,
    /** Whether bounded exception stack traces are retained. */
    val includeStackTraces: Boolean = true
) {
    init {
        require(memoryRecordLimit in 1..MAX_MEMORY_RECORD_LIMIT) {
            "memoryRecordLimit must be between 1 and $MAX_MEMORY_RECORD_LIMIT"
        }
        require(writerQueueCapacity in 1..MAX_WRITER_QUEUE_CAPACITY) {
            "writerQueueCapacity must be between 1 and $MAX_WRITER_QUEUE_CAPACITY"
        }
        require(maxFileBytes in MIN_FILE_BYTES..MAX_FILE_BYTES) {
            "maxFileBytes must be between $MIN_FILE_BYTES and $MAX_FILE_BYTES"
        }
        require(retainedFileCount in 1..MAX_RETAINED_FILE_COUNT) {
            "retainedFileCount must be between 1 and $MAX_RETAINED_FILE_COUNT"
        }
    }

    private companion object {
        /** Default memory-ring size. */
        private const val DEFAULT_MEMORY_RECORD_LIMIT = 200
        /** Default bounded writer-queue size. */
        private const val DEFAULT_WRITER_QUEUE_CAPACITY = 256
        /** Default per-segment byte budget. */
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
        /** Default retained segment count. */
        private const val DEFAULT_RETAINED_FILE_COUNT = 3
        /** Hard memory-ring limit. */
        private const val MAX_MEMORY_RECORD_LIMIT = 2_000
        /** Hard writer-queue limit. */
        private const val MAX_WRITER_QUEUE_CAPACITY = 4_096
        /** Minimum useful segment size. */
        private const val MIN_FILE_BYTES = 64L * 1024L
        /** Hard per-segment disk limit. */
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        /** Hard retained segment count. */
        private const val MAX_RETAINED_FILE_COUNT = 8
    }
}
