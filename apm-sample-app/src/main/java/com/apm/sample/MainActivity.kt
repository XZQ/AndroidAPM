package com.apm.sample

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.apm.core.Apm
import com.apm.core.diagnostics.ApmDiagnostics
import com.apm.sqlite.ApmSQLiteDatabase
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Interactive integration surface for the SDK's explicit host-side APIs. */
class MainActivity : AppCompatActivity() {
    /** Memory retained intentionally until the user clears the demo bucket. */
    private val leakBucket = ArrayList<ByteArray>()

    /** Main-thread scheduler used for event refreshes and delayed demo callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Host-owned worker for blocking sample IO, SQLite, IPC, and diagnostics work. */
    private val sampleExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Host-owned executor registered explicitly with the thread monitor. */
    private val monitoredThreadPool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable -> Thread(runnable, SAMPLE_THREAD_NAME) }
    )

    /** Helper that owns the sample database lifecycle. */
    private lateinit var databaseHelper: DemoDatabaseHelper

    /** Timed wrapper around the sample database. */
    private lateinit var monitoredDatabase: ApmSQLiteDatabase

    /** WebView explicitly installed into the WebView monitor. */
    private lateinit var demoWebView: WebView

    /** Text surface that displays recently accepted APM events. */
    private lateinit var eventsView: TextView

    /** Periodic task that refreshes the recent-event panel. */
    private val refreshTask = object : Runnable {
        /** Refreshes the UI and schedules the next bounded update. */
        override fun run() {
            eventsView.text = Apm.recentEvents(RECENT_EVENT_LIMIT).joinToString(separator = "\n\n")
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /** Creates the sample UI and wires every explicit integration entry point. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        eventsView = findViewById(R.id.eventsView)
        databaseHelper = DemoDatabaseHelper(this)
        monitoredDatabase = ApmSQLiteDatabase(
            databaseHelper.writableDatabase,
            sampleApplication.sqliteModule,
            SAMPLE_DATABASE_NAME
        )
        sampleApplication.threadMonitorModule.registerThreadPool(SAMPLE_THREAD_POOL_NAME, monitoredThreadPool)

        configureMemoryActions()
        configureCrashAndNetworkActions()
        configureExplicitIntegrationActions()
        configureDiagnosticsActions()
        configureWebView()
    }

    /** Starts the live recent-event refresh while the activity is visible. */
    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
    }

    /** Stops UI refresh work when the activity is no longer visible. */
    override fun onStop() {
        mainHandler.removeCallbacks(refreshTask)
        super.onStop()
    }

    /** Releases every host-owned resource and reverses explicit monitor registration. */
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        sampleApplication.threadMonitorModule.unregisterThreadPool(SAMPLE_THREAD_POOL_NAME)
        monitoredThreadPool.shutdownNow()
        sampleExecutor.shutdownNow()
        sampleApplication.webviewModule.uninstall(demoWebView)
        demoWebView.destroy()
        databaseHelper.close()
        super.onDestroy()
    }

    /** Returns the process application with its registered monitor references. */
    private val sampleApplication: SampleApplication
        get() = application as SampleApplication

    /** Wires allocation, cleanup, snapshot, and lifecycle-leak demonstrations. */
    private fun configureMemoryActions() {
        findViewById<Button>(R.id.allocButton).setOnClickListener {
            repeat(ALLOC_REPEAT_COUNT) {
                // Retain each block so the next snapshot observes the allocation.
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
    }

    /** Wires crash and manual network-completion callbacks. */
    private fun configureCrashAndNetworkActions() {
        findViewById<Button>(R.id.crashButton).setOnClickListener {
            throw RuntimeException("APM Sample: deliberate crash for testing")
        }

        val networkModule = sampleApplication.networkModule
        findViewById<Button>(R.id.networkOkButton).setOnClickListener {
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
            networkModule.onRequestComplete(
                url = "https://api.example.com/heavy-query",
                method = "POST",
                statusCode = 200,
                durationMs = 4_500L,
                requestSize = 1024,
                responseSize = 8192
            )
            showToast("Simulated slow request logged")
        }
        findViewById<Button>(R.id.networkErrorButton).setOnClickListener {
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

    /** Wires explicit IO, SQLite, IPC, thread-pool, battery, and WebView integrations. */
    private fun configureExplicitIntegrationActions() {
        findViewById<Button>(R.id.ioButton).setOnClickListener { runIoDemo() }
        findViewById<Button>(R.id.sqliteButton).setOnClickListener { runSqliteDemo() }
        findViewById<Button>(R.id.ipcButton).setOnClickListener { runIpcDemo() }
        findViewById<Button>(R.id.threadPoolButton).setOnClickListener { runThreadPoolDemo() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { runBatteryDemo() }
        findViewById<Button>(R.id.webviewButton).setOnClickListener { runWebViewDemo() }
    }

    /** Wires local SDK-health status and support-ZIP export. */
    private fun configureDiagnosticsActions() {
        findViewById<Button>(R.id.diagnosticsStatusButton).setOnClickListener {
            showDiagnosticStatus()
        }
        findViewById<Button>(R.id.diagnosticsExportButton).setOnClickListener {
            exportDiagnostics()
        }
    }

    /** Installs monitoring clients on a real host-owned WebView and loads local content. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        demoWebView = findViewById(R.id.demoWebView)
        demoWebView.settings.javaScriptEnabled = true
        sampleApplication.webviewModule.install(demoWebView)
        demoWebView.loadDataWithBaseURL(SAMPLE_PAGE_URL, SAMPLE_HTML, "text/html", "UTF-8", null)
    }

    /** Writes and reads a real cache file through the IO module's stream wrappers. */
    private fun runIoDemo() {
        sampleExecutor.execute {
            val target = File(cacheDir, SAMPLE_IO_FILE_NAME)
            sampleApplication.ioModule.wrapOutputStream(target.outputStream(), target.absolutePath).use { output ->
                output.write(ByteArray(SAMPLE_IO_BYTES) { index -> (index % BYTE_VALUE_RANGE).toByte() })
            }
            val bytesRead = sampleApplication.ioModule
                .wrapInputStream(target.inputStream(), target.absolutePath)
                .use { input -> input.readBytes().size }
            postToast("Monitored IO completed: $bytesRead bytes")
        }
    }

    /** Inserts and queries a real row through [ApmSQLiteDatabase]. */
    private fun runSqliteDemo() {
        sampleExecutor.execute {
            val values = ContentValues().apply {
                put(COLUMN_MESSAGE, "sample-${System.currentTimeMillis()}")
            }
            monitoredDatabase.insert(TABLE_EVENTS, null, values)
            val rowCount = monitoredDatabase.rawQuery(
                "SELECT $COLUMN_ID, $COLUMN_MESSAGE FROM $TABLE_EVENTS ORDER BY $COLUMN_ID DESC",
                null
            ).use { cursor -> cursor.count }
            postToast("Monitored SQLite rows: $rowCount")
        }
    }

    /** Measures a representative background service call through the IPC wrapper. */
    private fun runIpcDemo() {
        sampleExecutor.execute {
            sampleApplication.ipcModule.traceBinderCall("ISampleService", "lookup") {
                // Sleep represents a blocking AIDL call without depending on a second process.
                Thread.sleep(SAMPLE_IPC_DURATION_MS)
            }
            postToast("Monitored IPC call completed")
        }
    }

    /** Creates a visible queue backlog in the registered host executor. */
    private fun runThreadPoolDemo() {
        monitoredThreadPool.execute {
            // Keep the sole worker occupied long enough for the periodic inspector to sample the queue.
            Thread.sleep(SAMPLE_BACKLOG_HOLD_MS)
        }
        repeat(SAMPLE_BACKLOG_TASK_COUNT) {
            monitoredThreadPool.execute { Thread.sleep(SAMPLE_BACKLOG_TASK_MS) }
        }
        showToast("Thread-pool backlog queued")
    }

    /** Sends truthful host callbacks for WakeLock, GPS, and alarm usage. */
    private fun runBatteryDemo() {
        val tag = "sample-${System.nanoTime()}"
        sampleApplication.batteryModule.onWakeLockAcquired(tag)
        sampleApplication.batteryModule.onGpsStarted(tag)
        repeat(SAMPLE_ALARM_COUNT) {
            sampleApplication.batteryModule.onAlarmScheduled()
        }
        mainHandler.postDelayed(
            {
                // Close both sessions after the configured thresholds have elapsed.
                sampleApplication.batteryModule.onWakeLockReleased(tag)
                sampleApplication.batteryModule.onGpsStopped(tag)
                showToast("Battery callbacks completed")
            },
            SAMPLE_BATTERY_SESSION_MS
        )
    }

    /** Reloads local WebView content and measures an explicit JavaScript evaluation. */
    private fun runWebViewDemo() {
        demoWebView.loadDataWithBaseURL(SAMPLE_PAGE_URL, SAMPLE_HTML, "text/html", "UTF-8", null)
        sampleApplication.webviewModule.evaluateJavascript(
            demoWebView,
            "document.getElementById('status').textContent='APM measured JavaScript'"
        ) {
            showToast("Monitored WebView JavaScript completed")
        }
    }

    /** Shows current local diagnostics health without reading APM event storage. */
    private fun showDiagnosticStatus() {
        val status = ApmDiagnostics.status()
        showToast(
            getString(
                R.string.diagnostics_status_result,
                status.fileSinkHealthy,
                status.droppedRecords,
                status.writeFailures,
                status.readFailures,
                status.memoryBytes,
                status.queueBytes
            )
        )
    }

    /** Exports an app-private support ZIP on a host-owned worker thread. */
    private fun exportDiagnostics() {
        val target = File(cacheDir, DIAGNOSTICS_EXPORT_FILE_NAME)
        ApmDiagnostics.exportToAsync(sampleExecutor, target) { result ->
            runOnUiThread {
                val message = if (result.success) {
                    getString(R.string.diagnostics_export_success, result.exportedRecords)
                } else {
                    getString(R.string.diagnostics_export_failure)
                }
                showToast(message)
            }
        }
    }

    /** Posts a toast from a sample worker back to the UI thread. */
    private fun postToast(message: String) {
        runOnUiThread { showToast(message) }
    }

    /** Displays one short user-facing sample result. */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** SQLite schema owner for the explicit wrapper demonstration. */
    private class DemoDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, SAMPLE_DATABASE_NAME, null, SAMPLE_DATABASE_VERSION) {
        /** Creates the single sample event table. */
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $TABLE_EVENTS (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_MESSAGE TEXT NOT NULL)"
            )
        }

        /** Recreates disposable sample data after a schema version change. */
        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            database.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
            onCreate(database)
        }
    }

    companion object {
        /** Event panel refresh interval in milliseconds. */
        private const val REFRESH_INTERVAL_MS = 2_000L
        /** Number of recent events displayed on screen. */
        private const val RECENT_EVENT_LIMIT = 30
        /** Count of retained allocation blocks per button press. */
        private const val ALLOC_REPEAT_COUNT = 6
        /** Size of each retained allocation block. */
        private const val ALLOC_BLOCK_BYTES = 2 * 1024 * 1024
        /** App-private support archive name. */
        private const val DIAGNOSTICS_EXPORT_FILE_NAME = "android-apm-diagnostics.zip"
        /** App-private IO sample file name. */
        private const val SAMPLE_IO_FILE_NAME = "apm-io-demo.bin"
        /** Number of bytes written by the IO sample. */
        private const val SAMPLE_IO_BYTES = 16 * 1024
        /** Unsigned byte range used to generate deterministic sample content. */
        private const val BYTE_VALUE_RANGE = 256
        /** Artificial service-call duration above the sample IPC threshold. */
        private const val SAMPLE_IPC_DURATION_MS = 40L
        /** Duration that the sample worker remains occupied. */
        private const val SAMPLE_BACKLOG_HOLD_MS = 2_000L
        /** Work duration for each queued sample task. */
        private const val SAMPLE_BACKLOG_TASK_MS = 50L
        /** Queue depth above the configured thread-pool threshold. */
        private const val SAMPLE_BACKLOG_TASK_COUNT = 6
        /** Number of alarm callbacks that reaches the sample flood threshold. */
        private const val SAMPLE_ALARM_COUNT = 3
        /** Duration of sample battery sessions above their configured threshold. */
        private const val SAMPLE_BATTERY_SESSION_MS = 750L
        /** Stable name used for thread-pool registration. */
        private const val SAMPLE_THREAD_POOL_NAME = "sample-backlog"
        /** Visible worker thread name used by the sample pool. */
        private const val SAMPLE_THREAD_NAME = "sample-backlog-worker"
        /** Disposable sample database name. */
        private const val SAMPLE_DATABASE_NAME = "apm-sample.db"
        /** Initial sample database schema version. */
        private const val SAMPLE_DATABASE_VERSION = 1
        /** Sample table name. */
        private const val TABLE_EVENTS = "sample_events"
        /** Auto-incrementing sample row identifier. */
        private const val COLUMN_ID = "id"
        /** Sample message column. */
        private const val COLUMN_MESSAGE = "message"
        /** Stable local origin used for WebView lifecycle reporting. */
        private const val SAMPLE_PAGE_URL = "https://sample.local/"
        /** Trusted local HTML used without external network availability. */
        private const val SAMPLE_HTML =
            "<html><body><h3>Monitored WebView</h3><p id='status'>Ready</p></body></html>"
    }
}
