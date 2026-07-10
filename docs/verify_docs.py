"""Validate repository Markdown encoding and local links."""

from __future__ import annotations

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


REPO_ROOT = Path(__file__).resolve().parents[1]
LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
EXTERNAL_SCHEMES = {"http", "https", "mailto", "tel", "data"}


def markdown_files() -> list[Path]:
    """Return tracked-document candidates in a stable order."""
    files = list(REPO_ROOT.glob("*.md"))
    files.extend((REPO_ROOT / "docs").rglob("*.md"))
    return sorted(path for path in files if path.is_file())


def normalize_target(raw_target: str) -> str:
    """Remove optional Markdown title syntax and angle brackets."""
    target = raw_target.strip()
    if target.startswith("<") and ">" in target:
        return target[1 : target.index(">")]
    # Repository links do not use unescaped whitespace in paths. Content after
    # the first whitespace is therefore an optional Markdown link title.
    return target.split(maxsplit=1)[0]


def check_links(path: Path, text: str) -> list[str]:
    """Return broken local-link descriptions for one Markdown file."""
    failures: list[str] = []
    for match in LINK_PATTERN.finditer(text):
        raw_target = match.group(1)
        target = normalize_target(raw_target)
        if not target or target.startswith("#"):
            continue
        parsed = urlsplit(target)
        if parsed.scheme.lower() in EXTERNAL_SCHEMES or target.startswith("//"):
            continue
        decoded_path = unquote(parsed.path)
        if not decoded_path:
            continue
        candidate = (
            REPO_ROOT / decoded_path.lstrip("/")
            if decoded_path.startswith("/")
            else path.parent / decoded_path
        ).resolve()
        if not candidate.exists():
            line = text.count("\n", 0, match.start()) + 1
            failures.append(
                f"{path.relative_to(REPO_ROOT)}:{line}: {raw_target} -> "
                f"{candidate.relative_to(REPO_ROOT) if candidate.is_relative_to(REPO_ROOT) else candidate}"
            )
    return failures


def main() -> int:
    """Check UTF-8 decoding and every local Markdown link."""
    files = markdown_files()
    failures: list[str] = []
    link_count = 0
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            failures.append(f"{path.relative_to(REPO_ROOT)}: invalid UTF-8: {error}")
            continue
        link_count += len(LINK_PATTERN.findall(text))
        failures.extend(check_links(path, text))

    if failures:
        print("Documentation verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(f"Documentation verification passed: {len(files)} Markdown files, {link_count} links")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

