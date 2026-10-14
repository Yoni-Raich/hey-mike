"""One SSH request. Holds its cache lease until stdin release/EOF; no AI or server process."""
import argparse
import contextlib
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import zipfile

VERSION = "5.1.3"
FORMAT = "HDIFFW26-zstd-v5.1.3-s64-w2m-step256k"
OPTIONS = ["-WD-256k", "-s-64", "-c-zstd-3-23", "-w-2m"]
BINS = {
    ("nt", "amd64"): ("windows64", "77f141386e5d8f785c1c846e10fbbc19b6c05aa00e3f59cc44670fb3f0e2ae94"),
    ("posix", "x86_64"): ("linux64", "628963bf2ee9108a97260fa5eef44acd9ec94369b76090a957c9182b3abbb558"),
    ("posix", "aarch64"): ("linux_arm64", "03e404e16d06479deaba645a09ed5c06636778b083b82bc7fc932ba34425430b"),
}

class Stopped(Exception): pass

class Host:
    def __init__(self, root, stopped=None, generator=None):
        self.root = Path(root)
        self.stopped = stopped or threading.Event()
        self.generator = generator
        for name in ("blobs", "patches", "parts", "tools", "receipts"):
            (self.root / name).mkdir(parents=True, exist_ok=True)

    def check(self):
        if self.stopped.is_set(): raise Stopped("Transfer stopped")

    def phase(self, value):
        if getattr(self, "report_phases", False): print("HEYMIKE_PHASE " + value, flush=True)

    @contextlib.contextmanager
    def lease(self):
        # OS locks release on process death. Never steal a timed-out lock from a live worker.
        with open(self.root / "lease", "a+b") as file:
            if file.tell() == 0: file.write(b"0"); file.flush()
            while True:
                self.check()
                try:
                    file.seek(0)
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError: self.stopped.wait(0.05)
            try: yield
            finally:
                file.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(file.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(file, fcntl.LOCK_UN)

    def digest(self, path):
        digest = hashlib.sha256()
        with open(path, "rb") as source:
            while True:
                self.check()
                chunk = source.read(1024 * 1024)
                if not chunk: break
                digest.update(chunk)
        return digest.hexdigest()

    def atomic_json(self, path, data):
        part = self.root / "parts" / (uuid.uuid4().hex + ".json")
        try:
            with open(part, "x", encoding="utf-8") as output:
                json.dump(data, output); output.flush(); os.fsync(output.fileno())
            self.check()
            os.replace(part, path)
        finally: part.unlink(missing_ok=True)

    @contextlib.contextmanager
    def read_source(self, path):
        if os.name == "nt":
            import msvcrt
            from ctypes import wintypes
            create = ctypes.WinDLL("kernel32", use_last_error=True).CreateFileW
            create.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
            create.restype = wintypes.HANDLE
            handle = create(str(path), 0x80000000, 1, None, 3, 0, None)
            if handle == ctypes.c_void_p(-1).value: raise ctypes.WinError(ctypes.get_last_error())
            fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
            with os.fdopen(fd, "rb") as source: yield source
        else:
            # Detect ordinary concurrent writes using fstat and a second content pass.
            with open(path, "rb") as source: yield source

    def snapshot(self, source):
        self.phase("HASHING")
        if source.startswith("sha256:"):
            digest = source[7:]
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest): raise ValueError("Invalid immutable source")
            blob = self.root / "blobs" / digest
            meta = json.loads(blob.with_suffix(".json").read_text())
            if not blob.is_file() or self.digest(blob) != digest: raise ValueError("Immutable source missing or changed")
            return blob, meta
        path = Path(source)
        if not path.is_absolute() or not path.is_file() or path.suffix.lower() != ".apk": raise ValueError("Name a full APK path")
        part = self.root / "parts" / (uuid.uuid4().hex + ".apk")
        try:
            with self.read_source(path) as input:
                before = os.fstat(input.fileno())
                if shutil.disk_usage(self.root).free < before.st_size + 16*1024*1024: raise OSError("Not enough host cache storage")
                digest = hashlib.sha256()
                with open(part, "xb") as output:
                    while True:
                        self.check()
                        chunk = input.read(1024*1024)
                        if not chunk: break
                        digest.update(chunk); output.write(chunk)
                    output.flush(); os.fsync(output.fileno())
                after = os.fstat(input.fileno())
                if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns): raise ValueError("Source changed during snapshot")
                value = digest.hexdigest()
                if os.name != "nt":
                    input.seek(0); verify=hashlib.sha256()
                    while True:
                        self.check(); chunk=input.read(1024*1024)
                        if not chunk: break
                        verify.update(chunk)
                    if verify.hexdigest()!=value or os.fstat(input.fileno()).st_mtime_ns!=before.st_mtime_ns: raise ValueError("Source changed during snapshot")
            blob = self.root / "blobs" / value
            meta = {"sha256":value,"size":part.stat().st_size,"name":path.name}
            aliases=[]
            if blob.with_suffix(".json").is_file():
                try: aliases=json.loads(blob.with_suffix(".json").read_text()).get("names",[])
                except (ValueError,OSError): pass
            meta["names"]=list(dict.fromkeys([path.name,*aliases]))[:8]
            self.check()
            if blob.exists() and self.digest(blob)==value: part.unlink()
            else: os.replace(part,blob)
            self.atomic_json(blob.with_suffix(".json"),meta)
            return blob,meta
        finally: part.unlink(missing_ok=True)

    def tool(self):
        if self.generator: return str(self.generator)
        suffix,digest=BINS[(os.name,platform.machine().lower())]
        folder=self.root/"tools"/VERSION/suffix
        executable=folder/("hdiffz.exe" if os.name=="nt" else "hdiffz")
        name=f"hdiffpatch_v{VERSION}_bin_{suffix}.zip"
        cached=folder/name
        if executable.is_file() and cached.is_file():
            import io
            data=cached.read_bytes()
            if hashlib.sha256(data).hexdigest()==digest:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    member=next(n for n in archive.namelist() if n.endswith("/"+executable.name) or n==executable.name)
                    if self.digest(executable)==hashlib.sha256(archive.read(member)).hexdigest(): return str(executable)
        url=f"https://github.com/sisong/HDiffPatch/releases/download/v{VERSION}/{name}"
        request=urllib.request.Request(url,headers={"User-Agent":"HeyMike-file-transfer"})
        with urllib.request.urlopen(request,timeout=2) as response:
            chunks=[]; total=0
            while True:
                self.check(); chunk=response.read(64*1024)
                if not chunk: break
                total+=len(chunk)
                if total>8*1024*1024: raise ValueError("Delta tool archive is too large")
                chunks.append(chunk)
            data=b"".join(chunks)
        self.check()
        if hashlib.sha256(data).hexdigest()!=digest: raise ValueError("Delta tool checksum mismatch")
        import io
        stage=self.root/"parts"/uuid.uuid4().hex
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for member in archive.infolist():
                    if not (stage/member.filename).resolve().is_relative_to(stage.resolve()): raise ValueError("Unsafe tool archive")
                    if (member.external_attr>>16)&0o170000==0o120000: raise ValueError("Unsafe tool symlink")
                archive.extractall(stage)
            selected=next(stage.rglob(executable.name))
            folder.mkdir(parents=True,exist_ok=True)
            self.check(); os.replace(selected,executable)
            executable.chmod(0o700)
            # Keep the validated archive for provenance and future repair.
            (folder/name).write_bytes(data)
        finally: shutil.rmtree(stage,ignore_errors=True)
        return str(executable)

    def make_patch(self, old, new):
        self.phase("GENERATING")
        key=hashlib.sha256(json.dumps([old.name,new.name,VERSION,OPTIONS],separators=(",",":")).encode()).hexdigest()
        patch=self.root/"patches"/key
        meta_path=patch.with_suffix(".json")
        if patch.is_file() and meta_path.is_file():
            meta=json.loads(meta_path.read_text())
            if patch.stat().st_size==meta["size"] and self.digest(patch)==meta["sha256"]: return patch,meta
        temporary=self.root/"parts"/(uuid.uuid4().hex+".hdiff")
        log=self.root/"parts"/(uuid.uuid4().hex+".log")
        process=None
        try:
            self.check()
            with open(log,"wb") as output:
                process=subprocess.Popen([self.tool(),str(old),str(new),str(temporary),*OPTIONS],stdout=output,stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
                deadline=time.monotonic()+180
                while process.poll() is None:
                    self.check()
                    if time.monotonic()>deadline: raise TimeoutError("Delta generation timed out")
                    self.stopped.wait(0.05)
                if process.returncode: raise RuntimeError("Delta generation failed")
            meta={"sha256":self.digest(temporary),"size":temporary.stat().st_size}
            with open(temporary,"ab") as file: file.flush(); os.fsync(file.fileno())
            self.check(); os.replace(temporary,patch); self.atomic_json(meta_path,meta)
            return patch,meta
        finally:
            if process and process.poll() is None: process.kill(); process.wait()
            temporary.unlink(missing_ok=True); log.unlink(missing_ok=True)

    def prepare(self, request):
        source,meta=self.snapshot(request["path"])
        target={"sha256":meta["sha256"],"size":meta["size"]}
        result={"protocol":1,"target":target,"payload":target,"payloadPath":str(source),"name":meta["name"],"format":"full"}
        # An already-present target must not run generation or provision a tool.
        candidates=request.get("bases",[])[:4]
        if any(x==target for x in candidates): return result
        if request.get("allowDelta",False):
            for candidate in candidates:
                digest=candidate.get("sha256","")
                if len(digest)!=64 or any(c not in "0123456789abcdef" for c in digest): continue
                old=self.root/"blobs"/digest
                if not old.is_file() or old.stat().st_size!=candidate.get("size") or self.digest(old)!=digest: continue
                try:
                    patch,payload=self.make_patch(old,source)
                except Stopped: raise
                except Exception: break # full immutable target remains serviceable
                if payload["size"]<target["size"]*0.85:
                    result.update(payload=payload,payloadPath=str(patch),base=candidate,format=FORMAT)
                break
        return result

    def evict(self, keep):
        # Only service-owned content; never build outputs. Called while the host lease is held.
        files=[p for directory in ("blobs","patches") for p in (self.root/directory).iterdir() if p.is_file() and len(p.name)==64]
        files.sort(key=lambda p:p.stat().st_mtime,reverse=True)
        total=sum(p.stat().st_size for p in files)
        for file in reversed(files):
            if total<=8*1024**3: break
            if str(file) in keep: continue
            size=file.stat().st_size; file.unlink(); file.with_suffix(".json").unlink(missing_ok=True); total-=size
        for file in (self.root/"parts").iterdir():
            if file.is_file() and len(file.stem)==32 and all(c in "0123456789abcdef" for c in file.stem) and file.suffix in (".apk",".hdiff",".json",".log"):
                file.unlink(missing_ok=True)

    def precompute(self, blob, name):
        # One previous build of the same artifact name. Actual phone negotiation still uses digests.
        previous=sorted((self.root/"blobs").glob("*.json"),key=lambda p:p.stat().st_mtime,reverse=True)
        for metadata in previous:
            self.check()
            old=metadata.with_suffix("")
            if old==blob: continue
            try: meta=json.loads(metadata.read_text())
            except (ValueError,OSError): continue
            if name not in meta.get("names",[meta.get("name")]) or not old.is_file() or self.digest(old)!=old.name: continue
            try: self.make_patch(old,blob)
            except Stopped: raise
            except Exception: pass # Precomputation failure never invalidates a successful APK build.
            break

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--root",type=Path); parser.add_argument("--generator",type=Path)
    parser.add_argument("--export",type=Path)
    args=parser.parse_args()
    if args.export:
        host=Host(args.root or Path.home()/".hey-mike/file-transfer/v1")
        with host.lease():
            blob,meta=host.snapshot(str(args.export.resolve()))
            host.precompute(blob,meta["name"])
            host.evict({str(blob)})
            print("HEYMIKE "+json.dumps(meta,separators=(",",":")),flush=True)
        return
    line=sys.stdin.buffer.readline(128*1024+1)
    if len(line)>128*1024 or not line.endswith(b"\n"): raise ValueError("Invalid transfer request")
    request=json.loads(line)
    if request.get("protocol")!=1: raise ValueError("Unsupported transfer protocol")
    stopped=threading.Event(); released=threading.Event()
    def watch():
        if not sys.stdin.buffer.read(1): stopped.set()
        released.set()
    threading.Thread(target=watch,daemon=True).start()
    host=Host(args.root or Path(__file__).parent,stopped,args.generator)
    host.report_phases=True
    host.phase("PREPARING")
    with host.lease():
        result=host.prepare(request)
        print("HEYMIKE "+json.dumps(result,separators=(",",":")),flush=True)
        # Holds base/target/patch against eviction until SFTP and phone reconstruction finish.
        released.wait()
        host.check()
        host.evict({result["payloadPath"],str(host.root/"blobs"/result["target"]["sha256"])})

if __name__=="__main__":
    try: main()
    except Stopped: sys.exit(130)
    except Exception as error:
        print("HEYMIKE "+json.dumps({"error":str(error)}),flush=True); sys.exit(1)
