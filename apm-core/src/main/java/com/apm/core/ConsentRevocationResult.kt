package com.apm.core

/**
 * Outcome of revoking SDK collection consent in the current Android process.
 *
 * @property wasInitialized whether an active SDK runtime was stopped
 * @property discardedQueuedEventCount events removed from the in-memory dispatcher queue
 * @property clearedStoredEventCount durable rows observed immediately before the outbox was cleared;
 * null for stores without a count API
 * @property clearedIpcFileCount ready or temporary cross-process hand-off files removed locally
 * @property ipcFilesCleared whether every observed IPC hand-off file was removed; false when the
 * no-argument API had no active runtime and therefore could not locate app-private storage
 * @property storageCleared whether all located SQLite/file event stores accepted the clear operation;
 * false when the no-argument API had no active runtime and therefore could not locate them
 */
data class ConsentRevocationResult(
    val wasInitialized: Boolean,
    val discardedQueuedEventCount: Int,
    val clearedStoredEventCount: Int?,
    val clearedIpcFileCount: Int,
    val ipcFilesCleared: Boolean,
    val storageCleared: Boolean
)
