# AndroidAPM performance and device-soak gates

`apm-benchmark` is a non-published verification module with two independent layers:

1. AndroidX Microbenchmark measures durable codec and the 32-event SQLite transaction.
2. The host-driven sample-App campaign measures cold start, SDK init, main-thread emit, CPU, PSS, app-private disk, app UID power attribution, thermal status, collector outage, and process restart recovery.

Build-only or host-unit-test success does not produce physical-device performance evidence.

## Current physical-device status

The `2026-07-23` run on a physical Redmi/Xiaomi `22041216UC` (Android 13) produced two distinct results:

- the declared `AndroidBenchmarkRunner` completed all three microbenchmarks without suppressed AndroidX errors, and the checked-in verifier accepted encode at `4,640.93 ns / 22.00 allocations`, decode at `4,841.81 ns / 46.00 allocations`, and the 32-event SQLite transaction at `1,258,990.52 ns / 1,400.21 allocations`;
- the first two complete `smoke` acquisitions failed only `maxCpuAveragePercent`: `28.425%` and `32.046%` against the `20%` ceiling. Thread-level attribution identified perpetual FPS Choreographer callbacks on a static Activity as the dominant observer load;
- after API 24+ FPS collection switched to event-driven FrameMetrics with Choreographer only as registration/disable fallback, two complete runs of the same APK SHA-256 passed every unchanged smoke budget at `12.928%` and `12.362%` CPU. No 24-hour, 72-hour, or long-profile power result exists.

MIUI still rejects the Gradle/UTP session-based test-APK install with `INSTALL_FAILED_USER_RESTRICTED`, although direct installation of the exact built test APK succeeds. Record this as an OEM installer-path failure: a direct runner success must not be reported as a successful `verifyReleasePerformanceBudgets` Gradle aggregate task.

## Microbenchmark release gate

Use a physical, unlocked Android device on stable power and temperature:

```powershell
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
./gradlew.bat :apm-benchmark:verifyReleasePerformanceBudgets --no-daemon
```

`benchmark-budgets.json` requires all three methods and fails closed on missing/malformed metrics, emulator evidence, or an exceeded median time/allocation ceiling.

| Hot path | Median time ceiling | Median allocation ceiling | Operation count |
|---|---:|---:|---:|
| durable encode | 30 µs | 48 | 1 event |
| durable decode | 60 µs | 72 | 1 event |
| SQLite append batch | 8 ms total / 250 µs per event | 2,048 total / 64 per event | 32 events |

For parser wiring only, existing emulator JSON can be checked explicitly without producing release evidence:

```powershell
python apm-benchmark/verify_benchmark_budgets.py --budgets apm-benchmark/benchmark-budgets.json --results apm-benchmark/build/outputs/connected_android_test_additional_output/releaseAndroidTest/connected --allow-emulator
```

## End-to-end physical-device campaign

The sample Activity accepts host-only intent extras before it touches interactive demo resources. The runner first starts an SDK-disabled control process, then starts SDK-enabled processes with a custom uploader that always returns failure. This exercises the real dispatcher, SQLite outbox, retry, disk bound, and restart path while leaving the device's network configuration unchanged.

Each enabled process performs the same bounded map construction at 10 operations/second on the main thread and calls `Apm.emit`; control mode retains the map work but skips the SDK. A primitive 4,096-entry rolling reservoir reports emit P50/P95/max without allocating one report object per operation. The host independently samples `/proc`, `dumpsys meminfo`, `run-as du`, thermal service, charge counter, and cumulative app-UID `batterystats` power.

`run_device_soak.py` requires `--reset-app-data`. That explicit flag clears only the selected sample package before acquisition, preventing an old outbox from contaminating disk and restart evidence. It does not clear any other app and does not alter device networking.

```powershell
./gradlew.bat :apm-sample-app:assembleDebug --no-daemon

python apm-benchmark/run_device_soak.py `
  --profile smoke `
  --serial <physical-adb-serial> `
  --apk apm-sample-app/build/outputs/apk/debug/apm-sample-app-debug.apk `
  --output apm-benchmark/build/device-soak/smoke.json `
  --reset-app-data

python apm-benchmark/verify_device_soak.py `
  --budgets apm-benchmark/device-soak-budgets.json `
  --results apm-benchmark/build/device-soak/smoke.json `
  --profile smoke
```

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

Checked-in ceilings in `device-soak-budgets.json` cover:

- enabled-minus-control cold-start delta and maximum `Apm.init` time;
- main-thread synthetic-operation P95;
- process CPU and maximum PSS growth across restarts;
- app-private database/files/cache growth during collector outage;
- app-attributed mAh/hour and maximum Android thermal status.

The 70 MiB disk ceiling is intentionally just above the SDK's 64 MiB live-payload outbox budget to allow SQLite/WAL/page overhead while still detecting unbounded growth. These values are regression ceilings, not performance promises for every OEM.

Run all deterministic host tests with:

```powershell
python -m unittest discover -s apm-benchmark/tests -p "test_*.py"
```

An accepted result must retain the runner JSON, exact APK SHA-256, device model/fingerprint/API, raw process samples, profile, acquisition command, external meter evidence when used, and verifier output. A device-policy installation rejection is a blocked validation result, not a passing or failing SDK budget.
