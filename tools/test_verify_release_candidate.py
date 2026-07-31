"""Tests for the fail-closed Maven release-candidate verifier."""

from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from io import BytesIO
from pathlib import Path

from verify_release_candidate import (
    DEFAULT_VERSION,
    EXPECTED_BUNDLE_DEPENDENCIES,
    ReleaseCandidateError,
    expected_coordinates,
    publication_files,
    validate_repository,
    validate_signature_set,
)


CLASS_17 = b"\xca\xfe\xba\xbe\x00\x00\x00\x3d"


def pom_xml(
    group: str,
    artifact: str,
    version: str,
    dependencies: tuple[tuple[str, str, str], ...],
) -> str:
    """Build the minimal complete POM accepted by the production verifier."""
    dependency_xml = "".join(
        (
            "<dependency>"
            f"<groupId>{dependency_group}</groupId>"
            f"<artifactId>{dependency_artifact}</artifactId>"
            f"<version>{dependency_version}</version>"
            "</dependency>"
        )
        for dependency_group, dependency_artifact, dependency_version in dependencies
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        "<project>"
        "<modelVersion>4.0.0</modelVersion>"
        f"<groupId>{group}</groupId>"
        f"<artifactId>{artifact}</artifactId>"
        f"<version>{version}</version>"
        f"<name>{artifact}</name>"
        f"<description>AndroidAPM {artifact} publication</description>"
        "<url>https://github.com/XZQ/AndroidAPM</url>"
        "<licenses><license>"
        "<name>The Apache License, Version 2.0</name>"
        "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>"
        "</license></licenses>"
        "<developers><developer><id>YSHEN53</id><name>YSHEN53</name>"
        "</developer></developers>"
        "<scm>"
        "<url>https://github.com/XZQ/AndroidAPM</url>"
        "<connection>scm:git:git://github.com/XZQ/AndroidAPM.git</connection>"
        "<developerConnection>"
        "scm:git:git://github.com/XZQ/AndroidAPM.git"
        "</developerConnection>"
        "</scm>"
        f"<dependencies>{dependency_xml}</dependencies>"
        "</project>"
    )


def write_jar(path: Path, *, unsafe: bool = False) -> None:
    """Write a small JVM 17 JAR."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("../secret.class" if unsafe else "example/Type.class", CLASS_17)


def write_aar(path: Path) -> None:
    """Write a small AAR with nested JVM 17 bytecode."""
    classes = BytesIO()
    with zipfile.ZipFile(classes, "w") as archive:
        archive.writestr("example/Type.class", CLASS_17)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", "<manifest/>")
        archive.writestr("classes.jar", classes.getvalue())


class ReleaseCandidateVerifierTest(unittest.TestCase):
    """Exercise complete and tampered repository fixtures."""

    def create_repository(self, root: Path) -> Path:
        repository = root / "repository"
        for coordinate in expected_coordinates(DEFAULT_VERSION):
            directory = repository / coordinate.directory
            directory.mkdir(parents=True, exist_ok=True)
            if coordinate.artifact == "apm-bundle":
                dependencies = tuple(
                    ("com.apm", artifact, DEFAULT_VERSION)
                    for artifact in sorted(EXPECTED_BUNDLE_DEPENDENCIES)
                )
            elif coordinate.group == "com.apm.slow-method":
                dependencies = (("com.apm", "apm-plugin", DEFAULT_VERSION),)
            else:
                dependencies = ()
            files = publication_files(repository, coordinate)
            files[0].write_text(
                pom_xml(
                    coordinate.group,
                    coordinate.artifact,
                    coordinate.version,
                    dependencies,
                ),
                encoding="utf-8",
            )
            next_index = 1
            if coordinate.module_metadata:
                files[next_index].write_text(
                    json.dumps(
                        {
                            "component": {
                                "group": coordinate.group,
                                "module": coordinate.artifact,
                                "version": coordinate.version,
                            }
                        }
                    ),
                    encoding="utf-8",
                )
                next_index += 1
            if coordinate.binary_extension:
                binary = files[next_index]
                if coordinate.binary_extension == "aar":
                    write_aar(binary)
                else:
                    write_jar(binary)
                next_index += 1
            if coordinate.sources:
                with zipfile.ZipFile(files[next_index], "w") as archive:
                    archive.writestr("example/Source.kt", "package example")
        return repository

    def test_complete_repository_generates_manifest_and_spdx(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.create_repository(root)
            manifest, sbom = validate_repository(repository, root / "evidence")

            manifest_document = json.loads(manifest.read_text(encoding="utf-8"))
            sbom_document = json.loads(sbom.read_text(encoding="utf-8"))
            self.assertEqual(25, len(manifest_document["coordinates"]))
            self.assertEqual("unsigned-local-candidate", manifest_document["signatureState"])
            self.assertIn("sourceIdentity", manifest_document)
            self.assertEqual("SPDX-2.3", sbom_document["spdxVersion"])
            self.assertGreaterEqual(len(sbom_document["packages"]), 25)

    def test_missing_publication_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.create_repository(root)
            coordinate = expected_coordinates(DEFAULT_VERSION)[0]
            publication_files(repository, coordinate)[0].unlink()

            with self.assertRaisesRegex(ReleaseCandidateError, "POM set mismatch"):
                validate_repository(repository, root / "evidence")

    def test_bundle_dependency_omission_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.create_repository(root)
            bundle = next(
                coordinate
                for coordinate in expected_coordinates(DEFAULT_VERSION)
                if coordinate.artifact == "apm-bundle"
            )
            pom = publication_files(repository, bundle)[0]
            pom.write_text(
                pom_xml(
                    bundle.group,
                    bundle.artifact,
                    bundle.version,
                    (),
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ReleaseCandidateError, "dependency set mismatch"):
                validate_repository(repository, root / "evidence")

    def test_archive_path_traversal_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.create_repository(root)
            model = next(
                coordinate
                for coordinate in expected_coordinates(DEFAULT_VERSION)
                if coordinate.artifact == "apm-model"
            )
            binary = next(
                path
                for path in publication_files(repository, model)
                if path.name == f"{model.prefix}.jar"
            )
            write_jar(binary, unsafe=True)

            with self.assertRaisesRegex(ReleaseCandidateError, "unsafe archive entry"):
                validate_repository(repository, root / "evidence")

    def test_signature_policy_rejects_unsigned_and_partial_sets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first.jar"
            second = root / "second.pom"
            first.write_bytes(b"jar")
            second.write_text("<project/>", encoding="utf-8")

            with self.assertRaisesRegex(ReleaseCandidateError, "candidate is unsigned"):
                validate_signature_set((first, second), require_signatures=True)

            first.with_name(f"{first.name}.asc").write_text(
                "-----BEGIN PGP SIGNATURE-----\n"
                + ("A" * 128)
                + "\n-----END PGP SIGNATURE-----\n",
                encoding="ascii",
            )
            with self.assertRaisesRegex(ReleaseCandidateError, "partial PGP signature"):
                validate_signature_set((first, second), require_signatures=False)


if __name__ == "__main__":
    unittest.main()
