"""Build the cancellable Android delta adapter from checksum-pinned open-source archives."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PINS = [
    ("hdiff", "https://codeload.github.com/sisong/HDiffPatch/zip/3b9dca715ca492873bf2c49e22e5d5b7d2a78620", "24145493294f10dad98b53ad384ec468dc234a5cb1b1b9889de39f32ddb7a5a1"),
    ("zstd", "https://codeload.github.com/facebook/zstd/zip/refs/tags/v1.5.7", "7897bc5d620580d9b7cd3539c44b59d78f3657d33663fe97a145e07b4ebd69a4"),
    ("xxhash", "https://codeload.github.com/Cyan4973/xxHash/zip/refs/tags/v0.8.3", "db2c12e6f05d45546d3581b6881b25090fd65ab5194891da0b2a1a9c49beda66"),
]

def sources(output):
    result = {}
    for name, url, expected in PINS:
        archive = output / (name + ".zip")
        if not archive.exists():
            request = urllib.request.Request(url, headers={"User-Agent": "HeyMike-file-delta-build"})
            with urllib.request.urlopen(request, timeout=90) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected: raise ValueError("Source download checksum mismatch: " + name)
            archive.write_bytes(data)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected: raise ValueError("Source cache checksum mismatch: " + name)
        folder = output / name
        # Restore verified archive bytes even if an extracted build-cache file was changed.
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                if not (folder / entry.filename).resolve().is_relative_to(folder.resolve()): raise ValueError("Unsafe source archive path")
                # Upstream includes test/doc symlinks; never materialize them.
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    continue
                z.extract(entry, folder)
        result[name] = next(p for p in folder.iterdir() if p.is_dir())
    return result

def compile_android(sdk, output, deps):
    ndk = sdk / "ndk" / "28.2.13676358"
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    toolchain = ndk / "toolchains" / "llvm" / "prebuilt" / host
    clang = toolchain / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    assert clang.is_file(), "Install Android NDK 28.2.13676358"
    hdiff, zstd, xxhash = (deps[k] for k in ("hdiff", "zstd", "xxhash"))
    licenses = output / "assets/file-transfer/licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    for name, source_license in (("HDiffPatch.txt", hdiff / "LICENSE"), ("zstd.txt", zstd / "LICENSE"), ("xxHash.txt", xxhash / "LICENSE")):
        shutil.copyfile(source_license, licenses / name)
    source = [ROOT / "workspace/src/main/cpp/delta_jni.c", hdiff / "libHDiffPatch/HPatch/patch.c"]
    source += [zstd / "lib" / p for p in ("common/debug.c", "common/entropy_common.c", "common/error_private.c", "common/fse_decompress.c", "common/xxhash.c", "common/zstd_common.c", "decompress/huf_decompress.c", "decompress/zstd_ddict.c", "decompress/zstd_decompress.c", "decompress/zstd_decompress_block.c")]
    for abi, target in (("arm64-v8a", "aarch64-linux-android30"), ("x86_64", "x86_64-linux-android30")):
        final = output / "jniLibs" / abi / "libmike_delta.so"
        final.parent.mkdir(parents=True, exist_ok=True)
        fingerprint = hashlib.sha256(Path(__file__).read_bytes() + json.dumps(PINS).encode() + b"".join(p.read_bytes() for p in source)).hexdigest()
        marker = output / (abi + ".sha256")
        if final.exists() and marker.exists():
            try:
                cached=json.loads(marker.read_text())
                if cached["inputs"]==fingerprint and cached["sha256"]==hashlib.sha256(final.read_bytes()).hexdigest(): continue
            except (ValueError,KeyError): pass
        command = [str(clang), "--target="+target, "--sysroot="+str(toolchain / "sysroot"), "-shared", "-fPIC", "-O2", "-std=c11", "-D_FILE_OFFSET_BITS=64", "-D_POSIX_C_SOURCE=200809L", "-D_IS_USED_MULTITHREAD=0", "-DNDEBUG", "-DZSTD_DISABLE_ASM=1", "-DZSTD_LEGACY_SUPPORT=0", "-I"+str(hdiff), "-I"+str(zstd / "lib"), "-I"+str(xxhash), "-Wl,-z,max-page-size=16384", "-Wl,-z,relro,-z,now", *map(str, source), "-o", str(final)]
        subprocess.run(command, check=True)
        marker.write_text(json.dumps({"inputs":fingerprint,"sha256":hashlib.sha256(final.read_bytes()).hexdigest()}))
        print("Built", abi, final.stat().st_size)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    compile_android(args.sdk, args.output, sources(args.output))

if __name__ == "__main__":
    main()
