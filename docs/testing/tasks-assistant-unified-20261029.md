# Unified task board and personal assistant — 2026-10-29

## Source and integration

Rows 1 and 2 of the project conversation audit describe two layers of the same
feature. The complete source is the 1179 working tree at
`C:/Users/SHIRA/.codex/worktrees/personal-assistant-flow-20261028`, based on
`c65fe575ab56710e8948de3126a8cee967e01c9a`. This integration starts from fetched
`origin/dev` `851d9e8f3f4b02bcff20a913b56bf35afb77f35f` after PR #148.

The isolated branch is `feat/tasks-assistant-unified-20261029`, worktree
`C:/Users/SHIRA/.codex/worktrees/tasks-assistant-unified-20261029`. The original
dirty checkout and all original feature worktrees are preserved. The patch uses
the common base `053cc14ab94582efba6f1015307fde2bcdb05169`, omits already-equal
files, and resolves overlap against current `dev`. Version properties and runtime
pins are retained from `dev`, including Codex 0.163.0 and Alpine musl r13.

The unified feature includes board reliability, the shared particle face and
Hebrew UI, durable source-aware discovery/memory, Manager feedback/commitments,
briefing outbox and explicit execution profiles. See the current architecture
and the retained historical candidate reports for behavior and prior checks.

Integration preserves recovery live guards, serialized Stop, account file locks,
MCP confirmation handling, SSH preparation and desktop browser support. Both live
and replayed task notification paths apply `PersonalNotificationPolicy` before
payload access. Existing recovery/confirmation tests remain. A new queue test
stops permission while preparation suspends and asserts no engine dispatch.

## Validation

Task-only logs and build-storage setup:
`%TEMP%/hey-mike/tasks-assistant-unified-20261029/`.
Central owned build bucket: `build/7d069e481ad68541`.
Task-local storage quota does not delete other build buckets; global settings
are unchanged. The source version remains 1164 / `0.14.0-dev.connect-sdk`.

Focused JVM tests passed for core, workspace, automations, Codex and Claude.
`python -m unittest tools.test_prepare_runtime tools.test_codex_runtime`: 24
tests passed. Integrity-rejection messages are expected negative fixtures.
`python .agents/skills/repo-maintenance/scripts/test_ci_history.py`: 8 passed.

Combined command (Java 21, Android SDK from `ANDROID_HOME`, task-private Gradle
home, shared verified dependency caches):

```powershell
.\gradlew.bat :web:testDebugUnitTest :core:test :automations:testDebugUnitTest :engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest :mcp-loopback:testDebugUnitTest :runtime:testDebugUnitTest :workspace:testDebugUnitTest :remote:testDebugUnitTest :device-tools:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
```

`BUILD SUCCESSFUL`, 5m 9s, 743 tasks (644 executed, 99 up-to-date). All 1,733
JVM cases completed with zero failures/errors; 17 existing cases were skipped.
Counts: core 902, workspace 57 (14 skipped), automations 19, Codex 69, Claude
109, web 34, runtime 85, remote 165 (3 skipped), device-tools 106, MCP loopback
15, app 172. App lint has zero errors, 21 warnings and three information items;
no baseline or gate was weakened. Hosted Android CI is tracked by the PR checks.

The exact debug APK is `dev.androidagent.app.dev`, code 1164,
`0.14.0-dev.connect-sdk`, min SDK 30 / target SDK 35, 340,778,518 bytes. SHA-256:
`4e025d228554749bdc54d63669ec0292df106f9c4991b869c546ede0a1cf6be8`.
`apksigner verify --verbose` passed (debug signing, v2 present, v3 absent);
`zipalign -c -P 16 4` passed. This is a local test build, not a release or update
candidate for the hosting phone.

Installed app and test APK separately with `adb -s <owned-emulator> install -r`,
then ran the runner directly with classes `TaskBoardUiTest` (9),
`TaskTeamLifecycleTest` (3), `TaskPlaybookAndroidTest` (2) and
`McpApprovalUiTest` (1). Result: `OK (15 tests)`, 17.208s. This was a fresh,
task-owned headless API 35 Google APIs x86_64 emulator with a separate AVD home;
no existing AVD data or physical device was changed. Installed package read-back
matched 1164. No connected-test Gradle task or uninstall was used. The owned
emulator was stopped after evidence collection.

Six synthetic UI captures are retained under the task temp folder's
`ui-evidence/`. Inspected `task-board-hebrew.png`: Manager entry, task title,
reason/plan and both decision buttons are visible. Inspected `follow-up.png`:
owner/check time, possible completion requiring confirmation, instructions and
Check tomorrow are visible. The test asserts that Check tomorrow emits only the
follow-up decision. These images contain fixture text, not personal messages.
Screenshot SHA-256 values:

- `task-board-hebrew.png`: `c16267056477b012f1a58fba42e2b236a92b964fd364b54574e877931cc9a189`
- `follow-up.png`: `9523ebe62e84cfe710fc4940d3b2aba8f8efdb354470ac3358682c4c7dab00ef`

`git diff --check` and `git diff --cached --check` passed. Source hashes from
the original feature checkout are checked again before commit. Only this task's
worktree and build bucket are closed after merge; original worktrees stay intact.
The small local evidence folder is retained by name for the screenshots/logs.

## Remaining proof

No hosting-phone installation, real model background pass, sleeping-phone or
JobScheduler endurance test, personal-notification capture, or measured suggestion
quality improvement is claimed. Emulator fixtures do not prove native model
sandbox behavior or useful personal recommendations. Full production release
checks and publication are outside this integration. Source settings remain
opt-in; no account or user source is enabled as part of the merge.
