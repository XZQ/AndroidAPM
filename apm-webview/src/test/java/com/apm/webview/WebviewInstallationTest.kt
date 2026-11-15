package com.apm.webview

import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Looper
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /** Uninstall from a non-creation thread posts client restoration back to the install thread. */
    @Test
    fun `off-thread uninstall restores clients on the install thread`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val hostClient = WebViewClient()
        val hostChrome = WebChromeClient()
        val module = WebviewModule()
        module.install(webView, hostClient, hostChrome)

        val uninstaller = Thread { module.uninstall(webView) }.apply { isDaemon = true; start() }
        uninstaller.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        assertFalse(uninstaller.isAlive)

        // 恢复被 post 回安装线程；在 Robolectric 主 Looper 上执行后才真正还原宿主客户端。
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
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

    /** Renderer failure handling must retain the host's true return to avoid escalating into app death. */
    @Test
    fun `wrapped client preserves renderer exit recovery`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val detail = object : RenderProcessGoneDetail() {
            /** Models a renderer crash handled by the host. */
            override fun didCrash(): Boolean = true
            /** Uses a supported renderer priority. */
            override fun rendererPriorityAtExit(): Int = WebView.RENDERER_PRIORITY_BOUND
        }
        var callbacks = 0
        val host = object : WebViewClient() {
            /** Handles this precise renderer and returns the host's recovery decision. */
            override fun onRenderProcessGone(view: WebView, received: RenderProcessGoneDetail): Boolean {
                assertSame(webView, view)
                assertSame(detail, received)
                callbacks++
                return true
            }
        }

        assertTrue(WebviewModule().wrapWebViewClient(host).onRenderProcessGone(webView, detail))
        assertEquals(1, callbacks)
    }

    /** Safe Browsing callbacks and response policies must reach the original host client unchanged. */
    @Test
    fun `wrapped client preserves safe browsing callback`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val request = TestResourceRequest(RESOURCE_URL)
        var returnedToSafety = false
        val response = object : SafeBrowsingResponse() {
            /** Fails if wrapper substitutes the platform default warning. */
            override fun showInterstitial(allowReporting: Boolean) = error("Unexpected interstitial")
            /** Fails if wrapper changes the host's decision to proceed. */
            override fun proceed(report: Boolean) = error("Unexpected proceed")
            /** Records the host's selected response. */
            override fun backToSafety(report: Boolean) {
                assertFalse(report)
                returnedToSafety = true
            }
        }
        var callbacks = 0
        val host = object : WebViewClient() {
            /** Receives the same request, threat, and response object as the platform callback. */
            override fun onSafeBrowsingHit(
                view: WebView,
                received: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                assertSame(webView, view)
                assertSame(request, received)
                assertEquals(WebViewClient.SAFE_BROWSING_THREAT_MALWARE, threatType)
                assertSame(response, callback)
                callbacks++
                callback.backToSafety(false)
            }
        }

        WebviewModule().wrapWebViewClient(host).onSafeBrowsingHit(
            webView, request, WebViewClient.SAFE_BROWSING_THREAT_MALWARE, response
        )
        assertEquals(1, callbacks)
        assertTrue(returnedToSafety)
    }

    /** Background interception uses the navigation snapshot instead of reading UI-only WebView state. */
    @Test
    fun `resource interception reads frozen navigation url and preserves response`() {
        val webView = object : WebView(RuntimeEnvironment.getApplication()) {
            /** Detects even attempted URL access on the resource thread. */
            override fun getUrl(): String? {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Background WebView URL read" }
                return DIFFERENT_PAGE_URL
            }
        }
        val request = TestResourceRequest(RESOURCE_URL)
        val response = WebResourceResponse(MIME_TEXT, ENCODING_UTF8, ByteArrayInputStream(ByteArray(0)))
        val host = object : WebViewClient() {
            /** Returns the exact host-owned resource response. */
            override fun shouldInterceptRequest(view: WebView, received: WebResourceRequest): WebResourceResponse? {
                assertSame(webView, view)
                assertSame(request, received)
                return response
            }
        }
        val module = WebviewModule(WebviewConfig(whiteScreenThresholdMs = LONG_VISIBILITY_TIMEOUT_MS))
        module.onStart()
        try {
            val wrapped = module.wrapWebViewClient(host)
            wrapped.onPageStarted(webView, PAGE_URL, null)
            val result = AtomicReference<WebResourceResponse>()
            val failure = AtomicReference<Throwable>()
            val worker = Thread {
                try {
                    result.set(wrapped.shouldInterceptRequest(webView, request))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { isDaemon = true; start() }
            worker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))

            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertSame(response, result.get())
            val waterfall = requireNotNull(module.getResourceWaterfall())
            val field = ResourceWaterfall::class.java.getDeclaredField("pageResources").apply { isAccessible = true }
            val pages = field.get(waterfall) as Map<*, *>
            assertEquals(setOf(PAGE_URL), pages.keys)
        } finally {
            module.onStop()
        }
    }

    /** Recoverable telemetry failure cannot swallow a host's navigation callback. */
    @Test
    fun `navigation callback still delegates when observation fails`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        var finishes = 0
        val host = object : WebViewClient() {
            /** Records the host callback despite invalid monitoring-only truncation configuration. */
            override fun onPageFinished(view: WebView, url: String?) {
                finishes++
            }
        }
        val module = WebviewModule(WebviewConfig(maxUrlLength = -1, pageLoadThresholdMs = 0))
        module.onStart()
        try {
            val wrapped = module.wrapWebViewClient(host)
            wrapped.onPageStarted(webView, PAGE_URL, null)
            wrapped.onPageFinished(webView, PAGE_URL)
            assertEquals(1, finishes)
        } finally {
            module.onStop()
        }
    }

    /** Recoverable console observation failure cannot alter the host's decision. */
    @Test
    fun `console callback still delegates when observation fails`() {
        var messages = 0
        val host = object : WebChromeClient() {
            /** Accepts the console message even when monitoring configuration is invalid. */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                messages++
                return true
            }
        }
        val module = WebviewModule(WebviewConfig(maxUrlLength = -1))
        module.onStart()
        try {
            assertTrue(module.wrapWebChromeClient(host).onConsoleMessage(
                ConsoleMessage("boom", RESOURCE_URL, 1, ConsoleMessage.MessageLevel.ERROR)
            ))
            assertEquals(1, messages)
        } finally {
            module.onStop()
        }
    }

    /** Exceptions owned by the host delegate remain visible unchanged. */
    @Test
    fun `host callback exceptions are not swallowed`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val failure = IllegalStateException("Host callback failure")
        val host = object : WebViewClient() {
            /** Throws the exact host-owned exception. */
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                throw failure
            }
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            WebviewModule().wrapWebViewClient(host).onPageStarted(webView, PAGE_URL, null)
        })
    }

    companion object {
        /** Page URL captured by navigation, independent of a later WebView URL value. */
        private const val PAGE_URL = "https://example.test/page"
        /** A getter value which must not determine resource ownership. */
        private const val DIFFERENT_PAGE_URL = "https://example.test/different"
        /** Resource URL intercepted by the host fixture. */
        private const val RESOURCE_URL = "https://example.test/resource.js"
        /** Media type of the host-owned response fixture. */
        private const val MIME_TEXT = "text/plain"
        /** Encoding of the host-owned response fixture. */
        private const val ENCODING_UTF8 = "UTF-8"
        /** Keeps visibility timeouts out of the resource-thread regression. */
        private const val LONG_VISIBILITY_TIMEOUT_MS = 60_000L
        /** Bounded resource-thread join time. */
        private const val AWAIT_SECONDS = 5L
    }
}
