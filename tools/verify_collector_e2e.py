"""Run the real Android V2 uploader against a live local Collector process."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import tomllib
import urllib.error
import urllib.request
from collections.abc import Sequence
from contextlib import closing
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PROBE_SOURCE = REPOSITORY_ROOT / "tools" / "collector_e2e" / "CollectorE2eProbe.java"
SERVER_MARKER = Path("src/androidapm_server/main.py")
EXPECTED_EVENT_IDS = {"collector-e2e-event-1", "collector-e2e-event-2"}


def parse_arguments() -> argparse.Namespace:
    """Parse the explicit adjacent server checkout used by the integration gate."""
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--server-repo",
        type=Path,
        required=True,
        help="AndroidAPM-Server checkout containing Collector V2 support",
    )
    return parser.parse_args()


def run(
    command: Sequence[str],
    *,
    cwd: Path,
    environment: dict[str, str] | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    """Run one checked command with readable diagnostics."""
    print(f"[collector-e2e] {' '.join(command)}", flush=True)
    return subprocess.run(  # noqa: S603 - argv is explicit and shell execution is disabled.
        command,
        cwd=cwd,
        env=environment,
        check=True,
        capture_output=capture,
        text=True,
    )


def executable(directory: Path, name: str) -> Path:
    """Resolve a platform-specific executable in a virtual environment or JDK."""
    suffix = ".exe" if os.name == "nt" else ""
    candidate = directory / f"{name}{suffix}"
    if not candidate.is_file():
        raise RuntimeError(f"Required executable is missing: {candidate}")
    return candidate


def java_home() -> Path:
    """Resolve a complete JDK from JAVA_HOME."""
    configured = os.environ.get("JAVA_HOME")
    if not configured:
        raise RuntimeError("JAVA_HOME must point to JDK 17 or newer")
    home = Path(configured).resolve()
    executable(home / "bin", "java")
    executable(home / "bin", "javac")
    return home


def kotlin_stdlib() -> Path:
    """Locate the exact Kotlin runtime declared by the version catalog."""
    catalog = tomllib.loads(
        (REPOSITORY_ROOT / "gradle" / "libs.versions.toml").read_text(
            encoding="utf-8"
        )
    )
    version = str(catalog["versions"]["kotlin"])
    candidates = sorted(
        (
            Path.home()
            / ".gradle"
            / "caches"
            / "modules-2"
            / "files-2.1"
            / "org.jetbrains.kotlin"
            / "kotlin-stdlib"
            / version
        ).glob(f"*/kotlin-stdlib-{version}.jar")
    )
    if len(candidates) != 1:
        raise RuntimeError(
            f"Expected one Kotlin stdlib {version} JAR, found {candidates}"
        )
    return candidates[0]


def available_port() -> int:
    """Reserve and release one loopback port for the short-lived server."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_until_ready(port: int, process: subprocess.Popen[str], log_path: Path) -> None:
    """Wait for the server readiness endpoint or fail with its captured log."""
    deadline = time.monotonic() + 20
    url = f"http://127.0.0.1:{port}/health/ready"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            break
        try:
            with urllib.request.urlopen(url, timeout=1) as response:  # noqa: S310
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(0.1)
    log = log_path.read_text(encoding="utf-8", errors="replace")
    raise RuntimeError(f"Collector did not become ready.\n{log}")


def validate_database(database: Path) -> None:
    """Prove typed persistence and tenant-scoped deduplication after replay."""
    with closing(sqlite3.connect(database)) as connection:
        rows = connection.execute(
            """
            SELECT event_id, protocol, schema_version, app_version, payload_json
            FROM inbox_events
            ORDER BY event_id
            """
        ).fetchall()
    if len(rows) != 2 or {row[0] for row in rows} != EXPECTED_EVENT_IDS:
        raise RuntimeError(f"Expected two unique durable events after replay, found {rows}")
    for _, protocol, schema_version, app_version, _ in rows:
        if (protocol, schema_version, app_version) != (
            "protobuf_envelope_v2",
            "2",
            "2.4.1",
        ):
            raise RuntimeError("Durable Collector metadata does not match the V2 request")

    payload = json.loads(rows[0][4])
    fields = payload["fields"]
    field_types = payload["field_types"]
    expected_types = {
        "nullValue": "NULL",
        "stringValue": "STRING",
        "booleanValue": "BOOLEAN",
        "byteValue": "BYTE",
        "shortValue": "SHORT",
        "intValue": "INT",
        "longValue": "LONG",
        "floatValue": "FLOAT",
        "doubleValue": "DOUBLE",
        "floatNaN": "FLOAT",
        "doublePositiveInfinity": "DOUBLE",
        "charValue": "CHAR",
        "bigIntegerValue": "BIG_INTEGER",
        "bigDecimalValue": "BIG_DECIMAL",
    }
    if field_types != expected_types:
        raise RuntimeError(f"Typed scalar discriminators changed: {field_types}")
    if fields["nullValue"] is not None or fields["booleanValue"] is not True:
        raise RuntimeError(f"Typed null/boolean values changed: {fields}")
    if fields["longValue"] != 9_223_372_036_854_775_807:
        raise RuntimeError(f"Typed long value changed: {fields['longValue']}")
    if fields["floatNaN"] != "NaN" or fields["doublePositiveInfinity"] != "Infinity":
        raise RuntimeError("Non-finite scalars were not retained as JSON-safe typed text")
    if fields["bigIntegerValue"] != "123456789012345678901234567890":
        raise RuntimeError("Arbitrary-precision integer was not retained as exact text")
    if fields["bigDecimalValue"] != "1234567890.0000000001":
        raise RuntimeError("Arbitrary-precision decimal was not retained as exact text")
    if (
        payload["unknown"]["resource.installationId"]
        != "anonymous-e2e-installation"
    ):
        raise RuntimeError("Standard resource context was not durably retained")


def main() -> int:
    """Build, launch, upload twice, and inspect durable test-only storage."""
    arguments = parse_arguments()
    server_repo = arguments.server_repo.resolve()
    if not (server_repo / SERVER_MARKER).is_file():
        print(f"Invalid AndroidAPM-Server checkout: {server_repo}", file=sys.stderr)
        return 2

    try:
        selected_java_home = java_home()
        environment = os.environ.copy()
        environment["JAVA_HOME"] = str(selected_java_home)
        environment["PATH"] = (
            f"{selected_java_home / 'bin'}{os.pathsep}{environment.get('PATH', '')}"
        )
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        run(
            [
                wrapper,
                ":apm-model:jar",
                ":apm-uploader:bundleLibRuntimeToJarDebug",
                "--no-daemon",
            ],
            cwd=REPOSITORY_ROOT,
            environment=environment,
        )
        run(["uv", "sync", "--all-groups", "--frozen"], cwd=server_repo)

        server_python = executable(
            server_repo / ".venv" / ("Scripts" if os.name == "nt" else "bin"),
            "python",
        )
        model_candidates = sorted(
            path
            for path in (REPOSITORY_ROOT / "apm-model" / "build" / "libs").glob(
                "apm-model-*.jar"
            )
            if not path.name.endswith("-sources.jar")
        )
        if len(model_candidates) != 1:
            raise RuntimeError(f"Expected one apm-model runtime JAR, found {model_candidates}")
        model_jar = model_candidates[0]
        uploader_jar = (
            REPOSITORY_ROOT
            / "apm-uploader"
            / "build"
            / "intermediates"
            / "runtime_library_classes_jar"
            / "debug"
            / "bundleLibRuntimeToJarDebug"
            / "classes.jar"
        )
        classpath_entries = [model_jar, uploader_jar, kotlin_stdlib()]
        for path in classpath_entries:
            if not path.is_file():
                raise RuntimeError(f"Required runtime JAR is missing: {path}")

        with tempfile.TemporaryDirectory(prefix="androidapm-collector-e2e-") as temporary:
            work = Path(temporary)
            database = work / "collector.db"
            database_url = f"sqlite+aiosqlite:///{database.as_posix()}"
            server_environment = os.environ.copy()
            server_environment.update(
                {
                    "APM_DATABASE_URL": database_url,
                    "APM_ENVIRONMENT": "e2e",
                    "APM_EXPORT_ENABLED": "false",
                    "APM_INGEST_ENABLED": "true",
                }
            )
            run(
                [str(server_python), "-m", "alembic", "upgrade", "head"],
                cwd=server_repo,
                environment=server_environment,
            )
            key_result = run(
                [
                    str(server_python),
                    "-m",
                    "androidapm_server.admin",
                    "create-ingest-key",
                    "--tenant-id",
                    "collector-e2e",
                    "--tenant-name",
                    "Collector E2E",
                    "--app-id",
                    "com.example.collector.e2e",
                    "--environment",
                    "e2e",
                ],
                cwd=server_repo,
                environment=server_environment,
                capture=True,
            )
            ingest_key = key_result.stdout.strip().splitlines()[-1]
            if not ingest_key.startswith("apm1_"):
                raise RuntimeError("Admin CLI did not return a one-time ingest key")

            port = available_port()
            log_path = work / "collector.log"
            with log_path.open("w", encoding="utf-8") as log:
                process = subprocess.Popen(  # noqa: S603 - resolved venv Python, fixed argv.
                    [
                        str(server_python),
                        "-m",
                        "uvicorn",
                        "androidapm_server.main:app",
                        "--host",
                        "127.0.0.1",
                        "--port",
                        str(port),
                    ],
                    cwd=server_repo,
                    env=server_environment,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    text=True,
                )
                try:
                    wait_until_ready(port, process, log_path)
                    classes = work / "classes"
                    classes.mkdir()
                    compile_classpath = os.pathsep.join(str(path) for path in classpath_entries)
                    run(
                        [
                            str(executable(selected_java_home / "bin", "javac")),
                            "-encoding",
                            "UTF-8",
                            "-cp",
                            compile_classpath,
                            "-d",
                            str(classes),
                            str(PROBE_SOURCE),
                        ],
                        cwd=REPOSITORY_ROOT,
                        environment=environment,
                    )
                    runtime_classpath = os.pathsep.join(
                        [str(classes), *[str(path) for path in classpath_entries]]
                    )
                    probe_environment = environment.copy()
                    probe_environment["APM_E2E_INGEST_KEY"] = ingest_key
                    probe = run(
                        [
                            str(executable(selected_java_home / "bin", "java")),
                            "-cp",
                            runtime_classpath,
                            "com.apm.e2e.CollectorE2eProbe",
                            f"http://127.0.0.1:{port}/v1/events",
                        ],
                        cwd=REPOSITORY_ROOT,
                        environment=probe_environment,
                        capture=True,
                    )
                    if "COLLECTOR_E2E_PROBE_PASSED" not in probe.stdout:
                        raise RuntimeError(f"Uploader probe did not finish cleanly: {probe.stdout}")
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            validate_database(database)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[collector-e2e] FAILED: {error}", file=sys.stderr, flush=True)
        return 1

    print(
        "[collector-e2e] PASSED: real V2 uploader, Gzip HTTP, exact ACK, "
        "typed persistence, and replay deduplication",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
