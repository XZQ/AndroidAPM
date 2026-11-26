package com.apm.sample

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-driven scenario driver: taps every sample button in sequence so each APM
 * monitor produces real device evidence. Buttons are located by resource id so
 * the driver works on any UI language (e.g. default English and values-zh).
 */
@RunWith(AndroidJUnit4::class)
class ScenarioDriverTest {

    /** Active UiAutomator device handle. */
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Application package under test (supports debug suffix or custom flavors). */
    private val targetPackage: String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName

    /** Launches [MainActivity] from a clean top task and waits for its initial controls. */
    private fun launchMain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = context.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: Intent().setClassName(targetPackage, MAIN_ACTIVITY_CLASS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        checkNotNull(
            device.wait(Until.findObject(By.res(targetPackage, "allocButton")), LAUNCH_TIMEOUT_MS)
        ) { "MainActivity failed to render allocButton for package $targetPackage" }
    }

    /**
     * Locates a view by resource entry name, scrolling both down and up when needed, and clicks it.
     *
     * Failing fast when a button cannot be found prevents silent skipped scenarios after scrolling.
     */
    private fun tapById(idName: String, scroll: Boolean = true) {
        val selector = By.res(targetPackage, idName)
        var obj: UiObject2? = device.findObject(selector)
        if (obj == null && scroll) {
            // Search downward first for lower sections, then upward when returning to top controls.
            repeat(MAX_SCROLL_SWIPES) {
                if (obj == null) {
                    swipeVertical(downward = true)
                    obj = device.findObject(selector)
                }
            }
            repeat(MAX_SCROLL_SWIPES * 2) {
                if (obj == null) {
                    swipeVertical(downward = false)
                    obj = device.findObject(selector)
                }
            }
        }
        checkNotNull(obj) { "Missing sample control: $targetPackage:id/$idName" }.click()
    }

    /** Performs one vertical swipe across the central viewport. */
    private fun swipeVertical(downward: Boolean) {
        val centerX = device.displayWidth / 2
        val lowerY = device.displayHeight * SWIPE_LOWER_NUMERATOR / SWIPE_DENOMINATOR
        val upperY = device.displayHeight * SWIPE_UPPER_NUMERATOR / SWIPE_DENOMINATOR
        if (downward) {
            // Finger moves bottom -> top to scroll content downward.
            device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
        } else {
            // Finger moves top -> bottom to scroll content back toward the top.
            device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)
        }
    }

    /** Dismisses the system ANR "Wait" dialog when the OS surfaces one after the synthetic main-thread block. */
    private fun dismissSystemAnrDialogIfPresent() {
        device.findObject(By.res(ANDROID_SYSTEM_PACKAGE, SYSTEM_ANR_WAIT_BUTTON_ID))?.click()
    }

    /** Exercises all interactive sample scenarios end to end. */
    @Test
    fun driveAllScenarios() {
        launchMain()

        // Memory: alloc + snapshot
        tapById("allocButton"); Thread.sleep(MEDIUM_SETTLE_MS)
        tapById("captureButton"); Thread.sleep(SHORT_SETTLE_MS)

        // Network: ok / slow / error callbacks
        tapById("networkOkButton"); Thread.sleep(NETWORK_SETTLE_MS)
        tapById("networkSlowButton"); Thread.sleep(NETWORK_SETTLE_MS)
        tapById("networkErrorButton"); Thread.sleep(NETWORK_SETTLE_MS)

        // Slow method: 500ms instrumented workload on main thread
        tapById("slowMethodButton"); Thread.sleep(MEDIUM_SETTLE_MS)

        // IO / SQLite / IPC / threads / battery / webview / trace+OTel
        tapById("ioButton"); Thread.sleep(IO_WORKLOAD_SETTLE_MS)
        tapById("sqliteButton"); Thread.sleep(IO_WORKLOAD_SETTLE_MS)
        tapById("ipcButton"); Thread.sleep(IO_WORKLOAD_SETTLE_MS)
        tapById("threadPoolButton"); Thread.sleep(LONG_WORKLOAD_SETTLE_MS)
        tapById("batteryButton"); Thread.sleep(BATTERY_SETTLE_MS)
        tapById("webviewButton"); Thread.sleep(LONG_WORKLOAD_SETTLE_MS)
        tapById("traceButton"); Thread.sleep(IO_WORKLOAD_SETTLE_MS)

        // Memory churn for GC evidence (requires scrolling back up to top controls)
        repeat(GC_CHURN_ITERATIONS) {
            tapById("allocButton"); Thread.sleep(CHURN_STEP_MS)
            tapById("clearButton"); Thread.sleep(CHURN_STEP_MS)
        }
        tapById("captureButton"); Thread.sleep(SHORT_SETTLE_MS)

        // SDK self-diagnostics
        tapById("diagnosticsStatusButton"); Thread.sleep(MEDIUM_SETTLE_MS)
        tapById("diagnosticsExportButton"); Thread.sleep(LONG_WORKLOAD_SETTLE_MS)

        // Activity + ViewModel leak flow
        tapById("leakTestButton"); Thread.sleep(BATTERY_SETTLE_MS)
        checkNotNull(
            device.wait(Until.findObject(By.res(targetPackage, "finishButton")), LEAK_ACTIVITY_TIMEOUT_MS)
        ) { "LeakActivity failed to render finishButton" }
        tapById("finishButton", scroll = false); Thread.sleep(MEDIUM_SETTLE_MS)

        // ANR runs last so any delayed OS dialog cannot obstruct earlier UI scenarios
        tapById("anrButton"); Thread.sleep(ANR_SCENARIO_WAIT_MS)
        dismissSystemAnrDialogIfPresent()
    }

    companion object {
        /** Explicit fallback activity class name. */
        private const val MAIN_ACTIVITY_CLASS = "com.apm.sample.MainActivity"
        /** System package owning platform ANR dialogs. */
        private const val ANDROID_SYSTEM_PACKAGE = "android"
        /** Standard Android framework "Wait" button resource id on ANR dialogs. */
        private const val SYSTEM_ANR_WAIT_BUTTON_ID = "aerr_wait"
        /** Maximum launch wait in milliseconds. */
        private const val LAUNCH_TIMEOUT_MS = 10_000L
        /** Maximum wait for LeakActivity launch. */
        private const val LEAK_ACTIVITY_TIMEOUT_MS = 5_000L
        /** Maximum downward swipes before reversing direction. */
        private const val MAX_SCROLL_SWIPES = 4
        /** Swipe lower bound numerator (3/4 height). */
        private const val SWIPE_LOWER_NUMERATOR = 3
        /** Swipe upper bound numerator (1/4 height). */
        private const val SWIPE_UPPER_NUMERATOR = 1
        /** Swipe fraction denominator. */
        private const val SWIPE_DENOMINATOR = 4
        /** Smooth swipe step count avoiding fling overshoots. */
        private const val SWIPE_STEPS = 40
        /** Number of alloc/clear cycles used to trigger GC activity. */
        private const val GC_CHURN_ITERATIONS = 3
        /** Short UI settle pause between memory churn steps. */
        private const val CHURN_STEP_MS = 600L
        /** Settle pause after network simulation buttons. */
        private const val NETWORK_SETTLE_MS = 800L
        /** One-second settle pause after synchronous snapshot capture. */
        private const val SHORT_SETTLE_MS = 1_000L
        /** Medium settle pause after main-thread / navigation actions. */
        private const val MEDIUM_SETTLE_MS = 1_500L
        /** Settle pause for battery and activity transitions. */
        private const val BATTERY_SETTLE_MS = 2_000L
        /** Settle pause for background IO, SQLite, IPC, and trace workloads. */
        private const val IO_WORKLOAD_SETTLE_MS = 2_500L
        /** Settle pause for multi-second thread-pool, WebView, and ZIP export tasks. */
        private const val LONG_WORKLOAD_SETTLE_MS = 3_000L
        /** Wait covering the 8-second main-thread ANR block plus watchdog detection. */
        private const val ANR_SCENARIO_WAIT_MS = 10_000L
    }
}
