"""Read-only Claude Code transcript projection. No SDK, model or auth request."""
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path

UUID = re.compile(r"^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
MAX_FILE = 256 * 1024 * 1024
MAX_LINE = 32 * 1024 * 1024
MAX_OUTPUT = 8 * 1024 * 1024
SLICE = 512 * 1024


def roots():
    home = Path.home()
    config = Path(os.environ.get("CLAUDE_CONFIG_DIR") or home / ".claude")
    if config.is_symlink():
        raise ValueError("Claude configuration directory is a symlink")
    return config, config / "projects"


def files(projects):
    if not projects.exists():
        return []
    if projects.is_symlink():
        raise ValueError("Claude projects directory is a symlink")
    found = []
    for folder in projects.iterdir():
        if folder.is_symlink() or not folder.is_dir():
            continue
        for file in folder.iterdir():
            if file.suffix == ".jsonl" and UUID.fullmatch(file.stem) and not file.is_symlink() and file.is_file():
                found.append(file)
                if len(found) > 10000:
                    raise ValueError("Too many Claude transcripts; narrow the project inventory")
    return sorted(found, key=lambda p: p.stat().st_mtime, reverse=True)


def stamp(value):
    try:
        return int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp() * 1000)
    except (ValueError, TypeError, OverflowError):
        return 0


def content_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(b["text"] for b in content if isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str))
    return ""


def slices(file):
    with file.open("rb") as stream:
        size = file.stat().st_size
        head = stream.read(SLICE)
        if size <= SLICE:
            data = head
        else:
            stream.seek(max(SLICE, size - SLICE))
            tail = stream.read(SLICE).split(b"\n", 1)
            data = head.rsplit(b"\n", 1)[0] + b"\n" + (tail[1] if len(tail) == 2 else b"")
    for line in data.splitlines():
        try:
            row = json.loads(line)
            if isinstance(row, dict):
                yield row
        except (ValueError, UnicodeError):
            continue


def metadata(file, busy_ids):
    cwd = ""
    custom = title = summary = first = model = ""
    for row in slices(file):
        if row.get("isSidechain") or row.get("teamName"):
            continue
        cwd = row.get("cwd") or cwd
        typ = row.get("type")
        if typ == "custom-title":
            custom = row.get("customTitle") or custom
        elif typ == "ai-title":
            title = row.get("aiTitle") or title
        elif typ == "summary":
            summary = row.get("summary") or summary
        elif typ == "user" and not first and not row.get("isMeta"):
            first = content_text(row.get("message", {}).get("content"))[:140]
        elif typ == "assistant":
            model = row.get("message", {}).get("model") or model
    if not isinstance(cwd, str) or not (cwd.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", cwd) or cwd.startswith("\\\\")):
        return None
    name = custom or title or summary
    return {"id": file.stem, "cwd": cwd, "title": str(name or first or "Claude conversation")[:200],
            "name": str(name)[:200] if name else None, "updatedAt": int(file.stat().st_mtime * 1000),
            "model": model or None, "busy": file.stem in busy_ids}


def active_ids(config):
    ids = set()
    proc = Path("/proc")
    if proc.is_dir():
        for folder in proc.iterdir():
            if not folder.name.isdigit():
                continue
            try:
                args = (folder / "cmdline").read_bytes()[:65536].decode("utf-8", "replace").split("\0")
                if "claude" not in " ".join(args[:2]).lower():
                    continue
                for i, arg in enumerate(args[:-1]):
                    if arg in ("--resume", "--session-id", "-r") and UUID.fullmatch(args[i + 1]):
                        ids.add(args[i + 1])
            except OSError:
                continue
    sessions = config / "sessions"
    if sessions.is_dir() and not sessions.is_symlink():
        for file in sessions.glob("*.json"):
            if file.is_symlink() or file.stat().st_size > 65536:
                continue
            try:
                row = json.loads(file.read_text(encoding="utf-8"))
                pid = int(row.get("pid") or file.stem)
                if os.name == "nt":
                    # os.kill(pid, 0) is not a liveness probe on Windows.
                    import ctypes
                    kernel = ctypes.windll.kernel32
                    kernel.OpenProcess.restype = ctypes.c_void_p
                    handle = kernel.OpenProcess(0x1000, False, pid)
                    if not handle:
                        continue
                    try:
                        exit_code = ctypes.c_ulong()
                        if not kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(exit_code)) or exit_code.value != 259:
                            continue
                    finally:
                        kernel.CloseHandle(ctypes.c_void_p(handle))
                else:
                    os.kill(pid, 0)
                sid = row.get("sessionId") or row.get("session_id")
                if isinstance(sid, str) and UUID.fullmatch(sid):
                    ids.add(sid)
            except (OSError, ValueError, TypeError):
                continue
    return ids


def messages(file):
    if file.stat().st_size > MAX_FILE:
        raise ValueError("Claude transcript exceeds the 256 MiB read limit")
    nodes = {}
    leaf = None
    read_bytes = 0
    with file.open("rb") as stream:
        while True:
            line = stream.readline(MAX_LINE + 1)
            if not line:
                break
            read_bytes += len(line)
            if read_bytes > MAX_FILE:
                raise ValueError("Claude transcript exceeds the 256 MiB read limit")
            if len(line) > MAX_LINE:
                raise ValueError("Claude transcript record exceeds the 32 MiB limit")
            if not line.endswith(b"\n"):
                # A writer may be appending the final record. Never accept half of it.
                break
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except (ValueError, UnicodeError) as error:
                raise ValueError("Malformed complete record in Claude transcript") from error
            if not isinstance(row, dict) or row.get("isSidechain"):
                continue
            uid = row.get("uuid")
            if not isinstance(uid, str):
                continue
            # A fork can retain the original sessionId on copied ancestors.
            # The file's UUID is its identity. Compaction follows parentUuid;
            # logicalParentUuid would reconnect replaced history and can cycle.
            parent = row.get("parentUuid")
            typ = row.get("type")
            if typ not in ("user", "assistant", "progress", "system", "attachment"):
                continue
            text = ""
            main = not row.get("isMeta") and not row.get("teamName")
            if typ in ("user", "assistant") and main:
                text = content_text(row.get("message", {}).get("content"))
                leaf = uid
            nodes[uid] = {"id": uid, "parent": parent, "role": typ, "text": text,
                          "main": main, "order": read_bytes, "createdAt": stamp(row.get("timestamp"))}
    # Trailing progress/internal records must not select an older branch.
    parents = {node["parent"] for node in nodes.values()}
    leaves = []
    for terminal in nodes.keys() - parents:
        cursor, seen = terminal, set()
        while cursor in nodes and cursor not in seen:
            seen.add(cursor)
            node = nodes[cursor]
            if node["role"] in ("user", "assistant"):
                if node["main"]:
                    leaves.append(node)
                break
            cursor = node["parent"]
    if leaves:
        leaf = max(leaves, key=lambda node: node["order"])["id"]
    chain = []
    visited = set()
    while leaf and leaf in nodes:
        if leaf in visited:
            raise ValueError("Claude transcript has a parent cycle")
        visited.add(leaf)
        node = nodes[leaf]
        if node["role"] in ("user", "assistant") and node["text"]:
            chain.append({key: node[key] for key in ("id", "role", "text", "createdAt")})
        leaf = node["parent"]
    if leaf:
        raise ValueError("Claude history has a missing parent record; its source transcript is required")
    return list(reversed(chain))


def project(mode, sid=None):
    config, projects = roots()
    busy_ids = active_ids(config)
    candidates = files(projects)
    if mode == "list":
        items = [item for file in candidates if (item := metadata(file, busy_ids)) is not None]
        counts = {}
        for item in items:
            counts[item["id"]] = counts.get(item["id"], 0) + 1
        items = [item for item in items if counts[item["id"]] == 1]
        return {"threads": items, "skipped": len(candidates) - len(items)}
    if not isinstance(sid, str) or not UUID.fullmatch(sid):
        raise ValueError("Invalid Claude session ID")
    selected = [file for file in candidates if file.stem == sid]
    if not selected:
        raise ValueError("Claude session was not found; its original transcript is required")
    if len(selected) != 1:
        raise ValueError("Claude session ID occurs in more than one project; resolve the duplicate first")
    item = metadata(selected[0], busy_ids)
    if item is None:
        raise ValueError("Claude session has no valid working folder")
    if mode == "busy":
        return {"thread": item, "busy": sid in busy_ids}
    if mode != "read":
        raise ValueError("Unknown Claude session operation")
    return {"thread": item, "messages": messages(selected[0]), "busy": sid in busy_ids}


def main():
    try:
        payload = project(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
        encoded = json.dumps(payload, ensure_ascii=True, separators=(",", ":"))
        if len(encoded.encode("utf-8")) > MAX_OUTPUT:
            raise ValueError("Claude history exceeds the 8 MiB display limit")
    except Exception as error:
        encoded = json.dumps({"error": str(error)}, ensure_ascii=True, separators=(",", ":"))
    print("HEYMIKE " + encoded)


if __name__ == "__main__":
    main()
