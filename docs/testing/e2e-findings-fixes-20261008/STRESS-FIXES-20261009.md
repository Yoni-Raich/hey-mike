# Fixes after the Xiaomi stress run

## Changes

The 1122 stress run found a transient Claude OAuth failure and higher Android
jank counters under rapid drawer navigation. Source inspection also found that
every drawer opening started computer-thread listings without coalescing.

- `AgentViewModel` keeps one drawer refresh batch in flight. `RemoteHub` uses a
  per-computer gate and a 15-second monotonic interval for quiet drawer refreshes.
  Explicit connection/setup refreshes can skip that interval; in-flight duplicates
  never queue another listing. Atomic state updates preserve the lists and flags
  of other computers. Cancellation releases the gate and is not swallowed.
- A Claude CLI result explicitly reporting the competing OAuth refresh error
  can retry the exact original content once, after two seconds, in the same CLI
  and app turn. Only a failure before visible text, thinking, tool use or steering
  qualifies. No credentials or competing processes are changed. A second failure,
  permanent sign-in error, lost process or uncertain outcome ends normally.
  Stop prevents the delayed replay; steering discards the old request.
  The final write and steering share a lock so an older retry frame cannot
  overtake a newer steered message. Stop explicitly cancels the retry job.
- The UI measurement harness now resolves a fresh unique selector before every
  tap, checks both drawer states through warm-up and every measured cycle, and
  validates open and close event counts. A wrong screen stops the load instead
  of continuing with saved coordinates. Partial timing results are retained.

This bounds redundant work and adds limited recovery. It does not prove that
computer refresh was the cause of all UI jank or fix the upstream OAuth cause.

## Build and regression proof

Isolated worktree `e2e-validation-fixes-20261008`; original dirty checkout and
historical 101-case ledger were preserved. No reviewer was started.

```powershell
$env:ANDROID_HOME='C:\Users\SHIRA\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME=Join-Path (Get-Location) 'artifacts\gradle-home-stress-fixes'
.\gradlew.bat test :app:assembleDevDebug :app:assembleDevRelease `
  :app:assembleDevDebugAndroidTest :app:lintDevDebug :voice:lintDebug :a11y:lintDebug `
  '-PversionCodeOverride=1123' '-PversionNameOverride=0.14.0-dev.e2e-fixes3' `
  --offline --no-daemon --no-parallel --max-workers=2 --console=plain
python -m unittest tools.test_prepare_runtime tools.e2e_validation.test_helpers tools.e2e_validation.test_latency
```

Full gate passed in 5m19s. XML reports record **2,289 unit executions, zero
failures/errors and 16 existing skips**, across variants; these are not 2,289
independent device scenarios. Python: **23 passed**. Instrumentation APK built
only; no instrumentation, uninstall or data clear was run on the Xiaomi.
After serializing retry/steering and explicit Stop cancellation, the same full
gate passed again in **1m48s**, using version overrides **1124** and
**0.14.0-dev.e2e-fixes4**. Python checks passed again (23), and the changed UI
measurement script passed syntax compilation and its separate device run.

New tests cover 100 duplicate in-flight refresh attempts, computer independence,
quiet expiry/manual refresh and cancellation. Claude tests cover exact replay
content and one turn identity, second failure, Stop during backoff, no replay
after visible output, phone tools or built-in tools, permanent OAuth errors,
and steering during backoff.

After the full gate the steering test was strengthened to require completion of
the steered cycle. A fake user echo without `isReplay` did not claim that cycle;
it was corrected to the CLI's `command_lifecycle` acknowledgement. The scoped
engine test rerun then passed. These were test-only edits. Earlier local attempts
used an empty Gradle cache, omitted SDK environment or named a nonexistent Python
test module; none are credited as successful gates or app failures.

## Installed package

`adb devices -l` identified the sole authorized `device` target as Xiaomi Redmi
12, Android 15, serial `cd4928027d76`. Every device command selected that serial.

Debug APK: `dev.androidagent.app.dev`, code **1123**, name
**0.14.0-dev.e2e-fixes3**, SHA-256
`6fb5529d02ebdea9c73d5cfe31f42ff2f0a05d38a4925d0078db129ee41cbcf7`.
Debug-key v2 verification and 16 KiB zip alignment passed; test-only signing.
`adb -s cd4928027d76 install -r` returned Success. Installed package metadata
matches the code/name, last update 07:31:30 Jerusalem. After launching the QA
chat, the app's read-only state receiver reported accessibility bound and no
active runs. Dev Release was built unsigned and was not installed.

The **final installed build is 1124 / 0.14.0-dev.e2e-fixes4**, same dev package,
SHA-256 `e91f8e1c0f894115185eed19b99a4a62fad9bbf753526cda764d438ebe2301f7`.
Debug-key v2 and 16 KiB alignment verified. Its recorded install returned
Success, installed metadata matched, last update **08:01:46 Jerusalem**, and
accessibility bound after launch. The 1123 measurements below precede this
final installation; the UI and refresh implementation did not change between
these two builds. No release-install or production-signing claim is made.

## Device validation

The initial 100-cycle measurement dispatched all planned inputs but failed its
event-count oracle: zero opening events and 100 closing events. The final screen
was the status sheet, not the drawer. Saved coordinates had allowed a state
divergence to continue; the first divergent transition was not independently
pinpointed. These are **invalid drawer measurements**, not 100 passing cycles.
The rapid-burst preflight then found no opener and stopped before its load loop.
It is not credited as a new burst test. The status sheet was closed by its
observed selector, the drawer was independently opened/closed, and the guarded
harness ran in a separate folder. No personal content was edited.

The separate guarded run completed **20 opens and 20 closes**, with fresh state
checks and exactly 40 accounted draws; final drawer closed and accessibility
bound. Opening input-to-next-draw: median **11.828 ms**, p95 **14.140 ms**, maximum
**16.627 ms**. Closing: median **11.920 ms**, p95 **13.364 ms**, maximum **13.935 ms**.
Android reported **155/4,969 janky frames (3.119%)** over its opening reset
intervals. All 2,400 raw frame rows still have OEM Flags=32; strict per-frame
percentiles are unavailable. These are app touch dispatch to the next draw,
not display presentation, completed navigation or model/task latency. Fresh
idle UI dumps changed spacing between inputs, and installation reset process
history; do not use this to claim a matched before/after speed improvement.

## Claude and Pc on both builds

Sonnet completed a fresh QA chat on 1123 and another fresh QA chat on final
1124. Each used **11 calls and 11 results, no failures**: computers status and
Pc folder browse, copy Pc-to-Xiaomi and Xiaomi-to-Pc, open Settings, three
forced observations, two identical unforced observations and return to Mike.
Each prompt was checked exactly in the saved draft and the trace. Each has one
completed turn, and no computer subagent or outgoing message was created.

Each synthetic UTF-8 file is **29 bytes**, including Hebrew, a newline and two
trailing spaces. Direct device reads and local Pc reads independently match
the original bytes and hash in both directions. All three forced observations
returned three Settings nodes, and both identical reads returned unchanged.

| Build | Whole completed turn | Five read_ui calls: median / p95 / maximum |
|---|---:|---:|
| 1123 | 42.040 s | 74 / 220 / 220 ms |
| final 1124 | 44.452 s | 84 / 240 / 240 ms |

These are successful Sonnet paths, not a model-speed comparison. Neither turn
hit the transient OAuth error or invoked the retry. The exact original draft
was independently retained in storage and in the visible composer after both
installations; the original chat was restored, probe disabled, no active runs,
app in front and accessibility bound.

## Logs and memory

Completed UID/system captures on 9 October, Jerusalem time:

- **07:30:54–08:00:54**, 30m00s, includes the 1123 installation, invalid load,
  guarded valid UI test and 1123 Sonnet/Pc flow.
- **08:00:47–08:05:25**, 4m38s, overlaps the first interval and includes the
  1124 installation, completed Sonnet/Pc flow and final restoration.

Both collectors report complete true, endedEarly false, no errors or stderr.
Live probe rows confirm app UID logs were actually present. No fatal, ANR,
native-signal, OOM, IllegalStateException or IllegalArgumentException signature
was found in these captures or the final snapshot; crash buffer empty. Exit
history adds only the two expected PACKAGE UPDATED entries at the recorded
install times. Collectors and the sampler finished; no background load remains.

117 main-process memory samples, no sampler errors: 114 on PID 19215 (1123),
three on PID 28428 (the expected 1124 update). For 1123, first PSS **190,468 KiB**,
peak **258,399 KiB**, last **204,581 KiB**; retained difference about **13.8 MiB**.
The final 1124 snapshot reports PSS **202,348 KiB**, Java heap **39,468 KiB**,
native heap **25,868 KiB**, graphics **27,199 KiB**. Sampling ended before the
1124 model task finished. Process resets and unequal warm histories prevent a
before/after memory-fix claim; native runtime child heaps are not totaled.
Retained memory needs a longer equal-state profile before diagnosing a leak.

Raw evidence remains local in ignored
`artifacts/validation/stress-fixes-20261009/`; screenshots and full traces are
not uploaded. Keep the 1122 stress results separate from this new installation.

## Remaining limits

The normal Claude task tests the successful runtime/tool path. A real competing
OAuth refresh is intermittent and was not forced by editing credentials or
interrupting another user's CLI. The retry policy has deterministic engine tests,
not a claimed physical reproduction of that failure.

Memory retention remains a measurement finding, not a proven leak. A restart
changes heap/runtime history, so lower post-install memory would not prove a fix.
CapCut repeat exports, three matched successful runs per engine, network UNKNOWN,
real permission negatives, voice/concurrency and the planned two-hour soak remain
separate unfinished validation. No claim that all 101 cases or all possible bugs
are resolved is made.
