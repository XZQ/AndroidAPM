# AndroidAPM performance and device-soak gates

`apm-benchmark` is a non-published verification module with two measurement
layers and one aggregate device-lab acceptance contract:

1. AndroidX Microbenchmark measures durable codec, the 32-event SQLite transaction, 32 accepted dispatcher emissions, and HIGH-priority admission into a full 2,048-event queue.
2. The host-driven sample-App campaign measures cold start, SDK init, main-thread emit, CPU, PSS, app-private disk, app UID power attribution, thermal status, collector outage, and process restart recovery.
3. `device-lab-matrix.json` requires non-overlapping legacy, mainstream, and
   modern API lanes plus profile-level device, manufacturer, and reset-strategy
   diversity.

Build-only or host-unit-test success does not produce physical-device performance evidence.

## Current physical-device status

The `2026-07-23` run on a physical Redmi/Xiaomi `22041216UC` (Android 13) produced two distinct results:

- the declared `AndroidBenchmarkRunner` completed the three methods that existed at that revision without suppressed AndroidX errors, and the checked-in verifier accepted encode at `4,640.93 ns / 22.00 allocations`, decode at `4,841.81 ns / 46.00 allocations`, and the 32-event SQLite transaction at `1,258,990.52 ns / 1,400.21 allocations`;
- the first two complete `smoke` acquisitions failed only `maxCpuAveragePercent`: `28.425%` and `32.046%` against the `20%` ceiling. Thread-level attribution identified perpetual FPS Choreographer callbacks on a static Activity as the dominant observer load;
- after API 24+ FPS collection switched to event-driven FrameMetrics with Choreographer only as registration/disable fallback, two complete runs of the same APK SHA-256 passed every unchanged smoke budget at `12.928%` and `12.362%` CPU. No 24-hour, 72-hour, or long-profile power result exists.

MIUI still rejects the Gradle/UTP session-based test-APK install with `INSTALL_FAILED_USER_RESTRICTED`, although direct installation of the exact built test APK succeeds. Record this as an OEM installer-path failure: a direct runner success must not be reported as a successful `verifyReleasePerformanceBudgets` Gradle aggregate task.

A second physical preflight on a OnePlus `PLK110` (Android 16) exposed an OEM policy that denies `pm clear` to the ADB shell. The runner now falls back only for the exact `SecurityException` plus `CLEAR_APP_USER_DATA` denial: it uninstalls and reinstalls only the selected sample APK, records `appDataResetStrategy=uninstall-reinstall`, and keeps all unrelated ADB failures fail-closed. The resulting schema-v2 smoke artifact passed the unchanged budgets at `6.161%` enabled CPU and produced app-UID power evidence at `29.062 mAh/hour`. This proves power acquisition readiness on that device, not 24-hour acceptance.

## Microbenchmark release gate

Use a physical, unlocked Android device on stable power and temperature:

```powershell
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
./gradlew.bat :apm-benchmark:verifyReleasePerformanceBudgets --no-daemon
```

`benchmark-budgets.json` now requires all five methods and fails closed on missing/malformed metrics, emulator evidence, or an exceeded median time/allocation ceiling. The two Dispatcher methods compile on the current source but have no accepted physical-device result yet; the historical three-method JSON therefore cannot pass the current five-method gate.

| Hot path | Median time ceiling | Median allocation ceiling | Operation count |
|---|---:|---:|---:|
| durable encode | 30 µs | 48 | 1 event |
| durable decode | 60 µs | 72 | 1 event |
| SQLite append batch | 8 ms total / 250 µs per event | 2,048 total / 64 per event | 32 events |
| accepted dispatcher admission | 32 ms total / 1 ms per emit | 2,048 total / 64 per emit | 32 emits |
| HIGH admission into full queue | 8 ms | 256 | 1 emit + 1 LOW eviction |

For parser wiring, use the deterministic host tests. `--allow-emulator` remains an explicit parser-only option, but an emulator result must contain all five current methods:

```powershell
python -m unittest discover -s apm-benchmark/tests -p "test_*.py"
```

`DispatcherAdmissionBenchmark` initializes the public SDK path with a worker parked in an interruptible test uploader. Setup and consent-revocation cleanup run with measurement disabled, so the accepted benchmark measures 32 caller-side emissions and the pressure benchmark measures only the emergency HIGH emission into an already full queue. The production eviction path performs fixed LOW→NORMAL→HIGH FIFO passes and allocates only the proven victim list; it no longer materializes and sorts every lower-priority candidate. The default aggregation-disabled worker path also processes a scalar event directly instead of allocating `listOf(event)` per item. The new ceilings are regression guards, not accepted performance claims, until a managed physical runner produces complete five-method JSON.

## End-to-end physical-device campaign

The sample Activity accepts host-only intent extras before it touches interactive demo resources. The runner first starts an SDK-disabled control process, then starts SDK-enabled processes with a custom uploader that always returns failure. This exercises the real dispatcher, SQLite outbox, retry, disk bound, and restart path while leaving the device's network configuration unchanged.

Each enabled process performs the same bounded map construction at 10 operations/second on the main thread and calls `Apm.emit`; control mode retains the map work but skips the SDK. A primitive 4,096-entry rolling reservoir reports emit P50/P95/max without allocating one report object per operation. The host independently samples `/proc`, `dumpsys meminfo`, `run-as du`, thermal service, charge counter, and cumulative app-UID `batterystats` power. It prefers the package-scoped human-readable UID value and falls back to the exact UID `pwi,uid` row in Android's current checkin form when an OEM filters the readable list. A cumulative value must increase strictly across the enabled campaign; flat zeroes, resets, regressions, malformed values, and a different UID remain missing evidence.

`run_device_soak.py` requires `--reset-app-data`. That explicit flag clears only the selected sample package before acquisition, preventing an old outbox from contaminating disk and restart evidence. It first uses `pm clear`; if and only if the OEM returns a `SecurityException` for `android.permission.CLEAR_APP_USER_DATA`, it uninstalls and reinstalls the same selected APK. The artifact records `appDataResetStrategy` as `pm-clear` or `uninstall-reinstall`. It does not clear any other app, does not alter device networking, and does not turn disconnects or unrelated ADB failures into a reinstall.

Read-only evidence commands (`getprop`, `pidof`, `/proc`, `dumpsys`, `run-as du/cat`, package UID and `get-state`) may replay at one-second intervals when ADB reports only `device offline`, `device '<serial>' not found`, or `no devices/emulators found`. The default reconnect window is 30 seconds for smoke and 300 seconds for 24h/72h, with a 600-second absolute CLI ceiling. Install, uninstall, `pm clear`, Activity start and force-stop commands never replay automatically. Exhausted read-only transport loss raises even for optional smoke power fields, and each accepted artifact records `transientAdbRetryCount` plus `adbReconnectTimeoutSeconds`; the verifier accepts only non-negative retry counts, known reset strategies, and a timeout in `1..600`.

The first background OnePlus 24h attempt from commit `97cdc90` ended during its first hour because the device remained absent beyond the then-common 30-second window. The host failure log was retained, no result JSON was written, and no long-duration claim was made. That real failure motivated the profile-aware 300-second long window. The Redmi `22041216UC` then passed a fresh current-code smoke at `11.319%` CPU with `window=30s`, retry count `0`, and `pm-clear`. On 2026-07-24 a five-minute diagnostic proved that MIUI exposes the exact UID `pwi` checkin row but keeps its computed power at zero. On 2026-07-25 the active Redmi 24h retry was explicitly cancelled before completion because a personal phone is not required to remain attached for the current client-SDK iteration. The runner and sample process were stopped and no result JSON exists, so the cancellation is neither a pass nor a gate failure. The unchanged 24h/72h profiles are retained for pre-production execution on managed device-lab hardware or calibrated power infrastructure.

Result schema version 2 made CPU semantics explicit:

- `cpuAveragePercent` remains the authoritative enabled-process absolute CPU field used by the unchanged budget gate;
- `cpuControlPercent` is recomputed from the raw control segment jiffies and elapsed time;
- `cpuEnabledAveragePercent` is the wall-time-weighted enabled-process CPU and must equal `cpuAveragePercent`;
- `cpuDeltaPercent` is the signed diagnostic difference `enabled - control`.

Current result schema version 3 retains those CPU fields and additionally binds
an acquisition to the exact APK SHA-256, clean 40-character source revision,
matrix/budget/runner SHA-256 values, selected lane, UTC timestamp, serial,
manufacturer, model, device codename, fingerprint, API level, and primary ABI.
The runner refuses a dirty Git worktree before it creates an ADB client or
mutates a device. The verifier independently recomputes control and enabled CPU
from raw samples and rejects mismatched summary fields. Result schema versions 1
and 2 remain readable for historical single-artifact inspection, but only
current schema version 3 can satisfy the aggregate matrix gate. The CPU delta is
attribution evidence only: it does not replace or relax
`maxCpuAveragePercent`, and one pre-campaign control segment is not a paired
causal estimate across a 24h/72h run.

```powershell
./gradlew.bat :apm-sample-app:assembleDebug --no-daemon

python apm-benchmark/run_device_soak.py `
  --profile smoke `
  --lane-id legacy-api24-28 `
  --serial <physical-adb-serial> `
  --apk apm-sample-app/build/outputs/apk/debug/apm-sample-app-debug.apk `
  --output apm-benchmark/build/device-soak/smoke.json `
  --reset-app-data

python apm-benchmark/verify_device_soak.py `
  --budgets apm-benchmark/device-soak-budgets.json `
  --results apm-benchmark/build/device-soak/smoke.json `
  --profile smoke
```

Before reserving physical devices, validate the checked-in plan:

```powershell
python apm-benchmark/verify_device_matrix.py `
  --matrix apm-benchmark/device-lab-matrix.json `
  --budgets apm-benchmark/device-soak-budgets.json

./gradlew.bat :apm-benchmark:verifyDeviceLabMatrix --no-daemon
```

This is a schema/policy check only. Its success explicitly says that no physical
evidence was evaluated.

The equivalent Gradle artifact gate is:

```powershell
./gradlew.bat :apm-benchmark:verifyDeviceSoakFromResults `
  -PapmDeviceSoakResults=apm-benchmark/build/device-soak/smoke.json `
  -PapmDeviceSoakProfile=smoke `
  --no-daemon
```

### Profiles and evidence

| Profile | Enabled elapsed time | Default restarts | Power evidence |
|---|---:|---:|---|
| `smoke` | 30 s; gate minimum 20 s | every 15 s; at least 2 starts | optional |
| `24h` | 86,400 s | every hour; at least 24 starts | required |
| `72h` | 259,200 s | every 6 hours; at least 12 starts | required |

Use the same runner command with `--profile 24h` or `--profile 72h`. The verifier requires actual accumulated enabled-process duration; a short run renamed to a long profile fails. Long profiles also fail when neither app-UID `batterystats` delta nor calibrated external-meter evidence is present. An external meter may be supplied as app-attributed enabled-phase consumption with `--external-power-mah <mAh>`; preserve the meter's raw artifact and calibration record beside the JSON.

### Managed device-lab matrix

| Lane | API range | Required profiles |
|---|---:|---|
| `legacy-api24-28` | 24–28 | `smoke` |
| `mainstream-api29-33` | 29–33 | `smoke`, `24h` |
| `modern-api34-36` | 34–36 | `smoke`, `24h`, `72h` |

All lanes require a physical `arm64-v8a` device. Complete aggregate acceptance
requires three distinct devices from three manufacturers for `smoke`, two
devices from two manufacturers for `24h`, and one device for `72h`. Smoke
evidence must collectively cover both `pm-clear` and `uninstall-reinstall`.
Every lane/profile pair is mandatory, and all artifacts must identify one exact
APK and one exact source revision.

After collecting the six explicit lane/profile artifacts from the exact source
checkout that produced them, verify the complete set:

```powershell
python apm-benchmark/verify_device_matrix.py `
  --matrix apm-benchmark/device-lab-matrix.json `
  --budgets apm-benchmark/device-soak-budgets.json `
  --results <legacy-smoke.json> <mainstream-smoke.json> <mainstream-24h.json> `
            <modern-smoke.json> <modern-24h.json> <modern-72h.json>

./gradlew.bat :apm-benchmark:verifyDeviceLabCoverageFromResults `
  -PapmDeviceLabResults=<comma-separated-result-paths> `
  --no-daemon
```

The aggregate gate first applies the per-profile budgets, then checks current
schema, exact matrix/budget/runner hashes, lane matching, lane/profile
completeness, OEM/device/reset diversity, and single-APK/single-source
consistency. Missing, duplicate, stale, mixed-build, emulator, or over-budget
evidence fails closed.

Checked-in ceilings in `device-soak-budgets.json` cover:

- enabled-minus-control cold-start delta and maximum `Apm.init` time;
- main-thread synthetic-operation P95;
- enabled-process absolute CPU and maximum PSS growth across restarts; schema-v2/v3 control/delta CPU remains diagnostic;
- app-private database/files/cache growth during collector outage;
- app-attributed mAh/hour and maximum Android thermal status.

The 70 MiB disk ceiling is intentionally just above the SDK's 64 MiB live-payload outbox budget to allow SQLite/WAL/page overhead while still detecting unbounded growth. These values are regression ceilings, not performance promises for every OEM.

Run all deterministic host tests with:

```powershell
python -m unittest discover -s apm-benchmark/tests -p "test_*.py"
```

The current host suite contains 41 deterministic tests. An accepted result must
retain the runner JSON, exact APK SHA-256, clean source revision, matrix/budget/
runner hashes, lane, device serial/manufacturer/model/codename/fingerprint/API/
ABI, raw process samples, profile, acquisition command, external meter evidence
when used, and verifier output. A device-policy installation rejection is a
blocked validation result, not a passing or failing SDK budget. No schema-v3
24h or 72h result is currently claimed.
