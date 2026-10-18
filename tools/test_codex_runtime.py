import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SOURCE = Path(__file__).parents[1] / "remote/src/main/resources/codex_runtime.py"
spec = importlib.util.spec_from_file_location("codex_runtime", SOURCE)
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class CodexRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        old = updater.binary(self.root, "0.159.2")
        old.parent.mkdir(parents=True)
        old.write_bytes(b"old-working-binary")

    def metadata(self, selected="0.163.0"):
        name = "codex-app-server-package-" + updater.target_name() + ".tar.gz"
        return {"tag_name": "rust-v" + selected, "draft": False, "prerelease": False,
                "assets": [{"name": name, "size": 10, "digest": "sha256:" + "a" * 64,
                            "browser_download_url": "https://github.com/openai/codex/releases/download/rust-v" + selected + "/" + name}]}

    def retrieve(self, pin, target):
        with tarfile.open(target, "w:gz") as archive:
            data = b"verified-candidate"
            member = tarfile.TarInfo("bin/" + updater.binary(self.root, pin["version"]).name)
            member.size, member.mode = len(data), 0o755
            archive.addfile(member, io.BytesIO(data))

    def test_network_failure_keeps_old_install_and_does_not_activate(self):
        result = updater.update(self.root, fetch=lambda: (_ for _ in ()).throw(OSError("offline")))
        self.assertEqual("0.159.2", result["version"])
        self.assertFalse((self.root / "active.json").exists())

    def test_failed_probe_leaves_old_bytes_selection_and_no_candidate(self):
        result = updater.update(self.root, fetch=self.metadata, retrieve=self.retrieve,
                                validate=lambda *_: (_ for _ in ()).throw(ValueError("incompatible")))
        self.assertEqual("0.159.2", result["version"])
        self.assertFalse((self.root / "0.163.0").exists())
        self.assertEqual(b"old-working-binary", updater.binary(self.root, "0.159.2").read_bytes())

    def test_success_activates_after_probe_retains_old_and_throttles(self):
        checked = []
        result = updater.update(self.root, fetch=self.metadata, retrieve=self.retrieve,
                                validate=lambda path, selected: checked.append((path.read_bytes(), selected)))
        self.assertEqual("0.163.0", result["version"])
        self.assertEqual([(b"verified-candidate", "0.163.0")], checked)
        self.assertTrue(updater.binary(self.root, "0.159.2").exists())
        restored = updater.update(self.root, fetch=lambda: self.fail("throttled check reached network"))
        self.assertEqual(result["version"], restored["version"])

    def test_no_downgrades_or_preview_releases(self):
        for selected in ("0.150.0", "0.163.0-alpha.1", "../evil"):
            result = updater.update(self.root, force=True, fetch=lambda: self.metadata(selected), retrieve=lambda *_: self.fail("downloaded invalid release"))
            self.assertEqual("0.159.2", result["version"])

    def test_untrusted_url_digest_and_oversized_asset_are_refused(self):
        for key, value in (("browser_download_url", "https://example.com/codex"), ("digest", "sha256:bad"), ("size", updater.MAX_PACKAGE + 1)):
            metadata = self.metadata()
            metadata["assets"][0][key] = value
            with self.assertRaises(ValueError):
                updater.release_pin(metadata, updater.target_name())

    def test_archive_traversal_links_and_windows_paths_are_refused(self):
        for name, kind in (("../outside", tarfile.REGTYPE), ("C:/outside", tarfile.REGTYPE), ("a\\b", tarfile.REGTYPE), ("bin/link", tarfile.SYMTYPE)):
            archive = self.root / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                member = tarfile.TarInfo(name)
                member.type = kind
                output.addfile(member)
            with self.assertRaises(ValueError):
                updater.unpack(archive, self.root / "stage")
        self.assertFalse((self.root.parent / "outside").exists())

    def test_corrupt_selection_cannot_escape_root(self):
        updater.write_json(self.root / "active.json", {"version": "../../elsewhere"})
        self.assertEqual("0.159.2", updater.active_version(self.root))

    def test_existing_only_does_not_download_first_install(self):
        result = updater.update(self.root / "empty", existing_only=True, fetch=lambda: self.fail("first installation was automatic"))
        self.assertFalse(result["installed"])


if __name__ == "__main__":
    unittest.main()
