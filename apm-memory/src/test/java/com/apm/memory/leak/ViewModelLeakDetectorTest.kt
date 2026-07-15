package com.apm.memory.leak

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Robolectric coverage for explicit ViewModel field inspection. */
@RunWith(RobolectricTestRunner::class)
class ViewModelLeakDetectorTest {
    /** Context and View references are reported with their field names. */
    @Test
    fun `context and view fields are reported`() {
        val application = RuntimeEnvironment.getApplication()
        val result = ViewModelLeakDetector().checkViewModel(RiskyViewModel(application, View(application)))

        requireNotNull(result)
        assertEquals(LeakType.VIEW_MODEL, result.type)
        assertEquals(2, result.suspectFields.size)
        assertTrue(result.suspectFields.any { it.startsWith("context:") })
        assertTrue(result.suspectFields.any { it.startsWith("view:") })
    }

    /** Plain scalar fields do not create a leak candidate. */
    @Test
    fun `scalar fields are ignored`() {
        assertNull(ViewModelLeakDetector().checkViewModel(SafeViewModel("safe")))
    }

    /** ViewModel intentionally holding Android objects for detection coverage. */
    private class RiskyViewModel(
        /** Retained Context candidate. */
        private val context: Context,
        /** Retained View candidate. */
        private val view: View
    ) : ViewModel()

    /** ViewModel containing no Android object references. */
    private class SafeViewModel(
        /** Harmless scalar value. */
        private val value: String
    ) : ViewModel()
}
