# AndroidAPM Self-Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an independent, bounded local diagnostics journal that preserves structured AndroidAPM failures and supports status, snapshot, export, and clear without relying on the normal event pipeline.

**Architecture:** `ApmDiagnostics` owns a `DiagnosticRecorder` initialized before event storage and upload infrastructure. Callers write synchronously to Logcat and a bounded memory ring, then non-blockingly enqueue JSONL persistence to a dedicated `ApmExecutors` background thread; rolling files and ZIP export remain independent of `ApmDispatcher`, `EventStore`, and `ApmUploader`.

**Tech Stack:** Kotlin 2.2.21, Android app-private files, `ArrayBlockingQueue`, `Atomic*`, `org.json`, `java.util.zip`, JUnit 4, Robolectric 4.14.1, Gradle 8.13, JDK 17.

## Global Constraints

- Preserve Java 17 bytecode and minSdk 24.
- Add KDoc to every public, internal, and private property and method.
- Add inline comments at branches, loops, exception handling, callbacks, and business-significant assignments.
- Extract non-trivial numbers and strings into named constants.
- Create the diagnostics writer thread through `ApmExecutors`; do not add raw SDK threads or executors.
- Never block a host-facing diagnostic write on file IO or queue capacity.
- Keep the default memory ring at 200 records, writer queue at 256 records, rolling file size at 512 KiB, and retained-file count at 3.
- Limit sanitized messages to 4 KiB and exception text to 16 KiB / 64 frames.
- Do not copy event payloads, request bodies, SQL, user identifiers, access tokens, `defaultContext`, or business context into diagnostics.
- Keep `apm-uploader` independent of `apm-core`; route uploader logs through the existing `UploaderLogger` adapter.
- Do not add automatic diagnostics network upload.
- Use English commit messages with an allowed repository prefix.
- Use JDK `C:\Users\XZQ\.jdks\jbr-17.0.14` for Gradle verification.

## File Map

- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticsConfig.kt`: public bounded configuration and validation.
- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticModels.kt`: public levels, entries, status, and export result.
- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticSanitizer.kt`: internal truncation, credential redaction, and bounded stack formatting.
- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticJsonCodec.kt`: internal JSONL encoding/decoding.
- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticFileStore.kt`: rolling JSONL files, recovery, export, and clear.
- `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticRecorder.kt`: memory ring, bounded queue, writer loop, counters, and failure isolation.
- `apm-core/src/main/java/com/apm/core/diagnostics/ApmDiagnostics.kt`: public facade and runtime ownership.
- `apm-core/src/main/java/com/apm/core/ApmConfig.kt`: group diagnostics configuration.
- `apm-core/src/main/java/com/apm/core/ApmLogger.kt`: fan existing logger calls into diagnostics while preserving Logcat behavior.
- `apm-core/src/main/java/com/apm/core/Apm.kt`: initialize diagnostics first, record structured internal errors, shut down last, and expose health fields.
- `apm-core/src/main/java/com/apm/core/selfmonitor/SdkHealthReport.kt`: include self-diagnostic counters in health fields.
- `apm-core/src/test/java/com/apm/core/diagnostics/*Test.kt`: focused config, sanitizer, codec, file, recorder, and facade tests.
- `apm-core/src/test/java/com/apm/core/ApmConfigTest.kt`: public default/override coverage.
- `apm-core/src/test/java/com/apm/core/selfmonitor/SdkSelfMonitorTest.kt`: health field coverage.
- `apm-sample-app/src/main/java/com/apm/sample/MainActivity.kt`: status/snapshot/export demonstration.
- `apm-sample-app/src/main/res/layout/activity_main.xml` and `values/strings.xml`: diagnostics controls and labels.
- `AGENTS.md`, `README.md`, `docs/Android_APM_项目文档.md`, `docs/architecture/00_整体架构.md`, `docs/architecture/01_apm-core.md`: synchronized feature facts and limits.

---

### Task 1: Public Diagnostics Configuration and Models

**Files:**
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticsConfig.kt`
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticModels.kt`
- Modify: `apm-core/src/main/java/com/apm/core/ApmConfig.kt`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticsConfigTest.kt`
- Test: `apm-core/src/test/java/com/apm/core/ApmConfigTest.kt`

**Interfaces:**
- Produces: `DiagnosticsConfig`, `DiagnosticLevel`, `DiagnosticEntry`, `DiagnosticStatus`, and `DiagnosticExportResult`.
- Produces: `ApmConfig.diagnostics: DiagnosticsConfig` consumed by recorder initialization.

- [ ] **Step 1: Write failing defaults and validation tests**

```kotlin
package com.apm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConfigTest {
    @Test
    fun `defaults are enabled and bounded`() {
        val config = DiagnosticsConfig()
        assertTrue(config.enabled)
        assertEquals(200, config.memoryRecordLimit)
        assertEquals(256, config.writerQueueCapacity)
        assertEquals(512 * 1024L, config.maxFileBytes)
        assertEquals(3, config.retainedFileCount)
        assertTrue(config.includeStackTraces)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `memory limit above hard bound is rejected`() {
        DiagnosticsConfig(memoryRecordLimit = 2_001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive retained file count is rejected`() {
        DiagnosticsConfig(retainedFileCount = 0)
    }
}
```

Add to `ApmConfigTest`:

```kotlin
@Test
fun `default diagnostics config is enabled`() {
    assertTrue(ApmConfig().diagnostics.enabled)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.diagnostics.DiagnosticsConfigTest" --tests "com.apm.core.ApmConfigTest" --no-daemon
```

Expected: compilation fails because `DiagnosticsConfig` and `ApmConfig.diagnostics` do not exist.

- [ ] **Step 3: Add the public models and validated configuration**

Create `DiagnosticsConfig.kt`:

```kotlin
package com.apm.core.diagnostics

/** Bounded configuration for the independent SDK diagnostics journal. */
data class DiagnosticsConfig(
    /** Enables local SDK diagnostics. */
    val enabled: Boolean = true,
    /** Maximum number of recent records retained in memory. */
    val memoryRecordLimit: Int = DEFAULT_MEMORY_RECORD_LIMIT,
    /** Maximum number of records waiting for file persistence. */
    val writerQueueCapacity: Int = DEFAULT_WRITER_QUEUE_CAPACITY,
    /** Maximum bytes per active or retained JSONL segment. */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Number of JSONL segments retained including the active file. */
    val retainedFileCount: Int = DEFAULT_RETAINED_FILE_COUNT,
    /** Whether bounded exception stack traces are retained. */
    val includeStackTraces: Boolean = true
) {
    init {
        require(memoryRecordLimit in 1..MAX_MEMORY_RECORD_LIMIT)
        require(writerQueueCapacity in 1..MAX_WRITER_QUEUE_CAPACITY)
        require(maxFileBytes in MIN_FILE_BYTES..MAX_FILE_BYTES)
        require(retainedFileCount in 1..MAX_RETAINED_FILE_COUNT)
    }

    private companion object {
        /** Default memory-ring size. */
        private const val DEFAULT_MEMORY_RECORD_LIMIT = 200
        /** Default bounded writer-queue size. */
        private const val DEFAULT_WRITER_QUEUE_CAPACITY = 256
        /** Default per-segment byte budget. */
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
        /** Default retained segment count. */
        private const val DEFAULT_RETAINED_FILE_COUNT = 3
        /** Hard memory-ring limit. */
        private const val MAX_MEMORY_RECORD_LIMIT = 2_000
        /** Hard writer-queue limit. */
        private const val MAX_WRITER_QUEUE_CAPACITY = 4_096
        /** Minimum useful segment size. */
        private const val MIN_FILE_BYTES = 64L * 1024L
        /** Hard per-segment disk limit. */
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        /** Hard retained segment count. */
        private const val MAX_RETAINED_FILE_COUNT = 8
    }
}
```

Create `DiagnosticModels.kt` with immutable public types:

```kotlin
package com.apm.core.diagnostics

import java.io.File

/** Severity of an SDK diagnostic record. */
enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR }

/** One sanitized SDK diagnostic record. */
data class DiagnosticEntry(
    /** Monotonic sequence within the current diagnostics runtime. */
    val sequence: Long,
    /** Wall-clock creation time in epoch milliseconds. */
    val timestampMs: Long,
    /** Process-level SDK session identifier. */
    val sessionId: String,
    /** Diagnostic severity. */
    val level: DiagnosticLevel,
    /** SDK component that produced the record. */
    val component: String,
    /** Stable machine-readable error code when available. */
    val code: String?,
    /** Sanitized bounded human-readable message. */
    val message: String,
    /** Android process name. */
    val processName: String,
    /** Producing thread name. */
    val threadName: String,
    /** Exception class name when a throwable exists. */
    val exceptionClass: String?,
    /** Sanitized exception message. */
    val exceptionMessage: String?,
    /** Bounded exception stack trace. */
    val stackTrace: String?,
    /** Stable hash of the retained stack trace. */
    val stackHash: String?
)

/** Current health and resource usage of the diagnostics runtime. */
data class DiagnosticStatus(
    /** Whether the diagnostics runtime is enabled. */
    val enabled: Boolean,
    /** Whether the rolling file sink currently accepts writes. */
    val fileSinkHealthy: Boolean,
    /** Current bounded writer-queue depth. */
    val queueDepth: Int,
    /** Total bytes retained by diagnostic segments. */
    val retainedBytes: Long,
    /** Records dropped by bounded queue pressure. */
    val droppedRecords: Long,
    /** File-sink write failures. */
    val writeFailures: Long,
    /** Corrupt persisted lines skipped while reading. */
    val corruptRecords: Long,
    /** Last sanitized sink failure summary. */
    val lastFailure: String?
) {
    companion object {
        /** Status returned before diagnostics initialization. */
        val INACTIVE = DiagnosticStatus(false, false, 0, 0L, 0L, 0L, 0L, null)
    }
}

/** Result of a bounded diagnostics ZIP export. */
data class DiagnosticExportResult(
    /** Whether export completed successfully. */
    val success: Boolean,
    /** Created ZIP file, or null on failure. */
    val file: File?,
    /** Number of readable records written to the ZIP. */
    val exportedRecords: Int,
    /** Sanitized failure description, or null on success. */
    val errorMessage: String?
)
```

Add `val diagnostics: DiagnosticsConfig = DiagnosticsConfig()` to `ApmConfig` and import the type.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit the public contract**

```powershell
git add apm-core/src/main/java/com/apm/core/diagnostics apm-core/src/main/java/com/apm/core/ApmConfig.kt apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticsConfigTest.kt apm-core/src/test/java/com/apm/core/ApmConfigTest.kt
git commit -m "Feat: Add self-diagnostics configuration and models"
```

### Task 2: Sanitization and JSONL Codec

**Files:**
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticSanitizer.kt`
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticJsonCodec.kt`
- Modify: `apm-core/build.gradle.kts`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticSanitizerTest.kt`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticJsonCodecTest.kt`

**Interfaces:**
- Consumes: `DiagnosticEntry`, `DiagnosticLevel`.
- Produces: `DiagnosticSanitizer.sanitizeMessage`, `sanitizeThrowable`, `DiagnosticJsonCodec.encode`, and `decode`.

- [ ] **Step 1: Write failing sanitizer and round-trip tests**

```kotlin
@Test
fun `credentials are redacted and messages are bounded`() {
    val input = "https://host/path?token=secret&name=ok Authorization: Bearer abc"
    val output = DiagnosticSanitizer.sanitizeMessage(input.repeat(500))
    assertFalse(output.contains("secret"))
    assertFalse(output.contains("Bearer abc"))
    assertTrue(output.length <= 4_096)
}

@Test
fun `entry codec round trips controlled fields`() {
    val entry = diagnosticEntry(message = "disk failed", code = "event_store_write")
    val decoded = DiagnosticJsonCodec.decode(DiagnosticJsonCodec.encode(entry))
    assertEquals(entry, decoded)
}

@Test
fun `invalid JSONL line returns null`() {
    assertNull(DiagnosticJsonCodec.decode("{not-json"))
}
```

Use a private `diagnosticEntry` test factory with every constructor field explicit so later signature changes fail compilation.

- [ ] **Step 2: Run tests and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.diagnostics.DiagnosticSanitizerTest" --tests "com.apm.core.diagnostics.DiagnosticJsonCodecTest" --no-daemon
```

Expected: compilation fails because sanitizer and codec types do not exist.

- [ ] **Step 3: Implement bounded sanitization and stable JSONL**

Implement `DiagnosticSanitizer` with:

```kotlin
internal object DiagnosticSanitizer {
    private const val MAX_MESSAGE_CHARS = 4_096
    private const val MAX_EXCEPTION_CHARS = 16_384
    private const val MAX_STACK_FRAMES = 64
    private const val TRUNCATED_SUFFIX = "...[truncated]"
    private val credentialPattern = Regex(
        "(?i)(token|access_token|refresh_token|password|authorization)(\\s*[:=]\\s*)([^&\\s]+)"
    )
    private val bearerPattern = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")

    fun sanitizeMessage(value: String): String = truncate(
        bearerPattern.replace(credentialPattern.replace(value) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
        }, "Bearer [REDACTED]"),
        MAX_MESSAGE_CHARS
    )

    fun sanitizeThrowable(error: Throwable?, includeStack: Boolean): SanitizedThrowable {
        if (error == null) return SanitizedThrowable(null, null, null, null)
        val frames = if (includeStack) error.stackTrace.take(MAX_STACK_FRAMES) else emptyList()
        val stack = frames.joinToString("\n") { it.toString() }.takeIf { it.isNotEmpty() }
        val boundedStack = stack?.let { truncate(it, MAX_EXCEPTION_CHARS) }
        return SanitizedThrowable(
            className = error.javaClass.name,
            message = error.message?.let(::sanitizeMessage),
            stackTrace = boundedStack,
            stackHash = boundedStack?.let { Integer.toHexString(it.hashCode()) }
        )
    }

    private fun truncate(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars - TRUNCATED_SUFFIX.length) + TRUNCATED_SUFFIX
}

internal data class SanitizedThrowable(
    val className: String?,
    val message: String?,
    val stackTrace: String?,
    val stackHash: String?
)
```

Implement `DiagnosticJsonCodec` with `JSONObject`, `JSONObject.NULL`, a `formatVersion=1` field, enum `name`, and `optNullableString` that distinguishes JSON null from the literal string `"null"`. `decode` catches `RuntimeException` and returns `null`; it must never log through `ApmLogger`.

Add `testImplementation(libs.robolectric)` to `apm-core/build.gradle.kts` and annotate the codec test with `@RunWith(RobolectricTestRunner::class)` so Android's JSON implementation is executable in tests.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command. Expected: sanitizer and codec tests pass.

- [ ] **Step 5: Commit codec and privacy boundaries**

```powershell
git add apm-core/build.gradle.kts apm-core/src/main/java/com/apm/core/diagnostics apm-core/src/test/java/com/apm/core/diagnostics
git commit -m "Feat: Add bounded diagnostic record encoding"
```

### Task 3: Rolling File Store and Export

**Files:**
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticFileStore.kt`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticFileStoreTest.kt`

**Interfaces:**
- Consumes: `DiagnosticsConfig`, `DiagnosticEntry`, `DiagnosticJsonCodec`.
- Produces: `append(entry)`, `readAll()`, `retainedBytes()`, `exportTo(target, status)`, and `clear()`.

- [ ] **Step 1: Write failing rotation, recovery, corruption, export, and clear tests**

```kotlin
@Test
fun `append rotates and enforces retained segment count`() {
    val store = fileStore(maxFileBytes = 64L * 1024L, retainedFileCount = 2)
    repeat(400) { store.append(diagnosticEntry(sequence = it.toLong(), message = "x".repeat(400))) }
    val segments = tempDir.listFiles { file -> file.name.endsWith(".jsonl") }.orEmpty()
    assertTrue(segments.size <= 2)
    assertTrue(store.retainedBytes() <= 2L * 64L * 1024L + 1_024L)
}

@Test
fun `corrupt lines are skipped and counted`() {
    val store = fileStore()
    store.append(diagnosticEntry(sequence = 1L))
    File(tempDir, "diagnostics.jsonl").appendText("{broken\n")
    val read = store.readAll()
    assertEquals(1, read.entries.size)
    assertEquals(1L, read.corruptRecords)
}

@Test
fun `export contains manifest and readable JSONL`() {
    val store = fileStore()
    store.append(diagnosticEntry(sequence = 1L))
    val target = File(tempDir, "diagnostics.zip")
    val result = store.exportTo(target, DiagnosticStatus.INACTIVE)
    assertTrue(result.success)
    ZipFile(target).use { zip ->
        assertNotNull(zip.getEntry("manifest.json"))
        assertNotNull(zip.getEntry("diagnostics.jsonl"))
    }
}

@Test
fun `clear removes all segments`() {
    val store = fileStore()
    store.append(diagnosticEntry(sequence = 1L))
    assertTrue(store.clear())
    assertTrue(tempDir.listFiles { file -> file.name.endsWith(".jsonl") }.orEmpty().isEmpty())
}
```

- [ ] **Step 2: Run the file-store test and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.diagnostics.DiagnosticFileStoreTest" --no-daemon
```

Expected: compilation fails because `DiagnosticFileStore` does not exist.

- [ ] **Step 3: Implement locked rolling JSONL persistence**

Implement a focused store behind an injectable internal contract so recorder tests can provide blocking and throwing fakes without adding test-only methods to production classes:

```kotlin
internal interface DiagnosticStore {
    fun append(entry: DiagnosticEntry)
    fun readAll(): DiagnosticReadResult
    fun retainedBytes(): Long
    fun exportTo(target: File, status: DiagnosticStatus): DiagnosticExportResult
    fun clear(): Boolean
}

internal class DiagnosticFileStore(
    private val directory: File,
    private val config: DiagnosticsConfig
) : DiagnosticStore {
    private val lock = Any()

    override fun append(entry: DiagnosticEntry) = synchronized(lock) {
        ensureDirectory()
        val line = DiagnosticJsonCodec.encode(entry) + "\n"
        if (activeFile().length() + line.toByteArray(Charsets.UTF_8).size > config.maxFileBytes) rotate()
        activeFile().appendText(line, Charsets.UTF_8)
    }

    override fun readAll(): DiagnosticReadResult = synchronized(lock) {
        var corrupt = 0L
        val entries = segmentFilesOldestFirst().flatMap { file ->
            file.useLines(Charsets.UTF_8) { lines ->
                lines.mapNotNull { line ->
                    DiagnosticJsonCodec.decode(line).also { if (it == null) corrupt++ }
                }.toList()
            }
        }
        DiagnosticReadResult(entries, corrupt)
    }

    override fun retainedBytes(): Long = synchronized(lock) { segmentFiles().sumOf(File::length) }

    override fun clear(): Boolean = synchronized(lock) {
        segmentFiles().all { file -> !file.exists() || file.delete() }
    }
}
```

Complete the class with KDoc, named constants `diagnostics.jsonl`, `diagnostics.1.jsonl`, ZIP entry names, directory creation, descending rotation, deletion of the oldest segment before rename, deterministic oldest-first reads, and `exportTo` that writes a controlled manifest plus one merged `diagnostics.jsonl` entry. Tests inspect observable files in the temporary directory rather than adding test-only production accessors. Catch `IOException` only at the public store boundary and return `DiagnosticExportResult(false, null, 0, sanitizedMessage)` for export failures; append failures remain exceptions for the recorder to isolate.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command. Expected: all file-store tests pass.

- [ ] **Step 5: Commit local persistence**

```powershell
git add apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticFileStore.kt apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticFileStoreTest.kt
git commit -m "Feat: Persist bounded diagnostic journal"
```

### Task 4: Non-Blocking Recorder and Public Facade

**Files:**
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticRecorder.kt`
- Create: `apm-core/src/main/java/com/apm/core/diagnostics/ApmDiagnostics.kt`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticRecorderTest.kt`
- Test: `apm-core/src/test/java/com/apm/core/diagnostics/ApmDiagnosticsTest.kt`

**Interfaces:**
- Consumes: all earlier diagnostics types, `ApmExecutors`, `ProcessSessionId`.
- Produces: non-blocking `record`, bounded `flush`, `snapshot`, `status`, `exportTo`, `clear`, and `shutdown`.

- [ ] **Step 1: Write failing memory, overflow, sink-failure, and facade tests**

```kotlin
@Test
fun `memory snapshot is newest first and bounded`() {
    val recorder = recorder(memoryLimit = 2)
    recorder.record(DiagnosticLevel.INFO, "core", null, "one", null)
    recorder.record(DiagnosticLevel.INFO, "core", null, "two", null)
    recorder.record(DiagnosticLevel.ERROR, "core", "three", "three", null)
    assertEquals(listOf("three", "two"), recorder.snapshot(10).map(DiagnosticEntry::message))
}

@Test
fun `queue overflow never blocks and counts drops`() {
    val blockingStore = BlockingDiagnosticStore()
    val recorder = recorder(queueCapacity = 1, store = blockingStore)
    recorder.record(DiagnosticLevel.INFO, "core", null, "first", null)
    recorder.record(DiagnosticLevel.INFO, "core", null, "second", null)
    recorder.record(DiagnosticLevel.INFO, "core", null, "third", null)
    assertTrue(recorder.status().droppedRecords >= 1L)
}

@Test
fun `file failure keeps memory snapshot and avoids recursion`() {
    val recorder = recorder(store = ThrowingDiagnosticStore())
    recorder.record(DiagnosticLevel.ERROR, "storage", "write", "failed", IOException("disk full"))
    recorder.flush(1_000L)
    assertEquals("failed", recorder.snapshot(1).single().message)
    assertEquals(1L, recorder.status().writeFailures)
    assertFalse(recorder.status().fileSinkHealthy)
}

@Test
fun `facade before initialization is safe`() {
    ApmDiagnostics.resetForTest()
    assertEquals(DiagnosticStatus.INACTIVE, ApmDiagnostics.status())
    assertTrue(ApmDiagnostics.snapshot(20).isEmpty())
}
```

- [ ] **Step 2: Run recorder/facade tests and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.diagnostics.DiagnosticRecorderTest" --tests "com.apm.core.diagnostics.ApmDiagnosticsTest" --no-daemon
```

Expected: compilation fails because recorder and facade do not exist.

- [ ] **Step 3: Implement the recorder**

Use a short synchronized memory ring, `ArrayBlockingQueue`, atomic counters, and an `ApmExecutors.startThread("diagnostics-writer")` loop:

```kotlin
internal class DiagnosticRecorder(
    private val config: DiagnosticsConfig,
    private val processName: String,
    private val sessionId: String,
    private val store: DiagnosticStore
) {
    private val sequence = AtomicLong(0L)
    private val queue = ArrayBlockingQueue<DiagnosticEntry>(config.writerQueueCapacity)
    private val memory = ArrayDeque<DiagnosticEntry>(config.memoryRecordLimit)
    private val memoryLock = Any()
    private val running = AtomicBoolean(true)
    private val dropped = AtomicLong(0L)
    private val writeFailures = AtomicLong(0L)
    private val corrupt = AtomicLong(0L)
    private val fileHealthy = AtomicBoolean(true)
    private val lastFailure = AtomicReference<String?>(null)
    private val writer = ApmExecutors.startThread("diagnostics-writer", block = Runnable(::writerLoop))

    fun record(level: DiagnosticLevel, component: String, code: String?, message: String, error: Throwable?) {
        val throwable = DiagnosticSanitizer.sanitizeThrowable(error, config.includeStackTraces)
        val entry = DiagnosticEntry(
            sequence.incrementAndGet(), System.currentTimeMillis(), sessionId, level,
            DiagnosticSanitizer.sanitizeMessage(component), code?.let(DiagnosticSanitizer::sanitizeMessage),
            DiagnosticSanitizer.sanitizeMessage(message), processName, Thread.currentThread().name,
            throwable.className, throwable.message, throwable.stackTrace, throwable.stackHash
        )
        synchronized(memoryLock) {
            if (memory.size == config.memoryRecordLimit) memory.removeFirst()
            memory.addLast(entry)
        }
        if (!queue.offer(entry)) {
            if (level == DiagnosticLevel.ERROR) queue.poll()
            if (level != DiagnosticLevel.ERROR || !queue.offer(entry)) dropped.incrementAndGet()
        }
    }
}
```

Complete `writerLoop` with timed polling, a flush barrier represented by a separate sealed queue item, cooldown-based store retry, raw `android.util.Log.e` on sink failure, and no call back into `ApmLogger`. `snapshot(limit)` clamps to `1..memoryRecordLimit`, copies under lock, and reverses outside the lock. `shutdown` changes `running`, flushes for at most 1,000 ms, interrupts, joins for the remaining budget, and never throws.

- [ ] **Step 4: Implement the facade**

```kotlin
object ApmDiagnostics {
    private val recorder = AtomicReference<DiagnosticRecorder?>(null)

    fun status(): DiagnosticStatus = recorder.get()?.status() ?: DiagnosticStatus.INACTIVE

    fun snapshot(limit: Int = DEFAULT_SNAPSHOT_LIMIT): List<DiagnosticEntry> =
        recorder.get()?.snapshot(limit).orEmpty()

    fun exportTo(targetFile: File): DiagnosticExportResult = recorder.get()?.exportTo(targetFile)
        ?: DiagnosticExportResult(false, null, 0, "Diagnostics are not initialized")

    fun clear(): Boolean = recorder.get()?.clear() ?: false

    internal fun initialize(application: Application, config: DiagnosticsConfig, processName: String): DiagnosticRecorder? {
        if (!config.enabled) return null
        val created = DiagnosticRecorder(
            config, processName, ProcessSessionId.get(),
            DiagnosticFileStore(File(application.filesDir, DIAGNOSTICS_DIRECTORY), config)
        )
        recorder.getAndSet(created)?.shutdown()
        return created
    }

    internal fun shutdown() { recorder.get()?.shutdown() }
}
```

Add internal `record` and `resetForTest` methods, named constants, complete KDoc, and source-safe behavior when disabled or uninitialized.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the Step 2 command. Expected: all recorder and facade tests pass without leaked non-daemon threads.

- [ ] **Step 6: Commit the diagnostics runtime**

```powershell
git add apm-core/src/main/java/com/apm/core/diagnostics apm-core/src/test/java/com/apm/core/diagnostics
git commit -m "Feat: Add independent diagnostics runtime"
```

### Task 5: Wire Diagnostics into APM Logging and Self-Monitoring

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/ApmLogger.kt`
- Modify: `apm-core/src/main/java/com/apm/core/Apm.kt`
- Modify: `apm-core/src/main/java/com/apm/core/selfmonitor/SdkHealthReport.kt`
- Modify: `apm-core/src/test/java/com/apm/core/selfmonitor/SdkSelfMonitorTest.kt`
- Create: `apm-core/src/test/java/com/apm/core/ApmDiagnosticsIntegrationTest.kt`
- Modify: `apm-core/src/test/java/com/apm/core/UploaderFactoryTest.kt`

**Interfaces:**
- Consumes: `ApmDiagnostics` facade and recorder.
- Produces: diagnostics-first init, structured `recordInternalError`, lifecycle shutdown, and complete `sdk_health` fields.

- [ ] **Step 1: Write failing health-field and logger integration tests**

Add to `SdkSelfMonitorTest`:

```kotlin
@Test
fun `health fields include internal and diagnostic failures`() {
    val report = SdkHealthReport(
        emitCount = 1L, dropCount = 0L, queueSize = 0,
        avgUploadLatencyMs = 2L, maxUploadLatencyMs = 3L,
        internalErrorCount = 4L, diagnosticDroppedCount = 5L,
        diagnosticWriteFailureCount = 6L
    )
    val fields = report.toCoreHealthFields()
    assertEquals(4L, fields["internalErrorCount"])
    assertEquals(5L, fields["diagnosticDroppedCount"])
    assertEquals(6L, fields["diagnosticWriteFailureCount"])
}
```

Write an Android-aware integration test that initializes diagnostics with a temporary app files directory, calls `Apm.recordInternalError("ipc_write", IOException("disk full"))`, flushes, and asserts the snapshot entry has level `ERROR`, code `ipc_write`, exception class, and bounded stack. Add an uploader adapter assertion that an HTTP/uploader warning is present in the diagnostic snapshot without introducing an `apm-uploader -> apm-core` dependency.

- [ ] **Step 2: Run selected tests and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.selfmonitor.SdkSelfMonitorTest" --tests "com.apm.core.ApmDiagnosticsIntegrationTest" --tests "com.apm.core.UploaderFactoryTest" --no-daemon
```

Expected: health fields and structured integration behavior are missing.

- [ ] **Step 3: Fan `AndroidApmLogger` into diagnostics**

Change its constructor to accept an optional recorder and preserve Logcat semantics:

```kotlin
internal class AndroidApmLogger(
    private val enabled: Boolean,
    private val diagnostics: DiagnosticRecorder?
) : ApmLogger {
    override fun d(message: String) {
        if (enabled) {
            Log.d(TAG, message)
            diagnostics?.record(DiagnosticLevel.DEBUG, CORE_COMPONENT, null, message, null)
        }
    }

    override fun w(message: String) {
        Log.w(TAG, message)
        diagnostics?.record(DiagnosticLevel.WARN, CORE_COMPONENT, null, message, null)
    }

    override fun e(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
        diagnostics?.record(DiagnosticLevel.ERROR, CORE_COMPONENT, null, message, throwable)
    }
}
```

Retain a test-friendly default `diagnostics = null` constructor parameter if existing focused tests construct the logger directly.

- [ ] **Step 4: Initialize diagnostics before normal infrastructure and structure internal errors**

In `Apm.doInit`:

```kotlin
val processName = application.currentProcessNameCompat()
val diagnostics = ApmDiagnostics.initialize(application, config.diagnostics, processName)
val logger = AndroidApmLogger(config.debugLogging, diagnostics)
diagnostics?.record(DiagnosticLevel.INFO, CORE_MODULE, "session_start", "APM initialization started", null)
```

Wrap `doInit` from `init` so initialization exceptions are recorded and then rethrown, preserving host-visible behavior. Change `recordInternalError` to record through `ApmDiagnostics` even when normal state is unavailable, and only increment the current `SdkSelfMonitor` when state exists:

```kotlin
fun recordInternalError(tag: String, error: Throwable? = null) {
    state?.context?.selfMonitor?.recordInternalError(tag)
    ApmDiagnostics.record(DiagnosticLevel.ERROR, CORE_MODULE, tag, "Internal SDK error", error)
}
```

After normal dispatcher shutdown, record a session-stop entry, bounded-flush, and close the diagnostics writer. Preserve export/status/snapshot access to the stopped recorder.

- [ ] **Step 5: Emit complete SDK health fields**

Add fields with zero defaults to `SdkHealthReport` and implement:

```kotlin
internal fun SdkHealthReport.toCoreHealthFields(): Map<String, Any> = mapOf(
    "emitCount" to emitCount,
    "dropCount" to dropCount,
    "dropRate" to dropRate,
    "queueSize" to queueSize,
    "avgUploadLatencyMs" to avgUploadLatencyMs,
    "maxUploadLatencyMs" to maxUploadLatencyMs,
    "internalErrorCount" to internalErrorCount,
    "diagnosticDroppedCount" to diagnosticDroppedCount,
    "diagnosticWriteFailureCount" to diagnosticWriteFailureCount
)
```

At each monitoring interval, read `ApmDiagnostics.status()`, copy its counters into the report, and pass `report.toCoreHealthFields()` to `emit`. This fixes the existing omission of `internalErrorCount` from the runtime `sdk_health` event.

- [ ] **Step 6: Run selected and full core tests**

Run Step 2, then:

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --no-daemon
```

Expected: all core tests pass.

- [ ] **Step 7: Commit runtime integration**

```powershell
git add apm-core/src/main apm-core/src/test
git commit -m "Feat: Integrate SDK self-diagnostics"
```

### Task 6: Sample Support Flow and Documentation Synchronization

**Files:**
- Modify: `apm-sample-app/src/main/java/com/apm/sample/MainActivity.kt`
- Modify: `apm-sample-app/src/main/res/layout/activity_main.xml`
- Modify: `apm-sample-app/src/main/res/values/strings.xml`
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/Android_APM_项目文档.md`
- Modify: `docs/PROJECT_HANDOFF.md`
- Modify: `docs/architecture/00_整体架构.md`
- Modify: `docs/architecture/01_apm-core.md`

**Interfaces:**
- Consumes: `ApmDiagnostics.status`, `snapshot`, and `exportTo`.
- Produces: permission-free app-private export demonstration and synchronized user-facing documentation.

- [ ] **Step 1: Add a permission-free sample diagnostics section**

Add buttons for “Diagnostic status” and “Export diagnostics”. In `MainActivity`, run export on `ApmExecutors` is not public to the sample, so use a named sample-local single-thread executor and shut it down in `onDestroy`:

```kotlin
private val diagnosticsExecutor = Executors.newSingleThreadExecutor()

private fun showDiagnosticStatus() {
    val status = ApmDiagnostics.status()
    showToast("diagnostics healthy=${status.fileSinkHealthy} dropped=${status.droppedRecords}")
}

private fun exportDiagnostics() {
    diagnosticsExecutor.execute {
        val target = File(cacheDir, "android-apm-diagnostics.zip")
        val result = ApmDiagnostics.exportTo(target)
        runOnUiThread {
            showToast(if (result.success) "Exported ${result.exportedRecords} records" else "Export failed")
        }
    }
}
```

Use named string resources, KDoc, inline callback comments, and named constants. Do not add storage permissions or expose a `file://` URI. The sample demonstrates creation only; production sharing remains host-owned through a `FileProvider` or support flow.

- [ ] **Step 2: Update all source-of-truth documents**

Document:

- the distinction between event Logcat fallback and the self-diagnostic journal;
- defaults and hard resource bounds;
- independent failure domain and asynchronous in-flight-loss caveat;
- public status/snapshot/export/clear APIs;
- no automatic diagnostic upload;
- privacy exclusions and app-private storage;
- self-monitor health counter additions;
- focused and full verification actually executed.

Increment repository source/test counts only after computing them from the final tree with `rg --files`, excluding `smoke-tests/**` from SDK counts.

- [ ] **Step 3: Run sample assembly and documentation checks**

```powershell
./gradlew.bat :apm-sample-app:assembleDebug --no-daemon
python docs/verify_docs.py
git diff --check
```

Expected: sample assembles, docs verifier exits 0, and diff check is empty.

- [ ] **Step 4: Commit sample and documentation**

```powershell
git add apm-sample-app AGENTS.md README.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md docs/architecture/00_整体架构.md docs/architecture/01_apm-core.md
git commit -m "Docs: Document SDK self-diagnostics"
```

### Task 7: Full Verification and Final Evidence

**Files:**
- Modify only if evidence changed: `AGENTS.md`, `docs/Android_APM_项目文档.md`, `docs/PROJECT_HANDOFF.md`

**Interfaces:**
- Consumes: complete implementation.
- Produces: current-tip build, test, lint, publish, consumer, and Git evidence.

- [ ] **Step 1: Run fresh unit and debug build verification under JDK 17**

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
```

Expected: all commands exit 0 with no failed tests.

- [ ] **Step 2: Run release, lint, publication, and consumer verification**

```powershell
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

Expected: all commands exit 0 and the isolated consumer resolves the current `com.apm:*-0.1.0` publications.

- [ ] **Step 3: Compute fresh test evidence and synchronize only verified numbers**

Use PowerShell XML parsing to total JUnit suites, tests, failures, errors, and skips under project test-result directories. Count lint HTML reports and release artifact bytes. Update the three evidence documents only when the live outputs differ from their current values.

- [ ] **Step 4: Run required finish checks**

```powershell
git status --short --branch
python docs/verify_docs.py
git diff --check
git log --oneline -n 10
```

Expected: only intentional evidence-document changes remain, docs verification exits 0, and diff check is empty.

- [ ] **Step 5: Commit final evidence when needed**

```powershell
git add AGENTS.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md
git commit -m "Docs: Record self-diagnostics verification"
```

Skip this commit when live evidence already matches the documentation and no files changed.

- [ ] **Step 6: Verify final repository state**

```powershell
git status --short --branch
git log --oneline -n 10
git diff origin/develop...HEAD --stat
```

Expected: clean working tree on `develop`; local commits contain only the approved diagnostics feature, tests, sample, and synchronized documentation. Do not push unless the user explicitly asks for publishing.
