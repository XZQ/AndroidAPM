"""Build and fail-closed verify the AndroidAPM Maven release candidate."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Iterable
from xml.etree import ElementTree


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_VERSION = "0.1.0"
PROJECT_URL = "https://github.com/XZQ/AndroidAPM"
SCM_CONNECTION = "scm:git:git://github.com/XZQ/AndroidAPM.git"
ROOT_ARTIFACTS = (
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
NON_PUBLISHED_ARTIFACTS = ("apm-benchmark", "apm-sample-app", "build-logic")
EXPECTED_BUNDLE_DEPENDENCIES = frozenset(
    artifact for artifact in ROOT_ARTIFACTS if artifact != "apm-bundle"
)
DISALLOWED_ARCHIVE_SUFFIXES = (
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
)
DISALLOWED_ARCHIVE_NAMES = {
    ".env",
    "id_dsa",
    "id_ed25519",
    "id_rsa",
    "local.properties",
}
DYNAMIC_VERSION_PATTERN = re.compile(
    r"(?:\+|latest|release|snapshot|[\[\]\(\),])",
    re.IGNORECASE,
)


class ReleaseCandidateError(RuntimeError):
    """Raised when release-candidate evidence is incomplete or inconsistent."""


@dataclass(frozen=True, order=True)
class Coordinate:
    """A Maven coordinate expected in the staged repository."""

    group: str
    artifact: str
    version: str
    binary_extension: str | None
    sources: bool
    module_metadata: bool

    @property
    def gav(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"

    @property
    def directory(self) -> Path:
        return Path(*self.group.split(".")) / self.artifact / self.version

    @property
    def prefix(self) -> str:
        return f"{self.artifact}-{self.version}"


def expected_coordinates(version: str) -> tuple[Coordinate, ...]:
    """Return the complete public distribution set."""
    roots = tuple(
        Coordinate(
            group="com.apm",
            artifact=artifact,
            version=version,
            binary_extension="jar" if artifact == "apm-model" else "aar",
            sources=True,
            module_metadata=True,
        )
        for artifact in ROOT_ARTIFACTS
    )
    return roots + (
        Coordinate(
            group="com.apm",
            artifact="apm-plugin",
            version=version,
            binary_extension="jar",
            sources=True,
            module_metadata=True,
        ),
        Coordinate(
            group="com.apm.slow-method",
            artifact="com.apm.slow-method.gradle.plugin",
            version=version,
            binary_extension=None,
            sources=False,
            module_metadata=False,
        ),
    )


def sha256(path: Path) -> str:
    """Return a lowercase SHA-256 digest for one file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strip_namespace(root: ElementTree.Element) -> None:
    """Remove the Maven XML namespace for simple, strict lookups."""
    for element in root.iter():
        if "}" in element.tag:
            element.tag = element.tag.split("}", maxsplit=1)[1]


def required_text(root: ElementTree.Element, path: str, pom: Path) -> str:
    """Read required non-blank POM text."""
    value = root.findtext(path)
    if value is None or not value.strip():
        raise ReleaseCandidateError(f"{pom}: missing required POM field {path}")
    return value.strip()


def pom_dependencies(root: ElementTree.Element, pom: Path) -> tuple[tuple[str, str, str], ...]:
    """Parse direct Maven dependencies and reject dynamic versions."""
    managed_versions: dict[tuple[str, str], str] = {}
    for dependency in root.findall(
        "./dependencyManagement/dependencies/dependency"
    ):
        group = required_text(dependency, "groupId", pom)
        artifact = required_text(dependency, "artifactId", pom)
        managed_versions[(group, artifact)] = required_text(
            dependency,
            "version",
            pom,
        )

    dependencies: list[tuple[str, str, str]] = []
    for dependency in root.findall("./dependencies/dependency"):
        group = required_text(dependency, "groupId", pom)
        artifact = required_text(dependency, "artifactId", pom)
        declared_version = dependency.findtext("version")
        version = (
            declared_version.strip()
            if declared_version is not None and declared_version.strip()
            else managed_versions.get((group, artifact))
        )
        if version is None:
            raise ReleaseCandidateError(
                f"{pom}: dependency {group}:{artifact} has no declared or managed version"
            )
        if DYNAMIC_VERSION_PATTERN.search(version):
            raise ReleaseCandidateError(
                f"{pom}: dependency {group}:{artifact} uses non-fixed version {version!r}"
            )
        dependencies.append((group, artifact, version))
    return tuple(dependencies)


def validate_pom(
    pom: Path,
    coordinate: Coordinate,
) -> tuple[tuple[str, str, str], ...]:
    """Validate identity, release metadata, and declared dependency versions."""
    try:
        root = ElementTree.parse(pom).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise ReleaseCandidateError(f"{pom}: invalid POM XML: {error}") from error
    strip_namespace(root)

    actual = (
        required_text(root, "groupId", pom),
        required_text(root, "artifactId", pom),
        required_text(root, "version", pom),
    )
    expected = (coordinate.group, coordinate.artifact, coordinate.version)
    if actual != expected:
        raise ReleaseCandidateError(f"{pom}: coordinate {actual!r} != {expected!r}")

    required_text(root, "name", pom)
    required_text(root, "description", pom)
    if required_text(root, "url", pom) != PROJECT_URL:
        raise ReleaseCandidateError(f"{pom}: unexpected project URL")
    if required_text(root, "./licenses/license/name", pom) != (
        "The Apache License, Version 2.0"
    ):
        raise ReleaseCandidateError(f"{pom}: unexpected license name")
    if required_text(root, "./licenses/license/url", pom) != (
        "https://www.apache.org/licenses/LICENSE-2.0.txt"
    ):
        raise ReleaseCandidateError(f"{pom}: unexpected license URL")
    if required_text(root, "./developers/developer/id", pom) != "YSHEN53":
        raise ReleaseCandidateError(f"{pom}: unexpected developer id")
    required_text(root, "./developers/developer/name", pom)
    if required_text(root, "./scm/url", pom) != PROJECT_URL:
        raise ReleaseCandidateError(f"{pom}: unexpected SCM URL")
    if required_text(root, "./scm/connection", pom) != SCM_CONNECTION:
        raise ReleaseCandidateError(f"{pom}: unexpected SCM connection")
    if required_text(root, "./scm/developerConnection", pom) != SCM_CONNECTION:
        raise ReleaseCandidateError(f"{pom}: unexpected SCM developer connection")

    dependencies = pom_dependencies(root, pom)
    for group, artifact, version in dependencies:
        if group == "com.apm" and version != coordinate.version:
            raise ReleaseCandidateError(
                f"{pom}: internal dependency {group}:{artifact} must use "
                f"{coordinate.version}, found {version}"
            )
    return dependencies


def validate_module_metadata(path: Path, coordinate: Coordinate) -> None:
    """Validate Gradle module metadata identity."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        component = document["component"]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise ReleaseCandidateError(f"{path}: invalid Gradle module metadata") from error
    actual = (
        component.get("group"),
        component.get("module"),
        component.get("version"),
    )
    expected = (coordinate.group, coordinate.artifact, coordinate.version)
    if actual != expected:
        raise ReleaseCandidateError(f"{path}: component {actual!r} != {expected!r}")


def validate_archive_member_name(archive: Path, member_name: str) -> None:
    """Reject path traversal and common credential/key material."""
    normalized = member_name.replace("\\", "/")
    path = PurePosixPath(normalized)
    if path.is_absolute() or ".." in path.parts:
        raise ReleaseCandidateError(f"{archive}: unsafe archive entry {member_name!r}")
    lower_name = path.name.lower()
    if lower_name in DISALLOWED_ARCHIVE_NAMES or lower_name.endswith(
        DISALLOWED_ARCHIVE_SUFFIXES
    ):
        raise ReleaseCandidateError(
            f"{archive}: prohibited secret-like archive entry {member_name!r}"
        )


def class_major_version(data: bytes, location: str) -> int:
    """Read a JVM class-file major version."""
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ReleaseCandidateError(f"{location}: malformed JVM class file")
    return int.from_bytes(data[6:8], byteorder="big")


def inspect_zip(
    archive: Path,
    *,
    require_content: bool,
    expected_class_major: int | None,
) -> int:
    """Inspect one ZIP-compatible artifact and return its class count."""
    class_count = 0
    non_directory_count = 0
    try:
        with zipfile.ZipFile(archive) as zipped:
            for member in zipped.infolist():
                validate_archive_member_name(archive, member.filename)
                if member.is_dir():
                    continue
                non_directory_count += 1
                data = zipped.read(member)
                if member.filename.endswith(".class"):
                    class_count += 1
                    major = class_major_version(
                        data,
                        f"{archive}!/{member.filename}",
                    )
                    if expected_class_major is not None and major != expected_class_major:
                        raise ReleaseCandidateError(
                            f"{archive}!/{member.filename}: class major {major}, "
                            f"expected {expected_class_major}"
                        )
                elif member.filename == "classes.jar":
                    with zipfile.ZipFile(io.BytesIO(data)) as classes:
                        for nested in classes.infolist():
                            validate_archive_member_name(
                                archive,
                                f"classes.jar!/{nested.filename}",
                            )
                            if nested.is_dir() or not nested.filename.endswith(".class"):
                                continue
                            class_count += 1
                            major = class_major_version(
                                classes.read(nested),
                                f"{archive}!/classes.jar!/{nested.filename}",
                            )
                            if (
                                expected_class_major is not None
                                and major != expected_class_major
                            ):
                                raise ReleaseCandidateError(
                                    f"{archive}!/classes.jar!/{nested.filename}: "
                                    f"class major {major}, expected {expected_class_major}"
                                )
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleaseCandidateError(f"{archive}: invalid ZIP artifact: {error}") from error

    if require_content and non_directory_count == 0:
        raise ReleaseCandidateError(f"{archive}: archive contains no files")
    return class_count


def publication_files(repository: Path, coordinate: Coordinate) -> tuple[Path, ...]:
    """Return required publication files for one coordinate."""
    directory = repository / coordinate.directory
    files = [directory / f"{coordinate.prefix}.pom"]
    if coordinate.module_metadata:
        files.append(directory / f"{coordinate.prefix}.module")
    if coordinate.binary_extension:
        files.append(directory / f"{coordinate.prefix}.{coordinate.binary_extension}")
    if coordinate.sources:
        files.append(directory / f"{coordinate.prefix}-sources.jar")
    return tuple(files)


def validate_signature_set(files: Iterable[Path], require_signatures: bool) -> bool:
    """Require either no signatures or a complete structurally valid set."""
    required = tuple(files)
    signatures = tuple(path.with_name(f"{path.name}.asc") for path in required)
    existing = tuple(signature.is_file() for signature in signatures)
    if any(existing) and not all(existing):
        missing = [
            signature
            for signature, present in zip(signatures, existing, strict=True)
            if not present
        ]
        raise ReleaseCandidateError(
            "partial PGP signature set; missing " + ", ".join(str(path) for path in missing)
        )
    if require_signatures and not all(existing):
        raise ReleaseCandidateError(
            "release signatures are required, but the candidate is unsigned"
        )
    if all(existing):
        for signature in signatures:
            text = signature.read_text(encoding="ascii", errors="strict")
            if (
                len(text) < 128
                or "-----BEGIN PGP SIGNATURE-----" not in text
                or "-----END PGP SIGNATURE-----" not in text
            ):
                raise ReleaseCandidateError(
                    f"{signature}: malformed armored PGP signature"
                )
        return True
    return False


def git_value(*arguments: str) -> str:
    """Read one stable Git provenance value."""
    completed = subprocess.run(
        ["git", *arguments],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise ReleaseCandidateError(
            f"git {' '.join(arguments)} failed: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def dirty_source_identity(revision: str) -> str:
    """Hash tracked changes and untracked source paths into a bounded identity."""
    digest = hashlib.sha256(revision.encode("ascii"))
    diff = subprocess.run(
        ["git", "diff", "--binary", "HEAD"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
    )
    if diff.returncode != 0:
        raise ReleaseCandidateError(
            f"git diff failed: {diff.stderr.decode(errors='replace').strip()}"
        )
    digest.update(diff.stdout)

    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=REPOSITORY_ROOT,
        check=False,
        capture_output=True,
    )
    if untracked.returncode != 0:
        raise ReleaseCandidateError(
            "git ls-files failed: "
            + untracked.stderr.decode(errors="replace").strip()
        )
    for raw_path in sorted(path for path in untracked.stdout.split(b"\0") if path):
        path = REPOSITORY_ROOT / os.fsdecode(raw_path)
        digest.update(raw_path)
        if path.is_file():
            digest.update(path.read_bytes())
    return f"{revision}-dirty-{digest.hexdigest()[:20]}"


def source_provenance() -> tuple[str, str, bool, str]:
    """Return revision, source identity, dirty state, and creation time."""
    revision = git_value("rev-parse", "HEAD")
    dirty = bool(git_value("status", "--porcelain", "--untracked-files=all"))
    identity = dirty_source_identity(revision) if dirty else revision
    if dirty:
        created = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    else:
        created = git_value("show", "-s", "--format=%cI", "HEAD")
    return revision, identity, dirty, created.replace("+00:00", "Z")


def spdx_id(gav: str) -> str:
    """Create a stable SPDX identifier from a coordinate."""
    digest = hashlib.sha256(gav.encode("utf-8")).hexdigest()[:16]
    return f"SPDXRef-Package-{digest}"


def create_evidence(
    repository: Path,
    output_directory: Path,
    version: str,
    coordinates: tuple[Coordinate, ...],
    dependency_graph: dict[str, tuple[tuple[str, str, str], ...]],
    signed: bool,
) -> tuple[Path, Path]:
    """Write deterministic hash inventory and SPDX distribution SBOM."""
    revision, source_identity, dirty, created = source_provenance()
    files = [
        {
            "path": path.relative_to(repository).as_posix(),
            "size": path.stat().st_size,
            "sha256": sha256(path),
        }
        for path in sorted(repository.rglob("*"))
        if path.is_file()
    ]
    manifest = {
        "schemaVersion": 1,
        "project": "AndroidAPM",
        "version": version,
        "sourceRevision": revision,
        "sourceIdentity": source_identity,
        "sourceDirty": dirty,
        "signatureState": "complete" if signed else "unsigned-local-candidate",
        "coordinates": [coordinate.gav for coordinate in coordinates],
        "dependencies": {
            gav: [":".join(dependency) for dependency in dependencies]
            for gav, dependencies in sorted(dependency_graph.items())
        },
        "files": files,
    }

    package_coordinates = {coordinate.gav for coordinate in coordinates}
    for dependencies in dependency_graph.values():
        package_coordinates.update(":".join(dependency) for dependency in dependencies)
    packages = []
    for gav in sorted(package_coordinates):
        group, artifact, package_version = gav.split(":", maxsplit=2)
        owned = any(coordinate.gav == gav for coordinate in coordinates)
        packages.append(
            {
                "SPDXID": spdx_id(gav),
                "name": f"{group}:{artifact}",
                "versionInfo": package_version,
                "downloadLocation": "NOASSERTION",
                "filesAnalyzed": False,
                "licenseConcluded": "Apache-2.0" if owned else "NOASSERTION",
                "licenseDeclared": "Apache-2.0" if owned else "NOASSERTION",
                "copyrightText": "NOASSERTION",
                "externalRefs": [
                    {
                        "referenceCategory": "PACKAGE-MANAGER",
                        "referenceType": "purl",
                        "referenceLocator": (
                            f"pkg:maven/{group}/{artifact}@{package_version}"
                        ),
                    }
                ],
            }
        )
    relationships = [
        {
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": spdx_id(coordinate.gav),
        }
        for coordinate in coordinates
    ]
    for owner, dependencies in sorted(dependency_graph.items()):
        relationships.extend(
            {
                "spdxElementId": spdx_id(owner),
                "relationshipType": "DEPENDS_ON",
                "relatedSpdxElement": spdx_id(":".join(dependency)),
            }
            for dependency in dependencies
        )
    sbom = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"AndroidAPM-{version}-distribution",
        "documentNamespace": f"{PROJECT_URL}/spdx/{version}/{source_identity}",
        "creationInfo": {
            "created": created,
            "creators": ["Tool: AndroidAPM verify_release_candidate.py"],
        },
        "packages": packages,
        "relationships": relationships,
    }

    output_directory.mkdir(parents=True, exist_ok=True)
    manifest_path = output_directory / "release-manifest.json"
    sbom_path = output_directory / "release-sbom.spdx.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    sbom_path.write_text(
        json.dumps(sbom, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest_path, sbom_path


def validate_repository(
    repository: Path,
    output_directory: Path,
    version: str = DEFAULT_VERSION,
    *,
    require_signatures: bool = False,
) -> tuple[Path, Path]:
    """Validate a staged Maven repository and emit bounded release evidence."""
    repository = repository.resolve()
    if not repository.is_dir():
        raise ReleaseCandidateError(f"release repository does not exist: {repository}")

    coordinates = expected_coordinates(version)
    expected_poms = {
        (repository / coordinate.directory / f"{coordinate.prefix}.pom").resolve()
        for coordinate in coordinates
    }
    actual_poms = {path.resolve() for path in repository.rglob("*.pom")}
    if actual_poms != expected_poms:
        missing = sorted(str(path) for path in expected_poms - actual_poms)
        unexpected = sorted(str(path) for path in actual_poms - expected_poms)
        raise ReleaseCandidateError(
            f"published POM set mismatch; missing={missing}, unexpected={unexpected}"
        )

    for prohibited in NON_PUBLISHED_ARTIFACTS:
        if any(prohibited in path.parts for path in repository.rglob("*")):
            raise ReleaseCandidateError(
                f"non-published build unit leaked into repository: {prohibited}"
            )

    dependency_graph: dict[str, tuple[tuple[str, str, str], ...]] = {}
    signed_states: list[bool] = []
    for coordinate in coordinates:
        files = publication_files(repository, coordinate)
        for path in files:
            if not path.is_file() or path.stat().st_size == 0:
                raise ReleaseCandidateError(f"missing or empty publication file: {path}")
        signed_states.append(validate_signature_set(files, require_signatures))

        pom = files[0]
        dependencies = validate_pom(pom, coordinate)
        dependency_graph[coordinate.gav] = dependencies
        if coordinate.module_metadata:
            validate_module_metadata(files[1], coordinate)

        if coordinate.binary_extension:
            binary = next(
                path
                for path in files
                if path.name == f"{coordinate.prefix}.{coordinate.binary_extension}"
            )
            class_count = inspect_zip(
                binary,
                require_content=True,
                expected_class_major=61,
            )
            if coordinate.artifact in {"apm-model", "apm-plugin"} and class_count == 0:
                raise ReleaseCandidateError(f"{binary}: binary JAR contains no classes")
        if coordinate.sources:
            sources = next(path for path in files if path.name.endswith("-sources.jar"))
            inspect_zip(
                sources,
                require_content=True,
                expected_class_major=None,
            )

    if any(signed_states) and not all(signed_states):
        raise ReleaseCandidateError("some publications are signed while others are unsigned")
    signed = all(signed_states)

    bundle_gav = f"com.apm:apm-bundle:{version}"
    bundle_dependencies = {
        artifact
        for group, artifact, dependency_version in dependency_graph[bundle_gav]
        if group == "com.apm" and dependency_version == version
    }
    if bundle_dependencies != EXPECTED_BUNDLE_DEPENDENCIES:
        raise ReleaseCandidateError(
            "apm-bundle dependency set mismatch; "
            f"expected={sorted(EXPECTED_BUNDLE_DEPENDENCIES)}, "
            f"actual={sorted(bundle_dependencies)}"
        )

    marker_gav = (
        f"com.apm.slow-method:com.apm.slow-method.gradle.plugin:{version}"
    )
    if dependency_graph[marker_gav] != (("com.apm", "apm-plugin", version),):
        raise ReleaseCandidateError(
            "Gradle plugin marker must depend only on com.apm:apm-plugin"
        )

    manifest, sbom = create_evidence(
        repository,
        output_directory.resolve(),
        version,
        coordinates,
        dependency_graph,
        signed,
    )
    print(
        "Release candidate verification passed: "
        f"{len(coordinates)} coordinates, "
        f"{len(tuple(repository.rglob('*.aar')))} AAR, "
        f"{len(tuple(repository.rglob('*.jar')))} JAR, "
        f"{len(tuple(repository.rglob('*.pom')))} POM, "
        f"signatures={'complete' if signed else 'not required'}",
        flush=True,
    )
    return manifest, sbom


def run(command: list[str], label: str) -> None:
    """Run one release pipeline command."""
    print(f"[release] {label}: {' '.join(command)}", flush=True)
    completed = subprocess.run(command, cwd=REPOSITORY_ROOT, check=False)
    if completed.returncode != 0:
        raise ReleaseCandidateError(
            f"{label} failed with exit code {completed.returncode}"
        )


def wrapper() -> str:
    """Return the checked-in Gradle wrapper for this platform."""
    return "gradlew.bat" if os.name == "nt" else "./gradlew"


def safe_clean(repository: Path) -> None:
    """Delete only the dedicated generated candidate repository."""
    allowed_root = (REPOSITORY_ROOT / "build" / "release-candidate").resolve()
    target = repository.resolve()
    if target != allowed_root / "repository":
        raise ReleaseCandidateError(
            f"refusing to clean non-canonical release repository: {target}"
        )
    if target.exists():
        shutil.rmtree(target)


def build_and_verify(arguments: argparse.Namespace) -> tuple[Path, Path]:
    """Publish root/plugin artifacts, validate them, then build the isolated consumer."""
    repository = arguments.repository.resolve()
    if not arguments.allow_dirty:
        status = git_value("status", "--porcelain", "--untracked-files=all")
        if status:
            raise ReleaseCandidateError(
                "release-candidate build requires a clean Git worktree; "
                "use --allow-dirty only for pre-commit development checks"
            )
    safe_clean(repository)

    property_argument = f"-PapmReleaseRepository={repository}"
    signing_argument = ["-PapmRequireSigning=true"] if arguments.require_signatures else []
    gradle = wrapper()
    run(
        [
            gradle,
            "publishAllPublicationsToReleaseCandidateRepository",
            property_argument,
            *signing_argument,
            "--no-daemon",
        ],
        "publish root SDK artifacts",
    )
    run(
        [
            gradle,
            "-p",
            "apm-plugin",
            "publishAllPublicationsToReleaseCandidateRepository",
            property_argument,
            *signing_argument,
            "--no-daemon",
        ],
        "publish Gradle plugin and marker",
    )
    evidence = validate_repository(
        repository,
        arguments.output_directory,
        arguments.version,
        require_signatures=arguments.require_signatures,
    )
    run(
        [
            gradle,
            "-p",
            "smoke-tests/maven-consumer",
            "clean",
            "assembleDebug",
            f"-PapmRepositoryPath={repository}",
            "--refresh-dependencies",
            "--no-daemon",
        ],
        "resolve bundle and Gradle plugin from isolated repository",
    )
    return evidence


def parse_arguments() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository",
        type=Path,
        default=REPOSITORY_ROOT / "build" / "release-candidate" / "repository",
    )
    parser.add_argument(
        "--output-directory",
        type=Path,
        default=REPOSITORY_ROOT / "build" / "reports" / "release-candidate",
    )
    parser.add_argument("--version", default=DEFAULT_VERSION)
    parser.add_argument("--require-signatures", action="store_true")
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Validate an existing repository without cleaning, publishing, or consuming.",
    )
    return parser.parse_args()


def main() -> int:
    """Execute the release-candidate gate."""
    arguments = parse_arguments()
    try:
        if arguments.verify_only:
            evidence = validate_repository(
                arguments.repository,
                arguments.output_directory,
                arguments.version,
                require_signatures=arguments.require_signatures,
            )
        else:
            evidence = build_and_verify(arguments)
    except (OSError, ReleaseCandidateError, ValueError) as error:
        print(f"[release] FAILED: {error}", file=sys.stderr, flush=True)
        return 1
    print(
        f"[release] evidence: {evidence[0]} and {evidence[1]}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
