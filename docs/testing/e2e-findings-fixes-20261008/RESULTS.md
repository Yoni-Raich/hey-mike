# Findings fixes and Xiaomi validation — 8 October 2026

The workflow reload and computer draft defects are fixed and have physical-device evidence. Stop now records a cancelled tool with its completed prefix; the physical Stop check passed. Media metadata lets the model detect the earlier wrong-duration export. UI timing is measured. The full engine comparison and the new screen-settle timing change still need live checks.

Work is isolated on `fix/e2e-validation-findings-20261008`, based on dev `72bbe603`. The original dirty checkout and its historical QA ledger were preserved. No reviewer was started; the user will do the strong-model review later.

**9 October follow-up:** accessibility is now confirmed bound on the installed 1122 build. Real repeated screen/streaming checks and workflow/UI load have been exercised; the earlier accessibility blocker below is historical. See [stress evidence and remaining limits](STRESS-20261009.md). A full matched engine benchmark and new CapCut exports are still not complete.

## Implementation and evidence

**Final follow-up:** [stress fixes and 1124 evidence](STRESS-FIXES-20261009.md)
records bounded Claude auth recovery, coalesced Pc refresh, a guarded UI harness,
two exact Pc/Xiaomi round-trips, complete log windows and the final installed
debug 1124. The invalid 100-cycle attempt is retained and excluded from passing
timing results. Full validation and CapCut repeats remain unfinished.

| Proposal | Implementation | Actual validation | Remaining limit |
|---|---|---|---|
| Workflow text save/reload | Keep a call's text inside arguments; validate serialized definitions before atomic save | Sonnet saved, described, ran and independently read `EXACT_FIX_1121`; four calls/results. Unit tests cover parameters/outputs and protecting an existing valid file | One physical success; not every workflow action |
| Pc unsent draft | SQLite per-chat drafts; exact text stored/read back before acknowledgement; composer restores it and does not consume it | Xiaomi → Pc `open_chat`, exact Unicode/newline/leading and trailing spaces. Visible after reopening, switching chats and process restart; remained unsent | No target task was executed; source interruption during open_chat and immediate termination during ordinary debounced typing are not tested |
| Stop evidence | Non-cancellable terminal tracing, typed `tool_cancelled`, retained plan prefix/outputs; no screenshot or retry after cancellation | Sonnet `act_plan`: first QA file written; Stop during wait; one call/one cancellation; failed step `waiting`, one completed step and output retained. First file still exact; tail absent 120 seconds later | This uses the no-screen-backend wait fallback. It is not CapCut interruption, a network UNKNOWN test or proof of all Stop race paths |
| Own UI masking | Semantic, unchanged and image results explain `agent_own_ui`; own handles remain excluded; own active UI does not use whole-display screenshot fallback | Three Sonnet calls: force read returned zero matching nodes plus masking, same query returned unchanged plus masking, screenshot returned captured false. Final reply explained the intentional masking with no extra calls | Built/installed 1122 also changes the package-filter hint; its new idle-event policy needs another live screen run |
| Export validation | Actual duration/dimensions/rotation via media info, unknown metadata omitted; shipped `media-output-check` skill | Real retained CapCut exports: 6037 ms and 4040 ms, both 720×1280, rotation 0. Model flagged the first against the 4000 ms target and stated visual/decode checks were not done | No new CapCut export/repair run here. Metadata alone does not close the earlier bad export case |
| Controlled QA | Exact Unicode input through app IME; persisted draft and trace oracles; idle-only UI dumps; checkpoints before Send; explicit serial and no automatic uncertain replay. Native fixture has Unicode, rebuild, delays and simulated negatives | Debug receiver/harness used in all new cases; native fixture builds | Xiaomi rejected fixture install with `INSTALL_FAILED_USER_RESTRICTED`. No bypass or blind retry. Fixture edge cases remain unrun |
| Matched engine benchmark | Timing analyzer and exact same Settings → Mike → file marker task prepared | GPT attempt returned `usageLimitExceeded` before any tool call | Three successful repeats per engine were not run. GPT quota and now-disabled accessibility block this comparison; no general speed ranking |
| UI latency | Opt-in debug touch dispatch → next draw probe; separate raw gfxinfo and agent/tool timing | 20 drawer opens and 20 closes, three warmups, all drawn; figures below | Debug, idle, warmed drawer only. No before/after baseline, release speed, streaming/keyboard workload or display-presentation measurement |

## UI and agent latency

Tested on Xiaomi Redmi 12 / Android 15, build 1122. The drawer contained 281 chats and two places. No model run was active. The keyboard remained in the tested layout. Each opening frame interval was reset and retained separately.

| Input to next draw | Samples | Median | p95 | Max | No draw / superseded |
|---|---:|---:|---:|---:|---:|
| Open chat drawer | 20 | 16.544 ms | 19.565 ms | 21.312 ms | 0 / 0 |
| Close chat drawer | 20 | 16.416 ms | 18.612 ms | 20.188 ms | 0 / 0 |

This is touch ACTION_UP dispatch to a draw callback. It excludes touch hardware, GPU/display presentation and completion of the drawer animation. Draw and GPU execution are separate stages; see [Android rendering guidance](https://developer.android.com/topic/performance/rendering/profile-gpu). A fast next draw is not proof of a fast complete agent task.

Android's own counters across the 20 reset opening intervals reported 4,238 rendered frames and 111 janky frames (2.619%). Keep this separate from the touch figure. All 2,400 retained per-frame rows had nonzero OEM Flags=32, so the strict parser accepted zero rows. Per-frame percentiles and parsed deadline misses are **unavailable**, not zero. Raw rows are retained for later OEM-specific analysis; no interpretation of flag 32 is assumed.

The 1121 own-UI case had read calls of 414 ms and 3398 ms, and a masked screenshot of 118 ms. Source inspection found that streamed own-app content could reset the idle clock. Build 1122 ignores those own content/text/scroll events for settling, while keeping real window changes and other apps' events. Unit tests pass, but the causal link and physical improvement are **not yet proven**: accessibility is now off and the normal Xiaomi confirmation awaits the user.

Single-case agent timings, not a matched benchmark:

| Case on 1121 | Calls | Tool median / max | First tool after prompt | Completed turn |
|---|---:|---:|---:|---:|
| Workflow save/describe/run/read | 4 | 114.5 / 126 ms | 7051 ms | 17442 ms |
| Own-UI masking | 3 | 414 / 3398 ms | 5697 ms | 21194 ms |
| Two media info calls | 2 | 242.5 / 282 ms | 5960 ms | 11346 ms |
| Pc draft, cold SSH path | 1 | 21175 / 21175 ms | 6429 ms | 29978 ms |

Tool durations use a monotonic clock and can include approval/setup time. Outside-tool time includes model work, network, scheduling and streaming; it is not pure inference time. The stopped 1122 plan has a 1452 ms cancellation duration and no successful completion time.

## Build and installed package

Full gate passed in 3m27s:

```powershell
.\gradlew.bat test :app:assembleDevDebug :app:assembleDevRelease :app:assembleDevDebugAndroidTest :app:lintDevDebug :voice:lintDebug :a11y:lintDebug -PversionCodeOverride=1122 -PversionNameOverride=0.14.0-dev.e2e-fixes2 --offline --no-daemon --no-parallel --max-workers=2 --console=plain
python -m unittest tools.e2e_validation.test_helpers tools.e2e_validation.test_latency tools.test_prepare_runtime
git diff --check
```

217 XML reports: 2,267 unit-test executions, zero failures/errors, 16 skips. This includes flavor/build variants; it is not 2,267 unique physical cases. The final Python checks have 23 tests (8 QA helpers, 15 runtime preparation). App, voice and accessibility lint passed. The native fixture built. The shipped skill validator passed. Instrumentation APK was built only; it was not installed or run on the Xiaomi.

Installed package: `dev.androidagent.app.dev`, debug code 1122, `0.14.0-dev.e2e-fixes2`. Exact APK SHA-256: `04f9db8c440c962f592352592edff50c6c62a86e4b3fb98f2fa7467d72650dc2`. Signature: Android debug certificate, v2, test-only. Both install receipt and on-device package metadata agree. The release APK was built unsigned and was not installed; its manifest excludes the debug QA receiver. This is not a published release or production signing evidence.

Build 1121 had the same main fixes and supplied workflow/draft/masking/media evidence; 1122 adds the idle-event policy, masking hint and one serializer regression. It supplied UI timing and the Stop check. Do not attribute a new physical screen-speed result to 1122.

## Harness mistakes and new observation

- The first draft assertion selected the empty semantic label rather than its EditText parent. Storage was already exact; the corrected check proved the visible draft. QA open-chat intents now use the app's normal NEW_TASK/CLEAR_TOP/SINGLE_TOP flags.
- The first drawer check used a nonexistent `Chats` heading. It stopped before repetitions and was corrected to the observed `Close chats` selector.
- A hardware Back during a drawer precheck left the activity instead of satisfying the close-drawer oracle. Measurement uses the observed close button. Retain this as **UI-BACK-01**, needing a dedicated physical Back/predictive-back regression; it is not labelled a fixed defect here.
- The first Stop plan omitted package and returned `plan_package_required`, nothingRan true; no Stop or file write happened. A separate explicit-package control finished before the tester could stop it and wrote its tail. The final case uses distinct paths, verifies the in-flight prefix, then presses the observed Stop slot immediately. Only that last case proves cancellation; none of the earlier attempts are silently credited.
- The input runner originally required accessibility even for a call-only test that started with it off. It failed before Send, retained the exact draft, then continued from that safe checkpoint. The guard now only waits for rebind if service was bound before input.

## Logs and evidence

Local raw evidence is under this worktree's ignored `artifacts/validation/`. Important files: `workflow-claude.trace.jsonl`, `masking.trace.jsonl`, `media.trace.jsonl`, `draft.trace.jsonl`, `draft-independent-proof.json`, `stop-1122c.trace.jsonl`, `stop-independent-proof.json`, `ui-1122-c/`, `install-1122.json`, `unit-counts.json`, `post-1122/`, and `live-1122/log-capture-status.json`. Screens and full traces stay local; none are uploaded with the PR.

The completed live capture covers **16:44:53–17:00:51 Jerusalem time**, UID-scoped app logs plus system crash/ANR events; endedEarly false, no collector errors/stderr. No FATAL, ANR or native-signal signature was found in that interval or the final snapshot, and the crash buffer was empty. Exit history shows expected package updates and the deliberate force-stop. The earlier `live/` collector was interrupted without completion metadata and is partial evidence only. Do not claim continuous coverage of the earlier 1121 cases or a two-hour soak.

## What remains

The historical RUN-005 ledger remains **101 cases: 16 PASS, 23 PARTIAL, 3 FAIL, 55 NOT_RUN, 4 BLOCKED** for its original 1120 build. New regressions above are recorded separately; no bulk PASS or retroactive historical rewrite was made. Most of those 55 cases are unfinished testing, not technical blockers. This implementation batch does not complete the whole validation sprint.

For this change, resume with normal Xiaomi accessibility confirmation, then repeat force/unchanged reads on another app and compare the idle-event policy physically. Install the controlled fixture only through an allowed normal device setup; run stale-node, Unicode, delay and simulated-negative flows. After GPT quota resets, run at least three successful equal-output Settings flows per engine. A CapCut benchmark needs three equivalent exports per engine with duration, dimensions and final-frame proof, plus a separate Stop/resume case. Network UNKNOWN/cancel, real permissions/denial, concurrent ownership, voice, update negatives and the planned two-hour soak still need dedicated runs.

No personal messages, purchase, publication, data clear, uninstall, service-grant workaround, merge or strong-model review was performed. The user has been asked to confirm the normal accessibility screen; independent work was completed while that prerequisite remained pending.
