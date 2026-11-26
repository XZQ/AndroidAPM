package com.apm.memory.leak

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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
    /** Non-Application Context and View references are reported with their field names. */
    @Test
    fun `context and view fields are reported`() {
        val application = RuntimeEnvironment.getApplication()
        val activityLikeContext = ContextWrapper(application)
        val result = ViewModelLeakDetector().checkViewModel(RiskyViewModel(activityLikeContext, View(application)))

        requireNotNull(result)
        assertEquals(LeakType.VIEW_MODEL, result.type)
        assertEquals(2, result.suspectFields.size)
        assertTrue(result.suspectFields.any { it.startsWith("context:") })
        assertTrue(result.suspectFields.any { it.startsWith("view:") })
    }

    /** Process-scoped Application references (AndroidViewModel pattern) are not flagged as leaks. */
    @Test
    fun `application context field is ignored`() {
        val application = RuntimeEnvironment.getApplication()
        assertNull(ViewModelLeakDetector().checkViewModel(ApplicationHoldingViewModel(application)))
    }

    /** Plain scalar fields do not create a leak candidate. */
    @Test
    fun `scalar fields are ignored`() {
        assertNull(ViewModelLeakDetector().checkViewModel(SafeViewModel("safe")))
    }

    /** ViewModel intentionally holding Android UI objects for detection coverage. */
    private class RiskyViewModel(
        /** Retained non-Application Context candidate. */
        private val context: Context,
        /** Retained View candidate. */
        private val view: View
    ) : ViewModel()

    /** ViewModel holding only the process-scoped Application instance. */
    private class ApplicationHoldingViewModel(
        /** Legitimate process-scoped Application reference. */
        private val application: Application
    ) : ViewModel()

    /** ViewModel containing no Android object references. */
    private class SafeViewModel(
        /** Harmless scalar value. */
        private val value: String
    ) : ViewModel()
}
