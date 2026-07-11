# AndroidAPM Production Reliability Fault-Injection Design

## Goal

Prove and harden the Android client against failures in its own monitoring path. The governing priority is:

1. host application safety;
2. telemetry durability and ownership correctness;
3. diagnostic completeness.

When those goals conflict, the SDK may shed telemetry, pause a failing component, or degrade to a smaller capability set. It must not block a host callback indefinitely, replace a host return value or exception, delete an event without a valid acknowledgement, or create an unbounded resource loop.

## Scope

This pass covers five client-side boundaries:

- public `Apm` emission and monitor callbacks;
- the bounded dispatcher, transformation pipeline, and durable append;
- the SQLite outbox, event codec, claim/lease/expiry, and cached row accounting;
- the persistent upload worker and uploader boundary;
- the independent SDK diagnostics recorder and file sink.

It does not add a collector, server-side partial acknowledgement, a device laboratory, hidden-API hooks, or a public fault-injection mode. Test seams remain `internal` or test-only.

## Alternatives Considered

### Tests only

Add failure tests without changing runtime behavior. This is low risk but leaves every discovered violation for a later design pass and does not close the reliability loop.

### Central reliability coordinator

Route every module through one circuit-breaker and recovery service. This centralizes policy but creates a new critical dependency, couples otherwise independent modules, and increases the failure blast radius.

### Invariant-driven fault injection with minimal fixes

Inject deterministic failures at existing boundaries, preserve the current modular architecture, and change production code only when a test proves an invariant violation. This is the selected approach.

## Reliability Invariants

### Host behavior preservation

- Monitoring wrappers return the host result unchanged.
- Monitoring failures do not replace a host exception with an APM exception.
- Caller-facing emission remains non-blocking: bounded queue insertion uses an immediate offer and records a drop when full.
- Monitoring callbacks that run around host work isolate only non-fatal monitoring exceptions.

### Fatal error policy

The SDK does not broadly catch `Throwable`. `VirtualMachineError` (including `OutOfMemoryError`), `ThreadDeath`, and linkage failures are not treated as recoverable monitoring errors. Memory pressure is handled proactively through bounded queues, bounded maps/rings, batched work, and cache shedding rather than attempting to continue after a fatal VM condition.

### Durable ownership correctness

- A durable row is deleted only after uploader success and an owner-matching acknowledgement.
- Claim selection and lease assignment are one transaction.
- A failed or interrupted attempt retains the row and either releases ownership or relies on expiry.
- A stale worker cannot acknowledge, fail, or delete another worker's reclaimed row.
- Corrupt payload isolation may delete only the undecodable row; valid neighbors remain claimable.

### Bounded degradation

- Repeated storage, uploader, or diagnostics failures do not create a busy loop.
- Queues, maps, retained records, threads, files, retry counters, and backoff values stay bounded.
- Shutdown returns within its documented bound even if a custom uploader ignores interruption; lease expiry remains the recovery authority.
- Wall-clock rollback and arithmetic overflow do not create an effectively permanent lease.

### Diagnostic visibility

- Recoverable failures receive a stable internal-error reason code and a bounded diagnostic record.
- Diagnostic file-sink failures never recurse through `ApmLogger` or `Apm.recordInternalError`.
- A diagnostic failure cannot make an already handled host or telemetry operation fail.

## Fault-Injection Architecture

Production interfaces remain the injection boundaries. Tests use fake or throwing implementations of `EventStore`, `PendingEventStore`, `ApmUploader`, clocks, executors, config/context providers, and diagnostic sinks where a seam already exists. If a deterministic seam is missing, add the narrowest `internal` constructor dependency rather than a global service locator or public debug switch.

Each test follows the same structure:

1. establish a valid host operation or durable state;
2. inject exactly one failure or race at a named boundary;
3. assert the host-visible result and exception are unchanged;
4. assert durable rows and leases satisfy ownership rules;
5. assert work terminates or backs off within a deterministic bound;
6. assert one non-recursive diagnostic signal when diagnostics are available.

Randomized sequence tests use fixed recorded seeds so every failure is reproducible. They supplement concrete regression cases and do not replace them.

## Failure Matrix

### Public API and dispatcher

- business-context provider throws;
- aggregation, rate limiting, PII sanitization, serialization, or append throws;
- queue is full while the caller is on the main thread;
- `emit`, flush, and shutdown race;
- executor rejects startup or scheduled aggregation work;
- one malformed event is adjacent to valid events in a drained batch.

Expected result: the caller never waits for storage/network work, valid later events continue when safe, drops/failures are counted, and no failed append is reported as durable.

### SQLite outbox

- database full, locked, unavailable, or closed;
- transaction fails before and after claim selection;
- payload bytes or stored `event_id` are malformed;
- two stores race to claim the same rows;
- lease expires during upload and another owner reclaims it;
- wall clock moves backward or approaches `Long.MAX_VALUE`;
- cached count is uninitialized, stale, or adjusted after partial deletion.

Expected result: transactions roll back atomically, counts never become authoritative negative values, owner checks prevent cross-worker mutation, and corrupt-row deletion is isolated.

### Upload worker

- claim, count, acknowledge, fail-claim, release, or prune throws;
- single and batch uploaders throw or return failure;
- retry hints are negative, extreme, or larger than local backoff;
- shutdown interrupts an idle wait or active attempt;
- a custom uploader blocks beyond the lease and ignores interruption.

Expected result: the worker stays off the caller thread, does not spin, never acknowledges failure, caps retry arithmetic, and permits expiry-based recovery. The SDK documents that a custom synchronous uploader must provide its own bounded I/O; the SDK cannot safely terminate arbitrary host code.

### Diagnostics

- queue saturation;
- directory creation, segment append, rotation, export, or clear fails;
- malformed existing JSONL or segment metadata;
- shutdown races with record/export/clear;
- diagnostic context itself contains oversized or hostile values.

Expected result: memory and disk remain bounded, failure handling is non-recursive, export contains only readable snapshots, and telemetry operation results are unaffected.

### Configuration boundaries

- zero, negative, and maximum intervals, capacities, thresholds, lease durations, and batch sizes;
- explicitly enabled deprecated compatibility switches;
- repeated start/stop and duplicate module registration.

Expected result: values are rejected or clamped at one documented boundary, deprecated switches warn once, and lifecycle operations are idempotent.

## Minimal Production Hardening Policy

A failing test may justify only one of these local changes:

- isolate a non-fatal monitoring callback;
- add a bounded retry/backoff or failure cooldown;
- repair transactional ownership or cached-state accounting;
- add an internal deterministic dependency such as a clock;
- release or cap a resource;
- add a stable diagnostic reason code;
- document an unavoidable host-owned boundary.

The pass must not introduce a central coordinator, catch fatal JVM errors, silently change wire types, weaken at-least-once delivery, or add unverified automatic hooks.

## Delivery Slices

1. Public API and dispatcher failure firewall.
2. SQLite corruption, transaction, count, and clock/lease hardening.
3. Worker retry, shutdown, reclaim, and uploader-boundary hardening.
4. Diagnostics failure isolation and lifecycle races.
5. Cross-layer randomized state-machine tests, documentation sync, and full release verification.

Each slice starts with a failing regression or invariant test and ends with the smallest implementation needed to pass it.

## Verification

The focused suite must prove the fault matrix with deterministic unit or Robolectric tests. The final repository check remains:

```powershell
./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
./gradlew.bat assembleDebug --no-daemon
./gradlew.bat -p apm-plugin test --rerun-tasks --no-daemon
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin --no-daemon
./gradlew.bat lintDebug assembleRelease publishToMavenLocal --no-daemon
./gradlew.bat -p smoke-tests/maven-consumer clean assembleDebug --no-daemon
python docs/verify_docs.py
git diff --check
```

Completion additionally requires a code review with no remaining Critical or Important finding, updated verification counts/artifact evidence, a clean `develop`, a successful push, and exact equality among `HEAD`, `origin/develop`, and `refs/heads/develop` on GitHub.

## Acceptance Criteria

- Every failure matrix row has a deterministic test or an explicit, source-backed host-owned limitation.
- No new public fault-injection API or global reliability coordinator exists.
- No test relies on sleeping for race correctness when a latch, fake clock, or barrier can make it deterministic.
- Recoverable APM failures preserve host return values and exceptions.
- Durable rows are never acknowledged without uploader success and current ownership.
- Repeated failure paths are bounded in CPU, memory, disk, thread count, and diagnostic volume.
- Maintained docs describe actual behavior, including custom-uploader and fatal-error boundaries.
