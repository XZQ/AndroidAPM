package com.apm.slowmethod

import android.app.Application
import android.os.Looper
import android.util.Printer
import com.apm.core.Apm
import com.apm.core.ApmConfig
import com.apm.core.StorageType
import com.apm.model.ApmEvent
import com.apm.uploader.ApmUploader
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * StackSamplingProfiler 与 SlowMethodModule 阻塞现场抓栈生命周期单元测试（Robolectric）。
 *
 * 覆盖确定性门控行为：采样开关关闭时不进入采样态、stopSampling 同步输出
 * Top N 结果与样本计数、destroy 释放采样线程，以及 SlowMethodModule 在慢消息执行期间
 * 通过合并看门狗捕获真实业务阻塞栈并立即启动采样（而非在 Finished 事后抓取 Looper 空栈）。
 */
@RunWith(RobolectricTestRunner::class)
class StackSamplingProfilerTest {

    /** 每个用例后释放采样线程与全局 APM 状态，避免跨用例泄漏。 */
    @After
    fun tearDown() {
        Apm.stop()
        profiler?.destroy()
        profiler = null
    }

    /** 当前用例持有的 profiler 实例。 */
    private var profiler: StackSamplingProfiler? = null

    /** 采样开关关闭时 startSampling 不进入采样态，stop 输出零样本。 */
    @Test
    fun `disabled sampling never captures samples`() {
        val sampler = StackSamplingProfiler(SlowMethodConfig(enableStackSampling = false))
        profiler = sampler

        var callbackCount = 0
        sampler.onSamplingComplete = { topMethods, sampleCount ->
            callbackCount++
            assertTrue(topMethods.isEmpty())
            assertEquals(0, sampleCount)
        }

        sampler.startSampling()
        sampler.stopSampling()

        assertEquals(1, callbackCount)
    }

    /** 启用采样时 stopSampling 同步回调 Top N 列表与样本计数。 */
    @Test
    fun `enabled sampling reports top methods on stop`() {
        val sampler = StackSamplingProfiler(
            SlowMethodConfig(
                enableStackSampling = true,
                samplingIntervalMs = SAMPLING_INTERVAL_MS,
                samplingWindowMs = SAMPLING_WINDOW_MS,
                topMethodCount = TOP_METHOD_COUNT,
            )
        )
        profiler = sampler

        var reportedSampleCount = -1
        sampler.onSamplingComplete = { _, sampleCount ->
            reportedSampleCount = sampleCount
        }

        sampler.startSampling()
        sampler.stopSampling()

        // 立即 stop：pending 的采样任务被移除，样本计数为非负值且不超过窗口理论采样数。
        assertTrue(reportedSampleCount >= 0)
        assertTrue(reportedSampleCount <= MAX_POSSIBLE_SAMPLES)
    }

    /** destroy 释放采样线程后线程退出。 */
    @Test
    fun `destroy quits sampling thread`() {
        val sampler = StackSamplingProfiler(
            SlowMethodConfig(enableStackSampling = false)
        )
        sampler.destroy()

        val threadField = StackSamplingProfiler::class.java.getDeclaredField("samplingThread")
        threadField.isAccessible = true
        val thread = threadField.get(sampler) as Thread
        thread.join(JOIN_TIMEOUT_MS)
        assertTrue("sampling thread should exit after destroy", !thread.isAlive)
    }

    /** SlowMethodModule 应在慢消息执行期间捕获真实阻塞方法栈并立即触发采样，而非抓取 Finished 事后栈。 */
    @Test
    fun `slow method module captures in-flight blocking stack and triggers sampling before finish`() {
        val uploadedEvents = CopyOnWriteArrayList<ApmEvent>()
        val uploader = object : ApmUploader {
            override fun upload(event: ApmEvent): Boolean {
                uploadedEvents.add(event)
                return true
            }
        }
        val app = RuntimeEnvironment.getApplication() as Application
        Apm.init(
            application = app,
            config = ApmConfig(
                storageType = StorageType.FILE,
                uploader = uploader,
                enableSelfMonitoring = false,
                enableAutoThrottle = false,
                enablePiiSanitization = false
            )
        )
        val module = SlowMethodModule(
            SlowMethodConfig(
                thresholdMs = IN_FLIGHT_THRESHOLD_MS,
                severeThresholdMs = SEVERE_THRESHOLD_MS,
                includeStackTrace = true,
                enableStackSampling = true,
                samplingIntervalMs = SAMPLING_INTERVAL_MS,
                samplingWindowMs = SAMPLING_WINDOW_MS
            )
        )
        Apm.register(module)

        val profilerField = SlowMethodModule::class.java.getDeclaredField("samplingProfiler").apply {
            isAccessible = true
        }
        val activeProfiler = profilerField.get(module) as StackSamplingProfiler
        val loggingField = Looper::class.java.getDeclaredField("mLogging").apply {
            isAccessible = true
        }
        val installedPrinter = loggingField.get(Looper.getMainLooper()) as Printer

        val enteredBlockingWork = CountDownLatch(1)
        val releaseBlockingWork = CountDownLatch(1)
        var sampledInFlight = false
        val helper = Thread {
            assertTrue(enteredBlockingWork.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            // 推进采样线程 ShadowLooper 触发合并看门狗，此时主线程仍停留在 simulateSlowBusinessWork 内
            Shadows.shadowOf(activeProfiler.samplingLooper)
                .idleFor(Duration.ofMillis(WATCHDOG_ADVANCE_MS))
            sampledInFlight = activeProfiler.isSampling
            releaseBlockingWork.countDown()
        }
        helper.start()

        installedPrinter.println(">>>>> Dispatching to Handler (TestTarget) {1}")
        simulateSlowBusinessWork(enteredBlockingWork, releaseBlockingWork)
        installedPrinter.println("<<<<< Finished to Handler (TestTarget) {1}")
        helper.join(JOIN_TIMEOUT_MS)

        // Apm.stop() 会 drain dispatcher 并通过内存 uploader 交付事件
        Apm.stop()

        assertTrue("Stack sampling should start while slow dispatch is still in flight", sampledInFlight)
        val slowEvent = uploadedEvents.firstOrNull { it.name == "slow_method_detected" }
        assertNotNull("Expected slow_method_detected event", slowEvent)
        val capturedStack = slowEvent!!.fields["mainThreadStack"] as? String
        assertNotNull("Expected in-flight mainThreadStack field", capturedStack)
        assertTrue(
            "Captured stack should contain in-flight method simulateSlowBusinessWork, got:\n$capturedStack",
            capturedStack!!.contains("simulateSlowBusinessWork")
        )
        assertFalse(
            "Captured stack must not be a post-finish looperPrinter.println stack",
            capturedStack.contains("looperPrinter")
        )
    }

    /** 模拟主线程耗时业务方法，供看门狗在执行期抓取真实堆栈帧。 */
    private fun simulateSlowBusinessWork(
        enteredLatch: CountDownLatch,
        releaseLatch: CountDownLatch
    ) {
        Thread.sleep(IN_FLIGHT_SLEEP_MS)
        enteredLatch.countDown()
        assertTrue(releaseLatch.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    companion object {
        /** 测试采样间隔（毫秒）。 */
        private const val SAMPLING_INTERVAL_MS = 50L

        /** 测试采样窗口（毫秒）。 */
        private const val SAMPLING_WINDOW_MS = 10_000L

        /** 测试 Top N 数量。 */
        private const val TOP_METHOD_COUNT = 5

        /** 立即 stop 场景下的最大可能样本数（远小于窗口理论值）。 */
        private const val MAX_POSSIBLE_SAMPLES = 3

        /** 等待采样线程退出的超时（毫秒）。 */
        private const val JOIN_TIMEOUT_MS = 2_000L

        /** 执行期抓栈测试的慢消息阈值（毫秒）。 */
        private const val IN_FLIGHT_THRESHOLD_MS = 20L

        /** 执行期抓栈测试的严重阈值（毫秒）。 */
        private const val SEVERE_THRESHOLD_MS = 800L

        /** 主线程阻塞睡眠时长（毫秒），确保单调时钟超过阈值。 */
        private const val IN_FLIGHT_SLEEP_MS = 35L

        /** 推进采样线程虚拟时钟以触发看门狗的时长（毫秒）。 */
        private const val WATCHDOG_ADVANCE_MS = 30L
    }
}
