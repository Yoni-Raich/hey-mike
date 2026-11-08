"""Both shipped transcript writers append the same records the same way; no live Claude requests."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

RESOURCES = Path(__file__).resolve().parents[1] / "remote/src/main/resources/dev/androidagent/remote"
SID = "11111111-1111-4111-8111-111111111111"


def record(uuid, parent, text):
    return json.dumps({"parentUuid": parent, "isSidechain": False, "type": "user", "uuid": uuid,
                       "message": {"role": "user", "content": text}}, ensure_ascii=False, separators=(",", ":"))


class WritersTest(unittest.TestCase):
    def setUp(self):
        self.runners = [("python", (sys.executable, str(RESOURCES / "claude_transcript.py")))]
        if os.name == "nt" and shutil.which("powershell.exe"):
            self.runners.append(("powershell", ("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                                "-File", str(RESOURCES / "claude_transcript.ps1"), "-Payload")))

    def run_writer(self, runner, root, cwd, records):
        payload = root / "payload.json"
        payload.write_text(json.dumps({"cwd": cwd, "sessionId": SID, "records": records}, ensure_ascii=False), encoding="utf-8")
        result = subprocess.run([*runner, str(payload)], env={**os.environ, "CLAUDE_CONFIG_DIR": str(root)},
                                capture_output=True, encoding="utf-8", timeout=30, check=True)
        self.assertFalse(payload.exists(), "the payload is deleted once read")
        line = next(line for line in result.stdout.splitlines() if line.startswith("HEYMIKE "))
        return json.loads(line[8:])

    def test_a_new_session_goes_in_the_folder_named_for_the_cwd(self):
        for name, runner in self.runners:
            with self.subTest(writer=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                answer = self.run_writer(runner, root, "C:\\src\\my project", [record("a", "@HEYMIKE_LEAF@", "שלום"), record("b", "a", "two")])
                self.assertEqual({"ok": True}, answer)
                file = root / "projects" / "C--src-my-project" / (SID + ".jsonl")
                self.assertEqual([record("a", None, "שלום"), record("b", "a", "two")], file.read_text(encoding="utf-8").splitlines())

    def test_an_existing_session_continues_from_its_last_main_message(self):
        for name, runner in self.runners:
            with self.subTest(writer=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                file = root / "projects" / "elsewhere" / (SID + ".jsonl")
                file.parent.mkdir(parents=True)
                file.write_text(record("u1", None, "hi") + "\n" +
                                json.dumps({"type": "assistant", "uuid": "side", "isSidechain": True}) + "\n" +
                                json.dumps({"type": "queue-operation", "operation": "dequeue"}), encoding="utf-8")
                self.assertEqual({"ok": True}, self.run_writer(runner, root, "/other", [record("n1", "@HEYMIKE_LEAF@", "next")]))
                lines = file.read_text(encoding="utf-8").splitlines()
                self.assertEqual(4, len(lines))
                self.assertEqual(record("n1", "u1", "next"), lines[-1])
                self.assertFalse((root / "projects" / "-other").exists())

    def test_a_bad_session_id_is_refused(self):
        for name, runner in self.runners:
            with self.subTest(writer=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                payload = root / "payload.json"
                payload.write_text(json.dumps({"cwd": "/x", "sessionId": "../../etc", "records": ["{}"]}), encoding="utf-8")
                result = subprocess.run([*runner, str(payload)], env={**os.environ, "CLAUDE_CONFIG_DIR": str(root)},
                                        capture_output=True, encoding="utf-8", timeout=30, check=True)
                line = next(line for line in result.stdout.splitlines() if line.startswith("HEYMIKE "))
                self.assertIn("error", json.loads(line[8:]))
                self.assertFalse((root / "projects").exists())


if __name__ == "__main__":
    unittest.main()
