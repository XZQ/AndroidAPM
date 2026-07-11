# AndroidAPM Client Completion Design

## Goal

Complete every capability that can be made truthful, reliable, and testable inside the Android client repository. Move every collector, hosted-service, external publication, cloud-CI, symbolization-service, and device-lab dependency into one canonical cloud work document.

## Completion Boundary

The client is complete when:

1. every emitted event has a stable client-generated identity across persistence, IPC forwarding, retry, and every wire format;
2. durable upload selection is safe for concurrent workers and processes through transactional claim, lease, acknowledgement, failure release, and expiry;
3. every public monitoring switch is either consumed by a real runtime path or explicitly deprecated and disabled by default;
4. supported Binder, WebView, thread-pool, and render integrations have concrete APIs and tests;
5. a reproducible Android benchmark harness exists for device execution, while device-farm execution and published reports are identified as external work;
6. README restores a source-backed, qualified comparison with WeChat Matrix and Kuaishou KOOM;
7. cloud-only work is defined once in `docs/云端待建设清单.md`, and other maintained documents link to it instead of duplicating drifting lists.

The client is not required to implement a production collector, authentication, tenant isolation, server-side deduplication, query/aggregation, alerting, dashboards, a symbol server, Maven Central credentials, GitHub-hosted CI, or a physical-device laboratory.

## Subproject 1: Event Identity

### Model

`ApmEvent` gains a trailing `eventId` property to preserve source compatibility for existing positional constructors. New events receive an ID from a process-scoped generator containing a random process prefix and monotonic sequence. `copy()` retains the identity.

The ID is opaque. Consumers must not infer timestamps, users, devices, or tenants from it.

### Formats

- Line Protocol emits `eventId` as a top-level controlled field.
- Protobuf adds field 14 without renumbering existing fields.
- The zero-dependency protobuf writer emits field 14.
- The durable binary codec advances to version 2 and continues to decode version 1.
- Process IPC forwarding uses the durable codec, so identity survives forwarding automatically.

### Legacy Rows

SQLite schema version 3 adds an indexed `event_id` column. Existing rows receive deterministic install-local IDs derived from their stable row IDs during migration. When a version-1 payload is read, the store copies the row's `event_id` into the decoded event before upload. A retry therefore cannot generate a different identity.

## Subproject 2: Transactional Outbox Claims

Schema version 3 also adds nullable `lease_owner` and integer `lease_expires_at` columns plus an availability index.

`PendingEventStore` adds explicit operations:

- `claimPending(ownerId, limit, nowMs, leaseDurationMs)`
- `acknowledgeClaim(ownerId, ids)`
- `failClaim(ownerId, ids)`
- `releaseClaims(ownerId)`

The production SQLite implementation performs selection and ownership update in one write transaction. Rows are claimable when unowned or expired. Acknowledgement and failure mutation require the matching owner. Failed rows increment retry count and clear their lease. Process death requires no callback because expired claims become available again.

Legacy `readPending`, `deletePending`, and `markRetry` remain for source compatibility and tests, but production upload uses only the ownership-aware API.

Each `PersistentUploadWorker` creates one unique owner ID. Its lease duration is configurable and must be greater than the uploader's expected single-attempt bound. Shutdown releases that worker's claims best-effort; correctness never depends on release because expiry is authoritative.

## Subproject 3: Truthful Monitoring Integrations

### Configuration Audit Rule

Every public config property in every module must satisfy one of these conditions:

1. a source-backed runtime branch consumes it and tests prove the behavior; or
2. it is marked deprecated, defaults to the safe disabled value, and documentation names the supported replacement.

### IPC

- `enableBinderHook` remains binary/source visible, is deprecated, and defaults to `false`.
- `IpcModule.traceBinderCall(interfaceName, methodName, block)` measures a real host-selected Binder call and reports through the existing completion path, including failures.
- Existing `onBinderCallComplete` remains supported.
- `enableBinderAggregation` becomes real: completion callbacks feed a bounded window aggregator that emits count, average, maximum, slow count, and main-thread count.

No hidden-API or reflection BinderProxy hook is installed.

### WebView

- `enableAutoRegister` is deprecated and defaults to `false`.
- `WebviewModule.install(webView, delegateClient, delegateChromeClient)` installs public-API wrappers that forward to caller delegates while observing page start/finish, resource callbacks, received errors, and console errors.
- `uninstall(webView)` removes only wrappers installed by the module and restores the supplied delegates.
- Manual callbacks remain available for custom WebView stacks.

The SDK never searches arbitrary view trees or replaces unknown clients without host authorization.

### Thread Pools

- `enableThreadPoolMonitor` becomes real for explicitly registered `ThreadPoolExecutor` instances.
- The module exposes `registerThreadPool(name, executor)` and `unregisterThreadPool(name)`.
- Scheduled snapshots report queue depth, active count, pool size, completed count, and rejection-related saturation when the configured backlog threshold is crossed.
- Registrations are weakly owned or explicitly removed so the monitor does not keep host pools alive.
- The unused generic `enableThreadLeakDetect` and `threadLeakThresholdMs` contract is deprecated and disabled; duplicate-name and BLOCKED-thread detection remain the supported leak/deadlock heuristics.

### Render

- `detectOverdraw` is deprecated and defaults to `false`; Android exposes no stable production API for generic GPU-overdraw measurement.
- A trailing `slowFrameThresholdMs` replaces the misleading `viewDrawThresholdMs` name while inheriting an explicitly supplied legacy value for compatibility.
- API 24+ activities register `Window.OnFrameMetricsAvailableListener` through a module-owned background handler thread created by `ApmExecutors`.
- Frame totals are aggregated and slow/frozen frames produce bounded events. Listeners and the handler thread are removed during activity destruction and module stop.
- Existing view-count and hierarchy-depth inspection remains.

## Subproject 4: Device Benchmark Harness

A non-published Android benchmark test module targets `apm-sample-app`. It provides repeatable scenarios for:

- no-SDK baseline;
- initialized SDK with no monitoring modules;
- all supported modules enabled;
- event throughput and dispatcher pressure;
- SQLite append, claim, retry, acknowledgement, and pruning;
- diagnostics recording and export.

The harness records wall time, CPU time where available, Java heap delta, SQLite/diagnostic disk growth, emitted/dropped counts, and benchmark environment metadata. It builds in local verification. Physical device execution, multi-model matrices, power rails, thermal control, and accepted performance budgets belong to the cloud/device-lab document because this checkout currently has no connected device.

## Cloud Work Document

`docs/云端待建设清单.md` is the single canonical backlog for:

- collector ingestion and acknowledgement contract;
- authentication, tenant isolation, quotas, and abuse controls;
- server-side event-ID deduplication and retention;
- storage, query, aggregation, alerting, and dashboards;
- native symbol upload, build-ID indexing, tombstone symbolization, and retention;
- Maven Central or private repository credentials, signing, staging, and promotion;
- tracked cloud CI workflows and protected-branch policy;
- device-farm execution, power/thermal methodology, and published benchmark reports.

Each section states the client input already available, the cloud output required, and objective acceptance criteria.

## README Comparison

README restores a comparison table for AndroidAPM, WeChat Matrix, and Kuaishou KOOM. It must be rebuilt from current primary project documentation rather than copying the older table's unsupported claims.

Legend:

- `✅` means a complete source-backed runtime path exists in the compared project.
- `◐` means explicit host integration, optional plugin, or partial coverage is required.
- `—` means the project does not claim that capability in the cited primary documentation.

The table includes a comparison date and links directly to the Matrix and KOOM repositories. It does not claim hosted backend capabilities for AndroidAPM.

## Compatibility and Migration

- New public data-class properties are trailing and have defaults.
- Deprecated config fields remain present for the v1.x line.
- Durable codec v1 remains readable.
- SQLite v2 to v3 migration is additive and preserves rows.
- Protobuf fields are append-only.
- Existing manual monitoring callbacks remain supported.
- FileEventStore remains a documented non-durable compatibility path and does not pretend to support claims.

## Error Handling

- Claim transactions roll back completely on failure.
- Worker ownership mismatch is reported and never deletes another owner's rows.
- Unsupported automatic flags emit one bounded diagnostic warning only when a caller explicitly enables the legacy flag.
- Wrapper callbacks isolate monitoring failures and preserve delegate behavior.
- Benchmark collection failures are recorded as missing metrics rather than invalid zeroes.
- Degraded exceptions follow `Apm.recordInternalError`; diagnostics sink failures remain non-recursive.

## Testing and Verification

Test-first coverage includes:

- ID uniqueness, copy stability, line/protobuf/codec round trips, and v1 decode;
- v2-to-v3 database migration with stable legacy IDs;
- two-store and two-worker claim exclusion, owner-checked acknowledgement, failure release, expiry reclaim, shutdown release, and corrupt-row isolation;
- IPC tracing and aggregation;
- WebView wrapper delegate preservation and uninstall;
- registered thread-pool backlog detection and weak ownership;
- render frame aggregation and lifecycle cleanup;
- config audit tests proving every deprecated field is safely disabled;
- benchmark module compilation and scenario metadata;
- README/cloud-document link verification.

Final verification remains the repository JDK 21 unit-test, Debug, lint, Release, Maven Local, plugin-test, isolated consumer, documentation, diff, push, and exact remote-equality chain.
