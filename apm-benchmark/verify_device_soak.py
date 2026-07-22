"""Validate a physical-device A/B soak artifact against checked-in budgets."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1


class DeviceSoakVerificationError(ValueError):
    """Raised when device evidence is missing, malformed, or over budget."""


def _mapping(value: Any, label: str) -> dict[str, Any]:
    """Return a JSON object or fail with a location-aware message."""
    if not isinstance(value, dict):
        raise DeviceSoakVerificationError(f"{label} must be a JSON object")
    return value


def _number(value: Any, label: str, *, minimum: float | None = None) -> float:
    """Return one finite numeric value with an optional inclusive floor."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise DeviceSoakVerificationError(f"{label} must be a number")
    result = float(value)
    if not math.isfinite(result):
        raise DeviceSoakVerificationError(f"{label} must be finite")
    if minimum is not None and result < minimum:
        raise DeviceSoakVerificationError(f"{label} must be at least {minimum:g}")
    return result


def _boolean(value: Any, label: str) -> bool:
    """Return one strict JSON boolean."""
    if not isinstance(value, bool):
        raise DeviceSoakVerificationError(f"{label} must be a boolean")
    return value


def _read_json(path: Path, label: str) -> dict[str, Any]:
    """Read one UTF-8 JSON object with a stable diagnostic."""
    try:
        return _mapping(json.loads(path.read_text(encoding="utf-8")), label)
    except (OSError, json.JSONDecodeError) as error:
        raise DeviceSoakVerificationError(f"Cannot read {label} from {path}: {error}") from error


def load_profile(path: Path, profile_name: str) -> dict[str, Any]:
    """Load and validate one named device-soak budget profile."""
    document = _read_json(path, "device-soak budgets")
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise DeviceSoakVerificationError(
            f"Unsupported device-soak budget schema: {document.get('schemaVersion')}"
        )
    profiles = _mapping(document.get("profiles"), "profiles")
    profile = _mapping(profiles.get(profile_name), f"profiles.{profile_name}")
    validated: dict[str, Any] = {}
    for key in (
        "minObservedDurationSeconds",
        "minProcessStarts",
        "maxStartupDeltaMs",
        "maxSdkInitMs",
        "maxMainThreadOperationP95Us",
        "maxCpuAveragePercent",
        "maxPssGrowthKb",
        "maxDiskGrowthBytes",
        "maxChargeConsumptionMahPerHour",
        "maxThermalStatus",
    ):
        validated[key] = _number(profile.get(key), f"{profile_name}.{key}", minimum=0)
    for key in (
        "requirePhysicalDevice",
        "requireOfflineCollector",
        "requirePowerEvidence",
    ):
        validated[key] = _boolean(profile.get(key), f"{profile_name}.{key}")
    return validated


def load_result(path: Path, expected_profile: str) -> dict[str, Any]:
    """Load one runner artifact and require the requested schema/profile identity."""
    document = _read_json(path, "device-soak result")
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise DeviceSoakVerificationError(
            f"Unsupported device-soak result schema: {document.get('schemaVersion')}"
        )
    if document.get("profile") != expected_profile:
        raise DeviceSoakVerificationError(
            f"Result profile {document.get('profile')!r} does not match {expected_profile!r}"
        )
    _mapping(document.get("device"), "device")
    _mapping(document.get("config"), "config")
    _mapping(document.get("summary"), "summary")
    segments = document.get("segments")
    if not isinstance(segments, list) or not segments:
        raise DeviceSoakVerificationError("segments must be a non-empty JSON array")
    return document


def verify_result(profile: dict[str, Any], result: dict[str, Any]) -> list[str]:
    """Check evidence integrity first, then compare every required resource ceiling."""
    device = _mapping(result.get("device"), "device")
    config = _mapping(result.get("config"), "config")
    summary = _mapping(result.get("summary"), "summary")
    control = _mapping(result.get("control"), "control")
    segments = result.get("segments")
    if not isinstance(segments, list) or not segments:
        raise DeviceSoakVerificationError("segments must be a non-empty JSON array")
    is_emulator = _boolean(device.get("isEmulator"), "device.isEmulator")
    offline_collector = _boolean(config.get("offlineCollector"), "config.offlineCollector")

    observed_duration = _number(
        summary.get("observedDurationSeconds"),
        "summary.observedDurationSeconds",
        minimum=0,
    )
    process_starts = _number(summary.get("processStarts"), "summary.processStarts", minimum=0)
    metrics = {
        "startupDeltaMs": _number(summary.get("startupDeltaMs"), "summary.startupDeltaMs"),
        "sdkInitMs": _number(summary.get("sdkInitMaxMs"), "summary.sdkInitMaxMs", minimum=0),
        "mainThreadOperationP95Us": _number(
            summary.get("mainThreadOperationP95Us"),
            "summary.mainThreadOperationP95Us",
            minimum=0,
        ),
        "cpuAveragePercent": _number(
            summary.get("cpuAveragePercent"), "summary.cpuAveragePercent", minimum=0
        ),
        "pssGrowthKb": _number(summary.get("pssGrowthKb"), "summary.pssGrowthKb", minimum=0),
        "diskGrowthBytes": _number(
            summary.get("diskGrowthBytes"), "summary.diskGrowthBytes", minimum=0
        ),
        "thermalStatus": _number(
            summary.get("thermalMaxStatus"), "summary.thermalMaxStatus", minimum=0
        ),
    }
    power_value = summary.get("chargeConsumptionMahPerHour")
    power_source = summary.get("powerSource")
    if power_value is None:
        power_rate = None
    else:
        power_rate = _number(
            power_value,
            "summary.chargeConsumptionMahPerHour",
            minimum=0,
        )
        if not isinstance(power_source, str) or not power_source.strip():
            raise DeviceSoakVerificationError(
                "summary.powerSource is required when power evidence is present"
            )

    integrity_violations: list[str] = []
    control_probe = _mapping(control.get("probe"), "control.probe")
    if _boolean(control_probe.get("sdkEnabled"), "control.probe.sdkEnabled"):
        integrity_violations.append("control process unexpectedly initialized the SDK")
    raw_observed_seconds = 0.0
    for index, raw_segment in enumerate(segments):
        segment = _mapping(raw_segment, f"segments[{index}]")
        run_id = segment.get("runId")
        probe = _mapping(segment.get("probe"), f"segments[{index}].probe")
        samples = segment.get("samples")
        if not isinstance(run_id, str) or not run_id:
            raise DeviceSoakVerificationError(f"segments[{index}].runId must be non-empty")
        if probe.get("runId") != run_id:
            integrity_violations.append(f"segment {run_id} has mismatched probe identity")
        if not _boolean(probe.get("sdkEnabled"), f"segments[{index}].probe.sdkEnabled"):
            integrity_violations.append(f"segment {run_id} did not initialize the SDK")
        if not _boolean(
            probe.get("offlineCollector"),
            f"segments[{index}].probe.offlineCollector",
        ):
            integrity_violations.append(f"segment {run_id} did not use the offline collector")
        if not isinstance(samples, list) or len(samples) < 2:
            integrity_violations.append(f"segment {run_id} lacks two resource samples")
        raw_observed_seconds += _number(
            probe.get("observedDurationMs"),
            f"segments[{index}].probe.observedDurationMs",
            minimum=0,
        ) / 1000.0
    if int(process_starts) != len(segments) or process_starts != int(process_starts):
        integrity_violations.append(
            f"summary processStarts {process_starts:g} does not match {len(segments)} segments"
        )
    if abs(observed_duration - raw_observed_seconds) > 0.001:
        integrity_violations.append(
            f"summary duration {observed_duration:.3f}s does not match raw "
            f"{raw_observed_seconds:.3f}s"
        )
    if profile["requirePhysicalDevice"] and is_emulator:
        integrity_violations.append("emulator evidence is not accepted")
    if profile["requireOfflineCollector"] and not offline_collector:
        integrity_violations.append("offline collector mode was not enabled")
    if observed_duration < profile["minObservedDurationSeconds"]:
        integrity_violations.append(
            f"observed duration {observed_duration:.3f}s is below "
            f"{profile['minObservedDurationSeconds']:.3f}s"
        )
    if process_starts < profile["minProcessStarts"]:
        integrity_violations.append(
            f"process starts {process_starts:.0f} is below {profile['minProcessStarts']:.0f}"
        )
    if profile["requirePowerEvidence"] and power_rate is None:
        integrity_violations.append("power evidence is required for this profile")
    if integrity_violations:
        raise DeviceSoakVerificationError(
            "Device-soak evidence rejected:\n- " + "\n- ".join(integrity_violations)
        )

    ceilings = {
        "startupDeltaMs": profile["maxStartupDeltaMs"],
        "sdkInitMs": profile["maxSdkInitMs"],
        "mainThreadOperationP95Us": profile["maxMainThreadOperationP95Us"],
        "cpuAveragePercent": profile["maxCpuAveragePercent"],
        "pssGrowthKb": profile["maxPssGrowthKb"],
        "diskGrowthBytes": profile["maxDiskGrowthBytes"],
        "thermalStatus": profile["maxThermalStatus"],
    }
    violations = [
        f"{name} {metrics[name]:.3f} exceeds {ceiling:.3f}"
        for name, ceiling in ceilings.items()
        if metrics[name] > ceiling
    ]
    if power_rate is not None and power_rate > profile["maxChargeConsumptionMahPerHour"]:
        violations.append(
            f"chargeConsumptionMahPerHour {power_rate:.3f} exceeds "
            f"{profile['maxChargeConsumptionMahPerHour']:.3f}"
        )
    if violations:
        raise DeviceSoakVerificationError(
            "Device-soak budget violations:\n- " + "\n- ".join(violations)
        )

    messages = [
        f"PASS duration={observed_duration:.3f}s processStarts={process_starts:.0f}",
    ]
    messages.extend(
        f"PASS {name}={metrics[name]:.3f} <= {ceiling:.3f}"
        for name, ceiling in ceilings.items()
    )
    if power_rate is not None:
        messages.append(
            f"PASS chargeConsumptionMahPerHour={power_rate:.3f} <= "
            f"{profile['maxChargeConsumptionMahPerHour']:.3f} ({power_source})"
        )
    return messages


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Parse direct and Gradle verifier entrypoint arguments."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--budgets", type=Path, required=True, help="Checked-in budget JSON")
    parser.add_argument("--results", type=Path, required=True, help="Runner result JSON")
    parser.add_argument(
        "--profile",
        choices=("smoke", "24h", "72h"),
        required=True,
        help="Budget profile that the artifact must identify",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Validate one result file and return a process exit code."""
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        profile = load_profile(args.budgets, args.profile)
        result = load_result(args.results, args.profile)
        for message in verify_result(profile, result):
            print(message)
        print(f"Verified physical-device soak profile {args.profile}.")
        return 0
    except DeviceSoakVerificationError as error:
        print(f"Device-soak verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
