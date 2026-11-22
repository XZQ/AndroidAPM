package com.apm.sample

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Host-driven scenario driver: taps every sample button in sequence so each APM
 * monitor produces real device evidence. Buttons are located by resource id so
 * the driver works on any UI language (e.g. default English and values-zh).
 */
@RunWith(AndroidJUnit4::class)
class ScenarioDriverTest {

    private val device: UiDevice =
        UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())

    private fun launchMain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = context.packageManager.getLaunchIntentForPackage("com.apm.sample.debug")
            ?: Intent().setClassName("com.apm.sample.debug", "com.apm.sample.MainActivity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.res("com.apm.sample.debug:id/allocButton")), 10_000)
    }

    /** Scrolls once when the target button is outside the current viewport. */
    private fun tapById(idName: String, scroll: Boolean = true) {
        val selector = By.res("com.apm.sample.debug:id/$idName")
        var obj = device.findObject(selector)
        if (obj == null && scroll) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 40)
            obj = device.findObject(selector)
        }
        obj?.click()
    }

    @Test
    fun driveAllScenarios() {
        launchMain()

        // Memory: alloc + snapshot, clear + snapshot
        tapById("allocButton"); Thread.sleep(1_500)
        tapById("captureButton"); Thread.sleep(1_000)

        // Network: ok / slow / error callbacks
        tapById("networkOkButton"); Thread.sleep(800)
        tapById("networkSlowButton"); Thread.sleep(800)
        tapById("networkErrorButton"); Thread.sleep(800)

        // ANR: blocks main thread 8s (system dialog "Wait" is not needed for the SDK record)
        tapById("anrButton"); Thread.sleep(10_000)

        // Slow method: 500ms instrumented workload on main thread
        tapById("slowMethodButton"); Thread.sleep(1_500)

        // IO / SQLite / IPC / threads / battery / webview
        tapById("ioButton"); Thread.sleep(2_500)
        tapById("sqliteButton"); Thread.sleep(2_500)
        tapById("ipcButton"); Thread.sleep(2_500)
        tapById("threadPoolButton"); Thread.sleep(3_000)
        tapById("batteryButton"); Thread.sleep(2_000)
        tapById("webviewButton"); Thread.sleep(3_000)

        // Memory churn for GC evidence
        repeat(3) {
            tapById("allocButton"); Thread.sleep(600)
            tapById("clearButton"); Thread.sleep(600)
        }
        tapById("captureButton"); Thread.sleep(1_000)

        // SDK self-diagnostics
        tapById("diagnosticsStatusButton"); Thread.sleep(1_500)
        tapById("diagnosticsExportButton"); Thread.sleep(3_000)

        // Activity leak flow
        tapById("leakTestButton"); Thread.sleep(2_000)
        device.wait(Until.hasObject(By.res("com.apm.sample.debug:id/finishButton")), 5_000)
        tapById("finishButton", scroll = false); Thread.sleep(1_500)
    }
}
