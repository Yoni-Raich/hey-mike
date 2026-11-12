# Assistant flow 1179 plus limit recovery

The user asked for the limit-recovery feature on top of
`hey-mike-assistant-flow1179.apk`, including that build's changes.
This is a combined test build, not a release or phone-runtime validation.

## Source

- New worktree: `C:/Users/SHIRA/.codex/worktrees/assistant-flow-limits-20261028`.
  Branch: `feat/assistant-flow-limits-20261028`.
- Base: snapshot `c65fe575ab56710e8948de3126a8cee967e01c9a` plus the exact
  23 entries from the 1179 `source-1179.json`. Every source hash matched before
  copying and after copying. The original assistant and recovery checkouts
  were not edited.
- Verified 1179 APK SHA-256:
  `1c5e58b96b64506ab4978d2c0fe1703fb9c1cd9eb6681a6d858172c1d1e5eb24`.
- Added recovery commit `8c899bcc9c3754b21f143ec83b563026bb2c24f1` with
  `git apply --3way`. Kept both sides of the graph declaration conflict:
  the existing TaskDesk and the new recovery/account-switch fields. Kept
  both progress histories. Other product files applied without conflicts.
- Compared 718 base source files with the combined tree. Differences are
  restricted to the recovery feature, version and build record. Existing
  assistant changes, including task follow-through and owner feedback, remain.
- Version source is now 1180 / `0.14.0-dev.assistant-flow-limits1180`.
  No changes to runtime pins, signing keys, lint settings or dependencies.

## Checks

Windows, JDK 21, Android SDK 35; task-local storage init and the existing
private Gradle dependency home:

```powershell
.\gradlew.bat :core:test :workspace:testDebugUnitTest :engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 --console=plain -Pkotlin.compiler.execution.strategy=in-process
python -m unittest tools.test_prepare_runtime
git diff --check
git diff --cached --check
```

Gradle passed in 5m 35s; 693 tasks executed.

| Check | Cases | Failures/errors | Skipped |
|---|---:|---:|---:|
| Core | 894 | 0 | 0 |
| Workspace debug | 57 | 0 | 14 |
| Codex debug | 65 | 0 | 0 |
| Claude debug | 109 | 0 | 0 |
| App dev debug | 171 | 0 | 0 |
| Runtime preparation, Python | 16 | 0 | 0 |

The JVM total is 1,296 cases, including 13 recovery tests and 12 assistant
follow-through tests. App lint passed with zero errors, 21 warnings and
three information findings. Both app and Android-test APKs compiled.
No instrumented test was run for this combined candidate.

The first invocation stopped before compiling: cached runtime inputs had
been placed in a new bucket before the storage helper could mark ownership.
Moved only those task-created inputs aside, registered the empty bucket
through the helper, and restored them. The final build passed. Cached runtime
inputs were checked by the pinned staging script. Storage reported
`removed:[]`; no other build bucket was removed.

## APK proof

- Package: `dev.androidagent.app.dev`, version code 1180,
  version name `0.14.0-dev.assistant-flow-limits1180`, target SDK 35.
- File: `hey-mike-assistant-flow-limits1180.apk`, 340,722,242 bytes.
- SHA-256: `56079ae70084878f2f62b929234dc6f4aaab6b481f21ec785a2994dd4bf8d086`.
- `apksigner verify --verbose --print-certs`: v2 valid, Android Debug
  certificate. Its SHA-256 certificate matches 1179. v3 is absent; this is
  a debug/test APK. `zipalign -c -P 16 4` passed.
- All 41 entries under `lib/` and `assets/` have the same names and bytes as
  1179. DEX contains `UsageLimitRecovery`, `TaskFollowThrough` and
  `TaskBriefingNotifier`.
- Dev Debug assembly exported the signed APK to the existing delta-transfer
  host cache. The task retained an exact named copy outside the repository.
- Delivered with `copy_file` to the phone's Downloads as
  `hey-mike-assistant-flow-limits1180.apk`. The tool returned `complete:true`
  and 340,722,242 output bytes. That is the reconstructed APK size, not a
  measurement of network bytes or transfer efficiency. No installation ran.

## Retained paths and limits

- Source: the worktree above, with staged combined changes; no new commit,
  push, merge or publication. It stays open for later review or installation.
- Build bucket: `C:/Users/SHIRA/Documents/AI/Android-agent-use/build/e4b68a92be070ed4`.
- APK, logs, input/source manifests and inclusion checker:
  `C:/Users/SHIRA/AppData/Local/Temp/hey-mike/assistant-flow-limits-20261028/`.
- Original 1179 and limit-feature worktrees and artifacts remain in place.

The hosting phone reported installed 1178 before this build. This task does
not install, clear data, uninstall or start personal model work on the phone.
Real quota stops, account/provider handoff, reset wake-up under Android sleep
and the combined physical-phone UI remain untested. The full release gate
was not run. Earlier 1179 emulator results are historical, not a fresh UI
pass for this APK.
