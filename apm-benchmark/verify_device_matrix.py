"""Validate the device-lab policy or aggregate explicit physical result artifacts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


BENCHMARK_DIR = Path(__file__).resolve().parent
if str(BENCHMARK_DIR) not in sys.path:
    sys.path.insert(0, str(BENCHMARK_DIR))

from device_lab_matrix import (  # noqa: E402
    DeviceLabMatrix,
    DeviceLabMatrixError,
    file_sha256,
    load_matrix,
    require_device_lane,
)
import verify_device_soak as soak_verifier  # noqa: E402


SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SOURCE_REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
RUNNER_PATH = BENCHMARK_DIR / "run_device_soak.py"


class DeviceMatrixVerificationError(ValueError):
    """Raised when aggregate physical evidence is incomplete or inconsistent."""


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DeviceMatrixVerificationError(f"Cannot read result {path}: {error}") from error
    if not isinstance(value, dict):
        raise DeviceMatrixVerificationError(f"Result {path} must be a JSON object")
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DeviceMatrixVerificationError(f"{label} must be a non-empty string")
    return value.strip()


def _artifact_device_identity(
    device: dict[str, Any],
) -> tuple[str, str, str, str, str, str, int]:
    api_level = device.get("apiLevel")
    if isinstance(api_level, bool) or not isinstance(api_level, int):
        raise DeviceMatrixVerificationError("device.apiLevel must be an integer")
    return (
        _text(device.get("serial"), "device.serial"),
        _text(device.get("manufacturer"), "device.manufacturer"),
        _text(device.get("model"), "device.model"),
        _text(device.get("device"), "device.device"),
        _text(device.get("fingerprint"), "device.fingerprint"),
        _text(device.get("primaryAbi"), "device.primaryAbi"),
        api_level,
    )


def _require_hash(value: Any, expected: str, label: str) -> None:
    actual = _text(value, label)
    if not SHA256_PATTERN.fullmatch(actual):
        raise DeviceMatrixVerificationError(f"{label} must be a lowercase SHA-256")
    if actual != expected:
        raise DeviceMatrixVerificationError(
            f"{label} {actual} does not match current input {expected}"
        )


def validate_plan(matrix_path: Path, budgets_path: Path) -> DeviceLabMatrix:
    """Validate the matrix plus every referenced checked-in budget profile."""
    available_profiles: list[str] = []
    for profile in ("smoke", "24h", "72h"):
        soak_verifier.load_profile(budgets_path, profile)
        available_profiles.append(profile)
    return load_matrix(matrix_path, available_profiles=available_profiles)


def verify_matrix_results(
    matrix_path: Path,
    budgets_path: Path,
    result_paths: list[Path],
) -> list[str]:
    """Verify individual budgets, exact provenance, lane coverage, and diversity."""
    if not result_paths:
        raise DeviceMatrixVerificationError("At least one explicit result path is required")
    matrix = validate_plan(matrix_path, budgets_path)
    matrix_hash = file_sha256(matrix_path)
    budgets_hash = file_sha256(budgets_path)
    runner_hash = file_sha256(RUNNER_PATH)
    lane_profile_evidence: set[tuple[str, str]] = set()
    profile_devices: dict[str, set[str]] = defaultdict(set)
    profile_manufacturers: dict[str, set[str]] = defaultdict(set)
    profile_reset_strategies: dict[str, set[str]] = defaultdict(set)
    artifact_identities: set[tuple[str, str, str, str]] = set()
    device_identities: dict[str, tuple[str, str, str, str, str, str, int]] = {}
    apk_hashes: set[str] = set()
    source_revisions: set[str] = set()
    messages: list[str] = []

    for result_path in result_paths:
        raw = _read_json(result_path)
        profile = _text(raw.get("profile"), f"{result_path}.profile")
        if profile not in ("smoke", "24h", "72h"):
            raise DeviceMatrixVerificationError(
                f"{result_path}.profile is unsupported: {profile!r}"
            )
        result = soak_verifier.load_result(result_path, profile)
        budget = soak_verifier.load_profile(budgets_path, profile)
        soak_verifier.verify_result(budget, result)
        if result.get("schemaVersion") != soak_verifier.RESULT_SCHEMA_VERSION:
            raise DeviceMatrixVerificationError(
                f"{result_path} must use current result schema "
                f"{soak_verifier.RESULT_SCHEMA_VERSION}"
            )

        provenance = result.get("provenance")
        if not isinstance(provenance, dict):
            raise DeviceMatrixVerificationError(f"{result_path}.provenance must be an object")
        _require_hash(
            provenance.get("matrixSha256"),
            matrix_hash,
            f"{result_path}.provenance.matrixSha256",
        )
        _require_hash(
            provenance.get("budgetsSha256"),
            budgets_hash,
            f"{result_path}.provenance.budgetsSha256",
        )
        _require_hash(
            provenance.get("runnerSha256"),
            runner_hash,
            f"{result_path}.provenance.runnerSha256",
        )
        lane_id = _text(provenance.get("laneId"), f"{result_path}.provenance.laneId")
        lane = matrix.lane(lane_id)
        source_revision = _text(
            provenance.get("sourceRevision"),
            f"{result_path}.provenance.sourceRevision",
        )
        if not SOURCE_REVISION_PATTERN.fullmatch(source_revision):
            raise DeviceMatrixVerificationError(
                f"{result_path}.provenance.sourceRevision must be a 40-char commit"
            )
        if provenance.get("sourceDirty") is not False:
            raise DeviceMatrixVerificationError(
                f"{result_path}.provenance.sourceDirty must be false"
            )

        device = result.get("device")
        config = result.get("config")
        if not isinstance(device, dict) or not isinstance(config, dict):
            raise DeviceMatrixVerificationError(
                f"{result_path} lacks device/config objects"
            )
        reset_strategy = _text(
            config.get("appDataResetStrategy"),
            f"{result_path}.config.appDataResetStrategy",
        )
        require_device_lane(
            device,
            lane,
            profile,
            reset_strategy=reset_strategy,
        )
        apk_hash = _text(result.get("apkSha256"), f"{result_path}.apkSha256")
        if not SHA256_PATTERN.fullmatch(apk_hash):
            raise DeviceMatrixVerificationError(
                f"{result_path}.apkSha256 must be a lowercase SHA-256"
            )
        generated_at = _text(result.get("generatedAtUtc"), f"{result_path}.generatedAtUtc")
        device_identity = _artifact_device_identity(device)
        serial = device_identity[0]
        previous_identity = device_identities.setdefault(serial, device_identity)
        if previous_identity != device_identity:
            raise DeviceMatrixVerificationError(
                f"Device serial {serial!r} has conflicting identity across results"
            )
        artifact_identity = (serial, profile, generated_at, apk_hash)
        if artifact_identity in artifact_identities:
            raise DeviceMatrixVerificationError(
                f"Duplicate result identity for serial/profile/time in {result_path}"
            )
        artifact_identities.add(artifact_identity)
        lane_profile_evidence.add((lane_id, profile))
        profile_devices[profile].add(serial)
        profile_manufacturers[profile].add(device_identity[1].casefold())
        profile_reset_strategies[profile].add(reset_strategy)
        apk_hashes.add(apk_hash)
        source_revisions.add(source_revision)
        messages.append(
            f"PASS {lane_id}/{profile} serial={serial} "
            f"api={device.get('apiLevel')} manufacturer={device_identity[1]}"
        )

    if len(apk_hashes) != 1:
        raise DeviceMatrixVerificationError(
            f"Matrix evidence must use one exact APK SHA-256; found {sorted(apk_hashes)}"
        )
    if len(source_revisions) != 1:
        raise DeviceMatrixVerificationError(
            "Matrix evidence must use one exact source revision; "
            f"found {sorted(source_revisions)}"
        )

    missing_lane_profiles = [
        f"{lane.lane_id}/{profile}"
        for lane in matrix.lanes
        for profile in lane.profiles
        if (lane.lane_id, profile) not in lane_profile_evidence
    ]
    if missing_lane_profiles:
        raise DeviceMatrixVerificationError(
            "Missing required lane/profile evidence: " + ", ".join(missing_lane_profiles)
        )

    for coverage in matrix.coverage:
        profile = coverage.profile
        device_count = len(profile_devices[profile])
        manufacturer_count = len(profile_manufacturers[profile])
        if device_count < coverage.min_distinct_devices:
            raise DeviceMatrixVerificationError(
                f"{profile} has {device_count} distinct devices; "
                f"requires {coverage.min_distinct_devices}"
            )
        if manufacturer_count < coverage.min_distinct_manufacturers:
            raise DeviceMatrixVerificationError(
                f"{profile} has {manufacturer_count} distinct manufacturers; "
                f"requires {coverage.min_distinct_manufacturers}"
            )
        missing_resets = set(coverage.required_reset_strategies) - profile_reset_strategies[
            profile
        ]
        if missing_resets:
            raise DeviceMatrixVerificationError(
                f"{profile} lacks reset strategy evidence: {sorted(missing_resets)}"
            )
        messages.append(
            f"PASS {profile} diversity devices={device_count} "
            f"manufacturers={manufacturer_count}"
        )
    messages.append(
        f"PASS matrix APK={next(iter(apk_hashes))} "
        f"source={next(iter(source_revisions))}"
    )
    return messages


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--budgets", type=Path, required=True)
    parser.add_argument(
        "--results",
        type=Path,
        nargs="+",
        help="Explicit result JSON files; omit to validate only the checked-in plan",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        matrix = validate_plan(args.matrix, args.budgets)
        if args.results:
            for message in verify_matrix_results(args.matrix, args.budgets, args.results):
                print(message)
            print("Verified complete physical device-lab matrix evidence.")
        else:
            lane_profiles = sum(len(lane.profiles) for lane in matrix.lanes)
            print(
                f"Validated device-lab matrix plan: {len(matrix.lanes)} lanes, "
                f"{lane_profiles} lane/profile requirements. "
                "No physical evidence was evaluated."
            )
        return 0
    except (
        DeviceLabMatrixError,
        DeviceMatrixVerificationError,
        soak_verifier.DeviceSoakVerificationError,
    ) as error:
        print(f"Device-matrix verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
