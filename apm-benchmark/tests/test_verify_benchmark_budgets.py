"""Deterministic host-side tests for the AndroidX benchmark budget gate."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_benchmark_budgets.py"
SPEC = importlib.util.spec_from_file_location("verify_benchmark_budgets", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)


class BenchmarkBudgetVerifierTest(unittest.TestCase):
    """Covers pass, failure, missing-data, and environment-integrity behavior."""

    def setUp(self) -> None:
        """Create an isolated result tree and one required budget."""
        self.temp_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_directory.name)
        self.budget_path = self.root / "budgets.json"
        self.result_path = self.root / "device" / "sample-benchmarkData.json"
        self.result_path.parent.mkdir()
        self.key = "com.apm.benchmark.EventCodecBenchmark.encodeDurableEvent"
        self._write_budget(max_time=100.0, max_allocations=10.0)

    def tearDown(self) -> None:
        """Remove temporary files created by the test."""
        self.temp_directory.cleanup()

    def _write_budget(self, max_time: float, max_allocations: float) -> None:
        """Write one valid checked-in-style budget document."""
        self.budget_path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "benchmarks": {
                        self.key: {
                            "operationCount": 1,
                            "maxMedianTimeNs": max_time,
                            "maxMedianAllocationCount": max_allocations,
                        }
                    },
                }
            ),
            encoding="utf-8",
        )

    def _write_result(
        self,
        *,
        time_ns: float = 90.0,
        allocations: float = 9.0,
        emulator: bool = False,
        include_benchmark: bool = True,
    ) -> None:
        """Write a minimal AndroidX-compatible benchmark result document."""
        benchmarks = []
        if include_benchmark:
            benchmarks.append(
                {
                    "name": ("EMULATOR_" if emulator else "") + "encodeDurableEvent",
                    "className": "com.apm.benchmark.EventCodecBenchmark",
                    "metrics": {
                        "timeNs": {"median": time_ns},
                        "allocationCount": {"median": allocations},
                    },
                }
            )
        self.result_path.write_text(
            json.dumps(
                {
                    "context": {
                        "build": {
                            "brand": "google" if emulator else "physical",
                            "device": "emu64xa16k" if emulator else "device",
                            "fingerprint": "sdk_gphone/emulator" if emulator else "vendor/device/release",
                            "model": "sdk_gphone" if emulator else "device-model",
                            "type": "userdebug" if emulator else "user",
                        }
                    },
                    "benchmarks": benchmarks,
                }
            ),
            encoding="utf-8",
        )

    def test_valid_physical_result_passes(self) -> None:
        """A complete physical-device result below both ceilings passes."""
        self._write_result()

        budgets = VERIFIER.load_budgets(self.budget_path)
        measurements = VERIFIER.load_measurements(self.result_path, allow_emulator=False)

        messages = VERIFIER.verify_results(budgets, measurements)
        self.assertEqual(1, len(messages))

    def test_latency_or_allocation_overage_fails(self) -> None:
        """Either protected metric exceeding its ceiling fails the release gate."""
        self._write_result(time_ns=101.0, allocations=11.0)

        budgets = VERIFIER.load_budgets(self.budget_path)
        measurements = VERIFIER.load_measurements(self.result_path, allow_emulator=False)

        with self.assertRaisesRegex(VERIFIER.BudgetVerificationError, "budget violations"):
            VERIFIER.verify_results(budgets, measurements)

    def test_missing_required_benchmark_fails(self) -> None:
        """An empty result cannot silently pass the release gate."""
        self._write_result(include_benchmark=False)

        with self.assertRaises(VERIFIER.BudgetVerificationError):
            VERIFIER.load_measurements(self.result_path, allow_emulator=False)

    def test_emulator_result_is_rejected_by_default(self) -> None:
        """Emulator execution evidence cannot become an accepted release baseline."""
        self._write_result(emulator=True)

        with self.assertRaisesRegex(VERIFIER.BudgetVerificationError, "Emulator result rejected"):
            VERIFIER.load_measurements(self.result_path, allow_emulator=False)

    def test_emulator_override_only_exercises_parser(self) -> None:
        """The explicit override supports local parser checks without weakening the Gradle gate."""
        self._write_result(emulator=True)

        measurements = VERIFIER.load_measurements(self.result_path, allow_emulator=True)

        self.assertIn(self.key, measurements)


if __name__ == "__main__":
    unittest.main()
