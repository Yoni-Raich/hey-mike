"""Mike's computer-only Codex updater. No account or conversation files are read.

Each version has its own directory. Only a verified, message-free probe can
change active.json. Existing processes keep their executable and are not stopped.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import queue
import re
import shutil
import subprocess
import tarfile
import tempfile
import threading
import time
import urllib.request
import urllib.parse
import uuid

BUNDLED_VERSION = "0.163.0"
API = "https://api.github.com/repos/openai/codex/releases/latest"
CHECK_SECONDS = 15 * 60
MAX_PACKAGE = 512 * 1024 * 1024
MAX_EXPANDED = 1536 * 1024 * 1024


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}", value):
        raise ValueError("Invalid stable Codex version")
    return tuple(map(int, value.split(".")))


def root_directory():
    if os.name == "nt":
        return Path(os.environ["LOCALAPPDATA"]) / "HeyMike" / "codex"
    return Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share"))) / "heymike/codex"


def target_name():
    arch = {"AMD64": "x86_64", "x86_64": "x86_64", "aarch64": "aarch64", "ARM64": "aarch64"}.get(platform.machine())
    if not arch or platform.system() not in ("Windows", "Linux"):
        raise ValueError("This computer is not supported by Codex")
    return arch + ("-pc-windows-msvc" if os.name == "nt" else "-unknown-linux-musl")


def binary(root, selected):
    version(selected)
    return root / selected / "bin" / ("codex-app-server.exe" if os.name == "nt" else "codex-app-server")


def write_json(path, data):
    part = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        part.write_text(json.dumps(data), encoding="utf-8")
        os.replace(part, path)
    finally:
        part.unlink(missing_ok=True)


def read_json(path):
    try:
        if path.stat().st_size > 1024 * 1024:
            return {}
        result = json.loads(path.read_text(encoding="utf-8"))
        return result if isinstance(result, dict) else {}
    except (OSError, ValueError):
        return {}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@contextlib.contextmanager
def temporary_directory(**kwargs):
    folder = tempfile.mkdtemp(**kwargs)
    try:
        yield folder
    finally:
        # Windows can keep SQLite/antivirus handles briefly after a child exits.
        # Cleanup owns only the exact directory created by this invocation.
        for attempt in range(20):
            try:
                shutil.rmtree(folder)
                break
            except PermissionError:
                if attempt < 19:
                    time.sleep(0.1)
            except FileNotFoundError:
                break


def read_metadata(address=API):
    request = urllib.request.Request(address, headers={"User-Agent": "HeyMike-runtime-updater", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        if urllib.parse.urlparse(response.url).hostname != "api.github.com":
            raise ValueError("Unexpected Codex metadata origin")
        data = response.read(2 * 1024 * 1024 + 1)
        if len(data) > 2 * 1024 * 1024:
            raise ValueError("Codex metadata is too large")
    return json.loads(data)


def release_pin(metadata, target):
    selected = metadata["tag_name"].removeprefix("rust-v")
    version(selected)
    if metadata.get("draft") or metadata.get("prerelease"):
        raise ValueError("Codex release is not stable")
    name = "codex-app-server-package-" + target + ".tar.gz"
    asset = next(a for a in metadata["assets"] if a["name"] == name)
    address = "https://github.com/openai/codex/releases/download/rust-v" + selected + "/" + name
    digest = asset.get("digest", "")
    if asset["browser_download_url"] != address or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("Invalid Codex release asset")
    size = asset["size"]
    if not isinstance(size, int) or not 0 < size <= MAX_PACKAGE:
        raise ValueError("Invalid Codex package size")
    return {"version": selected, "url": address, "sha256": digest[7:], "size": size}


def download(pin, destination):
    request = urllib.request.Request(pin["url"], headers={"User-Agent": "HeyMike-runtime-updater"})
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=30) as response, destination.open("xb") as output:
        parsed = urllib.parse.urlparse(response.url)
        if parsed.scheme != "https" or parsed.hostname not in ("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"):
            raise ValueError("Unexpected Codex download origin")
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            size += len(chunk)
            if size > pin["size"]:
                raise ValueError("Codex download is too large")
            digest.update(chunk)
            output.write(chunk)
    if size != pin["size"] or digest.hexdigest() != pin["sha256"]:
        raise ValueError("Codex download did not match its checksum")


def unpack(archive, folder):
    total, names = 0, set()
    with tarfile.open(archive, "r:gz") as package:
        for item in package:
            path = PurePosixPath(item.name)
            if (path.is_absolute() or ".." in path.parts or "\\" in item.name or ":" in item.name
                    or not (item.isfile() or item.isdir()) or item.name in names):
                raise ValueError("Unsafe Codex archive entry")
            names.add(item.name)
            total += item.size
            if total > MAX_EXPANDED or len(names) > 2048:
                raise ValueError("Codex archive is too large")
            destination = folder.joinpath(*path.parts)
            if item.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with package.extractfile(item) as source, destination.open("xb") as output:
                    shutil.copyfileobj(source, output)
                destination.chmod(0o755 if item.mode & 0o111 else 0o644)


def probe(executable, expected):
    """No prompt, sign-in, tools or real HOME; only --version and initialize."""
    with temporary_directory(prefix="mike-codex-probe-") as folder:
        (Path(folder) / ".codex").mkdir()
        env = dict(os.environ)
        env.update(HOME=folder, USERPROFILE=folder, CODEX_HOME=str(Path(folder) / ".codex"))
        for key in list(env):
            if any(word in key.upper() for word in ("API_KEY", "TOKEN", "SECRET", "PASSWORD")) or key == "CODEX_SANDBOX":
                env.pop(key, None)
        flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        result = subprocess.run([str(executable), "--version"], cwd=folder, env=env, capture_output=True, timeout=15, **flags)
        if result.returncode or result.stdout.decode().strip() != "codex-app-server " + expected:
            raise ValueError("Codex version probe failed")
        request = json.dumps({"id": 1, "method": "initialize", "params": {"clientInfo": {"name": "hey-mike-update-probe", "version": "1.0.0"}, "capabilities": {"experimentalApi": True}}}) + "\n"
        with tempfile.TemporaryFile() as err:
            process = subprocess.Popen([str(executable), "--listen", "stdio://"], cwd=folder, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=err, **flags)
            replies = queue.Queue()
            def read_reply():
                try:
                    total = 0
                    while True:
                        line = process.stdout.readline(1024 * 1024 + 1)
                        total += len(line)
                        if not line or total > 1024 * 1024:
                            raise ValueError("Codex initialize probe returned no bounded reply")
                        reply = json.loads(line)
                        if reply.get("id") == 1:
                            replies.put(reply)
                            return
                except Exception as error:
                    replies.put(error)
            reader = threading.Thread(target=read_reply, daemon=True)
            try:
                reader.start()
                process.stdin.write(request.encode())
                process.stdin.flush()
                reply = replies.get(timeout=20)
                if isinstance(reply, Exception) or not isinstance(reply.get("result"), dict) or "error" in reply:
                    err.seek(0)
                    raise ValueError("Codex initialize probe failed: " + str(reply)[:1000] + "; " + err.read(2000).decode(errors="replace"))
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()
                process.stdin.close()
                reader.join(timeout=2)
                process.stdout.close()


def active_version(root):
    selected = read_json(root / "active.json").get("version")
    if selected:
        try:
            if binary(root, selected).is_file():
                return selected
        except (ValueError, TypeError):
            pass
    # Keep older Mike installs usable if the network is down during an APK upgrade.
    existing = []
    for folder in root.iterdir():
        try:
            if binary(root, folder.name).is_file():
                existing.append(folder.name)
        except ValueError:
            continue
    return max(existing, key=version) if existing else None


@contextlib.contextmanager
def update_lock(root):
    # An OS lock releases on crash; no stale lock file can block later updates.
    with (root / "update.lock").open("a+b") as handle:
        if os.name == "nt":
            import msvcrt
            handle.seek(0)
            if not handle.read(1):
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == "nt":
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def update(root, force=False, existing_only=False, fetch=read_metadata, retrieve=download, validate=probe, now=time.time):
    root.mkdir(parents=True, exist_ok=True)
    current = active_version(root)
    if existing_only and not current:
        return {"installed": False, "automatic": True}
    note = None
    try:
        with update_lock(root):
            current = active_version(root)
            previous_check = read_json(root / "last-check.json").get("at", 0)
            if not force and current and 0 <= now() - previous_check < CHECK_SECONDS:
                return {"version": current, "exe": str(binary(root, current)), "installed": True, "automatic": True}
            write_json(root / "last-check.json", {"at": now()})
            pin = release_pin(fetch(), target_name())
            latest = pin["version"]
            if version(latest) < version(current or BUNDLED_VERSION):
                raise ValueError("Refusing a Codex downgrade")
            if latest != current:
                # TemporaryDirectory owns only this attempt; published versions are never removed.
                with temporary_directory(prefix=".update-", dir=root) as tmp:
                    archive, stage = Path(tmp) / "package.tgz", Path(tmp) / "package"
                    retrieve(pin, archive)
                    stage.mkdir()
                    unpack(archive, stage)
                    executable = stage / "bin" / binary(root, latest).name
                    validate(executable, latest)
                    destination = root / latest
                    if destination.exists():
                        # Another verified installer may have published the same version.
                        for item in stage.rglob("*"):
                            if item.is_file() and sha256(item) != sha256(destination / item.relative_to(stage)):
                                raise ValueError("Existing Codex package differs from the verified download")
                        validate(binary(root, latest), latest)
                    else:
                        os.replace(stage, destination)
                write_json(root / "active.json", {"version": latest, "previous": current, "packageSha256": pin["sha256"]})
                current = latest
    except Exception as error:
        # Network, checksum, archive, compatibility and lock failures keep the working version.
        note = "Codex update deferred (" + type(error).__name__ + "); keeping the installed version"
    return {"version": current, "exe": str(binary(root, current)) if current else None, "installed": current is not None,
            "automatic": True, "updateMessage": note}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--existing-only", action="store_true")
    parser.add_argument("--root", type=Path, default=root_directory())
    args = parser.parse_args()
    print("HEYMIKE " + json.dumps(update(args.root, force=args.force, existing_only=args.existing_only)))


if __name__ == "__main__":
    main()
