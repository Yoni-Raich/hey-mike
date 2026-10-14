# Claude runtime preservation and Haiku 5.5 — 2026-10-11

## Change

The ordinary child/voice branch was built from dev, where PR #121 and #122
were still open. It carried Claude 2.1.285 and the old model catalog.
`installer.refresh()` ran at startup and removed every other version before
checking whether its own binary was present. Replacing an APK that used the
newer runtime could therefore remove it and show Download again. No live
before/after runtime evidence was available for the reported Nothing event.

The two exact PR heads are integrated with a normal Git merge:

- Haiku support: `34d093ca465080d2551355caf07b0100d0a3b13f`.
- Automatic updates: `b49824ea3823e36cc199267ddc60c17c03f8d2ac`.

Discovery keeps other versions. It restores authenticated active/previous
metadata, then tries the bundled pin and known legacy pins. Compiled hash/size
metadata permits reuse of previously shipped 2.1.285 bytes without downloading.
Unknown old folders and altered known-pin metadata are not adopted.
Existing verified Claude remains ready if the selection marker cannot be saved.
First install saves that marker too. The first automatic update from the old
pin uses bundled 2.1.293, without waiting for latest-release metadata; later
updates still require the signed official manifest and isolated launch probe.
Failures and cancellation retain the working version and its account location.

The merge keeps the newer dev authentication retry, Stop and steering changes,
alongside catalog TTL/runtime-identity refresh. Child and voice behavior remains
in the same branch. No other worktree was edited.

## Checks

The scoped runtime/Claude tests and app compilation passed in 52s. The new
upgrade suite first failed on recovery of an explicit previous selection when
active metadata was missing; the source was fixed and the final suite passed.
The first full gate was interrupted and is not claimed as complete.

Final command:

```powershell
.\gradlew.bat test assembleDevRelease assembleDevDebug assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --no-daemon --no-parallel --max-workers=2 --init-script <scoped-storage-init>
python -m unittest tools.test_prepare_runtime
git diff --check
```

Gradle passed in 2m24s, 1,168 tasks (219 executed, 949 up-to-date). Unit XML:
2,482 executions, zero failures/errors, 16 existing skips. These are executions
across variants, not a count of unique tests. Python: 15 passed. App and voice
lint passed. The scoped storage hook kept all other/current build buckets;
its status reported `removed: []` and storage above its target.

The new `ClaudeRuntimeUpgradeTest` has 18 cases covering old installed bytes,
missing/explicit selection, first installation, restart, a compatible build
with an older bundled pin, rollback, corruption, altered metadata, unsafe
paths, storage-marker failure, offline metadata, HTTP failure and cancellation.

The exact final app and test APKs were installed with `install -r` on the
dedicated x86_64 Compose emulator, then the runner was invoked directly:

```text
am instrument -w -e package dev.androidagent.app.ui dev.androidagent.app.dev.test/androidx.test.runner.AndroidJUnitRunner
OK (34 tests), 44.236s
```

This checks fixtures, not a live emulator model, microphone or accessibility.
The owned emulator was stopped afterwards. No connected-AndroidTest task ran.

## Package and physical evidence

- Dev Debug/test build: `dev.androidagent.app.dev`, code `1133`,
  version `0.14.0-dev.subagents5`.
- SHA-256: `e32d19a7539d3447689f3d831c7b77b7ab1684a7569ca7d43fedfcc80eac0b6f`.
- Debug-key APK Signature Scheme v2; v3 false. 16 KiB alignment passes.
- APK: ignored `artifacts/subagents-device/live-updates/hey-mike-1133-subagents-claude-upgrade.apk`.
- Dev Release built but was not production signed, installed or published.

Xiaomi Redmi 12 / Android 15 had exact known 2.1.285 installed before upgrade.
A binary-safe private database/preferences/runtime backup was taken before
replacement. `install -r` returned Success; MainActivity launch returned OK.
Installed version and installed APK SHA match the final artifact.

Startup wrote an active selection for the old runtime without a Download
button/action. Its original SHA remained
`31efc4136bc678575f4c6730e248d34f89dbfea0468be1c5d012af199cd62ee8`.
The foreground updater then downloaded and activated 2.1.293 automatically,
with a retained previous selection. Its SHA is exactly the compiled pin:
`00755ae106b6925c1adb2b17e4c6d9b0c41eb4cc55bcad3581345b0aa3176c85`.
Activation requires the real loader/version/control-only initialize probe.
The phone model menu showed Haiku and its Haiku 5.5 description.

A new ordinary phone chat saved `CLAUDE`/`haiku` and sent one checkpointed
read-only test. Its trace records one `device_status` call/result with
`success=true`, an exact `QA_HAIKU_1133_OK` assistant reply and completed turn.
No sign-in or credential changes were made. All 102 pre-1133 chat IDs, 1,930
message IDs and two draft IDs remain. This compares IDs, not full row equality.
The original synthetic QA parent was restored; final voice/runs are idle and
accessibility is bound. No phone instrumentation, uninstall or data clearing.

Raw captures, signed-package checks, private backup and trace evidence remain
under the ignored artifact directory. No personal screenshots or account files
were added to Git. Existing public synthetic child-UI fixtures are in `ui/`.

## Remaining gaps

Nothing's 1132 version was confirmed by the app metadata tool. The phone is
not currently visible to computer ADB; 1133 installation and upgrade on that
device remain open. Its original reported runtime deletion was not captured.
Physical corrupt-download/cancel/disk-full cases are unit-tested, not forced
against the user's runtime. Long-call/remote-project/spoken creation gaps from
the child/voice report still apply. This is a dev test build, not a production
release or stress validation.
