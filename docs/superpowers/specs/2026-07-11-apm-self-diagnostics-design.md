# AndroidAPM Self-Diagnostics Design

## Goal

Add a bounded, privacy-conscious self-diagnostics channel that preserves enough evidence to investigate failures inside AndroidAPM itself, even when the normal event dispatcher, durable event outbox, or uploader is degraded.

The diagnostics channel is a local support facility, not a second telemetry product. It must never block the host application's critical paths, grow without bounds, or depend on the event delivery components it is intended to diagnose.

## Current Gap

The repository already has two partial facilities:

- `ApmLogger` writes debug, warning, and error messages to Logcat.
- `SdkSelfMonitor` counts emits, drops, upload latency, queue size, and internal errors.

These do not form a complete diagnostic system. Logcat is ephemeral, `recordInternalError` does not retain structured error details, the periodic health event shares the normal event pipeline, and there is no supported snapshot, export, clear, or storage-health API. A failure in the dispatcher, event store, or uploader can therefore remove the evidence needed to diagnose that same failure.

## Approved Direction

Implement an independent local diagnostic journal with three outputs:

1. Existing Logcat output for live development.
2. A bounded in-memory ring containing the most recent structured records.
3. Bounded app-private rolling files that survive process restarts.

The SDK will expose local inspection and export APIs. It will not add an automatic diagnostics network uploader in this iteration. An integrator may explicitly export the diagnostic package and attach it to its own support or feedback flow.

## Non-Goals

- No second HTTP client, diagnostics endpoint, authentication protocol, or automatic network retry queue.
- No dependency on `ApmDispatcher`, `EventStore`, `PendingEventStore`, `PersistentUploadWorker`, or `ApmUploader` for diagnostic persistence.
- No event payload, request body, SQL text, user identifier, access token, or arbitrary business context capture.
- No replacement for Java crash events, native tombstones, ANR traces, or Hprof artifacts.
- No attempt to redirect async-signal-safe native crash logging into Kotlin file APIs.
- No unbounded stack traces, queues, memory buffers, files, or retention periods.

## Architecture

```text
APM modules / dispatcher / storage / uploader
                    |
                    v
             ApmLogger facade
                    |
                    v
          DiagnosticRecorder
          |        |        |
          |        |        +--> Logcat sink
          |        +-----------> memory ring
          +--------------------> bounded writer queue
                                      |
                                      v
                              rolling file sink

Public ApmDiagnostics facade
          |-- snapshot
          |-- status
          |-- exportTo
          +-- clear
```

`DiagnosticRecorder` is created before the normal APM runtime infrastructure so initialization failures can be captured. It is owned by a small diagnostics runtime separate from `Apm.State`. Publishing or clearing diagnostics must remain possible after partial initialization failure and after `Apm.stop()` closes the event pipeline.

Existing `ApmLogger` callers remain source-compatible. The Android implementation becomes a facade that writes to Logcat and the recorder. `UploaderFactory` continues adapting the core logger into `UploaderLogger`, so uploader failures enter the same diagnostic journal without making `apm-uploader` depend on `apm-core`.

## Diagnostic Record

Each record is immutable and contains only controlled fields:

- format version
- monotonic sequence number
- wall-clock timestamp
- SDK session identifier
- level: `DEBUG`, `INFO`, `WARN`, or `ERROR`
- component such as `core`, `dispatcher`, `storage`, `uploader`, or `module`
- stable error code/tag when available
- bounded message
- process name
- thread name
- exception class name
- bounded exception message
- bounded stack trace and stack hash when an exception exists

Messages are limited to 4 KiB after sanitization. Exception text is limited to 16 KiB and 64 frames. Longer values are truncated with an explicit marker. The persistent representation is UTF-8 JSON Lines so individual records remain readable and partially recoverable if the last write is interrupted.

The recorder accepts trusted SDK-generated strings only. It additionally redacts common credential-shaped query parameters and authorization values. Normal APM event fields and `defaultContext` are never copied into diagnostic records.

## Levels and Defaults

- Self-diagnostics are enabled by default.
- `DEBUG` follows the existing `debugLogging` switch and is not persisted when that switch is disabled.
- Lifecycle milestones and safe configuration summaries use `INFO`.
- Recoverable degradation uses `WARN`.
- Failed SDK operations and caught exceptions use `ERROR`.
- Warning and error records always reach the diagnostic recorder while diagnostics are enabled.

The existing Logcat behavior remains compatible: debug output is gated, while warnings and errors remain visible.

## Resource Bounds and Write Policy

Default limits are explicit and configurable within validated safe ranges:

- memory ring: 200 records
- writer queue: 256 records
- rolling file size: 512 KiB
- retained files: 3, including the active file
- total default disk budget: approximately 1.5 MiB

The calling thread performs only record construction, sanitization, the short memory-ring update, Logcat output, and a non-blocking queue `offer`. File IO runs on a single background executor created through `ApmExecutors`.

If the writer queue is full, a new `ERROR` may evict one older queued record and retry once; other records are dropped. Every drop increments `diagnosticDroppedCount`. No diagnostic call waits for queue capacity.

File rotation uses an active file plus numbered retained segments. Rotation and export operate under the file sink's own lock and never acquire APM event-store or dispatcher locks.

## Failure Isolation

File sink exceptions are handled inside the sink and must not be reported through `ApmLogger` or `Apm.recordInternalError`, because doing so would recursively enter the failing recorder.

On a file failure the system:

1. increments `diagnosticWriteFailureCount`;
2. stores a sanitized last-failure summary in memory;
3. marks the file sink unhealthy;
4. continues memory-ring and raw Logcat output;
5. retries file initialization after a bounded cooldown.

Corrupt or truncated JSONL lines are skipped during snapshot/export and counted. A bad retained segment does not prevent exporting the remaining readable records.

`Apm.stop()` requests a bounded writer drain and then closes the writer executor. Failure to drain does not block shutdown indefinitely. The last in-memory snapshot remains readable from the diagnostics facade, and persisted segments remain exportable.

## Public API

Expose an `ApmDiagnostics` facade from `apm-core`:

```kotlin
val status: DiagnosticStatus = ApmDiagnostics.status()
val recent: List<DiagnosticEntry> = ApmDiagnostics.snapshot(limit = 100)
val result: DiagnosticExportResult = ApmDiagnostics.exportTo(targetFile)
val cleared: Boolean = ApmDiagnostics.clear()
```

`snapshot` clamps invalid limits to the supported range and returns newest-first immutable entries. `status` reports whether diagnostics and the file sink are active, queue depth, retained-file bytes, dropped records, write failures, corrupt records, and the last sanitized sink failure.

`exportTo` is synchronous and documented for a worker thread. It first performs a bounded writer flush, then creates a ZIP containing a manifest and readable JSONL segments. The manifest contains SDK/session/process metadata and diagnostic counters, but no business context. Export failure is returned as data and never thrown into the host application.

`clear` removes persisted diagnostic segments and clears the memory ring after coordinating with the writer. It returns `false` and updates diagnostic status on failure rather than throwing. Calls before `Apm.init` return an inactive status or empty result.

## Configuration

Add a grouped `DiagnosticsConfig` to `ApmConfig` rather than adding unrelated top-level flags:

```kotlin
DiagnosticsConfig(
    enabled = true,
    memoryRecordLimit = 200,
    writerQueueCapacity = 256,
    maxFileBytes = 512 * 1024,
    retainedFileCount = 3,
    includeStackTraces = true
)
```

Construction validates positive limits and enforces documented upper bounds. The default directory is app-private storage under an AndroidAPM-owned diagnostics directory and is not externally readable without an explicit export performed by the host.

## Integration with Existing Self-Monitoring

`Apm.recordInternalError(tag, error)` becomes the standard structured failure entry point:

- increment `SdkSelfMonitor.internalErrorCount`;
- record an `ERROR` diagnostic with the stable tag, exception class, message, and bounded stack;
- avoid formatting the throwable into a debug-only message.

The periodic `sdk_health` event includes the existing interval-reset `internalErrorCount` plus interval deltas for `diagnosticDroppedCount` and `diagnosticWriteFailureCount`. Detailed cumulative totals remain available from `ApmDiagnostics.status()` and detailed records remain in the independent journal.

The health event may still be lost when the normal event pipeline is unhealthy; that does not affect the local diagnostic journal.

## Lifecycle and Data Flow

Initialization order:

1. Resolve application/process identity.
2. Initialize or attach the diagnostics runtime and write a safe session-start record.
3. Create event storage, uploader, dispatcher, self-monitoring, and optional process coordination.
4. Record safe lifecycle milestones and structured failures.
5. Publish `Apm.State` only after the normal runtime is ready.

Shutdown order adds a diagnostics step after the normal pipeline shutdown: record the bounded shutdown outcome, flush the diagnostic writer with a short timeout, and close the writer while preserving readable files.

Repeated `Apm.init` after `Apm.stop()` starts a new diagnostic session without deleting prior retained segments. File rotation enforces the same total retention budget across sessions.

## Documentation Changes

Implementation must update:

- `AGENTS.md` verified architecture and finish evidence;
- `docs/Android_APM_项目文档.md` current capabilities and defaults;
- `README.md` configuration and support-export usage;
- `docs/architecture/00_整体架构.md` independent diagnostics control path;
- `docs/architecture/01_apm-core.md` components, lifecycle, APIs, failure semantics, and tests;
- the sample application with a minimal diagnostics status/export demonstration if it can be added without new permissions.

Documentation must distinguish APM event Logcat fallback from SDK self-diagnostic Logcat/file output.

## Test Strategy

Implementation follows test-driven development. Each behavior is introduced by a failing test before production code.

Unit tests cover:

- config defaults, validation, and upper bounds;
- record formatting, truncation, redaction, stack limits, and stack hash stability;
- memory-ring ordering and capacity;
- non-blocking queue overflow and error-priority behavior;
- file append, rotation, retention, restart recovery, and corrupt-line skipping;
- sink failure fallback without recursion;
- snapshot, status, export ZIP contents, and clear;
- structured `recordInternalError` behavior;
- inclusion of all self-diagnostic counters in `sdk_health`;
- compatibility of uploader logging through `UploaderFactory`.

Robolectric or Android-aware tests cover app-private directory selection and runtime initialization. Integration tests inject failing event-store/uploader collaborators and prove that diagnostics remain queryable and exportable when the normal event pipeline fails.

## Acceptance Criteria

The feature is accepted when all of the following are true:

1. Structured internal errors that reach the file sink remain readable after a normal process restart; the bounded asynchronous policy explicitly allows loss of records still queued during abrupt process death.
2. Dispatcher, event-store, and uploader failures can be diagnosed without relying on those components to deliver the evidence.
3. Logging from host-facing APM calls performs no blocking file IO and has explicit memory, queue, stack, and disk bounds.
4. File failures degrade to memory plus Logcat without recursion or host exceptions.
5. Export produces a readable bounded package without event payloads or business context.
6. Existing `ApmLogger` and uploader integration remain source-compatible.
7. `sdk_health` exposes internal-error and diagnostic-sink aggregate counters.
8. Relevant focused tests, full `testDebugUnitTest`, `assembleDebug`, plugin tests, documentation verification, and `git diff --check` pass under JDK 17.
9. Repository documentation describes the implemented behavior and its limits without claiming automatic diagnostics upload.
