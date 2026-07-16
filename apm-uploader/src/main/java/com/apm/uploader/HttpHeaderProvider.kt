package com.apm.uploader

/**
 * Provides a fresh HTTP header snapshot for every upload request.
 *
 * Implementations may read a short-lived access token from an in-memory credential manager.
 * The provider runs on the uploader worker thread and must return quickly without performing an
 * unbounded network refresh. Throwing fails the current upload safely so the durable outbox can
 * retry after the credential manager has refreshed.
 */
fun interface HttpHeaderProvider {

    /**
     * Returns headers for the current request without exposing long-lived credentials to config.
     *
     * @return immutable or caller-owned header map for one request
     */
    fun currentHeaders(): Map<String, String>

    companion object {
        /** Empty provider used when dynamic authentication is not configured. */
        val EMPTY: HttpHeaderProvider = HttpHeaderProvider { emptyMap() }
    }
}
