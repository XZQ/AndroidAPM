"""Run an A/B AndroidAPM overhead and offline-restart campaign on one device."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RESULT_SCHEMA_VERSION = 2
DEFAULT_PACKAGE = "com.apm.sample.debug"
DEFAULT_ACTIVITY = "com.apm.sample.MainActivity"
RESULT_FILE = "files/apm-device-soak-result.json"
EMULATOR_MARKERS = ("emulator", "generic", "sdk_gphone", "emu64")
CLEAR_DATA_PERMISSION_MARKERS = (
    "securityexception",
    "android.permission.clear_app_user_data",
)
PROFILE_DEFAULTS = {
    "smoke": {
        "durationSeconds": 30,
        "controlDurationSeconds": 5,
        "restartIntervalSeconds": 15,
        "sampleIntervalSeconds": 2,
        "eventsPerSecond": 10,
    },
    "24h": {
        "durationSeconds": 24 * 60 * 60,
        "controlDurationSeconds": 60,
        "restartIntervalSeconds": 60 * 60,
        "sampleIntervalSeconds": 60,
        "eventsPerSecond": 10,
    },
    "72h": {
        "durationSeconds": 72 * 60 * 60,
        "controlDurationSeconds": 60,
        "restartIntervalSeconds": 6 * 60 * 60,
        "sampleIntervalSeconds": 5 * 60,
        "eventsPerSecond": 10,
    },
}


class DeviceSoakRunError(RuntimeError):
    """Raised when ADB or evidence collection cannot complete safely."""


@dataclass(frozen=True)
class ProcessSample:
    """One timestamped process/resource snapshot."""

    elapsed_seconds: float
    cpu_jiffies: int
    pss_kb: int
    disk_bytes: int
    thermal_status: int
    charge_counter_uah: int | None
    uid_power_mah: float | None


class Adb:
    """Small checked subprocess wrapper bound to one explicit device serial."""

    def __init__(self, executable: str, serial: str) -> None:
        """Bind every command to the selected device."""
        self._base = [executable, "-s", serial]

    def run(
        self,
        *arguments: str,
        timeout: float = 30,
        check: bool = True,
    ) -> str:
        """Execute one ADB command and return decoded standard output."""
        completed = subprocess.run(
            [*self._base, *arguments],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if check and completed.returncode != 0:
            detail = (completed.stderr or completed.stdout).strip()
            raise DeviceSoakRunError(
                f"ADB command failed ({completed.returncode}): {' '.join(arguments)}: {detail}"
            )
        return completed.stdout

    def shell(self, *arguments: str, timeout: float = 30, check: bool = True) -> str:
        """Execute one argument-safe remote shell command."""
        return self.run("shell", *arguments, timeout=timeout, check=check)


def _finite_number(value: float, label: str) -> float:
    """Reject non-finite values before serializing a gate artifact."""
    if not math.isfinite(value):
        raise DeviceSoakRunError(f"{label} is not finite")
    return value


def _sha256(path: Path) -> str:
    """Hash the exact APK installed for traceable evidence."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _reports_success(output: str) -> bool:
    """Accept only an explicit standalone ADB success line."""
    return any(line.strip() == "Success" for line in output.splitlines())


def _install_apk(adb: Adb, apk: Path, replace: bool) -> None:
    """Install the selected sample APK and require an explicit success marker."""
    arguments = ["install"]
    if replace:
        arguments.append("-r")
    arguments.append(str(apk))
    output = adb.run(*arguments, timeout=180).strip()
    if not _reports_success(output):
        raise DeviceSoakRunError(f"APK install did not report Success: {output}")


def _reset_sample_app_data(adb: Adb, package: str, apk: Path) -> str:
    """Reset only the selected sample package, with a policy-safe reinstall fallback."""
    try:
        output = adb.shell("pm", "clear", package, timeout=60).strip()
    except DeviceSoakRunError as error:
        detail = str(error).lower()
        if not all(marker in detail for marker in CLEAR_DATA_PERMISSION_MARKERS):
            raise

        # Some production OEM builds deny CLEAR_APP_USER_DATA to the ADB shell.
        uninstall_output = adb.run("uninstall", package, timeout=180).strip()
        if not _reports_success(uninstall_output):
            raise DeviceSoakRunError(
                f"Sample uninstall did not report Success for {package}: "
                f"{uninstall_output}"
            ) from error
        _install_apk(adb, apk, replace=False)
        return "uninstall-reinstall"

    if not _reports_success(output):
        raise DeviceSoakRunError(
            f"App-data reset did not report Success for {package}: {output}"
        )
    return "pm-clear"


def _getprop(adb: Adb, name: str) -> str:
    """Read and trim one Android system property."""
    return adb.shell("getprop", name).strip()


def _device_identity(adb: Adb, serial: str) -> dict[str, Any]:
    """Collect stable device identity and reject ambiguous emulator detection later."""
    fingerprint = _getprop(adb, "ro.build.fingerprint")
    model = _getprop(adb, "ro.product.model")
    device = _getprop(adb, "ro.product.device")
    qemu = _getprop(adb, "ro.kernel.qemu")
    combined = " ".join((fingerprint, model, device)).lower()
    return {
        "serial": serial,
        "model": model,
        "device": device,
        "fingerprint": fingerprint,
        "apiLevel": int(_getprop(adb, "ro.build.version.sdk")),
        "isEmulator": qemu == "1" or any(marker in combined for marker in EMULATOR_MARKERS),
    }


def _parse_startup_ms(output: str) -> float:
    """Read ActivityManager TotalTime from one cold-start result."""
    match = re.search(r"^TotalTime:\s*(\d+)\s*$", output, re.MULTILINE)
    if match is None:
        raise DeviceSoakRunError(f"ActivityManager output lacks TotalTime: {output.strip()}")
    return float(match.group(1))


def _process_id(adb: Adb, package: str) -> int:
    """Resolve exactly one running application process identifier."""
    output = adb.shell("pidof", package).strip()
    candidates = [value for value in output.split() if value.isdigit()]
    if len(candidates) != 1:
        raise DeviceSoakRunError(f"Expected one PID for {package}, got {output!r}")
    return int(candidates[0])


def _cpu_jiffies(adb: Adb, package: str, pid: int) -> int:
    """Read app-owned process user+system ticks through run-as."""
    stat = adb.shell("run-as", package, "cat", f"/proc/{pid}/stat").strip()
    close_parenthesis = stat.rfind(")")
    if close_parenthesis < 0:
        raise DeviceSoakRunError("Malformed /proc process stat")
    fields_after_name = stat[close_parenthesis + 2 :].split()
    if len(fields_after_name) <= 12:
        raise DeviceSoakRunError("Incomplete /proc process stat")
    return int(fields_after_name[11]) + int(fields_after_name[12])


def _pss_kb(adb: Adb, package: str) -> int:
    """Read total proportional set size from Android meminfo."""
    output = adb.shell("dumpsys", "meminfo", package, timeout=60)
    patterns = (
        r"TOTAL PSS:\s*([0-9,]+)",
        r"^\s*TOTAL\s+([0-9,]+)\s+",
    )
    for pattern in patterns:
        match = re.search(pattern, output, re.MULTILINE)
        if match is not None:
            return int(match.group(1).replace(",", ""))
    raise DeviceSoakRunError("dumpsys meminfo lacks total PSS")


def _disk_bytes(adb: Adb, package: str) -> int:
    """Measure app-private database/files/cache allocation without root."""
    output = adb.shell(
        "run-as",
        package,
        "du",
        "-sk",
        "databases",
        "files",
        "cache",
        check=False,
    )
    sizes_kb = [int(match.group(1)) for match in re.finditer(r"^\s*(\d+)\s+", output, re.MULTILINE)]
    if not sizes_kb:
        raise DeviceSoakRunError("run-as du returned no app-private size evidence")
    return sum(sizes_kb) * 1024


def _thermal_status(adb: Adb) -> int:
    """Read Android's current coarse thermal throttling status."""
    output = adb.shell("dumpsys", "thermalservice", timeout=60)
    match = re.search(r"(?:Thermal\s+)?Status:\s*(\d+)", output, re.IGNORECASE)
    if match is None:
        raise DeviceSoakRunError("dumpsys thermalservice lacks thermal status")
    return int(match.group(1))


def _charge_counter_uah(adb: Adb) -> int | None:
    """Read the device charge counter as environmental evidence when supported."""
    output = adb.shell("dumpsys", "battery")
    match = re.search(r"charge counter:\s*(-?\d+)", output, re.IGNORECASE)
    if match is None:
        return None
    value = int(match.group(1))
    return value if value >= 0 else None


def _package_uid(adb: Adb, package: str) -> int:
    """Resolve the installed Linux UID used by batterystats attribution."""
    output = adb.shell("cmd", "package", "list", "packages", "-U", package)
    match = re.search(rf"package:{re.escape(package)}\s+uid:(\d+)", output)
    if match is None:
        raise DeviceSoakRunError(f"Cannot resolve package UID for {package}")
    return int(match.group(1))


def _clock_ticks_per_second(adb: Adb) -> int:
    """Read the device kernel USER_HZ used by `/proc/<pid>/stat`."""
    output = adb.shell("getconf", "CLK_TCK").strip()
    if not output.isdigit() or int(output) <= 0:
        raise DeviceSoakRunError(f"Cannot resolve device CLK_TCK: {output!r}")
    return int(output)


def _uid_label(uid: int) -> str:
    """Convert a Linux application UID into dumpsys' uXaY display label."""
    user_id = uid // 100_000
    app_id = uid % 100_000
    if app_id < 10_000:
        return str(uid)
    return f"u{user_id}a{app_id - 10_000}"


def _uid_power_mah(adb: Adb, package: str, uid: int) -> float | None:
    """Read cumulative Android batterystats estimated power for this app UID."""
    output = adb.shell("dumpsys", "batterystats", "--charged", package, timeout=120, check=False)
    labels = (str(uid), _uid_label(uid))
    for label in labels:
        match = re.search(
            rf"^\s*Uid\s+{re.escape(label)}:\s*([0-9]+(?:\.[0-9]+)?)",
            output,
            re.IGNORECASE | re.MULTILINE,
        )
        if match is not None:
            return float(match.group(1))
    return None


def _sample(adb: Adb, package: str, uid: int, started: float) -> ProcessSample:
    """Capture one complete resource sample or fail rather than inventing zeroes."""
    pid = _process_id(adb, package)
    return ProcessSample(
        elapsed_seconds=time.monotonic() - started,
        cpu_jiffies=_cpu_jiffies(adb, package, pid),
        pss_kb=_pss_kb(adb, package),
        disk_bytes=_disk_bytes(adb, package),
        thermal_status=_thermal_status(adb),
        charge_counter_uah=_charge_counter_uah(adb),
        uid_power_mah=_uid_power_mah(adb, package, uid),
    )


def _configure_mode(adb: Adb, component: str, sdk_enabled: bool, offline: bool) -> None:
    """Persist the next cold-process A/B mode through the exported sample Activity."""
    output = adb.shell(
        "am",
        "start",
        "-S",
        "-W",
        "-n",
        component,
        "--ez",
        "apm_soak_set_sdk_enabled",
        str(sdk_enabled).lower(),
        "--ez",
        "apm_soak_set_offline_collector",
        str(offline).lower(),
        timeout=60,
    )
    _parse_startup_ms(output)
    adb.shell("am", "force-stop", component.split("/", 1)[0])


def _launch_probe(
    adb: Adb,
    component: str,
    duration_seconds: int,
    events_per_second: int,
    run_id: str,
) -> float:
    """Cold-start one measured process segment and return ActivityManager startup time."""
    output = adb.shell(
        "am",
        "start",
        "-S",
        "-W",
        "-n",
        component,
        "--el",
        "apm_soak_duration_seconds",
        str(duration_seconds),
        "--ei",
        "apm_soak_events_per_second",
        str(events_per_second),
        "--es",
        "apm_soak_run_id",
        run_id,
        timeout=60,
    )
    return _parse_startup_ms(output)


def _read_probe_result(adb: Adb, package: str, run_id: str) -> dict[str, Any]:
    """Poll the app-private result until the expected segment identity appears."""
    deadline = time.monotonic() + 20
    last_output = ""
    while time.monotonic() < deadline:
        last_output = adb.run(
            "exec-out",
            "run-as",
            package,
            "cat",
            RESULT_FILE,
            timeout=30,
            check=False,
        ).strip()
        try:
            document = json.loads(last_output)
        except json.JSONDecodeError:
            document = None
        if isinstance(document, dict) and document.get("runId") == run_id:
            return document
        time.sleep(1)
    raise DeviceSoakRunError(
        f"Timed out waiting for result {run_id}; last app-private output={last_output!r}"
    )


def _run_segment(
    adb: Adb,
    package: str,
    component: str,
    uid: int,
    duration_seconds: int,
    events_per_second: int,
    sample_interval_seconds: int,
    label: str,
) -> dict[str, Any]:
    """Run one cold process segment and retain raw resource evidence."""
    run_id = f"{label}-{uuid.uuid4().hex[:12]}"
    startup_ms = _launch_probe(adb, component, duration_seconds, events_per_second, run_id)
    started = time.monotonic()
    samples: list[ProcessSample] = [_sample(adb, package, uid, started)]
    deadline = started + duration_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(float(sample_interval_seconds), remaining))
        samples.append(_sample(adb, package, uid, started))
    probe = _read_probe_result(adb, package, run_id)
    adb.shell("am", "force-stop", package)
    return {
        "runId": run_id,
        "requestedDurationSeconds": duration_seconds,
        "startupMs": startup_ms,
        "probe": probe,
        "samples": [sample.__dict__ for sample in samples],
    }


def _segment_cpu_percent(
    segment: dict[str, Any],
    clock_ticks_per_second: int,
) -> tuple[float, float]:
    """Return CPU percent and wall weight from cumulative process ticks."""
    samples = segment["samples"]
    first = samples[0]
    last = samples[-1]
    wall_seconds = float(last["elapsed_seconds"]) - float(first["elapsed_seconds"])
    if wall_seconds <= 0:
        raise DeviceSoakRunError("A segment lacks a positive CPU sampling interval")
    cpu_seconds = (
        int(last["cpu_jiffies"]) - int(first["cpu_jiffies"])
    ) / float(clock_ticks_per_second)
    return max(0.0, cpu_seconds / wall_seconds * 100.0), wall_seconds


def _summarize(
    control: dict[str, Any],
    segments: list[dict[str, Any]],
    external_power_mah: float | None,
    clock_ticks_per_second: int,
) -> dict[str, Any]:
    """Aggregate raw process segments into the stable verifier contract."""
    startups = [float(segment["startupMs"]) for segment in segments]
    probes = [segment["probe"] for segment in segments]
    all_samples = [sample for segment in segments for sample in segment["samples"]]
    control_cpu, _ = _segment_cpu_percent(control, clock_ticks_per_second)
    cpu_pairs = [
        _segment_cpu_percent(segment, clock_ticks_per_second)
        for segment in segments
    ]
    cpu_weight = sum(weight for _, weight in cpu_pairs)
    enabled_cpu_average = sum(value * weight for value, weight in cpu_pairs) / cpu_weight
    cpu_delta = enabled_cpu_average - control_cpu
    observed_seconds = sum(float(probe["observedDurationMs"]) for probe in probes) / 1000.0
    first_sample = all_samples[0]
    last_sample = all_samples[-1]
    pss_growth = max(0, max(int(sample["pss_kb"]) for sample in all_samples) - int(first_sample["pss_kb"]))
    disk_growth = max(
        0,
        max(int(sample["disk_bytes"]) for sample in all_samples)
        - int(first_sample["disk_bytes"]),
    )

    power_rate: float | None = None
    power_source: str | None = None
    observed_hours = observed_seconds / 3600.0
    if external_power_mah is not None:
        power_rate = external_power_mah / observed_hours
        power_source = "external-meter"
    else:
        first_power = first_sample.get("uid_power_mah")
        last_power = last_sample.get("uid_power_mah")
        if first_power is not None and last_power is not None:
            power_rate = max(0.0, float(last_power) - float(first_power)) / observed_hours
            power_source = "android-batterystats-uid"

    return {
        "observedDurationSeconds": _finite_number(observed_seconds, "observed duration"),
        "processStarts": len(segments),
        "startupControlMs": float(control["startupMs"]),
        "startupMedianMs": statistics.median(startups),
        "startupDeltaMs": statistics.median(startups) - float(control["startupMs"]),
        "sdkInitMaxMs": max(float(probe["initDurationNs"]) for probe in probes) / 1_000_000.0,
        "mainThreadOperationP95Us": max(float(probe["operationP95Ns"]) for probe in probes) / 1000.0,
        # Preserve the original absolute gate field while adding explicit A/B attribution.
        "cpuAveragePercent": _finite_number(enabled_cpu_average, "enabled CPU average"),
        "cpuControlPercent": _finite_number(control_cpu, "control CPU average"),
        "cpuEnabledAveragePercent": _finite_number(
            enabled_cpu_average,
            "enabled CPU average",
        ),
        "cpuDeltaPercent": _finite_number(cpu_delta, "CPU delta"),
        "pssGrowthKb": pss_growth,
        "diskGrowthBytes": disk_growth,
        "chargeConsumptionMahPerHour": (
            _finite_number(power_rate, "power rate") if power_rate is not None else None
        ),
        "powerSource": power_source,
        "thermalMaxStatus": max(int(sample["thermal_status"]) for sample in all_samples),
    }


def _positive_int(value: str) -> int:
    """Parse one positive integer CLI override."""
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be greater than zero")
    return parsed


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Parse campaign, device, and optional acquisition overrides."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=tuple(PROFILE_DEFAULTS), required=True)
    parser.add_argument("--serial", required=True, help="Exact adb device serial")
    parser.add_argument("--apk", type=Path, required=True, help="Debug sample APK to install")
    parser.add_argument("--output", type=Path, required=True, help="Result JSON path")
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY)
    parser.add_argument(
        "--reset-app-data",
        action="store_true",
        required=True,
        help="Acknowledge clearing only the selected sample package before acquisition",
    )
    parser.add_argument("--duration-seconds", type=_positive_int)
    parser.add_argument("--control-duration-seconds", type=_positive_int)
    parser.add_argument("--restart-interval-seconds", type=_positive_int)
    parser.add_argument("--sample-interval-seconds", type=_positive_int)
    parser.add_argument("--events-per-second", type=_positive_int)
    parser.add_argument(
        "--external-power-mah",
        type=float,
        help="Optional app-attributed mAh from a calibrated external meter for the enabled phase",
    )
    return parser.parse_args(argv)


def run(args: argparse.Namespace) -> dict[str, Any]:
    """Install the APK, execute control/enabled phases, and build one raw artifact."""
    defaults = PROFILE_DEFAULTS[args.profile]
    duration = args.duration_seconds or defaults["durationSeconds"]
    control_duration = args.control_duration_seconds or defaults["controlDurationSeconds"]
    restart_interval = args.restart_interval_seconds or defaults["restartIntervalSeconds"]
    sample_interval = args.sample_interval_seconds or defaults["sampleIntervalSeconds"]
    events_per_second = args.events_per_second or defaults["eventsPerSecond"]
    if args.external_power_mah is not None and (
        not math.isfinite(args.external_power_mah) or args.external_power_mah < 0
    ):
        raise DeviceSoakRunError("--external-power-mah must be finite and non-negative")
    if not args.apk.is_file():
        raise DeviceSoakRunError(f"APK does not exist: {args.apk}")

    adb = Adb(args.adb, args.serial)
    adb.run("get-state")
    device = _device_identity(adb, args.serial)
    _install_apk(adb, args.apk, replace=True)
    reset_strategy = _reset_sample_app_data(adb, args.package, args.apk)
    uid = _package_uid(adb, args.package)
    clock_ticks_per_second = _clock_ticks_per_second(adb)
    component = f"{args.package}/{args.activity}"

    _configure_mode(adb, component, sdk_enabled=False, offline=False)
    control = _run_segment(
        adb,
        args.package,
        component,
        uid,
        control_duration,
        events_per_second,
        min(sample_interval, control_duration),
        "control",
    )
    _configure_mode(adb, component, sdk_enabled=True, offline=True)

    segments: list[dict[str, Any]] = []
    remaining = duration
    while remaining > 0:
        segment_duration = min(restart_interval, remaining)
        segments.append(
            _run_segment(
                adb,
                args.package,
                component,
                uid,
                segment_duration,
                events_per_second,
                min(sample_interval, segment_duration),
                f"enabled-{len(segments) + 1}",
            )
        )
        remaining -= segment_duration

    return {
        "schemaVersion": RESULT_SCHEMA_VERSION,
        "profile": args.profile,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apkSha256": _sha256(args.apk),
        "device": device,
        "config": {
            "requestedDurationSeconds": duration,
            "controlDurationSeconds": control_duration,
            "restartIntervalSeconds": restart_interval,
            "sampleIntervalSeconds": sample_interval,
            "eventsPerSecond": events_per_second,
            "offlineCollector": True,
            "clockTicksPerSecond": clock_ticks_per_second,
            "externalPowerMah": args.external_power_mah,
            "appDataResetStrategy": reset_strategy,
        },
        "control": control,
        "segments": segments,
        "summary": _summarize(
            control,
            segments,
            args.external_power_mah,
            clock_ticks_per_second,
        ),
    }


def main(argv: list[str] | None = None) -> int:
    """Run one campaign and atomically replace its requested result artifact."""
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = run(args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
        temporary.replace(args.output)
        print(f"Device-soak result written to {args.output}")
        print(json.dumps(result["summary"], indent=2, sort_keys=True))
        return 0
    except (DeviceSoakRunError, OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"Device-soak run failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
