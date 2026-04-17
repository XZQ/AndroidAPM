package com.didi.apm.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp 拦截器，自动采集网络请求性能数据。
 * 集成到 OkHttp Client 后，每个请求完成时自动回调 NetworkModule。
 *
 * 使用方式：
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(ApmNetworkInterceptor(networkModule))
 *     .build()
 * ```
 */
class ApmNetworkInterceptor(
    /** 网络监控模块引用。 */
    private val networkModule: NetworkModule
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTimeMs = System.currentTimeMillis()

        try {
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startTimeMs

            // 请求成功，上报
            networkModule.onRequestComplete(
                url = request.url.toString(),
                method = request.method,
                statusCode = response.code,
                durationMs = durationMs,
                requestSize = request.body?.contentLength() ?: 0,
                responseSize = response.body?.contentLength() ?: 0
            )

            return response
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startTimeMs

            // 请求失败，上报错误
            networkModule.onRequestComplete(
                url = request.url.toString(),
                method = request.method,
                statusCode = STATUS_CODE_NETWORK_ERROR,
                durationMs = durationMs,
                error = e.message ?: e.javaClass.simpleName
            )

            throw e
        }
    }

    companion object {
        /** 网络错误状态码（非 HTTP 标准码）。 */
        private const val STATUS_CODE_NETWORK_ERROR = -1
    }
}
