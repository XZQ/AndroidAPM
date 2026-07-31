"""Deterministic tests for the managed physical-device matrix gate."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


BENCHMARK_DIR = Path(__file__).resolve().parents[1]
MODULE_PATH = BENCHMARK_DIR / "verify_device_matrix.py"
SPEC = importlib.util.spec_from_file_location("verify_device_matrix", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)


class DeviceMatrixVerifierTest(unittest.TestCase):
    """Covers plan validation, lane matching, provenance, and aggregate coverage."""

    def setUp(self) -> None:
        """Create a small complete matrix whose results verify without wall-clock waits."""
        self.temp_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_directory.name)
        self.matrix_path = self.root / "matrix.json"
        self.budgets_path = self.root / "budgets.json"
        self.matrix = {
            "schemaVersion": 1,
            "lanes": [
                self._lane("legacy", 24, 28, ["smoke"]),
                self._lane("mainstream", 29, 33, ["smoke", "24h"]),
                self._lane("modern", 34, 36, ["smoke", "24h", "72h"]),
            ],
            "profileCoverage": {
                "smoke": {
                    "minDistinctDevices": 3,
                    "minDistinctManufacturers": 3,
                    "requiredResetStrategies": [
                        "pm-clear",
                        "uninstall-reinstall",
                    ],
                },
                "24h": {
                    "minDistinctDevices": 2,
                    "minDistinctManufacturers": 2,
                    "requiredResetStrategies": [],
                },
                "72h": {
                    "minDistinctDevices": 1,
                    "minDistinctManufacturers": 1,
                    "requiredResetStrategies": [],
                },
            },
        }
        profile = {
            "minObservedDurationSeconds": 10,
            "minProcessStarts": 1,
            "requirePhysicalDevice": True,
            "requireOfflineCollector": True,
            "requirePowerEvidence": False,
            "maxStartupDeltaMs": 250,
            "maxSdkInitMs": 200,
            "maxMainThreadOperationP95Us": 2000,
            "maxCpuAveragePercent": 20,
            "maxPssGrowthKb": 65536,
            "maxDiskGrowthBytes": 73400320,
            "maxChargeConsumptionMahPerHour": 300,
            "maxThermalStatus": 3,
        }
        self.budgets = {
            "schemaVersion": 1,
            "profiles": {
                "smoke": dict(profile),
                "24h": dict(profile),
                "72h": dict(profile),
            },
        }
        self._write_inputs()

    def tearDown(self) -> None:
        """Remove temporary matrix inputs and result artifacts."""
        self.temp_directory.cleanup()

    @staticmethod
    def _lane(
        lane_id: str,
        api_min: int,
        api_max: int,
        profiles: list[str],
    ) -> dict:
        """Build one compact wildcard-manufacturer ARM64 lane."""
        return {
            "id": lane_id,
            "description": f"{lane_id} test lane",
            "apiLevelMin": api_min,
            "apiLevelMax": api_max,
            "manufacturers": ["*"],
            "primaryAbis": ["arm64-v8a"],
            "profiles": profiles,
            "allowedResetStrategies": [
                "pm-clear",
                "uninstall-reinstall",
            ],
        }

    def _write_inputs(self) -> None:
        self.matrix_path.write_text(json.dumps(self.matrix), encoding="utf-8")
        self.budgets_path.write_text(json.dumps(self.budgets), encoding="utf-8")

    def _result(
        self,
        *,
        lane_id: str,
        profile: str,
        serial: str,
        manufacturer: str,
        api_level: int,
        reset_strategy: str,
        generated_index: int,
    ) -> dict:
        """Build one current-schema physical result below every test budget."""
        return {
            "schemaVersion": 3,
            "profile": profile,
            "generatedAtUtc": f"2026-07-31T00:00:{generated_index:02d}+00:00",
            "apkSha256": "a" * 64,
            "device": {
                "serial": serial,
                "manufacturer": manufacturer,
                "model": f"{manufacturer} Phone",
                "device": f"{serial}-device",
                "fingerprint": f"{manufacturer}/{serial}/release",
                "primaryAbi": "arm64-v8a",
                "apiLevel": api_level,
                "isEmulator": False,
            },
            "provenance": {
                "matrixSchemaVersion": 1,
                "matrixSha256": VERIFIER.file_sha256(self.matrix_path),
                "budgetsSha256": VERIFIER.file_sha256(self.budgets_path),
                "runnerSha256": VERIFIER.file_sha256(VERIFIER.RUNNER_PATH),
                "laneId": lane_id,
                "sourceRevision": "b" * 40,
                "sourceDirty": False,
            },
            "config": {
                "offlineCollector": True,
                "clockTicksPerSecond": 100,
                "appDataResetStrategy": reset_strategy,
                "transientAdbRetryCount": 0,
                "adbReconnectTimeoutSeconds": 30,
            },
            "control": {
                "probe": {"sdkEnabled": False},
                "samples": [
                    {"elapsed_seconds": 0, "cpu_jiffies": 100},
                    {"elapsed_seconds": 10, "cpu_jiffies": 110},
                ],
            },
            "segments": [
                {
                    "runId": f"{profile}-1",
                    "probe": {
                        "runId": f"{profile}-1",
                        "sdkEnabled": True,
                        "offlineCollector": True,
                        "observedDurationMs": 10_000,
                    },
                    "samples": [
                        {"elapsed_seconds": 0, "cpu_jiffies": 100},
                        {"elapsed_seconds": 10, "cpu_jiffies": 150},
                    ],
                }
            ],
            "summary": {
                "observedDurationSeconds": 10,
                "processStarts": 1,
                "startupDeltaMs": 10,
                "sdkInitMaxMs": 50,
                "mainThreadOperationP95Us": 500,
                "cpuAveragePercent": 5,
                "cpuControlPercent": 1,
                "cpuEnabledAveragePercent": 5,
                "cpuDeltaPercent": 4,
                "pssGrowthKb": 1024,
                "diskGrowthBytes": 4096,
                "chargeConsumptionMahPerHour": None,
                "powerSource": None,
                "thermalMaxStatus": 1,
            },
        }

    def _complete_results(self) -> list[Path]:
        """Write every lane/profile artifact plus the required OEM/reset diversity."""
        definitions = [
            ("legacy", "smoke", "serial-a", "VendorA", 26, "pm-clear"),
            ("mainstream", "smoke", "serial-b", "VendorB", 31, "uninstall-reinstall"),
            ("mainstream", "24h", "serial-b", "VendorB", 31, "uninstall-reinstall"),
            ("modern", "smoke", "serial-c", "VendorC", 35, "pm-clear"),
            ("modern", "24h", "serial-c", "VendorC", 35, "pm-clear"),
            ("modern", "72h", "serial-c", "VendorC", 35, "pm-clear"),
        ]
        paths: list[Path] = []
        for index, definition in enumerate(definitions):
            result = self._result(
                lane_id=definition[0],
                profile=definition[1],
                serial=definition[2],
                manufacturer=definition[3],
                api_level=definition[4],
                reset_strategy=definition[5],
                generated_index=index,
            )
            path = self.root / f"result-{index}.json"
            path.write_text(json.dumps(result), encoding="utf-8")
            paths.append(path)
        return paths

    def test_checked_in_plan_is_valid_without_claiming_results(self) -> None:
        """The repository plan is internally coherent independent of device access."""
        matrix = VERIFIER.validate_plan(
            BENCHMARK_DIR / "device-lab-matrix.json",
            BENCHMARK_DIR / "device-soak-budgets.json",
        )

        self.assertEqual(3, len(matrix.lanes))
        self.assertEqual(6, sum(len(lane.profiles) for lane in matrix.lanes))

    def test_overlapping_profile_lanes_fail_closed(self) -> None:
        """A physical artifact must never ambiguously satisfy two lanes."""
        self.matrix["lanes"][1]["apiLevelMin"] = 28
        self._write_inputs()

        with self.assertRaisesRegex(
            VERIFIER.DeviceLabMatrixError,
            "overlapping API lanes",
        ):
            VERIFIER.validate_plan(self.matrix_path, self.budgets_path)

    def test_emulator_cannot_satisfy_a_physical_lane(self) -> None:
        """Lane matching rejects an emulator before acquisition or aggregation."""
        matrix = VERIFIER.validate_plan(self.matrix_path, self.budgets_path)
        device = {
            "isEmulator": True,
            "apiLevel": 26,
            "manufacturer": "VendorA",
            "primaryAbi": "arm64-v8a",
        }

        with self.assertRaisesRegex(
            VERIFIER.DeviceLabMatrixError,
            "isEmulator must be false",
        ):
            VERIFIER.require_device_lane(device, matrix.lane("legacy"), "smoke")

    def test_complete_matrix_with_exact_provenance_passes(self) -> None:
        """All lane/profile, OEM, reset, APK, and source constraints can pass."""
        messages = VERIFIER.verify_matrix_results(
            self.matrix_path,
            self.budgets_path,
            self._complete_results(),
        )

        self.assertTrue(any("smoke diversity devices=3" in message for message in messages))
        self.assertTrue(any("matrix APK=" in message for message in messages))

    def test_missing_lane_profile_evidence_fails(self) -> None:
        """Individually valid artifacts cannot pass an incomplete matrix."""
        paths = self._complete_results()

        with self.assertRaisesRegex(
            VERIFIER.DeviceMatrixVerificationError,
            "modern/72h",
        ):
            VERIFIER.verify_matrix_results(
                self.matrix_path,
                self.budgets_path,
                paths[:-1],
            )

    def test_stale_mixed_or_conflicting_identity_evidence_fails(self) -> None:
        """Aggregate acceptance binds exact inputs, one APK, and stable device identity."""
        paths = self._complete_results()
        stale = json.loads(paths[0].read_text(encoding="utf-8"))
        stale["provenance"]["matrixSha256"] = "c" * 64
        paths[0].write_text(json.dumps(stale), encoding="utf-8")
        with self.assertRaisesRegex(
            VERIFIER.DeviceMatrixVerificationError,
            "does not match current input",
        ):
            VERIFIER.verify_matrix_results(
                self.matrix_path,
                self.budgets_path,
                paths,
            )

        paths = self._complete_results()
        conflicting = json.loads(paths[2].read_text(encoding="utf-8"))
        conflicting["device"]["model"] = "Different Phone"
        paths[2].write_text(json.dumps(conflicting), encoding="utf-8")
        with self.assertRaisesRegex(
            VERIFIER.DeviceMatrixVerificationError,
            "conflicting identity",
        ):
            VERIFIER.verify_matrix_results(
                self.matrix_path,
                self.budgets_path,
                paths,
            )

        paths = self._complete_results()
        mixed = json.loads(paths[-1].read_text(encoding="utf-8"))
        mixed["apkSha256"] = "d" * 64
        paths[-1].write_text(json.dumps(mixed), encoding="utf-8")
        with self.assertRaisesRegex(
            VERIFIER.DeviceMatrixVerificationError,
            "one exact APK",
        ):
            VERIFIER.verify_matrix_results(
                self.matrix_path,
                self.budgets_path,
                paths,
            )


if __name__ == "__main__":
    unittest.main()
