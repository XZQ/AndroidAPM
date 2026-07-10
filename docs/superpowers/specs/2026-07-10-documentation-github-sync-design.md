# AndroidAPM Documentation and GitHub Sync Design

## Goal

Make the current local AndroidAPM source code the single factual baseline, synchronize every maintainable documentation artifact to that baseline, restore documentation tracking in Git, and push the resulting repository state to `origin/develop`.

## Approved Scope

- Keep `.workbuddy/` ignored and untracked.
- Keep `.github/` ignored and untracked, including `.github/workflows/ci.yml`.
- Remove the `/docs/` ignore rule.
- Track every other current file under `docs/`, including Markdown, scripts, generated SVG/PNG diagrams, DOCX reports, ZIP archives, and JPEG assets.
- Update all text, generated, and report artifacts whose contents can be derived from the current source tree.
- Preserve historical ZIP/JPEG assets when they have no reproducible source, and identify them as historical artifacts in the documentation index rather than presenting them as current architecture evidence.
- Update tracked top-level documentation, especially `AGENTS.md`, `README.md`, and `CLAUDE.md` when long-term rules or public capability statements require correction.
- Commit the synchronized result and push the current `develop` branch directly to `origin/develop`.

## Source of Truth

Documentation claims must be derived in this order:

1. Current source code and build configuration.
2. Current executable tests and generated test reports.
3. Current local Git state and remote equality checks.
4. Existing documentation only as editorial input, never as evidence that overrides code.

The sync must verify the 22 root Gradle subprojects, the `apm-plugin` and `build-logic` included builds, source/test counts, public configuration defaults, module entry points, event delivery semantics, build stack, and integration requirements directly from the repository.

## Documentation Structure

### Canonical handoff documents

- `AGENTS.md`: repository rules, read order, verified baseline, and concise current state.
- `docs/Android_APM_项目文档.md`: detailed project status, architecture, module inventory, verification evidence, and roadmap.
- `docs/PROJECT_HANDOFF.md`: portable handoff snapshot without self-referential “latest commit” claims.
- `README.md`: user-facing capabilities, honest integration requirements, and quick start.
- `CLAUDE.md`: durable coding and synchronization rules only.

### Architecture documentation

- `docs/architecture/00_整体架构.md`: first-principles system boundary, runtime pipeline, lifecycle, threading, durability, and known limits.
- `docs/architecture/01_apm-core.md` through `19_apm-otel-exporter.md`: each file must match the corresponding module’s actual classes, public entry points, configuration flags, automatic versus manual integration, data output, thread behavior, and current limitations.
- `docs/architecture/README.md`: complete index and reading order.

### Review and generated artifacts

- Reconcile `docs/APM_Optimization_2026-07-08.md` and `docs/APM_Review_2026-07-08.md` with fixes that landed after those reviews, retaining only still-valid findings.
- Update `docs/generate_report.py` so generated reports use the same current facts and terminology.
- Rebuild generated SVG/PNG architecture diagrams when their topology or labels differ from the source-derived design.
- Regenerate DOCX reports from the synchronized source documentation when the local generation path supports it.
- Keep shell monitoring scripts tracked and document their purpose and environment assumptions.

## Accuracy Rules

- Separate implemented capability from enabled-by-default behavior.
- Separate automatic instrumentation from host-required wrappers, interceptors, and callbacks.
- Do not claim a production backend, external Maven publication, cloud CI, native symbolization service, or device soak coverage unless the repository contains fresh evidence.
- Describe the durable outbox as acknowledged, at-least-once delivery with possible duplicates; do not imply exactly-once delivery.
- Document the absence of event-level idempotency and concurrent upload claim/lease semantics.
- Mark configuration switches that lack a complete runtime implementation instead of presenting them as delivered automation.
- Document default safety settings, including Logcat fallback, disabled PII sanitization, disabled aggregation, disabled multi-process coordination, and opt-in high-risk native/Hprof behaviors.
- Use one consistent set of module, source, test, and verification counts across all maintained documents.
- Avoid embedding a documentation commit as the “latest current commit”; identify verified implementation baselines and instruct readers to use `git log` for the current tip.

## Validation Design

The synchronized documentation is accepted only after all of the following pass:

1. Repository inventory scripts reproduce module, Kotlin, C, proto, and test-file counts.
2. Searches find no stale module totals, stale build-unit totals, obsolete primary FileEventStore flow, legacy Transform claims, or claims that `.github` CI is tracked.
3. Every Markdown link to a local file resolves.
4. Generated diagrams and DOCX files can be opened or parsed after regeneration.
5. `testDebugUnitTest --rerun-tasks` passes under JDK 21.
6. `assembleDebug` and `./gradlew -p apm-plugin test` pass under JDK 21.
7. Documentation-only changes leave source compilation behavior unchanged.
8. `git status`, staged-file review, and diff inspection show only the approved documentation, generated artifacts, `.gitignore`, and required top-level documentation changes.
9. After push, local `HEAD`, `origin/develop`, and `git ls-remote origin refs/heads/develop` are identical.

Release, lint, local Maven publication, and smoke-consumer validation may also be run when their cost is reasonable; if not run, the final report must distinguish them from the checks executed in this synchronization.

## Git and GitHub Strategy

- Work on the existing `develop` branch because the user explicitly requested synchronization of the current authoritative checkout.
- Keep `.workbuddy/` and `.github/` ignored.
- Restore `docs/` tracking by removing only the `/docs/` ignore rule.
- Stage explicit approved paths and review the full staged diff before committing.
- Use English commit messages with the repository-approved prefix format.
- Push directly to `origin/develop` over the configured Git remote.
- Do not create a pull request. GitHub CLI is unavailable, and a PR is not required for the approved direct synchronization.

## Success Criteria

The work is complete when GitHub contains the same tracked source and documentation baseline as the local checkout, all maintained documents describe the current code without known contradictions, `.workbuddy/` and `.github/` remain ignored, verification evidence is recorded precisely, and local/remote commit equality is proven after push.
