package com.apm.webview

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView

/** Delegating WebChromeClient that observes console failures without replacing host behavior. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class MonitoringWebChromeClient(
    /** Monitoring callback target. */
    private val module: WebviewModule,
    /** Host-owned client whose behavior must be preserved. */
    private val delegate: WebChromeClient
) : WebChromeClient() {
    /** Records JavaScript console errors and preserves the host decision. */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            module.onJsConsoleError(
                url = consoleMessage.sourceId().orEmpty(),
                errorMessage = consoleMessage.message().orEmpty(),
                sourceUrl = consoleMessage.sourceId(),
                line = consoleMessage.lineNumber()
            )
        }
        return delegate.onConsoleMessage(consoleMessage)
    }

    /** Forwards legacy console messages. */
    @Suppress("DEPRECATION")
    override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) =
        delegate.onConsoleMessage(message, lineNumber, sourceID)

    /** Forwards progress changes. */
    override fun onProgressChanged(view: WebView, newProgress: Int) = delegate.onProgressChanged(view, newProgress)

    /** Forwards title changes. */
    override fun onReceivedTitle(view: WebView, title: String?) = delegate.onReceivedTitle(view, title)

    /** Forwards favicon changes. */
    override fun onReceivedIcon(view: WebView, icon: Bitmap?) = delegate.onReceivedIcon(view, icon)

    /** Forwards touch-icon changes. */
    override fun onReceivedTouchIconUrl(view: WebView, url: String?, precomposed: Boolean) =
        delegate.onReceivedTouchIconUrl(view, url, precomposed)

    /** Forwards custom-view presentation. */
    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        delegate.onShowCustomView(view, callback)

    /** Forwards legacy custom-view presentation. */
    @Suppress("DEPRECATION")
    override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) =
        delegate.onShowCustomView(view, requestedOrientation, callback)

    /** Forwards custom-view dismissal. */
    override fun onHideCustomView() = delegate.onHideCustomView()

    /** Forwards window-creation decisions. */
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        delegate.onCreateWindow(view, isDialog, isUserGesture, resultMsg)

    /** Forwards window-focus requests. */
    override fun onRequestFocus(view: WebView) = delegate.onRequestFocus(view)

    /** Forwards window-close requests. */
    override fun onCloseWindow(window: WebView) = delegate.onCloseWindow(window)

    /** Forwards JavaScript alerts. */
    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        delegate.onJsAlert(view, url, message, result)

    /** Forwards JavaScript confirmations. */
    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        delegate.onJsConfirm(view, url, message, result)

    /** Forwards JavaScript prompts. */
    override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
        delegate.onJsPrompt(view, url, message, defaultValue, result)

    /** Forwards before-unload confirmations. */
    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        delegate.onJsBeforeUnload(view, url, message, result)

    /** Forwards geolocation prompts. */
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) =
        delegate.onGeolocationPermissionsShowPrompt(origin, callback)

    /** Forwards geolocation prompt dismissal. */
    override fun onGeolocationPermissionsHidePrompt() = delegate.onGeolocationPermissionsHidePrompt()

    /** Forwards runtime permission requests. */
    override fun onPermissionRequest(request: PermissionRequest) = delegate.onPermissionRequest(request)

    /** Forwards cancelled runtime permission requests. */
    override fun onPermissionRequestCanceled(request: PermissionRequest) = delegate.onPermissionRequestCanceled(request)

    /** Forwards JavaScript timeout decisions. */
    @Suppress("DEPRECATION")
    override fun onJsTimeout(): Boolean = delegate.onJsTimeout()

    /** Forwards default video-poster lookup. */
    override fun getDefaultVideoPoster(): Bitmap? = delegate.defaultVideoPoster

    /** Forwards video-loading view lookup. */
    override fun getVideoLoadingProgressView(): View? = delegate.videoLoadingProgressView

    /** Forwards visited-history lookup. */
    override fun getVisitedHistory(callback: ValueCallback<Array<String>>) = delegate.getVisitedHistory(callback)

    /** Forwards file chooser requests. */
    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = delegate.onShowFileChooser(webView, filePathCallback, fileChooserParams)

    /** Forwards legacy database quota decisions. */
    @Suppress("DEPRECATION")
    override fun onExceededDatabaseQuota(
        url: String?,
        databaseIdentifier: String?,
        quota: Long,
        estimatedDatabaseSize: Long,
        totalQuota: Long,
        quotaUpdater: WebStorage.QuotaUpdater?
    ) = delegate.onExceededDatabaseQuota(
        url,
        databaseIdentifier,
        quota,
        estimatedDatabaseSize,
        totalQuota,
        quotaUpdater
    )
}
