import gzip
import hashlib
import io
import struct
import tarfile
import tempfile
import unittest
from pathlib import Path

from tools.prepare_runtime import (
    CODE_MODE_HOST_ANDROID_NAME,
    CODE_MODE_HOST_NAME,
    EM_AARCH64,
    EM_X86_64,
    LIB_MAPPING,
    MUSL_LICENSE_ASSET,
    MUSL_LOADER_ABIS,
    MUSL_LOADER_LIB_NAME,
    MUSL_LOADER_MEMBER,
    extract_archive_member,
    is_extractable_lib_name,
    patch_code_mode_host_lookup,
    stage_musl_loader,
)


def fake_elf(machine: int, size: int = 256) -> bytes:
    header = bytearray(size)
    header[0:4] = b"\x7fELF"
    header[4] = 2  # ELF64
    header[5] = 1  # little-endian
    struct.pack_into("<HH", header, 16, 3, machine)
    return bytes(header)


def tar_segment(entries, cut: bool) -> bytes:
    """One gzip stream holding a tar. `cut` drops the end-of-archive blocks, as abuild does."""
    buffer = io.BytesIO()
    tar = tarfile.open(fileobj=buffer, mode="w", format=tarfile.USTAR_FORMAT)
    for name, kind, payload in entries:
        info = tarfile.TarInfo(name)
        if kind == "file":
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
        elif kind == "dir":
            info.type = tarfile.DIRTYPE
            tar.addfile(info)
        elif kind == "symlink":
            info.type = tarfile.SYMTYPE
            info.linkname = payload
            tar.addfile(info)
    end = tar.offset
    tar.close()
    raw = buffer.getvalue()[:end] if cut else buffer.getvalue()
    return gzip.compress(raw)


def fake_apk(loader: bytes, data_cut: bool = False, extra=None) -> bytes:
    """Alpine .apk layout: signature, control and data tar.gz streams, concatenated."""
    signature = tar_segment([(".SIGN.RSA.test.rsa.pub", "file", b"sig")], cut=True)
    control = tar_segment([(".PKGINFO", "file", b"pkgname = musl\n")], cut=True)
    data_entries = [
        ("lib", "dir", None),
        (MUSL_LOADER_MEMBER, "file", loader),
        ("lib/libc.musl-aarch64.so.1", "symlink", "ld-musl-aarch64.so.1"),
    ] + list(extra or [])
    data = tar_segment(data_entries, cut=data_cut)
    return signature + control + data


class PrepareRuntimeTest(unittest.TestCase):
    def test_android_alias_matches_fixed_width_without_nul(self):
        self.assertEqual(len(CODE_MODE_HOST_ANDROID_NAME), len(CODE_MODE_HOST_NAME))
        self.assertNotIn(b"\0", CODE_MODE_HOST_ANDROID_NAME)

    def test_android_alias_is_extracted_from_release_apks(self):
        # Android skips entries not named lib*.so unless the app is debuggable.
        self.assertTrue(is_extractable_lib_name(CODE_MODE_HOST_ANDROID_NAME.decode()))
        self.assertEqual(LIB_MAPPING["bin/codex-code-mode-host"], CODE_MODE_HOST_ANDROID_NAME.decode())

    def test_every_staged_library_is_extracted_from_release_apks(self):
        for package_path, lib_name in LIB_MAPPING.items():
            with self.subTest(package_path=package_path):
                self.assertTrue(is_extractable_lib_name(lib_name), lib_name)

    def test_names_android_skips_are_rejected(self):
        self.assertFalse(is_extractable_lib_name("codex-code-mode-x.so"))
        self.assertFalse(is_extractable_lib_name("libcodex_rg"))
        self.assertFalse(is_extractable_lib_name("lib/codex.so"))

    def test_patch_leaves_valid_terminated_path(self):
        original = b"error:" + CODE_MODE_HOST_NAME + b"|" + CODE_MODE_HOST_NAME + b"\0tail"
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "codex-app-server"
            binary.write_bytes(original)
            patch_code_mode_host_lookup(str(binary))
            patched = binary.read_bytes()

        self.assertEqual(
            patched,
            b"error:"
            + CODE_MODE_HOST_NAME
            + b"|"
            + CODE_MODE_HOST_ANDROID_NAME
            + b"\0tail",
        )
        lookup_start = patched.index(CODE_MODE_HOST_ANDROID_NAME)
        lookup_end = lookup_start + len(CODE_MODE_HOST_ANDROID_NAME)
        self.assertNotIn(b"\0", patched[lookup_start:lookup_end])
        self.assertEqual(patched[lookup_end], 0)


class MuslLoaderTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def write_apk(self, payload: bytes) -> str:
        apk = self.root / "musl.apk"
        apk.write_bytes(payload)
        return str(apk)

    def test_loader_is_arm64_only_and_extracted_from_release_apks(self):
        self.assertEqual(MUSL_LOADER_ABIS, ("arm64-v8a",))
        self.assertTrue(is_extractable_lib_name(MUSL_LOADER_LIB_NAME))
        self.assertEqual(MUSL_LOADER_LIB_NAME, "libld_musl.so")
        self.assertEqual(MUSL_LICENSE_ASSET, "musl-COPYRIGHT")

    def test_extracts_member_from_concatenated_gzip_streams(self):
        loader = fake_elf(EM_AARCH64)
        for data_cut in (True, False):
            with self.subTest(data_cut=data_cut):
                apk = self.write_apk(fake_apk(loader, data_cut=data_cut))
                dest = self.root / "out.so"
                extract_archive_member(apk, MUSL_LOADER_MEMBER, str(dest))
                self.assertEqual(dest.read_bytes(), loader)

    def test_missing_member_fails_closed(self):
        apk = self.write_apk(fake_apk(fake_elf(EM_AARCH64)))
        with self.assertRaises(SystemExit):
            extract_archive_member(apk, "lib/ld-musl-x86_64.so.1", str(self.root / "out.so"))
        self.assertFalse((self.root / "out.so").exists())

    def test_unsafe_member_names_fail_closed(self):
        for name in ("../evil", "/abs/evil", "lib/../../evil", "lib//evil"):
            with self.subTest(name=name):
                apk = self.write_apk(fake_apk(fake_elf(EM_AARCH64), extra=[(name, "file", b"x")]))
                with self.assertRaises(SystemExit):
                    extract_archive_member(apk, MUSL_LOADER_MEMBER, str(self.root / "out.so"))

    def test_link_in_place_of_wanted_member_fails_closed(self):
        apk = self.write_apk(fake_apk(fake_elf(EM_AARCH64)))
        with self.assertRaises(SystemExit):
            extract_archive_member(apk, "lib/libc.musl-aarch64.so.1", str(self.root / "out.so"))

    def test_duplicate_member_fails_closed(self):
        apk = self.write_apk(
            fake_apk(fake_elf(EM_AARCH64), extra=[(MUSL_LOADER_MEMBER, "file", b"other")])
        )
        with self.assertRaises(SystemExit):
            extract_archive_member(apk, MUSL_LOADER_MEMBER, str(self.root / "out.so"))

    def test_stages_verified_loader_as_lib_so(self):
        loader = fake_elf(EM_AARCH64)
        apk = self.write_apk(fake_apk(loader))
        jnilibs = self.root / "jniLibs" / "arm64-v8a"
        jnilibs.mkdir(parents=True)
        entry = stage_musl_loader(
            apk, str(jnilibs), hashlib.sha256(loader).hexdigest(), len(loader)
        )
        staged = jnilibs / MUSL_LOADER_LIB_NAME
        self.assertEqual(staged.read_bytes(), loader)
        self.assertEqual(entry["lib_name"], MUSL_LOADER_LIB_NAME)
        self.assertEqual(entry["abi"], "arm64-v8a")
        self.assertEqual(entry["elf_machine"], EM_AARCH64)

    def test_loader_hash_mismatch_fails_closed_and_removes_file(self):
        loader = fake_elf(EM_AARCH64)
        apk = self.write_apk(fake_apk(loader))
        jnilibs = self.root / "jniLibs"
        jnilibs.mkdir()
        with self.assertRaises(SystemExit):
            stage_musl_loader(apk, str(jnilibs), "0" * 64, len(loader))
        self.assertFalse((jnilibs / MUSL_LOADER_LIB_NAME).exists())

    def test_loader_size_mismatch_fails_closed(self):
        loader = fake_elf(EM_AARCH64)
        apk = self.write_apk(fake_apk(loader))
        jnilibs = self.root / "jniLibs"
        jnilibs.mkdir()
        with self.assertRaises(SystemExit):
            stage_musl_loader(apk, str(jnilibs), hashlib.sha256(loader).hexdigest(), len(loader) + 1)
        self.assertFalse((jnilibs / MUSL_LOADER_LIB_NAME).exists())

    def test_loader_for_wrong_machine_fails_closed(self):
        loader = fake_elf(EM_X86_64)
        apk = self.write_apk(fake_apk(loader))
        jnilibs = self.root / "jniLibs"
        jnilibs.mkdir()
        with self.assertRaises(SystemExit):
            stage_musl_loader(apk, str(jnilibs), hashlib.sha256(loader).hexdigest(), len(loader))
        self.assertFalse((jnilibs / MUSL_LOADER_LIB_NAME).exists())


if __name__ == "__main__":
    unittest.main()
