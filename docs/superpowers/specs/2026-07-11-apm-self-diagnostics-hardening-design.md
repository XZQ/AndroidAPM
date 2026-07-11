# AndroidAPM Self-Diagnostics Hardening Design

## Goal

Make SDK self-diagnostics reliable when the SDK itself is degraded: bounded in memory and on disk, isolated across Android processes, observable without recursive logging, and safe to initialize, stop, inspect, and export.

## Design Principles

1. Diagnostics must not depend on the telemetry dispatcher, durable outbox, or uploader.
2. Every resource has both a count limit and a byte limit where record size varies.
3. Accepted records are either retained for persistence or reflected in an explicit loss counter.
4. One Android process never writes another process's journal files.
5. Initialization publishes runtime state only after every required component succeeds.
6. Shutdown attempts every cleanup phase and diagnostics remain available until cleanup reporting finishes.
7. File parsing and ZIP creation are worker-thread operations; asynchronous APIs make the safe path easy.
8. Export can never overwrite a live journal segment.

## Storage and Multi-Process Model

The root remains `<filesDir>/android-apm/diagnostics`. Each process writes below a deterministic directory named from a sanitized process-name prefix plus a SHA-256 prefix. The hash prevents collisions between process names that sanitize to the same value.

Each process owns its `diagnostics.jsonl` rotation set. The public export scans all process directories under the root and merges readable records into one bounded ZIP. The current process's in-memory ring is merged and deduplicated by process, session, and sequence. `clear()` remains current-process scoped; `clearAllProcesses()` is explicit.

## Resource Bounds and Backpressure

The memory ring and writer queue retain their record-count limits and add byte budgets. Entry size is measured from the UTF-8 encoded JSON representation plus the newline delimiter.

- The memory ring evicts oldest records until both limits are satisfied.
- The writer queue admits only records that fit both limits.
- During a file-sink cooldown, the writer waits before dequeuing, preserving queued records and making later producer-side drops visible.
- A single record larger than a byte budget remains available only in the bounded in-memory representation when possible and is counted as dropped for persistence.

## Health Metrics

`DiagnosticStatus` exposes memory bytes, queue bytes, dropped records, read failures, write failures, and corrupt records separately. Retained disk bytes are cached after writes, clears, and reads so `status()` performs no file-system traversal.

Scheduler health events use deltas from the previous diagnostic status, preventing cumulative totals from being emitted as if they occurred in each reporting interval.

## Lifecycle and Attribution

`Apm.doInit` constructs resources in local staged variables. Any failure cleans up successfully created resources in reverse order before state can become visible. `Apm.stop` runs each phase independently, reports swallowed failures to diagnostics, and shuts diagnostics down last.

`ApmLogger.withComponent(component)` is a backward-compatible default method. The Android logger returns a scoped view so uploader, dispatcher, privacy, aggregation, and monitoring modules produce diagnostic records with their real component.

## Public Inspection and Export

Synchronous `snapshot()` and `exportTo()` remain for compatibility and are documented as worker-thread APIs. `snapshotAsync()` and `exportToAsync()` accept a caller-provided `Executor` and callback. The export layer rejects targets whose canonical path equals any active or rotated source segment.

The ZIP manifest includes format version, SDK version, export time, process names, session IDs, exported/corrupt counts, and health counters.

## Privacy

Sanitization covers authorization and bearer credentials, token/password variants, API keys, client secrets, generic secrets, cookies, quoted JSON keys, query/form separators, and URL-encoded separators. Values are replaced before truncation.

## Verification

Unit tests cover deterministic process directories, aggregation, collision protection, cooldown behavior, byte budgets, read/write metric semantics, async APIs, sanitizer variants, manifest metadata, lifecycle rollback, shutdown continuation, scoped attribution, and sustained queue pressure. The normal JDK 21 full build, lint, release, Maven publication, plugin tests, consumer smoke build, documentation verification, and exact remote equality checks remain required.
