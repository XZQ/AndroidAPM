# AndroidAPM Documentation and GitHub Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the current local AndroidAPM source code the factual documentation baseline, track every local documentation artifact except `.workbuddy/` and `.github/`, validate the synchronized repository, and push `develop` to GitHub.

**Architecture:** Derive one repository inventory from source/build/test evidence, propagate it through canonical handoff documents and per-module architecture documents, then regenerate derived diagrams and reports from those synchronized sources. Git changes remain documentation-only apart from the approved `.gitignore` policy change.

**Tech Stack:** PowerShell, Git, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, JDK 21, Markdown, Python/report generation, SVG/PNG, DOCX.

## Global Constraints

- Treat current source and build files as authoritative; existing prose cannot override code.
- Keep `.workbuddy/` ignored and untracked.
- Keep `.github/` ignored and untracked.
- Track every other current file under `docs/`.
- Preserve JDK 21 as the verification runtime and Java 11 as the produced JVM bytecode target.
- Keep the current project composition at 22 root Gradle subprojects plus 2 included builds unless live inventory proves otherwise.
- Separate implemented capability, default-enabled behavior, manual integration, and future work.
- Use `apply_patch` for text edits.
- Use English commit messages in `Type: Subject` format with an approved repository prefix.
- Do not modify SDK runtime source during this documentation synchronization.

---

### Task 1: Capture the authoritative repository inventory and restore documentation tracking

**Files:**
- Modify: `.gitignore`
- Create: `docs/superpowers/plans/2026-07-10-documentation-github-sync.md`
- Track: `docs/**`

**Interfaces:**
- Consumes: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, all `**/src/main/**`, all `**/src/test/**`, Git status/history.
- Produces: one verified inventory used by every later documentation task and a Git policy where `docs/` is tracked while `.workbuddy/` and `.github/` remain ignored.

- [ ] **Step 1: Record live counts and module topology**

Run:

```powershell
rtk git status -sb
rtk git rev-parse HEAD
rtk git rev-parse origin/develop
rtk rg -n "includeBuild|include\(" settings.gradle.kts
rtk rg --files -g '**/src/main/**/*.kt' -g '!**/build/**' -g '!smoke-tests/**' | Measure-Object -Line
rtk rg --files -g '**/src/main/**/*.c' -g '!**/build/**' -g '!smoke-tests/**' | Measure-Object -Line
rtk rg --files -g '**/src/main/**/*.proto' -g '!**/build/**' -g '!smoke-tests/**' | Measure-Object -Line
rtk rg --files -g '**/src/test/**' -g '!**/build/**' -g '!smoke-tests/**' | Measure-Object -Line
```

Expected: 22 root subprojects, 2 included builds, 123 Kotlin main files, 4 C files, 1 proto file, and 63 test files.

- [ ] **Step 2: Change only the approved ignore policy**

Apply this exact policy to `.gitignore`:

```gitignore
/.workbuddy/
/.github/
/.claude/
```

Remove `/docs/`; leave unrelated existing ignore rules unchanged.

- [ ] **Step 3: Verify tracking boundaries**

Run:

```powershell
rtk git check-ignore -v .workbuddy .github/workflows/ci.yml .claude
rtk git check-ignore docs/Android_APM_项目文档.md
rtk git status --short
```

Expected: `.workbuddy`, `.github`, and `.claude` are ignored; the docs check returns no ignore rule; all local `docs/` artifacts appear as trackable changes except the already tracked design/plan files.

- [ ] **Step 4: Commit the tracking policy and plan**

```powershell
rtk git add .gitignore docs/superpowers/plans/2026-07-10-documentation-github-sync.md
rtk git commit -m "Build: Restore documentation tracking"
```

Expected: commit contains only `.gitignore` and this implementation plan.

---

### Task 2: Synchronize canonical project and handoff documentation

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `CLAUDE.md` only if the tracking/sync rule needs clarification
- Modify: `docs/Android_APM_项目文档.md`
- Modify: `docs/PROJECT_HANDOFF.md`

**Interfaces:**
- Consumes: Task 1 inventory; `Apm.kt`, `ApmConfig.kt`, `ApmDispatcher.kt`, `PersistentUploadWorker.kt`, `ProcessEventCoordinator.kt`, `SQLiteEventStore.kt`, `HttpApmUploader.kt`, `SampleApplication.kt`.
- Produces: the authoritative read order, project boundary, user-facing integration contract, current verification scope, and prioritized unfinished work.

- [ ] **Step 1: Rewrite current-state metadata consistently**

Use `2026-07-10` as the documentation synchronization date. State exactly:

```text
24 build units = 22 root subprojects + 2 included builds
128 main source files = 123 Kotlin + 4 C + 1 proto
63 test files
JDK 21 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.21
Java 11 bytecode / compileSdk 34 / minSdk 24 / targetSdk 34
```

Do not identify a documentation commit as the latest implementation commit. Use `git log --oneline -n 10` for the live tip and identify `3c27ff9` as the latest runtime implementation commit only if history still confirms it.

- [ ] **Step 2: Correct the product boundary and runtime data flow**

Describe the default path as:

```text
monitor module -> Apm.emit -> bounded dispatcher queue (2048)
-> aggregation/rate-limit/PII stages when enabled
-> appendBatch (up to 32 drained events)
-> SQLite durable outbox (50,000 rows)
-> PersistentUploadWorker
-> BatchApmUploader/HttpApmUploader
-> external collector supplied by the integrator
```

Include critical synchronous persistence, optional cross-process `.tmp` to `.ipc` handoff, retry count/TTL pruning, and at-least-once duplicate risk.

- [ ] **Step 3: Correct integration and default-behavior claims**

Document that Network, SQLite, IPC, WebView, Battery, IO, and compile-time slow-method instrumentation require explicit host integration. Document defaults: Logcat fallback, aggregation off, PII sanitization off, multi-process coordination off, native crash off, Hprof/fork dump off.

- [ ] **Step 4: Correct current gaps and roadmap**

List production collector/backend, event idempotency, concurrent outbox lease, device soak/overhead testing, external Maven publication, native symbolization, incomplete automatic-hook flags, and render overdraw as non-complete areas. Remove claims that tracked GitHub Actions currently provide cloud CI.

- [ ] **Step 5: Run canonical-document consistency scans**

```powershell
rtk rg -n "23（22|1 个 included|121（117|57 个测试|Thread\.sleep|exactly-once|恰好一次|GitHub Actions 已|CI 已" AGENTS.md README.md CLAUDE.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md
rtk rg -n "24|128|123 Kotlin|4 C|1 proto|63" AGENTS.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md
```

Expected: the first search contains no stale factual claims; the second shows the same current inventory in all canonical status documents.

- [ ] **Step 6: Commit canonical documentation**

```powershell
rtk git add AGENTS.md README.md CLAUDE.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md
rtk git diff --cached --check
rtk git commit -m "Docs: Sync canonical project baseline"
```

Expected: canonical documentation is internally consistent and contains no runtime source edits.

---

### Task 3: Synchronize core architecture documentation

**Files:**
- Modify: `docs/architecture/README.md`
- Modify: `docs/architecture/00_整体架构.md`
- Modify: `docs/architecture/01_apm-core.md`
- Modify: `docs/architecture/02_apm-model-storage-uploader.md`

**Interfaces:**
- Consumes: Task 2 terminology and the core/model/storage/uploader source files.
- Produces: the shared architecture vocabulary and data-delivery semantics used by all feature-module documents.

- [ ] **Step 1: Align global architecture and lifecycle**

Document initialization, module registration/filtering, dynamic config/gray release, lazy event construction, bounded dispatch, persistent upload, shutdown ordering, self-monitoring, and optional multi-process coordination exactly as implemented.

- [ ] **Step 2: Align model and delivery semantics**

Document `ApmEvent`, priority/severity distinction, reversible `ApmEventCodec`, Line Protocol/Protobuf transport, string normalization in durable fields, 2 MiB payload bound, SQLite schema v2, priority eviction, retry pruning, Gzip, `Retry-After`, and possible duplicates after ambiguous network completion.

- [ ] **Step 3: Remove stale diagrams and claims**

Remove FileEventStore as the primary production path, blocking `Thread.sleep` retry diagrams, unlimited queue implications, and claims of exactly-once delivery. Keep FileEventStore and RetryingApmUploader as explicit compatibility/non-durable paths.

- [ ] **Step 4: Verify core symbols against source**

```powershell
rtk rg -n "QUEUE_CAPACITY|MAX_BATCH_DRAIN|MAX_OUTBOX_RETRIES|OUTBOX_TTL_MS|DEFAULT_MAX_EVENTS|MAX_PAYLOAD_BYTES" apm-core apm-storage apm-model -g '*.kt'
rtk rg -n "2048|32|10|7 天|50,000|2 MiB|at-least-once" docs/architecture/00_整体架构.md docs/architecture/01_apm-core.md docs/architecture/02_apm-model-storage-uploader.md
```

Expected: documented constants and semantics match the live definitions.

- [ ] **Step 5: Commit core architecture documents**

```powershell
rtk git add docs/architecture/README.md docs/architecture/00_整体架构.md docs/architecture/01_apm-core.md docs/architecture/02_apm-model-storage-uploader.md
rtk git diff --cached --check
rtk git commit -m "Docs: Sync core architecture documentation"
```

---

### Task 4: Synchronize monitoring and extension module documentation

**Files:**
- Modify: `docs/architecture/03_apm-memory.md`
- Modify: `docs/architecture/04_apm-crash.md`
- Modify: `docs/architecture/05_apm-anr.md`
- Modify: `docs/architecture/06_apm-launch.md`
- Modify: `docs/architecture/07_apm-network.md`
- Modify: `docs/architecture/08_apm-fps.md`
- Modify: `docs/architecture/09_apm-slow-method.md`
- Modify: `docs/architecture/10_apm-io.md`
- Modify: `docs/architecture/11_apm-battery.md`
- Modify: `docs/architecture/12_apm-sqlite.md`
- Modify: `docs/architecture/13_apm-webview.md`
- Modify: `docs/architecture/14_apm-ipc.md`
- Modify: `docs/architecture/15_apm-thread-monitor.md`
- Modify: `docs/architecture/16_apm-gc-monitor.md`
- Modify: `docs/architecture/17_apm-render.md`
- Modify: `docs/architecture/18_apm-trace.md`
- Modify: `docs/architecture/19_apm-otel-exporter.md`

**Interfaces:**
- Consumes: every corresponding module `*Config.kt`, `*Module.kt`, helper/monitor classes, native CMake/C sources, `apm-plugin` source, and module tests.
- Produces: one honest, source-backed document per monitoring or extension module.

- [ ] **Step 1: Apply a common per-module structure**

Each module document must contain: purpose, actual entry point, automatic hooks, required host integration, configuration defaults, emitted events, threads/resources, fallback/degradation behavior, tests, and known limitations.

- [ ] **Step 2: Correct automatic versus manual integration**

State explicitly:

```text
Network: OkHttp interceptor/listener or manual completion callback
SQLite: ApmSQLiteDatabase wrapper or onSqlExecuted callback
IPC: onBinderCallComplete callback; no delivered generic Binder auto-hook
WebView: host forwards page/JS/resource callbacks; no delivered automatic registration
Battery: host forwards WakeLock/GPS/Alarm lifecycle callbacks
IO: stream wrappers plus optional xhook-backed native path
Slow method: runtime Looper/sampling plus host-applied Gradle ASM plugin
Render: view count/depth only; overdraw/draw-time flags are not delivered detectors
Thread monitor: thread count/name/BLOCKED inspection; no delivered thread-pool backlog instrumentation
OTel exporter: structured mapping only, no OTel SDK/network export
```

- [ ] **Step 3: Verify public API and configuration names**

```powershell
rtk rg -n "^(data class|class|object|    fun |    override fun|    val )" apm-*/src/main -g '*.kt'
rtk rg -n "enableBinderHook|enableAutoRegister|detectOverdraw|enableThreadPoolMonitor" apm-* -g '*.kt'
```

Expected: every public integration method and every non-delivered switch is represented accurately in its module document.

- [ ] **Step 4: Commit module architecture documents**

```powershell
rtk git add docs/architecture/03_apm-memory.md docs/architecture/04_apm-crash.md docs/architecture/05_apm-anr.md docs/architecture/06_apm-launch.md docs/architecture/07_apm-network.md docs/architecture/08_apm-fps.md docs/architecture/09_apm-slow-method.md docs/architecture/10_apm-io.md docs/architecture/11_apm-battery.md docs/architecture/12_apm-sqlite.md docs/architecture/13_apm-webview.md docs/architecture/14_apm-ipc.md docs/architecture/15_apm-thread-monitor.md docs/architecture/16_apm-gc-monitor.md docs/architecture/17_apm-render.md docs/architecture/18_apm-trace.md docs/architecture/19_apm-otel-exporter.md
rtk git diff --cached --check
rtk git commit -m "Docs: Sync module architecture documentation"
```

---

### Task 5: Reconcile review, optimization, scripts, diagrams, and report artifacts

**Files:**
- Modify: `docs/APM_Optimization_2026-07-08.md`
- Modify: `docs/APM_Review_2026-07-08.md`
- Modify: `docs/generate_report.py`
- Review/track: `docs/amstart.sh`
- Review/track: `docs/monitor_app.sh`
- Review/track: `docs/monitor_thread.sh`
- Modify/regenerate: `docs/architecture/generated-diagrams/*.{svg,png}`
- Modify/regenerate: `docs/APM_对比报告.docx`
- Modify/regenerate: `docs/APM_框架对比报告.docx`
- Track/preserve: `docs/记录.zip`
- Track/preserve: `docs/绘制.jpeg`

**Interfaces:**
- Consumes: synchronized canonical and architecture Markdown from Tasks 2-4.
- Produces: derived artifacts that no longer contradict their source documents, plus clearly labeled historical assets.

- [ ] **Step 1: Reconcile dated review documents**

For every 2026-07-08 finding, mark it resolved only when a current commit/source line proves the resolution; retain open items with current priority and remove obsolete counts or paths.

- [ ] **Step 2: Align report generator and monitoring scripts**

Update hard-coded module totals, pipeline terminology, paths, and verification claims in `generate_report.py`. Keep shell scripts functionally unchanged unless their documented package/process paths are stale; add or update usage comments rather than changing runtime behavior.

- [ ] **Step 3: Regenerate diagrams**

Update the five SVG sources so labels and edges match the synchronized architecture. Render matching PNG files using an available local renderer; validate each SVG as XML and each PNG as a readable image.

- [ ] **Step 4: Regenerate DOCX reports**

Use the available workspace document runtime or `docs/generate_report.py` to rebuild both DOCX reports from synchronized facts. Open the DOCX containers as ZIP files and verify `[Content_Types].xml` and `word/document.xml` exist.

- [ ] **Step 5: Register historical artifacts honestly**

Add `记录.zip` and `绘制.jpeg` to the docs index as historical/reference artifacts with their original dates. Do not describe them as the current architecture baseline.

- [ ] **Step 6: Commit derived documentation artifacts**

```powershell
rtk git add docs/APM_Optimization_2026-07-08.md docs/APM_Review_2026-07-08.md docs/generate_report.py docs/amstart.sh docs/monitor_app.sh docs/monitor_thread.sh docs/architecture/generated-diagrams docs/APM_对比报告.docx docs/APM_框架对比报告.docx docs/记录.zip docs/绘制.jpeg
rtk git diff --cached --check
rtk git commit -m "Docs: Refresh generated documentation artifacts"
```

---

### Task 6: Validate the complete synchronized repository

**Files:**
- Modify if evidence requires: `AGENTS.md`
- Modify if evidence requires: `docs/Android_APM_项目文档.md`
- Modify if evidence requires: `docs/PROJECT_HANDOFF.md`

**Interfaces:**
- Consumes: all documentation changes from Tasks 1-5.
- Produces: fresh verification evidence and a clean, internally consistent candidate for GitHub.

- [ ] **Step 1: Run stale-claim and link checks**

Run repository-wide searches for old counts, FileEventStore-as-primary diagrams, blocking retry claims, automatic Binder/WebView hooks, tracked GitHub CI, and self-referential latest-documentation hashes. Resolve every true stale hit.

- [ ] **Step 2: Verify document inventory and local links**

Enumerate every file under `docs/`, parse every relative Markdown link, and require each local target to exist. Expected: zero missing local link targets.

- [ ] **Step 3: Force-run root unit tests under JDK 21**

```powershell
rtk proxy powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Users\XZQ\.jdks\jbr-21.0.11','Process'); & '.\gradlew.bat' testDebugUnitTest --rerun-tasks --no-daemon"
```

Expected: `BUILD SUCCESSFUL` with every root test task executed.

- [ ] **Step 4: Run Debug build and plugin tests under JDK 21**

```powershell
rtk proxy powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Users\XZQ\.jdks\jbr-21.0.11','Process'); & '.\gradlew.bat' assembleDebug --no-daemon"
rtk proxy powershell -NoProfile -Command "[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Users\XZQ\.jdks\jbr-21.0.11','Process'); & '.\gradlew.bat' -p apm-plugin test --rerun-tasks --no-daemon"
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Update verification evidence only from executed commands**

Record the date, commands, test totals, warnings, and checks actually run. Keep release/lint/Maven/smoke statements explicitly tied to older evidence unless rerun in this task.

- [ ] **Step 6: Commit final verification sync if needed**

```powershell
rtk git add AGENTS.md docs/Android_APM_项目文档.md docs/PROJECT_HANDOFF.md
rtk git diff --cached --check
rtk git commit -m "Docs: Record synchronized verification baseline"
```

Skip the commit only when validation requires no documentation changes.

---

### Task 7: Publish and prove GitHub equality

**Files:**
- No content changes expected.

**Interfaces:**
- Consumes: the verified commit chain from Tasks 1-6.
- Produces: exact local/remote equality on `develop`.

- [ ] **Step 1: Review the complete publish scope**

```powershell
rtk git status -sb
rtk git log --oneline --decorate origin/develop..HEAD
rtk git diff --stat origin/develop..HEAD
rtk git diff --name-status origin/develop..HEAD
```

Expected: only approved `.gitignore`, top-level documentation, `docs/**`, and design/plan changes; no `.workbuddy/**`, `.github/**`, or runtime source changes.

- [ ] **Step 2: Push the current branch**

```powershell
rtk git push -u origin develop
```

Expected: push succeeds without force.

- [ ] **Step 3: Verify exact remote equality**

```powershell
rtk git fetch origin develop
rtk git rev-parse HEAD
rtk git rev-parse origin/develop
rtk git ls-remote origin refs/heads/develop
rtk git status -sb
```

Expected: all three develop hashes are identical and the working tree is clean.
