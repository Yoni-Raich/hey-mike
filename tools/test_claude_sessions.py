"""Shared native-store fixtures for both shipped readers; no live Claude requests."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid

RESOURCES = Path(__file__).resolve().parents[1] / "remote/src/main/resources/dev/androidagent/remote"
SID = "11111111-1111-4111-8111-111111111111"


class ReadersTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mike-claude-reader-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.file = self.root / "projects/encoded-project" / (SID + ".jsonl")
        self.file.parent.mkdir(parents=True)
        self.runners = [(sys.executable, str(RESOURCES / "claude_sessions.py"))]
        if os.name == "nt" and shutil.which("powershell.exe"):
            self.runners.append(("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(RESOURCES / "claude_sessions.ps1")))

    def row(self, role="user", text="hello", parent=None, **extra):
        return dict(type=role, uuid=str(uuid.uuid4()), parentUuid=parent, sessionId=SID,
                    cwd="C:\\src\\שלום 'project'", timestamp="2026-10-10T20:00:00Z",
                    message=dict(content=text), **extra)

    def write(self, rows, suffix=b""):
        self.file.write_bytes(b"".join(json.dumps(row, ensure_ascii=False).encode() + b"\n" for row in rows) + suffix)

    def check(self, mode, verify, sid=SID):
        for runner in self.runners:
            with self.subTest(reader=runner[0], mode=mode):
                arguments = [mode] + ([sid] if mode != "list" else [])
                if runner[0] == "powershell.exe":
                    arguments = ["-Mode", mode] + (["-SessionId", sid] if mode != "list" else [])
                result = subprocess.run([*runner, *arguments], env={**os.environ, "CLAUDE_CONFIG_DIR": str(self.root)},
                                        capture_output=True, encoding="utf-8", timeout=30, check=True)
                line = next(line for line in result.stdout.splitlines() if line.startswith("HEYMIKE "))
                verify(json.loads(line[8:]))

    def test_branch_unicode_text_only_and_partial_tail(self):
        user = self.row(text="סכם את הקוד")
        obsolete = self.row("assistant", "old branch", user["uuid"])
        reply = self.row("assistant", [{"type": "text", "text": "תשובה"}, {"type": "image", "source": {"data": "PRIVATE_IMAGE"}},
                                      {"type": "tool_use", "input": {"secret": "PRIVATE_TOOL"}}], user["uuid"])
        hidden = self.row("user", "hidden context", reply["uuid"], isMeta=True)
        final = self.row("assistant", "final", hidden["uuid"])
        side = self.row("assistant", "sidechain", final["uuid"], isSidechain=True)
        self.write([user, obsolete, reply, hidden, final, side], b'{"type":"user",')
        self.check("read", lambda row: self.assertEqual(["סכם את הקוד", "תשובה", "final"], [m["text"] for m in row["messages"]]))

    def test_title_and_native_identity(self):
        user = self.row()
        self.write([user, dict(type="ai-title", aiTitle="auto", sessionId=SID), dict(type="custom-title", customTitle="Chosen title", sessionId=SID)])
        def verify(row):
            self.assertEqual(0, row["skipped"])
            item = row["threads"][0]
            self.assertEqual((SID, "Chosen title", user["cwd"]), (item["id"], item["title"], item["cwd"]))
        self.check("list", verify)

    def test_complete_corruption_is_reported(self):
        self.write([self.row()], b'{"invalid":\n')
        self.check("read", lambda row: self.assertIn("Malformed complete", row["error"]))

    def test_valid_unterminated_record_is_not_committed(self):
        user = self.row()
        self.write([user], json.dumps(self.row("assistant", "not committed", user["uuid"])).encode())
        self.check("read", lambda row: self.assertEqual(["hello"], [m["text"] for m in row["messages"]]))

    def test_missing_duplicate_invalid_ids_and_cycles(self):
        self.write([self.row()])
        self.check("read", lambda row: self.assertIn("not found", row["error"]), str(uuid.uuid4()))
        duplicate = self.root / "projects/another" / self.file.name
        duplicate.parent.mkdir()
        shutil.copyfile(self.file, duplicate)
        self.check("read", lambda row: self.assertIn("more than one", row["error"]))
        self.check("list", lambda row: self.assertEqual((0, 2), (len(row["threads"]), row["skipped"])))
        duplicate.unlink()  # Own fixture, never a user's transcript.
        cyclic = self.row()
        cyclic["parentUuid"] = cyclic["uuid"]
        self.write([cyclic])
        self.check("read", lambda row: self.assertIn("cycle", row["error"]))

    def test_live_pid_is_busy_and_stale_pid_is_not(self):
        self.write([self.row()])
        metadata = self.root / "sessions/active.json"
        metadata.parent.mkdir()
        metadata.write_text(json.dumps(dict(pid=os.getpid(), sessionId=SID)))
        self.check("busy", lambda row: self.assertTrue(row["busy"]))
        metadata.write_text(json.dumps(dict(pid=2147483647, sessionId=SID)))
        self.check("busy", lambda row: self.assertFalse(row["busy"]))

    def test_large_image_record_is_not_transferred(self):
        user = self.row()
        image = self.row("user", [{"type": "image", "source": {"data": "A" * 1_200_000}}], user["uuid"])
        reply = self.row("assistant", "image understood", image["uuid"])
        self.write([user, image, reply])
        self.check("read", lambda row: self.assertEqual(["hello", "image understood"], [m["text"] for m in row["messages"]]))
        self.check("list", lambda row: self.assertEqual(1, len(row["threads"])))

    def test_compaction_uses_summary_without_logical_parent_cycle(self):
        user = self.row()
        compact = self.row("system", "", None, logicalParentUuid=user["uuid"])
        summary = self.row("user", "compacted summary", compact["uuid"], isCompactSummary=True)
        compact["logicalParentUuid"] = summary["uuid"]
        answer = self.row("assistant", "after compaction", summary["uuid"])
        self.write([user, compact, summary, answer])
        self.check("read", lambda row: self.assertEqual(["compacted summary", "after compaction"], [m["text"] for m in row["messages"]]))
        self.check("read", lambda row: self.assertIn("Invalid Claude session ID", row["error"]), "../credentials")
        self.write([self.row(parent=str(uuid.uuid4()))])
        self.check("read", lambda row: self.assertIn("missing parent", row["error"]))

    def test_fork_keeps_foreign_session_ids_and_internal_tail_does_not_change_branch(self):
        user = self.row()
        user["sessionId"] = str(uuid.uuid4())
        reply = self.row("assistant", "fork answer", user["uuid"])
        # A re-emitted root and trailing orphan progress are not the active leaf.
        progress = self.row("progress", "", None)
        self.write([user, reply, user, progress])
        self.check("read", lambda row: self.assertEqual(["hello", "fork answer"], [m["text"] for m in row["messages"]]))
        self.check("list", lambda row: self.assertEqual(SID, row["threads"][0]["id"]))

    def test_complete_null_record_is_corruption_in_both_readers(self):
        self.write([self.row()], b'\x00\x00{"type":"user"}\n')
        self.check("read", lambda row: self.assertIn("Malformed complete", row["error"]))

    def test_symlinked_project_is_not_followed(self):
        outside = self.root / "outside"
        outside.mkdir()
        shutil.copyfile(self.file if self.file.exists() else self.write_file(), outside / self.file.name)
        link = self.root / "projects/link"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("Creating symlinks is not permitted on this test host")
        self.check("list", lambda row: self.assertEqual(1, len(row["threads"])))

    def write_file(self):
        self.write([self.row()])
        return self.file


if __name__ == "__main__":
    unittest.main()
