# AndroidAPM Client Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish all reliable and testable Android-client responsibilities while consolidating hosted and device-lab work into one cloud backlog document.

**Architecture:** Event identity is added append-only across model and wire formats; SQLite schema v3 introduces transactional lease ownership; monitoring modules expose public-API integrations and deprecate unsupported automatic switches; a non-published benchmark test module targets the sample app. Documentation uses one canonical cloud boundary and a source-backed Matrix/KOOM comparison.

**Tech Stack:** Kotlin 2.2.21, Android API 24-34, SQLiteOpenHelper, JUnit 4, Robolectric 4.14.1, AndroidX Benchmark/Macrobenchmark, Gradle 8.13, AGP 8.13.2, JDK 17.

## Global Constraints

- Preserve source compatibility in the v1.x line by adding trailing defaulted properties and retaining deprecated fields.
- Durable codec version 1 remains readable and Protobuf field numbers remain unchanged.
- SQLite version 2 rows migrate without deletion.
- No hidden Android API, BinderProxy reflection hook, arbitrary WebView takeover, or generic GPU-overdraw claim.
- Every production behavior begins with a focused failing test and observed RED result.
- SDK threads and handler threads are created through `ApmExecutors`.
- Degraded swallowed exceptions call `Apm.recordInternalError`; diagnostics sink errors remain non-recursive.
- Public/private members receive KDoc and significant control flow receives inline comments.

---

### Task 1: Stable event identity in model and durable codec

**Files:**
- Create: `apm-model/src/main/kotlin/com/apm/model/ApmEventIdGenerator.kt`
- Modify: `apm-model/src/main/kotlin/com/apm/model/ApmEvent.kt`
- Modify: `apm-model/src/main/kotlin/com/apm/model/ApmEventCodec.kt`
- Test: `apm-model/src/test/kotlin/com/apm/model/ApmEventTest.kt`
- Test: `apm-model/src/test/kotlin/com/apm/model/ApmEventCodecTest.kt`

**Interfaces:**
- Produces: `ApmEvent.eventId: String`, `ApmEventIdGenerator.next(): String`, durable codec v2 with v1 reader.

- [ ] **Step 1: Write failing identity and codec compatibility tests**

```kotlin
@Test fun `new events have unique stable identities`() {
    val first = ApmEvent(module = "core", name = "one")
    val second = ApmEvent(module = "core", name = "two")
    assertTrue(first.eventId.isNotBlank())
    assertNotEquals(first.eventId, second.eventId)
    assertEquals(first.eventId, first.copy(name = "copy").eventId)
}

@Test fun `durable codec round trip preserves event identity`() {
    val event = ApmEvent(module = "core", name = "saved")
    assertEquals(event, ApmEventCodec.decode(ApmEventCodec.encode(event)))
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-model:test --tests "com.apm.model.*" --no-daemon`

Expected: compilation fails because `eventId` does not exist.

- [ ] **Step 3: Implement generator, trailing property, codec v2, and explicit v1 decoder**

```kotlin
internal object ApmEventIdGenerator {
    private val processPrefix = UUID.randomUUID().toString()
    private val sequence = AtomicLong(0L)
    fun next(): String = "$processPrefix-${sequence.incrementAndGet().toString(16)}"
}

data class ApmEvent(
    // existing fields unchanged
    val extras: Map<String, String> = emptyMap(),
    val eventId: String = ApmEventIdGenerator.next()
)
```

Codec v2 writes `eventId` after `extras`; decoder dispatches version 1 to the old field order with `eventId = ""`, and version 2 reads the appended string.

- [ ] **Step 4: Run GREEN and commit**

Run the Task 1 command. Expected: PASS.

Commit: `Feat: Add stable event identity`

---

### Task 2: Propagate event identity through every wire format

**Files:**
- Modify: `apm-model/src/main/kotlin/com/apm/model/ApmEvent.kt`
- Modify: `apm-model/src/main/kotlin/com/apm/model/ProtobufWriter.kt`
- Modify: `apm-model/src/main/kotlin/com/apm/model/ProtobufSerializer.kt`
- Modify: `apm-model/src/main/proto/apm_event.proto`
- Test: `apm-model/src/test/kotlin/com/apm/model/ApmEventTest.kt`
- Test: `apm-model/src/test/kotlin/com/apm/model/ProtobufSerializerTest.kt`

**Interfaces:**
- Consumes: `ApmEvent.eventId` from Task 1.
- Produces: line-protocol `eventId=...`; Protobuf field `14` named `event_id`.

- [ ] **Step 1: Write failing line and protobuf tests**

```kotlin
@Test fun `line protocol contains event identity`() {
    val line = ApmEvent(module = "core", name = "line", eventId = "event-7").toLineProtocol()
    assertTrue(line.contains("eventId=event-7"))
}
```

Add a Protobuf test that decodes field 14 from the produced bytes and asserts `event-7`.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-model:test --tests "com.apm.model.*" --no-daemon`

Expected: line assertion fails and field 14 is absent.

- [ ] **Step 3: Append both fields**

```proto
  // Stable client-generated identity used for server-side deduplication.
  string event_id = 14;
```

Add `eventId` to the line segments and write Protobuf tag `(14 shl 3) or 2` through the existing bounded string writer.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Propagate event identity`

---

### Task 3: Migrate SQLite schema without losing rows

**Files:**
- Modify: `apm-storage/src/main/java/com/apm/storage/EventDbHelper.kt`
- Modify: `apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt`
- Test: `apm-storage/src/test/java/com/apm/storage/SQLiteEventStoreTest.kt`
- Create: `apm-storage/src/test/java/com/apm/storage/EventDbMigrationTest.kt`

**Interfaces:**
- Produces: schema v3 columns `event_id`, `lease_owner`, `lease_expires_at`; stable `legacy-<rowId>` migration IDs.

- [ ] **Step 1: Write a failing v2-to-v3 migration test**

Create a version-2 database with one encoded v1 row, reopen through version 3, and assert the row remains, `event_id` is non-empty and stable across two reads, and lease columns are unowned/zero.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-storage:testDebugUnitTest --tests "com.apm.storage.EventDbMigrationTest" --no-daemon`

Expected: missing v3 columns.

- [ ] **Step 3: Implement additive migration**

```sql
ALTER TABLE events ADD COLUMN event_id TEXT;
ALTER TABLE events ADD COLUMN lease_owner TEXT;
ALTER TABLE events ADD COLUMN lease_expires_at INTEGER NOT NULL DEFAULT 0;
UPDATE events SET event_id = 'legacy-' || id WHERE event_id IS NULL OR event_id = '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_event_id ON events(event_id);
CREATE INDEX IF NOT EXISTS idx_lease_availability ON events(lease_expires_at, priority, timestamp);
```

New inserts store `event.eventId`. Reads copy the row ID column into v1-decoded events whose payload ID is blank.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Migrate outbox identity schema`

---

### Task 4: Add transactional claim, lease, expiry, and owner checks

**Files:**
- Modify: `apm-storage/src/main/java/com/apm/storage/EventStore.kt`
- Modify: `apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt`
- Test: `apm-storage/src/test/java/com/apm/storage/SQLiteEventStoreTest.kt`

**Interfaces:**
- Produces: `claimPending`, `acknowledgeClaim`, `failClaim`, `releaseClaims`.

- [ ] **Step 1: Write failing two-owner tests**

```kotlin
@Test fun `active claim excludes another owner and expiry reclaims it`() {
    store.append(event("one"))
    assertEquals(1, store.claimPending("worker-a", 10, 1_000L, 500L).size)
    assertTrue(store.claimPending("worker-b", 10, 1_100L, 500L).isEmpty())
    assertEquals(1, store.claimPending("worker-b", 10, 1_501L, 500L).size)
}

@Test fun `owner mismatch cannot acknowledge another claim`() {
    store.append(event("one"))
    val id = store.claimPending("worker-a", 1, 1_000L, 500L).single().id
    assertEquals(0, store.acknowledgeClaim("worker-b", listOf(id)))
    assertEquals(1, store.pendingCount())
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-storage:testDebugUnitTest --tests "com.apm.storage.SQLiteEventStoreTest" --no-daemon`

- [ ] **Step 3: Implement transactional production semantics**

```kotlin
fun claimPending(ownerId: String, limit: Int, nowMs: Long, leaseDurationMs: Long): List<PendingEvent>
fun acknowledgeClaim(ownerId: String, ids: List<Long>): Int
fun failClaim(ownerId: String, ids: List<Long>)
fun releaseClaims(ownerId: String): Int
```

Use one writable transaction to select rows satisfying `lease_owner IS NULL OR lease_expires_at <= nowMs`, update selected IDs to the owner and expiry, then decode those exact rows. Acknowledge/fail SQL includes `lease_owner = ?`.

- [ ] **Step 4: Add two-store concurrency coverage, run GREEN, and commit**

Open two `SQLiteEventStore` instances on the same database, race claims from two executors, and assert every row ID belongs to exactly one returned batch.

Commit: `Feat: Add outbox claim leases`

---

### Task 5: Move PersistentUploadWorker onto claims

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/ApmConfig.kt`
- Modify: `apm-core/src/main/java/com/apm/core/PersistentUploadWorker.kt`
- Modify: `apm-core/src/main/java/com/apm/core/ApmDispatcher.kt`
- Test: `apm-core/src/test/java/com/apm/core/PersistentUploadWorkerTest.kt`

**Interfaces:**
- Consumes: Task 4 claim API.
- Produces: unique worker owner, configurable `uploadLeaseDurationMs`, owner-aware success/failure/shutdown.

- [ ] **Step 1: Rewrite fake store around claim ownership and add failing worker tests**

Test success calls `acknowledgeClaim`, failure calls `failClaim`, shutdown calls `releaseClaims`, and two workers never upload the same active claim.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-core:testDebugUnitTest --tests "com.apm.core.PersistentUploadWorkerTest" --no-daemon`

- [ ] **Step 3: Implement owner lifecycle**

```kotlin
private val ownerId = "${ProcessSessionId.get()}-${WORKER_SEQUENCE.incrementAndGet()}"
```

Replace `readPending/deletePending/markRetry` with claim/acknowledge/fail, validate lease duration, and release on shutdown in a failure-isolated cleanup phase.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Refactor: Claim durable upload batches`

---

### Task 6: Make IPC configuration truthful and add measurement aggregation

**Files:**
- Modify: `apm-ipc/src/main/java/com/apm/ipc/IpcConfig.kt`
- Modify: `apm-ipc/src/main/java/com/apm/ipc/IpcModule.kt`
- Create: `apm-ipc/src/main/java/com/apm/ipc/BinderCallAggregator.kt`
- Test: `apm-ipc/src/test/java/com/apm/ipc/IpcConfigTest.kt`
- Test: `apm-ipc/src/test/java/com/apm/ipc/IpcModuleTest.kt`

**Interfaces:**
- Produces: `traceBinderCall(interfaceName, methodName, block)` and bounded aggregation event.

- [ ] **Step 1: Write failing config, trace-success, trace-failure, and aggregation tests**

Assert default `enableBinderHook == false`; traced blocks return/throw unchanged and still record duration; the Nth completion emits one aggregate with exact count/max/main-thread count.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-ipc:testDebugUnitTest --no-daemon`

- [ ] **Step 3: Implement public-API tracing and aggregation**

```kotlin
fun <T> traceBinderCall(interfaceName: String, methodName: String, block: () -> T): T {
    val startedAt = SystemClock.elapsedRealtimeNanos()
    return try { block() } finally {
        onBinderCallComplete(interfaceName, methodName,
            TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - startedAt))
    }
}
```

Deprecate `enableBinderHook` with `ReplaceWith("false")`; when explicitly true, record one diagnostic warning and install nothing.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Add truthful Binder tracing`

---

### Task 7: Add explicit WebView client wrappers

**Files:**
- Modify: `apm-webview/src/main/java/com/apm/webview/WebviewConfig.kt`
- Modify: `apm-webview/src/main/java/com/apm/webview/WebviewModule.kt`
- Create: `apm-webview/src/main/java/com/apm/webview/ApmWebViewClient.kt`
- Create: `apm-webview/src/main/java/com/apm/webview/ApmWebChromeClient.kt`
- Test: `apm-webview/src/test/java/com/apm/webview/WebviewModuleTest.kt`

**Interfaces:**
- Produces: `install(webView, delegateClient, delegateChromeClient)` and `uninstall(webView)`.

- [ ] **Step 1: Write failing delegate-preservation and uninstall tests**

Use a Robolectric WebView and counting delegates. Assert wrapper callbacks reach both monitoring and delegate exactly once, delegate return values are preserved, and uninstall restores supplied delegates.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-webview:testDebugUnitTest --no-daemon`

- [ ] **Step 3: Implement public WebView wrappers**

Wrappers call module page/resource/console methods inside failure isolation and then forward every overridden callback. Track installations in a `WeakHashMap<WebView, Installation>` protected by a lock. Deprecate `enableAutoRegister` and default it to false.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Add explicit WebView instrumentation`

---

### Task 8: Monitor explicitly registered thread pools

**Files:**
- Modify: `apm-thread-monitor/src/main/java/com/apm/threadmonitor/ThreadMonitorConfig.kt`
- Modify: `apm-thread-monitor/src/main/java/com/apm/threadmonitor/ThreadMonitorModule.kt`
- Create: `apm-thread-monitor/src/main/java/com/apm/threadmonitor/ThreadPoolRegistry.kt`
- Test: `apm-thread-monitor/src/test/java/com/apm/threadmonitor/ThreadMonitorModuleTest.kt`

**Interfaces:**
- Produces: `registerThreadPool(name, executor)`, `unregisterThreadPool(name)`.

- [ ] **Step 1: Write failing backlog and weak-retention tests**

Register a blocked `ThreadPoolExecutor`, enqueue beyond threshold, run a snapshot, and assert backlog fields. Remove the strong test reference, force registry cleanup, and assert the entry disappears.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-thread-monitor:testDebugUnitTest --no-daemon`

- [ ] **Step 3: Implement registry and snapshot integration**

Store `WeakReference<ThreadPoolExecutor>` entries by bounded sanitized name. Snapshot queue size, active count, pool size, maximum pool size, and completed task count. Deprecate and disable `enableThreadLeakDetect` and its threshold.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Monitor registered thread pools`

---

### Task 9: Add API 24+ frame metrics and deprecate overdraw

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/ApmExecutors.kt`
- Modify: `apm-render/src/main/java/com/apm/render/RenderConfig.kt`
- Modify: `apm-render/src/main/java/com/apm/render/RenderModule.kt`
- Create: `apm-render/src/main/java/com/apm/render/FrameMetricsAccumulator.kt`
- Test: `apm-render/src/test/java/com/apm/render/RenderConfigTest.kt`
- Test: `apm-render/src/test/java/com/apm/render/FrameMetricsAccumulatorTest.kt`
- Test: `apm-render/src/test/java/com/apm/render/RenderModuleTest.kt`

**Interfaces:**
- Produces: `ApmExecutors.startHandlerThread`, `RenderConfig.slowFrameThresholdMs`, frame aggregate events.

- [ ] **Step 1: Write failing threshold, frame classification, and cleanup tests**

Test normal/slow/frozen classification at exact boundaries, bounded aggregation reset, deprecated overdraw default false, listener registration per Activity, and removal on destroy/stop.

- [ ] **Step 2: Run RED**

Run: `./gradlew.bat :apm-render:testDebugUnitTest :apm-core:testDebugUnitTest --no-daemon`

- [ ] **Step 3: Implement handler-thread factory and FrameMetrics listener**

Register `Window.OnFrameMetricsAvailableListener` on API 24+, read `FrameMetrics.TOTAL_DURATION`, aggregate on the module handler, and emit bounded summaries rather than per-frame events. Deprecate `detectOverdraw`; add trailing `slowFrameThresholdMs: Long = viewDrawThresholdMs`.

- [ ] **Step 4: Run GREEN and commit**

Commit: `Feat: Measure render frame duration`

---

### Task 10: Audit every public configuration switch

**Files:**
- Create: `apm-core/src/test/java/com/apm/core/PublicConfigContractTest.kt`
- Modify: configuration and module files identified by the audit.

**Interfaces:**
- Produces: machine-checked list of active/deprecated public switches.

- [ ] **Step 1: Write a failing reflection/source contract test**

Maintain an explicit table of every boolean/threshold config property and its status `ACTIVE` or `DEPRECATED_DISABLED`. Assert deprecated booleans default false and deprecated thresholds are not consumed by production branches.

- [ ] **Step 2: Run RED and resolve every uncovered field**

Run: `./gradlew.bat testDebugUnitTest --no-daemon`

For each failure, either connect the field to tested runtime behavior or deprecate it with a safe default and replacement documentation.

- [ ] **Step 3: Run GREEN and commit**

Commit: `Refactor: Make monitoring configs truthful`

---

### Task 11: Add a non-published device benchmark harness

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `apm-benchmark/build.gradle.kts`
- Create: `apm-benchmark/src/main/AndroidManifest.xml`
- Create: `apm-benchmark/src/main/java/com/apm/benchmark/ClientBenchmark.kt`
- Create: `apm-benchmark/src/main/java/com/apm/benchmark/BenchmarkEnvironment.kt`
- Modify: `apm-sample-app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: compileable Macrobenchmark scenarios and environment metadata; no publication.

- [ ] **Step 1: Add the benchmark module with one intentionally failing environment assertion**

The test asserts package name, API level, thermal status availability, and output schema fields before running startup, event-throughput, SQLite claim, and diagnostics scenarios.

- [ ] **Step 2: Build to observe RED**

Run: `./gradlew.bat :apm-benchmark:assemble --no-daemon`

Expected: missing benchmark catalog aliases/module classes.

- [ ] **Step 3: Add compatible AndroidX test/benchmark dependencies and scenarios**

Use `com.android.test`, target `:apm-sample-app`, `androidx.benchmark.macro.junit4`, and `androidx.test.ext:junit`. Record metrics and environment into instrumentation status bundles without inventing unavailable power values.

- [ ] **Step 4: Compile GREEN and commit**

Run: `./gradlew.bat :apm-benchmark:assemble --no-daemon`

Commit: `Build: Add client benchmark harness`

---

### Task 12: Create the canonical cloud backlog and restore README comparison

**Files:**
- Create: `docs/云端待建设清单.md` (historical step; the canonical file was later moved to `AndroidAPM-Server/docs/云端待建设清单.md` and removed from this client repository)
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/Android_APM_项目文档.md`
- Modify: `docs/PROJECT_HANDOFF.md`
- Modify: `docs/architecture/00_整体架构.md`
- Modify: affected module architecture documents.

**Interfaces:**
- Produces: one cloud truth source and current primary-source comparison table.

- [ ] **Step 1: Research Matrix and KOOM primary repositories**

Record current repository links, documented scope, and comparison date. Do not infer absence beyond what primary documentation claims.

- [ ] **Step 2: Create cloud backlog with exact acceptance contracts**

For collector/dedup, auth/tenant, query/alert/dashboard, symbols, Maven publication, cloud CI, and device lab, state client input, cloud deliverable, and measurable acceptance criteria.

- [ ] **Step 3: Restore a qualified README table**

Use `✅`, `◐`, and `—` legend from the design. Include event identity, acknowledged outbox, diagnostics, memory, crash/ANR, startup, network, FPS/slow method, IO, SQLite, WebView, Binder, thread pools, render, and hosted-backend boundary.

- [ ] **Step 4: Replace duplicate unfinished lists with links and commit**

Run: `python docs/verify_docs.py`

Commit: `Docs: Separate cloud work from client scope`

---

### Task 13: Full verification, baseline sync, and GitHub publish

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/Android_APM_项目文档.md`
- Modify: `docs/PROJECT_HANDOFF.md`

- [ ] **Step 1: Run fresh JDK 17 verification**

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
./gradlew.bat :apm-benchmark:assemble --no-daemon
python docs/verify_docs.py
git diff --check
```

- [ ] **Step 2: Record exact counts and external device limitation**

Update source/test/build-unit counts, XML test totals, lint reports, APK bytes, Maven artifacts, and benchmark compilation. State that physical benchmark results require a connected device/device lab.

- [ ] **Step 3: Commit documentation and push develop**

Commit: `Docs: Record client completion baseline`

- [ ] **Step 4: Prove remote equality**

Assert `HEAD == origin/develop == git ls-remote origin refs/heads/develop`, remote divergence is `0 0`, the worktree is clean, and the remote still contains only `develop`.
