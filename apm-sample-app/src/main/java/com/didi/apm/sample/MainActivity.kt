package com.didi.apm.sample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.didi.apm.core.Apm

class MainActivity : AppCompatActivity() {

    /** 内存泄漏桶：持续分配不会被释放的内存。 */
    private val leakBucket = ArrayList<ByteArray>()
    /** 主线程 Handler，用于定时刷新事件面板。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 事件展示 TextView。 */
    private lateinit var eventsView: TextView

    /** 定时刷新事件面板的任务。 */
    private val refreshTask = object : Runnable {
        override fun run() {
            eventsView.text = Apm.recentEvents(15).joinToString(separator = "\n\n")
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        eventsView = findViewById(R.id.eventsView)

        // --- Memory ---
        findViewById<Button>(R.id.allocButton).setOnClickListener {
            repeat(ALLOC_REPEAT_COUNT) {
                leakBucket += ByteArray(ALLOC_BLOCK_BYTES)
            }
            sampleApplication.memoryModule.captureOnce("alloc_button")
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            leakBucket.clear()
            Runtime.getRuntime().gc()
            sampleApplication.memoryModule.captureOnce("clear_button")
        }

        findViewById<Button>(R.id.captureButton).setOnClickListener {
            sampleApplication.memoryModule.captureOnce("manual_button")
        }

        findViewById<Button>(R.id.leakTestButton).setOnClickListener {
            startActivity(Intent(this, LeakActivity::class.java))
        }

        // --- Crash ---
        findViewById<Button>(R.id.crashButton).setOnClickListener {
            // 故意抛出未捕获异常，触发 CrashModule
            throw RuntimeException("APM Sample: deliberate crash for testing")
        }

        // --- Network 模拟 ---
        val networkModule = sampleApplication.networkModule

        findViewById<Button>(R.id.networkOkButton).setOnClickListener {
            // 模拟成功的 HTTP 请求
            networkModule.onRequestComplete(
                url = "https://api.example.com/users",
                method = "GET",
                statusCode = 200,
                durationMs = 150L,
                responseSize = 2048
            )
            showToast("Simulated OK request logged")
        }

        findViewById<Button>(R.id.networkSlowButton).setOnClickListener {
            // 模拟慢请求（超过 slowThresholdMs）
            networkModule.onRequestComplete(
                url = "https://api.example.com/heavy-query",
                method = "POST",
                statusCode = 200,
                durationMs = 4500L,
                requestSize = 1024,
                responseSize = 8192
            )
            showToast("Simulated slow request logged")
        }

        findViewById<Button>(R.id.networkErrorButton).setOnClickListener {
            // 模拟失败请求
            networkModule.onRequestComplete(
                url = "https://api.example.com/broken",
                method = "GET",
                statusCode = 500,
                durationMs = 80L,
                error = "Internal Server Error"
            )
            showToast("Simulated error request logged")
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(refreshTask)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(refreshTask)
        super.onStop()
    }

    /** 获取 SampleApplication 实例。 */
    private val sampleApplication: SampleApplication
        get() = application as SampleApplication

    /** 显示简短 Toast。 */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 事件面板刷新间隔（毫秒）。 */
        private const val REFRESH_INTERVAL_MS = 2_000L
        /** 内存分配重复次数。 */
        private const val ALLOC_REPEAT_COUNT = 6
        /** 每次分配的块大小（字节）：2MB。 */
        private const val ALLOC_BLOCK_BYTES = 2 * 1024 * 1024
    }
}
