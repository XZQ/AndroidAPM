"""Validate strict Gradle dependency-verification policy in every build root."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
METADATA_PATHS = (
    Path("gradle/verification-metadata.xml"),
    Path("apm-plugin/gradle/verification-metadata.xml"),
    Path("build-logic/gradle/verification-metadata.xml"),
    Path("smoke-tests/maven-consumer/gradle/verification-metadata.xml"),
)
CONSUMER_PATH = Path("smoke-tests/maven-consumer/gradle/verification-metadata.xml")
EXPECTED_CONSUMER_TRUST = {"com.apm", "com.apm.slow-method"}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class VerificationMetadataError(RuntimeError):
    """Raised when a dependency-verification policy is incomplete or weakened."""


def local_name(tag: str) -> str:
    """Return an XML local name without its namespace."""
    return tag.split("}", maxsplit=1)[-1]


def children(element: ElementTree.Element, name: str) -> list[ElementTree.Element]:
    """Find direct XML children by local name."""
    return [child for child in element if local_name(child.tag) == name]


def require_single_child(
    element: ElementTree.Element,
    name: str,
    path: Path,
) -> ElementTree.Element:
    """Return exactly one required direct child."""
    matches = children(element, name)
    if len(matches) != 1:
        raise VerificationMetadataError(
            f"{path}: expected exactly one <{name}>, found {len(matches)}"
        )
    return matches[0]


def validate_metadata(path: Path) -> int:
    """Validate strict SHA-256 coverage and narrowly scoped trust policy."""
    absolute = REPOSITORY_ROOT / path
    if not absolute.is_file():
        raise VerificationMetadataError(f"missing dependency metadata: {path}")
    try:
        root = ElementTree.parse(absolute).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise VerificationMetadataError(f"{path}: invalid XML: {error}") from error

    configuration = require_single_child(root, "configuration", path)
    verify_metadata = require_single_child(configuration, "verify-metadata", path)
    if (verify_metadata.text or "").strip() != "true":
        raise VerificationMetadataError(f"{path}: verify-metadata must be true")

    trusted_groups: set[str] = set()
    trusted_sections = children(configuration, "trusted-artifacts")
    if len(trusted_sections) > 1:
        raise VerificationMetadataError(f"{path}: duplicate trusted-artifacts sections")
    if trusted_sections:
        for trust in children(trusted_sections[0], "trust"):
            if set(trust.attrib) - {"group", "reason"}:
                raise VerificationMetadataError(
                    f"{path}: trust rules may only match an exact group"
                )
            group = trust.attrib.get("group", "")
            reason = trust.attrib.get("reason", "")
            if not group or not reason:
                raise VerificationMetadataError(
                    f"{path}: trust rules require exact group and reason"
                )
            trusted_groups.add(group)

    expected_trust = EXPECTED_CONSUMER_TRUST if path == CONSUMER_PATH else set()
    if trusted_groups != expected_trust:
        raise VerificationMetadataError(
            f"{path}: trusted groups {sorted(trusted_groups)} != "
            f"{sorted(expected_trust)}"
        )

    components = require_single_child(root, "components", path)
    component_nodes = children(components, "component")
    if not component_nodes:
        raise VerificationMetadataError(f"{path}: no verified dependency components")
    for component in component_nodes:
        identity = (
            component.attrib.get("group"),
            component.attrib.get("name"),
            component.attrib.get("version"),
        )
        if any(not value for value in identity):
            raise VerificationMetadataError(
                f"{path}: component has incomplete identity {identity!r}"
            )
        artifacts = children(component, "artifact")
        if not artifacts:
            raise VerificationMetadataError(
                f"{path}: component {':'.join(identity)} has no artifacts"
            )
        for artifact in artifacts:
            checksums = children(artifact, "sha256")
            if not checksums:
                raise VerificationMetadataError(
                    f"{path}: {':'.join(identity)}:{artifact.attrib.get('name')} "
                    "has no SHA-256"
                )
            for checksum in checksums:
                if not SHA256_PATTERN.fullmatch(checksum.attrib.get("value", "")):
                    raise VerificationMetadataError(
                        f"{path}: malformed SHA-256 for {':'.join(identity)}"
                    )
    return len(component_nodes)


def main() -> int:
    """Validate every independent Gradle build."""
    try:
        counts = {str(path): validate_metadata(path) for path in METADATA_PATHS}
    except VerificationMetadataError as error:
        print(f"Dependency verification metadata FAILED: {error}", file=sys.stderr)
        return 1
    print(
        "Dependency verification metadata passed: "
        + ", ".join(f"{path}={count}" for path, count in counts.items())
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
