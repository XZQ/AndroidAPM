"""Deterministic tests for device-soak parsing and aggregation."""

from __future__ import annotations

import importlib.util
import io
import subprocess
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import Mock, patch


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

    def test_streamed_install_requires_a_standalone_success_line(self) -> None:
        """Standard multi-line install output passes without accepting fuzzy text."""
        adb = Mock()
        adb.run.return_value = "Performing Streamed Install\nSuccess\n"

        RUNNER._install_apk(adb, Path("sample.apk"), replace=True)

        adb.run.assert_called_once_with(
            "install",
            "-r",
            "sample.apk",
            timeout=180,
        )
        self.assertFalse(RUNNER._reports_success("Not Success"))

    @patch.object(RUNNER.time, "sleep")
    @patch.object(RUNNER.subprocess, "run")
    def test_read_only_adb_command_retries_transient_disconnect(
        self,
        run_command: Mock,
        sleep: Mock,
    ) -> None:
        """A read-only command may replay after a bounded transport reconnect."""
        run_command.side_effect = [
            subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="",
                stderr="adb.exe: device 'serial' not found",
            ),
            subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="123\n",
                stderr="",
            ),
        ]
        adb = RUNNER.Adb("adb", "serial")

        output = adb.read_shell("pidof", "com.apm.sample.debug")

        self.assertEqual("123\n", output)
        self.assertEqual(1, adb.transient_retry_count)
        self.assertEqual(2, run_command.call_count)
        sleep.assert_called_once_with(RUNNER.TRANSIENT_ADB_RETRY_INTERVAL_SECONDS)

    @patch.object(RUNNER.time, "sleep")
    @patch.object(RUNNER.subprocess, "run")
    def test_mutating_adb_command_never_retries(
        self,
        run_command: Mock,
        sleep: Mock,
    ) -> None:
        """Commands with side effects fail immediately instead of being replayed."""
        run_command.side_effect = [
            subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="",
                stderr="adb.exe: device 'serial' not found",
            ),
            subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="Success\n",
                stderr="",
            ),
        ]
        adb = RUNNER.Adb("adb", "serial")

        with self.assertRaisesRegex(RUNNER.DeviceSoakRunError, "not found"):
            adb.shell("am", "force-stop", "com.apm.sample.debug")

        self.assertEqual(1, run_command.call_count)
        self.assertEqual(0, adb.transient_retry_count)
        sleep.assert_not_called()

    @patch.object(RUNNER.time, "sleep")
    @patch.object(RUNNER.subprocess, "run")
    def test_read_only_retry_exhaustion_fails_even_when_command_is_optional(
        self,
        run_command: Mock,
        sleep: Mock,
    ) -> None:
        """A persistent transport loss cannot degrade into missing optional evidence."""
        run_command.return_value = subprocess.CompletedProcess(
            args=[],
            returncode=1,
            stdout="",
            stderr="error: device offline",
        )
        adb = RUNNER.Adb("adb", "serial", reconnect_timeout_seconds=1)

        with self.assertRaisesRegex(RUNNER.DeviceSoakRunError, "device offline"):
            adb.read_shell("dumpsys", "battery", check=False)

        self.assertEqual(2, run_command.call_count)
        self.assertEqual(1, adb.transient_retry_count)
        sleep.assert_called_once_with(RUNNER.TRANSIENT_ADB_RETRY_INTERVAL_SECONDS)

    def test_reconnect_timeout_is_profile_aware_and_absolutely_bounded(self) -> None:
        """Long profiles tolerate minutes, while CLI overrides retain a hard ceiling."""
        smoke = RUNNER.parse_args(
            [
                "--profile",
                "smoke",
                "--serial",
                "serial",
                "--apk",
                "sample.apk",
                "--output",
                "result.json",
                "--reset-app-data",
            ]
        )
        long_run = RUNNER.parse_args(
            [
                "--profile",
                "24h",
                "--serial",
                "serial",
                "--apk",
                "sample.apk",
                "--output",
                "result.json",
                "--reset-app-data",
            ]
        )

        self.assertIsNone(smoke.adb_reconnect_timeout_seconds)
        self.assertIsNone(long_run.adb_reconnect_timeout_seconds)
        self.assertEqual(
            30,
            RUNNER.PROFILE_DEFAULTS["smoke"]["adbReconnectTimeoutSeconds"],
        )
        self.assertEqual(
            300,
            RUNNER.PROFILE_DEFAULTS["24h"]["adbReconnectTimeoutSeconds"],
        )
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                RUNNER.parse_args(
                    [
                        "--profile",
                        "24h",
                        "--serial",
                        "serial",
                        "--apk",
                        "sample.apk",
                        "--output",
                        "result.json",
                        "--reset-app-data",
                        "--adb-reconnect-timeout-seconds",
                        "601",
                    ]
                )

    def test_app_data_reset_prefers_package_manager_clear(self) -> None:
        """A supported device clears only the selected package without reinstalling."""
        adb = Mock()
        adb.shell.return_value = "Success\n"

        strategy = RUNNER._reset_sample_app_data(
            adb,
            "com.apm.sample.debug",
            Path("sample.apk"),
        )

        self.assertEqual("pm-clear", strategy)
        adb.shell.assert_called_once_with(
            "pm",
            "clear",
            "com.apm.sample.debug",
            timeout=60,
        )
        adb.run.assert_not_called()

    def test_app_data_reset_reinstalls_after_oem_permission_denial(self) -> None:
        """A CLEAR_APP_USER_DATA policy denial falls back to the same sample APK."""
        adb = Mock()
        adb.shell.side_effect = RUNNER.DeviceSoakRunError(
            "java.lang.SecurityException: shell does not have permission "
            "android.permission.CLEAR_APP_USER_DATA"
        )
        adb.run.side_effect = ["Success\n", "Success\n"]

        strategy = RUNNER._reset_sample_app_data(
            adb,
            "com.apm.sample.debug",
            Path("sample.apk"),
        )

        self.assertEqual("uninstall-reinstall", strategy)
        self.assertEqual(
            [
                unittest.mock.call(
                    "uninstall",
                    "com.apm.sample.debug",
                    timeout=180,
                ),
                unittest.mock.call(
                    "install",
                    "sample.apk",
                    timeout=180,
                ),
            ],
            adb.run.call_args_list,
        )

    def test_app_data_reset_does_not_mask_unrelated_adb_failures(self) -> None:
        """Disconnects and other ADB failures remain fail-closed."""
        adb = Mock()
        adb.shell.side_effect = RUNNER.DeviceSoakRunError("device offline")

        with self.assertRaisesRegex(RUNNER.DeviceSoakRunError, "device offline"):
            RUNNER._reset_sample_app_data(
                adb,
                "com.apm.sample.debug",
                Path("sample.apk"),
            )

        adb.run.assert_not_called()

    def test_summary_uses_weighted_cpu_and_uid_power_delta(self) -> None:
        """Aggregation spans process restarts without averaging percentages incorrectly."""
        control = self._segment(
            startup_ms=100,
            duration_ms=5_000,
            operation_p95_ns=0,
            init_ns=0,
            first_jiffies=50,
            last_jiffies=75,
            wall_seconds=5,
            first_pss=9_000,
            last_pss=9_500,
            first_disk=500,
            last_disk=500,
            first_power=0.9,
            last_power=1.0,
        )
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
        self.assertAlmostEqual(5.0, summary["cpuControlPercent"])
        self.assertAlmostEqual(100.0 / 15.0, summary["cpuEnabledAveragePercent"])
        self.assertAlmostEqual(100.0 / 15.0 - 5.0, summary["cpuDeltaPercent"])
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
