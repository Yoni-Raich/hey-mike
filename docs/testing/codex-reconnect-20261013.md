# Codex connection recovery — 2026-10-13

Base: `origin/dev` at `5d7f2f386d67c38dd220832b101e7c9af9431e5f`.
Branch: `fix/codex-reconnect-20261013`, in a dedicated worktree. The dirty
primary checkout and other running Codex/video processes were left alone.

## Behavior

Windows computer chats keep the same Codex app-server through temporary SSH
loss. A token-protected loopback relay retains output and input receipts in
memory. Numbered, length-checked, hashed frames survive partial delivery;
unacknowledged requests can be delivered again to the relay but reach Codex
only once. Recovery does not resend a task or create a competing writer.
The app shows reconnect progress only in that computer's chats. Stop wakes a
blocked local writer; an unreachable computer is bounded by a five-minute
lease. Existing commands are not claimed to be undone.

Native Windows Claude `.exe` chats now use this same retained transport,
with separate launch files and a per-chat exclusive owner file. Starting a
second retained helper for the same session is refused while the first owns
it. Ambiguous startup results never cause a second automatic launch. Literal
native arguments preserve spaces, empty arguments and percent signs without
shell expansion. A damaged Claude protocol frame fails with no private payload
and no automatic task replay. Closing a recovering Claude transport wakes its
writer before taking the BufferedWriter lock.

The engine accepts transport trailing commas with a narrow string-aware
normalizer and validates the result with the existing strict JSON parser.
Tool arguments still use their strict parser. Incomplete and other invalid
JSON fail closed without printing the raw payload. The owned runtime is
closed before reporting terminal reader failure. Connection generations are
checked so an old reader cannot close a newly connected process.

## Checks

The scoped Gradle home reuses caches via junctions and does not load the
machine's automatic build-cleanup init script. Windows/JDK 21; target 17.

```powershell
.\gradlew.bat :engine-codex:testDebugUnitTest :remote:testDebugUnitTest :core:test --gradle-user-home C:\Users\SHIRA\.codex\tmp\gradle-codex-reconnect-20261013 --no-daemon --no-parallel --max-workers=2
.\gradlew.bat :remote:testDebugUnitTest --tests '*CodexRelay*' --gradle-user-home C:\Users\SHIRA\.codex\tmp\gradle-codex-reconnect-20261013 --no-daemon --no-parallel --max-workers=2
```

- Scoped core/engine/remote checks passed before the last relay progress/budget
  additions. The final relay suites passed after those additions.
- Real shipped PowerShell/C# helper tests passed with a harmless echo child:
  lost receipt, reconnect, Unicode, capability rejection, owned-child Stop,
  and offline lease expiry. Frame tests cover partial output, valid-base64
  truncation, duplicate output, offline Stop, host-key rejection, expired relay
  state, and repeated invalid frames without an unbounded recovery budget.
- Real pinned Windows Codex 0.159.2 and an in-process SSH server passed an
  actual SSH session disconnect/reconnect. The broker remained alive, its
  metadata stayed unchanged, and the same loaded thread remained available.
  The test used an empty temporary CODEX_HOME and project, no sign-in and no
  `turn/start` or model request. A zero-turn thread has no persisted rollout;
  history resume was not used as evidence.
- Existing retrying upstream Codex error notifications still deliver text and
  completion. New JSON tests cover trailing commas, escaped quoted text,
  malformed syntax, private-payload suppression and owned-runtime cleanup.
- A computer recovery-status integration test kept its child run active and
  left the concurrent phone run and its status unchanged.
- Post-Claude scoped checks passed: 1,067 unit executions, no failures/errors,
  two existing Linux-only skips. New cases cover literal native launch settings
  and refusal of a second owner using a different relay instance, plus damaged
  Claude output without leaking its payload or automatically replaying a task.

## Final build evidence

The combined source passed the full gate plus app lint:

```powershell
.\gradlew.bat :core:test :engine-codex:testDebugUnitTest :engine-claude:testDebugUnitTest :remote:testDebugUnitTest test assembleDevDebug assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --gradle-user-home C:\Users\SHIRA\.codex\tmp\gradle-codex-reconnect-20261013 --console=plain --no-daemon --no-parallel --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process
python -m unittest tools.test_prepare_runtime
git diff --check
```

- Unit XML totals: 2,659 executions; 2,627 passed, 32 existing skips, zero
  failures/errors. Skips are Linux/native fixture prerequisites and existing
  quick-action fixtures, not evidence of physical phone coverage.
- App lint: zero errors/fatals, 21 warnings. Voice lint: zero errors/fatals,
  two warnings. Runtime preparation: 15 Python tests passed.
- The combined run built all artifacts and reports. A PowerShell 5 wrapper
  misclassified the SDK XML warning as NativeCommandError and wrote 999;
  that receipt is not claimed as a passing gate. Repeating the unchanged gate
  with separate native stdout/stderr and the process's actual exit code passed
  in 15s: four executed tasks, 1,170 up-to-date; exit code 0.
- Final logs are outside Git:
  `C:\Users\SHIRA\.codex\tmp\codex-reconnect-claude-gate-confirm-20261013.log`,
  its `.err.log` and `.json` receipt. The earlier slow background run was
  stopped only by its task/PIDs and restarted at normal priority. Other agent
  and rendering processes were left alone. The temporary task was removed.
- Nineteen changed source/test files match the frozen SHA-256 manifest at
  `C:\Users\SHIRA\.codex\tmp\codex-reconnect-final-source-20261013.json`.
  Both APKs contain byte-identical current `relay.cs` and `relay.ps1` resources.
- Both packages are `dev.androidagent.app.dev`, 1133 /
  `0.14.0-dev.subagents5`, with successful 16 KiB ZIP alignment. Debug is
  debuggable and its signature v2 verifies (v3 is false). Release is
  non-debuggable and unsigned. These are validation artifacts, not a published
  release or an installable update over the phone's newer 1143. No downgrade
  or data-removing install was attempted.

| Artifact under `app/build/outputs/apk/dev/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `debug/app-dev-debug.apk` | 333380300 | `8618fc3a356fa334a747e19994484a1297cb3dfb15ac28f013e2630bf5394ac0` |
| `release/app-dev-release-unsigned.apk` | 291444581 | `0fd5b5722b73a3580f77bd0edfda9b098fc092ba1e037c1a3bbf0079ecc23768` |

## Remaining gaps

No installed-phone evidence, real elevator/mobile-network outage, authenticated
model turn during an outage, WebRTC media recovery, Linux retained process,
phone process restart recovery, or migrated legacy writer lock. Existing
external writers are never killed. The initial source of the reported invalid
JSON is not established from the abbreviated error. Nothing was installed,
published, merged, or version-bumped for this validation.
No real Claude executable/model turn through the relay was exercised. Claude's
engine uses protocol fixtures; the real Windows helper tests use an echo child.

Claude coverage does not migrate legacy direct-SSH processes. Linux and Windows
npm `.cmd` Claude launches still use the direct path. A provider quota rejection
is terminal; transport recovery cannot make more quota available.

## Live incident observations

The reported Codex app-server process was replaced after repeated stream EOFs.
Windows Application/System warnings checked from roughly 15:50 to 16:35 had
no matching Codex crash record. EOF alone does not establish a process crash,
network loss or caller-initiated closure. The new transport was not installed
on the reporting phone, so these repeats are not evidence of its behavior.
The phone's current workspace trace confirms four EOF failures and one separate
60-second timeout. `apps_settings(app_info)` reports installed version 1143 /
`0.14.0-dev.delta4`. This read used on-device tools; no ADB was required.

The user-named Claude session's transcript has overlapping main conversation
branches and a final answer at 16:21 local. That supports investigating a lost
old attachment rather than assuming no answer was generated. The exact phone
fallback event is not in the computer transcript. Separate provider rejection
messages at 16:28 and 16:29 report a session quota limit resetting at 17:50.
No existing rendering processes were stopped during this investigation.

Final build/check evidence is recorded in PROGRESS.md.
