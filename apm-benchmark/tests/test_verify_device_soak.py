"""Deterministic tests for the physical-device soak budget gate."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_device_soak.py"
SPEC = importlib.util.spec_from_file_location("verify_device_soak", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)


class DeviceSoakVerifierTest(unittest.TestCase):
    """Covers evidence integrity, resource ceilings, and power requirements."""

    def setUp(self) -> None:
        """Create isolated checked-in-style budgets and one passing result."""
        self.temp_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_directory.name)
        self.budget_path = self.root / "budgets.json"
        self.result_path = self.root / "result.json"
        profile = {
            "minObservedDurationSeconds": 20,
            "minProcessStarts": 2,
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
            "maxThermalStatus": 4,
        }
        self.budget_path.write_text(
            json.dumps({"schemaVersion": 1, "profiles": {"smoke": profile, "24h": profile}}),
            encoding="utf-8",
        )
        self.result = {
            "schemaVersion": 1,
            "profile": "smoke",
            "device": {"isEmulator": False},
            "config": {"offlineCollector": True},
            "control": {
                "probe": {"sdkEnabled": False},
            },
            "segments": [
                {
                    "runId": "segment-1",
                    "probe": {
                        "runId": "segment-1",
                        "sdkEnabled": True,
                        "offlineCollector": True,
                        "observedDurationMs": 15_000,
                    },
                    "samples": [{}, {}],
                },
                {
                    "runId": "segment-2",
                    "probe": {
                        "runId": "segment-2",
                        "sdkEnabled": True,
                        "offlineCollector": True,
                        "observedDurationMs": 15_000,
                    },
                    "samples": [{}, {}],
                },
            ],
            "summary": {
                "observedDurationSeconds": 30,
                "processStarts": 2,
                "startupDeltaMs": 10,
                "sdkInitMaxMs": 50,
                "mainThreadOperationP95Us": 500,
                "cpuAveragePercent": 5,
                "pssGrowthKb": 1024,
                "diskGrowthBytes": 4096,
                "chargeConsumptionMahPerHour": None,
                "powerSource": None,
                "thermalMaxStatus": 1,
            },
        }
        self._write_result()

    def tearDown(self) -> None:
        """Remove temporary files created by the test."""
        self.temp_directory.cleanup()

    def _write_result(self) -> None:
        """Persist the current mutable result fixture."""
        self.result_path.write_text(json.dumps(self.result), encoding="utf-8")

    def _verify_smoke(self) -> list[str]:
        """Load and verify the smoke fixture through the public helpers."""
        profile = VERIFIER.load_profile(self.budget_path, "smoke")
        result = VERIFIER.load_result(self.result_path, "smoke")
        return VERIFIER.verify_result(profile, result)

    def test_complete_physical_smoke_result_passes(self) -> None:
        """A complete physical A/B result below every ceiling passes."""
        self.assertGreater(len(self._verify_smoke()), 1)

    def test_emulator_or_online_collector_evidence_fails(self) -> None:
        """Neither emulator data nor a successful collector path can satisfy the gate."""
        self.result["device"]["isEmulator"] = True
        self.result["config"]["offlineCollector"] = False
        self._write_result()

        with self.assertRaisesRegex(VERIFIER.DeviceSoakVerificationError, "emulator"):
            self._verify_smoke()

    def test_short_duration_or_too_few_restarts_fails(self) -> None:
        """A parser-valid but incomplete campaign cannot pass."""
        self.result["summary"]["observedDurationSeconds"] = 19
        self.result["summary"]["processStarts"] = 1
        self._write_result()

        with self.assertRaisesRegex(VERIFIER.DeviceSoakVerificationError, "duration"):
            self._verify_smoke()

    def test_any_resource_overage_fails(self) -> None:
        """One exceeded CPU ceiling fails the complete resource gate."""
        self.result["summary"]["cpuAveragePercent"] = 20.1
        self._write_result()

        with self.assertRaisesRegex(VERIFIER.DeviceSoakVerificationError, "cpuAveragePercent"):
            self._verify_smoke()

    def test_long_profile_requires_power_evidence(self) -> None:
        """Long campaigns fail closed when charge or external-meter evidence is absent."""
        budgets = json.loads(self.budget_path.read_text(encoding="utf-8"))
        budgets["profiles"]["24h"]["requirePowerEvidence"] = True
        self.budget_path.write_text(json.dumps(budgets), encoding="utf-8")
        self.result["profile"] = "24h"
        self._write_result()
        profile = VERIFIER.load_profile(self.budget_path, "24h")
        result = VERIFIER.load_result(self.result_path, "24h")

        with self.assertRaisesRegex(VERIFIER.DeviceSoakVerificationError, "power evidence"):
            VERIFIER.verify_result(profile, result)

    def test_power_evidence_requires_a_source(self) -> None:
        """A bare numeric power value is rejected without acquisition provenance."""
        self.result["summary"]["chargeConsumptionMahPerHour"] = 12.5
        self._write_result()

        with self.assertRaisesRegex(VERIFIER.DeviceSoakVerificationError, "powerSource"):
            self._verify_smoke()


if __name__ == "__main__":
    unittest.main()
