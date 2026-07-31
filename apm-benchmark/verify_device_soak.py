"""Validate a physical-device A/B soak artifact against checked-in budgets."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


BUDGET_SCHEMA_VERSION = 1
RESULT_SCHEMA_VERSION = 3
SUPPORTED_RESULT_SCHEMA_VERSIONS = (1, 2, RESULT_SCHEMA_VERSION)
ATTRIBUTION_TOLERANCE = 1e-6
MAX_ADB_RECONNECT_TIMEOUT_SECONDS = 10 * 60
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SOURCE_REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")


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


def _text(value: Any, label: str) -> str:
    """Return one non-empty string without silently coercing another JSON type."""
    if not isinstance(value, str) or not value.strip():
        raise DeviceSoakVerificationError(f"{label} must be a non-empty string")
    return value.strip()


def _sha256(value: Any, label: str) -> str:
    """Require one canonical lowercase SHA-256 provenance value."""
    digest = _text(value, label)
    if not SHA256_PATTERN.fullmatch(digest):
        raise DeviceSoakVerificationError(f"{label} must be a lowercase SHA-256")
    return digest


def _validate_current_provenance(document: dict[str, Any]) -> None:
    """Require reproducible device/source/acquisition identity for schema v3."""
    _sha256(document.get("apkSha256"), "apkSha256")
    generated_at = _text(document.get("generatedAtUtc"), "generatedAtUtc")
    try:
        parsed_time = datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise DeviceSoakVerificationError(
            "generatedAtUtc must be an ISO-8601 timestamp"
        ) from error
    if parsed_time.tzinfo is None:
        raise DeviceSoakVerificationError("generatedAtUtc must include a timezone")

    device = _mapping(document.get("device"), "device")
    for key in (
        "serial",
        "manufacturer",
        "model",
        "device",
        "fingerprint",
        "primaryAbi",
    ):
        _text(device.get(key), f"device.{key}")
    api_level = device.get("apiLevel")
    if (
        isinstance(api_level, bool)
        or not isinstance(api_level, int)
        or api_level < 24
        or api_level > 100
    ):
        raise DeviceSoakVerificationError("device.apiLevel must be an integer in 24..100")

    provenance = _mapping(document.get("provenance"), "provenance")
    expected_keys = {
        "matrixSchemaVersion",
        "matrixSha256",
        "budgetsSha256",
        "runnerSha256",
        "laneId",
        "sourceRevision",
        "sourceDirty",
    }
    if set(provenance) != expected_keys:
        raise DeviceSoakVerificationError(
            "provenance keys mismatch; "
            f"missing={sorted(expected_keys - set(provenance))}, "
            f"unknown={sorted(set(provenance) - expected_keys)}"
        )
    if provenance.get("matrixSchemaVersion") != 1:
        raise DeviceSoakVerificationError("provenance.matrixSchemaVersion must be 1")
    for key in ("matrixSha256", "budgetsSha256", "runnerSha256"):
        _sha256(provenance.get(key), f"provenance.{key}")
    _text(provenance.get("laneId"), "provenance.laneId")
    source_revision = _text(provenance.get("sourceRevision"), "provenance.sourceRevision")
    if not SOURCE_REVISION_PATTERN.fullmatch(source_revision):
        raise DeviceSoakVerificationError(
            "provenance.sourceRevision must be a lowercase 40-character commit"
        )
    if provenance.get("sourceDirty") is not False:
        raise DeviceSoakVerificationError("provenance.sourceDirty must be false")


def _segment_cpu_percent(
    segment: dict[str, Any],
    label: str,
    clock_ticks_per_second: float,
) -> tuple[float, float]:
    """Recompute one raw segment's process CPU percent and wall-time weight."""
    samples = segment.get("samples")
    if not isinstance(samples, list) or len(samples) < 2:
        raise DeviceSoakVerificationError(f"{label}.samples must contain at least two entries")
    first = _mapping(samples[0], f"{label}.samples[0]")
    last = _mapping(samples[-1], f"{label}.samples[-1]")
    first_elapsed = _number(
        first.get("elapsed_seconds"),
        f"{label}.samples[0].elapsed_seconds",
        minimum=0,
    )
    last_elapsed = _number(
        last.get("elapsed_seconds"),
        f"{label}.samples[-1].elapsed_seconds",
        minimum=0,
    )
    first_jiffies = _number(
        first.get("cpu_jiffies"),
        f"{label}.samples[0].cpu_jiffies",
        minimum=0,
    )
    last_jiffies = _number(
        last.get("cpu_jiffies"),
        f"{label}.samples[-1].cpu_jiffies",
        minimum=0,
    )
    wall_seconds = last_elapsed - first_elapsed
    if wall_seconds <= 0:
        raise DeviceSoakVerificationError(f"{label} lacks a positive CPU sampling interval")
    if last_jiffies < first_jiffies:
        raise DeviceSoakVerificationError(f"{label} CPU jiffies moved backwards")
    cpu_percent = (
        (last_jiffies - first_jiffies)
        / clock_ticks_per_second
        / wall_seconds
        * 100.0
    )
    return cpu_percent, wall_seconds


def _read_json(path: Path, label: str) -> dict[str, Any]:
    """Read one UTF-8 JSON object with a stable diagnostic."""
    try:
        return _mapping(json.loads(path.read_text(encoding="utf-8")), label)
    except (OSError, json.JSONDecodeError) as error:
        raise DeviceSoakVerificationError(f"Cannot read {label} from {path}: {error}") from error


def load_profile(path: Path, profile_name: str) -> dict[str, Any]:
    """Load and validate one named device-soak budget profile."""
    document = _read_json(path, "device-soak budgets")
    if document.get("schemaVersion") != BUDGET_SCHEMA_VERSION:
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
    if document.get("schemaVersion") not in SUPPORTED_RESULT_SCHEMA_VERSIONS:
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
    if document.get("schemaVersion") == RESULT_SCHEMA_VERSION:
        _validate_current_provenance(document)
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
    result_schema = result.get("schemaVersion")
    cpu_attribution: dict[str, float] | None = None
    if isinstance(result_schema, int) and result_schema >= 2:
        clock_ticks_per_second = _number(
            config.get("clockTicksPerSecond"),
            "config.clockTicksPerSecond",
            minimum=1,
        )
        raw_control_cpu, _ = _segment_cpu_percent(
            control,
            "control",
            clock_ticks_per_second,
        )
        raw_enabled_pairs = [
            _segment_cpu_percent(
                _mapping(segment, f"segments[{index}]"),
                f"segments[{index}]",
                clock_ticks_per_second,
            )
            for index, segment in enumerate(segments)
        ]
        raw_enabled_weight = sum(weight for _, weight in raw_enabled_pairs)
        raw_enabled_cpu = (
            sum(value * weight for value, weight in raw_enabled_pairs)
            / raw_enabled_weight
        )
        cpu_attribution = {
            "control": _number(
                summary.get("cpuControlPercent"),
                "summary.cpuControlPercent",
                minimum=0,
            ),
            "enabled": _number(
                summary.get("cpuEnabledAveragePercent"),
                "summary.cpuEnabledAveragePercent",
                minimum=0,
            ),
            "delta": _number(
                summary.get("cpuDeltaPercent"),
                "summary.cpuDeltaPercent",
            ),
            "rawControl": raw_control_cpu,
            "rawEnabled": raw_enabled_cpu,
        }
    reset_strategy = config.get("appDataResetStrategy")
    if result_schema == RESULT_SCHEMA_VERSION and reset_strategy is None:
        raise DeviceSoakVerificationError(
            "config.appDataResetStrategy is required by result schema 3"
        )
    if reset_strategy is not None and reset_strategy not in (
        "pm-clear",
        "uninstall-reinstall",
    ):
        raise DeviceSoakVerificationError(
            "config.appDataResetStrategy must be pm-clear or uninstall-reinstall"
        )
    transient_retry_count = config.get("transientAdbRetryCount")
    if result_schema == RESULT_SCHEMA_VERSION and transient_retry_count is None:
        raise DeviceSoakVerificationError(
            "config.transientAdbRetryCount is required by result schema 3"
        )
    if transient_retry_count is not None and (
        isinstance(transient_retry_count, bool)
        or not isinstance(transient_retry_count, int)
        or transient_retry_count < 0
    ):
        raise DeviceSoakVerificationError(
            "config.transientAdbRetryCount must be a non-negative integer"
        )
    reconnect_timeout = config.get("adbReconnectTimeoutSeconds")
    if result_schema == RESULT_SCHEMA_VERSION and reconnect_timeout is None:
        raise DeviceSoakVerificationError(
            "config.adbReconnectTimeoutSeconds is required by result schema 3"
        )
    if reconnect_timeout is not None and (
        isinstance(reconnect_timeout, bool)
        or not isinstance(reconnect_timeout, int)
        or reconnect_timeout <= 0
        or reconnect_timeout > MAX_ADB_RECONNECT_TIMEOUT_SECONDS
    ):
        raise DeviceSoakVerificationError(
            "config.adbReconnectTimeoutSeconds must be an integer in 1..600"
        )
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
    if cpu_attribution is not None:
        if abs(cpu_attribution["enabled"] - metrics["cpuAveragePercent"]) > ATTRIBUTION_TOLERANCE:
            integrity_violations.append(
                "cpuEnabledAveragePercent does not match the absolute cpuAveragePercent gate"
            )
        expected_delta = cpu_attribution["enabled"] - cpu_attribution["control"]
        if abs(cpu_attribution["delta"] - expected_delta) > ATTRIBUTION_TOLERANCE:
            integrity_violations.append(
                "cpuDeltaPercent does not match enabled minus control CPU"
            )
        if abs(cpu_attribution["control"] - cpu_attribution["rawControl"]) > ATTRIBUTION_TOLERANCE:
            integrity_violations.append(
                "cpuControlPercent does not match raw control samples"
            )
        if abs(cpu_attribution["enabled"] - cpu_attribution["rawEnabled"]) > ATTRIBUTION_TOLERANCE:
            integrity_violations.append(
                "cpuEnabledAveragePercent does not match raw enabled samples"
            )
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
    if cpu_attribution is not None:
        messages.append(
            "INFO cpuAttribution "
            f"control={cpu_attribution['control']:.3f}% "
            f"enabled={cpu_attribution['enabled']:.3f}% "
            f"delta={cpu_attribution['delta']:.3f}%"
        )
    if transient_retry_count is not None:
        messages.append(
            "INFO adbReadOnlyRetries "
            f"count={transient_retry_count} "
            f"window={reconnect_timeout or 'legacy-unknown'}s "
            f"reset={reset_strategy or 'legacy-unknown'}"
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
