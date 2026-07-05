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

- Verification date: `2026-07-04`
- Documentation handoff snapshot: `2026-07-06`
- Build units: `23`
- Composition: `22` root Gradle subprojects (`4` core modules + `15` monitoring modules + `2` extension modules (apm-trace, apm-otel-exporter) + `apm-sample-app`) + `1` included build (`apm-plugin`)
- Main source files: `121`
- Test files: `57`
- Recent implementation commit: `cd2a409 Refactor: Harden runtime delivery and native alignment`
- Previous documentation baseline commit: `a7b2a0a Docs: Sync runtime hardening commit`
- Current hardening: atomic IPC publish, critical-event IPC handoff, configurable HTTP Gzip, lazy FPS monitor creation, and 16KB native page alignment.
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
