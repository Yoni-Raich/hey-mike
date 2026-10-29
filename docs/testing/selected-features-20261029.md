# Selected features into dev — 2026-10-29

## Scope and source

The user selected rows 3–9 of the conversation audit for merge into `dev`.
The integration starts at fetched `origin/dev` `b9ea1510` in
`%USERPROFILE%/.codex/worktrees/selected-features-20261029`.

| Selected change | Original source |
|---|---|
| Continue after a usage limit; change account/provider | `8c899bcc` |
| Start idle five-hour windows | `08ee3bdb` |
| Reject non-personal notifications before reading content | Policy from `1d67fe6`, adapted to dev's listener |
| Computer Use confirmation routing | Focused 1181 patch, with its core/engine/router tests and UI fixture |
| SSH/SFTP startup robustness | Four preserved files from `claude-sftp-robustness-20261029` |
| Risky full-access command approvals | `56feee2e` |
| Desktop view in the internal browser | `f677c1c4` |

The source worktrees, their staged files and uncommitted work were left alone.
No task-board, Manager, assistant-flow or WhatsApp experiment source is added.
The integration adds Stop guards and shared account-file locking because the
previously separate features did not enforce these boundaries together.

## Checks

The initial scoped integration passed before the additional Stop fixes:
841 core, 165 remote (3 existing skips), 68 Codex, 108 Claude, 15 automation
and 34 web cases, no failures/errors. The remote suite includes eleven real
loopback SSH/SFTP robustness cases: connection loss, bounded retry, uncertain
launch, cancellation, retained recovery and concurrent uploads.

Final source validation passed with this command (Windows, JDK 21, Android
SDK 35, task-owned Gradle home, `ANDROID_HOME` set):

```powershell
.\gradlew.bat :web:testDebugUnitTest :core:test :automations:testDebugUnitTest :engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest :mcp-loopback:testDebugUnitTest :runtime:testDebugUnitTest :workspace:testDebugUnitTest :remote:testDebugUnitTest :device-tools:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

| Suite | Cases | Failures/errors | Skips |
|---|---:|---:|---:|
| Core | 847 | 0 | 0 |
| Automations | 15 | 0 | 0 |
| Codex | 68 | 0 | 0 |
| Claude | 108 | 0 | 0 |
| MCP loopback | 15 | 0 | 0 |
| Runtime | 85 | 0 | 0 |
| Workspace | 47 | 0 | 14 existing |
| Remote | 165 | 0 | 3 existing |
| Device tools | 106 | 0 | 0 |
| Web | 34 | 0 | 0 |
| App | 172 | 0 | 0 |

Total: 1662 JVM cases, zero failures/errors, 17 existing skips. Six new
integration regressions cover Stop during an alarm/switch, global Stop versus
explicit resume, cancelled queue entries, serialized guard loss and interrupted
switch restart. App and test APK assembly pass. App lint: zero errors, 21
warnings and three information findings. Both diff whitespace checks pass.

The local Dev Debug APK is `dev.androidagent.app.dev`, source version
1164 / `0.14.0-dev.connect-sdk` (no delivery version override). APK v2 signature
and 16 KiB alignment pass; v3 is absent. SHA-256:
`409b5ed3f2198f1a8957fadfe7f86718321331121c35af0b1afb98a8216e2779`.
It is a validation artifact, not a phone update or production release.

Python runtime/update checks: `python -m unittest tools.test_prepare_runtime
tools.test_codex_runtime` passed all 24 cases. Negative hash/archive fixtures
print expected rejection messages.

Builds use a task-owned Gradle home, shared locked dependency caches and the
normal marked central bucket. The task-local storage limit disables automatic
deletion of other agents' buckets. No unrelated cache or worktree was cleaned.

## Existing build failures repaired

- Clean downloads of Alpine musl r12 returned 404. The official r13 archive
  downloaded successfully; archive SHA-256 is
  `d616c430bcbe0bf452b7f6ea2669f2cad14e85644a7599f4f495115d021742ef`.
  The extracted ARM64 loader remains 723480 bytes; SHA-256 is
  `42fb025bc758b76e60e7b3b0fcdcfab93b6ba1694b363244c2e8e6f51847e28c`.
  The staging script still checks archive, loader hash/size and ELF machine.
- [Dev nightly 40506383745](https://github.com/Yoni-Raich/hey-mike/actions/runs/40506383745)
  failed at `:workspace:prepareFileDelta` with `Install Android NDK
  28.2.13676358`. Nightly now installs that pinned NDK before building,
  matching the existing Android PR workflow. Checks were not weakened.

## UI and device boundary

The imported Computer Use approval fixture checks request text, absence of a
standing grant, and forwarding only an explicit answer. Its earlier 1181
emulator run and screenshot do not establish integrated native desktop success.
The browser's 34 JVM cases include the seven desktop-setting/gateway cases.

No phone installation or account/window-start operation is performed by this
merge task. Real quota exhaustion, provider/account handoff, Android sleep and
exact-alarm delivery, work-profile notifications, native desktop permission
and input, and real-site desktop layout remain separate device checks.
The changed musl loader also needs an ARM64 Claude launch smoke before release.
Build and unit-test evidence is not a production-release gate.
