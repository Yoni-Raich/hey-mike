# Task-board v2 implementation evidence

Implementation and local checks are complete. Phone installation is waiting
for the user to unlock the phone. This is not yet physical runtime proof.

The user approved implementation of
[the plan](../plans/task-board-reliability-and-manager-20261026.md).
Source baseline: `6bf1f35`; branch `feat/task-board-reliability`, worktree
`C:/Users/SHIRA/.codex/worktrees/task-board-v2-20261026`.
The main checkout and previous feature checkouts are preserved.

Central build bucket: `build/045f31c33a438b7f`. Task-local runner and logs:
`%TEMP%/hey-mike/task-board-v2/validation`. It uses the existing verified
Gradle dependency cache and the storage hook with automatic deletion of
other buckets disabled. Runtime preparation still verifies pinned inputs.
The initial clean build hit the known Alpine musl URL 404; its cached pinned
archive, license and CA file were copied from an existing build bucket.
No runtime pin was changed.

Implemented so far, still under integration tests:

- Stable finalized-message feed in sessions.db schema 10; capture cursor and
  signal insertion in one taskboard.db transaction, long input split into parts.
- Decision/event history, full tasks/get, snooze Wake, immutable approval plan.
- Source ancestry and current-policy checks, including a storage write permit.
- Read-only backstage voice is blocked rather than opened with full native tools;
  Manager voice receives the Manager context.
- Staged background task/memory changes, persistent job records, policy/revision
  conflict checks, output/input acknowledgment transaction, immutable rule
  generations selected through SQLite, unique per-run maintenance reports.
- Compensating note undo and forgotten-note tombstones, without replacing all
  memory. Background queue stores a job reference and resolves its prompt at
  dispatch. Internal Manager review has a restricted profile and bounded triggers.

Build iterations are not final checks. `foundation-compile.log` caught a
missing import and the runtime-cache issue. `foundation-2.log` caught expected
schema-version assertions (9 to 10) and a stale mid-build method signature.
`staging-compile.log` caught cross-module nullable-property smart casts.
Those were fixed. `integration-1.log` passed core/workspace tests and app
compilation. `reliability-2.log` caught one missing test fixture argument;
it was fixed. `integration-3.log` passed core/workspace tests, but the app
compile used a stale core signature after a mid-build edit. Source was frozen
before the broad candidate check.

`candidate-1173.log`: full Gradle gate PASS in 17m55s:

```text
test :app:assembleDevDebug :app:assembleDevRelease
:app:assembleDevDebugAndroidTest :app:lintDevDebug :voice:lintDebug
```

XML totals for that candidate: 2,985 cases across build variants, no failures
or errors, 34 skips (remote 3 per variant; workspace 14 per variant). These
counts include repeated variants, not 2,985 unique test methods.
`python -m unittest tools.test_prepare_runtime`: PASS, 16 cases.
`git diff --check`: PASS at that checkpoint.

After this gate, review found a missing provenance bit on old pending
notifications. Current captures now save `sourceVerified`; legacy external
signals stay outside automatic context. Source lineage also follows generated
team metadata. User turns take queue priority over background references.
Child results can feed discovery with the original task's source ancestry.
Raw chat revisions have a two-week capture window; an overlarge message is a
visible coverage gap and cannot block all later messages.
`final-1173.log`: full gate PASS in 15m41s for these changes.

New regression coverage includes full long-notification storage, inbox
backpressure, forbidden late commits, record conflicts, safe undo after later
edits/Forget, immutable rule manifests, rule revocation, old-source provenance,
queue dispatch revalidation, event history and repeated nightly report titles.
Android fixtures add restricted-voice refusal before opening a normal engine
thread, partial-context drafts and the explicit snooze Wake action.

Only our read-only emulator instance `emulator-5582` is used for new fixtures.
It runs without a window or saved snapshot; no existing emulator or phone is
uninstalled or cleared. The existing Xiaomi and other emulator are untouched.
Native Codex chat on an x86 emulator remains an invalid runtime proof.

The last fixes add durable consumed-event receipts (old feedback cannot
reappear after the 4,000-ID settings window), removal of obsolete learned
subfiles, and a calendar-permission check before creating a calendar job.
Legacy external input is kept in a separate, bounded quarantine with the same
72-hour retention. It does not consume the active inbox; explicit collected-
input deletion clears it too. The original learned index is backed up before
the fixed tool-reader adapter is installed.

`delivery-1173.log`: FAIL, one new Coach Undo test ran against the previously
compiled prompt after an in-build edit. Other selected tasks completed. This
run is not a passing gate. The Coach now receives card category and action
type, and is told that applying undo is a user correction, not approval of its
old rules. Source was frozen and rebuilt before packaging the delivery APK.

`verified-debug-1173.log`: PASS in 6m32s:

```text
:core:test :workspace:testDebugUnitTest :app:testDevDebugUnitTest
:app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug
```

Final scoped XML: core 869, workspace 55 (14 skips), app 170; no failures or
errors. This last scoped gate supersedes the failed prompt check. The earlier
full-variant/release packaging checks are checkpoints, not a claim that the
final unsigned release APK was installed or tested.

Direct instrumentation on our isolated emulator: `android-final-1173.log`,
PASS, all 11 selected tests in 16.737s. Its calendar permission was denied, so
the missing-permission test ran. Six Compose tests cover Hebrew/RTL, large
action text, model/engine selection, a context-reading draft, calendar access
and snooze Wake. Two tests cover Android canonical playbook paths; three cover
team lifecycle, voice refusal and missing calendar access. The screenshot
helper waits for the modal's real window instead of accepting a blank root.
Images are in `%TEMP%/hey-mike/task-board-v2/validation/ui-final/` and contain
only fixture data. They are not personal phone screenshots.

## Exact delivery artifact

Dev Debug: `dev.androidagent.app.dev`, version code 1173,
`0.14.0-dev.task-board1173`, ARM64 and x86_64.

```text
File: %TEMP%/hey-mike/task-board-v2/validation/hey-mike-task-board-1173.apk
Bytes: 343611380
SHA-256: 2f4d986104c29c56c8f95ec11c50cbcbb78f1c94313c48a611188f55ac53f438
Signer certificate SHA-256:
f65c3469483d5b059543c7a67d369578bf3627a02795d77fc76a244444dda1ee
```

`apksigner verify --verbose --print-certs`: PASS, Android Debug certificate,
APK v2 signing. V3 is not enabled for this debug artifact. `zipalign -c -P 16 4`:
PASS. This is a test build, not an official signed production release.

`copy_file` completed all 343611380 bytes to phone Downloads:
`hey-mike-task-board-1173-2f4d9861.apk`, media URI
`content://media/external_primary/downloads/1000238601`.
Opening the artifact first reached the Android keyguard; no Install/Update
tap was issued by this session. The user then confirmed installation. At
22:37, phone `apps_settings app_info` independently verified installed
`dev.androidagent.app.dev`, code 1173, `0.14.0-dev.task-board1173`.
The installed-file hash and live model journey have not been verified.
No app data, credentials or sessions were cleared.

Still required: installed-file hash and a harmless real phone journey,
including the selected model's restricted native profile. Real notification
delivery, screen-off/process-loss recovery, overnight learning quality,
measured model use and battery need separate physical pilot evidence.

Worktree remains open at the path above on `feat/task-board-reliability`,
with local changes kept for review; no commit, push, PR or release publication.
The central build bucket and task temp folder named above are retained for
the APK, logs, test reports and synthetic UI evidence. Other worktrees and
devices were not cleaned or reset.
Our read-only emulator instance `emulator-5582` was stopped after validation;
the base AVD and other connected devices were left intact.

## Visual refresh, dev build 1174

`design-1174-final.log`: PASS for `:app:testDevDebugUnitTest`,
`:app:assembleDevDebug`, `:app:assembleDevDebugAndroidTest` and
`:app:lintDevDebug`. App unit tests: 170, zero failures/errors/skips.
Direct `TaskBoardUiTest` instrumentation on our read-only emulator: PASS,
8 tests in 16.203s. It includes a still-frame face capture, large-font
actions, mixed Hebrew content and dispatch of edited task instructions.
`ui-design1174-final/` contains synthetic fixtures, not personal data.

Dev APK: `dev.androidagent.app.dev`, code 1174,
`0.14.0-dev.task-design1174`, 343652816 bytes, SHA-256
`95970326c89f67ff8fe5acc027cb477298e14af8eececf47c2516e20158c2e6a`.
Android debug v2 signature and 16 KiB ZIP alignment verified. The APK is
kept as `%TEMP%/hey-mike/task-board-v2/validation/hey-mike-design-1174.apk`.
`copy_file` completed all 343652816 bytes to
`phone:Download/hey-mike-design1174-95970326.apk`, URI
`content://media/external_primary/downloads/1000238618`. Opening it reached
the phone's keyguard; no installer confirmation was tapped by this session.
Later `apps_settings app_info` confirmed installed package
`dev.androidagent.app.dev`, version code 1174 and name
`0.14.0-dev.task-design1174`. Installed-file hash and real phone appearance
remain unverified. Our read-only emulator was stopped after the tests.
