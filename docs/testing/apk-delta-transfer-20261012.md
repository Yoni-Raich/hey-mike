# APK delta transfer — 2026-10-12

Worktree: `C:\Users\SHIRA\.codex\worktrees\apk-delta-transfer-20261012`.
Branch: `feat/apk-delta-transfer-20261012`, based on dev
`1b6827ba03ae218fa631e166d32a160b8a8acf5b`. Other checkouts were preserved.

## Behavior

The model still calls `copy_file(from, to, replace)` once. For computer APKs
the receiver negotiates a verified target or base over the existing SSH link.
It reuses cached target bytes, downloads a patch, or receives the full APK.
Patch and target hashes must match before publication. Stop does not start a
full fallback. A failed install can retry the completed local APK as before.

Dev Debug builds export a signed snapshot and try to precompute a patch. The
phone seeds its own installed APK after the bootstrap full update. The caches
are shared between chats, and copy destinations do not share writable cache
inodes. Cache miss, tool failure, a large patch or a missing Python interpreter
keep a full-copy route. No phone was installed or controlled for this change.

HDiffPatch 5.1.3 is pinned to release hashes and source commit
`3b9dca715ca492873bf2c49e22e5d5b7d2a78620`. Native dependencies are zstd 1.5.7
and xxHash 0.8.3. Android binaries use NDK 28.2.13676358 for ARM64 and x86_64.
CI now installs that NDK; the existing lint/test gate is not weakened.

## Checks

The full command is run with an isolated Gradle home, so the user's global
build-storage cleanup script is not invoked:

```powershell
$env:ANDROID_HOME='C:\Users\SHIRA\AppData\Local\Android\Sdk'
.\gradlew.bat test assembleDevRelease assembleDevDebug assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug '-PversionCodeOverride=1134' '-PversionNameOverride=0.14.0-dev.delta1' '-PdeltaNativeLibraryPath=C:\Users\SHIRA\.codex\worktrees\apk-delta-transfer-20261012\workspace\build\host-delta' '-PdeltaFixtureDir=C:\Users\SHIRA\AppData\Local\Temp\hey-mike-apk-delta-20261012' --gradle-user-home C:\Users\SHIRA\.codex\tmp\gradle-voice-129-20261011 --no-daemon --no-parallel --max-workers=2 --console=plain
```

The host JNI fixture is built first with `python -m tools.build_host_delta`.
Native tests require explicit `deltaNativeLibraryPath` and `deltaFixtureDir`;
without fixtures they skip and must not be reported as native proof.

`python -m unittest tools.test_host_transfer tools.test_prepare_runtime -v`
is run with `MIKE_DELTA_GENERATOR` set to the verified research `hdiffz.exe`.
The actual CLI case otherwise skips; no network download is required by these
unit tests. `git diff --check` is also required.

The new tests cover full transfer, another chat's target-cache hit, delta
reconstruction, same-size corruption, immutable fallback, cancellation during
fetch/native work, cancelled queue waiters, bootstrap seeding, cache restart,
eviction ownership and concurrent atomic no-replace commits. Real SSH/SFTP
tests preserve a live lease through prepare/fetch and cancel a queued helper
without disconnecting the shared SSH session. They use a local SSH server and
fake test credentials, not a saved user's computer account.

The Windows JNI fixture reconstructs an existing signed 1132→1133 APK pair,
checks its full SHA-256, rejects bad size/patch input and cancels during actual
native output. Separate host integration creates the patch with the product
helper and checks that its bytes equal the native fixture. It also observes a
real `hdiffz.exe` child and verifies that stdin EOF stops it with host exit 130.

Evidence is kept outside Git under:
`C:\Users\SHIRA\AppData\Local\Temp\hey-mike-apk-delta-20261012`.
Logs include `implementation-final-gate.log`, `python-final-tests.log`,
`native-host-final-build.log`, `ssh-delta-integration.log` and
`implementation-evidence.json`. The research report in that folder describes
the separate earlier benchmark. Its 2.35 MB result used slower zstd20; this
implementation uses zstd3 and produces 2,776,115 bytes on that pair.

An actual generator run caught a repeated `-WD` option that had caused full
fallback. The option list was fixed, an actual CLI regression test was added,
and the final gate/package check was repeated. This was not a native patch or
signature failure. The test-only Windows SFTP server needed drive-root mapping;
product Windows OpenSSH paths remain `/C:/...`.

## Remaining gaps

- No phone/emulator install, Android JNI execution or live visual evidence.
  UI changes only name transfer phases; Compose compilation and lint are
  separate from a screenshot or hardware run.
- No actual Wi-Fi timing, Android CPU/RAM/thermal measurement, low-storage
  installation or self-update. PC JNI timings are not phone timings.
- No production signing. Dev Debug uses the debug key; Dev Release is a build
  gate only. Existing signature preservation tests use signed APK v2.
- Linux source mutation checks detect ordinary writers; no adversarial writer
  or strict OS snapshot is claimed. Python 3.9+ is required for optimization.
- Legacy full SFTP on a computer without Python retains its prior size/SSH
  checks rather than a negotiated SHA-256 source manifest.
- No directory fsync/power-loss guarantee, split-APK transaction or resumable
  partial transfer. A normal retry can reuse a verified complete blob/patch.
- Uploads and non-APK copies keep their existing transport. Source output is
  never removed by cache eviction; no app data, sign-in or session is cleared.

## Final results

The following results describe the initial 1134 build. The later installed
1140 build and physical package verification are recorded below.

- Final full Gradle gate passed in 1m7s: 2,545 unit test executions, zero
  failures/errors and 16 existing skips. The native fixture tests and real
  SSH/SFTP tests had zero skips. AndroidTest was compiled, not executed.
- Python host/runtime suites passed: 24 tests, zero skips. This includes the
  actual `hdiffz.exe` CLI regression test. `git diff --check` passed.
- Product host helper on the signed 1132→1133 pair produced 2,776,115 bytes
  instead of 331,102,984 bytes (99.16% less transfer data). Prepare took
  2.658s with an empty patch cache and 0.849s with a populated patch cache;
  both include source snapshot/hashing, and neither is an OS cold-cache test.
  The patch bytes matched the fixture reconstructed byte-identically by JNI.
- Closing helper stdin during a real generation run stopped its observed
  `hdiffz.exe` child; helper exit was 130 and the child exited. The measured
  host wait was 0.008s. This is PC cancellation evidence, not Android timing.
- Final Dev Debug APK: package `dev.androidagent.app.dev`, version code 1134,
  name `0.14.0-dev.delta1`, 332,271,147 bytes. SHA-256:
  `7381eea0e0045e30d52bf66b488d1bbe2c6b09b36976c30dade0126761eed89e`.
  `apksigner verify --verbose --print-certs` passed with the existing debug
  certificate and APK Signature Scheme v2. This is not a production release.
  `zipalign -c -P 16 4` passed; every ELF LOAD segment in both delta native
  ABIs has 16,384-byte alignment. Both native ABIs, all three license texts and
  the byte-identical host helper are present in the APK. The final metadata
  and alignment evidence is in `final-apk-verification.json` beside the logs.
- The exact APK was copied within the PC to
  `artifacts/hey-mike-dev-1134-delta1.apk`; its SHA-256 matched. No file was
  copied to the phone, and no installation or deployment was performed.

## Bootstrap delivery and installed verification

After installation was requested, the full 1134 copy completed but Android
rejected it: the chat phone already had version 1137. The isolated delta
worktree then preserved the newer slash/context/compaction changes from
`voice-child-update-timing-129-20261011` (20 source/test files), without editing
that worktree. The normalized source hashes are in
`preserved-slash-context-source.json` beside the research logs.

The full Gradle gate was repeated for 1140 / `0.14.0-dev.delta2` and passed in
3m30s: 2,609 unit executions, zero failures/errors, 16 existing skips.
Metadata, debug-key v2 signature, ZIP/ELF 16KiB alignment, packaged host helper
and preserved source hashes passed. The 331,542,025-byte artifact is
`artifacts/hey-mike-dev-1140-delta2.apk`; SHA-256:
`85c5d8e6b24c9f23c3ef28ed5bc5eb4d6494f916ec3cb86f3e4a230461179d3e`.
Build log: `preserved-1140-gate.log`; package evidence:
`delivery-1140-evidence.json`, both outside Git in the research directory.

The full bootstrap copy to `chat:hey-mike-dev-1140-delta2.apk` completed.
Installation with replacement was interrupted before its tool returned.
Independent phone checks then confirmed `dev.androidagent.app.dev` version
1140 / `0.14.0-dev.delta2`, and the installed `base.apk` hash matched the
artifact exactly. Last update was 2026-10-12 17:02:53 (Asia/Jerusalem); the
first-install timestamp remains 2026-09-08 20:12:05. No downgrade, uninstall or
data clearing was used. These checks prove exact installed bytes, not a live
Android JNI reconstruction or a delta transfer benchmark.
