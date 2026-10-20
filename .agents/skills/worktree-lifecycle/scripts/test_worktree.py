"""Windows safety checks. All fixtures live in a new %TEMP%/hey-mike folder."""
import contextlib
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("worktree.ps1").resolve()


@contextlib.contextmanager
def hold_file(path):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [
        wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
        wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE,
    ]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.CreateFileW(str(path), 0xC0000000, 0, None, 3, 0, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        yield
    finally:
        kernel.CloseHandle(handle)


@unittest.skipUnless(os.name == "nt", "Windows PowerShell and file sharing")
class WorktreeSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_home = (Path(tempfile.gettempdir()) / "hey-mike").resolve()
        cls.temp_home.mkdir(exist_ok=True)
        cls.root = Path(tempfile.mkdtemp(prefix="worktree-tests-", dir=cls.temp_home))

    @classmethod
    def tearDownClass(cls):
        # Never recursively delete an unchecked computed path.
        assert cls.root.resolve().parent == cls.temp_home
        assert cls.root.name.startswith("worktree-tests-")

        def writable(function, path, error):
            os.chmod(path, stat.S_IWRITE)
            function(path)

        shutil.rmtree(cls.root, onerror=writable)

    def git(self, *args, cwd=None):
        return subprocess.run(
            ["git", "-c", "user.name=Yoni Raich",
             "-c", "user.email=yoniraich@gmail.com", *args],
            cwd=cwd or self.repo, check=True, capture_output=True, text=True,
            stdin=subprocess.DEVNULL,
        ).stdout

    def setUp(self):
        self.repo = self.root / self._testMethodName
        self.repo.mkdir()
        self.git("init", "-b", "dev")
        (self.repo / ".gitignore").write_text("build/\n.claude/\nignored/\n")
        (self.repo / "source.txt").write_text("keep source\n")
        self.git("add", ".")
        self.git("commit", "-m", "test fixture")
        self.git("update-ref", "refs/remotes/origin/dev", "HEAD")
        self.wt = self.repo / ".claude" / "worktrees" / "fixture"
        self.git("worktree", "add", "--detach", str(self.wt))
        bucket_id = hashlib.sha256(str(self.wt).lower().encode()).hexdigest()[:16]
        self.bucket = self.repo / "build" / bucket_id
        self.bucket.mkdir(parents=True)
        self.mark(self.wt)
        (self.bucket / "keep.txt").write_text("build evidence\n")
        (self.bucket / ".active.lock").touch()

    def mark(self, owner):
        (self.bucket / ".hey-mike-build.json").write_text(
            json.dumps({"worktree": str(owner)}), encoding="utf-8"
        )

    def remove(self, path=None, cwd=None, extra=()):
        return subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-File", str(SCRIPT), "remove", "-Path", str(path or self.wt), *extra],
            cwd=cwd or self.repo, capture_output=True, text=True,
            stdin=subprocess.DEVNULL, timeout=30,
        )

    def refused(self, result, message):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stdout + result.stderr)
        self.assertTrue(self.wt.exists())
        self.assertTrue((self.bucket / "keep.txt").exists())

    def test_clean_removes_worktree_and_bucket(self):
        result = self.remove()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.wt.exists())
        self.assertFalse(self.bucket.exists())
        self.assertNotIn(self.wt.as_posix(), self.git("worktree", "list", "--porcelain"))

    def test_merged_local_branch_removed(self):
        self.git("switch", "-c", "fixture-branch", cwd=self.wt)
        result = self.remove(extra=("-DeleteBranch",))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("refs/heads/fixture-branch", self.git("for-each-ref", "--format=%(refname)"))

    def test_untracked_file_preserved(self):
        (self.wt / "unfinished.txt").write_text("unfinished work\n")
        self.refused(self.remove(), "work would be lost")
        self.assertEqual((self.wt / "unfinished.txt").read_text(), "unfinished work\n")

    def test_unpushed_commit_preserved(self):
        (self.wt / "source.txt").write_text("new work\n")
        self.git("add", ".", cwd=self.wt)
        self.git("commit", "-m", "local work", cwd=self.wt)
        self.refused(self.remove(), "Commits on no remote")

    def test_git_lock_preserved(self):
        self.git("worktree", "lock", str(self.wt))
        self.refused(self.remove(), "worktree is locked")

    def test_running_build_preserved(self):
        with hold_file(self.bucket / ".active.lock"):
            self.refused(self.remove(), "Gradle build is running")

    def test_wrong_bucket_owner_preserved(self):
        self.mark(self.repo)
        self.refused(self.remove(), "not marked as this worktree")

    def test_failed_git_check_preserved(self):
        # Git marks this file hidden on Windows; open the existing file.
        with (self.wt / ".git").open("r+") as marker:
            marker.write("gitdir: missing-metadata\n")
            marker.truncate()
        self.refused(self.remove(), "Git check failed")

    def test_current_subfolder_preserved(self):
        nested = self.wt / "ignored" / "nested"
        nested.mkdir(parents=True)
        self.refused(self.remove(cwd=nested), "shell is inside that worktree")

    def test_main_checkout_preserved(self):
        self.refused(self.remove(path=self.repo), "main checkout")
        self.assertTrue((self.repo / "source.txt").exists())

    def test_git_removal_failure_keeps_bucket(self):
        ignored = self.wt / "ignored"
        ignored.mkdir()
        locked = ignored / "locked.txt"
        locked.write_text("locked evidence\n")
        with hold_file(locked):
            self.refused(self.remove(), "Git refused to remove")
            self.assertTrue(locked.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
