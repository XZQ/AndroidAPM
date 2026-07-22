"""Deterministic tests for device-soak parsing and aggregation."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "run_device_soak.py"
SPEC = importlib.util.spec_from_file_location("run_device_soak", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
RUNNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RUNNER
SPEC.loader.exec_module(RUNNER)


class DeviceSoakRunnerTest(unittest.TestCase):
    """Protects host parsers and weighted resource summary semantics."""

    def test_activity_manager_total_time_is_required(self) -> None:
        """Cold-start parsing accepts TotalTime and rejects incomplete output."""
        self.assertEqual(123.0, RUNNER._parse_startup_ms("Status: ok\nTotalTime: 123\n"))
        with self.assertRaises(RUNNER.DeviceSoakRunError):
            RUNNER._parse_startup_ms("Status: ok\n")

    def test_android_uid_label_matches_batterystats_identity(self) -> None:
        """Application UIDs map to the display label used by dumpsys batterystats."""
        self.assertEqual("u0a234", RUNNER._uid_label(10234))
        self.assertEqual("u10a234", RUNNER._uid_label(1010234))

    def test_summary_uses_weighted_cpu_and_uid_power_delta(self) -> None:
        """Aggregation spans process restarts without averaging percentages incorrectly."""
        control = {"startupMs": 100.0}
        segments = [
            self._segment(
                startup_ms=120,
                duration_ms=10_000,
                operation_p95_ns=500_000,
                init_ns=20_000_000,
                first_jiffies=100,
                last_jiffies=200,
                wall_seconds=10,
                first_pss=10_000,
                last_pss=12_000,
                first_disk=1_000,
                last_disk=2_000,
                first_power=1.0,
                last_power=1.2,
            ),
            self._segment(
                startup_ms=140,
                duration_ms=20_000,
                operation_p95_ns=750_000,
                init_ns=30_000_000,
                first_jiffies=200,
                last_jiffies=300,
                wall_seconds=20,
                first_pss=11_000,
                last_pss=13_000,
                first_disk=2_000,
                last_disk=4_000,
                first_power=1.2,
                last_power=1.5,
            ),
        ]

        summary = RUNNER._summarize(
            control,
            segments,
            external_power_mah=None,
            clock_ticks_per_second=100,
        )

        self.assertAlmostEqual(100.0 / 15.0, summary["cpuAveragePercent"])
        self.assertEqual(3_000, summary["pssGrowthKb"])
        self.assertEqual(3_000, summary["diskGrowthBytes"])
        self.assertEqual(30.0, summary["sdkInitMaxMs"])
        self.assertEqual(750.0, summary["mainThreadOperationP95Us"])
        self.assertEqual("android-batterystats-uid", summary["powerSource"])
        self.assertAlmostEqual(60.0, summary["chargeConsumptionMahPerHour"])

    @staticmethod
    def _segment(
        *,
        startup_ms: float,
        duration_ms: int,
        operation_p95_ns: int,
        init_ns: int,
        first_jiffies: int,
        last_jiffies: int,
        wall_seconds: int,
        first_pss: int,
        last_pss: int,
        first_disk: int,
        last_disk: int,
        first_power: float,
        last_power: float,
    ) -> dict:
        """Build one runner-compatible raw segment fixture."""
        return {
            "startupMs": startup_ms,
            "probe": {
                "observedDurationMs": duration_ms,
                "operationP95Ns": operation_p95_ns,
                "initDurationNs": init_ns,
            },
            "samples": [
                {
                    "elapsed_seconds": 0.0,
                    "cpu_jiffies": first_jiffies,
                    "pss_kb": first_pss,
                    "disk_bytes": first_disk,
                    "thermal_status": 1,
                    "charge_counter_uah": 2_000_000,
                    "uid_power_mah": first_power,
                },
                {
                    "elapsed_seconds": float(wall_seconds),
                    "cpu_jiffies": last_jiffies,
                    "pss_kb": last_pss,
                    "disk_bytes": last_disk,
                    "thermal_status": 2,
                    "charge_counter_uah": 1_999_000,
                    "uid_power_mah": last_power,
                },
            ],
        }


if __name__ == "__main__":
    unittest.main()
