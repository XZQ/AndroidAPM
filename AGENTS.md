# AGENTS.md

## Purpose

This file is the repository-local handoff entry for any model or developer taking over this project.
Read this file first, then follow the read order below.

## Read Order

1. `docs/Android_APM_项目文档.md`
2. `docs/PROJECT_HANDOFF.md`
3. `README.md`
4. `CLAUDE.md`
5. `docs/architecture/00_整体架构.md`
6. The specific module doc under `docs/architecture/` you are about to change

## Current Verified Baseline

- Verification date: `2026-07-07`
- Build units: `24`
- Composition: `22` root Gradle subprojects (`4` core modules + `15` monitoring modules + `2` extension modules (apm-trace, apm-otel-exporter) + `apm-sample-app`) + `2` included builds (`apm-plugin`, `build-logic`)
- Main source files: `128` (123 Kotlin + 4 C + 1 proto)
- Test files: `63`
- Recent implementation commits: 2026-07-07 optimization series (see `git log --oneline -n 20`)
- Current hardening (2026-07-07 series):
  - build-logic convention plugin + POM metadata + optional signing
  - JNI static-binding fixes (apm-io, apm-crash) with contract tests
  - native SIGQUIT ANR detection shipped (`libapm-anr.so`, flag-poll design)
  - protobuf priority field, HTTP stream draining + Retry-After hints
  - dispatcher bounded-queue batch pipeline off the caller thread,
    SQLite batch transactions + cached row counter, rate-limiter LRU,
    non-blocking upload retry backoff, outbox TTL pruning, IPC file batching
  - ApplicationExitInfo exit-reason collection (API 30+), true process-start
    launch baseline, real clock-tick CPU math, ApmSQLiteDatabase wrapper
  - WAL enabled via setWriteAheadLoggingEnabled (execSQL PRAGMA crashed)
  - shared ApmExecutors, Robolectric-backed SQLiteEventStore tests,
    CI triggers for main/master + lint report artifacts
- Verified commands:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
  - `./gradlew -p apm-plugin test`
  - `./gradlew lintDebug`
  - `./gradlew assembleRelease`
  - `./gradlew publishToMavenLocal`
  - `./gradlew -p smoke-tests/maven-consumer clean assembleDebug`
- Result: all commands passed on `2026-07-04`

## Important Reality Check

- `README.md` is the product intro, but volatile project status belongs in `docs/Android_APM_项目文档.md`.
- `docs/PROJECT_HANDOFF.md` is the portable handoff snapshot for changing computers or agents.
- `CLAUDE.md` contains coding and commit constraints that should be treated as project rules, not Claude-only suggestions.
- The old Claude workflow referenced an external private memory file outside the repository. That is not a reliable cross-model source of truth.
- The repository-local source of truth for current status is `docs/Android_APM_项目文档.md`.

## Working Rules

- Add KDoc for all `public` / `internal` / `private` properties and methods.
- Create SDK threads/executors through `com.apm.core.ApmExecutors`; apm-uploader (which cannot see apm-core) keeps module-local executors and logs through the injected `UploaderLogger`.
- Report degraded-and-swallowed exceptions through `Apm.recordInternalError(tag, error)` instead of empty catch blocks.
- Add inline comments at important branches, loops, exception handling, assignments with business meaning, and callbacks.
- Extract magic numbers and strings into named constants unless the value is a trivial `0`, `1`, or `-1`.
- Use English commit messages in the format `Type: Subject`.
- Valid commit prefixes: `Feat`, `Fix`, `Refactor`, `Perf`, `Style`, `Docs`, `Revert`, `Build`.

## State Sync Rules

- After any meaningful code, architecture, build, test, or documentation change, update `docs/Android_APM_项目文档.md`.
- At minimum, sync:
  - verification date
  - module/file/test counts if they changed
  - build/test status
  - recent commit hash after commit
  - affected module status or architecture notes
- If a user-facing capability summary changes, also sync `README.md`.
- If a module design changes, also sync the corresponding file in `docs/architecture/`.

## Quick Orientation

- Project type: multi-module Android APM framework
- Build stack: Kotlin `2.2.21`, AGP `8.13.2`, Gradle `8.13`, JDK `21`, JVM bytecode target `11`, compileSdk `34`
- Current package namespace: `com.apm`
- Sample app: `apm-sample-app`
- Gradle plugin build: `apm-plugin` via `pluginManagement { includeBuild("apm-plugin") }`
- Slow-method Gradle plugin uses AGP instrumentation API; no legacy Transform compatibility flag is required.

## First Actions For A New Agent

1. Read `docs/Android_APM_项目文档.md` for the latest verified state.
2. Read `docs/PROJECT_HANDOFF.md` for the current handoff summary and future work list.
3. Check `git status --short --branch` and `git log --oneline -n 10`.
4. If task-specific, open the matching module and architecture doc before editing.
5. Before finishing, sync the repository-local status docs.
