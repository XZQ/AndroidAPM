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

## Current Verified Baseline

- Documentation synchronization date: `2026-07-11`
- Branch: `develop`; use `git log --oneline -n 10` for the current tip
- Latest runtime implementation commit before this documentation sync: `7922c99 Feat: Integrate SDK self-diagnostics`
- Build units: `24`
- Composition: `22` root Gradle subprojects (`4` foundation + `15` monitoring + `2` extension + `apm-sample-app`) and `2` included builds (`apm-plugin`, `build-logic`)
- Main source files: `135` (`130` Kotlin + `4` C + `1` proto)
- Test files: `70`
- Toolchain: JDK `21`, Gradle `8.13`, AGP `8.13.2`, Kotlin `2.2.21`
- Android: compileSdk `34`, minSdk `24`, targetSdk `34`; JVM bytecode target `11`

Fresh checks executed on `2026-07-11` against the completed self-diagnostics tip:

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
```

All commands passed under JDK `21.0.11`. The generated XML reports contain `75` suites and `501` tests with `0` failures/errors/skips; lint produced `21` HTML reports; the sample Release artifact is `apm-sample-app-release-unsigned.apk` (`4,589,624` bytes). Maven Local contains the current `com.apm:*-0.1.0` publications (`20` AAR, `22` JAR, `21` POM), and the isolated consumer resolved them successfully.

## Project Boundary

AndroidAPM is a modular Android client SDK, not a complete hosted APM product. It captures, normalizes, protects, persists, and transports telemetry. A production collector, authentication, tenant isolation, query/aggregation backend, alerting, native symbolization service, and operational dashboards are outside this repository.

The default runtime path is:

```text
monitor module
  -> Apm.emit (caller captures timestamp/thread/context snapshot)
  -> bounded dispatcher queue (2048; overflow drops instead of blocking)
  -> optional aggregation, rate limiting, and PII sanitization
  -> appendBatch (up to 32 drained events)
  -> SQLite durable outbox (50,000 rows)
  -> PersistentUploadWorker
  -> BatchApmUploader / HttpApmUploader / custom uploader
  -> integrator-owned collector
```

Crash-class events can use synchronous local persistence. Optional multi-process forwarding publishes complete `.tmp` files as `.ipc` files before the uploader process consumes them.

Delivery is acknowledged and at least once: rows are deleted only after an uploader reports success, but ambiguous network completion can produce duplicates. There is no event-level idempotency key or concurrent batch claim/lease protocol.

## Integration Reality

Registration alone does not make every monitor automatic:

- Network requires the OkHttp interceptor/listener or manual completion callbacks.
- SQLite requires `ApmSQLiteDatabase` or `onSqlExecuted` callbacks.
- IPC currently exposes `onBinderCallComplete`; `enableBinderHook` is not a delivered generic Binder hook.
- WebView requires the host to forward page/JS/resource callbacks; `enableAutoRegister` is not a delivered automatic registration layer.
- Battery WakeLock/GPS/Alarm signals are host callbacks.
- IO uses stream wrappers and an optional xhook-backed native path.
- Slow-method ASM requires the host module to apply `com.apm.slow-method`.
- Render currently measures view count/depth; overdraw and draw-time config flags are not delivered detectors.
- Thread monitoring inspects count/name/BLOCKED state; thread-pool backlog instrumentation is not delivered.
- `apm-otel-exporter` maps data only; it does not depend on or send through the OTel SDK.

Important defaults: endpoint fallback is Logcat; aggregation, PII sanitization, multi-process coordination, native crash, Hprof dump, and fork dump are opt-in.

SDK self-diagnostics are separate from event delivery. They are enabled by default and retain a 200-record memory ring plus up to three 512 KiB app-private JSONL segments through a bounded 256-record writer queue. `ApmDiagnostics` exposes status, snapshot, ZIP export, and clear APIs. Diagnostics never use the event dispatcher/outbox/uploader and are not automatically uploaded.

## Working Rules

- Add KDoc for all `public`, `internal`, and `private` properties and methods.
- Add inline comments at important branches, loops, exception handling, callbacks, and business-significant assignments.
- Extract non-trivial magic numbers and strings into named constants.
- Create SDK threads/executors through `com.apm.core.ApmExecutors`; `apm-uploader` retains module-local executors because it cannot depend on `apm-core`.
- Report degraded-and-swallowed exceptions through `Apm.recordInternalError(tag, error)`.
- Do not route diagnostics file-sink failures back through `ApmLogger` or `Apm.recordInternalError`; that path must remain non-recursive.
- Preserve the durable SQLite outbox as the default storage path.
- Before adding multiple upload workers or cross-process upload ownership, design batch claim/lease/expiry semantics.
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
2. Relevant Gradle tests/builds under JDK 21
3. `python docs/verify_docs.py` plus documentation stale-claim checks
4. `git diff --check`
5. After push: exact equality among `HEAD`, `origin/develop`, and `git ls-remote origin refs/heads/develop`
