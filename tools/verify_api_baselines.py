"""Validate that every published Kotlin artifact has a checked-in ABI baseline."""

from __future__ import annotations

import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PUBLISHED_KOTLIN_MODULES = (
    "apm-anr",
    "apm-battery",
    "apm-bundle",
    "apm-core",
    "apm-crash",
    "apm-fps",
    "apm-gc-monitor",
    "apm-io",
    "apm-ipc",
    "apm-launch",
    "apm-memory",
    "apm-model",
    "apm-network",
    "apm-otel-exporter",
    "apm-plugin",
    "apm-remote-config",
    "apm-render",
    "apm-slow-method",
    "apm-sqlite",
    "apm-storage",
    "apm-thread-monitor",
    "apm-trace",
    "apm-uploader",
    "apm-webview",
)
INTENTIONALLY_EMPTY_MODULES = {"apm-bundle"}
NON_PUBLISHED_MODULES = {"apm-benchmark", "apm-sample-app"}


def baseline_path(module: str) -> Path:
    """Return the canonical binary-compatibility-validator dump path."""
    return REPOSITORY_ROOT / module / "api" / f"{module}.api"


def main() -> int:
    """Fail closed on missing, empty, or accidentally scoped ABI baselines."""
    failures: list[str] = []

    for module in PUBLISHED_KOTLIN_MODULES:
        path = baseline_path(module)
        relative = path.relative_to(REPOSITORY_ROOT)
        if not path.is_file():
            failures.append(f"missing baseline: {relative}")
            continue

        text = path.read_text(encoding="utf-8")
        if module in INTENTIONALLY_EMPTY_MODULES:
            if text:
                failures.append(
                    f"{relative} must stay empty because apm-bundle contains no classes"
                )
        elif not text.strip():
            failures.append(f"unexpected empty baseline: {relative}")
        elif "public " not in text:
            failures.append(f"baseline contains no public declarations: {relative}")

    for module in NON_PUBLISHED_MODULES:
        path = baseline_path(module)
        if path.exists():
            failures.append(
                f"non-published project must not have an ABI baseline: "
                f"{path.relative_to(REPOSITORY_ROOT)}"
            )

    expected = {baseline_path(module).resolve() for module in PUBLISHED_KOTLIN_MODULES}
    actual = {
        path.resolve()
        for path in REPOSITORY_ROOT.glob("*/api/*.api")
        if path.is_file()
    }
    for path in sorted(actual - expected):
        failures.append(
            f"unexpected baseline: {path.relative_to(REPOSITORY_ROOT.resolve())}"
        )

    if failures:
        print("API baseline verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    non_empty = len(PUBLISHED_KOTLIN_MODULES) - len(INTENTIONALLY_EMPTY_MODULES)
    print(
        "API baseline verification passed: "
        f"{len(PUBLISHED_KOTLIN_MODULES)} artifacts, "
        f"{non_empty} non-empty ABI surfaces"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
