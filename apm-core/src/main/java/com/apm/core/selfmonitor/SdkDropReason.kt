package com.apm.core.selfmonitor

/** Stable, payload-free reasons explaining where an accepted telemetry attempt was lost. */
enum class SdkDropReason {
    /** Compatibility fallback for callers that have not supplied a more specific reason. */
    UNCLASSIFIED,
    /** Dispatcher was already stopping and could not accept new work. */
    DISPATCHER_SHUTDOWN,
    /** A producer refused to wait for the dispatcher admission lock. */
    DISPATCHER_ADMISSION_BUSY,
    /** The bounded dispatcher queue had no lower-priority victim or free slot. */
    DISPATCHER_QUEUE_FULL,
    /** The dispatcher retained-byte budget could not admit the event. */
    DISPATCHER_BYTE_BUDGET,
    /** An older lower-priority queued event was evicted for more valuable work. */
    DISPATCHER_PRIORITY_EVICTION,
    /** One NORMAL/LOW module exceeded its protected high-water queue share. */
    DISPATCHER_MODULE_ISOLATION,
    /** Lazy construction, aggregation, sanitization, or another event-processing step failed. */
    EVENT_PROCESSING_FAILURE,
    /** Signed dynamic sampling excluded the event. */
    DYNAMIC_SAMPLING,
    /** The effective event rate limit rejected the event. */
    RATE_LIMIT,
    /** Durable encoding or the configured per-event payload limit rejected the event. */
    STORAGE_PAYLOAD_REJECTED,
    /** Durable row-count or live-payload pressure evicted historical events. */
    STORAGE_CAPACITY_EVICTED,
    /** A recoverable storage exception prevented local hand-off. */
    STORAGE_FAILURE,
    /** A non-durable transport rejected an event after local processing. */
    UPLOADER_REJECTED,
    /** A durable row exceeded its retry allowance or retention age. */
    OUTBOX_EXPIRED_OR_RETRY_EXHAUSTED,
    /** Consent revocation intentionally erased queued telemetry. */
    CONSENT_REVOKED,
    /** A non-uploader process could not publish a critical IPC hand-off file. */
    IPC_HANDOFF_FAILURE,
    /** A non-uploader process exhausted its pending-event retained-byte budget. */
    IPC_PENDING_BYTE_BUDGET,
    /** One encoded event or atomic IPC file exceeded its configured byte budget. */
    IPC_FILE_BYTE_BUDGET,
    /** Published IPC files exhausted the process-shared directory byte budget. */
    IPC_DIRECTORY_BYTE_BUDGET
}
