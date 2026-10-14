# Claude computer session continuity — 2026-10-11

Implemented in an isolated worktree based on dev
`6692067402dba86841ef35c20fdad450166243bf`. Original dirty checkout preserved.
This is implementation and bounded validation, not a completed physical E2E run.

## Result

- The project drawer lists native Claude Code and Codex conversations, with
  distinct engine/computer/session identities. Import keeps the native Claude
  UUID and working folder. Refresh appends new text, preserving local messages,
  notes, attachments, draft and queued work. Repeated import/refresh is deduped.
- Existing computer Claude bindings gain an explicit provider/native UUID.
  Native message IDs persist locally; interrupted UI writes can recover from
  the transcript. One-time matching of older local history is best effort.
- Selection, app resume, Refresh history and idle Send update computer Claude
  history. Sync and Desktop handoff block new Send/voice UI actions; refresh
  rechecks active runs before appending. Active turns retain steering/Stop.
- A detected external writer or unknown status blocks writing. Imported missing
  sessions are refused rather than silently replaced. Only Mike's idle process
  is released; another client's process is never stopped.
- Open in Claude Desktop stages a short bridge command and uses official
  `claude --desktop --resume <uuid>` in the signed-in Windows desktop session.
  The UI reports a launch request, not proof of a visible resumed conversation.

## Native-store validation on Pc

Read-only scan of the real default Claude store; no auth files, model calls,
transcript edits or personal text/screenshots were saved in this report.
Installed CLI checked earlier in this task: 2.1.295.

| Check | Final observation |
| --- | --- |
| Top-level UUID transcript files | 97 |
| Listed sessions, both shipped readers | 92; identical UUID sets |
| Files omitted with a partial-list warning | 3 without valid project metadata; 2 sharing a duplicate UUID |
| Readable histories, both readers | 91, totaling 2,623 projected user/assistant messages |
| Explicit read failure | 1 malformed complete record containing raw NUL bytes |
| Windows full scan | 86.121 s; maximum individual read 4.668 s |
| Largest final Windows display payload | 110,907 bytes, below the 8 MiB limit |
| Detected writers during scan | 0 |

The counts describe one local-store snapshot, not all Claude deployments.
The malformed file remains unchanged and requires source repair before Mike
can import it. Duplicate native IDs are refused because choosing a project
silently could resume the wrong conversation.

The earlier implementation exposed four history failures. Three were caused
by rejecting copied fork ancestors with a different `sessionId`; one came
from following `logicalParentUuid` across compaction. Both reader defects
were fixed and reproduced in shared fixtures. Windows initially accepted a
complete record containing raw NUL bytes that Python rejected; it now reports
the same explicit corruption error. The final readers agree on list identity,
read count, projected message count and corruption result.

## Automated checks

- Shared Windows PowerShell/Python fixtures: 11 passed. Covered Unicode/text
  blocks, branch selection, foreign IDs in fork ancestors, compaction summaries,
  trailing internal records, incomplete tails, malformed complete records/raw
  NULs, missing/duplicate IDs, cycles, live/stale PID markers, large images,
  custom configuration directory, invalid IDs and symlinked projects.
- `python -m unittest tools.test_claude_sessions tools.test_prepare_runtime -v`:
  26 passed, 8.166 s. Expected negative runtime-fixture error messages are
  asserted failures, not failing tests.
- Unit regressions cover provider identity, persisted bindings, append-only
  deduplication, repeat answers, multiple live blocks, restart/unsaved-output
  recovery, one-time legacy adoption, strict missing-session resume, idle
  release and active-run refusal, plus existing Codex drawer behavior.
- Windows interactive bridge exercised with a local CLI stub, including the
  same UUID/cwd, paths with spaces, result readback and task/script cleanup.
  Latest recorded fixture durations were 1.771/1.455 s across unit variants.
  This stub opens no Claude UI and calls no model. A real command-length failure
  found in validation was fixed by uploading the bridge before invoking it.
- Three added Compose instrumentation cases check Claude drawer selection,
  refresh/Desktop action routing and busy-chat Send refusal/no Codex fork.
  They are compiled into the test APK; they were not run on a device.

- Full gate: `gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :app:assembleDevDebug :app:lintDevDebug :voice:lintDebug --no-daemon` passed
  in 55 s. 2,353 XML unit executions across variants: zero failures/errors,
  16 skips. `git diff --check` passed. Cached Gradle home was isolated from the
  user's global init scripts; `HEYMIKE_TEST_DESKTOP_BRIDGE=true` enabled the
  signed-in Windows stub check. Log: local `mike-claude-sync-native-final.log`.
- Dev Debug APK: `dev.androidagent.app.dev`, 28 / `0.14.0`, debug-key v2 and
  16 KiB alignment verified. SHA-256:
  `c0da717e6c70c8500a431b87877313314daa676e1efdc2fc28ea492f88da8222`.
  Dev Release is unsigned and was not installed. Both variants contain the
  exact source bytes of both readers. No release/version bump was requested.

## Scope and remaining gaps

- Covers local Claude Code / Desktop Code-tab transcripts. Cloud Chat, Cowork,
  Remote Control cloud history, SSH/WSL stores and Mac Desktop handoff are not
  claimed to be synchronized. Desktop handoff requires Windows, the default
  store, an interactive signed-in user and Claude Desktop installed/signed in.
  Custom stores remain readable/continuable through the computer CLI.
- No Android device was attached to Pc during final checks (`adb devices -l`
  empty). No installation, new UI screenshots, instrumented execution or live
  phone-to-SSH flow was performed. The original dirty device-validation files
  and checkout were preserved.
- No real native Desktop conversation or model turn was opened in this task.
  Pending: new Mike turn -> Desktop same-UUID continuation -> close Desktop ->
  Mike refresh/new turn, and a Desktop-origin session followed in Mike.
- The Linux Python reader passed the shared fixtures on Windows; live Linux
  process discovery, Server SSH and Desktop handoff were not exercised.
- Writer detection uses process arguments and native PID metadata. It is
  advisory, not an atomic lock shared with every external client. Clients
  without visible markers and a simultaneous external launch remain a race.
- Legacy history matching consumes each local message once, with role/text
  and a five-minute timestamp window. Unmatched or clock-skewed legacy text
  may be displayed twice. It is never used to delete local history.
- Copied identical Claude UUIDs routed to two computers are refused for writes;
  the engine's global native-ID routing is not a cross-computer namespace.
- Project setup still depends on the existing Codex/ChatGPT setup path. Computer
  Claude skills are not added to the composer. Phone MCP state is not migrated
  to Desktop. Refresh releases an idle CLI so the next turn reloads native disk
  history; this can add startup latency. No new latency improvement is claimed.
- Interrupted SSH before bridge execution can leave a small app-owned staged
  script. Successful fixture execution left no `HeyMikeClaude-*` task behind.

## Next live checks

1. Attach a test phone; install the exact Dev Debug APK with replacement only.
2. Open a Desktop-origin Claude session from its project in Mike; compare text,
   native UUID, provider and cwd without exposing private evidence.
3. Use an isolated QA project for a Mike-origin Claude turn; hand off to Desktop,
   verify its visible UUID/history, continue there, close, refresh Mike twice,
   and continue in Mike with no duplicated messages.
4. Repeat with a fork and a compacted session; verify drafts/attachments survive
   backgrounding, disconnect/reconnect, active-turn refusal and Stop.
5. Repeat discovery/resume on Linux Server; exercise missing/malformed transcripts
   and inaccessible writer metadata with explicit recoverable errors.

## Deep review and fixes — 2026-10-14

Reviewed on top of dev `358d2ef` (relay restore #132, tool search #133, stale
relay owner #134). The pending 2026-10-11 merge was concluded as it had been
resolved (strip and voice banner both kept, send guard beside dev's voice
routing) and dev `358d2ef` merged after it (composer keeps the sync and the
compaction guards). The unpublished fix commit was re-authored to the
authorized Git identity with its tree and GPT-6 sign-off unchanged.

### Found and fixed

| Problem | Effect before | Fix |
| --- | --- | --- |
| Desktop bridge and job script written as UTF-8 without BOM, paths as plain literals | A Hebrew folder or user name reached `claude --desktop` as other letters (PowerShell 5.1, code page 1255) | Both scripts are pure ASCII; every path is Base64 UTF-8 decoded in the script; files also get a BOM; `Process.Start` instead of `Start-Process` (`[` is not a wildcard) |
| Windows JSON printed to the SSH pipe in the OEM code page | Bytes `0x99 0x8C` for two Hebrew letters in probes, folder lists and Desktop errors | `WindowsHost.asciiJson` escapes non-ASCII as `\uXXXX` |
| Desktop answer lost after the task started | Error toast only; a retry could hand off twice and Mike could write meanwhile | `ClaudeDesktopUnknownException`: chat blocked with the reason, no automatic retry, released by a check that finds no writer |
| One binding thread for two engines | Codex -> Claude -> Codex on a computer started an empty Codex thread; a Claude session Mike started in an imported Codex chat failed closed and could be locked by a missing first transcript | `RemoteBinding.parked` keeps the other engine's thread and origin; `opened()` moves between them |
| Compaction | History showed only the part after the last compaction plus Claude's summary as a user message | Readers cross the boundary once through `logicalParentUuid` (same file, never back) and hide `isCompactSummary` / transcript-only rows |
| Send held back by the Claude check | The typed text was gone | Text returns to an empty composer |
| Text shown after Stop | Not recorded as Mike's, so a refresh added it again | Recorded for the owning visible turn, also after Stop was asked |
| Drawer follow keys | A Claude chat saved before bindings named an engine, or a parked thread, was offered again as a second chat | `PcChats.followed` covers binding, parked and chat threads; import reuses it |
| Engine switch | The busy banner kept the old engine's state | Re-check on switch; banner follows the chat's engine |

Reader file name moved to `session-reader-v3` because the projection changed.

### Evidence

- Real store on Pc, read-only, counts only: 114 sessions listed by both
  readers (same IDs; 4 hidden duplicates, 3 folderless stubs). 113 readable by
  both with 4,009 messages and 0 per-session mismatches; the same 1 file fails
  as malformed. The previous reader showed 3,168 messages for the same 113:
  the difference is history before compaction. One compacted 2.1.284 session:
  6 messages with the summary before, 63 without it after. Slowest Windows
  read 9.3 s while Gradle was running.
- `HEYMIKE_TEST_DESKTOP_BRIDGE=true`: the real one-off interactive scheduled
  task ran the bridge in the signed-in desktop with a fixture `claude.cmd` in
  a folder named with Hebrew letters, a quote and brackets. The fixture got
  `--desktop --resume <uuid>`, ran in that folder, and nothing was left behind.
  No Claude Desktop window was opened and no model was called.
- PowerShell 5.1 prints `{"a":"\u05e9\u05dcx\ud83d\ude00"}` through
  `asciiJson` and Kotlin reads it back exactly (unit test on Windows).
- Red/green: the Stop ledger test and the parked Codex thread test fail on
  the previous code and pass now.
- Full gate `gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :app:lintDevDebug --no-daemon`: passed; 2,717 XML unit
  executions, 0 failures/errors, 34 skips. `python -m unittest
  tools.test_prepare_runtime tools.test_claude_sessions`: 30 passed.
  `git diff --check`: clean.

### Still not verified

- No phone -> SSH -> Desktop -> phone run: no APK was installed for this
  task and no real Desktop handoff was opened. Compose fixture tests were
  compiled, not run (the screenshots in `ui/` are from the 2026-10-11 fixture
  run of the same tests).
- Linux reader on a Linux host, Mac Desktop, and a Mike-origin prompt with an
  attached file matched against its transcript text are untested.
- Writer detection stays advisory; a client without visible markers or a
  simultaneous launch can still race.

## Primary references

- [Claude Desktop CLI/session handoff](https://code.claude.com/docs/en/desktop#coming-from-the-cli).
- [Claude Agent SDK session management](https://code.claude.com/docs/en/agent-sdk/sessions).
- [Official Python SDK transcript-chain implementation](https://github.com/anthropics/claude-agent-sdk-python/blob/main/src/claude_agent_sdk/_internal/sessions.py)
  checked on 2026-10-11 for parent-chain/compaction and native filename identity.
  The bounded helpers are implemented here; the SDK is not added to the APK.
