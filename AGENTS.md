# AGENTS.md

## Purpose

This is the repository-local handoff entry for AndroidAPM. Treat the current source tree and executable verification as the source of truth; documentation must be corrected when it disagrees with code.

## Read Order

1. `docs/Android_APM_项目文档.md`
2. `docs/PROJECT_HANDOFF.md`
3. `README.md`
4. `CLAUDE.md`
5. `docs/architecture/00_整体架构.md`
6. The matching module document under `docs/architecture/`
7. `docs/protocol/COLLECTOR_WIRE_V3.md` for the current strict occurrence-bound contract
8. `docs/protocol/COLLECTOR_WIRE_V2.md` for the retained compatibility contract
9. The sibling `AndroidAPM-Server/docs/云端待建设清单.md` for every external/cloud dependency

## Current Verified Baseline

- Gradle 9.6.0, AGP 9.5.2, Kotlin 2.2.21, and JDK 21 for the daemon and Java/Kotlin compilation and tests across all four Gradle build roots. JVM bytecode target is 21. Android compileSdk is 36, minSdk is 24, and targetSdk is 34.
- The project has 27 build units, 170 main sources, and 130 test and benchmark sources in Gradle source sets. The five Python test files are counted separately.
- Use `python tools/verify_ci.py` with `JAVA_HOME` set to JDK 21. The gate checks 24 ABI baselines, four strict dependency verification files, 25 local Maven coordinates, and a build of the independent Maven consumer.
- The JDK 21 and AGP 9.5.2 migration was verified with Android Studio Sync, all-module `assembleDebug`, benchmark Release and AndroidTest Kotlin compilation, and 987 root Android, model, and Gradle plugin tests. See the [project baseline](docs/Android_APM_项目文档.md) and [historical verification](docs/HISTORICAL_VERIFICATION.md).

## Project Boundary

AndroidAPM is a modular Android client SDK, not a complete hosted APM product. It captures, normalizes, protects, persists, and transports telemetry. A production collector, authentication, tenant isolation, query/aggregation backend, alerting, native symbolization service, and operational dashboards are outside this repository.

The default runtime path is:

```text
monitor module
  -> Apm.emit (caller captures timestamp/thread/context/payload snapshots)
  -> bounded dispatcher queue (2048 events + 8 MiB estimated retained bytes; default 75% high-water / 50% per-module NORMAL/LOW share; higher priority may evict enough oldest lower-priority entries; producers never wait)
  -> signed dynamic sampling, optional aggregation, dynamic rate limiting, and default-on PII sanitization
  -> appendBatch (up to 32 drained events)
  -> SQLite durable outbox v4 (50,000 rows; 64 MiB live-payload budget; 256 KiB per-event soft limit; unique eventId; `idx_priority_desc_ts` claim ordering index added at schema v4, v3→v4 is additive)
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> BatchApmUploader / HttpApmUploader / custom uploader
  -> integrator-owned collector
```

Crash and ANR use the dedicated `Apm.emitCriticalSync` path: caller-supplied lower priority is promoted to CRITICAL, the shared queue/sampling/aggregation/rate-limit path is bypassed, and success means the complete event reached SQLite or a synchronously published critical IPC file. The path never performs blocking network IO. The uploader process sends CRITICAL IPC events directly to the synchronous store boundary and deletes the ready file only after acceptance; false, recoverable failure, or no consumer retains the complete file for at-least-once retry. Published `.ipc` files do not age out before acceptance; the directory byte budget bounds them, while incomplete `.tmp` files retain the five-minute cleanup. Optional normal multi-process forwarding bounds pending events to 4 MiB, raw encoded events to 256 KiB, each ready file to 1 MiB, and the ready directory to 16 MiB; producers use a lock-free pending queue, one fixed-delay writer task performs batching, and complete `.tmp` files publish as `.ipc` files under a cross-process budget lock.

Delivery is acknowledged and at least once. Stable `eventId` survives Line Protocol, Protobuf field 14, durable codec, SQLite, and IPC. SQLite write transactions atomically claim rows with owner/expiry; only the owner may acknowledge or fail them, shutdown releases claims, and expired claims are reclaimable. Ambiguous network completion can still retransmit, so the collector must deduplicate by `eventId`.

Durable codec format V4 appends occurrence-bound release/installation/native identity after the V3 typed scalar payload and continues to read V1/V2/V3 rows without inventing occurrence. Supported scalar types round-trip exactly; arbitrary objects deliberately degrade through `toString()` rather than Java serialization. Legacy Line Protocol and standalone Protobuf remain string-valued. `PROTOBUF_ENVELOPE_V2` remains the batch-declared compatibility contract; strict built-in HTTP uses `PROTOBUF_ENVELOPE_V3`, which requires field-16 occurrence identity on every event, a `b3-` batch ID, the same 1 MiB default/4 MiB absolute uncompressed request budget, and exact whole-batch ACK headers.

## Integration Reality

Registration alone does not make every monitor automatic:

- Network requires the OkHttp interceptor/listener, explicit `traceHttpUrlConnection`, or manual completion callbacks. The HttpURLConnection helper executes by reading `responseCode`, does not consume/disconnect the host connection, reports only total duration/status/header-derived size, and is not a global hook.
- SQLite requires `ApmSQLiteDatabase` or `onSqlExecuted` callbacks. Only wrapper `rawQuery` has the database handle, full SQL, and bound arguments needed for threshold-gated `EXPLAIN QUERY PLAN`; monitoring reports are isolated from host database results/exceptions.
- IPC uses `traceBinderCall` or `onBinderCallComplete`; deprecated `enableBinderHook=false` does not use hidden APIs.
- WebView uses explicit per-instance `install/uninstall`, delegate wrappers, `evaluateJavascript`, or callbacks; deprecated global `enableAutoRegister=false` does not take over arbitrary instances.
- Battery WakeLock/GPS/Alarm signals are host callbacks.
- IO uses explicit stream wrappers and an optional xhook-backed native path. Deprecated `enableAutoHook=false` does not take over arbitrary Java streams; Java ThreadLocal call-path suppression prevents double counting without dropping custom streams, Native callback-depth prevents APM-owned IO recursion, fd sessions use mutex/generation protection, duplicate/small-buffer findings report once per bounded path, and wrapper bookkeeping cannot fail completed host IO.
- Slow-method ASM requires the host module to apply `com.apm.slow-method`.
- FPS reports on a monotonic interval (`reportIntervalMs=1000` by default) rather than after a fixed frame count. Rendered FPS is measured actual rendered-frame callback intervals divided by their elapsed nanoseconds, capped by the display refresh rate, and reports `windowDurationMs`; the deprecated `windowSize` remains source-compatible but no longer controls cadence. API 24+ uses event-driven FrameMetrics as the primary source with a bounded primitive rolling accumulator; perpetual Choreographer callbacks are only the disabled/failed-FrameMetrics fallback so a static screen is not kept awake by the observer.
- Render measures view count/depth and API 24+ FrameMetrics. Deprecated `detectOverdraw=false` is truthful because no supported GPU overdraw counter exists.
- Thread monitoring inspects count/name/BLOCKED state; real ThreadPoolExecutor backlog requires explicit registration. Generic leak fields are deprecated/false.
- GC count/time/allocation/reclaim analysis consumes monotonic ART cumulative counters on an `ApmExecutors` background sampler; missing/reset counters invalidate only that dimension/window, invalid intervals clamp to one second, and available heap dimensions continue.
- Business context defaults to occurrence-time synchronous capture for compatibility; such providers must be O(1), perform no IO, and wait on no contended locks. `ASYNC_CACHED` runs the provider only on `apm-biz-context`, keeps the last good immutable snapshot after recoverable failure, bounds refresh cadence to 100 ms–24 h, and lets `Apm.refreshBizContext()` request one coalesced background refresh after login or tenant changes. Async emit reads never call host provider code.
- Reliability priority is host safety, then telemetry durability, then diagnostic completeness. Recoverable `Exception` failures are isolated at dispatcher/store/uploader/diagnostics boundaries; fatal VM errors are not converted into drops or retries. The shared dispatcher remains a single worker, but it now enforces both the 2,048-event and default 8 MiB estimated-retained-byte budgets. Default-on high-water isolation stops one NORMAL/LOW module from consuming more than 50% of total queue capacity after 75% occupancy; HIGH/CRITICAL bypass that gate and may evict enough older lower-priority entries to satisfy both dimensions. Multi-process coordination separately bounds pending, encoded event, ready-file, and ready-directory bytes and never creates one executor task per event. SQLite isolates an invalid/oversized event from valid batch peers, caps one durable payload at 256 KiB by default, and trims low-priority old rows against a 64 MiB live-payload budget as well as the 50,000-row budget; storage rejection/eviction increments SDK drop health. Active leases remain protected and physical SQLite page/WAL size is not the live-payload metric. Retry hints are bounded to 60 seconds; `maxRetries` means retries after the initial attempt and exhausted rows are pruned immediately after `maxRetries + 1` failures. Cached outbox deletion counts cannot become negative, and diagnostics export failures return failure data.
- `apm-otel-exporter` maps data only; it does not depend on or send through the OTel SDK.
- `apm-remote-config` polls authenticated HTTPS with ETag, verifies canonical JSON with pinned Ed25519/Tink keys, durably records the highest revision and LKG, and publishes only non-expired verified values. It drives global/module kill switches and event sampling/rate limits; endpoint rotation is separately opt-in and accepts only HTTPS.
- `apm-bundle` is a transitives-only convenience artifact that exposes all published client modules from one dependency. It does not initialize/register modules or apply `com.apm.slow-method`; size-sensitive consumers should keep selecting fine-grained artifacts.
- `apm-benchmark` has two independent measurement gates plus a managed-device aggregate contract. AndroidX Microbenchmark protects codec/SQLite/Dispatcher median time and allocations; the historical physical result covers only the original three methods, while the current five-method contract still needs managed-device execution. `run_device_soak.py` drives the debug sample through a zero-SDK control and always-failing-uploader enabled phases, then checks cold start, SDK init, main-thread operation P95, process CPU/PSS, app-private disk, app-UID power, thermal status, actual elapsed duration, and restarts for `smoke`/`24h`/`72h`. The required `--reset-app-data` clears only the selected sample package before acquisition. Schema v3 binds exact APK/source/matrix/budget/runner/lane/device provenance, while the aggregate verifier requires all three API lanes, six lane/profile artifacts, OEM/device/reset diversity, and one APK/source. Plan validation and host tests/builds never count as accepted physical results.

Important defaults: `ApmRuntimeProfile.COMPATIBILITY` preserves the existing empty/unsupported endpoint safe-discard behavior and explicit `logcat://` delivery. `PRODUCTION_STRICT` fails before diagnostics/storage/thread creation unless consent is explicitly `GRANTED`, sanitization is enabled, debug logging is disabled, storage is SQLite, and delivery uses an exact HTTPS endpoint or an explicit non-Logcat custom uploader. The built-in strict HTTP path additionally requires `PROTOBUF_ENVELOPE_V3`, complete bounded standard resource identity, a complete occurrence snapshot supplied through the three-argument `Apm.init`, and enough batch-byte headroom above the durable event limit. The current automatic Provider calls only the two-argument init and therefore cannot initialize V3. `Apm.revokeCollectionConsent(application)` is the cold-start-safe erase API: it makes revocation sticky, stops an active runtime without draining/aggregation flush, stops delivery before erasing, and clears SQLite, File, and IPC event artifacts; every SDK process must invoke revocation to close its in-memory producers. Dynamic endpoint rotation, aggregation, multi-process coordination, native crash, Hprof dump, and fork dump remain opt-in. Static HTTP headers and the per-request credential provider are empty.

AutoThrottle degradation is immediate and sticky. Recovery requires three consecutive periods at or below 20% drop rate and 3 seconds average upload latency; a degraded or hysteresis-band period resets the recovery streak. Registration and signed dynamic-config reconciliation cannot restart a module while it is held by auto-throttle, and recovery still rechecks process/dynamic/gray gates.

SDK self-diagnostics are separate from event delivery. They are enabled by default and bound both the 200-record memory ring and 256-record writer queue to 4 MiB each, plus up to three 512 KiB app-private JSONL segments per Android process. Process journals are isolated; aggregate export selects up to 16 recent process directories and is bounded to 10,000 records / 16 MiB of uncompressed JSONL. Each real loss increments the aggregate drop count plus a stable reason and LOW/NORMAL/HIGH/CRITICAL priority counter; aggregate-only compatibility results use an explicit `UNATTRIBUTED` priority rather than inventing one. Capacity eviction and retry/age pruning carry exact SQLite priority counts. Each periodic `sdk_health` report first records this payload-free numeric summary, including dispatcher module-isolation drops, current queued estimated bytes, and bounded fixed-stage latency count/average/P95-upper-bound/max fields, in the independent journal, then attempts normal telemetry at HIGH priority; dispatcher and IPC byte-budget rejections use stable reasons rather than collapsing into generic queue/file failures. The journal copy does not depend on dispatcher/outbox/uploader. `ApmDiagnostics` exposes cached status, synchronous/async snapshot and ZIP export, current-process clear, explicit all-process clear, and a file-free `hostIntegrationSnapshot()` for Network/SQLite/IPC/WebView/thread-pool/Battery/IO runtime wiring evidence. Host integration evidence stores only fixed enums, counters, and timestamps; `NO_RUNTIME_EVIDENCE` means the operation has not occurred in the current init/stop session, not that static integration is proven broken. Diagnostics are not automatically uploaded.

## Working Rules

- Add KDoc for all `public`, `internal`, and `private` properties and methods.
- Add inline comments at important branches, loops, exception handling, callbacks, and business-significant assignments.
- Extract non-trivial magic numbers and strings into named constants.
- Create SDK threads/executors through `com.apm.core.ApmExecutors`; `apm-uploader` retains module-local executors because it cannot depend on `apm-core`, and those executors must use its explicit named daemon/background thread factory.
- Report degraded-and-swallowed exceptions through `Apm.recordInternalError(tag, error)`.
- Do not route diagnostics file-sink failures back through `ApmLogger` or `Apm.recordInternalError`; that path must remain non-recursive.
- Preserve the durable SQLite outbox as the default storage path.
- Preserve SQLite transaction-scoped claim selection, owner-aware ACK/failure, expiry reclaim, and active-lease prune/trim protection.
- Preserve fail-closed strict-profile validation before SDK resource creation and consent-revocation ordering: reject new events, stop delivery, then erase queued/persisted telemetry without a graceful flush.
- Do not claim a config switch is an automatic hook unless a source-backed runtime path consumes it.

## Git and Documentation Policy

- Commit messages are English `Type: Subject`.
- Allowed prefixes: `Feat`, `Fix`, `Refactor`, `Perf`, `Style`, `Docs`, `Revert`, `Build`.
- `docs/` is tracked and is part of the deliverable.
- `.workbuddy/`, `.github/`, and `.claude/` are local-only and ignored.
- After meaningful code, architecture, build, test, or documentation changes, update `AGENTS.md` and `docs/Android_APM_项目文档.md`.
- Update `README.md` when user-facing capabilities or integration requirements change.
- Update the corresponding `docs/architecture/*.md` whenever module behavior changes.
- Record current verification in the project baseline, and use the changelog and historical verification file for dated evidence.

## Required Finish Checks

1. `git status --short --branch`
2. Relevant Gradle tests/builds under JDK 21
3. For a full client CI refresh, `python tools/verify_ci.py`; otherwise run `python docs/verify_docs.py` plus documentation stale-claim checks
4. `git diff --check`
5. After push: exact equality among `HEAD`, `origin/develop`, and `git ls-remote origin refs/heads/develop`
