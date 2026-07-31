"""Shared schema and matching logic for the physical Android device-lab matrix."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


MATRIX_SCHEMA_VERSION = 1
SUPPORTED_PROFILES = ("smoke", "24h", "72h")
SUPPORTED_RESET_STRATEGIES = ("pm-clear", "uninstall-reinstall")


class DeviceLabMatrixError(ValueError):
    """Raised when a matrix plan or device/lane assignment is invalid."""


@dataclass(frozen=True)
class DeviceLabLane:
    """One non-overlapping API-level lane and its required campaign profiles."""

    lane_id: str
    description: str
    api_level_min: int
    api_level_max: int
    manufacturers: tuple[str, ...]
    primary_abis: tuple[str, ...]
    profiles: tuple[str, ...]
    allowed_reset_strategies: tuple[str, ...]


@dataclass(frozen=True)
class ProfileCoverage:
    """Aggregate diversity requirements for one campaign profile."""

    profile: str
    min_distinct_devices: int
    min_distinct_manufacturers: int
    required_reset_strategies: tuple[str, ...]


@dataclass(frozen=True)
class DeviceLabMatrix:
    """Validated device-lab plan used by acquisition and aggregate verification."""

    lanes: tuple[DeviceLabLane, ...]
    coverage: tuple[ProfileCoverage, ...]

    def lane(self, lane_id: str) -> DeviceLabLane:
        """Resolve one exact lane identifier."""
        for lane in self.lanes:
            if lane.lane_id == lane_id:
                return lane
        raise DeviceLabMatrixError(f"Unknown device-lab lane: {lane_id!r}")

    def coverage_for(self, profile: str) -> ProfileCoverage:
        """Resolve one exact profile coverage policy."""
        for coverage in self.coverage:
            if coverage.profile == profile:
                return coverage
        raise DeviceLabMatrixError(f"Missing coverage policy for profile {profile!r}")


def file_sha256(path: Path) -> str:
    """Return a lowercase SHA-256 digest for one exact acquisition input."""
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        raise DeviceLabMatrixError(f"Cannot hash {path}: {error}") from error
    return digest.hexdigest()


def _mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise DeviceLabMatrixError(f"{label} must be a JSON object")
    return value


def _strict_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unknown = sorted(actual - expected)
        raise DeviceLabMatrixError(
            f"{label} keys mismatch; missing={missing}, unknown={unknown}"
        )


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DeviceLabMatrixError(f"{label} must be a non-empty string")
    return value.strip()


def _integer(value: Any, label: str, *, minimum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise DeviceLabMatrixError(f"{label} must be an integer >= {minimum}")
    return value


def _string_list(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        raise DeviceLabMatrixError(f"{label} must be a non-empty string array")
    parsed = tuple(_text(item, f"{label}[{index}]") for index, item in enumerate(value))
    if len(set(parsed)) != len(parsed):
        raise DeviceLabMatrixError(f"{label} must not contain duplicates")
    return parsed


def _reset_strategy_list(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list):
        raise DeviceLabMatrixError(f"{label} must be a JSON array")
    parsed = tuple(_text(item, f"{label}[{index}]") for index, item in enumerate(value))
    if len(set(parsed)) != len(parsed):
        raise DeviceLabMatrixError(f"{label} must not contain duplicates")
    unsupported = sorted(set(parsed) - set(SUPPORTED_RESET_STRATEGIES))
    if unsupported:
        raise DeviceLabMatrixError(f"{label} contains unsupported values: {unsupported}")
    return parsed


def _allowed_reset_strategy_list(value: Any, label: str) -> tuple[str, ...]:
    parsed = _reset_strategy_list(value, label)
    if not parsed:
        raise DeviceLabMatrixError(f"{label} must contain at least one strategy")
    return parsed


def _read_document(path: Path) -> dict[str, Any]:
    try:
        return _mapping(json.loads(path.read_text(encoding="utf-8")), "device-lab matrix")
    except (OSError, json.JSONDecodeError) as error:
        raise DeviceLabMatrixError(f"Cannot read device-lab matrix from {path}: {error}") from error


def load_matrix(
    path: Path,
    *,
    available_profiles: Iterable[str] = SUPPORTED_PROFILES,
) -> DeviceLabMatrix:
    """Load and validate one fail-closed matrix plan."""
    document = _read_document(path)
    _strict_keys(document, {"schemaVersion", "lanes", "profileCoverage"}, "matrix")
    if document.get("schemaVersion") != MATRIX_SCHEMA_VERSION:
        raise DeviceLabMatrixError(
            f"Unsupported device-lab matrix schema: {document.get('schemaVersion')}"
        )

    allowed_profiles = tuple(available_profiles)
    if not allowed_profiles or len(set(allowed_profiles)) != len(allowed_profiles):
        raise DeviceLabMatrixError("available_profiles must be unique and non-empty")
    unsupported_profiles = set(allowed_profiles) - set(SUPPORTED_PROFILES)
    if unsupported_profiles:
        raise DeviceLabMatrixError(
            f"Unsupported available profiles: {sorted(unsupported_profiles)}"
        )

    raw_lanes = document.get("lanes")
    if not isinstance(raw_lanes, list) or not raw_lanes:
        raise DeviceLabMatrixError("lanes must be a non-empty JSON array")
    lanes: list[DeviceLabLane] = []
    for index, raw_lane in enumerate(raw_lanes):
        label = f"lanes[{index}]"
        lane = _mapping(raw_lane, label)
        _strict_keys(
            lane,
            {
                "id",
                "description",
                "apiLevelMin",
                "apiLevelMax",
                "manufacturers",
                "primaryAbis",
                "profiles",
                "allowedResetStrategies",
            },
            label,
        )
        lane_id = _text(lane.get("id"), f"{label}.id")
        api_min = _integer(lane.get("apiLevelMin"), f"{label}.apiLevelMin", minimum=24)
        api_max = _integer(lane.get("apiLevelMax"), f"{label}.apiLevelMax", minimum=api_min)
        manufacturers = _string_list(lane.get("manufacturers"), f"{label}.manufacturers")
        if "*" in manufacturers and manufacturers != ("*",):
            raise DeviceLabMatrixError(
                f"{label}.manufacturers wildcard must be the only value"
            )
        profiles = _string_list(lane.get("profiles"), f"{label}.profiles")
        unknown_profiles = sorted(set(profiles) - set(allowed_profiles))
        if unknown_profiles:
            raise DeviceLabMatrixError(
                f"{label}.profiles contains unavailable values: {unknown_profiles}"
            )
        lanes.append(
            DeviceLabLane(
                lane_id=lane_id,
                description=_text(lane.get("description"), f"{label}.description"),
                api_level_min=api_min,
                api_level_max=api_max,
                manufacturers=manufacturers,
                primary_abis=_string_list(
                    lane.get("primaryAbis"),
                    f"{label}.primaryAbis",
                ),
                profiles=profiles,
                allowed_reset_strategies=_allowed_reset_strategy_list(
                    lane.get("allowedResetStrategies"),
                    f"{label}.allowedResetStrategies",
                ),
            )
        )
    lane_ids = [lane.lane_id for lane in lanes]
    if len(set(lane_ids)) != len(lane_ids):
        raise DeviceLabMatrixError("lane ids must be unique")

    raw_coverage = _mapping(document.get("profileCoverage"), "profileCoverage")
    if set(raw_coverage) != set(allowed_profiles):
        raise DeviceLabMatrixError(
            "profileCoverage must contain exactly "
            f"{sorted(allowed_profiles)}; actual={sorted(raw_coverage)}"
        )
    coverage_entries: list[ProfileCoverage] = []
    for profile in allowed_profiles:
        label = f"profileCoverage.{profile}"
        raw_entry = _mapping(raw_coverage.get(profile), label)
        _strict_keys(
            raw_entry,
            {
                "minDistinctDevices",
                "minDistinctManufacturers",
                "requiredResetStrategies",
            },
            label,
        )
        minimum_devices = _integer(
            raw_entry.get("minDistinctDevices"),
            f"{label}.minDistinctDevices",
            minimum=1,
        )
        minimum_manufacturers = _integer(
            raw_entry.get("minDistinctManufacturers"),
            f"{label}.minDistinctManufacturers",
            minimum=1,
        )
        if minimum_manufacturers > minimum_devices:
            raise DeviceLabMatrixError(
                f"{label}.minDistinctManufacturers cannot exceed minDistinctDevices"
            )
        matching_lanes = [lane for lane in lanes if profile in lane.profiles]
        for left_index, left in enumerate(matching_lanes):
            for right in matching_lanes[left_index + 1 :]:
                if max(left.api_level_min, right.api_level_min) <= min(
                    left.api_level_max,
                    right.api_level_max,
                ):
                    raise DeviceLabMatrixError(
                        f"profile {profile} has overlapping API lanes "
                        f"{left.lane_id!r} and {right.lane_id!r}"
                    )
        required_resets = _reset_strategy_list(
            raw_entry.get("requiredResetStrategies"),
            f"{label}.requiredResetStrategies",
        )
        available_resets = {
            strategy
            for lane in matching_lanes
            for strategy in lane.allowed_reset_strategies
        }
        unavailable_resets = sorted(set(required_resets) - available_resets)
        if unavailable_resets:
            raise DeviceLabMatrixError(
                f"{label} requires reset strategies unavailable in its lanes: "
                f"{unavailable_resets}"
            )
        if not any(lane.manufacturers == ("*",) for lane in matching_lanes):
            available_manufacturers = {
                manufacturer.casefold()
                for lane in matching_lanes
                for manufacturer in lane.manufacturers
            }
            if len(available_manufacturers) < minimum_manufacturers:
                raise DeviceLabMatrixError(
                    f"{label} requires {minimum_manufacturers} manufacturers but "
                    f"its lanes allow only {len(available_manufacturers)}"
                )
        coverage_entries.append(
            ProfileCoverage(
                profile=profile,
                min_distinct_devices=minimum_devices,
                min_distinct_manufacturers=minimum_manufacturers,
                required_reset_strategies=required_resets,
            )
        )

    return DeviceLabMatrix(lanes=tuple(lanes), coverage=tuple(coverage_entries))


def device_lane_violations(
    device: dict[str, Any],
    lane: DeviceLabLane,
    profile: str,
    *,
    reset_strategy: str | None = None,
) -> list[str]:
    """Return every reason a physical device artifact cannot satisfy one lane."""
    violations: list[str] = []
    if device.get("isEmulator") is not False:
        violations.append("device.isEmulator must be false")
    api_level = device.get("apiLevel")
    if isinstance(api_level, bool) or not isinstance(api_level, int):
        violations.append("device.apiLevel must be an integer")
    elif not lane.api_level_min <= api_level <= lane.api_level_max:
        violations.append(
            f"API {api_level} is outside {lane.api_level_min}..{lane.api_level_max}"
        )
    manufacturer = device.get("manufacturer")
    if not isinstance(manufacturer, str) or not manufacturer.strip():
        violations.append("device.manufacturer must be non-empty")
    elif lane.manufacturers != ("*",) and manufacturer.casefold() not in {
        value.casefold() for value in lane.manufacturers
    }:
        violations.append(
            f"manufacturer {manufacturer!r} is not allowed by lane {lane.lane_id!r}"
        )
    primary_abi = device.get("primaryAbi")
    if not isinstance(primary_abi, str) or primary_abi not in lane.primary_abis:
        violations.append(
            f"primary ABI {primary_abi!r} is not allowed by lane {lane.lane_id!r}"
        )
    if profile not in lane.profiles:
        violations.append(f"profile {profile!r} is not required by lane {lane.lane_id!r}")
    if (
        reset_strategy is not None
        and reset_strategy not in lane.allowed_reset_strategies
    ):
        violations.append(
            f"reset strategy {reset_strategy!r} is not allowed by lane {lane.lane_id!r}"
        )
    return violations


def require_device_lane(
    device: dict[str, Any],
    lane: DeviceLabLane,
    profile: str,
    *,
    reset_strategy: str | None = None,
) -> None:
    """Fail before acquisition or acceptance when a device misses its selected lane."""
    violations = device_lane_violations(
        device,
        lane,
        profile,
        reset_strategy=reset_strategy,
    )
    if violations:
        raise DeviceLabMatrixError(
            f"Device does not satisfy lane {lane.lane_id!r}:\n- "
            + "\n- ".join(violations)
        )
