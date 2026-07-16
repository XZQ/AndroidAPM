# AndroidAPM Self-Diagnostics Hardening Plan

## 1. Storage isolation and export safety

- Add failing tests for deterministic collision-resistant process directories.
- Add failing tests for aggregate multi-process export and journal-target rejection.
- Implement per-process stores, root aggregation, explicit all-process clear, and canonical collision guards.

## 2. Bounded truthful buffering

- Add failing tests for configuration byte limits, memory eviction, queue byte rejection, and cooldown retention.
- Add UTF-8 entry-size accounting to both buffers.
- Wait through cooldown before dequeue and keep loss accounting producer-visible.

## 3. Accurate status and asynchronous access

- Add failing tests for cached retained bytes, separate read failures, and async callbacks.
- Extend status fields and keep disk traversal out of `status()`.
- Add executor-based snapshot and export APIs.

## 4. Transactional lifecycle and component attribution

- Add failing tests for partial initialization rollback and shutdown continuation.
- Stage `Apm` resources and clean them in reverse order on failure.
- Add scoped loggers and pass component-specific views through core and module contexts.

## 5. Privacy and support metadata

- Add failing tests for JSON, cookie, API-key, secret, and URL-encoded credential shapes.
- Expand sanitizer patterns.
- Add SDK, process, and session metadata to the export manifest.

## 6. Documentation and verification

- Update public docs, architecture docs, project handoff, sample usage, and repository baseline.
- Run focused tests after each implementation slice.
- Run the complete JDK 17 verification chain, docs checks, and `git diff --check`.
- Commit intentionally, push `develop`, and prove `HEAD == origin/develop == ls-remote`.
