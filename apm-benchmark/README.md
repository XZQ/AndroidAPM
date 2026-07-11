# AndroidAPM device benchmarks

This module is a non-published AndroidX Microbenchmark harness for client-side overhead. It measures the versioned event codec and the production 32-event SQLite outbox transaction. It is intentionally excluded from Maven publication.

Use a physical, unlocked Android device on stable power and temperature. Build-only verification does not produce performance claims:

```powershell
./gradlew.bat :apm-benchmark:assembleRelease :apm-benchmark:compileReleaseAndroidTestKotlin
./gradlew.bat :apm-benchmark:connectedReleaseAndroidTest
```

AndroidX writes measurement JSON and traces under `apm-benchmark/build/outputs/connected_android_test_additional_output/`. Record device model, OS/build fingerprint, battery level, thermal state, charging state, iteration count, median, P90, allocation count, and output artifact hash alongside every accepted run.

Long-stability, battery, thermal, and disk-growth campaigns require the device matrix and acceptance process defined in `docs/云端待建设清单.md`; the repository supplies the repeatable client harness but does not fabricate physical-device results.
