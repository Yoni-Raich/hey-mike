import tempfile
import unittest
from pathlib import Path

from tools.prepare_runtime import (
    CODE_MODE_HOST_ANDROID_NAME,
    CODE_MODE_HOST_NAME,
    LIB_MAPPING,
    is_extractable_lib_name,
    patch_code_mode_host_lookup,
)


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


if __name__ == "__main__":
    unittest.main()
