"""Host protocol tests. Uses only owned temporary files; never calls ADB or builds an APK."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "remote/src/main/resources/file-transfer/host_transfer.py"
spec = importlib.util.spec_from_file_location("host_transfer", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class HostTransferTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.host = module.Host(self.root / "cache")
        self.apk = self.root / "a name 'שלום.apk"
        self.apk.write_bytes(b"PK signed apk fixture" * 64)
    def tearDown(self): self.temp.cleanup()
    def request(self, **extra): return {"path": str(self.apk), "allowDelta": False, "bases": [], **extra}
    def test_full_snapshot_is_immutable_when_build_output_is_replaced(self):
        with self.host.lease():
            first = self.host.prepare(self.request())
            original = self.apk.read_bytes()
            self.apk.write_bytes(b"next build")
            fallback = self.host.prepare(self.request(path="sha256:" + first["target"]["sha256"]))
            self.assertEqual(first["target"], fallback["target"])
            self.assertEqual(original, Path(first["payloadPath"]).read_bytes())
            self.assertEqual(self.apk.name, fallback["name"])
    def test_existing_target_never_downloads_a_tool_or_generates(self):
        with self.host.lease():
            first = self.host.prepare(self.request())
            with patch.object(self.host, "tool", side_effect=AssertionError("Must reuse target")):
                again = self.host.prepare(self.request(allowDelta=True, bases=[first["target"]]))
            self.assertEqual("full", again["format"])
    def test_same_size_corrupt_base_is_not_used(self):
        with self.host.lease():
            first = self.host.prepare(self.request())
            Path(first["payloadPath"]).write_bytes(b"X" * first["target"]["size"])
            self.apk.write_bytes(b"PK new version" * 64)
            with patch.object(self.host, "make_patch", side_effect=AssertionError("Corrupt base")):
                result = self.host.prepare(self.request(allowDelta=True, bases=[first["target"]]))
            self.assertEqual("full", result["format"])
    @unittest.skipUnless(os.environ.get("MIKE_DELTA_GENERATOR"), "Set MIKE_DELTA_GENERATOR for real CLI integration")
    def test_real_generator_parameters_produce_a_patch_that_reconstructs(self):
        self.host.generator=Path(os.environ["MIKE_DELTA_GENERATOR"])
        with self.host.lease():
            first=self.host.prepare(self.request())
            self.apk.write_bytes(self.apk.read_bytes()+b"new feature"*400)
            result=self.host.prepare(self.request(allowDelta=True,bases=[first["target"]]))
            self.assertEqual(module.FORMAT,result["format"])
            output=self.root/"rebuilt.apk"
            patcher=self.host.generator.with_name("hpatchz.exe" if os.name=="nt" else "hpatchz")
            subprocess.run([str(patcher),first["payloadPath"],result["payloadPath"],str(output)],check=True,stdout=subprocess.DEVNULL)
            self.assertEqual(self.apk.read_bytes(),output.read_bytes())
    def test_patch_reuse_is_checked_with_its_digest(self):
        old = self.root / "a"; new = self.root / "b"; old.write_bytes(b"old"); new.write_bytes(b"new")
        key = hashlib.sha256(json.dumps([old.name,new.name,module.VERSION,module.OPTIONS],separators=(",",":")).encode()).hexdigest()
        artifact = self.host.root / "patches" / key
        artifact.write_bytes(b"patch")
        artifact.with_suffix(".json").write_text(json.dumps({"sha256":hashlib.sha256(b"patch").hexdigest(),"size":5}))
        with patch.object(self.host, "tool", side_effect=AssertionError("Cached")):
            got, meta = self.host.make_patch(old, new)
        self.assertEqual(artifact, got); self.assertEqual(5, meta["size"])
    def test_stop_before_snapshot_creates_no_blob(self):
        self.host.stopped.set()
        with self.assertRaises(module.Stopped): self.host.prepare(self.request())
        self.assertEqual([], list((self.host.root / "blobs").iterdir()))
    def test_generation_stop_kills_its_child_and_does_not_fall_back(self):
        with self.host.lease():
            old = self.host.prepare(self.request())
            self.apk.write_bytes(b"PK next apk" * 80)
            class Child:
                killed = False
                returncode = None
                def poll(self): return None if not self.killed else -1
                def kill(self): self.killed = True
                def wait(self): return -1
            child = Child()
            def start(*args, **kwargs): self.host.stopped.set(); return child
            with patch.object(self.host, "tool", return_value="hdiffz"), patch.object(module.subprocess, "Popen", side_effect=start):
                with self.assertRaises(module.Stopped): self.host.prepare(self.request(allowDelta=True, bases=[old["target"]]))
            self.assertTrue(child.killed)
            self.assertEqual([], list((self.host.root / "patches").iterdir()))
    def test_external_request_holds_lease_until_release(self):
        process = subprocess.Popen([sys.executable,str(SCRIPT),"--root",str(self.host.root)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            request={"protocol":1,**self.request()}
            process.stdin.write((json.dumps(request)+"\n").encode()); process.stdin.flush()
            while True:
                line=process.stdout.readline()
                self.assertTrue(line)
                if line.startswith(b"HEYMIKE "): break
            self.assertIsNone(process.poll())
            # A second client cannot steal this live OS lock.
            other=module.Host(self.host.root); acquired=threading.Event()
            def acquire():
                with other.lease(): acquired.set()
            thread=threading.Thread(target=acquire); thread.start()
            self.assertFalse(acquired.wait(0.1))
            process.stdin.write(b"release\n"); process.stdin.flush(); process.stdin.close()
            self.assertEqual(0, process.wait(timeout=5)); thread.join(timeout=5)
            self.assertTrue(acquired.is_set())
        finally:
            if process.poll() is None: process.kill(); process.wait()
            process.stdout.close(); process.stderr.close()
    def test_eof_releases_lease_as_stop(self):
        process=subprocess.Popen([sys.executable,str(SCRIPT),"--root",str(self.host.root)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            process.stdin.write((json.dumps({"protocol":1,**self.request()})+"\n").encode()); process.stdin.flush()
            while not process.stdout.readline().startswith(b"HEYMIKE "): pass
            process.stdin.close(); self.assertEqual(130,process.wait(timeout=5))
        finally:
            if process.poll() is None: process.kill(); process.wait()
            process.stdout.close(); process.stderr.close()

if __name__=="__main__": unittest.main()
