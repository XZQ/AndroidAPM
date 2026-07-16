package com.apm.uploader

/**
 * Resolves the HTTP upload endpoint immediately before each request.
 *
 * Implementations should return [defaultEndpoint] when no verified remote override is active.
 * [HttpApmUploader] accepts remote changes only when they use HTTPS and contain no user info.
 */
fun interface HttpEndpointProvider {

    /**
     * Returns the endpoint snapshot for one request.
     *
     * @param defaultEndpoint application-bundled bootstrap endpoint
     * @return candidate endpoint for the current request
     */
    fun currentEndpoint(defaultEndpoint: String): String

    companion object {
        /** Provider that always retains the application-bundled endpoint. */
        val DEFAULT = HttpEndpointProvider { defaultEndpoint -> defaultEndpoint }
    }
}
