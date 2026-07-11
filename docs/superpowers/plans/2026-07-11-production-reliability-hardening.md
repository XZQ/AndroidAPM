# Production Reliability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove and harden AndroidAPM so recoverable monitoring failures preserve host behavior, durable ownership stays correct, and repeated failures remain bounded.

**Architecture:** Add deterministic fault injection at existing interfaces, then make only local fixes where a test exposes an invariant violation. Preserve the current dispatcher/outbox/worker/diagnostics boundaries; do not add a central coordinator or public fault-injection switch.

**Tech Stack:** Kotlin 2.2.21, Android SQLite/Robolectric, JUnit 4, Gradle 8.13, JDK 21.

## Global Constraints

- Priority is host application safety, then telemetry durability, then diagnostic completeness.
- Catch recoverable `Exception`; do not broadly swallow `VirtualMachineError`, `ThreadDeath`, or linkage failures.
- Keep queues, retries, maps, files, threads, and diagnostic volume bounded.
- Preserve stable `eventId`, at-least-once delivery, owner-aware ACK, and the SQLite durable outbox default.
- Add KDoc to every new public, internal, or private property and function.
- Report degraded recoverable exceptions through `Apm.recordInternalError`; diagnostics sink failures remain non-recursive.
- Use `ApmExecutors` for core threads; do not introduce public debug/fault-injection configuration.
- Every behavior change starts with a failing test and ends with focused verification and an English prefixed commit.

---

### Task 1: Dispatcher failure firewall and fatal-error policy

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/ApmDispatcher.kt`
- Modify: `apm-core/src/main/java/com/apm/core/Apm.kt`
- Modify: `apm-core/src/test/java/com/apm/core/ApmDispatcherTest.kt`
- Modify: `apm-core/src/test/java/com/apm/core/ApmDiagnosticsIntegrationTest.kt`

**Interfaces:**
- Consumes: `EventStore.appendBatch`, `ApmUploader.upload`, `Apm.recordInternalError`.
- Produces: per-event recoverable isolation in `ApmDispatcher.processBatch`; no-throw independent sinks in `Apm.recordInternalError`.

- [ ] **Step 1: Write failing dispatcher regression tests**

Add tests that enqueue a throwing lazy factory before a valid event and assert the valid event is still stored; use a store whose `append` throws `OutOfMemoryError` and assert `dispatchCriticalSync` propagates it rather than returning `false`.

```kotlin
@Test
fun `recoverable lazy factory failure does not kill dispatcher worker`() {
    val store = RecordingStore()
    val dispatcher = dispatcher(store = store)
    dispatcher.dispatchLazy { throw IllegalStateException("factory") }
    dispatcher.dispatch(createEvent("after-failure"))
    waitUntil { store.events.any { it.name == "after-failure" } }
    dispatcher.shutdown()
}

@Test
fun `critical persistence does not swallow fatal vm error`() {
    val fatal = OutOfMemoryError("fatal")
    val dispatcher = dispatcher(store = ThrowingAppendStore(fatal))
    assertSame(fatal, assertFailsWith<OutOfMemoryError> {
        dispatcher.dispatchCriticalSync(createEvent("fatal"))
    })
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests com.apm.core.ApmDispatcherTest --rerun-tasks --no-daemon
```

Expected: the worker-continuation test times out and the fatal-error test reports no thrown `OutOfMemoryError`.

- [ ] **Step 3: Implement minimal per-event isolation**

Resolve, aggregate, rate-limit, and sanitize each queued event inside `try/catch (error: Exception)`. Record the queued event priority when known, log the failure, call `Apm.recordInternalError("dispatcher_process_event", error)`, and continue with later events. Change persistence, critical-sync, aggregation-flush, and shutdown catches from `Throwable`/`runCatching` to explicit `Exception` handling so fatal VM errors propagate.

Make `Apm.recordInternalError` isolate `selfMonitor` and `ApmDiagnostics.record` independently:

```kotlin
fun recordInternalError(tag: String, error: Throwable? = null) {
    try {
        state?.context?.selfMonitor?.recordInternalError(tag)
    } catch (_: Exception) {
        // The remaining independent diagnostics sink must still receive the original failure.
    }
    try {
        ApmDiagnostics.record(DiagnosticLevel.ERROR, INTERNAL_COMPONENT, tag, internalErrorMessage(tag), error)
    } catch (_: Exception) {
        // Diagnostics failures cannot recurse or escape into the host.
    }
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same Gradle command. Expected: all `ApmDispatcherTest` cases pass.

- [ ] **Step 5: Commit task 1**

```powershell
git add apm-core/src/main/java/com/apm/core/ApmDispatcher.kt apm-core/src/main/java/com/apm/core/Apm.kt apm-core/src/test/java/com/apm/core/ApmDispatcherTest.kt apm-core/src/test/java/com/apm/core/ApmDiagnosticsIntegrationTest.kt
git commit -m "Fix: Isolate dispatcher monitoring failures"
```

### Task 2: SQLite cached-count and lease arithmetic invariants

**Files:**
- Modify: `apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt`
- Modify: `apm-storage/src/test/java/com/apm/storage/SQLiteEventStoreTest.kt`

**Interfaces:**
- Consumes: existing schema-v3 claim/lease columns and synchronized store methods.
- Produces: `internal fun calculateLeaseExpiry(nowMs: Long, durationMs: Long): Long`; non-negative cached deletion accounting.

- [ ] **Step 1: Write failing cross-store count test**

Create two stores over the same helper. Let store A cache one row, store B add two more, delete all three through store A, then append eleven rows through store A with `maxEvents=10`. Assert only ten remain. This proves a stale cache cannot become negative and suppress trimming.

```kotlin
@Test
fun `cross store deletion cannot drive cached count negative`() {
    val storeA = SQLiteEventStore(helper, maxEvents = 10)
    val storeB = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = 10)
    storeA.append(event("seed"))
    storeB.appendBatch(listOf(event("external-1"), event("external-2")))
    storeA.deletePending(storeA.readPending(10).map(PendingEvent::id))
    storeA.appendBatch((0 until 11).map { event("replacement-$it") })
    assertEquals(10, storeA.pendingCount())
}
```

Add direct lease arithmetic cases for normal values and `Long.MAX_VALUE` saturation.

- [ ] **Step 2: Run focused storage tests and verify RED**

```powershell
./gradlew.bat :apm-storage:testDebugUnitTest --tests com.apm.storage.SQLiteEventStoreTest --rerun-tasks --no-daemon
```

Expected: cross-store deletion leaves 11 rows before the fix.

- [ ] **Step 3: Implement bounded accounting and named lease arithmetic**

Replace every direct cached deletion subtraction with one helper that uses `AtomicLong.updateAndGet` and clamps initialized values to zero. Extract `calculateLeaseExpiry` as an internal top-level function, require a positive duration, and saturate overflow at `Long.MAX_VALUE`. Keep database transaction semantics unchanged.

```kotlin
internal fun calculateLeaseExpiry(nowMs: Long, durationMs: Long): Long {
    require(durationMs > 0L) { "durationMs must be positive" }
    return if (nowMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else nowMs + durationMs
}

private fun decrementCachedCount(deleted: Int) {
    if (deleted <= 0) return
    cachedRowCount.updateAndGet { current ->
        if (current == UNINITIALIZED_COUNT) current else (current - deleted).coerceAtLeast(0L)
    }
}
```

- [ ] **Step 4: Run focused storage tests and verify GREEN**

Run the same storage command. Expected: all tests pass, including concurrent claim and corrupt-row cases.

- [ ] **Step 5: Commit task 2**

```powershell
git add apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt apm-storage/src/test/java/com/apm/storage/SQLiteEventStoreTest.kt
git commit -m "Fix: Bound outbox lease accounting"
```

### Task 3: Persistent worker retry and exception boundaries

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/PersistentUploadWorker.kt`
- Modify: `apm-core/src/test/java/com/apm/core/PersistentUploadWorkerTest.kt`

**Interfaces:**
- Consumes: `RetryPolicy.delayForAttempt`, `ApmUploader.retryAfterHintMs`, owner-aware store operations.
- Produces: `internal fun boundedRetryWaitMs(localDelayMs: Long, retryAfterMs: Long?): Long`.

- [ ] **Step 1: Write failing backoff and fatal-boundary tests**

Assert negative delays clamp to `MIN_WAIT_MS`, extreme hints clamp to `MAX_RETRY_WAIT_MS`, and normal larger hints win. Add a throwing `Exception` uploader test proving rows remain and retry count increments. Add an uploader throwing `OutOfMemoryError` with an uncaught-exception capture executor or direct extracted attempt seam, proving fatal errors are not converted to `false`.

```kotlin
@Test
fun `retry wait is bounded for hostile hints`() {
    assertEquals(10L, boundedRetryWaitMs(-1L, -5L))
    assertEquals(60_000L, boundedRetryWaitMs(1_000L, Long.MAX_VALUE))
    assertEquals(5_000L, boundedRetryWaitMs(1_000L, 5_000L))
}
```

- [ ] **Step 2: Run worker tests and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests com.apm.core.PersistentUploadWorkerTest --rerun-tasks --no-daemon
```

Expected: the new bounded helper is missing and existing `runCatching` converts fatal errors into recoverable failures.

- [ ] **Step 3: Implement explicit recoverable catches and bounded wait**

Replace `runCatching` in claim, count, acknowledge, fail, upload, prune, and release paths with `try/catch (error: Exception)`. Compute retry waits through a named internal helper capped at 60 seconds. Keep `wakeSignal` able to shorten every wait. Do not add an unsafe mechanism to terminate arbitrary custom uploader code; retain lease expiry as the recovery authority and document that boundary.

- [ ] **Step 4: Run worker and core tests and verify GREEN**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --rerun-tasks --no-daemon
```

Expected: all core tests pass; recoverable transport failures retain rows and fatal VM errors are not swallowed.

- [ ] **Step 5: Commit task 3**

```powershell
git add apm-core/src/main/java/com/apm/core/PersistentUploadWorker.kt apm-core/src/test/java/com/apm/core/PersistentUploadWorkerTest.kt
git commit -m "Fix: Bound persistent upload failures"
```

### Task 4: Diagnostics explicit-operation isolation

**Files:**
- Modify: `apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticRecorder.kt`
- Modify: `apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticRecorderTest.kt`

**Interfaces:**
- Consumes: `DiagnosticStore.exportTo`, `DiagnosticExportResult`.
- Produces: export failures returned as data even for a custom throwing store.

- [ ] **Step 1: Write failing export and stopped-recorder tests**

Add a `ThrowingExportStore` whose `exportTo` throws `IOException`. Assert `recorder.exportTo(target)` returns `success=false`, `file=null`, and a sanitized error. Record after shutdown and assert memory remains bounded while `droppedRecords` increments.

- [ ] **Step 2: Run diagnostic tests and verify RED**

```powershell
./gradlew.bat :apm-core:testDebugUnitTest --tests com.apm.core.diagnostics.DiagnosticRecorderTest --rerun-tasks --no-daemon
```

Expected: the throwing custom store escapes from `DiagnosticRecorder.exportTo`.

- [ ] **Step 3: Return export failure as data**

Wrap only `Exception`, call `noteReadFailure` or the dedicated explicit-operation failure counter without routing back through `ApmLogger`, and return:

```kotlin
DiagnosticExportResult(
    success = false,
    file = null,
    exportedRecords = 0,
    errorMessage = DiagnosticSanitizer.sanitizeMessage(error.message ?: error.javaClass.simpleName)
)
```

Keep fatal errors unhandled and preserve the existing memory/queue bounds.

- [ ] **Step 4: Run diagnostic and core tests and verify GREEN**

Run the focused command, then the full `:apm-core:testDebugUnitTest`. Expected: all pass.

- [ ] **Step 5: Commit task 4**

```powershell
git add apm-core/src/main/java/com/apm/core/diagnostics/DiagnosticRecorder.kt apm-core/src/test/java/com/apm/core/diagnostics/DiagnosticRecorderTest.kt
git commit -m "Fix: Isolate diagnostic export failures"
```

### Task 5: Deterministic outbox state-machine coverage

**Files:**
- Create: `apm-storage/src/test/java/com/apm/storage/OutboxReliabilityStateMachineTest.kt`
- Modify if a regression is exposed: `apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt`

**Interfaces:**
- Consumes: append, claim, owner-aware acknowledge/fail, release, expiry reclaim, pending count.
- Produces: fixed-seed model comparison for randomized operation sequences.

- [ ] **Step 1: Implement the deterministic model test**

Use `Random(0xA9_2026)` and 250 operations. Maintain an in-memory model keyed by `eventId` with owner, expiry, and retry count. Randomly append unique/duplicate events, claim as owner A/B, acknowledge with correct/wrong owner, fail, release, and advance a fake wall clock. After each operation assert database event IDs are unique, `pendingCount` matches the model, and no wrong-owner action mutates a row.

- [ ] **Step 2: Run the state-machine test**

```powershell
./gradlew.bat :apm-storage:testDebugUnitTest --tests com.apm.storage.OutboxReliabilityStateMachineTest --rerun-tasks --no-daemon
```

Expected: PASS. If it fails, preserve the seed and minimized operation trace in the regression name before applying a local store fix.

- [ ] **Step 3: Run the complete storage suite**

```powershell
./gradlew.bat :apm-storage:testDebugUnitTest --rerun-tasks --no-daemon
```

Expected: all storage tests pass with zero failures.

- [ ] **Step 4: Commit task 5**

```powershell
git add apm-storage/src/test/java/com/apm/storage/OutboxReliabilityStateMachineTest.kt apm-storage/src/main/java/com/apm/storage/SQLiteEventStore.kt
git commit -m "Fix: Exercise outbox failure sequences"
```

### Task 6: Documentation, review, and release verification

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `docs/Android_APM_项目文档.md`
- Modify: `docs/PROJECT_HANDOFF.md`
- Modify: `docs/architecture/01_apm-core.md` for dispatcher and diagnostics behavior
- Modify: `docs/architecture/02_apm-model-storage-uploader.md`
- Modify: `docs/generate_report.py` and generated DOCX files only if report source includes changed claims.

**Interfaces:**
- Consumes: final runtime behavior and fresh verification evidence.
- Produces: source-aligned maintained documentation and pushed `develop`.

- [ ] **Step 1: Synchronize behavior documentation**

Document the host-safety priority, recoverable `Exception` boundary, fatal-error policy, bounded retry hint, cross-process cached-count correction, custom synchronous uploader limitation, and diagnostics export failure-as-data behavior. Update source/test counts and verification totals from fresh artifacts only.

- [ ] **Step 2: Run complete verification under JDK 21**

Run every command from the design specification. Expected: all builds/tests pass, lint has no fatal issue, Maven Local publications resolve in the isolated consumer, docs verification passes, and `git diff --check` is clean.

- [ ] **Step 3: Perform final code review**

Review the complete diff against the design invariants. Resolve every Critical/Important finding and rerun affected tests. Confirm no broad recoverable path catches fatal VM errors and no new unbounded retry/resource path exists.

- [ ] **Step 4: Commit final documentation and fixes**

```powershell
git add -A
git commit -m "Docs: Record reliability verification"
```

- [ ] **Step 5: Push and verify exact GitHub equality**

```powershell
git push origin develop
git rev-parse HEAD
git rev-parse origin/develop
git ls-remote origin refs/heads/develop
git status --short --branch
```

Expected: all three hashes are identical, GitHub exposes only `develop`, and the worktree is clean.
