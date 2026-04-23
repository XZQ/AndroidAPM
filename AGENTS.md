# AGENTS.md

## Purpose

This file is the repository-local handoff entry for any model or developer taking over this project.
Read this file first, then follow the read order below.

## Read Order

1. `docs/Android_APM_项目文档.md`
2. `README.md`
3. `CLAUDE.md`
4. `docs/architecture/00_整体架构.md`
5. The specific module doc under `docs/architecture/` you are about to change

## Current Verified Baseline

- Verification date: `2026-04-21`
- Build units: `21`
- Composition: `20` root Gradle subprojects (`4` core modules + `15` monitoring modules + `apm-sample-app`) + `1` included build (`apm-plugin`)
- Main source files: `86`
- Test files: `40`
- Verified commands:
  - `JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew assembleDebug`
  - `JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew testDebugUnitTest`
  - `JAVA_HOME=/home/didi/.jdks/jbr_dcevm-11.0.16 ./gradlew -p apm-plugin test`
- Result: all three commands passed on `2026-04-21`

## Important Reality Check

- `README.md` is the product intro, but volatile project status belongs in `docs/Android_APM_项目文档.md`.
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
- Build stack: Kotlin `1.8.10`, AGP `7.4.2`, JDK `11`, compileSdk `34`
- Current package namespace: `com.apm`
- Sample app: `apm-sample-app`
- Gradle plugin build: `apm-plugin` via `pluginManagement { includeBuild("apm-plugin") }`
- Slow-method Gradle plugin uses AGP instrumentation API; no legacy Transform compatibility flag is required.

## First Actions For A New Agent

1. Read `docs/Android_APM_项目文档.md` for the latest verified state.
2. Check `git status --short --branch` and `git log --oneline -n 10`.
3. If task-specific, open the matching module and architecture doc before editing.
4. Before finishing, sync the repository-local status docs.
