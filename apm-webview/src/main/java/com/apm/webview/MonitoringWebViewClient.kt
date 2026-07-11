package com.apm.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Delegating WebViewClient that observes supported navigation callbacks while
 * preserving host behavior. Hosts receive the same arguments and return paths.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class MonitoringWebViewClient(
    /** Monitoring callback target. */
    private val module: WebviewModule,
    /** Host-owned client whose behavior must be preserved. */
    private val delegate: WebViewClient
) : WebViewClient() {
    /** Records navigation start before forwarding it. */
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        url?.let(module::onPageStarted)
        delegate.onPageStarted(view, url, favicon)
    }

    /** Records navigation completion before forwarding it. */
    override fun onPageFinished(view: WebView, url: String?) {
        url?.let(module::onPageFinished)
        delegate.onPageFinished(view, url)
    }

    /** Forwards URL override decisions for request-based navigation. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        delegate.shouldOverrideUrlLoading(view, request)

    /** Forwards legacy URL override decisions. */
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        delegate.shouldOverrideUrlLoading(view, url)

    /** Records resource start and preserves the host interception response. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        module.getResourceWaterfall()?.onResourceStart(view.url.orEmpty(), request)
        return delegate.shouldInterceptRequest(view, request)
    }

    /** Forwards legacy resource interception. */
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        delegate.shouldInterceptRequest(view, url)

    /** Forwards individual resource load notifications. */
    override fun onLoadResource(view: WebView, url: String?) = delegate.onLoadResource(view, url)

    /** Forwards visible-page commit notifications. */
    override fun onPageCommitVisible(view: WebView, url: String?) {
        url?.let(module::onPageVisible)
        delegate.onPageCommitVisible(view, url)
    }

    /** Forwards modern resource failures. */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
        delegate.onReceivedError(view, request, error)

    /** Forwards legacy resource failures. */
    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) =
        delegate.onReceivedError(view, errorCode, description, failingUrl)

    /** Forwards HTTP failures. */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
        delegate.onReceivedHttpError(view, request, errorResponse)

    /** Forwards form resubmission decisions. */
    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) =
        delegate.onFormResubmission(view, dontResend, resend)

    /** Forwards visited-history updates. */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
        delegate.doUpdateVisitedHistory(view, url, isReload)

    /** Forwards SSL errors to the host policy. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
        delegate.onReceivedSslError(view, handler, error)

    /** Forwards client-certificate requests. */
    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
        delegate.onReceivedClientCertRequest(view, request)

    /** Forwards HTTP authentication requests. */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
        delegate.onReceivedHttpAuthRequest(view, handler, host, realm)

    /** Forwards key override decisions. */
    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean =
        delegate.shouldOverrideKeyEvent(view, event)

    /** Forwards unhandled key events. */
    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) = delegate.onUnhandledKeyEvent(view, event)

    /** Forwards scale changes. */
    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
        delegate.onScaleChanged(view, oldScale, newScale)

    /** Forwards login requests. */
    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) =
        delegate.onReceivedLoginRequest(view, realm, account, args)

    /** Forwards legacy redirect handling. */
    @Suppress("DEPRECATION")
    override fun onTooManyRedirects(view: WebView, cancelMsg: Message, continueMsg: Message) =
        delegate.onTooManyRedirects(view, cancelMsg, continueMsg)
}
