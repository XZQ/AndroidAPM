"""Validate AndroidX Microbenchmark JSON against checked-in release budgets."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
BENCHMARK_RESULT_SUFFIX = "benchmarkData.json"
EMULATOR_MARKERS = ("emulator", "generic", "sdk_gphone", "emu64")


class BudgetVerificationError(ValueError):
    """Raised when benchmark evidence is missing, malformed, or over budget."""


@dataclass(frozen=True)
class BenchmarkMeasurement:
    """Normalized measurements for one AndroidX benchmark method."""

    key: str
    median_time_ns: float
    median_allocation_count: float


def _require_mapping(value: Any, label: str) -> dict[str, Any]:
    """Return a JSON object or fail with a location-aware message."""
    if not isinstance(value, dict):
        raise BudgetVerificationError(f"{label} must be a JSON object")
    return value


def _require_positive_number(value: Any, label: str) -> float:
    """Return a finite positive number used by a budget or measurement."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BudgetVerificationError(f"{label} must be a number")
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise BudgetVerificationError(f"{label} must be finite and greater than zero")
    return result


def load_budgets(path: Path) -> dict[str, dict[str, float]]:
    """Load and validate the checked-in release-budget contract."""
    try:
        document = _require_mapping(json.loads(path.read_text(encoding="utf-8")), str(path))
    except (OSError, json.JSONDecodeError) as error:
        raise BudgetVerificationError(f"Cannot read benchmark budgets from {path}: {error}") from error
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise BudgetVerificationError(
            f"Unsupported benchmark budget schema: {document.get('schemaVersion')}"
        )
    raw_benchmarks = _require_mapping(document.get("benchmarks"), "benchmarks")
    if not raw_benchmarks:
        raise BudgetVerificationError("At least one benchmark budget is required")

    budgets: dict[str, dict[str, float]] = {}
    for key, raw_budget in raw_benchmarks.items():
        if not isinstance(key, str) or not key:
            raise BudgetVerificationError("Benchmark budget keys must be non-empty strings")
        budget = _require_mapping(raw_budget, f"benchmarks.{key}")
        budgets[key] = {
            "operationCount": _require_positive_number(
                budget.get("operationCount"), f"{key}.operationCount"
            ),
            "maxMedianTimeNs": _require_positive_number(
                budget.get("maxMedianTimeNs"), f"{key}.maxMedianTimeNs"
            ),
            "maxMedianAllocationCount": _require_positive_number(
                budget.get("maxMedianAllocationCount"),
                f"{key}.maxMedianAllocationCount",
            ),
        }
    return budgets


def _is_emulator(document: dict[str, Any], benchmarks: list[Any]) -> bool:
    """Detect Android emulator evidence using device metadata and AndroidX name prefixes."""
    context = _require_mapping(document.get("context"), "context")
    build = _require_mapping(context.get("build"), "context.build")
    device_identity = " ".join(
        str(build.get(field, "")).lower()
        for field in ("brand", "device", "fingerprint", "model", "type")
    )
    if any(marker in device_identity for marker in EMULATOR_MARKERS):
        return True
    return any(
        isinstance(item, dict) and str(item.get("name", "")).startswith("EMULATOR_")
        for item in benchmarks
    )


def _metric_median(metrics: dict[str, Any], metric_name: str, key: str) -> float:
    """Read a finite positive AndroidX median for one required metric."""
    metric = _require_mapping(metrics.get(metric_name), f"{key}.metrics.{metric_name}")
    return _require_positive_number(metric.get("median"), f"{key}.{metric_name}.median")


def load_measurements(path: Path, allow_emulator: bool) -> dict[str, BenchmarkMeasurement]:
    """Load one AndroidX result file and normalize benchmark method identities."""
    try:
        document = _require_mapping(json.loads(path.read_text(encoding="utf-8")), str(path))
    except (OSError, json.JSONDecodeError) as error:
        raise BudgetVerificationError(f"Cannot read benchmark results from {path}: {error}") from error
    raw_benchmarks = document.get("benchmarks")
    if not isinstance(raw_benchmarks, list) or not raw_benchmarks:
        raise BudgetVerificationError(f"No benchmark measurements found in {path}")
    if _is_emulator(document, raw_benchmarks) and not allow_emulator:
        raise BudgetVerificationError(
            f"Emulator result rejected for release budgets: {path}. Use a physical device."
        )

    measurements: dict[str, BenchmarkMeasurement] = {}
    for index, raw_benchmark in enumerate(raw_benchmarks):
        benchmark = _require_mapping(raw_benchmark, f"benchmarks[{index}]")
        class_name = benchmark.get("className")
        method_name = benchmark.get("name")
        if not isinstance(class_name, str) or not isinstance(method_name, str):
            raise BudgetVerificationError(f"Benchmark identity is malformed at index {index}")
        normalized_method = method_name.removeprefix("EMULATOR_")
        key = f"{class_name}.{normalized_method}"
        if key in measurements:
            raise BudgetVerificationError(f"Duplicate benchmark measurement: {key}")
        metrics = _require_mapping(benchmark.get("metrics"), f"{key}.metrics")
        measurements[key] = BenchmarkMeasurement(
            key=key,
            median_time_ns=_metric_median(metrics, "timeNs", key),
            median_allocation_count=_metric_median(metrics, "allocationCount", key),
        )
    return measurements


def verify_results(
    budgets: dict[str, dict[str, float]],
    measurements: dict[str, BenchmarkMeasurement],
) -> list[str]:
    """Compare every required benchmark with both latency and allocation ceilings."""
    missing = sorted(set(budgets) - set(measurements))
    if missing:
        raise BudgetVerificationError(
            "Missing required benchmark measurements: " + ", ".join(missing)
        )

    messages: list[str] = []
    violations: list[str] = []
    for key, budget in budgets.items():
        measurement = measurements[key]
        operation_count = budget["operationCount"]
        time_budget = budget["maxMedianTimeNs"]
        allocation_budget = budget["maxMedianAllocationCount"]
        messages.append(
            f"PASS {key}: median={measurement.median_time_ns:.2f} ns "
            f"({measurement.median_time_ns / operation_count:.2f} ns/op), "
            f"allocations={measurement.median_allocation_count:.2f} "
            f"({measurement.median_allocation_count / operation_count:.2f}/op)"
        )
        if measurement.median_time_ns > time_budget:
            violations.append(
                f"{key} median time {measurement.median_time_ns:.2f} ns exceeds "
                f"{time_budget:.2f} ns"
            )
        if measurement.median_allocation_count > allocation_budget:
            violations.append(
                f"{key} median allocations {measurement.median_allocation_count:.2f} exceeds "
                f"{allocation_budget:.2f}"
            )
    if violations:
        raise BudgetVerificationError("Benchmark budget violations:\n- " + "\n- ".join(violations))
    return messages


def find_result_files(root: Path) -> list[Path]:
    """Find all AndroidX benchmark JSON files below a connected-test output root."""
    if not root.is_dir():
        raise BudgetVerificationError(f"Benchmark result directory does not exist: {root}")
    files = sorted(path for path in root.rglob(f"*{BENCHMARK_RESULT_SUFFIX}") if path.is_file())
    if not files:
        raise BudgetVerificationError(f"No *{BENCHMARK_RESULT_SUFFIX} files found below {root}")
    return files


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Parse command-line inputs for the Gradle and direct-script entrypoints."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--budgets", type=Path, required=True, help="Checked-in budget JSON")
    parser.add_argument("--results", type=Path, required=True, help="AndroidX result directory")
    parser.add_argument(
        "--allow-emulator",
        action="store_true",
        help="Exercise parser wiring only; emulator evidence is never accepted by the Gradle release gate",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Validate all connected-device result files and return a process exit code."""
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        budgets = load_budgets(args.budgets)
        result_files = find_result_files(args.results)
        for result_file in result_files:
            measurements = load_measurements(result_file, allow_emulator=args.allow_emulator)
            print(f"Verifying {result_file}")
            for message in verify_results(budgets, measurements):
                print(message)
        print(f"Verified {len(budgets)} benchmark budgets across {len(result_files)} result file(s).")
        return 0
    except BudgetVerificationError as error:
        print(f"Benchmark budget verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
