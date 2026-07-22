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
7. The sibling `AndroidAPM-Server/docs/云端待建设清单.md` for every external/cloud dependency

## Current Verified Baseline

- Documentation synchronization date: `2026-07-22`
- Branch: `develop`; use `git log --oneline -n 10` for the current tip
- Runtime tip: use `git log --oneline -n 10`; the signed remote-config milestone and docs share one delivery commit
- Build units: `26`
- Composition: `24` root Gradle subprojects (`5` foundation + `15` monitoring + `2` extension + `apm-sample-app` + non-published `apm-benchmark`) and `2` included builds (`apm-plugin`, `build-logic`)
- Main source files: `156` (`151` Kotlin + `4` C + `1` proto)
- Test/benchmark files: `93`
- Toolchain: Java `17`; Gradle runtime JDK `17+`; Gradle `8.13`, AGP `8.13.2`, Kotlin `2.2.21`
- Android: compileSdk `34`, minSdk `24`, targetSdk `34`; JVM bytecode target `17`
- The root build, both included builds, and the isolated Maven consumer use Java `17` toolchains without rejecting newer Gradle-compatible JDK runtimes; Java and Kotlin compilation targets Java `17` bytecode.

Fresh checks executed on `2026-07-16` against the completed client-closure tip:

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat :apm-model:test --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

All commands passed under JDK `17.0.14`. Root Gradle XML reports contain `94` suites and `595` tests with `0` failures/errors/skips; the included `apm-plugin` build separately passed `18` tests. Lint produced `23` HTML reports; the sample Release artifact is `apm-sample-app-release-unsigned.apk` (`4,708,872` bytes). Maven Local contains the current `com.apm:*-0.1.0` publications (`21` AAR, `23` JAR, `22` POM); `apm-benchmark` is absent from Maven Local, and the isolated consumer resolved the published SDK modules successfully. Representative model, Android library, Gradle plugin, and sample classes all report class-file major version `61` (Java 17). The root build configuration and `:apm-model:test` also passed with Gradle launched by JDK `21.0.11`; the resulting model class remained major version `61`. A Xiaomi `22041216UC` and Android 17 emulator were visible to ADB: physical benchmark installation was blocked by device policy (`INSTALL_FAILED_USER_RESTRICTED`), while the emulator completed all three benchmark methods and generated JSON/Perfetto output after suppressing the expected `EMULATOR` gate, but the task ended on an AndroidX `IsolationActivity` launch timeout. These emulator values are execution evidence, not physical performance claims; accepted physical measurements remain external validation.

Incremental P0 hardening checks on `2026-07-21` used JDK `17.0.14`: `:apm-core:testDebugUnitTest --no-daemon` passed `23` suites / `153` tests with zero failures, and `python docs/verify_docs.py` passed `40` Markdown files / `37` local links. A root `testDebugUnitTest` refresh was attempted but is not a new passing baseline: an external cleanup removed Gradle 8.13 distribution/transform files during the run, causing Kotlin daemon cache `FileNotFoundException` and wrapper re-download failure. Keep the `2026-07-16` `595`-test result as the last verified full-root baseline until the root command is rerun in a stable cache environment.

Second-batch hardening checks on `2026-07-22` used JDK `17.0.14`: `:apm-core:testDebugUnitTest :apm-storage:testDebugUnitTest --no-daemon` passed `23` suites / `160` tests and `6` suites / `36` tests respectively, with zero failures/errors/skips. The storage suite was rerun with `--rerun-tasks` after the chunked trim hardening and passed all `36` tests. `python docs/verify_docs.py` again passed `40` Markdown files / `37` local links. This is a focused incremental baseline; it does not replace the `2026-07-16` full-root `595`-test baseline.

Third-batch measurement/self-observability checks on `2026-07-22` used JDK `17.0.14`: `:apm-fps:testDebugUnitTest :apm-core:testDebugUnitTest --rerun-tasks --no-daemon` passed `4` suites / `31` tests and `23` suites / `162` tests respectively, with zero failures/errors/skips. After the API 26 delayed-frame guard was added, `:apm-fps:testDebugUnitTest :apm-fps:lintDebug --no-daemon` reran successfully. Current `apm-fps` and `apm-core` lint reports both say `No issues found`, and `python docs/verify_docs.py` passed `40` Markdown files / `37` local links. FPS reporting now uses a one-second monotonic time window by default, and FrameMetrics aggregation has deterministic rolling-window tests without per-frame report-object allocation. `sdk_health` has an independent diagnostics summary plus a high-priority telemetry attempt. This focused result does not replace the `2026-07-16` full-root baseline.

## Project Boundary

AndroidAPM is a modular Android client SDK, not a complete hosted APM product. It captures, normalizes, protects, persists, and transports telemetry. A production collector, authentication, tenant isolation, query/aggregation backend, alerting, native symbolization service, and operational dashboards are outside this repository.

The default runtime path is:

```text
monitor module
  -> Apm.emit (caller captures timestamp/thread/context/payload snapshots)
  -> bounded dispatcher queue (2048; higher priority may evict the oldest lower-priority entry; producers never wait)
  -> signed dynamic sampling, optional aggregation, dynamic rate limiting, and default-on PII sanitization
  -> appendBatch (up to 32 drained events)
  -> SQLite durable outbox v3 (50,000 rows; 64 MiB live-payload budget; 256 KiB per-event soft limit; unique eventId)
  -> claim(owner, lease, expiry) -> PersistentUploadWorker
  -> BatchApmUploader / HttpApmUploader / custom uploader
  -> integrator-owned collector
```

Crash-class events can use synchronous local persistence. Optional multi-process forwarding publishes complete `.tmp` files as `.ipc` files before the uploader process consumes them.

Delivery is acknowledged and at least once. Stable `eventId` survives Line Protocol, Protobuf field 14, durable codec, SQLite, and IPC. SQLite write transactions atomically claim rows with owner/expiry; only the owner may acknowledge or fail them, shutdown releases claims, and expired claims are reclaimable. Ambiguous network completion can still retransmit, so the collector must deduplicate by `eventId`.

## Integration Reality

Registration alone does not make every monitor automatic:

- Network requires the OkHttp interceptor/listener or manual completion callbacks.
- SQLite requires `ApmSQLiteDatabase` or `onSqlExecuted` callbacks. Only wrapper `rawQuery` has the database handle, full SQL, and bound arguments needed for threshold-gated `EXPLAIN QUERY PLAN`; monitoring reports are isolated from host database results/exceptions.
- IPC uses `traceBinderCall` or `onBinderCallComplete`; deprecated `enableBinderHook=false` does not use hidden APIs.
- WebView uses explicit per-instance `install/uninstall`, delegate wrappers, `evaluateJavascript`, or callbacks; deprecated global `enableAutoRegister=false` does not take over arbitrary instances.
- Battery WakeLock/GPS/Alarm signals are host callbacks.
- IO uses explicit stream wrappers and an optional xhook-backed native path. Deprecated `enableAutoHook=false` does not take over arbitrary Java streams; Java ThreadLocal call-path suppression prevents double counting without dropping custom streams, Native callback-depth prevents APM-owned IO recursion, fd sessions use mutex/generation protection, duplicate/small-buffer findings report once per bounded path, and wrapper bookkeeping cannot fail completed host IO.
- Slow-method ASM requires the host module to apply `com.apm.slow-method`.
- FPS reports on a monotonic wall-clock interval (`reportIntervalMs=1000` by default) rather than after a fixed frame count. The deprecated `windowSize` remains source-compatible but no longer controls cadence; API 24+ FrameMetrics uses a bounded primitive rolling accumulator on the main callback path.
- Render measures view count/depth and API 24+ FrameMetrics. Deprecated `detectOverdraw=false` is truthful because no supported GPU overdraw counter exists.
- Thread monitoring inspects count/name/BLOCKED state; real ThreadPoolExecutor backlog requires explicit registration. Generic leak fields are deprecated/false.
- GC count/time/allocation/reclaim analysis consumes monotonic ART cumulative counters on an `ApmExecutors` background sampler; missing/reset counters invalidate only that dimension/window, invalid intervals clamp to one second, and available heap dimensions continue.
- Reliability priority is host safety, then telemetry durability, then diagnostic completeness. Recoverable `Exception` failures are isolated at dispatcher/store/uploader/diagnostics boundaries; fatal VM errors are not converted into drops or retries. SQLite isolates an invalid/oversized event from valid batch peers, caps one durable payload at 256 KiB by default, and trims low-priority old rows against a 64 MiB live-payload budget as well as the 50,000-row budget; storage rejection/eviction increments SDK drop health. Active leases remain protected and physical SQLite page/WAL size is not the live-payload metric. Retry hints are bounded to 60 seconds; `maxRetries` means retries after the initial attempt and exhausted rows are pruned immediately after `maxRetries + 1` failures. Cached outbox deletion counts cannot become negative, and diagnostics export failures return failure data.
- `apm-otel-exporter` maps data only; it does not depend on or send through the OTel SDK.
- `apm-remote-config` polls authenticated HTTPS with ETag, verifies canonical JSON with pinned Ed25519/Tink keys, durably records the highest revision and LKG, and publishes only non-expired verified values. It drives global/module kill switches and event sampling/rate limits; endpoint rotation is separately opt-in and accepts only HTTPS.

Important defaults: an empty/unsupported endpoint safely acknowledges and discards without logging payload; Logcat delivery requires explicit `logcat://`; PII sanitization is enabled and covers textual patterns plus high-confidence sensitive field names; debug logging is disabled. Dynamic endpoint rotation, aggregation, multi-process coordination, native crash, Hprof dump, and fork dump remain opt-in. Static HTTP headers and the per-request credential provider are empty.

AutoThrottle degradation is immediate and sticky. Recovery requires three consecutive periods at or below 20% drop rate and 3 seconds average upload latency; a degraded or hysteresis-band period resets the recovery streak. Registration and signed dynamic-config reconciliation cannot restart a module while it is held by auto-throttle, and recovery still rechecks process/dynamic/gray gates.

SDK self-diagnostics are separate from event delivery. They are enabled by default and bound both the 200-record memory ring and 256-record writer queue to 4 MiB each, plus up to three 512 KiB app-private JSONL segments per Android process. Process journals are isolated; aggregate export selects up to 16 recent process directories and is bounded to 10,000 records / 16 MiB of uncompressed JSONL. Each periodic `sdk_health` report first records a payload-free numeric summary in this independent journal, then attempts normal telemetry at HIGH priority; the journal copy does not depend on dispatcher/outbox/uploader. `ApmDiagnostics` exposes cached status, synchronous/async snapshot and ZIP export, current-process clear, and explicit all-process clear APIs. Diagnostics are not automatically uploaded.

## Working Rules

- Add KDoc for all `public`, `internal`, and `private` properties and methods.
- Add inline comments at important branches, loops, exception handling, callbacks, and business-significant assignments.
- Extract non-trivial magic numbers and strings into named constants.
- Create SDK threads/executors through `com.apm.core.ApmExecutors`; `apm-uploader` retains module-local executors because it cannot depend on `apm-core`.
- Report degraded-and-swallowed exceptions through `Apm.recordInternalError(tag, error)`.
- Do not route diagnostics file-sink failures back through `ApmLogger` or `Apm.recordInternalError`; that path must remain non-recursive.
- Preserve the durable SQLite outbox as the default storage path.
- Preserve SQLite transaction-scoped claim selection, owner-aware ACK/failure, expiry reclaim, and active-lease prune/trim protection.
- Do not claim a config switch is an automatic hook unless a source-backed runtime path consumes it.

## Git and Documentation Policy

- Commit messages are English `Type: Subject`.
- Allowed prefixes: `Feat`, `Fix`, `Refactor`, `Perf`, `Style`, `Docs`, `Revert`, `Build`.
- `docs/` is tracked and is part of the deliverable.
- `.workbuddy/`, `.github/`, and `.claude/` are local-only and ignored.
- After meaningful code, architecture, build, test, or documentation changes, update `AGENTS.md` and `docs/Android_APM_项目文档.md`.
- Update `README.md` when user-facing capabilities or integration requirements change.
- Update the corresponding `docs/architecture/*.md` whenever module behavior changes.
- Keep volatile verification evidence in the project/handoff documents, not in `CLAUDE.md`.

## Required Finish Checks

1. `git status --short --branch`
2. Relevant Gradle tests/builds under JDK 17
3. `python docs/verify_docs.py` plus documentation stale-claim checks
4. `git diff --check`
5. After push: exact equality among `HEAD`, `origin/develop`, and `git ls-remote origin refs/heads/develop`
