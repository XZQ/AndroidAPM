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
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.apm.core.Apm
import com.apm.core.diagnostics.ApmDiagnostics
import com.apm.model.ApmPriority
import com.apm.sqlite.ApmSQLiteDatabase
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Interactive integration surface for the SDK's explicit host-side APIs. */
class MainActivity : AppCompatActivity() {
    /** Whether the interactive demo resources were initialized for normal sample use. */
    private var interactiveDemoInitialized = false

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

    /** Fixed rolling latency reservoir used without per-event object allocation. */
    private val soakLatencySamplesNs = LongArray(SOAK_LATENCY_SAMPLE_CAPACITY)

    /** Total operations observed during the current soak segment. */
    private var soakOperationCount = 0L

    /** Requested end time for the current soak segment. */
    private var soakEndElapsedMs = 0L

    /** Segment start time used to report actual observed duration. */
    private var soakStartedElapsedMs = 0L

    /** Process age at segment start, including Application initialization. */
    private var soakProcessAgeAtStartMs = 0L

    /** Synthetic event rate applied once per main-thread tick. */
    private var soakEventsPerSecond = 0

    /** Stable host-supplied identity for one process segment. */
    private var soakRunId = ""

    /** Keeps the control workload observable to the runtime optimizer. */
    private var soakControlBlackhole = 0

    /** Main-thread fixed-rate workload used by device-soak profiles. */
    private val soakWorkloadTask = object : Runnable {
        /** Executes one bounded event burst or publishes the final result. */
        override fun run() {
            if (SystemClock.elapsedRealtime() >= soakEndElapsedMs) {
                finishSoakProbe()
                return
            }
            repeat(soakEventsPerSecond) {
                runOneSoakOperation()
            }
            mainHandler.postDelayed(this, SOAK_TICK_INTERVAL_MS)
        }
    }

    /** Creates the sample UI and wires every explicit integration entry point. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        eventsView = findViewById(R.id.eventsView)
        if (applySoakConfigurationForNextProcess(intent)) {
            return
        }
        if (startSoakProbeIfRequested(intent)) {
            return
        }

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
        interactiveDemoInitialized = true
    }

    /** Starts the live recent-event refresh while the activity is visible. */
    override fun onStart() {
        super.onStart()
        if (interactiveDemoInitialized) {
            mainHandler.removeCallbacks(refreshTask)
            mainHandler.post(refreshTask)
        }
    }

    /** Stops UI refresh work when the activity is no longer visible. */
    override fun onStop() {
        mainHandler.removeCallbacks(refreshTask)
        super.onStop()
    }

    /** Releases every host-owned resource and reverses explicit monitor registration. */
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (interactiveDemoInitialized) {
            sampleApplication.threadMonitorModule.unregisterThreadPool(SAMPLE_THREAD_POOL_NAME)
            sampleApplication.webviewModule.uninstall(demoWebView)
            demoWebView.destroy()
            databaseHelper.close()
        }
        monitoredThreadPool.shutdownNow()
        sampleExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Returns the process application with its registered monitor references. */
    private val sampleApplication: SampleApplication
        get() = application as SampleApplication

    /** Applies host-requested A/B flags synchronously for the next cold process launch. */
    private fun applySoakConfigurationForNextProcess(sourceIntent: Intent): Boolean {
        val changesSdkMode = sourceIntent.hasExtra(EXTRA_SOAK_SET_SDK_ENABLED)
        val changesOfflineMode = sourceIntent.hasExtra(EXTRA_SOAK_SET_OFFLINE_COLLECTOR)
        if (!changesSdkMode && !changesOfflineMode) {
            return false
        }
        val preferences = getSharedPreferences(
            SampleApplication.SOAK_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val editor = preferences.edit()
        if (changesSdkMode) {
            editor.putBoolean(
                SampleApplication.SOAK_SDK_ENABLED_KEY,
                sourceIntent.getBooleanExtra(EXTRA_SOAK_SET_SDK_ENABLED, true)
            )
        }
        if (changesOfflineMode) {
            editor.putBoolean(
                SampleApplication.SOAK_OFFLINE_COLLECTOR_KEY,
                sourceIntent.getBooleanExtra(EXTRA_SOAK_SET_OFFLINE_COLLECTOR, false)
            )
        }
        // commit() makes the next force-stop/cold-start transition deterministic for the host gate.
        val committed = editor.commit()
        val status = JSONObject()
            .put("committed", committed)
            .put(
                "sdkEnabled",
                preferences.getBoolean(SampleApplication.SOAK_SDK_ENABLED_KEY, true)
            )
            .put(
                "offlineCollector",
                preferences.getBoolean(SampleApplication.SOAK_OFFLINE_COLLECTOR_KEY, false)
            )
        eventsView.text = status.toString()
        Log.i(SOAK_LOG_TAG, SOAK_CONFIG_MARKER + status.toString())
        return true
    }

    /** Starts a bounded main-thread workload when the host supplies soak arguments. */
    private fun startSoakProbeIfRequested(sourceIntent: Intent): Boolean {
        if (!sourceIntent.hasExtra(EXTRA_SOAK_DURATION_SECONDS)) {
            return false
        }
        val durationSeconds = sourceIntent.getLongExtra(EXTRA_SOAK_DURATION_SECONDS, 0L)
            .coerceIn(MIN_SOAK_DURATION_SECONDS, MAX_SOAK_DURATION_SECONDS)
        soakEventsPerSecond = sourceIntent.getIntExtra(
            EXTRA_SOAK_EVENTS_PER_SECOND,
            DEFAULT_SOAK_EVENTS_PER_SECOND
        ).coerceIn(MIN_SOAK_EVENTS_PER_SECOND, MAX_SOAK_EVENTS_PER_SECOND)
        soakRunId = sourceIntent.getStringExtra(EXTRA_SOAK_RUN_ID).orEmpty()
            .take(MAX_SOAK_RUN_ID_LENGTH)
        soakOperationCount = 0L
        soakLatencySamplesNs.fill(0L)
        soakStartedElapsedMs = SystemClock.elapsedRealtime()
        soakEndElapsedMs = soakStartedElapsedMs + durationSeconds * MILLIS_PER_SECOND
        soakProcessAgeAtStartMs = (
            soakStartedElapsedMs - Process.getStartElapsedRealtime()
            ).coerceAtLeast(0L)
        eventsView.text = "Device soak running: $soakRunId"
        mainHandler.post(soakWorkloadTask)
        return true
    }

    /** Executes one identical synthetic operation with only [Apm.emit] differing in control mode. */
    private fun runOneSoakOperation() {
        val sequence = soakOperationCount
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val fields = mapOf<String, Any?>(
            "sequence" to sequence,
            "runId" to soakRunId,
            "payload" to SOAK_PAYLOAD
        )
        if (sampleApplication.soakSdkEnabled) {
            Apm.emit(
                module = SOAK_EVENT_MODULE,
                name = SOAK_EVENT_NAME,
                priority = ApmPriority.LOW,
                fields = fields
            )
        } else {
            // The control process retains the same map construction without entering the SDK.
            soakControlBlackhole = soakControlBlackhole xor fields.hashCode()
        }
        val elapsedNs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
        val sampleIndex = (sequence % SOAK_LATENCY_SAMPLE_CAPACITY).toInt()
        soakLatencySamplesNs[sampleIndex] = elapsedNs
        soakOperationCount += 1L
    }

    /** Writes one compact segment result for `adb run-as` retrieval and Logcat diagnosis. */
    private fun finishSoakProbe() {
        mainHandler.removeCallbacks(soakWorkloadTask)
        val observedDurationMs = (
            SystemClock.elapsedRealtime() - soakStartedElapsedMs
            ).coerceAtLeast(0L)
        val sampleCount = soakOperationCount.coerceAtMost(SOAK_LATENCY_SAMPLE_CAPACITY.toLong()).toInt()
        val sortedSamples = soakLatencySamplesNs.copyOf(sampleCount).apply { sort() }
        val result = JSONObject()
            .put("schemaVersion", SOAK_RESULT_SCHEMA_VERSION)
            .put("runId", soakRunId)
            .put("sdkEnabled", sampleApplication.soakSdkEnabled)
            .put("offlineCollector", sampleApplication.soakOfflineCollector)
            .put("pid", Process.myPid())
            .put("processAgeAtStartMs", soakProcessAgeAtStartMs)
            .put("initDurationNs", sampleApplication.apmInitDurationNs)
            .put("observedDurationMs", observedDurationMs)
            .put("operationCount", soakOperationCount)
            .put("latencySampleCount", sampleCount)
            .put("operationP50Ns", percentile(sortedSamples, PERCENTILE_50))
            .put("operationP95Ns", percentile(sortedSamples, PERCENTILE_95))
            .put("operationMaxNs", sortedSamples.lastOrNull() ?: 0L)
        val serialized = result.toString()
        try {
            // This small write happens after the measured interval and survives process restart.
            File(filesDir, SOAK_RESULT_FILE_NAME).writeText(serialized, Charsets.UTF_8)
        } catch (error: Exception) {
            Log.e(SOAK_LOG_TAG, "Failed to persist device-soak result", error)
        }
        Log.i(SOAK_LOG_TAG, SOAK_RESULT_MARKER + serialized)
        eventsView.text = serialized
    }

    /** Returns one nearest-rank percentile from an already sorted primitive sample array. */
    private fun percentile(sortedSamples: LongArray, percentile: Int): Long {
        if (sortedSamples.isEmpty()) {
            return 0L
        }
        val rank = ((sortedSamples.size * percentile + PERCENT_DENOMINATOR - 1) /
            PERCENT_DENOMINATOR).coerceIn(1, sortedSamples.size)
        return sortedSamples[rank - 1]
    }

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
        /** Intent extra that selects SDK-enabled or control mode for the next process. */
        private const val EXTRA_SOAK_SET_SDK_ENABLED = "apm_soak_set_sdk_enabled"
        /** Intent extra that selects the offline collector for the next process. */
        private const val EXTRA_SOAK_SET_OFFLINE_COLLECTOR = "apm_soak_set_offline_collector"
        /** Intent extra containing one process-segment duration in seconds. */
        private const val EXTRA_SOAK_DURATION_SECONDS = "apm_soak_duration_seconds"
        /** Intent extra containing the synthetic main-thread event rate. */
        private const val EXTRA_SOAK_EVENTS_PER_SECOND = "apm_soak_events_per_second"
        /** Intent extra containing the stable host run identity. */
        private const val EXTRA_SOAK_RUN_ID = "apm_soak_run_id"
        /** Logcat tag reserved for host-side soak orchestration. */
        private const val SOAK_LOG_TAG = "AndroidAPM-Soak"
        /** Machine-readable prefix for persisted mode changes. */
        private const val SOAK_CONFIG_MARKER = "APM_SOAK_CONFIG="
        /** Machine-readable prefix for completed segment summaries. */
        private const val SOAK_RESULT_MARKER = "APM_SOAK_RESULT="
        /** App-private result retrieved through `adb exec-out run-as`. */
        private const val SOAK_RESULT_FILE_NAME = "apm-device-soak-result.json"
        /** Current host/device result schema. */
        private const val SOAK_RESULT_SCHEMA_VERSION = 1
        /** Smallest accepted process-segment duration. */
        private const val MIN_SOAK_DURATION_SECONDS = 1L
        /** Longest supported campaign segment, equal to 72 hours. */
        private const val MAX_SOAK_DURATION_SECONDS = 72L * 60L * 60L
        /** Default synthetic event rate per second. */
        private const val DEFAULT_SOAK_EVENTS_PER_SECOND = 10
        /** Smallest synthetic event rate accepted from the host. */
        private const val MIN_SOAK_EVENTS_PER_SECOND = 1
        /** Largest bounded main-thread event rate accepted from the host. */
        private const val MAX_SOAK_EVENTS_PER_SECOND = 1_000
        /** Maximum primitive latency samples retained per process segment. */
        private const val SOAK_LATENCY_SAMPLE_CAPACITY = 4_096
        /** Main-thread workload cadence in milliseconds. */
        private const val SOAK_TICK_INTERVAL_MS = 1_000L
        /** Milliseconds represented by one second. */
        private const val MILLIS_PER_SECOND = 1_000L
        /** Maximum host run-identity length persisted into events. */
        private const val MAX_SOAK_RUN_ID_LENGTH = 128
        /** Median nearest-rank percentile. */
        private const val PERCENTILE_50 = 50
        /** Tail nearest-rank percentile. */
        private const val PERCENTILE_95 = 95
        /** Percentage denominator used by nearest-rank calculation. */
        private const val PERCENT_DENOMINATOR = 100
        /** Dedicated module name for synthetic soak events. */
        private const val SOAK_EVENT_MODULE = "device_soak"
        /** Dedicated event name for synthetic soak operations. */
        private const val SOAK_EVENT_NAME = "synthetic_main_thread"
        /** Bounded payload shared by control and SDK-enabled workloads. */
        private const val SOAK_PAYLOAD = "bounded-offline-probe"
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
