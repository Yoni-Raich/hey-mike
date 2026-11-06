# SPDX-License-Identifier: Apache-2.0
"""Publication-boundary checks; fixture binaries are intentionally not Android artifacts."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools import package_mike_connect as kit


class DeveloperKitPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mike-connect-package-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.output = Path(self.temporary.name) / "output"
        source = self.root / "connect-protocol/src/main/kotlin/dev/heymike/connect/Protocol.kt"
        source.parent.mkdir(parents=True)
        source.write_text('const val VERSION = "2026-07-28"\nconst val SDK_VERSION = "1.0.0"\nconst val BINDING = 1\n')
        for module in kit.MODULES:
            folder = self.root / module
            folder.mkdir(exist_ok=True)
            (folder / "LICENSE").write_text("Apache License\nVersion 2.0\n")
            (folder / "build.gradle.kts").write_text('version = "1.0.0"\n')
        developer = self.root / "connect-sdk/developer-kit"
        developer.mkdir()
        (developer / "README.md").write_text("# Portable kit\n")
        for skill in kit.SKILLS:
            path = developer / "skills" / skill
            (path / "references").mkdir(parents=True)
            (path / "SKILL.md").write_text(f"---\nname: {skill}\ndescription: A test fixture.\n---\n[Reference](references/api.md)\n")
            (path / "references/api.md").write_text("# API\n")
        for module, extension in kit.MODULES.items():
            directory = self.root / "artifacts/connect-maven/dev/heymike/connect" / module / "1.0.0"
            directory.mkdir(parents=True)
            base = f"{module}-1.0.0"
            binary = b"publication-test-binary-" + module.encode()
            sources = b"publication-test-sources-" + module.encode()
            metadata = {"component": {"group": kit.GROUP, "module": module, "version": "1.0.0"}, "variants": [{
                "files": [{"name": base + "." + extension, "url": base + "." + extension, "size": len(binary), "sha256": kit.digest(binary)}]
            }]}
            files = {base + "." + extension: binary, base + "-sources.jar": sources,
                     base + ".pom": (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{kit.GROUP}</groupId><artifactId>{module}</artifactId><version>1.0.0</version></project>').encode(),
                     base + ".module": json.dumps(metadata).encode()}
            for name, data in files.items():
                (directory / name).write_bytes(data)
                self.sidecars(directory / name)

    @staticmethod
    def sidecars(path):
        for algorithm in kit.HASHES:
            Path(str(path) + "." + algorithm).write_text(kit.digest(path.read_bytes(), algorithm))

    def build(self):
        return kit.package(self.root, self.output, "1.0.0-preview.1")

    def test_complete_portable_bundle_and_archive_manifest_match(self):
        (self.root / "local.properties").write_text("private-path")
        (self.root / "signing").mkdir()
        (self.root / "signing/private.jks").write_text("never-publish")
        folder, archive = self.build()
        release = kit.verify_bundle(folder)
        self.assertEqual(3, len(release["binarySha256"]))
        self.assertFalse(release["publication"]["publishedByThisCommand"])
        self.assertFalse(release["publication"]["mavenCentralReady"])
        with zipfile.ZipFile(archive) as z:
            self.assertFalse(any(".jks" in name or "local.properties" in name or "connect-host" in name for name in z.namelist()))
            destination = Path(self.temporary.name) / "extracted"
            z.extractall(destination)
        self.assertEqual(release, kit.verify_bundle(destination / folder.name))
        for skill in kit.SKILLS:
            self.assertTrue((folder / f"skills/{skill}/LICENSE").is_file())

    def test_repackaging_same_version_leaves_every_byte_unchanged(self):
        folder, archive = self.build()
        original = archive.read_bytes()
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertEqual(original, archive.read_bytes())
        kit.verify_bundle(folder)

    def test_bad_maven_checksum_stops_before_output_is_created(self):
        path = self.root / "artifacts/connect-maven/dev/heymike/connect/connect-sdk/1.0.0/connect-sdk-1.0.0.aar"
        path.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_stale_gradle_file_metadata_is_rejected_even_with_updated_sidecars(self):
        path = self.root / "artifacts/connect-maven/dev/heymike/connect/connect-sdk/1.0.0/connect-sdk-1.0.0.module"
        metadata = json.loads(path.read_text())
        metadata["variants"][0]["files"][0]["sha256"] = "0" * 64
        path.write_text(json.dumps(metadata))
        self.sidecars(path)
        with self.assertRaisesRegex(ValueError, "metadata does not match"):
            self.build()

    def test_private_module_dependencies_are_rejected(self):
        path = self.root / "artifacts/connect-maven/dev/heymike/connect/connect-sdk/1.0.0/connect-sdk-1.0.0.pom"
        path.write_text(path.read_text().replace("</project>", f"<dependencies><dependency><groupId>{kit.GROUP}</groupId><artifactId>connect-host</artifactId><version>1.0.0</version></dependency></dependencies></project>"))
        self.sidecars(path)
        with self.assertRaisesRegex(ValueError, "private or mismatched"):
            self.build()

    def test_missing_skill_reference_is_rejected(self):
        ref = self.root / f"connect-sdk/developer-kit/skills/{kit.SKILLS[0]}/references/api.md"
        ref.unlink()
        with self.assertRaisesRegex(ValueError, "Missing bundled reference"):
            self.build()

    def test_machine_specific_path_in_public_docs_is_rejected(self):
        (self.root / "connect-sdk/developer-kit/README.md").write_text("Use C:/Users/someone/private/maven")
        with self.assertRaisesRegex(ValueError, "Machine-specific path"):
            self.build()

    def test_unexpected_secret_file_inside_kit_is_rejected(self):
        (self.root / "connect-sdk/developer-kit/.env").write_text("secret")
        with self.assertRaisesRegex(ValueError, "Unexpected developer-kit file"):
            self.build()

    def test_arbitrary_properties_file_in_example_is_not_published(self):
        path = self.root / "connect-sdk/developer-kit/examples/preferences-provider/signing/release.properties"
        path.parent.mkdir(parents=True)
        path.write_text("private-signing-configuration")
        with self.assertRaisesRegex(ValueError, "Unexpected developer-kit file"):
            self.build()

    def test_extra_nested_manifest_is_also_an_unlisted_file(self):
        folder, _ = self.build()
        (folder / "skills/MANIFEST.sha256").write_text("unlisted")
        with self.assertRaisesRegex(ValueError, "file set differs"):
            kit.verify_bundle(folder)

    def test_tampering_and_extra_files_fail_extracted_verification(self):
        folder, _ = self.build()
        (folder / "extra.txt").write_text("not-in-manifest")
        with self.assertRaisesRegex(ValueError, "file set differs"):
            kit.verify_bundle(folder)
        (folder / "extra.txt").unlink()
        (folder / "README.md").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            kit.verify_bundle(folder)

    def test_traversal_in_bundle_version_or_manifest_is_rejected(self):
        with self.assertRaises(ValueError):
            kit.package(self.root, self.output, "../../outside")
        folder, _ = self.build()
        (folder / "MANIFEST.sha256").write_text("0" * 64 + "  ../outside\n")
        with self.assertRaisesRegex(ValueError, "Invalid manifest"):
            kit.verify_bundle(folder)


if __name__ == "__main__":
    unittest.main()
