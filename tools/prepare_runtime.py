#!/usr/bin/env python3
"""Stage the official Codex app-server package for the Android APK.

Pipeline (stdlib only, reproducible):
  1. Verify SHA-256 of the official rust-v0.156.0 ARM64 and x86_64 musl packages.
  2. Safely extract them to .codex-work/runtime/package-* (no absolute paths,
     no "..", no symlinks/hardlinks, no devices).
  3. Copy native ELFs to app/build/generated/runtime/jniLibs/<abi>/ as .so
     files (targetSdk 35 can only execute APK nativeLibraryDir files).
  4. Verify each staged .so has the expected ELF machine for its ABI.
  5. Verify a pinned Mozilla CA PEM bundle and write it beside the runtime
     metadata (the app copies it to app-private storage before launch).
  6. Write app/build/generated/runtime/assets/runtime/ metadata
     (codex-package.json copy + runtime-manifest.json).
  7. arm64-v8a only: stage the pinned Alpine musl loader as libld_musl.so
     (it launches the downloaded Claude Code binary, which is never bundled)
     and ship the pinned musl licence as assets/runtime/musl-COPYRIGHT.

Root wires the Gradle side (jniLibs/assets srcDirs) and runs device tests.
This script never touches credentials and never claims device success.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import sys
import tarfile
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXPECTED_SHA256 = "817e464eec79ae7b3af56ea1395e4ddd58387e76e294bac0721dc58ceddec636"
PACKAGE_VERSION = "0.156.0"
PACKAGE_TARGET = "aarch64-unknown-linux-musl"
X86_PACKAGE_TARGET = "x86_64-unknown-linux-musl"

# Cached archives carry the version so a bump downloads the new package
# instead of failing the hash check against an older cached one.
DEFAULT_PACKAGE = os.path.join(
    ".codex-work", "runtime", "codex-package-%s.tar.gz" % PACKAGE_VERSION
)
DEFAULT_PACKAGE_DIR = os.path.join(".codex-work", "runtime", "package")
DEFAULT_X86_PACKAGE = os.path.join(
    ".codex-work", "runtime", "codex-package-%s-x86_64.tar.gz" % PACKAGE_VERSION
)
DEFAULT_X86_PACKAGE_DIR = os.path.join(".codex-work", "runtime", "package-x86_64")
DEFAULT_JNILIBS = os.path.join(
    "app", "build", "generated", "runtime", "jniLibs", "arm64-v8a"
)
DEFAULT_X86_JNILIBS = os.path.join(
    "app", "build", "generated", "runtime", "jniLibs", "x86_64"
)
DEFAULT_ASSETS = os.path.join(
    "app", "build", "generated", "runtime", "assets", "runtime"
)
DEFAULT_CA_BUNDLE = os.path.join(".codex-work", "runtime", "cacert.pem")

# Public Mozilla-derived CA list; the hash makes builds fail closed if the
# upstream file changes. Pin a dated file: the undated URL is replaced with
# each curl release and breaks every clean build.
CA_BUNDLE_URL = "https://curl.se/ca/cacert-2026-08-13.pem"
CA_BUNDLE_SHA256 = "f66dff1bdf8f96060b8177976f8b7d9254bc89bc4db933d769f7384d28480bc9"

# Canonical package path -> staged lib name. Upstream tarballs contain
# codex-resources/bwrap; AndroidRuntimeHost adds an intentional runtime-only
# alias codex-path/bwrap -> libcodex_bwrap.so so bwrap is discoverable on PATH.
LIB_MAPPING = {
    "bin/codex-app-server": "libcodex_app_server.so",
    # Android extracts an APK native-library entry into nativeLibraryDir only
    # when its name starts with "lib" and ends with ".so". Debuggable builds
    # are exempt, which is how a name without the prefix worked in debug APKs
    # and went missing in release ones. The app-server is patched at staging
    # time to request this exact name from nativeLibraryDir.
    "bin/codex-code-mode-host": "libcodex_codemode.so",
    "codex-path/rg": "libcodex_rg.so",
    "codex-resources/bwrap": "libcodex_bwrap.so",
    "codex-resources/zsh/bin/zsh": "libcodex_zsh.so",
}

# Alpine's musl dynamic loader starts the official, unmodified linux-arm64-musl
# Claude Code binary from app storage (targetSdk 35 cannot exec app files, and
# /system/bin/linker64 cannot load a musl binary). Pinned package and file;
# every mismatch fails the build. The Claude binary itself is never bundled.
MUSL_APK_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/musl-1.2.5-r12.apk"
MUSL_APK_SHA256 = "ac281d1e7f9e9c447c51e309317b975f48be6edaf3ab91ae73b959cf86703782"
DEFAULT_MUSL_APK = os.path.join(".codex-work", "runtime", "musl-1.2.5-r12-aarch64.apk")
MUSL_LOADER_MEMBER = "lib/ld-musl-aarch64.so.1"
MUSL_LOADER_SHA256 = "b5afeb0dcc9e22e92f1088566b0d1c1562ef567a4abebaf4b0fd14000800b8ed"
MUSL_LOADER_SIZE = 723480
MUSL_LOADER_LIB_NAME = "libld_musl.so"
MUSL_LOADER_ABIS = ("arm64-v8a",)
# The Alpine package carries no licence file, so ship upstream's COPYRIGHT for
# the same musl release (MIT).
MUSL_LICENSE_URL = "https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT?h=v1.2.5"
MUSL_LICENSE_SHA256 = "f9bc4423732350eb0b3f7ed7e91d530298476f8fec0c6c427a1c04ade22655af"
DEFAULT_MUSL_LICENSE = os.path.join(".codex-work", "runtime", "musl-1.2.5-COPYRIGHT")
MUSL_LICENSE_ASSET = "musl-COPYRIGHT"
MAX_EXTRACTED_MEMBER_BYTES = 64 * 1024 * 1024

EM_AARCH64 = 183
EM_X86_64 = 62
ELF_MAGIC = b"\x7fELF"
CODE_MODE_HOST_NAME = b"codex-code-mode-host"
CODE_MODE_HOST_ANDROID_NAME = b"libcodex_codemode.so"


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print("prepare_runtime: ERROR: " + message, file=sys.stderr)
    raise SystemExit(1)


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_package(url: str, dest: str) -> None:
    print("prepare_runtime: downloading " + url)
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    tmp = dest + ".part"
    try:
        with urllib.request.urlopen(url) as response, open(tmp, "wb") as handle:
            shutil.copyfileobj(response, handle, 1024 * 1024)
    except Exception as exc:
        if os.path.exists(tmp):
            os.remove(tmp)
        fail("download failed: %s" % exc)
    os.replace(tmp, dest)


def verify_hash(path: str, expected: str) -> None:
    actual = sha256_file(path)
    if actual.lower() != expected.lower():
        fail(
            "SHA-256 mismatch for %s\n  expected %s\n  actual   %s"
            % (path, expected, actual)
        )
    print("prepare_runtime: sha256 ok " + actual)


def reset_output(path: str) -> None:
    resolved = os.path.realpath(path)
    allowed = [os.path.realpath(os.path.join(REPO_ROOT, '.codex-work', 'runtime')),
               os.path.realpath(os.path.join(REPO_ROOT, 'app', 'build', 'generated', 'runtime'))]
    if not any(resolved != base and os.path.commonpath([resolved, base]) == base for base in allowed):
        fail('output must be a child of a dedicated runtime build directory: ' + resolved)
    if os.path.isdir(resolved):
        shutil.rmtree(resolved)
    os.makedirs(resolved, exist_ok=True)


def member_parts(name: str) -> list[str]:
    """Path parts of a safe relative member name; fails on anything else."""
    if not name or name.startswith("/") or name.startswith("\\"):
        fail("refusing absolute member: %r" % name)
    parts = name.replace("\\", "/").split("/")
    if "" in parts or "." in parts or ".." in parts:
        fail("refusing unsafe member: %r" % name)
    return parts


def safe_extract(archive: str, dest: str) -> list[str]:
    """Extract with strict guards. Returns sorted member names."""
    reset_output(dest)
    dest_real = os.path.realpath(dest)
    names: list[str] = []
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            name = member.name
            parts = member_parts(name)
            if member.issym() or member.islnk():
                fail("refusing link member: %r" % name)
            if member.isdev():
                fail("refusing device member: %r" % name)
            target_real = os.path.realpath(os.path.join(dest, *parts))
            if target_real != dest_real and not target_real.startswith(
                dest_real + os.sep
            ):
                fail("refusing escaping member: %r" % name)
            names.append(name)
        # Python >=3.12 data filter as a second guard; strict checks above apply
        # on every interpreter.
        try:
            tar.extractall(dest, filter="data")  # type: ignore[call-arg]
        except TypeError:
            tar.extractall(dest)
    return sorted(names)


def extract_archive_member(archive: str, member_name: str, dest: str) -> None:
    """Copy one regular file out of a tar.gz into [dest], with safe-extract rules.

    Alpine .apk files are several gzip streams (signature, control, data)
    concatenated; the first tars are cut without end-of-archive blocks.
    gzip reads the streams as one, and ignore_zeros keeps reading past any
    end-of-archive block. Every member name must be safe; the wanted member
    must appear exactly once and be a regular file. Links and devices are
    never written. Nothing else is extracted.
    """
    found = None
    with tarfile.open(archive, "r:gz", ignore_zeros=True) as tar:
        for member in tar.getmembers():
            member_parts(member.name)
            if member.name != member_name:
                continue
            if found is not None:
                fail("duplicate member %r in %s" % (member_name, archive))
            if not member.isreg():
                fail("member %r is not a regular file in %s" % (member_name, archive))
            if member.size <= 0 or member.size > MAX_EXTRACTED_MEMBER_BYTES:
                fail("member %r has an invalid size %d" % (member_name, member.size))
            found = member
        if found is None:
            fail("member %r not found in %s" % (member_name, archive))
        source = tar.extractfile(found)
        if source is None:
            fail("cannot read member %r in %s" % (member_name, archive))
        tmp = dest + ".part"
        try:
            with source, open(tmp, "wb") as handle:
                shutil.copyfileobj(source, handle, 1024 * 1024)
            os.replace(tmp, dest)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)


def stage_musl_loader(
    apk: str,
    jnilibs: str,
    expected_sha256: str = MUSL_LOADER_SHA256,
    expected_size: int = MUSL_LOADER_SIZE,
) -> dict:
    """Stage the musl loader as lib*.so; remove it again on any mismatch."""
    if not is_extractable_lib_name(MUSL_LOADER_LIB_NAME):
        fail("staged name %s is not lib*.so" % MUSL_LOADER_LIB_NAME)
    dest = os.path.join(jnilibs, MUSL_LOADER_LIB_NAME)
    try:
        extract_archive_member(apk, MUSL_LOADER_MEMBER, dest)
        size = os.path.getsize(dest)
        if size != expected_size:
            fail("musl loader size %d, expected %d" % (size, expected_size))
        verify_hash(dest, expected_sha256)
        check_elf(dest, EM_AARCH64, "arm64-v8a")
    except SystemExit:
        if os.path.exists(dest):
            os.remove(dest)
        raise
    os.chmod(dest, 0o755)
    print("prepare_runtime: staged %s -> %s" % (MUSL_LOADER_MEMBER, MUSL_LOADER_LIB_NAME))
    return {
        "abi": "arm64-v8a",
        "package_path": MUSL_LOADER_MEMBER,
        "lib_name": MUSL_LOADER_LIB_NAME,
        "size": size,
        "sha256": sha256_file(dest),
        "elf_machine": EM_AARCH64,
        "source": MUSL_APK_URL,
        "source_sha256": MUSL_APK_SHA256,
        "license": "MIT (assets/runtime/%s)" % MUSL_LICENSE_ASSET,
    }


def check_required_layout(names: list[str]) -> None:
    present = set(names)
    missing = [p for p in list(LIB_MAPPING) + ["codex-package.json"] if p not in present]
    if missing:
        fail("package missing required entries: " + ", ".join(missing))


def validate_ca_pem(path: str) -> None:
    size = os.path.getsize(path)
    if size <= 0 or size > 8 * 1024 * 1024:
        fail("CA bundle has an invalid size: %s" % path)
    with open(path, "rb") as handle:
        # The PEM blocks are ASCII, but curl's bundle also contains UTF-8
        # comments with CA names. Decode those comments without accepting
        # arbitrary binary data.
        text = handle.read().decode("utf-8", errors="strict")
    count = text.count("-----BEGIN CERTIFICATE-----")
    if count < 1 or "-----END CERTIFICATE-----" not in text:
        fail("CA bundle is not a PEM certificate bundle: %s" % path)
    print("prepare_runtime: CA bundle ok certificates=%d sha256=%s" % (count, sha256_file(path)))


def check_elf(path: str, expected_machine: int, abi: str) -> int:
    with open(path, "rb") as handle:
        header = handle.read(64)
    if len(header) < 20 or header[0:4] != ELF_MAGIC:
        fail("not an ELF file: " + path)
    if header[4] != 2 or header[5] != 1:
        fail(
            "not ELF64 little-endian: %s (class=%d data=%d)"
            % (path, header[4], header[5])
        )
    _e_type, e_machine = struct.unpack_from("<HH", header, 16)
    if e_machine != expected_machine:
        fail("not %s (e_machine=%d): %s" % (abi, e_machine, path))
    return e_machine


def is_extractable_lib_name(name: str) -> bool:
    """Whether Android extracts this APK native-library entry in a release build."""
    return name.startswith("lib") and name.endswith(".so") and "/" not in name


def patch_code_mode_host_lookup(path: str) -> None:
    """Point the staged app-server at the Android-packaged helper name.

    Codex 0.156.0 resolves the helper as a sibling named
    ``codex-code-mode-host``. Android only extracts native-library entries
    from the APK when their name starts with ``lib`` and ends with ``.so``
    (debuggable apps are exempt), so the exact sibling cannot exist in the
    ``nativeLibraryDir`` of a release build. The final occurrence is the compiled
    install-context constant; the earlier occurrence is user-facing error
    text and must remain unchanged. The Rust string is stored adjacent to
    other read-only data, so the replacement must have the exact same width:
    padding with NUL bytes would make them part of the Path and cause process
    spawning to fail. Fail closed if the pinned binary layout changes instead
    of silently patching an unknown string.
    """
    with open(path, "rb") as handle:
        data = bytearray(handle.read())
    positions: list[int] = []
    start = 0
    while True:
        position = data.find(CODE_MODE_HOST_NAME, start)
        if position < 0:
            break
        positions.append(position)
        start = position + 1
    if len(positions) != 2:
        fail(
            "expected two code-mode host strings in pinned app-server, found %d: %s"
            % (len(positions), path)
        )
    if len(CODE_MODE_HOST_ANDROID_NAME) != len(CODE_MODE_HOST_NAME):
        fail("Android code-mode host alias must have the same length as upstream name")
    if b"\0" in CODE_MODE_HOST_ANDROID_NAME:
        fail("Android code-mode host alias contains an embedded NUL")
    position = positions[-1]
    data[position : position + len(CODE_MODE_HOST_NAME)] = CODE_MODE_HOST_ANDROID_NAME
    if b"\0" in data[position : position + len(CODE_MODE_HOST_NAME)]:
        fail("patched code-mode host lookup contains an embedded NUL")
    with open(path, "wb") as handle:
        handle.write(data)
    print(
        "prepare_runtime: patched app-server helper lookup %s -> %s"
        % (CODE_MODE_HOST_NAME.decode(), CODE_MODE_HOST_ANDROID_NAME.decode())
    )


def stage_libraries(
    package_dir: str,
    jnilibs: str,
    abi: str,
    target: str,
    expected_machine: int,
) -> list[dict]:
    reset_output(jnilibs)
    staged: list[dict] = []
    for package_path in sorted(LIB_MAPPING):
        lib_name = LIB_MAPPING[package_path]
        if not is_extractable_lib_name(lib_name):
            fail(
                "staged name %s is not lib*.so; a release APK would not extract it"
                % lib_name
            )
        src = os.path.join(package_dir, *package_path.split("/"))
        if not os.path.isfile(src):
            fail("missing extracted file: " + src)
        if os.path.islink(src):
            fail("unexpected symlink in package: " + src)
        dest = os.path.join(jnilibs, lib_name)
        shutil.copyfile(src, dest)
        os.chmod(dest, 0o755)
        if package_path == "bin/codex-app-server":
            patch_code_mode_host_lookup(dest)
        check_elf(dest, expected_machine, abi)
        staged.append(
            {
                "abi": abi,
                "package_path": package_path,
                "lib_name": lib_name,
                "size": os.path.getsize(dest),
                "sha256": sha256_file(dest),
                "elf_machine": expected_machine,
                "target": target,
            }
        )
        print("prepare_runtime: staged %s -> %s" % (package_path, lib_name))
    return staged


def stage_assets(
    package_dir: str,
    assets: str,
    staged: list[dict],
    ca_bundle: str,
    targets: list[str],
    musl_loader: dict,
    musl_license: str,
) -> None:
    reset_output(assets)
    manifest_src = os.path.join(package_dir, "codex-package.json")
    with open(manifest_src, "r", encoding="utf-8") as handle:
        package_manifest = json.load(handle)
    shutil.copyfile(manifest_src, os.path.join(assets, "codex-package.json"))
    shutil.copyfile(ca_bundle, os.path.join(assets, "cacert.pem"))
    shutil.copyfile(musl_license, os.path.join(assets, MUSL_LICENSE_ASSET))
    manifest = {
        "version": package_manifest.get("version", PACKAGE_VERSION),
        "variant": package_manifest.get("variant", "codex-app-server"),
        "target": package_manifest.get("target", PACKAGE_TARGET),
        "targets": targets,
        "abis": sorted({item["abi"] for item in staged}),
        "package_sha256": EXPECTED_SHA256,
        "files": staged,
        "musl_loader": musl_loader,
        "env_contract": {
            "HOME": "<files>/runtime/home",
            "CODEX_HOME": "<files>/runtime/home/.codex",
            "TMPDIR": "<files>/runtime/tmp",
            "PATH": "<nativeLibraryDir>:<files>/runtime/package/codex-path:/system/bin",
            "HTTPS_PROXY": "http://127.0.0.1:<ephemeral-port>",
            "HTTP_PROXY": "http://127.0.0.1:<ephemeral-port>",
            "NO_PROXY": "localhost,127.0.0.1",
            "SSL_CERT_FILE": "<files>/runtime/cacert.pem",
            "CODEX_CA_CERTIFICATE": "<files>/runtime/cacert.pem",
            "CODEX_SANDBOX": "removed",
            "launch": "<nativeLibraryDir>/libcodex_app_server.so --listen stdio://",
        },
        "notes": (
            "The official rust-v0.156.0 app-server is staged with an in-place "
            "helper-name patch: its final code-mode host lookup uses "
            "libcodex_codemode.so, the lib*.so entry Android extracts into "
            "nativeLibraryDir. The original package archive is unchanged; "
            "rg/zsh/bwrap discovery remains best effort. arm64-v8a also carries "
            "the unmodified Alpine musl loader as libld_musl.so, which starts "
            "the Claude Code binary the app downloads at first use (never "
            "bundled). No device success is claimed by this script."
        ),
    }
    # Sort keys for reproducibility.
    with open(os.path.join(assets, "runtime-manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print("prepare_runtime: wrote runtime-manifest.json")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stage Codex runtime for the APK.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--package-dir", default=DEFAULT_PACKAGE_DIR)
    parser.add_argument("--expected-sha256", default=EXPECTED_SHA256)
    parser.add_argument("--out-jnilibs", default=DEFAULT_JNILIBS)
    parser.add_argument("--out-assets", default=DEFAULT_ASSETS)
    parser.add_argument(
        "--url",
        default="https://github.com/openai/codex/releases/download/rust-v0.156.0/codex-app-server-package-aarch64-unknown-linux-musl.tar.gz",
        help="Download the package from this URL when --package is missing.",
    )
    parser.add_argument("--x86-package", default=DEFAULT_X86_PACKAGE)
    parser.add_argument("--x86-package-dir", default=DEFAULT_X86_PACKAGE_DIR)
    parser.add_argument(
        "--x86-expected-sha256",
        default="037a10600af8228fca6f600ccd37048eb70b875df9097774fa9776f494ea55ea",
    )
    parser.add_argument(
        "--x86-url",
        default="https://github.com/openai/codex/releases/download/rust-v0.156.0/codex-app-server-package-x86_64-unknown-linux-musl.tar.gz",
        help="Download the x86_64 package from this URL when --x86-package is missing.",
    )
    parser.add_argument("--ca-bundle", default=DEFAULT_CA_BUNDLE)
    parser.add_argument("--ca-url", default=CA_BUNDLE_URL)
    parser.add_argument("--ca-sha256", default=CA_BUNDLE_SHA256)
    parser.add_argument("--musl-apk", default=DEFAULT_MUSL_APK)
    parser.add_argument("--musl-license", default=DEFAULT_MUSL_LICENSE)
    return parser.parse_args(argv)


def ensure_pinned_download(path: str, url: str, sha256: str) -> None:
    """Use the cached file or download it; either way the pinned hash must match."""
    if not os.path.isfile(path):
        download_package(url, path)
    verify_hash(path, sha256)


def resolve(repo_path: str) -> str:
    return repo_path if os.path.isabs(repo_path) else os.path.join(REPO_ROOT, repo_path)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    package = resolve(args.package)
    package_dir = resolve(args.package_dir)
    x86_package = resolve(args.x86_package)
    x86_package_dir = resolve(args.x86_package_dir)
    jnilibs = resolve(args.out_jnilibs)
    x86_jnilibs = resolve(DEFAULT_X86_JNILIBS)
    assets = resolve(args.out_assets)
    ca_bundle = resolve(args.ca_bundle)
    musl_apk = resolve(args.musl_apk)
    musl_license = resolve(args.musl_license)

    variants = [
        {
            "abi": "arm64-v8a",
            "target": PACKAGE_TARGET,
            "package": package,
            "package_dir": package_dir,
            "jnilibs": jnilibs,
            "url": args.url,
            "sha256": args.expected_sha256,
            "machine": EM_AARCH64,
        },
        {
            "abi": "x86_64",
            "target": X86_PACKAGE_TARGET,
            "package": x86_package,
            "package_dir": x86_package_dir,
            "jnilibs": x86_jnilibs,
            "url": args.x86_url,
            "sha256": args.x86_expected_sha256,
            "machine": EM_X86_64,
        },
    ]
    staged: list[dict] = []
    musl_loader: dict | None = None
    for variant in variants:
        if not os.path.isfile(variant["package"]):
            if variant["url"]:
                download_package(variant["url"], variant["package"])
            else:
                fail("package not found: %s" % variant["package"])
        verify_hash(variant["package"], variant["sha256"])
        names = safe_extract(variant["package"], variant["package_dir"])
        print("prepare_runtime: extracted %d %s entries" % (len(names), variant["abi"]))
        check_required_layout(names)
        staged.extend(
            stage_libraries(
                variant["package_dir"],
                variant["jnilibs"],
                variant["abi"],
                variant["target"],
                variant["machine"],
            )
        )
        # After stage_libraries, which resets this ABI's directory.
        if variant["abi"] in MUSL_LOADER_ABIS:
            ensure_pinned_download(musl_apk, MUSL_APK_URL, MUSL_APK_SHA256)
            musl_loader = stage_musl_loader(musl_apk, variant["jnilibs"])
    if musl_loader is None:
        fail("musl loader was not staged")
    ensure_pinned_download(musl_license, MUSL_LICENSE_URL, MUSL_LICENSE_SHA256)

    if not os.path.isfile(ca_bundle):
        if args.ca_url:
            download_package(args.ca_url, ca_bundle)
        else:
            fail("CA bundle not found: %s" % ca_bundle)
    verify_hash(ca_bundle, args.ca_sha256)
    validate_ca_pem(ca_bundle)
    stage_assets(
        package_dir,
        assets,
        staged,
        ca_bundle,
        [PACKAGE_TARGET, X86_PACKAGE_TARGET],
        musl_loader,
        musl_license,
    )
    print("prepare_runtime: OK version=%s targets=%s" % (
        PACKAGE_VERSION,
        ",".join([PACKAGE_TARGET, X86_PACKAGE_TARGET]),
    ))
    print("  jnilibs: " + os.path.relpath(os.path.dirname(jnilibs), REPO_ROOT))
    print("  assets:  " + os.path.relpath(assets, REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
