"""Exercise actual Windows cleanup on disposable build trees, never real builds."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest

TOOLS = Path(__file__).parent / 'build-storage'


@unittest.skipUnless(os.name == 'nt', 'Windows build storage')
class BuildStorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hey-mike-storage-test-')
        self.repo = Path(self.temp.name) / 'repo'
        self.root = self.repo / 'build'
        self.root.mkdir(parents=True)
        subprocess.run(['git', '-C', str(self.repo), 'init', '-q'], check=True)
        self.config = self.repo / 'config.json'
        self.config.write_text(json.dumps(dict(repository=str(self.repo), buildRoot=str(self.root), maxBytes=2200, policy='oldest-completed')))

    def tearDown(self):
        self.temp.cleanup()

    def bucket(self, name, date):
        worktree = self.repo / name
        worktree.mkdir()
        (worktree / '.git').write_text('gitdir: ' + str(self.repo / '.git'))
        key = hashlib.sha256(str(worktree).lower().encode()).hexdigest()[:16]
        path = self.root / key
        path.mkdir()
        (path / 'output.bin').write_bytes(b'x' * 1000)
        (path / '.active.lock').touch()
        (path / '.hey-mike-build.json').write_text(json.dumps(dict(schema=1, repository=str(self.repo), worktree=str(worktree), lastFinished=date)))
        return path, worktree

    def run_manager(self, mode='cleanup', *extra, ok=True):
        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(TOOLS / 'manage.ps1'), '-Config', str(self.config), '-Mode', mode, *extra], capture_output=True, text=True)
        if ok:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def test_oldest_first_and_unmanaged_files_survive(self):
        old, _ = self.bucket('old', '2026-01-01T00:00:00Z')
        new, _ = self.bucket('new', '2026-02-01T00:00:00Z')
        unmanaged = self.root / 'user-file.txt'
        unmanaged.write_text('keep')
        self.run_manager()
        self.assertFalse(old.exists())
        self.assertTrue(new.exists())
        self.assertEqual(unmanaged.read_text(), 'keep')

    def test_active_bucket_is_skipped(self):
        active, _ = self.bucket('active', '2026-01-01T00:00:00Z')
        idle, _ = self.bucket('idle', '2026-02-01T00:00:00Z')
        # A read/write handle has the same sharing conflict as Gradle's lease.
        handle = open(active / '.active.lock', 'r+b')
        try:
            self.run_manager()
            self.assertTrue(active.exists())
            self.assertFalse(idle.exists())
        finally:
            handle.close()

    def test_finishing_bucket_is_kept(self):
        old, _ = self.bucket('old', '2026-01-01T00:00:00Z')
        current, worktree = self.bucket('current', '2026-01-02T00:00:00Z')
        self.run_manager('finish', '-Bucket', current.name, '-Worktree', str(worktree))
        self.assertFalse(old.exists())
        self.assertTrue(current.exists())

    def test_start_reservation_prevents_cleanup(self):
        starting, worktree = self.bucket('starting', '2026-01-01T00:00:00Z')
        self.run_manager('begin', '-Bucket', starting.name, '-Worktree', str(worktree))
        idle, _ = self.bucket('idle', '2026-02-01T00:00:00Z')
        self.run_manager()
        self.assertTrue(starting.exists())
        self.assertFalse(idle.exists())

    def test_configuration_outside_repository_is_rejected(self):
        data = json.loads(self.config.read_text())
        data['buildRoot'] = str(self.repo.parent)
        self.config.write_text(json.dumps(data))
        self.run_manager(ok=False)
        self.assertTrue(self.root.exists())

    def test_existing_unmarked_bucket_is_not_adopted(self):
        path, worktree = self.bucket('unmarked', '2026-01-01T00:00:00Z')
        (path / '.hey-mike-build.json').unlink()
        self.run_manager('begin', '-Bucket', path.name, '-Worktree', str(worktree), ok=False)
        self.assertTrue((path / 'output.bin').exists())

    def test_foreign_worktree_is_rejected(self):
        path, worktree = self.bucket('foreign', '2026-01-01T00:00:00Z')
        (worktree / '.git').unlink()
        subprocess.run(['git', '-C', str(worktree), 'init', '-q'], check=True)
        self.run_manager('begin', '-Bucket', path.name, '-Worktree', str(worktree), ok=False)
        self.assertTrue(path.exists())

    def test_junction_inside_bucket_is_rejected(self):
        old, _ = self.bucket('old', '2026-01-01T00:00:00Z')
        outside = self.repo / 'source'
        outside.mkdir()
        (outside / 'important.txt').write_text('keep')
        subprocess.run(['powershell.exe', '-NoProfile', '-Command', 'New-Item -ItemType Junction -Path $env:TEST_LINK -Target $env:TEST_TARGET | Out-Null'], env=dict(os.environ, TEST_LINK=str(old / 'link'), TEST_TARGET=str(outside)), check=True)
        self.run_manager(ok=False)
        self.assertEqual((outside / 'important.txt').read_text(), 'keep')

    def test_runtime_redirect_preserves_pins_and_rejects_other_outputs(self):
        spec = importlib.util.spec_from_file_location('stage_runtime', TOOLS / 'stage_runtime.py')
        wrapper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(wrapper)
        module = types.SimpleNamespace(DEFAULT_JNILIBS='', DEFAULT_X86_JNILIBS='', DEFAULT_ASSETS='', DEFAULT_PACKAGE_DIR='', DEFAULT_X86_PACKAGE_DIR='', DEFAULT_PACKAGE='cache/archive.tgz', EXPECTED_SHA256='pinned-hash', reset_output=lambda p: None)
        bucket = self.root / 'fake-bucket'
        wrapper.configure(module, bucket)
        self.assertEqual(module.EXPECTED_SHA256, 'pinned-hash')
        self.assertEqual(module.DEFAULT_X86_JNILIBS, str(bucket / 'app/generated/runtime/jniLibs/x86_64'))
        module.reset_output(module.DEFAULT_JNILIBS)
        with self.assertRaises(ValueError):
            module.reset_output(str(self.repo))


if __name__ == '__main__':
    unittest.main()
