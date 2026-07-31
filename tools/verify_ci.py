"""Run the repository's platform-neutral client verification gate."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
MINIMUM_JAVA_MAJOR = 17
JAVA_VERSION_PATTERN = re.compile(r'version "(?P<version>[^"]+)"')


def java_executable() -> Path:
    """Resolve the Java executable from JAVA_HOME or PATH."""
    executable_name = "java.exe" if os.name == "nt" else "java"
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / executable_name
        if candidate.is_file():
            return candidate
        raise RuntimeError(
            f"JAVA_HOME points to {java_home!r}, but {candidate} does not exist."
        )

    resolved = shutil.which("java")
    if resolved:
        return Path(resolved)
    raise RuntimeError("No Java executable was found. Set JAVA_HOME to JDK 17 or newer.")


def parse_java_major(version_output: str) -> int:
    """Parse legacy and modern Java version strings into a major version."""
    match = JAVA_VERSION_PATTERN.search(version_output)
    if match is None:
        raise RuntimeError(f"Unable to parse Java version output: {version_output.strip()}")

    version = match.group("version")
    first_component = int(version.split(".", maxsplit=1)[0])
    if first_component == 1:
        components = version.split(".")
        if len(components) < 2:
            raise RuntimeError(f"Unable to parse legacy Java version: {version}")
        return int(components[1])
    return first_component


def require_supported_java() -> None:
    """Fail before Gradle configuration when the selected runtime is unsupported."""
    executable = java_executable()
    completed = subprocess.run(
        [str(executable), "-version"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    output = f"{completed.stdout}\n{completed.stderr}".strip()
    if completed.returncode != 0:
        raise RuntimeError(f"{executable} -version failed:\n{output}")

    major = parse_java_major(output)
    if major < MINIMUM_JAVA_MAJOR:
        raise RuntimeError(
            f"AndroidAPM CI requires JDK {MINIMUM_JAVA_MAJOR}+, but JAVA_HOME/PATH "
            f"selected Java {major}: {executable}"
        )
    print(f"[environment] Java {major}: {executable}", flush=True)


def run_step(label: str, command: Sequence[str]) -> None:
    """Run one fail-fast verification step from the repository root."""
    print(f"[verify] {label}: {' '.join(command)}", flush=True)
    completed = subprocess.run(command, cwd=REPOSITORY_ROOT, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"{label} failed with exit code {completed.returncode}.")


def gradle_wrapper() -> str:
    """Return the checked-in Gradle wrapper executable for the current platform."""
    return "gradlew.bat" if os.name == "nt" else "./gradlew"


def main() -> int:
    """Execute ABI checks, tests, and documentation verification."""
    try:
        require_supported_java()
        wrapper = gradle_wrapper()
        run_step(
            "root public API compatibility",
            [wrapper, "apiCheck", "--no-daemon"],
        )
        run_step(
            "included Gradle plugin public API compatibility",
            [wrapper, "-p", "apm-plugin", "apiCheck", "--no-daemon"],
        )
        run_step(
            "API baseline integrity",
            [sys.executable, "tools/verify_api_baselines.py"],
        )
        run_step(
            "device-lab matrix policy",
            [
                sys.executable,
                "apm-benchmark/verify_device_matrix.py",
                "--matrix",
                "apm-benchmark/device-lab-matrix.json",
                "--budgets",
                "apm-benchmark/device-soak-budgets.json",
            ],
        )
        run_step(
            "benchmark and device-lab host tests",
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                "apm-benchmark/tests",
                "-p",
                "test_*.py",
            ],
        )
        run_step(
            "root Android and model tests",
            [
                wrapper,
                "testDebugUnitTest",
                ":apm-model:test",
                "--rerun-tasks",
                "--no-daemon",
            ],
        )
        run_step(
            "included Gradle plugin tests",
            [
                wrapper,
                "-p",
                "apm-plugin",
                "test",
                "--rerun-tasks",
                "--no-daemon",
            ],
        )
        run_step(
            "documentation verification",
            [sys.executable, "docs/verify_docs.py"],
        )
    except (OSError, RuntimeError, ValueError) as error:
        print(f"[verify] FAILED: {error}", file=sys.stderr, flush=True)
        return 1

    print("[verify] PASSED", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
