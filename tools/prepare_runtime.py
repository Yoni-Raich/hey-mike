#!/usr/bin/env python3
"""Stage the official Codex app-server package for the Android APK.

Pipeline (stdlib only, reproducible):
  1. Verify SHA-256 of the official rust-v0.153.4 ARM64 and x86_64 musl packages.
  2. Safely extract them to .codex-work/runtime/package-* (no absolute paths,
     no "..", no symlinks/hardlinks, no devices).
  3. Copy native ELFs to app/build/generated/runtime/jniLibs/<abi>/ as .so
     files (targetSdk 35 can only execute APK nativeLibraryDir files).
  4. Verify each staged .so has the expected ELF machine for its ABI.
  5. Verify a pinned Mozilla CA PEM bundle and write it beside the runtime
     metadata (the app copies it to app-private storage before launch).
  6. Write app/build/generated/runtime/assets/runtime/ metadata
     (codex-package.json copy + runtime-manifest.json).

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

EXPECTED_SHA256 = "5673c5a8935ff2f85ca67b489e560fdd5e08fb0f0e2f7426f048ec7449aa4fdc"
PACKAGE_VERSION = "0.153.4"
PACKAGE_TARGET = "aarch64-unknown-linux-musl"
X86_PACKAGE_TARGET = "x86_64-unknown-linux-musl"

DEFAULT_PACKAGE = os.path.join(".codex-work", "runtime", "codex-package.tar.gz")
DEFAULT_PACKAGE_DIR = os.path.join(".codex-work", "runtime", "package")
DEFAULT_X86_PACKAGE = os.path.join(".codex-work", "runtime", "codex-package-x86_64.tar.gz")
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
# upstream URL changes unexpectedly.
CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"
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


def safe_extract(archive: str, dest: str) -> list[str]:
    """Extract with strict guards. Returns sorted member names."""
    reset_output(dest)
    dest_real = os.path.realpath(dest)
    names: list[str] = []
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            name = member.name
            if not name or name.startswith("/") or name.startswith("\\"):
                fail("refusing absolute member: %r" % name)
            parts = name.replace("\\", "/").split("/")
            if "" in parts or "." in parts or ".." in parts:
                fail("refusing unsafe member: %r" % name)
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

    Codex 0.153.4 resolves the helper as a sibling named
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
) -> None:
    reset_output(assets)
    manifest_src = os.path.join(package_dir, "codex-package.json")
    with open(manifest_src, "r", encoding="utf-8") as handle:
        package_manifest = json.load(handle)
    shutil.copyfile(manifest_src, os.path.join(assets, "codex-package.json"))
    shutil.copyfile(ca_bundle, os.path.join(assets, "cacert.pem"))
    manifest = {
        "version": package_manifest.get("version", PACKAGE_VERSION),
        "variant": package_manifest.get("variant", "codex-app-server"),
        "target": package_manifest.get("target", PACKAGE_TARGET),
        "targets": targets,
        "abis": sorted({item["abi"] for item in staged}),
        "package_sha256": EXPECTED_SHA256,
        "files": staged,
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
            "The official rust-v0.153.4 app-server is staged with an in-place "
            "helper-name patch: its final code-mode host lookup uses "
            "libcodex_codemode.so, the lib*.so entry Android extracts into "
            "nativeLibraryDir. The original package archive is unchanged; "
            "rg/zsh/bwrap discovery remains best effort. No device success is "
            "claimed by this script."
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
        default="https://github.com/openai/codex/releases/download/rust-v0.153.4/codex-app-server-package-aarch64-unknown-linux-musl.tar.gz",
        help="Download the package from this URL when --package is missing.",
    )
    parser.add_argument("--x86-package", default=DEFAULT_X86_PACKAGE)
    parser.add_argument("--x86-package-dir", default=DEFAULT_X86_PACKAGE_DIR)
    parser.add_argument(
        "--x86-expected-sha256",
        default="a5d37ff1fa6953ee6d317b7e69bfafd39f5f53350b631d790fa7531159f22420",
    )
    parser.add_argument(
        "--x86-url",
        default="https://github.com/openai/codex/releases/download/rust-v0.153.4/codex-app-server-package-x86_64-unknown-linux-musl.tar.gz",
        help="Download the x86_64 package from this URL when --x86-package is missing.",
    )
    parser.add_argument("--ca-bundle", default=DEFAULT_CA_BUNDLE)
    parser.add_argument("--ca-url", default=CA_BUNDLE_URL)
    parser.add_argument("--ca-sha256", default=CA_BUNDLE_SHA256)
    return parser.parse_args(argv)


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
