package com.apm.network

import com.apm.core.Apm
import com.apm.core.ApmClock
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility interceptor. Prefer ApmEventListener.factory for complete OkHttp call timing.
 * When paired with a summary-owning listener this only records integration evidence. Standalone
 * use observes response-body completion/failure/close, never declares success at response headers.
 */
class ApmNetworkInterceptor(
    /** Module receiving final summaries for standalone compatibility use. */
    private val networkModule: NetworkModule
) : Interceptor {
    /** Preserves the host response and exception while deferring final statistics until body completion. */
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        networkModule.recordIntegrationObservation()
        // Capture ownership before proceed: callFailed may run before proceed throws to this frame.
        if (ListenerSummaryOwners.owns(chain.call(), networkModule)) return chain.proceed(chain.request())
        val request = chain.request()
        val startTimeMs = ApmClock.monotonicTimeMillis()
        val completed = AtomicBoolean()
        var statusCode = STATUS_CODE_NETWORK_ERROR
        var responseBytes = 0L
        val requestBytes = try { request.body?.contentLength() ?: 0L } catch (_: Exception) { 0L }

        /** Reports once even when read failure is followed by close. */
        fun finish(error: IOException? = null) {
            if (!completed.compareAndSet(false, true)) return
            try {
                networkModule.onRequestComplete(
                    url = request.url.toString(), method = request.method,
                    statusCode = if (error == null) statusCode else STATUS_CODE_NETWORK_ERROR,
                    durationMs = ApmClock.elapsedMillisSince(startTimeMs),
                    requestSize = requestBytes, responseSize = responseBytes,
                    error = error?.let { it.message ?: it.javaClass.simpleName }
                )
            } catch (failure: Exception) {
                // Recoverable telemetry failure cannot replace the host's body read result.
                Apm.recordInternalError(ERROR_REPORT, failure)
            }
        }

        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            finish(error)
            throw error
        }
        statusCode = response.code
        val body = response.body
        if (body == null || body.contentLength() == 0L) {
            finish()
            return response
        }
        val observed = object : ResponseBody() {
            /** One buffered source preserves normal OkHttp streaming and backpressure. */
            private val observedSource = object : ForwardingSource(body.source()) {
                /** Counts consumed bytes and reports EOF or transport failure exactly once. */
                override fun read(sink: Buffer, byteCount: Long): Long {
                    return try {
                        val read = super.read(sink, byteCount)
                        if (read > 0L) responseBytes += read
                        if (read == -1L || (body.contentLength() >= 0L && responseBytes >= body.contentLength())) finish()
                        read
                    } catch (error: IOException) {
                        finish(error)
                        throw error
                    }
                }

                /** Explicit early close is terminal for this compatibility observation. */
                override fun close() {
                    try {
                        super.close()
                        finish()
                    } catch (error: IOException) {
                        finish(error)
                        throw error
                    }
                }
            }.buffer()

            /** Keeps host media type semantics. */
            override fun contentType() = body.contentType()
            /** Keeps declared content length semantics. */
            override fun contentLength() = body.contentLength()
            /** Returns the same buffered wrapper for every caller. */
            override fun source(): BufferedSource = observedSource
        }
        return response.newBuilder().body(observed).build()
    }

    companion object {
        /** Non-HTTP transport failure sentinel. */
        private const val STATUS_CODE_NETWORK_ERROR = -1
        /** Payload-free telemetry failure tag. */
        private const val ERROR_REPORT = "network_interceptor_report"
    }
}
