"""Append a chat's earlier turns to a Claude Code session transcript. No model or auth request.

The payload file holds {"cwd", "sessionId", "records"}: records are JSON lines,
the first naming "@HEYMIKE_LEAF@" as its parent. Same rules as the app's
ClaudeTranscript.extend. The payload is deleted after it is read.
"""
import json
import os
import re
import sys
from pathlib import Path

UUID = re.compile(r"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
LEAF = '"@HEYMIKE_LEAF@"'
MESSAGES = ("user", "assistant", "system", "attachment")


def leaf(lines):
    for line in reversed(lines):
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and row.get("type") in MESSAGES and row.get("isSidechain") is not True and row.get("uuid"):
            return row["uuid"]
    return None


def extend(payload):
    session = payload["sessionId"]
    if not UUID.match(session):
        raise ValueError("Invalid Claude session ID")
    records = payload["records"]
    if not records:
        return
    config = Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude")
    projects = config / "projects"
    if config.is_symlink() or projects.is_symlink():
        raise ValueError("Claude projects directory is a symlink")
    target = None
    if projects.is_dir():
        for folder in projects.iterdir():
            candidate = folder / (session + ".jsonl")
            if folder.is_dir() and not folder.is_symlink() and candidate.is_file() and not candidate.is_symlink():
                target = candidate
                break
    if target is None:
        name = re.sub(r"[^A-Za-z0-9]", "-", payload["cwd"])
        if len(name) > 200:
            raise ValueError("Working folder path is too long")
        (projects / name).mkdir(parents=True, exist_ok=True)
        target = projects / name / (session + ".jsonl")
    existing = target.read_text(encoding="utf-8") if target.is_file() else ""
    parent = leaf([line for line in existing.splitlines() if line.strip()])
    first = records[0].replace(LEAF, json.dumps(parent) if parent else "null")
    text = "\n".join([first] + records[1:]) + "\n"
    if existing and not existing.endswith("\n"):
        text = "\n" + text
    with open(target, "a", encoding="utf-8", newline="\n") as out:
        out.write(text)


def main():
    try:
        path = Path(sys.argv[1])
        payload = json.loads(path.read_text(encoding="utf-8"))
        path.unlink()
        extend(payload)
        result = {"ok": True}
    except Exception as error:
        result = {"error": str(error)}
    print("HEYMIKE " + json.dumps(result, ensure_ascii=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
