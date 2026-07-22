# AndroidAPM device benchmarks

This module is a non-published AndroidX Microbenchmark harness and release-budget gate for client-side overhead. It measures the versioned event codec and the production 32-event SQLite outbox transaction. It is intentionally excluded from Maven publication.

Use a physical, unlocked Android device on stable power and temperature. Build-only verification does not produce performance claims:

```powershell
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
./gradlew.bat :apm-benchmark:connectedReleaseAndroidTest
```

AndroidX writes measurement JSON and traces under `apm-benchmark/build/outputs/connected_android_test_additional_output/`. Record device model, OS/build fingerprint, battery level, thermal state, charging state, iteration count, median, P90, allocation count, and output artifact hash alongside every accepted run.

## Release budgets

`benchmark-budgets.json` is the checked-in release contract. The gate requires all three benchmark methods, compares AndroidX median time and allocation count, and fails closed on missing/malformed metrics or any exceeded ceiling.

| Hot path | Median time ceiling | Median allocation ceiling | Operation count |
|---|---:|---:|---:|
| durable encode | 30 µs | 48 | 1 event |
| durable decode | 60 µs | 72 | 1 event |
| SQLite append batch | 8 ms total / 250 µs per event | 2,048 total / 64 per event | 32 events |

Run the end-to-end gate on a dedicated physical-device CI runner:

```powershell
./gradlew.bat :apm-benchmark:verifyReleasePerformanceBudgets --no-daemon
```

The Gradle gate deliberately rejects emulator fingerprints and AndroidX `EMULATOR_` results. For parser wiring only, existing emulator JSON can be checked explicitly without producing release evidence:

```powershell
python apm-benchmark/verify_benchmark_budgets.py --budgets apm-benchmark/benchmark-budgets.json --results apm-benchmark/build/outputs/connected_android_test_additional_output/releaseAndroidTest/connected --allow-emulator
python -m unittest discover -s apm-benchmark/tests -p "test_*.py"
```

Long-stability, battery, thermal, and disk-growth campaigns require the device matrix and acceptance process defined in `docs/云端待建设清单.md`; the repository supplies the repeatable client harness but does not fabricate physical-device results.
