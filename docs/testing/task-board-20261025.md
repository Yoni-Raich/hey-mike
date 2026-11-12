# Task board validation — 2026-10-25

Source worktree: `.claude/worktrees/proactive-tasks-20261025`, branch
`feat/proactive-task-board`, based on `053cc14a`. Existing main-checkout
changes were preserved. The installed 1167 PC command-approval source and
tests were carried forward from `e6171bd3` before building the update.

## Build and logic

Windows, pinned cached runtime inputs, task-local Gradle home and central
build bucket `62581fe8d0e198f7`. The task config disables automatic cleanup
of other worktrees. Logs stay under `%TEMP%/hey-mike/proactive-tasks/validation`.

`run-checks.ps1` invokes `gradlew.bat --no-daemon --max-workers=2
-Pkotlin.compiler.execution.strategy=in-process` with:

- `:core:test :workspace:testDebugUnitTest`
- `:engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest`
- `:remote:testDebugUnitTest :app:testDevDebugUnitTest`
- `:app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug`

Both combined gates passed (`final-check-1.log`, `final-check-2.log`). The
second includes the last memory-link and provenance fixes. JVM XML totals:

| Module | Tests | Failures / errors | Skips |
| --- | ---: | ---: | ---: |
| core | 839 | 0 / 0 | 0 |
| workspace | 47 | 0 / 0 | 14 |
| engine-codex | 59 | 0 / 0 | 0 |
| engine-claude | 103 | 0 / 0 | 0 |
| remote | 152 | 0 / 0 | 3 |
| app | 170 | 0 / 0 | 0 |

These are the latest XML totals (1,370 cases); the normalized-home regression
adds one core case after the original 1,369-case combined gates.

`python -m unittest tools.test_prepare_runtime`: 16 passed.
`git diff --check`: passed. App unit tests, both APK assemblies and lint also
passed after the final visual fixes (`final-ui-3.log`). Automation module has no JVM tests;
its successful compilation is not notification extraction runtime proof.

New safety cases cover uncertain start/restart, preserved attempt keys,
Stop with a late child receipt, dedupe after processing, playbook traversal
and protected user rules, owning-chat decisions and restricted-host refusal.
Protocol tests check Claude's empty native tool set and Codex's read-only
configuration. Existing PC command-approval integration tests remain.

## UI

An Android 15 x86_64 emulator was started read-only and headless for fixtures.
App and test APKs were installed with explicit-serial `adb install -r`; the
runner was invoked directly, never with AGP connected tests.
`TaskBoardUiTest`: four passed, covering Hebrew task actions at large font,
forwarding the current note, both model engines, the board's team entry and
the explicit calendar permission action.

The activity-root capture missed the modal window. This was detected during
image inspection and the fixture now captures the actual display through
UiAutomation. Screenshot inspection found dark inherited text on the dark
sheet; its explicit content color is fixed. The final screenshot was inspected.
Fixture images stay under `%TEMP%/hey-mike/proactive-tasks/ui-evidence` and
contain synthetic data only.

## Phone update

Requested target: the phone hosting this chat, identified by phone tools as
Nothing A059. The attached USB Xiaomi is not the requested target and was not
used. Before update, hosting-phone app metadata is 1167 /
`0.14.0-dev.pc-approvals1167`.

Installed Dev Debug `dev.androidagent.app.dev`, version 1168 /
`0.14.0-dev.tasks1168`, without clearing data. Phone metadata and the installed
base APK hash matched the build:
`4ecea76900dff8856578e7ce9e830b7bf02e9a419ff5f9d19e7a99d688afdd79`.
APK v2 signature (Android Debug) and 16 KiB ZIP alignment passed. Transfer
used `copy_file`; wire bytes were not measured, so delta size is not claimed.

### Manager lifecycle repair, 1169

The user reported a non-working Manager. The production empty-draft cleanup
could remove a newly created team chat before its first message; the roster
then pointed to a missing session. The fix creates title and system context
atomically, protects retained chats from cleanup, and repairs missing built-ins
on upgrade. It also cancels stale queued turns for missing chats.

`:core:test :workspace:testDebugUnitTest :app:testDevDebugUnitTest
:app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug`
passed (`manager-fix-1169.log`, 4m04s). Direct emulator instrumentation passed
five tests in 72.456s: four UI fixtures plus `TaskTeamLifecycleTest`, which
opens and leaves all team chats through the real Activity/ViewModel and
recreates the Activity before any user message.

1169 / `0.14.0-dev.tasks1169` was delivered through `copy_file` and the phone's
APK Manager. Phone `app_info` confirms the installed version. Artifact SHA256:
`636f6f6cd1e386cf7b2e434df9a559f5223c81bdee932a5f6e555aef5b703c74`.
Signature and alignment passed. Installed-base hash verification is pending
because the phone's Wireless Debugging backend is off.

### Android playbook path repair, 1170

The user's 1169 screenshot then showed `Playbook links are not allowed`.
The path check compared canonical paths with unresolved app-home aliases.
The trusted home is now resolved first while links inside the book stay
blocked. Added a normalized-home JVM case and Android tests for the real app
home, a trusted ancestor symlink, and rejection of a link inside the book.
The scoped core/app unit tests, app/test APK assemblies and app lint passed
(`playbook-fix-1170.log`, 3m46s). The new Android test APK was first run against
the still-installed 1169 emulator app: both playbook tests failed with the
exact phone error (`playbook-before-1169.txt`). After installing 1170 both
passed. The lifecycle fixture initially waited on main-loop idleness; the
Manager's continuous eye animation could keep it waiting. That fixture run
was stopped on the dedicated emulator. The fixture now waits on bounded
lifecycle/state conditions, without waiting for animation idleness. Its
test-only assembly passed (`lifecycle-fixture-1170.log`, 25s).

Final direct instrumentation passed all seven cases in 8.875s
(`task-board-after-1170-final.txt`): two playbook regressions, the real
Manager/team lifecycle case and four UI cases. No model was called in these
fixtures. No instrumentation was run on the hosting phone.

1170 / `0.14.0-dev.tasks1170` artifact SHA256:
`0af5cfa414c76fb11c1851f4e9d96a49d8d39f01d1171de87714af3537515cc7`.
Size 339,758,746 bytes, matching Android Debug v2 signing certificate and
16 KiB alignment verified. `copy_file` staged it to the chat, then to phone
Downloads. APK Manager's Install action was dispatched once; its screen
reported installation in progress. The Mike device connection then dropped.
The old `app_info` call remained pending and was terminated; a fresh read-only
request also timed out. **1170 installation is not yet verified**; reopen
Mike, check `app_info`, then compare the installed base hash and check the
Manager/discovery runtime. Do not repeat the install without checking state.

The feature worktree, central bucket `62581fe8d0e198f7`, and
`%TEMP%/hey-mike/proactive-tasks` evidence are retained by name while physical
verification is pending. No other agent's worktree or bucket was removed.

## Remaining runtime limits

No claim yet about overnight battery use, Doze/OEM delivery timing, each
mail/WhatsApp notification shape, full notification history, or nightly model
quality. No WhatsApp screen takeover or backup import is implemented. Codex's
native read-only sandbox needs a physical smoke; structural tests alone do
not prove enforcement on Android. This is a local Dev Debug update, not a
production release or the full release gate.
