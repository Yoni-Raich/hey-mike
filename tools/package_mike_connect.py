#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build an immutable, allowlisted Mike Connect developer preview. Never publishes remotely."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"connect-protocol": "jar", "connect-sdk": "aar", "connect-sdk-testing": "aar"}
SKILLS = ("mike-connect-app-audit", "mike-connect-integrate")
GROUP = "dev.heymike.connect"
HASHES = ("md5", "sha1", "sha256", "sha512")
SEMVER = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def digest(data: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, data).hexdigest()


def read_file(path: Path) -> bytes:
    if path.is_symlink():
        raise ValueError(f"Symlink is not a distributable file: {path.name}")
    return path.read_bytes()


def versions(root: Path) -> tuple[str, str, int]:
    protocol = read_file(root / "connect-protocol/src/main/kotlin/dev/heymike/connect/Protocol.kt").decode()
    sdk = re.search(r'const val SDK_VERSION = "([^"]+)"', protocol)[1]
    profile = re.search(r'const val VERSION = "([^"]+)"', protocol)[1]
    binding = int(re.search(r"const val BINDING = ([0-9]+)", protocol)[1])
    if not SEMVER.fullmatch(sdk):
        raise ValueError("Invalid SDK version")
    for module in MODULES:
        gradle = read_file(root / module / "build.gradle.kts").decode()
        if re.search(r'version = "([^"]+)"', gradle)[1] != sdk:
            raise ValueError(f"Version mismatch in {module}")
    return sdk, profile, binding


def kit_sources(root: Path) -> dict[str, bytes]:
    kit = root / "connect-sdk/developer-kit"
    payload = {}
    for directory, dirs, files in os.walk(kit, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in {"build", ".gradle", ".kotlin", ".idea", "__pycache__"})
        for name in dirs:
            if (Path(directory) / name).is_symlink():
                raise ValueError("Kit may not contain symlink directories")
        for name in sorted(files):
            path = Path(directory) / name
            rel = path.relative_to(kit).as_posix()
            if name == "local.properties":
                continue
            allowed = rel == "README.md" or (
                rel.startswith("skills/") and path.suffix == ".md"
            ) or (
                rel.startswith("examples/preferences-provider/") and
                (path.suffix in {".md", ".kt", ".kts", ".xml"} or name == "gradle.properties")
            )
            if not allowed or name.startswith(".") or path.is_symlink():
                raise ValueError(f"Unexpected developer-kit file: {rel}")
            payload[rel] = read_file(path)
    if "README.md" not in payload:
        raise ValueError("Developer kit README is missing")
    for skill in SKILLS:
        key = f"skills/{skill}/SKILL.md"
        if key not in payload or f"name: {skill}" not in payload[key].decode().splitlines():
            raise ValueError(f"Missing or incorrectly named skill: {skill}")
    license_data = read_file(root / "connect-sdk/LICENSE")
    if b"Apache License" not in license_data:
        raise ValueError("Public SDK license is missing")
    for module in MODULES:
        if read_file(root / module / "LICENSE") != license_data:
            raise ValueError(f"Public module license differs: {module}")
    payload["LICENSE"] = license_data
    for skill in SKILLS:
        payload[f"skills/{skill}/LICENSE"] = license_data
    return payload


def maven_files(root: Path, version: str) -> tuple[dict[str, bytes], dict[str, str]]:
    payload, binaries = {}, {}
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module, extension in MODULES.items():
        directory = root / "artifacts/connect-maven/dev/heymike/connect" / module / version
        base = f"{module}-{version}"
        for suffix in (f".{extension}", "-sources.jar", ".pom", ".module"):
            name = base + suffix
            source = directory / name
            data = read_file(source)
            rel = f"maven/dev/heymike/connect/{module}/{version}/{name}"
            payload[rel] = data
            for algorithm in HASHES:
                sidecar = read_file(directory / f"{name}.{algorithm}").decode().strip()
                if sidecar != digest(data, algorithm):
                    raise ValueError(f"Maven checksum mismatch: {module}/{name}.{algorithm}")
                payload[f"{rel}.{algorithm}"] = (sidecar + "\n").encode()
            if suffix == f".{extension}":
                binaries[f"{GROUP}:{module}:{version}"] = digest(data)
            elif suffix == ".pom":
                pom = ET.fromstring(data)
                values = {field: pom.findtext("m:" + field, namespaces=ns) for field in ("groupId", "artifactId", "version")}
                if values != {"groupId": GROUP, "artifactId": module, "version": version}:
                    raise ValueError(f"Incorrect POM coordinates for {module}")
                for dependency in pom.findall("m:dependencies/m:dependency", ns):
                    if dependency.findtext("m:groupId", namespaces=ns) == GROUP:
                        if dependency.findtext("m:artifactId", namespaces=ns) not in MODULES or dependency.findtext("m:version", namespaces=ns) != version:
                            raise ValueError("Public SDK POM depends on a private or mismatched module")
            elif suffix == ".module":
                metadata = json.loads(data)
                expected = {"group": GROUP, "module": module, "version": version}
                if any(metadata["component"].get(key) != value for key, value in expected.items()):
                    raise ValueError(f"Incorrect Gradle coordinates for {module}")
                for variant in metadata["variants"]:
                    for artifact in variant.get("files", []):
                        if artifact["url"] != artifact["name"] or Path(artifact["name"]).name != artifact["name"]:
                            raise ValueError("Unexpected Gradle artifact URL")
                        actual = read_file(directory / artifact["name"])
                        if len(actual) != artifact["size"] or digest(actual) != artifact["sha256"]:
                            raise ValueError("Gradle metadata does not match its artifact")
                    for dependency in variant.get("dependencies", []):
                        if dependency["group"] == GROUP and (dependency["module"] not in MODULES or dependency["version"].get("requires") != version):
                            raise ValueError("Public Gradle metadata depends on a private or mismatched module")
    return payload, binaries


def check_links(payload: dict[str, bytes]) -> None:
    for filename, data in payload.items():
        if not filename.endswith(".md"):
            continue
        text = data.decode("utf-8")
        if re.search(r"[A-Za-z]:[\\/]Users[\\/]|/home/[^ /]+/|/Users/[^ /]+/", text):
            raise ValueError(f"Machine-specific path in public documentation: {filename}")
        for raw in LINK.findall(text):
            target = raw.strip("<>").split("#", 1)[0]
            if not target or target.startswith(("https://", "http://", "mailto:")):
                continue
            path = PurePosixPath(filename).parent / target
            parts = []
            for part in path.parts:
                if part == "..":
                    if not parts:
                        raise ValueError(f"Link escapes the kit: {filename}: {raw}")
                    parts.pop()
                elif part not in ("", "."):
                    parts.append(part)
            if "/".join(parts) not in payload:
                raise ValueError(f"Missing bundled reference: {filename}: {raw}")


def source_state(root: Path) -> dict:
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True))
        return {"revision": revision, "hasUncommittedChanges": dirty}
    except (OSError, subprocess.CalledProcessError):
        return {"revision": None, "hasUncommittedChanges": None}


def verify_bundle(directory: Path) -> dict:
    manifest = (directory / "MANIFEST.sha256").read_text(encoding="utf-8")
    expected = {}
    for line in manifest.splitlines():
        value, name = line.split("  ", 1)
        path = PurePosixPath(name)
        if not re.fullmatch(r"[0-9a-f]{64}", value) or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name or name in expected:
            raise ValueError("Invalid manifest entry")
        expected[name] = value
    actual = {p.relative_to(directory).as_posix(): read_file(p) for p in directory.rglob("*") if p.is_file() and p != directory / "MANIFEST.sha256"}
    if actual.keys() != expected.keys():
        raise ValueError("Manifest file set differs from the kit")
    for name, data in actual.items():
        if digest(data) != expected[name]:
            raise ValueError(f"Bundle checksum mismatch: {name}")
    check_links(actual)
    release = json.loads(actual["release.json"])
    if release["status"] != "local-preview" or sorted(release["skills"]) != sorted(SKILLS):
        raise ValueError("Invalid preview metadata")
    for coordinate, value in release["binarySha256"].items():
        group, module, version = coordinate.split(":")
        extension = MODULES[module]
        name = f"maven/{group.replace('.', '/')}/{module}/{version}/{module}-{version}.{extension}"
        if digest(actual[name]) != value:
            raise ValueError(f"Release binary hash mismatch: {module}")
    return release


def package(root: Path, output: Path, bundle_version: str) -> tuple[Path, Path]:
    if not SEMVER.fullmatch(bundle_version):
        raise ValueError("Use a version such as 1.0.0-preview.1")
    sdk_version, profile, binding = versions(root)
    payload = kit_sources(root)
    maven, binaries = maven_files(root, sdk_version)
    payload.update(maven)
    release = {
        "bundleVersion": bundle_version, "sdkVersion": sdk_version, "status": "local-preview",
        "bindingVersion": binding, "mcpProfile": profile, "license": "Apache-2.0", "skills": list(SKILLS),
        "binarySha256": binaries, "source": source_state(root),
        "compatibility": {"libraryMinApi": 24, "connectMinApi": 30, "javaBytecode": 17,
                          "defaultTrust": "dev test identity; not production; see integration reference"},
        "publication": {"publishedByThisCommand": False, "mavenCentralReady": False,
                        "remaining": ["production trust policy", "namespace ownership", "complete POM metadata", "Javadoc artifacts", "PGP signatures", "release CI and physical conformance"]},
    }
    payload["release.json"] = (json.dumps(release, indent=2, sort_keys=True) + "\n").encode()
    check_links(payload)
    payload["MANIFEST.sha256"] = "".join(f"{digest(data)}  {name}\n" for name, data in sorted(payload.items())).encode()
    output.mkdir(parents=True, exist_ok=True)
    folder = output / f"mike-connect-developer-kit-{bundle_version}"
    archive = output / f"{folder.name}.zip"
    checksum = output / f"{archive.name}.sha256"
    if any(p.exists() for p in (folder, archive, checksum)):
        raise FileExistsError("This bundle version exists. Choose a new version; existing artifacts are never replaced.")
    # Staging stays on the destination filesystem. Only files created by this invocation are temporary.
    with tempfile.TemporaryDirectory(prefix=".mike-connect-stage-", dir=output) as temporary:
        stage = Path(temporary)
        tree = stage / folder.name
        tree.mkdir()
        for name, data in sorted(payload.items()):
            target = tree / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        verify_bundle(tree)
        staged_zip = stage / archive.name
        with zipfile.ZipFile(staged_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(f"{folder.name}/{name}", date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                z.writestr(info, data)
        staged_checksum = stage / checksum.name
        staged_checksum.write_text(f"{digest(staged_zip.read_bytes())}  {archive.name}\n", encoding="utf-8")
        # Exclusive outputs also protect against a concurrent invocation with this version.
        with archive.open("xb") as target:
            target.write(staged_zip.read_bytes())
        with checksum.open("x", encoding="utf-8") as target:
            target.write(staged_checksum.read_text(encoding="utf-8"))
        tree.rename(folder)
    return folder, archive


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-version", help="A new immutable kit version, for example 1.0.0-preview.1")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/connect-distribution")
    parser.add_argument("--verify", type=Path, help="Verify an already extracted kit; creates nothing")
    args = parser.parse_args()
    if args.verify:
        print(json.dumps(verify_bundle(args.verify), indent=2))
    else:
        if not args.bundle_version:
            parser.error("--bundle-version is required when packaging")
        folder, archive = package(ROOT, args.output.resolve(), args.bundle_version)
        print(json.dumps({"directory": str(folder), "archive": str(archive), "sha256": digest(archive.read_bytes()), "remotePublication": False}, indent=2))


if __name__ == "__main__":
    main()
