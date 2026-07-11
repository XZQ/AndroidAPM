package com.apm.webview

import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies explicit, reversible WebView instrumentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebviewInstallationTest {
    /** Install wraps both clients and uninstall restores host delegates. */
    @Test
    fun `install and uninstall preserve host clients`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val hostClient = WebViewClient()
        val hostChrome = WebChromeClient()
        val module = WebviewModule()

        module.install(webView, hostClient, hostChrome)
        assertTrue(webView.webViewClient is MonitoringWebViewClient)
        assertTrue(webView.webChromeClient is MonitoringWebChromeClient)

        assertTrue(module.uninstall(webView))
        assertSame(hostClient, webView.webViewClient)
        assertSame(hostChrome, webView.webChromeClient)
    }

    /** Navigation callbacks reach the original host client exactly once. */
    @Test
    fun `wrapped navigation client preserves callback`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        var starts = 0
        val hostClient = object : WebViewClient() {
            /** Counts forwarded navigation callbacks. */
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                starts += 1
            }
        }
        val wrapped = WebviewModule().wrapWebViewClient(hostClient)

        wrapped.onPageStarted(webView, "https://example.com", null)

        assertEquals(1, starts)
    }

    /** Console callbacks preserve the original host decision. */
    @Test
    fun `wrapped chrome client preserves console callback`() {
        var messages = 0
        val hostChrome = object : WebChromeClient() {
            /** Counts and accepts forwarded console messages. */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                messages += 1
                return true
            }
        }
        val wrapped = WebviewModule().wrapWebChromeClient(hostChrome)

        val accepted = wrapped.onConsoleMessage(
            ConsoleMessage("boom", "app.js", 7, ConsoleMessage.MessageLevel.ERROR)
        )

        assertTrue(accepted)
        assertEquals(1, messages)
    }
}
