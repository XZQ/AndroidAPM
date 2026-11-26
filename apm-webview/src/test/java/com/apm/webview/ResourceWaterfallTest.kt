package com.apm.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises resource retention under overflow, duplicate requests, and concurrent admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ResourceWaterfallTest {
    /** Requests rejected by the record budget cannot retain timing keys after page completion. */
    @Test
    fun `overflow retains only admitted records and page completion releases all state`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = RESOURCE_LIMIT))
        repeat(NAVIGATION_COUNT) { navigation ->
            val page = "$PAGE_URL/$navigation"
            repeat(OVERFLOW_REQUEST_COUNT) { request ->
                waterfall.onResourceStart(page, TestResourceRequest("$RESOURCE_URL/$request"))
            }
            assertEquals(RESOURCE_LIMIT, records(waterfall).size)
            assertTrue(retainedCollectionEntries(waterfall) <= RESOURCE_LIMIT)

            waterfall.onPageComplete(page)

            assertEquals(0, retainedCollectionEntries(waterfall))
        }
    }

    /** Incomplete pages share one global record bound, so abandoned navigations cannot grow maps. */
    @Test
    fun `abandoned pages share the resource capacity`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = RESOURCE_LIMIT))
        repeat(OVERFLOW_REQUEST_COUNT) { page ->
            waterfall.onResourceStart("$PAGE_URL/$page", TestResourceRequest(RESOURCE_URL))
        }

        assertEquals(RESOURCE_LIMIT, records(waterfall).size)
        assertTrue(retainedCollectionEntries(waterfall) <= RESOURCE_LIMIT)
        waterfall.stop()
        assertEquals(0, retainedCollectionEntries(waterfall))
    }

    /** Atomic admission keeps the global bound even when WebView invokes resource callbacks in parallel. */
    @Test
    fun `parallel resource starts cannot exceed the budget`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = RESOURCE_LIMIT))
        val ready = CountDownLatch(WORKER_COUNT)
        val release = CountDownLatch(1)
        val workers = List(WORKER_COUNT) { worker ->
            Thread {
                ready.countDown()
                assertTrue(release.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                repeat(OVERFLOW_REQUEST_COUNT) { request ->
                    waterfall.onResourceStart(PAGE_URL, TestResourceRequest("$RESOURCE_URL/$worker/$request"))
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue(ready.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        release.countDown()
        workers.forEach { worker ->
            worker.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
            assertFalse(worker.isAlive)
        }

        assertEquals(RESOURCE_LIMIT, records(waterfall).size)
        assertTrue(retainedCollectionEntries(waterfall) <= RESOURCE_LIMIT)
        waterfall.onPageComplete(PAGE_URL)
        assertEquals(0, retainedCollectionEntries(waterfall))
    }

    /** Repeated URLs own distinct bounded records and completion resolves each oldest unfinished entry. */
    @Test
    fun `duplicate urls complete one admitted record at a time`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = RESOURCE_LIMIT))
        repeat(OVERFLOW_REQUEST_COUNT) {
            waterfall.onResourceStart(PAGE_URL, TestResourceRequest(RESOURCE_URL))
        }

        waterfall.onResourceEnd(PAGE_URL, RESOURCE_URL, null)
        assertEquals(1, records(waterfall).count { it.endTimeMs > 0L })
        waterfall.onResourceEnd(PAGE_URL, RESOURCE_URL, null)
        assertEquals(RESOURCE_LIMIT, records(waterfall).count { it.endTimeMs > 0L })
        waterfall.onPageComplete(PAGE_URL)
        assertEquals(0, retainedCollectionEntries(waterfall))
    }

    /** A retained recorder reference stays closed permanently after the owning module stops. */
    @Test
    fun `stopped recorder does not retain later resources`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = RESOURCE_LIMIT))
        waterfall.setActivePage(PAGE_URL)
        waterfall.onResourceStart(TestResourceRequest(RESOURCE_URL))
        waterfall.stop()
        waterfall.setActivePage(PAGE_URL)
        waterfall.onResourceStart(TestResourceRequest(RESOURCE_URL))
        waterfall.onResourceStart(PAGE_URL, TestResourceRequest(RESOURCE_URL))
        waterfall.onResourceEnd(PAGE_URL, RESOURCE_URL, null)
        waterfall.onPageComplete(PAGE_URL)

        assertEquals(0, retainedCollectionEntries(waterfall))
    }

    /** Query parameters and fragments do not hide image, stylesheet, or script extensions. */
    @Test
    fun `resource type inference strips query and fragment suffixes`() {
        val waterfall = ResourceWaterfall(WebviewConfig(maxTrackedResources = 8))
        waterfall.onResourceStart(PAGE_URL, TestResourceRequest("https://example.test/logo.png?v=2#hero"))
        waterfall.onResourceStart(PAGE_URL, TestResourceRequest("https://example.test/theme.css#above-fold"))
        waterfall.onResourceStart(PAGE_URL, TestResourceRequest("https://example.test/app.js#module"))

        val types = records(waterfall).associate { it.url to it.resourceType }
        assertEquals("image", types["https://example.test/logo.png?v=2#hero"])
        assertEquals("stylesheet", types["https://example.test/theme.css#above-fold"])
        assertEquals("script", types["https://example.test/app.js#module"])
    }

    /** Reads the private retained records to verify memory bounds without adding a production API. */
    @Suppress("UNCHECKED_CAST")
    private fun records(waterfall: ResourceWaterfall): List<ResourceRecord> {
        val field = ResourceWaterfall::class.java.getDeclaredField("pageResources").apply { isAccessible = true }
        return (field.get(waterfall) as Map<String, List<ResourceRecord>>).values.flatten()
    }

    /** Counts all entries retained inside private map collections, including rejected-request timing queues. */
    private fun retainedCollectionEntries(waterfall: ResourceWaterfall): Int {
        return ResourceWaterfall::class.java.declaredFields.sumOf { field ->
            if (!Map::class.java.isAssignableFrom(field.type)) return@sumOf 0
            field.isAccessible = true
            (field.get(waterfall) as Map<*, *>).values.sumOf { value ->
                if (value is Collection<*>) value.size else 1
            }
        }
    }

    companion object {
        /** Tiny budget makes overflow and concurrent admission regression paths deterministic. */
        private const val RESOURCE_LIMIT = 2
        /** Enough unique requests to expose retained overflow URLs across repeated navigations. */
        private const val OVERFLOW_REQUEST_COUNT = 1_000
        /** Number of consecutive completed pages in the retention regression. */
        private const val NAVIGATION_COUNT = 3
        /** Simultaneous admission threads. */
        private const val WORKER_COUNT = 8
        /** Bounded latch and thread-join wait. */
        private const val AWAIT_SECONDS = 5L
        /** Stable page identity for tests. */
        private const val PAGE_URL = "https://example.test/page"
        /** Stable HTTP resource URL for tests. */
        private const val RESOURCE_URL = "https://example.test/resource"
    }
}

/** Minimal immutable resource request that can be used from either UI or resource threads. */
internal class TestResourceRequest(
    /** Request URL kept independent of any WebView getter. */
    private val requestUrl: String
) : WebResourceRequest {
    /** Supplies the frozen request URL. */
    override fun getUrl(): Uri = Uri.parse(requestUrl)
    /** Test resources are subresources rather than main-frame navigation. */
    override fun isForMainFrame(): Boolean = false
    /** Test requests do not redirect. */
    override fun isRedirect(): Boolean = false
    /** Resource loads do not require a user gesture. */
    override fun hasGesture(): Boolean = false
    /** Uses the standard resource retrieval method. */
    override fun getMethod(): String = METHOD_GET
    /** Retains no request headers. */
    override fun getRequestHeaders(): Map<String, String> = emptyMap()

    companion object {
        /** HTTP method for resource fixtures. */
        private const val METHOD_GET = "GET"
    }
}
