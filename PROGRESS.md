# Progress

### Maintenance: remove a timeout-test timing race — 2026-10-23

Baseline `origin/dev` is `ff5729f7`; unrelated checkout changes were preserved.
The unchanged `readUiTimeoutIsTypedAndNeverFallsBack` passed once in Gradle,
but failed 1/100 repetitions under `java -Xint`: its 10ms real deadline could
expire before the first dump, yielding `expected:<1> but was:<0>`.

The regression now uses virtual coroutine time and the default 6s budget.
It verifies one dispatch, no early completion, completion at the deadline,
typed timeout, one cancellation and no fallback. Its own workspace is cleaned.
Only the test and a test-only dependency changed; runtime timing is unchanged.

After: focused test PASS; same interpreted-JVM comparison 100/100 PASS;
`:device-tools:testDebugUnitTest :core:test --no-daemon --max-workers=2
-Pkotlin.compiler.execution.strategy=in-process` PASS on Windows/JDK 21.
XML: device-tools 106 + core 809 = 915 tests, zero failures/errors/skips.
Task-specific storage init/config kept other build buckets (`removed:[]`).
Hosted CI is tracked in the focused PR. No local app assembly, install,
physical-device test or full release gate was run for this test-only change.

The six failures in the latest 100 CI runs were triaged: four already have
merged fixes, two browser class-setup failures need deeper diagnostics. A
narrow scan of 241 production Kotlin files yielded no removal candidate;
no dead-code safety or instruction A/B claim is made. Evidence and limits:
`docs/history/maintenance-tests-2026-10-23.md`.

Merge review: PR #143 is merged into `dev` at `31a897a6`. PR #144 was updated
with that base, preserving both maintenance entries in the two documentation
conflicts. The reviewed timeout test and dependency are unchanged. Checks for
the combined head are tracked in PR #144 before its merge.

### Evidence-based maintenance: CI duplicate runs — 2026-10-23

Added the development-only `repo-maintenance` skill and a read-only Actions
history helper. Baseline `origin/dev` is `ff5729f7`. The latest 50 Android runs
contained 19 completed push/PR pairs at the same branch/head. Their push jobs
used 7,099 seconds (118.32 runner minutes), excluding queue time. This is a
historical opportunity estimate, not measured savings or billing.

Android CI now checks every PR, integration pushes (`dev`/`main`) and all tags;
feature branches use draft/regular PRs for automatic checks. Only superseded
runs of the same PR are cancelled. Existing checks remain, with audit-helper
tests added. Manual dispatch needs the workflow on default `main` first.
See `docs/history/maintenance-ci-2026-10-23.md` for source links and limits.

Local checks passed: eight helper tests; 24 existing runtime tests; 13 trigger
scenarios and an exact comparison preserving the baseline check job; skill
validation; `actionlint` 1.7.12; and `git diff --check`. No local Gradle build,
device run or release gate was performed. Hosted CI and live trigger evidence
are tracked in [PR #143](https://github.com/Yoni-Raich/hey-mike/pull/143).
Its first head (`4cc4b7c2`) started one PR run and no duplicate push run; the
evidence-only follow-up checks cancellation of the superseded PR run.
Other checkout changes and worktrees were preserved.

### Worktree and temporary-build lifecycle — 2026-10-20

Added a repository development skill and a PowerShell status/removal helper.
Removal checks dirty/unpushed work, Git failures, locks and bucket ownership.
Git must remove the worktree before its build bucket is deleted; a refusal
never triggers a recursive folder-delete fallback. Existing checkout changes
are kept separate from this PR.

`python .agents/skills/worktree-lifecycle/scripts/test_worktree.py`: 11 tests
passed on Windows PowerShell 5.1. Disposable Git repositories covered normal
removal, merged local branches, untracked work, unpushed commits, failed Git
checks, Git locks, an active build lease, a wrong bucket owner, the main/current
checkout and a subfolder, and Git removal failing on a Windows file lock.
Skill frontmatter validation and `git diff --check` passed. Fixtures were
removed. No Gradle build, APK change or device test was needed or run.

### Separate Pinned project/chat tabs — 2026-10-19

Follow-up: New chat now sits above both Pinned tabs and outside the scrolling
lists, as requested. The scoped Dev Debug build, app unit tests (168, no
failures/errors/skips), app lint (zero errors, 21 warnings, three information
items), signature/alignment and diff checks passed again for code 1163.
It was installed in place on the same Xiaomi. Live UI bounds confirm one New
chat button above the tabs; both tab/empty-state checks passed. Pins were
retained and accessibility remains bound. Visually reviewed screenshots:
ignored `artifacts/pin-subtabs-20261019/xiaomi-1163-*.png`. Installed base.apk
matches the built APK SHA-256:
`fdc6c036b5a5ca18050c0fc8da9ce793b900e5694c57a7badc433aca017ccd7e`.
The detailed search/Pin/Unpin checks below were on 1162; 1163 changes layout
only. No instrumentation or full release gate was run for this follow-up.

Isolated branch `feat/pinned-project-chat-tabs` starts at `origin/dev`
`c33b1681`. Pinned now has Projects (first) and Chats tabs, separate scroll
positions, matching counts, search hints and empty states. Projects retain
their expandable chats; Chats contains only individually pinned chats.
Pin changes now invalidate the remembered chat list immediately.

`:app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug
:app:assembleDevDebugAndroidTest --no-daemon` passed with two workers and
in-process Kotlin. App unit tests: 168, zero failures/errors/skips. App lint:
zero errors, 21 warnings, three information items. The three new Compose
regressions compile; they were not run under instrumentation. Build storage
used the existing isolated Gradle home and no-prune init script; no other
worktree output was removed. `git diff --check` passed.

At the user's request the emulator was closed and validation moved to the
USB-connected Xiaomi Redmi 12 / 23053RN02Y, Android 15. Dev Debug code 1162 /
`0.14.0-dev.pin-tabs` installed with `adb -s <selected-device> install -r`.
This was a direct USB install to the selected test phone, not a network copy
to the phone hosting this chat. APK metadata, test-key v2 signature and 16 KiB
zip alignment passed. Installed base.apk SHA-256 matches the local APK:
`b42e8844652bd81365c48ec302530746d0509dfe63b56236337ccee0c9bdd6a5`.

Direct UI checks on 1162 passed: project/chat separation, native tab selection,
retaining the tab when leaving Pinned, search across tabs, and immediate Unpin
updates with both empty states. Two temporary pins survived the updates and
were then restored to the original empty pin set. Accessibility stayed bound
with no crashed service. No test APK was installed, no data was cleared, and
no session was created or deleted. Raw UI evidence and command helpers remain
under ignored `artifacts/pin-subtabs-20261019/`; APK/reports are in the ignored
central bucket `build/e02b9362a42a9b9a/` in the main checkout. Not checked:
the full release gate, another phone, or real model/device-tool execution.

### Connected apps screen: one switch per app — merged to dev, 2026-10-19

Settings > Connected apps used to need "Review capabilities" before the
"Enable connection" switch appeared. Each app is now a card with its icon and a
single switch (the whole top row toggles). Turning on reads the app's abilities
and opens one dialog (look things up / make changes / ask you first) with
"Turn on"; turning off is immediate and needs no reply from the app. Per-ability
switches, ask-first, publisher, installer and recent activity moved under
"Details". The screen refreshes on resume; the Settings hub row says "1 app on"
or "Off · choose which apps Mike can use". Abilities the user never saw stay off
and show as "N new abilities" with a Review button.

Evidence, on the tree rebased onto origin/dev cc02886: `:core:test`,
`:connect-protocol:test`, `:connect-sdk:testDebugUnitTest`,
`:connect-host:testDebugUnitTest`, `:workspace:testDebugUnitTest` and
`:app:testDevDebugUnitTest` pass with no failures or errors in any result XML,
including 9 new ConnectedAppStatusTest cases; `:app:lintDevDebug` 0 errors;
`:app:assembleDevDebug` builds. APK checked with aapt2: dev.androidagent.app.dev,
versionCode 1164, 0.14.0-dev.connect-sdk, 338,653,662 bytes, SHA-256 prefix
98aa1c883cbda190. An earlier build of the same screen (1161) was installed on the
Nothing A059 by the user; the merged 1164 build was not installed.

Untested: the layout on a real screen (dark/light, Hebrew phone), the dialog
with a long ability list, the TalkBack reading of the toggle row, the ON_RESUME
refresh, and turning on LumaStand through the new dialog. No Compose UI test
covers this screen yet.

### Mike Connect developer kit — local preview, 2026-10-18

Prepared connect-sdk/developer-kit with two portable external-developer skills:
mike-connect-app-audit (read-only source evidence and capability planning) and
mike-connect-integrate (SDK provider implementation, domain checks, trust and
validation). Each includes its own references; the audit includes a handoff
template and distinguishes preset selection from missing creative app features.
These skills are not shipped inside Mike's runtime asset catalog.

tools/package_mike_connect.py packages only the three public Apache-2.0 Maven
modules, developer docs/skills and a standalone preferences-provider example.
It checks Maven hashes/dependencies, portable references, exact payload hashes
and existing-version refusal. Output is ignored artifacts/connect-distribution/
mike-connect-developer-kit-1.0.0-preview.1.zip (209272 bytes), SHA-256
d9cce4828b5a693fc2f0666437e01206ae1d84d533a4d6fdc2fae2933dd7ffeb.
SDK AAR/protocol hashes remain identical to the prior validated SDK. The old
local ZIP is unchanged. The kit records its dirty source state, preview status
and production/Central gaps rather than claiming a published stable SDK.

Both SKILL.md frontmatter checks passed with Python UTF-8 mode. Local links
also pass when each skill is checked as a standalone folder. The ZIP was
extracted under a fresh system-temp directory; its independent app assembled
and passed lintDebug with Gradle 8.9, AGP 8.7.3, Kotlin 2.1.0 and JDK 21 in 28s
(44 tasks), resolving the extracted Maven repository without Mike app modules.
This is consumer build evidence, not a live agent skill benchmark or physical
Binder/device test. Packaging unit checks and detailed commands are recorded in
docs/history/2026-10-18-mike-connect-developer-kit.md.

Recommendation: keep source in this repository with independent SDK tags and
GitHub prerelease assets, then add Maven Central for stable distribution after
production trust, namespace, POM/Javadoc/PGP and release CI are ready. Nothing
was committed, tagged, uploaded or published by this packaging work.

### Mike Connect v1 — installed on Nothing; user test pending, 2026-10-18

At the user's request, the hosting Nothing A059 was updated without clearing
data: Mike Dev 1159 -> 1160 and LumaStand 1.6.0 (8) -> 1.7.1 (10). copy_file
delivered both APKs; APK Manager installed them. Installed package versions
and both base.apk SHA-256 values were independently checked through the
deliberately selected A059 ADB connection and match the exact build artifacts.
Mike was reopened. The runtime now reports 1160 and Connected apps: none enabled.
The user still needs to review and enable LumaStand in Connected apps before
testing its capabilities. No connection grant was enabled by the agent.

Isolated feat/mike-connect-sdk starts at origin/dev c33b168. Three public
Apache-2.0 modules, the Android host, Keystore grants, Connected apps settings,
one connected_apps gateway and a sample clock are implemented. The separate
LumaStand project uses the real published local SDK for get_theme, set_theme
and open_clock. No per-app integration code is required in Mike.

Final full gate passed in 9m38s: test, Dev Release, Dev Debug, app/test APKs,
voice/app/SDK/host lint. XML totals across variants: 2,958 cases, zero failures
or errors, 34 skips. Lint has zero errors. Runtime Python tests: 16 passed.
New protocol (6), SDK (6 per variant) and gateway (12) cases include cancellation,
duplicate writes, unknown callers with a stubbed certificate lookup, Parcel
bounds, elicitation, live grant revocation and preserved per-function consent.

Mike test artifacts: versionCode 1160, 0.14.0-dev.connect-sdk1. Dev Release is
non-debuggable, aligned and v3-signed with the test certificate pinned by the
SDK. LumaStand 1.7.1 (10) is signed with its existing key; 39 tests and lint pass.
The Maven SDK bundle and quickstart were copied to the hosting phone's Downloads.

The USB Xiaomi rejected the LumaStand install with INSTALL_FAILED_USER_RESTRICTED
(Install canceled by user). Its Mike remains at 1156; no new app was installed,
and no app data was cleared. Human confirmation in Xiaomi's security UI is
pending. Physical discovery, background calls, real approvals, UI handoff and
cross-UID rejection remain untested. These build checks do not prove those flows.
Commands, hashes and limits: docs/history/2026-10-18-mike-connect-sdk.md.

### Engine updates and visible versions — 2026-10-18

Isolated branch `feat/runtime-updates-20261018` starts at `origin/dev` 7f0a1d54.
Phone and computer Codex pins are 0.163.0; Claude's phone baseline is 2.1.302,
with the earlier verified pins retained. Codex 0.163.0 removed bundled zsh;
staging now reflects that layout and still checks the helper-name patch.

Computer Codex updates use stable official metadata, exact package hashes and
sizes, bounded safe extraction, a separate HOME for --version/initialize,
an OS lock and atomic side-by-side activation. A 15-minute foreground check
stages updates without restarting or replaying live runs. Phone settings,
computer cards, device_status, the per-turn context and computers status expose
installed/selected/running versions. Android's static Codex binary still needs
a Mike APK update; the phone checks availability and explains that limit.

On Pc, the native Claude updater changed 2.1.299 to 2.1.302. The real Codex
updater downloaded and validated 0.163.0, saved it as active for future launches,
and retained 0.159.2. The already-running 0.159.2 process was left alone.
The probe used no real account, prompt or device tool.

Focused Gradle gate (core/runtime/remote/Codex/app unit tests, Dev Debug and
app lint) passed. App lint: zero errors, 21 warnings, three information items.
Python runtime/updater tests: 24 passed; Claude session-reader tests: 17 passed.
Signed Claude 2.1.302 manifest fixtures pass authentication with the existing
release key. The full gate passed in 13m28s: `test assembleDevRelease
:app:assembleDevDebug :app:assembleDevDebugAndroidTest :voice:lintDebug
:app:lintDevDebug --no-daemon`, with in-process Kotlin, two workers and Chrome
enabled. XML totals: 2,928 cases, zero failures/errors, 34 existing skips.
The final scoped remote/app unit, Dev Debug and app-lint check passed in 2m06s
after the last remote selection race fix; release compilation already included
that fix. Final package: `dev.androidagent.app.dev`, Dev Debug, code 1159 /
`0.14.0-dev.runtime-updates`, signed with the local Android test key (v2).
Signature verification and 16 KiB zip alignment passed. APK SHA-256:
`0e42dc94d5ed41d420eba64e7f990e50098894e52baf05b6130213afd5f11774`.
`copy_file` completed the final 340,033,271-byte APK to the hosting chat, then
published `Download/hey-mike-runtime-1159.apk` through MediaStore. The tool did
not report patch/wire bytes, so this does not measure delta savings. The hosting
phone still reports 1158; Android installer approval remains pending. Installed
base.apk hashing and a real ARM64 Codex/Claude task on 1159 are not yet checked.

The two new Compose checks passed on a dedicated API 35 x86_64 emulator using
the direct instrumentation runner, without uninstalling either APK. Screenshots
were visually checked under ignored `artifacts/runtime-updates-20261018/ui/`.
The UI candidate was `9b7468bc...`, before the final remote-only race fix.
They use synthetic 0.164.0 availability to prove current-versus-next labels;
they do not claim 0.164.0 exists. This is UI evidence only, not phone runtime,
SSH recovery, Claude sign-in or end-to-end model/device-control proof.

### Pinned chats and projects: hosting-phone update — 2026-10-18

PR #137 adds long-press Pin/Unpin for chats and projects and a searchable
Pinned tab beside the computer tabs. Pins are local to each phone. The source
includes current dev's internal browser and persistent Claude ownership fix.

In the isolated `hey-mike-pin` worktree, `:app:assembleDevDebug
:app:testDevDebugUnitTest :app:lintDevDebug --no-daemon` passed with version
overrides 1158 / `0.14.0-dev.browser-pin-owner`, in-process Kotlin and two
workers. App unit tests: 168, zero failures/errors/skips; ChatLibraryTest: 4.
APK metadata, signature verification, 16 KiB zip alignment and diff check
passed. The APK is Dev Debug, signed with the local Android test key.
SHA-256: `7D0DEA706423627CCE4765F3772AAED6FC6A59646425E20016D826ABC24F4BA8`.
`version.properties` now records those same overrides.

The exact APK was copied through `copy_file` to the hosting phone and saved
as `Download/hey-mike-pin-1158.apk`. Fresh `apps_settings(app_info)` reports
`dev.androidagent.app.dev`, code 1158, matching version name. The user reports
the feature works well on this phone (Nothing in this chat); this is user UI
confirmation, not an automated visual check. No app data was cleared.
Not checked: installed base.apk hash, automated Pin/Unpin/persistence UI flow,
or the full release gate. Transfer success alone does not measure delta bytes.

### Internal browser: dev integration and panel handoff review — 2026-10-15

Merged current `origin/dev` (`816be90`) into the browser branch as `5d005c8`.
Only PROGRESS conflicted; both the browser evidence and Claude ownership
updates were kept. PR #136 targets dev and includes the full internal browser.
Its initial push and PR CI checks passed on `5d005c8`.

The automated review found a real panel handoff gap: the Android host inherited
a no-op `screenInUse`, leaving the panel above phone actions or approvals after
Give back. A new host regression failed on that code. The host now publishes
a dismissal version; the UI closes its panel and parks the same WebView. Phone
work waits for the frame to detach, refusing after 3s if the UI has not done so.
A visible user-owned page refuses screen work instead of being taken away,
including calls from another chat. Later page updates do not undo an explicit
reopen. Focused host/UI tests passed: 10 + 5, no failures or skips.

Local integration validation hit build-environment problems: a shared Kotlin
compiler wait (only this gate's daemon was stopped), the global storage hook
trying to prune a locked lint-cache JAR, then unreadable shared transform
metadata. Validation uses a temporary Gradle home with its own transforms,
in-process Kotlin and a central-output init script that holds the existing
worktree lease without pruning other builds. Global settings were not edited.
The first full run then failed on Chrome's DevToolsActivePort write/read race
and two SshDeltaPeerTest timeouts. The Chrome fixture now waits for a readable
port within the same 15s startup budget. The serial full gate passed in 12m12s:
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:web:lintDebug :app:lintDevDebug --no-daemon`, with
`-Pkotlin.compiler.execution.strategy=in-process --max-workers=1` and
`HEYMIKE_TEST_CHROME` set. Reports: 2917 test cases across 290 suites, zero
failures/errors, 34 existing skips. Web: 87 per variant, including 60 live Chrome
tests in each, no skips. Both SSH test variants passed without a remote change.
Both requested APK builds and all three lint tasks passed. `python -m unittest
tools.test_prepare_runtime tools.test_claude_sessions`: 32 passed. Diff check
passed. Final-head CI is tracked in PR #136.

Evidence is ignored under `artifacts/browser-dev-merge-20261015/`, including
failed attempts, the red host test, focused green run, full-gate log, summary
JSON and archived result XML. No new merged-tree
phone install was done. Earlier Xiaomi evidence below applies to the earlier
browser code; the panel handoff change has host/UI unit proof only. Hosting
phone metadata read during this integration reported 1157, and was not replaced
with the older 1155 browser APK. GitHub's stale-body Back and the unreproduced
uncertain-click case remain open as described below.

### Internal browser: new-window links, router back, tolerant settling — 2026-10-15

The hosting phone's deep run took 3:00, with 86.2s in browser calls. Xiaomi
`cd4928027d76` (USB, explicit serial) reproduced two causes with a new probe
scenario `github` (production host, refusing approvals, no human input):

- GitHub's external link uses target=_blank. The refused popup left a click
  verify waiting 15.0s each time (gh-0..2 15693-15931ms, red v20) and needed a
  second `open` call. Gesture-started new windows now load in the same page;
  the throwaway window never fetches (local 204) and its address goes through
  the page's own policy path. Same click after the fix: 1231-1554ms, 5/5 (v22,
  v25), destination checked.
- Back on GitHub stays in one document. Probe v21 run 0 reproduced the
  hosting phone's symptom: repository URL, Issues body. `back` now reports a
  same-document step and points to `reload`. Not reproduced in 13 later probe
  backs; seen in both installed-app chats below.
- Probe v21 run 1 hit `Cannot find context` while settling across the new
  navigation. Settling now follows a replaced document (lost context, frame
  or helper) instead of failing a sent action; timeouts still fail.
- Skill: verify by URL or seen text, not a guessed selector (`#readme` cost
  the hosting run a full wait). Timing logs now carry `errorType`.

Evidence (ignored `artifacts/browser-nav-20261015/`): web live-Chrome suite 60
tests per variant, 0 failures (`HEYMIKE_TEST_CHROME`); new tests cover router
back, cross-document back, a settle read losing the document, errorType.
Full gate (`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:app:lintDevDebug`) passed for ee81c74 (2897 executions, 34 existing skips,
0 failures) and again for be3a7dc (exit 0; result XML later evicted by build
storage, so counts were not re-read). `tools.test_prepare_runtime` OK, diff
check clean. Probe v23 then v25 on Xiaomi: sites, controls, forms, keyboard,
github (5 rounds), popup (8 rounds, after reload or wait) all `FINISHED 0`.

Dev Release 1155 `0.14.0-dev.browser-nav2` (be3a7dc), non-debuggable, 16KiB
aligned, v3 signed with the local test key, SHA-256
`a9e8b0a58419278f042a9a1664f25d03d54c0b03975e546dcedf98f7a2061b9e`; installed
on Xiaomi with `install -r`, base.apk hash identical, accessibility bound.
Same prompt in a live Codex 6.1 Sol Low chat (GitHub repo, Issues, back,
external link, show): 1154 used 6 browser calls, the external click returned
uncertain although the page arrived; 1155 used 4 calls, the click and handoff
ran as one 1738ms plan, PC send to final log 41.2s. In both chats the model's
own 8s back verify timed out on the router symptom and reload recovered it.

Gaps: the 1154 uncertain click was not reproduced (0/8 probe runs), so the
settle hardening is a plausible, not proven, fix; errorType logging will name
the next one. A same-document back still costs the model's verify timeout
before reload.

Hosting delivery, 17:14: rechecked the retained signed artifact (291924290
bytes), SHA-256 above, version 1155, 16KiB alignment and v3 certificate. The
hosting phone reported version 1153 before update. `copy_file` completed to
`chat:browser-1155/app-dev-debug.apk` and Downloads URI
`content://media/external_primary/downloads/1000235171`. APK Manager initially
showed Install, but it had already moved to Installing before our tap plan
resolved: target_not_found, zero taps sent. The observed installing screen
reported Silent install over ADB. No install retry was made. The first app-info
read still returned 1153. The follow-up wait and metadata read did not answer
after the update began; the read-only wait was cancelled, without repeating
installation. Final installed-version verification remains open, separate
from the completed transfer and observed installation start.

### Internal browser: deep Xiaomi regressions — 2026-10-15

Code `9fca73d` fixes three reproduced problems: document/visual hit-test
coordinates after keyboard pan or scrolling; CSS smooth scrolling racing
the next hit test; and `display:contents` wrappers hiding MDN's article from
the snapshot and target lookup. Input remains native and trusted. No approval,
Stop, genuine-cover check or uncertain-action retry rule was weakened.

Separate non-debuggable production-host probe on USB Xiaomi 23053RN02Y,
Android 15, WebView 154.0.8037.107: probe 17 passed eight keyboard/scroll
cases (four frame heights, two repeats), six real httpbin form flows, and
cover/user-control/Stop/24-step/two-chat checks. Mid-plan cancellation left
the counter at exactly 2; the long plan added exactly 21 clicks and handed
over in 11220ms. Two chat pages retained their independent state. No form
was submitted. A malformed harness workspace initially triggered the host's
correct deleted-chat cleanup; it was fixed in the test harness.

Probe 19 passed two complete Wikipedia / MDN / GitHub / WHATWG site rounds,
including link navigation, scrolling, verified history back and external
navigation, in 48253ms without a model. Internal clicks including verification:
Wikipedia 1411/1239ms, MDN 1140/962ms, GitHub Issues 1852/3375ms.
GitHub opens its external link as a popup: refusal was observed, then the
known URL was opened in the same page and independently checked. The harness
waits for SPA body readiness after Back and allows one fresh read/re-resolution
only for a stale node with the session input counter unchanged. Probe 19
needed no such recovery. Earlier failed and incomplete diagnostics are kept.

Six new regressions per web variant: native pan, wrong-frame cover, document
scroll, smooth scroll, rendered contents, and hidden contents. The old code
failed the corresponding positive tests before each fix. Final full gate:
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:web:lintDebug :app:lintDevDebug --no-daemon --console=plain`, 1153 version
overrides, passed in 2m38s. Web 80 per variant, no skips; complete matrix 2893
executions, zero failures/errors, 34 existing skips. Runtime preparation:
15 OK. Raw device JSONL/logcat, red tests and gate evidence are ignored under
`artifacts/browser-batching-20261015/deep-xiaomi/`. See
`docs/testing/internal-browser-deep-xiaomi-20261015.md` for scope and gaps.

Hosting-phone metadata was independently read during this work: 1152
`0.14.0-dev.browser-batching1`.

Package: final rebuild from code `9fca73d` passed in 2m7s. Dev Release,
non-debuggable, test-signed, version 1153 `0.14.0-dev.browser-keyboard1`.
16KiB ZIP alignment and v3 signature pass; the existing test certificate
is unchanged. Signed APK is 291932681 bytes, SHA-256
`f27240b19d676d6dc20865a3f2dc8c074883e6791cf6752c3c170cc79189d21e`.
Packaged page helper matches committed source bytes. USB update on Xiaomi
succeeded without data removal; installed base.apk hash and version match.

Full application smoke also passed on that exact installed APK, using a
normal ChatGPT/Codex 6.1 Sol Low chat: open MDN JavaScript, follow JavaScript
reference, show. App timing log: open 3163.4ms, one click/verify/handoff plan
1798.0ms, both OK, zero protocol errors/timeouts. The screen showed the correct
Reference title/domain and You have control. The PC's pre-Send UTC marker to
the handoff timing-log timestamp was 17267.9ms; browser backend sum 4961.4ms.
This is one short smoke, not the earlier form benchmark or a model speedup
comparison. The previously enabled accessibility service needed a visible
Android Settings off/on cycle after update; normal confirmation/countdown
was respected. It ended bound with no crashed services. No secure setting
was written, and no instrumentation runner was used.

Hosting-phone transfer/installation of 1153 is separate from this confirmed
Xiaomi package and application evidence. `copy_file` completed to
`chat:browser-1153/app-dev-debug.apk` and then Downloads URI
`content://media/external_primary/downloads/1000235086`. APK Manager's Install
was tapped once and its next screen said Installing app-dev-debug.apk / Silent
install over ADB. No install retry was sent. The subsequent wait and two
app-info reads did not return after installation began; those read-only waits
were cancelled. Hosting-phone installed version remains unverified. No
hosting-phone post-update browser success is claimed.

### Internal browser: fewer model/tool exchanges — 2026-10-15

Known forms can now fill, use a read-only `check` of live values/checked
states, and hand over with `plan(show:true)` in one call. A failed check
stops later steps and handoff; a lost handoff reply retains the completed
ledger. Secrets are refused. Plans allow 24 known steps within the same
80s ceiling. The schema and shipped agent instructions explain this path.

Controlled physical comparison in separate non-debuggable probe 10 on
Xiaomi Android 15: three alternating pairs, same two fills, Apply once,
independently checked final DOM and user control. Split: 5 calls, median
1451ms, 2885 reply bytes. Batch: 1 call, median 1474ms, 2365 reply bytes.
That is 80% fewer calls and 18.0% less returned text; backend time stayed
similar. No model delay, token measurement or wall-time speedup is claimed.
The focused probe ended BATCHING_PASS; the separate screenshot issue remains
open. Raw evidence is ignored under `artifacts/browser-batching-20261015/`.

Trace entries now separate app-side queue, invocation and result handling
(`tool_timing`) and record numeric run totals (`run_timing`). The residual
summary is labelled outside tool calls rather than pure thinking. A virtual
clock verifies 600ms queue / 1000ms invocation / 300ms delivery without
recording private arguments in timing entries.

Checks: five initial new browser tests failed before implementation. Final
full gate passed in 2m51s with 1152 version overrides: `test assembleDevRelease
assembleDevDebugAndroidTest :voice:lintDebug :web:lintDebug :app:lintDevDebug
--no-daemon --console=plain`. Web: 74 per variant, core: 787, no failures or
skips in those modules. Across all variants: 2881 test executions, zero
failures/errors, 34 existing skips. Runtime preparation: 15 OK.
The hosting phone's complete model-driven run with this change is not yet
measured. See `docs/testing/internal-browser-batching-20261015.md`.

Delivery: code commit `d88eddd`; final Dev Release rebuilt in 30s with
version 1152 `0.14.0-dev.browser-batching1`. ZIP alignment, v3 signature and
the existing test certificate verified. APK SHA-256
`88d69a261fb2c940ee9fd9a5971f423c8fdddd22e956e9f929958c28bd55a052`,
291932681 bytes. Packaged helper, browser skill and agent instructions match
the committed sources byte for byte. `copy_file` completed to the hosting
chat and local Downloads. APK Manager then displayed Installing; our Install
step found no button and sent no tap, so it was not repeated. Installed
version verification remains separate from this transfer/install progress.

### Internal browser: false approval on navigation paths — 2026-10-15

The user's hosting-phone report isolated a 120.02s approval wait on the
Google `forms/post` result, followed by approval_denied with no click.
Source matched `post` inside the path as a publishing verb. Google also
wraps the title in a link-role div inside the real anchor. The helper now
judges that enclosing anchor and excludes URL/path tokens only from the
`post` word check on ordinary HTTP(S) GET links. Buttons, downloads,
non-GET action links, non-web schemes, other sensitive verbs and form
submissions retain approval checks. No timeout or approval answer changed.

The initial path-only fix passed 66 tests but failed the physical result
click; probe versions 7/8 and the captured public DOM were retained. The
final regression includes Google's nested structure. All 67 web tests
(43 live CDP, 7 host, 7 policy, 8 gateway, 2 wait) pass without failures or
skips, including nine refused sensitive controls and a non-web link.
Web lint passes. Full gate with 1151 version overrides passed in 1m20s:
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
:web:lintDebug :app:lintDevDebug --no-daemon --console=plain`. Runtime
preparation: 15 OK. An intermediate build-storage file lock was resolved
by stopping idle Gradle daemons and building sequentially, without manual
cleanup or changes to other worktrees.

Physical repeat, separate non-debuggable probe 9 on Xiaomi Android 15:
all 15 benchmark calls passed with every approval callback set to false.
Google result click: 2179/1477/1126ms, median 1477ms, one redirect each,
no protocol timeouts or approval requests. Direct form open:
1027/791/1281ms. Five-action fill/radio/checkbox plan:
2480/2446/2426ms. No form submission. Logs, failed intermediate runs and
timing/DOM evidence are ignored under `artifacts/browser-approval-20261015/`.
These are module measurements on the test phone, not a live task on the
hosting phone. The original 27.7s event remains unexplained.

Prepared test-signed Dev Release 1151 `0.14.0-dev.browser-approval1` for
the hosting phone. ZIP alignment, v3 signature and the existing certificate
verified. APK SHA-256:
`56c597ceea5c368081253cdc86a1dd5645a7701c35eb76f2e0000c94ae3224eb`.
The packaged page helper matches the tested source byte for byte.
See `docs/testing/internal-browser-performance-20261015.md` for limits.

Delivery: `copy_file` completed to the source chat and then local Downloads.
Opening that exact file reached APK Manager's "Installing app-dev-debug.apk"
screen. Phone tool replies stopped during the update. In the 13:32 follow-up,
live `apps_settings(app_info)` confirmed installed 1151,
`0.14.0-dev.browser-approval1`; browser ready with one page and accessibility
connected. The installed base APK hash was not read. No reinstall was needed.
Probe 9's separate later screenshot check ended FAIL at
Screenshot unavailable after its 15 successful benchmark calls; the probe
was then stopped. No full-probe PASS is claimed.

The user's fresh hosting-phone report at 13:32 says the result click and
form verification took 1.47s backend / 1.55s outer, with no approval wait.
The form was filled, updated, verified and shown without submitting it.
Scrolling commands ran but the reported scroll range was zero: this does
not prove movement. Reported totals: 14.21s backend, 14.97s outer calls,
79.4s wall time. The 64.43s outside calls is not split into model, orchestration
or other time by this report. The 0.76s difference between outer and backend
totals is not a standalone network measurement. The slowest step was Enter:
3.09s including 3.00s settling. Raw hosting-phone traces were not inspected.

### Internal browser: measured waits and DOM plans — 2026-10-15

Added per-call and per-step monotonic timings, exclusive phases, CDP command
counts/errors/timeouts, document events and redirect counts. The returned
compact record is also in the existing tool trace; logcat `MikeBrowserTiming`
has per-command detail in numbered chunks. Timing records exclude URLs,
selectors, field values, page text, headers, bodies and chat/workspace paths.
Plans report action/verification status, keep their no-replay ledger and take
one final snapshot. The browser skill explains that `browser plan` is the DOM
equivalent of the screen's `act_plan`; no duplicate tool or gateway was added.

Two regressions were red before fixing waits: a usable document with an 8s
image took 8535ms to open; a nested read stretched 3s settling to 10050ms.
After: the live Chrome image fixture took 396ms; the virtual-time delayed
read stays within 3050ms. DOM readiness no longer needs every image loaded;
settling/verification include nested cancellable calls, preserve Stop, and
report budget expiry. A physical follow-up also found the keyboard's panned
visual viewport was ignored during hit testing. After correcting that, the
three fills, radio and checkbox plan passed on Xiaomi without sending a form.

Physical module benchmark, separate non-debuggable probe, Xiaomi Android 15,
WebView 154.0.8037.107: three successful repetitions of every performance call
(15/15). Google result click: 1640/1214/1632ms; direct httpbin open:
1256/613/758ms; five-action form plan: 2481/2419/2445ms. Each result click
recorded one redirect; no protocol timeout. One search used its settling
budget and then passed URL verification. Public-page snapshots, geometry,
raw timings and logcat are ignored under `artifacts/browser-performance-20261015/`.

Checks: `:web:testDebugUnitTest :web:lintDebug` PASS, 62 tests (38 live CDP,
7 host, 7 URL policy, 8 gateway, 2 wait), no failures/skips. Full gate
`gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
:voice:lintDebug :web:lintDebug :app:lintDevDebug --no-daemon --console=plain`
BUILD SUCCESSFUL (1m32s), web 62 per variant. Runtime preparation tests: 15 OK;
`git diff --check` clean. Intermediate compile/test errors in the newly added
tests and description were corrected before the final green gate.

Limits: the user's original single 27.7s click on another phone was not
reproduced or diagnosed. These timings exclude model/relay time. This pass
tested the production browser module in a separate foreground probe, not the
full Hey Mike host/approval/handoff UI or the hosting phone. The probe's later
CDP screenshot check was intermittent; its final file says FAIL at Screenshot
unavailable after all benchmark calls completed. No full-probe PASS is claimed.
See `docs/testing/internal-browser-performance-20261015.md` for exact evidence,
test harness limits and reproduction. Earlier network-policy gaps remain open.

Delivery follow-up: built the same app code as test-signed Dev Release 1150
`0.14.0-dev.browser-timings2` (26s); ZIP-aligned, v3 signature and matching
test certificate verified. APK SHA-256
`b90c8b505fe0b6143457181f4bcef9f8dd5974d8c4c954560cb93308d23fb025`.
`copy_file` completed to the hosting phone; the native installer updated in
place. `apps_settings(app_info)` then confirmed 1150, and `device_status`
reported browser ready / accessibility connected. No installed-base hash or
live browser task on that phone was checked after this update. This retained
computer chat still lacks a callable `browser` despite the runtime status.
Artifacts/metadata are in the ignored `delivery-1150/` evidence subfolder.

### Internal browser: physical follow-up and non-debuggable Hey Mike — 2026-10-15

Follow-up on app code `4ea778a`; no product code changes. Xiaomi 23053RN02Y,
Android 15. Two real chats on Dev Debug 1147 retained different pages and
control states: weather at DuckDuckGo stayed Mike's, Example Domain stayed
the user's. Back, chat switch and Open returned the matching page; the same
two CDP target IDs survived. Screenshots and target lists are under ignored
`artifacts/internal-browser-followup-2026-10-15/`.

Built `gradlew.bat :app:assembleDevRelease '-PversionCodeOverride=1148'
'-PversionNameOverride=0.14.0-dev.browser-releasecheck1' --console=plain`:
BUILD SUCCESSFUL (43 s). ZIP-aligned and signed with the same test debug
certificate, APK Signature Scheme v3 verified. This is a test-signed Dev
Release build, not a published release. APK and installed `base.apk` SHA-256
both `85f9304c979eff09e19076d81276762a6ec64a9ebf626966b4a586bb7fd17f78`.
USB `adb install -r` succeeded. Package metadata shows 1148, that version name,
and no DEBUGGABLE flag. Original install time and chat history stayed; no
uninstall or data clear. Accessibility remained bound, no crashed services.

On that installed Release build, a live on-phone Codex 6.1 Sol task called
`device_status`, opened DuckDuckGo with `browser`, and showed it in Hey Mike.
The expanded action card shows Checked the phone / Browser / Browser.
An ADB tap and IME key events put `Release QA` in the page's search field;
the field stayed above the keyboard. This was simulated input, not human
typing. A second Release chat opened Example Domain, leaving DuckDuckGo and
its typed field alive. No private session files were read from Release.

Release handoff with a panel already open: B showed DuckDuckGo, then C's live
`browser show` brought C's Example Domain panel forward. Across 137 CDP
observations over 47 s, the same targets traded on-screen and parked positions
at 1.985 s; C stayed attached afterwards. Native screenshots show B before,
C after, and B reopened afterwards with `Release QA` still present (37, 39,
45). Three requested browser waits were refused with `user_control` before
that show, so this was not a 30-second delay test. No crash or page reset seen.

Release show from the background also passed, while unlocked: after Give back,
the live task waited 10 s then showed the same page. Home was pressed about
0.4 s after Send. Window focus was sampled through 32 s: launcher first,
Hey Mike MainActivity from about 11 s; the page was visible in screenshot 43.
This does not prove locked-screen behavior or long background survival.

Network gap reproduced on Debug 1147 with the unchanged production WebView
policy: direct navigation to an owned loopback DNS alias made zero receiver
connections; a synthetic public HTTPS 302 to the same HTTPS alias made two
TLS ClientHello connections. The receiver ended TLS before HTTP. No private
HTTP request/response or Internet-hosted redirect was proved. The HTTP
variant made zero hits because Android refused cleartext. The fixture used
CDP response fulfillment plus a USB reverse to a PC loopback listener, then
restored Example Domain and removed the reverse. No network device was
probed, and no DNS, certificate trust or app policy was changed. The gap is
confirmed at connection level and remains unfixed; see ARCHITECTURE.

Secure lock testing is pending a human being available to unlock the Xiaomi;
it has a secure keyguard. The phone has not been locked for this test. Close
page, renderer crash, user dropdowns/pickers and pinch zoom remain untested.
The full gate in the next entry covers this unchanged app code; the follow-up
adds physical evidence and a version override only.

### Internal browser: integrated with dev, review fixes, page shown inside Hey Mike — 2026-10-15

Branch `fix/internal-browser-review-20261015`. `claude/internal-web-browser`
(`a1874f2`, unchanged) is merged into dev `9caafd2` as a real merge, so dev's
features, schema 9 and version 1147 stay. No version bump: nothing is released.

Review fixes (`fix(web): close review findings`). Each has a test that failed
on the merge commit and passes now. Red run checked here: the 12 new tests
on the pre-fix code with only the `CdpConnection` test seam added gave
48 tests, 12 failed, and each of the 12 was a new test:
- `show` hid its own page: the posted screen hand-over parked it after it
  appeared. Hand-over is now awaited (`screenInUse` is suspend end to end) and
  a failing member stops the caller.
- A click on a `span` inside a submit button, or on a label, skipped approval.
  Approval is judged on the element that activates.
- `fill(submit)` could send Enter into another form when a frame moved focus.
  Enter now needs the target focused through every frame, before and after
  approval. Otherwise the completed fill is reported (`completedAction`) and no
  Enter is sent.
- A lost reply to a mutating helper call was retried, which ran page handlers
  twice. No retries now; scroll/value/option count as sent first, and a lost
  reply is reported as uncertain.
- A click inside a frame went through an overlay in the parent document. The
  hit test now also runs from the top viewport.
- The same match in two frames took the first. Now `ambiguous_target`.
- An approval stayed valid after the control or form destination changed.
  Now `target_changed`/`focus_changed`.
- A failing main-frame snapshot returned an empty page, and a screenshot could
  show a blocked frame. Both now fail.

Design change (the user's request): the page is part of its chat, inside Hey
Mike, not a floating window. See ARCHITECTURE "Shown inside Hey Mike". The
WebView stays in a parked hidden window while not viewed, so it keeps running
in the background. The chat shows a card. Open moves the same WebView into a
full-screen panel: site, lock or "Not secure", who has the page, Take control
or Give back to Mike, Stop, and Close (asks first). Touches are held back
while Mike has the page. `show` brings Hey Mike forward on that chat, gives
the user control, and says whether the page reached the screen.

Follow-up after the device run (same day): the panel bar text was black on
the dark bar, and the composer's keyboard stayed up over the page; both fixed.
After a source review, also: each page gets its own native frame
(`key(pageKey)`), every attach, detach and control callback carries the key
of the page it was made for, the UI state records which chat its page belongs
to (`browserPageChat`, read through `activeBrowserPage`), and Stop shows
whenever a run is active, also while the user has the page. New JVM tests: a
state test that chat B never shows chat A's page while the collectors catch
up, and a Robolectric host test that a late detach from A's old frame leaves
B's page attached. These are state and host tests; they do not run Compose,
so `AndroidView` reuse across a real chat switch is not exercised by them.
ARCHITECTURE now calls the four-page limit a soft cap (it gives way when
every page is busy, shown or the user's), with no test.

Evidence (Windows, central build storage, `HEYMIKE_TEST_CHROME` = desktop
Chrome), final code: `gradlew.bat test assembleDevRelease
assembleDevDebugAndroidTest :voice:lintDebug :web:lintDebug :app:lintDevDebug
--no-daemon --rerun-tasks`: BUILD SUCCESSFUL (2m 56s), no lint errors. Tests, every module 0 failures: web 54 per variant (35 live CDP, 7 Robolectric host, 7 policy, 5 gateway), core 786, app 165 per variant, remote 149 (3 skipped), workspace 47 (14 skipped). `python -m unittest tools.test_prepare_runtime` 15 OK; `git diff --check` clean.

Device (Xiaomi 23053RN02Y, no SIM, HyperOS). `adb install -r`
in place over 1139: no uninstall, sign-in, chats and data kept; same debug
certificate (SHA-256 `f65c3469…dda1ee`). Final installed `base.apk` SHA-256
`f15a4f2e7a02d599ada7c15f59d58958f1196ce41ed88a3cb810d8cc4467eb60`,
`dev.androidagent.app.dev` 1147 `0.14.0-dev.claude-sync4`. Accessibility
stayed bound after each install. Screenshots, the chat's `session-trace.jsonl`
and APK metadata are kept under the ignored
`artifacts/internal-browser-device-2026-10-15/`. One Claude-subscription run
first failed on an OAuth refresh error (not the browser); the retry worked.
Seen on the phone, each in a screenshot:
- `show` (trace: `browser open` then `browser show`, reply "in front of the
  user, in Hey Mike") opened the page inside Hey Mike's chat with the site
  host, lock and "You have control" (s6, t3).
- Back returned to the chat; the card read "Your turn · example.com"; Open
  reopened the page (s7, s8).
- Give back to Mike locked the page: a swipe did not scroll it. After Take
  control the same swipe scrolled (s9, s10, s11).
- Typing into the page: with the user in control, a tap on DuckDuckGo's search
  field and `adb input text` (IME key events, not a human) put "Hey mike test"
  in the field, which stayed above the keyboard (t4). The user's dropdowns and
  pickers were not tried.
- Stop while the user had the page and `ask_user` was pending: Stop was shown
  (u2); pressing it cancelled the run (trace: `ask_user` `tool_cancelled`) and
  the button went away (u3).
- Agent refused while the user had the page: trace shows `fill` → `error:
  user_control`, then `hide`, then `fill` with submit → ok, title "weather at
  DuckDuckGo". Background: Home was pressed about 2 s after sending; the window
  focus was the launcher when checked right after Home and again about 80 s
  later. The successful `fill` started about 5 s and finished about 10 s after the send. Focus was sampled,
  not watched, so this shows the step ran with the launcher in front, not that
  the page drew continuously. The model's final text said the page was "under
  your control again"; the card said "Mike's browser", which matches the
  trace (no `show` after `hide`). That sentence was the model's error.

That first run did not cover show from another app, two real chats switching
with a panel open, or non-debuggable Hey Mike. The follow-up above covers
those on Release 1148. Still untested: secure lock, Close page, a renderer
crash, the user's dropdowns/pickers and pinch zoom.

Still open, not fixed: a redirect can reach a privately resolving destination
before the read guard runs (the follow-up confirms TLS connections, not a
private HTTP request). DNS rebinding and
websockets are still gaps. `tools/browser-probe` only follows the new `show`
signature and still builds (`-p tools/browser-probe assembleRelease`). It does
not exercise the in-app panel.

### Internal browser: review fixes for targets, the ledger, reconnect and the shown page — 2026-10-15

Review of the internal browser (`:web`, item 20). The original work was
committed as found first (`aa39281`); the fixes are separate. Broken before,
each shown by a live-Chrome test that failed on the original code:

- A click on a control under a cookie banner or dialog pressed the banner and
  reported "click Covered". Now a hit test (`elementFromPoint`) refuses with
  `obscured`, names what covers it with a ref, and presses nothing.
- `fill` ignored whether the field kept the focus, so typed text could land in
  another field. Now it types only into the focused target.
- Plain text shown twice was pressed at its first occurrence. Now
  `ambiguous_target`.
- Enter in a form inside a frame skipped the approval card (the main document
  only saw the frame). Now judged in the field's frame; an unreadable frame asks.
- A child frame, or a page that reached a blocked address by itself, was read
  into snapshots. Now blocked frames are listed but never read or acted in, and
  every read re-checks the page address first.
- An approval still waiting when a plan ran out of time was reported as "may
  have happened"; a lost connection during a read said the same, and a failed
  `snapshot` returned `ok: true`. Now an input counter decides: nothing sent
  means "did not happen, resume with startAt", anything sent means
  `mayHaveActed: true`. Failure replies also keep page notes and say when the
  screen is off or the phone locked.

Also changed (by design, not covered by a failing test): a dropped CDP
connection reattaches to the same WebView by target id instead of replacing
the page; all page endings go through one main-thread `discard`; at most four
pages, closing deleted chats' pages first; a shown page is put away before any
run uses the screen (`DeviceToolGateway.screenInUse`), so taps cannot land in
it; the bar shows the site host and "Not secure" for http; a service-worker
client applies the URL policy; reserved IPv4 ranges and NAT64 are blocked; refs
come from a process-wide `Long` range; plans and steps are validated before a
page opens; the finger `MotionEvent` is touchscreen-sourced. A failed DNS
lookup after a page loaded no longer blanks it.

Evidence (Windows, this worktree, desktop Chrome headless via
`HEYMIKE_TEST_CHROME`): the red run of the new tests on the original code had
8 of 10 fail. Then `:web:testDebugUnitTest` 36 (24 live CDP, 7 policy, 5
gateway), 0 failures; `:core:test` 582; `:app:testDevDebugUnitTest` 92;
`:workspace:testDebugUnitTest` 18 (6 skipped); the full gate `gradlew.bat test
assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug` plus
`:web:lintDebug` and `:app:lintDevDebug`: BUILD SUCCESSFUL, no lint errors;
`python -m unittest tools.test_prepare_runtime` 5 OK; `git diff --check`
clean; `gradlew.bat -p tools/browser-probe assembleRelease` builds.

Not tested: nothing in this change ran on a phone. Not checked: the Android
host (reconnect by target id, `discard`, the page cap, putting a shown page
away, the address bar, the service-worker client), `WebViewInput` taps (the
history says a click once ended in `browser_disconnected` on a locked phone;
the MotionEvent path was never re-run physically), the CSS-to-view scale under
pinch zoom, approvals from a browser step, and two chats browsing at once. The
ref-aliasing test also passed on the original code, so that change is
hardening, not a fixed bug. Server redirects to privately-resolving names,
DNS rebinding and websockets remain gaps (see ARCHITECTURE). The branch is 107
commits behind `dev`; a trial `git merge-tree` shows 8 conflicting files
(coordinator, composite gateway, app wiring, CI, Gradle files, two tests), so
integrating it needs its own pass.
### Claude chat blocked by Mike's own CLI — 2026-10-15

- Claude chats on Pc kept showing "open in another Claude". Mike now launches
  `claude` with files under `.hey-mike/codex-relay/<token>/`, but the session
  readers only took `.hey-mike/claude` as Mike's, so Mike's own CLI counted as
  another client. Both readers now accept either folder.
- The first installed fix, 1156 / reader v4, still blocked the hosting phone:
  the PC reader correctly returned `mike:true, busy:false`, but `RemoteHub`
  required the in-memory Claude engine to hold the session. An APK/engine
  restart loses that map while the retained process remains alive.
- Reader v5 and namespaced Windows Claude launches carry a stable client UUID
  derived from the phone's saved computer ID. Our retained process can be
  reclaimed after restart; another phone's client and external writers still
  block. Refresh never kills a process or sends a turn. Old unmarked relays
  stay blocked until their local engine releases them or their lease ends.
- GitHub also exposed a retained-transport lifecycle race: readers could
  receive the terminal exception before `isAlive` became false. Terminal
  state is now published before waking readers. Its retry-budget test also
  advances an injected clock; the 100 ms bound and assertions remain.
- Real check on Pc against the live session `5a043e5e…`: old reader
  `busy:true, mike:false`, new reader `busy:false, mike:true`.
- `:remote:testDebugUnitTest` passed (150 cases, zero failures/errors, three
  existing skips). `python -m unittest tools.test_claude_sessions` passed 17
  tests on both readers. New fixtures cover old/relayed launch paths, both
  slash styles, similarly named sibling folders, and an external writer
  sharing Mike's session. The regression test fails on the old reader for
  both relay path styles and passes with this fix. New fixtures also cover
  same/other phone identities, legacy paths, and malformed client prefixes.
  A real Windows Node fixture process, with no model/auth requests, was
  classified by the actual CIM reader as `busy:false,mike:true,owned:true`
  for its client, and `busy:true,mike:false,owned:false` for another client.
  The Kotlin gate test has no live engine, matching the restart condition.
  `git diff --check` passed.
- Not verified: the full phone -> SSH flow after the follow-up install, a live Linux host.
  Phone build and delivery use a separate integration branch to retain the
  browser changes already installed on the hosting phone.

### Claude session sync deep review — 2026-10-14

- PR #127 brought onto dev `358d2ef`; the open 2026-10-11 merge was concluded.
  Fixed: Desktop bridge paths in Hebrew (ASCII scripts with Base64 paths and a
  BOM), Windows JSON in the OEM code page (`\uXXXX`), unknown Desktop outcome
  (blocked, never retried on its own), a Codex thread lost after a switch to
  Claude and back (`RemoteBinding.parked`), compaction hiding earlier history
  and showing Claude's summary, a draft lost when sending was held back, text
  shown after Stop imported again, duplicate drawer entries.
- Real store scan, both readers: 114 listed, 113 read, 4,009 messages, 0
  mismatches (old reader 3,168). Real interactive scheduled-task bridge with a
  fixture CLI in a Hebrew/quote/bracket folder passed. Full gate passed:
  2,717 unit executions, 0 failures. Not verified: phone -> SSH -> Desktop ->
  phone, Linux host, Mac. Details: `docs/testing/claude-session-sync-20261010/RESULTS.md`.

### Claude session sync review fixes — 2026-10-11

- Review of PR #127 against the real Pc store found three blockers, fixed here.
  The readers took a session's last `cwd`, so 16 of 92 sessions were filed
  under a worktree or subfolder that `claude --resume` cannot find them from;
  they now take the folder the session started in (0 of 93 differ). A chat
  whose first turn never wrote a transcript was locked for good; a missing
  transcript in a chat Mike started is now "nothing to read". Any reader
  failure blocked sending in every Claude chat on a computer; now only a
  session imported from the computer fails closed, and unreadable history
  text no longer blocks at all.
- Checking for another writer no longer stops Mike's own warm `claude`: the
  readers tell a Mike-launched CLI (`.hey-mike/claude` in its command line)
  from any other client. The reader is uploaded once per app run instead of
  before every call, and a chat open or a send reads text and writer status in
  one round trip. PowerShell listing of the real store: 10.4 s before, 1.6 s
  after. Empty and folderless transcript files are no longer reported.
- UI: the two permanent text buttons above the composer became one quiet
  status line with two icon buttons; the blocking banner names the real cause
  (held by another Claude, or could not be checked); duplicate-ID sessions are
  one muted line in the drawer, not a problem card.
- Evidence: `python -m unittest tools.test_claude_sessions` 14 passed on both
  readers. `gradlew.bat :remote:test :engine-claude:test
  :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --continue`
  BUILD SUCCESSFUL in 1m 23s. Both readers agree on every real session.
- Not tested: the new UI on a device, a live phone -> Desktop -> phone turn,
  two phones on one session, and the Linux reader against a real server. On
  the Xiaomi (cd4928027d76) the pre-fix build installed and launched, but the
  androidTest APK was refused (`INSTALL_FAILED_USER_RESTRICTED`), so the three
  Compose test classes still have not run on a device.

### Claude computer session continuity — 2026-10-11

- Isolated branch based on merged dev `6692067402dba86841ef35c20fdad450166243bf`.
  Native Claude Code / Desktop Code-tab sessions now appear beside Codex in
  computer projects. Imports keep their engine, computer, cwd and native UUID;
  idle refresh appends external text and preserves local history/drafts/queue.
  A persistent native-ID ledger handles repeat sync and interrupted UI writes.
- Imported missing sessions and detected external writers are refused. Active
  runs are not released. Windows Open in Claude Desktop uses the official CLI
  through a one-off interactive task; a staged script avoids cmd length limits.
  A signed-in desktop fixture verified UUID/cwd and task/script cleanup with a
  local CLI stub. No actual Desktop UI or model turn was opened.
- Real Pc store scan: 97 files, 92 listed UUIDs, 91 readable histories / 2,623
  text messages. Both shipped readers agree. Three metadata-less files and
  two duplicate-ID files are omitted with a warning; one raw-NUL corruption
  yields an explicit error. Native files were not edited. Full Windows scan
  86.121 s, maximum read 4.668 s, maximum display payload 110,907 bytes.
- Real-store testing found/fixed foreign ancestor session IDs in forks and
  compaction logical-parent cycles. Compacted history follows the official
  parent chain/summary; already stored local history is retained. The readers
  now agree when a complete record contains invalid raw NUL bytes.
- Final command passed in 55 s: `gradlew.bat test assembleDevRelease
  assembleDevDebugAndroidTest :app:assembleDevDebug :app:lintDevDebug
  :voice:lintDebug --no-daemon`. Used the isolated cached Gradle home and
  `HEYMIKE_TEST_DESKTOP_BRIDGE=true`. XML: 2,353 executions, zero failures/errors,
  16 skips. Python reader/runtime suite: 26 passed (8.166 s). Diff check passed.
- Dev Debug metadata: `dev.androidagent.app.dev`, 28 / `0.14.0`, arm64/x86_64,
  debug-key v2 signature, 16 KiB alignment. SHA-256:
  `c0da717e6c70c8500a431b87877313314daa676e1efdc2fc28ea492f88da8222`.
  Dev Release built unsigned, SHA-256:
  `02575d481a2c99c0fbae9112894ea30043ca6f6835439b704bf8cd083f810ec6`.
  Both APKs contain source-matching PowerShell/Python readers. No version bump,
  production release or installation was performed.
- UI evidence is three compiled Compose instrumentation cases, not a live UI
  pass or screenshot. `adb devices -l` was empty. Native Desktop same-ID model
  continuation, phone→SSH→Desktop→phone, Server/Linux and ownership races remain
  unverified. Writer checks are advisory; legacy text matching is best effort.
  Original dirty checkout preserved. No reviewer or new-feature merge was run.
  [Full evidence and live-check list](docs/testing/claude-session-sync-20261010/RESULTS.md).

### Windows relay takeover of a stale owner — 2026-10-14

- Seen on Pc in the chat logs and relay folders: the phone started a new
  Codex relay at 13:26 while the 13:08 app-server still held the thread, so
  `thread/resume` failed with "already has an active writer". At 13:30 the
  switch to Claude met the 13:25 Claude relay of the same chat, which still
  held its owner lock, and failed with "Could not start Claude's retained
  connection". Both locks were free a few minutes later (offline lease).
- A new relay now ends the earlier relay of the same owner (per chat for
  Claude, per phone-saved computer for Codex), verified by PID and start
  time, and then takes the owner. Metadata records the child's PID and start
  time; `<owner>.relay` records only the metadata path. No token on disk.
- Relays started by earlier builds have no owner record and still hold their
  owner until their lease ends; this applies once, after the update.
- `:remote:testDebugUnitTest` on Windows: new native tests prove a second
  relay of the same owner ends the first (broker and child gone, even with a
  silent attachment) and a different owner leaves it running. The old
  "refuses a second owner" test now asserts the takeover. Five consecutive
  `--rerun-tasks` runs passed (134 tests, 2 existing skips). The two runs
  before them each had one or two timing failures in other relay tests
  (`Relay condition timed out`, a 32 s reattach); two baseline dev runs were
  clean. Treat those relay timing tests as load sensitive.
- Dev Debug 1148 / `0.14.0-dev.relay-owner1` built; the packaged relay files
  contain the change. Not yet installed or tried on the phone against Pc.

### Claude computer tool search — 2026-10-14

- Computer chats no longer force `ENABLE_TOOL_SEARCH=false`; they keep the
  MCP timeouts and use the computer's own settings/default tool search.
  Phone chat settings are unchanged. Launch regression checks passed.
- Controlled Windows CLI 2.1.296 / Opus 5.5 comparison with the same greeting,
  Mike prompt and 36 SDK tool definitions: input fell from 98,411 to 30,077
  tokens (69.4%). ToolSearch found and called a synthetic Mike tool; interrupt
  acknowledged and ended a pending synthetic call with no later dispatch.
- Full test/build/lint gate passed: 2,667 unit executions, no failures/errors,
  32 existing skips. Runtime preparation: 15 Python tests passed.
  Dev Debug 1147 / `0.14.0-dev.tool-search1` built, v2 signature and 16KiB ZIP
  alignment verified. Dev Release is unsigned and was not sent.
- `copy_file` completed. After Wireless ADB connected, the self-update lost
  its tool response. A later independent `apps_settings(app_info)` confirms
  the chat phone is on 1147 / `0.14.0-dev.tool-search1`; no install was replayed
  and no data was cleared. The installed `base.apk` SHA-256 matches the built
  artifact exactly. App chat/tool/Stop and Linux live validation remain open. See
  `docs/testing/claude-tool-search-20261014.md` for evidence and limits.

### Windows relay startup repair — 2026-10-14

- Preserved the exact 1144 reconnect source in a new managed worktree;
  the primary checkout and the original reconnect worktree were left untouched.
- Replaced background `Start-Process` with a hidden native launch that explicitly
  escapes OpenSSH's kill-on-close job and inherits no SSH pipe handles. Startup
  now requires an authenticated health response. Dead/reused broker PIDs fail
  immediately; pending writes retain the safe terminal error. The offline lease
  begins after process startup and endpoint publication.
- The native Windows job regression test reproduces the old launch's abrupt
  termination and proves the product bootstrap survives that same job closure
  and exchanges Unicode input/output. Scoped relay checks: 12 tests passed,
  no failures or skips. Runtime preparation: 15 Python tests passed.
- Final gate passed: `test assembleDevDebug assembleDevRelease
  assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --no-daemon`,
  isolated Gradle home, two workers. 2,663 unit executions, zero failures or
  errors, 32 existing skips. App/voice lint: zero errors/fatals. Packaged helper
  bytes match source in Debug and Release. Debug v2 signature and 16KiB ZIP
  alignment verified; Release is unsigned and is not the delivery artifact.
- Installed 1145 through `copy_file` then `install_apk`; independently verified
  the installed APK SHA-256. Its real new PC chat proved the broker stayed
  alive, but RPC stdin delivery still stalled and the chat returned `Stream
  closed`. This was a failed physical chat, not completion evidence.
- 1146 uses an authenticated SSH `direct-tcpip` tunnel to that same loopback
  broker, bypassing Windows command stdin for both Codex and native Claude.
  A real Windows SSH echo fixture reproduced the stdin stall; the underlying
  Windows pipe cause remains unproven. The tunnel passed 100 round trips.
  Real-SSH unit fixtures cover Unicode exchange, scoped channel closure,
  product endpoint validation and reattachment to the same native broker.
- Repeated final gate passed for 1146 in 2m12s: 2,667 unit executions, zero
  failures/errors, 32 existing skips; app/voice lint had zero errors/fatals.
  Runtime preparation: 15 Python tests passed. Signed Dev Debug metadata,
  v2 signature, same signer, 16KiB ZIP alignment and packaged helper bytes
  verified. An initial short-lease fixture failure exposed startup time being
  counted as offline time; fixed before this successful gate.
- Current artifact: 1146 / `0.14.0-dev.reconnect3`, Dev Debug, 331,619,686
  bytes, SHA-256 `11052cde1eeaf5f9c4d1fa7a98026245c3b340a684f2d3c6bc42d39827bd6a16`.
  Source: `app/build/outputs/apk/dev/debug/app-dev-debug.apk` (ignored).
  `copy_file` completed in 7.718s; `install_apk(replace=true)` updated the
  Nothing A059. Copied and installed `base.apk` SHA-256 both match the artifact.
  Transfer wire bytes were not measured.
- Physical 1146 checks passed: a fresh Codex PC chat ran `pwd` on the Windows
  project and `read_ui` once on the phone; the PC tool receipt and phone gateway
  receipt confirm both. A guarded one-time TCP deletion dropped only that
  test tunnel during a PC command. It reattached in about 1.84s; independent
  process creation times confirmed the same broker and app-server, and the
  prepared command executed once. The output and final answer arrived, then a
  follow-up recalled the original marker and folder on the same Codex thread.
  The old reported chat, selected on its Claude/Sonnet engine, also completed a
  new reply without tools or replaying earlier work. Accessibility was restored
  through Android's settings UI after an idle app restart and verified bound.
  Original screen timeout restored. No sign-in/session data was cleared.
- Remaining gaps: outer SSH-session loss, a physical Wi-Fi/VPN outage, long
  outage/soak, voice recovery, Linux retained-process behavior and a signed
  non-debuggable Release installation are not proved by these checks.
  See `docs/testing/relay-startup-20261014.md`.

### Windows Codex and Claude connection recovery — 2026-10-13

- Dedicated `fix/codex-reconnect-20261013` worktree from dev `5d7f2f38`;
  the dirty primary checkout and existing running processes were preserved.
- Added an authenticated loopback relay retaining the same Windows app-server
  across SSH loss, framed output replay and deduplicated input receipts,
  bounded backoff/lease, scoped reconnect status and locally cancellable Stop.
- Transport trailing commas are accepted narrowly; other malformed JSON
  remains terminal, hides its payload and closes the owned runtime. An old
  reader cannot close a new connection. Legacy busy-writer advice no longer
  claims that reconnect alone fixes it.
- Native Windows Claude chats also keep their process through SSH loss. An
  exclusive per-chat owner prevents a second retained process; each instance
  gets separate launch files. A lost startup receipt never starts another
  helper automatically. Claude's writer is cancellable, Stop wakes it first,
  and invalid protocol frames fail without exposing their payload.
- Scoped core/engine/remote checks and final relay suites passed. Real shipped
  helper tests proved receipt deduplication and lease/Stop behavior. Pinned
  Windows Codex plus real SSH disconnect/reconnect kept the same loaded thread
  and live broker, without a model request or sign-in. Post-Claude scoped checks:
  1,067 executions, zero failures/errors, two existing Linux-only skips.
  Final combined gate passed: 2,659 unit executions, zero failures/errors,
  32 existing skips; Dev Debug/unsigned Dev Release and AndroidTest APKs built.
  App/voice lint had zero errors/fatals; runtime preparation's 15 Python tests
  passed. A final native exit-code confirmation passed in 15s with 1,170 cached
  tasks and four executed tasks after the combined build. Source hashes match
  the frozen manifest, and packaged helper bytes match source in both APKs.
- No phone install, physical network outage, authenticated in-flight task,
  voice transport, Linux retained process, or app-restart recovery evidence.
  No legacy/external writer was killed; no version bump, commit, PR or merge.
  The chat phone still reports 1143 / `0.14.0-dev.delta4`. The phone trace has
  four stream EOFs and a separate 60-second timeout, without the underlying
  closure reason. The reported Claude transcript has a final at 16:21 and
  separate quota rejections at 16:28/16:29. No rendering jobs were stopped.
  APK validation metadata remains 1133 / `0.14.0-dev.subagents5`, below the
  installed phone version. These are build evidence, not an update candidate;
  no downgrade was attempted. Debug signature v2 verified; Release is unsigned.
  Details: `docs/testing/codex-reconnect-20261013.md`.

### Delta transfer gated to dev, skills added - 2026-10-12

- Real phone check on 1143 (`0.14.0-dev.delta4`): a ~332 MB `copy_file` finished with the chat session intact (transfer on its own SSH connection); SHA-256 on phone equals the PC file. 1142 measured the delta at ~436 KB. Wire bytes were not re-measured on 1143.
- Delta is dev-channel only (`BuildConfig.DELTA_TRANSFER`). `assembleProdDebug` APK has no `libmike_delta.so`, no host helper, no delta skill text, and uses plain rename publication; its `assets/file-transfer/licenses` text files remain. `:core:test :workspace:testDebugUnitTest :remote:test :app:testDevDebugUnitTest :app:lintDevDebug :app:lintProdDebug` passed. Not run: full release gate, a prod install on a phone.
- Skills: `.agents/skills/phone-apk-delivery` (developers) and `app/src/dev/assets/agent_stack/skills/files-across-devices` (Mike, dev builds only); `AGENTS.md` points to the first.

### Delta bootstrap installed on the chat phone — 2026-10-12

- The 1134 APK copied successfully, but Android rejected installation because
  the chat phone already had 1137 (`0.14.0-dev.slash1`). No downgrade was used.
- Preserved the newer slash/context/compaction source in the isolated delta
  worktree: 20 source/test files copied byte-identically (normalized line
  endings), other worktrees untouched. Source manifest is outside Git under
  `%TEMP%/hey-mike-apk-delta-20261012/preserved-slash-context-source.json`.
- Repeated the full build/test/lint gate for Dev Debug 1140 /
  `0.14.0-dev.delta2`: passed in 3m30s, 2,609 unit executions, zero
  failures/errors and 16 existing skips. Signature v2, ZIP/ELF 16KiB alignment,
  helper packaging and preservation manifest verified. Artifact:
  `artifacts/hey-mike-dev-1140-delta2.apk` (331,542,025 bytes).
- `copy_file` completed the full bootstrap transfer. `install_apk(replace=true)`
  was interrupted before returning, so its outcome was checked independently.
  On the chat phone, `apps_settings(app_info)` and package metadata report
  1140 / `0.14.0-dev.delta2`. Installed `base.apk` SHA-256 exactly matches:
  `85c5d8e6b24c9f23c3ef28ed5bc5eb4d6494f916ec3cb86f3e4a230461179d3e`.
  Last update: 17:02:53 local; first install remains 2026-09-08 20:12:05.
- This proves the exact APK installed as an update. No physical JNI delta
  reconstruction, Wi-Fi delta benchmark or live feature/UI test is claimed.

### APK delta transfer — 2026-10-12

- Implemented in isolated `feat/apk-delta-transfer-20261012` from dev
  `1b6827ba03ae218fa631e166d32a160b8a8acf5b`; other worktrees untouched.
  PC-to-phone APK copies negotiate verified immutable SHA-256 bases over the
  existing SSH connection, reuse global blobs/patches, and reconstruct exact
  signed APK bytes through bounded cancellable HDiffPatch JNI callbacks.
  Missing bases or unusable deltas fall back to the pinned full snapshot;
  Stop never triggers fallback. Transfers show preparation and verification
  phases and publish verified files atomically. Debug builds seed the PC cache
  and attempt patch precomputation; startup seeds the app's installed APK.
- Final gate passed: `test assembleDevRelease assembleDevDebug
  assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug`, isolated
  Gradle home, native fixture properties, version overrides 1134 /
  `0.14.0-dev.delta1`. 2,545 unit executions, zero failures/errors, 16 existing
  skips; new native and real SSH/SFTP suites had no skips. Python host/runtime:
  24 passed, no skips. `git diff --check` passed.
- Product helper on existing signed 1132→1133 APKs produced a 2,776,115-byte
  patch for a 331,102,984-byte APK (99.16% less transfer data). PC preparation
  was 2.658s cold patch cache / 0.849s warm patch cache, including hashing.
  Actual JNI reconstruction preserved SHA-256; actual generator Stop exited
  the observed child. These results do not measure Android or Wi-Fi speed.
- Final debug APK metadata, v2 signature, 16KiB ZIP alignment and bundled
  native ABIs/helper/licenses verified. PC artifact:
  `artifacts/hey-mike-dev-1134-delta1.apk`, SHA-256
  `7381eea0e0045e30d52bf66b488d1bbe2c6b09b36976c30dade0126761eed89e`.
  No phone transfer/install, Android JNI run, UI screenshot, production
  signing or deployment. Python-less hosts keep legacy full SFTP; Linux
  strict snapshots, split APKs and partial resume remain gaps. Full evidence:
  `docs/testing/apk-delta-transfer-20261012.md`.

### Voice child-update timing and quiet — issue #129, 2026-10-11

- Continued `fix/voice-child-update-timing-129` from merged PR #128 on dev
  `189852e8dcd28fc463d9fd26811b41641c9006da`; other worktrees stay untouched.
- Full child context now arrives during either speaker's turn. A separate
  call-local queue groups complete useful revisions after finalized turns and
  audio quiet. Streaming/activity, bare technical/no-change messages and the
  coordinator's no-final-reply diagnostic do not trigger announcements.
- Pause/Resume child updates and exact English/Hebrew user quiet phrases gate
  automatic speech locally without ending Voice or cancelling child work.
  Normal user replies restore playback even without assistant deltas. Pending
  cards, full results, source ownership, user gates and Stop fences remain.
- Final frozen-source Windows gate passed in 2m13s: `test assembleDevRelease
  assembleDevDebug assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug`
  with `--no-daemon --no-parallel --max-workers=2` and an isolated Gradle home.
  XML: 2,506 executions, zero failures/errors, 16 existing skips; new queue
  suite: 24 passed. Runtime Python: 15 passed. Source SHA-256 manifest checked
  unchanged across the final gate; `git diff --check` passed.
- AndroidTest UI fixture compiled, not run. No phone/emulator test, screenshot,
  install, deployment, version bump or production signing. Real audio buffering,
  microphone thresholds and late response timing remain unverified. Details:
  `docs/testing/voice-child-update-timing-129-20261011.md`.

### Claude upgrade preservation and Haiku integration — 2026-10-11

- Integrated PR #121 and #122 into the ordinary-child/voice branch. The old
  startup installer removed other Claude versions; discovery now keeps them.
  Missing selection metadata reuses authenticated known installed bytes.
  First installation saves its choice; a failed marker write keeps Claude ready.
- A reused 2.1.285 upgrades first to bundled 2.1.293 without depending on
  latest-release discovery. Update download/probe failure and cancellation
  keep the working version. Saved active/previous choices support rollback.
  Model refresh and the newer dev auth-retry/steering fixes both remain.
- Final 1133 (`0.14.0-dev.subagents5`) full gate passed in 2m24s: Debug/Release,
  AndroidTest build, app/voice lint; 2,482 unit executions, zero failures/errors,
  16 skips. New upgrade suite: 18 passed in each variant. Runtime Python:
  15 passed. Manual emulator runner: 34 UI tests passed in 44.236s.
- Xiaomi Redmi 12: backed up private database/preferences and old runtime,
  replaced the app with `install -r`, verified exact installed APK SHA.
  Old 2.1.285 SHA stayed unchanged; startup selected it without another
  Download action, then automatically activated exact verified 2.1.293.
  Haiku 5.5 appeared in the model menu. A real saved `CLAUDE`/`haiku` chat
  called `device_status` successfully and returned `QA_HAIKU_1133_OK`.
  All 102 pre-update chat IDs, 1,930 message IDs and two drafts remain.
  Original QA chat restored; voice/runs idle and accessibility bound.
- Nothing 1132 installation was confirmed through app metadata. Nothing
  is currently absent from computer ADB, so 1133 is not installed there yet.
  No data clearing, uninstall, phone instrumentation or production release.
  Full evidence and remaining gaps: `docs/testing/session-subagents-voice-20261011/CLAUDE-UPGRADE.md`.

### Live child updates and automatic parent reply — 2026-10-11

- Child public text now reaches its parent and ancestors as ordered revisions,
  including commentary between tools and the full result. Active parents get
  context; idle parents wake with their saved model/effort. Stop, startup,
  pending user gates, deletion and unknown delivery outcomes are fenced.
- Voice receives all quoted context and a speakable handoff. Xiaomi QA found
  that disabling the muted WebRTC track delayed speech until unmute. Capture
  now uses ADM zeroed samples while the live track stays enabled; Stop still
  disables it. Real muted voice QA received both progress markers and the final
  result automatically, with no source-user follow-up.
- Final 1132 (`0.14.0-dev.subagents4`) full gate passed in 1m38s, including app
  and voice lint. XML: 2,390 executions, zero failures/errors, 16 skips;
  runtime Python: 15 passed; manual emulator UI runner: 34 passed in 49.136s.
- Exact signed Dev Debug APK installed with `install -r` on Xiaomi Redmi 12 /
  Android 15; installed SHA matches. All 100 original chat IDs, 1,876 message
  IDs and two draft IDs remain. Accessibility stayed bound; no uninstall,
  data clearing or physical instrumentation. Typed QA also proved live progress
  and a new parent turn after the previous parent turn ended.
- Evidence, full SHA, UI fixture and remaining spoken-command, remote-project,
  long-call and production-release gaps:
  `docs/testing/session-subagents-voice-20261011/LIVE-UPDATES.md`.

### Ordinary session subagents and voice — 2026-10-11

- Implemented `session_agents` options/start/list/status/message/cancel/open.
  Children are normal chats with durable parent links, separate engine/model/
  reasoning effort and optional saved computer/project. Voice can browse and
  continue a child while its source call stays active. Source Stop fences its
  subtree, including pending setup; child Stop leaves the source running.
- Clean isolated branch from `origin/dev` at `6692067`; the existing dirty
  checkout was preserved. Database v9 adds receipts, links and model choices.
  No replay after restart or an unknown dispatch outcome.
- Full gate passed: `gradlew.bat test assembleDevRelease assembleDevDebug
  assembleDevDebugAndroidTest :voice:lintDebug :app:lintDevDebug --no-daemon
  --no-parallel --max-workers=2` (2m43s). XML: 2,374 unit executions,
  zero failures/errors, 16 skips. Runtime Python: 15 passed. Manual emulator
  runner: 33 UI tests passed, including Hebrew/large text, child continuation,
  source/child Stop, voice navigation and delayed draft echo regression.
- Xiaomi Redmi 12 / Android 15: backed up the old private data and APK, then
  installed exact Dev Debug 1129 (`0.14.0-dev.subagents1`) with `install -r`.
  SHA-256 `659c5803c3d18682f1911ea601799acf77f82848b4226f62e1fa863db8722f5c`
  matches the installed APK. Debug-key v2 signature and 16 KiB alignment pass.
  The first v6→v9 upgrade preserved all original rows: 96 chats, 1,830 messages
  and two drafts. Final reopen retained child receipts, links and model choices.
- Real phone tasks: Sol 6.1/High and Claude Opus/Medium children replied;
  clicking the card continued the same chat. A live voice source stayed active
  during a child message/reply and Return. Final-build `device_status` passed;
  accessibility was restored through the visible Xiaomi Settings flow and is
  bound, with no crashed service. No uninstall, data clearing or physical-phone
  instrumentation was used.
- A final cancellation regression failed before the fix: a completed child can
  still have an active grandchild. Protocol cancel now stops that subtree while
  preserving the completed child's result. The real coordinator integration
  test also confirms the unrelated root keeps running.
- Remaining gaps: spoken creation/steering has not been verified with a human;
  a standalone recorded-audio fixture install was refused by Xiaomi and was
  not bypassed. A saved PC did not answer over VPN or LAN; remote project/model
  routing is unit-tested, but remote-project E2E remains unverified. One muted
  voice connection reset after about five minutes; long-call stability is open.
  This is a dev test build, not a signed production-release validation.
  Details and UI fixture evidence: `docs/testing/session-subagents-voice-20261011/RESULTS.md`.

### Nothing opening-crash follow-up — 2026-10-09

- Reproduced a source-level upgrade failure using the exact preserved 1113
  schema: both old and new builds used database version 6, but the old one
  lacked `composer_drafts`. Opening a saved chat raised SQLiteException
  `no such table: composer_drafts`. The regression failed before the fix.
- Database v7 now runs the existing additive migration. Two new regression
  checks pass, including preserved chat/thread/engine, messages, queue,
  Mike column/index, exact drafts and a workspace file.
- Full 1128 gate passed (1m26s): unit variants, Dev Debug/Release,
  androidTest compilation and app/voice/a11y/workspace lint. XML: 2,325
  executions, zero failures/errors, 16 skips. Runtime Python: 15 passed.
  Package/version readback, debug-key v2 signature and 16 KiB alignment pass.
  APK SHA-256: `04ca64576fcb19b69971823eaf65a45d2afbe24aafd7f7a5c17c6393ee603515`.
- Nothing A059 / Android 16 USB follow-up confirmed the actual 1127 APK by
  installed version and SHA-256. Its fatal stack is the same missing-table
  exception. A private database/preferences backup was taken before replacing
  it with exact 1128 (`adb install -r`); installed version and SHA-256 match.
- MainActivity launch returned `Status: ok`. The live database is v7, with
  `composer_drafts` and the original Mike index. Binary-safe row comparison
  confirms all 430 chats and 28,583 messages unchanged; no original rows lost.
  A complete 11:37:53–11:47:53 UID/system capture has no new fatal/ANR/native
  signal/OOM signature. This is bounded opening/upgrade evidence, not stress
  or live-model tool coverage. No uninstall or data clearing was used.
- USB disconnected before the additional controlled cold reopen and saved
  history UI check. Dev accessibility is not enabled (the prod service is);
  the selected digital assistant is Dev. Device-tool and power-panel repeats
  need manual Dev accessibility setup and a live connection.
  Details: `docs/testing/assistant-screen-text-20261009/RESULTS.md`.

### Assistant screen preload and text panel — 2026-10-09

- Each invocation freezes permitted Android screen text/screenshot before the
  panel covers the app. A passive device-gateway accessibility snapshot keeps
  visible posts intact. Screen data remains quoted and untrusted; passwords,
  own windows and disabled/protected captures are excluded. Voice audio waits
  for context delivery. The text budget is now 16,000 characters.
- The floating card has an EditText, Send, a scrollable answer and IME insets.
  First insertion/paste ends voice without hiding the panel; typed turns use
  the original text/image and retain the selected chat engine. The exact user
  question is displayed separately from internal context. Open Mike transfers
  the same chat; failed Send keeps the draft.
- Full final gate passed (1m28s): unit variants, Dev Debug/Release, androidTest
  APK compilation, app/voice/a11y lint. XML: 2,321 executions, zero failures or
  errors, 16 skips; Python 23 passed; diff check passed. No instrumentation run.
- Xiaomi Redmi 12 / Android 15 replacement install verified: 1127 /
  `0.14.0-dev.assistant3`, Dev Debug, debug-key v2, 16 KiB alignment. SHA-256:
  `dadea9011bb9bef2469de4d7047c6970b88d05b2be4eea18b2bbc2c885a13a7a`.
  Dev Release built unsigned and was not installed.
- Actual assistant invocation over a synthetic Hebrew Chrome post captured
  385 characters and the original image. Typing ended live voice; Gboard and
  card position checked. Claude/Sonnet answered 10:30 and image-only 731 with
  zero tools, 11.551 s recorded-turn-to-answer. Long answer scrolling and
  Open Mike handoff passed. Earlier Codex/Claude candidates also answered
  directly; 1126 exposed answer clipping, fixed and rechecked in 1127.
- Complete UID/system captures 08:38:53–09:08:53 and 09:13:33–09:17:23 show no
  fatal/ANR/native-signal/OOM signature; final crash buffer empty. Expected
  package-update exits only during installs. Original chat/IME restored,
  accessibility bound, voice/runs idle, own QA server and reverse port removed.
- Physical hardware long press and spoken question remain unverified; current
  voice context is text, whereas typed image understanding is proven. OEM
  privacy negatives, landscape/large-font/cold-start and STARTING cancellation
  need physical coverage. Native panel Markdown styling remains a polish item.
  [Detailed evidence and gaps](docs/testing/assistant-screen-text-20261009/RESULTS.md).
  Isolated branch stacks on PR #125; no strong-model review or merge was run.

### Stress findings fixes and final Xiaomi install — 2026-10-09

- Coalesce in-flight computer thread listings; quiet drawer refresh interval
  15 seconds, atomic per-computer state, cancellation propagated. Claude's
  explicit pre-work transient OAuth error retries exact content once in the
  same CLI/turn; no retry after text/thinking/tools/steering. Stop cancels the
  retry, and steering/write ordering is serialized. Credentials untouched.
- Final full Windows gate passed (1m48s): all unit variants, Dev Debug/Release,
  androidTest APK build, app/voice/a11y lint. XML: 2,289 executions, zero
  failures/errors, 16 skips; Python 23 passed. No instrumentation executed.
- Xiaomi final debug install 1124 / `0.14.0-dev.e2e-fixes4` verified; debug-key
  v2, 16 KiB aligned, SHA
  `e91f8e1c0f894115185eed19b99a4a62fad9bbf753526cda764d438ebe2301f7`.
  Release built unsigned, not installed. Accessibility remained bound.
- New Sonnet/Pc flows on 1123 and final 1124: each 11 calls/results, exact
  29-byte Unicode file round-trip independently checked at both ends, five
  Settings reads including two unchanged replies. Completed 42.040/44.452 s.
  Normal success path proved; real OAuth contention was not forced.
- Initial 100-cycle latency run invalidated (wrong sheet, missing opening
  events); burst preflight stopped before load. Fixed harness resolves fresh
  selectors and checks every drawer state. Separate 1123 run: 20 opens/closes,
  40 accounted draws; opening median 11.828 ms, p95 14.140 ms, max 16.627 ms.
  Android counters 155/4,969 janky frames; strict OEM per-frame timing unavailable.
  No matched speed-improvement claim or new passing 100-cycle burst claim.
- Completed overlapping UID/system logs 07:30:54–08:00:54 and 08:00:47–08:05:25
  Jerusalem; no fatal/ANR/native-signal/OOM signature, only expected update exits.
  117 memory samples; retained PSS remains a finding, not a proven leak/fix.
  Original exact draft restored, probe off, no active runs; collectors finished.
- [Detailed final report](docs/testing/e2e-findings-fixes-20261008/STRESS-FIXES-20261009.md).
  Original dirty checkout/ledger preserved. CapCut repeats, full model benchmark,
  network/voice/concurrency/permission negatives and two-hour soak remain open.
  User performs the strong review; no reviewer or merge was run.

### Xiaomi stress follow-up — 2026-10-09

- Existing debug 1122, accessibility confirmed bound. Completed 100 warmed
  drawer cycles (200 draws), plus two 100-input fast bursts. Probed burst:
  median 11.105 ms, p95 28.558 ms, max 58.961 ms, 100/100 draws; Android
  reported 13.302% janky frames under burst navigation. These are dispatch
  to next draw and Android frame counters, not full task/display latency.
- GPT-6 Luna: six 16-step workflows, 96 completed writes/reads, eight final
  files independently exact. Oversized plan correctly refused before action.
  GPT Settings/own streamed UI and recovered Sonnet Settings: 38 real screen
  reads; stable package/masking results and identical queries unchanged.
- Initial Sonnet failed OAuth refresh before tools; a later attempt recovered
  without credentials changes. One successful Settings sample per engine does
  not establish a general speed ranking. Successful turns: 51 calls/results.
- Complete live logs 05:13:26–05:37:07 Jerusalem (23m41s), no crash/ANR/native
  signal signature; exit history unchanged. 87 memory samples, same main PID.
  Pre/post PSS rose about 17.9 MiB and plateaued during cooldown; leak unproven.
  Original visible/persisted draft restored; accessibility bound, no active runs.
- Report: [stress evidence](docs/testing/e2e-findings-fixes-20261008/STRESS-20261009.md).
  No new product changes, build, install or review. Raw ignored artifacts under
  `artifacts/validation/stress-20261009/`; collectors finished. CapCut loops,
  three-repeat engine comparison, network recovery, voice and two-hour soak
  remain outside this bounded batch. Historical case statuses preserved.

### E2E findings fixes and Xiaomi latency — 2026-10-08

- Isolated from dev `72bbe603`; original dirty checkout and historical QA
  ledger preserved. Workflow text save/reload, exact durable Pc drafts, typed
  Stop trace/prefix evidence, own-UI masking, real video metadata and a shipped
  export-check skill implemented. Debug QA driver/receiver and native fixture
  added; detailed commands and gaps are in
  [RESULTS](docs/testing/e2e-findings-fixes-20261008/RESULTS.md).
- Full `test`, Dev Debug/Release, androidTest APK build, app/voice/a11y lint
  gate passed (3m27s); 2,267 unit executions, zero failures/errors, 16 skips.
  Python QA/runtime checks: 23 passed. No instrumentation run or uninstall.
- Physical Xiaomi Redmi 12 / Android 15: debug 1121 proved Sonnet workflow
  save/describe/run/read, masking, video metadata and exact Pc draft across
  switch/reopen/restart. Debug 1122 installed and verified (test-only debug
  key); SHA `04f9db8c440c962f592352592edff50c6c62a86e4b3fb98f2fa7467d72650dc2`.
  Stop retained its prefix/output, cancelled the wait and never ran the tail;
  independently checked 120 seconds later.
- Warm idle drawer: 20 opens, median 16.544 ms, p95 19.565 ms, max 21.312 ms;
  20 closes median 16.416 ms. These are touch dispatch to next draw, not display
  or task completion. Android reported 111/4,238 janky frames across the reset
  intervals. OEM flag-32 rows were excluded; parsed frame percentiles unavailable.
- Completed live log interval 16:44:53–17:00:51 Jerusalem: no fatal/ANR/native
  signal signature; expected update/force-stop exits. Earlier interrupted
  capture is partial only. No whole-run or two-hour soak claim.
- Remaining: human normal Xiaomi accessibility confirmation after restart;
  live proof of new idle-event policy; native fixture install was rejected by
  Xiaomi security; GPT quota exceeded before tools, so the three-repeat matched
  engine benchmark and new CapCut validation remain blocked/unrun. Historical
  55 NOT_RUN cases are not bulk-marked blocked or passed. User handles strong review.

### Screen-control overlay and Android Settings — 2026-10-08

- Isolated `fix/screen-control-overlay-20261008` from `055bd951`, matching the
  installed persistent-Mike/computer-subagent/Haiku test build. The original
  dirty checkout was left untouched.
- Phone `dumpsys window` confirmed the same live `AndroidAgentControl`
  application overlay had `mForceHideNonSystemOverlayWindow=true`,
  `isOnScreen=false`, `isVisible=false` under Settings' hide-overlay flag;
  Home restored its visibility and returning to Settings hid it again.
- Active controls now use the connected accessibility service's public
  `TYPE_ACCESSIBILITY_OVERLAY` window and service-owned token. Service changes
  rehost an active card; ADB-only use keeps the app-overlay fallback, which
  protected screens can still hide.
- Classify provider and workflow operations by their arguments. Reads and
  workspace/library work do not activate the overlay; screen reads and real
  screen actions do. The first capture attaches before hiding; cancellation
  and failures restore with `NonCancellable`.
- First check passed: `:core:test :device-tools:testDebugUnitTest
  :overlay:testDebugUnitTest --no-daemon --no-parallel --max-workers=2
  --console=plain`. Regression coverage checks screen-read attachment,
  operation routing, background lease release, editor takeover and capture
  failure/Stop restoration. `python -m unittest tools.test_prepare_runtime`
  passed all 15 tests.
- Full Windows/JDK 21 gate passed: `test :app:assembleDevDebug
  :app:assembleDevRelease :app:assembleDevDebugAndroidTest :app:lintDevDebug
  :voice:lintDebug :overlay:lintDebug :a11y:lintDebug
  -PversionCodeOverride=1112 -PversionNameOverride=0.15.0-overlay-fix-test
  --no-daemon --no-parallel --max-workers=2 --console=plain` (3m51s).
  Core/debug XML reports: 1,376 cases, zero failures/errors, eight existing
  platform/remote skips; release and prod-flavor unit variants also passed.
  App/overlay/a11y/voice lint: zero errors, 21/6/4/2 warnings respectively.
  The instrumentation APK was built only; it was not run on the user's phone.
- Final cleanup removes the obsolete takeover latch and removes a stale
  WindowManager registration even if a service-token change detached its view.
  Scoped core/overlay tests, Dev Debug/Release and instrumentation APK builds,
  and app/overlay lint passed again after that cleanup (1m41s).
- Test APK: `dev.androidagent.app.dev`, code 1112,
  `0.15.0-overlay-fix-test`, 331,791,258 bytes; SHA-256
  `330645f4ded8cd35da406904d5e1385d9769a70039a366d0fdab6506c6f9c581`.
  Debug-key v2 signature and 16 KB zip alignment verified. Copy to the phone
  completed. `install_apk` was interrupted before a receipt: installation and
  post-fix Settings visibility are unverified; do not infer them from the build.

- Dev integration starts at `c143425b` (PR #123). Only the overlay fix was
  cherry-picked from `7d0a2aa`; conflict resolution keeps dev's existing
  coordinator and app wiring. Persistent-Mike and Haiku changes from the
  installed-build branch are not part of this integration.
- Validation on the dev integration source passed (2m21s): `:core:test
  :device-tools:testDebugUnitTest :overlay:testDebugUnitTest
  :a11y:testDebugUnitTest :engine-codex:testDebugUnitTest
  :remote:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:lintDevDebug :overlay:lintDebug --offline --no-daemon --no-parallel
  --max-workers=2 --console=plain`. XML: 1,153 cases, zero failures/errors,
  two existing optional remote skips. App/overlay lint have zero errors.
  Python runtime-preparation tests (15) and `git diff --check` passed.
  An isolated Gradle home reused dependency caches and kept this worktree's
  output under ignored `**/build/`, with runtime inputs in `.codex-work/`;
  the shared build cleanup policy was not run. No new phone operation,
  install or runtime verification was performed for the dev integration.
- Phone follow-up, 2026-10-08: `apps_settings(app_info)` read back
  `dev.androidagent.app.dev`, code 1113, `0.15.0-adb-access-preserved-test`.
  Its matching local APK and preserved build source include the service-host
  overlay, argument-aware capability classification and capture restoration;
  DEX inspection confirmed `refreshWindowHost()` and the argument overload of
  `needsControl`. This is build/metadata evidence, not an installed-byte hash.
- With accessibility connected and Wireless ADB off, opened
  `android.settings.SETTINGS` through the phone gateway. `read_ui` verified
  `com.android.settings` in front; the user then confirmed "מופיע" (the card
  appears). Settings visibility is therefore physically verified by the user
  on the installed 1113 build. No ADB operation or reinstall was needed.
  Background-only suppression remains covered by coordinator/gateway unit
  tests; a real manual-exit background task and service rebind remain untested.

### Allow computer ADB for connected-device work — 2026-10-08

- Removed the blanket computer-side ADB ban from `RemoteInstructions`.
  A request to build, install, debug or test on a connected Android device,
  or an explicit ADB request, authorizes ADB for that task without another
  ADB permission question. This also covers the chat's own phone. Select
  the requested target from `adb devices -l`, require state `device`, ask
  if ambiguous, and use `adb -s <serial>` for every device command. Phone
  tools keep their own target; phone Wireless ADB status does not describe
  the computer's USB connection. Existing access, Stop and security rules stay.
- Windows/JDK 21: `:remote:testDebugUnitTest --offline --no-daemon
  --no-parallel --max-workers=2 --console=plain` passed on the final source.
  XML: 104 tests, zero failures/errors, two existing optional Linux/SSH skips.
  New regressions cover Codex/Claude, Windows/Linux and Ask/Full access.
  The stdio integration verifies the new instructions reach a computer
  child thread while its saved access policy stays intact. `git diff --check`
  passed. Generated output: ignored `build/58541f01f3a310d7/` in the primary
  repository. An isolated Gradle home kept the existing build paths and
  leases but disabled cleanup of other buckets; global settings were untouched.
- Not tested: APK build/install, a live model using ADB, physical Xiaomi
  access or the full release gate. No phone or emulator operation was run.
  The updated app must supply the new instructions; editing source does
  not replace the instructions already loaded in an existing chat.

### Direct computer subagents from a source chat — 2026-10-07

- Added `computers(start_task, task_status, cancel_task)`. A direct task
  creates a Codex child session on a saved computer/project without switching
  the source screen. IDs, progress, gates and the final result return to the
  source. `open_chat` still prepares an unsent draft.
- Children use the existing SSH connection, pinned host key, phone-account
  exchange and saved computer access policy. Stable request keys are sealed
  before dispatch. Retry returns the same receipt; restart or an unconfirmed
  remote outcome becomes `unknown`, with no automatic replay. Source/global
  Stop covers setup races and children, including a source in voice.
- Server failures are scoped to the owning phone/computer and engine. Clean
  app-server EOF and manual computer disconnect no longer strand child tasks
  or end unrelated runs. Source replies answer the gate shown there.
- Windows/JDK 21: `:core:test :engine-codex:testDebugUnitTest
  :remote:testDebugUnitTest :app:testDevDebugUnitTest :app:lintDevDebug
  :app:assembleDevDebug --no-daemon --no-parallel --max-workers=2
  --console=plain` passed with normal pinned runtime staging. XML: 698 core,
  48 engine, 102 remote and 142 app tests (990 total, zero failures/errors,
  two existing optional Linux/SSH skips). All eight new stdio integration
  scenarios passed: exact task and policy, single retry dispatch, result,
  approval, cancellation, scoped errors and disconnect in both directions.
  Lint: zero errors, 21 warnings. `python -m unittest
  tools.test_prepare_runtime` passed all 15 tests. `git diff --check` passed.
  After the retry receipt wording was corrected to follow its stored state,
  `:remote:testDebugUnitTest :app:lintDevDebug :app:assembleDevDebug` passed again.
- The final rerun used `--offline`, an isolated Gradle user home and a
  temporary init script with the existing output paths, runtime staging and
  build lease, but read-only storage status. The normal cleanup hook failed
  before tests on a locked lint-cache JAR in another build bucket. That work
  and the global storage configuration were left alone; storage remains above
  its 5 GiB target. No unrelated source changes were included.
- Test APK: Dev Debug, `dev.androidagent.app.dev`, code 28, version 0.14.0,
  ARM64/x86_64, 330,200,642 bytes, verified debug-key v2 signing. SHA-256:
  `0346f4a957ca93ca4bded80a05ce7d4d536539dbbf757086dbd2370fc3b05c76`.
  Outputs remain under ignored `build/6cec6ec9b6827655/` in the primary
  repository, including `app/outputs/apk/dev/debug/app-dev-debug.apk`.
- Not tested: installed phone UI, real saved-computer SSH, a live model task,
  real approval/Stop over that connection, process loss with remote work still
  running, or the full release gate. Integration uses the real routing,
  coordinator and JSON-RPC code with an in-memory stdio peer; it does not prove
  those physical/account paths. No APK was installed or release published.
  After restart, unknown tasks require inspection; they are not reattached.
### Automatic Claude runtime and model catalog updates — 2026-10-07

- Claude catalogs refresh on resume, chat/menu open and each foreground
  minute. Engine caches expire after one minute, invalidate on a runtime
  identity change, retain the last good list offline, and back off for 30
  seconds after failure. Computer catalogs remain scoped to their own CLI.
- After the explicit first installation, the phone checks official Claude
  releases every 15 minutes while Mike is open, with a persisted throttle.
  Binary downloads wait for an unmetered connection. Metadata is bounded,
  HTTPS-only, without redirects, and the exact manifest must pass Anthropic's
  detached GPG signature using its bundled, fingerprint-checked public key.
  Bouncy Castle 1.86 supplies OpenPGP; its upstream MIT notice is retained
  in the repo and APK. Unknown major versions/harness schemas fail closed.
- A candidate is size/hash verified beside the working binary, then checked
  through the real loader with `--version` and a message-free, tool-free
  `initialize` using a separate HOME. Only a compatible build becomes active.
  Previous files remain available to running turns and for restart rollback.
  A runtime change resumes an idle chat on its next turn, without interrupting
  a turn already running. Sign-in and session files are neither read nor moved.
- Windows/JDK 21: `./gradlew.bat :core:test :runtime:testDebugUnitTest
  :engine-claude:testDebugUnitTest :remote:testDebugUnitTest
  :app:testDevDebugUnitTest :app:assembleDevDebug :app:lintDevDebug
  --no-daemon --no-parallel --max-workers=2 --console=plain` passed.
  XML: 680 core, 64 runtime, 79 Claude, 62 remote, 140 app; 1,025 tests,
  zero failures/errors, two existing optional remote skips. New tests cover
  real signed public metadata, tampering, unsafe/malformed/oversized replies,
  protocol rejection, retention, cancellation, persisted selection/throttle,
  rollback, metered downloads, catalog expiry/runtime changes/offline retries,
  and continuing a live turn before resuming on a new process. Python runtime
  preparation tests: 15 passed. App lint: zero errors, 21 existing warnings.
- A one-off Java audit invoked the production `ClaudeReleaseClient` against
  the live official endpoint and authenticated release 2.1.293. The production
  `ClaudeRuntimeProbe` also passed against the real official Windows 2.1.293
  binary in an isolated HOME. No login, account change or model message occurred.
  This proves the Windows probe and live metadata flow, not Android execution.
- Dev Debug APK: `dev.androidagent.app.dev`, code 28, version 0.14.0,
  333,499,367 bytes, existing Android debug-key v2 signing (test-only).
  SHA-256 `92285bf95de805ab0a7ade13bca387196139a854a9faaabcf7d74d7b82917712`.
  The release key and Bouncy Castle license are present in the APK. Logs,
  public downloads, Java audit/config and verification JSON remain ignored
  under `artifacts/claude-auto-update/`; Gradle outputs use the configured
  central `build/0db2d29f53122413/` bucket. The global storage hook automatically
  pruned the earlier completed Haiku build bucket; no source or user data was
  deleted by task commands.
- Not tested: installed Settings/menu text, real ARM64 candidate activation,
  rollback across an Android process death, network transitions on Nothing,
  retained real sign-in with a new binary, a model turn with phone tools, or
  real SSH runtime changes. No phone install or full release gate was run.
  Checks run while Mike is open; immediate closed-app updates and old-version
  disk pruning are not implemented. This change is stacked on the Haiku 5.5
  PR, preserves the original dirty checkout, and changes no release/tag/asset.

### Haiku 5.5 runtime update — 2026-10-07

- Pin the phone's official Claude Code to 2.1.293, the minimum version
  [documented for Haiku 5.5](https://code.claude.com/docs/en/model-config).
  The installer and protocol pins agree. The fallback Haiku name is now
  "Haiku 5.5" and exposes the five effort levels; a CLI catalog still takes
  precedence, including the Haiku 4.5 name from an older computer.
- Downloaded the official `linux-arm64-musl` binary and checked its bytes
  against the [2.1.293 manifest](https://downloads.claude.ai/claude-code-releases/2.1.293/manifest.json):
  244,463,424 bytes, AArch64 ELF, SHA-256
  `00755ae106b6925c1adb2b17e4c6d9b0c41eb4cc55bcad3581345b0aa3176c85`.
  The official Windows binary also matched its manifest hash and size.
- A real Windows 2.1.293 process with isolated config answered `initialize`:
  `haiku` resolves to `claude-haiku-5-5`, the description names Haiku 5.5,
  and `low`, `medium`, `high`, `xhigh`, `max` are supported. No sign-in or
  model message was sent. Only its public model fields were saved in the
  new catalog fixture. The older recorded catalog test still passes.
- Windows/JDK 21: `./gradlew.bat :core:test :runtime:testDebugUnitTest
  :engine-claude:testDebugUnitTest :remote:testDebugUnitTest
  :app:testDevDebugUnitTest :app:assembleDevDebug :app:lintDevDebug
  --no-daemon --no-parallel --max-workers=2 --console=plain` passed.
  XML: 680 core, 42 runtime, 74 engine, 62 remote and 140 app tests;
  998 total, zero failures/errors, two existing remote skips. App lint:
  zero errors, 21 warnings. `python -m unittest tools.test_prepare_runtime`
  passed all 15 tests; `git diff --check` passed.
- APK: Dev Debug, `dev.androidagent.app.dev`, code 28, version 0.14.0,
  329,783,126 bytes, Android debug-key v2 signed (test-only). SHA-256:
  `f2d0915fc421f75b569b2464cd510e8699971ea4fc09f2d5cdb09c0209f99e06`.
  Its DEX contains the new version, download URL, hash and fallback name.
  Binaries, APK and probe/build evidence remain outside tracked files.
- Not tested: phone/emulator installation, Android launch of 2.1.293,
  retained real account sign-in, a Haiku 5.5 task with phone tools, installed
  model-menu UI, real SSH chats, or the full release gate. After an app
  update the new binary must be downloaded in Claude setup. No release
  version, tag or published asset changed. The additional Nothing delivery
  below uses the phone's existing test-code base.

#### Nothing delivery and computer model refresh — 2026-10-07

- The connected phone identified itself as Nothing `A059`. Built the update
  on the already installed computer-subagent/persistent-Mike base `ddf1806`
  with this PR's patch cherry-picked as `055bd95`, preserving those test
  features. No changes were made in the original dirty checkout.
- The same Gradle checks passed with `-PversionCodeOverride=1111` and
  `-PversionNameOverride=0.15.0-haiku55-test`. XML: 728 core, 42 runtime,
  74 Claude engine, 102 remote and 150 app tests; 1,096 total, zero
  failures/errors, two existing remote skips. Lint: zero errors, 21 warnings.
- Delivery APK: `dev.androidagent.app.dev`, Dev Debug, code 1111,
  `0.15.0-haiku55-test`, 330,145,565 bytes, Android debug-key v2 signed.
  SHA-256: `f2ee9526c31ecae01d5577fdeafc84cec0e20cd0089838d6c4a22424f101dc9e`.
  `copy_file` completed the transfer, then `install_apk(replace=true)` was
  dispatched. The source turn was interrupted while installation was
  returning; `apps_settings(app_info)` afterward confirmed code 1111 and
  the exact version name, and `dumpsys activity` showed MainActivity resumed.
  The installation was not replayed. No uninstall, data clear or
  instrumentation was used.
- The user's model-menu capture still showed Haiku 4.5. The phone's new
  local Claude binary had not been downloaded, and Pc's native CLI was
  still 2.1.292. `claude update` succeeded and `claude --version` read back
  2.1.293. The user then reported that the model menu worked. This is user
  confirmation of the computer-model refresh, not a real model-turn trace.
- Own-app UI observation is excluded by Mike's accessibility tools, so
  their black screenshot did not mean the phone was locked. Android's
  keyguard state confirmed it was unlocked. Installed local Claude launch,
  account retention and a Haiku 5.5 phone-tool task remain unverified.

### Shared topic names for Mike chats — 2026-10-06

- New typed chats start with a name from the first real user request. The
  agent can refine it once with `set_chat_title` during the ordinary task.
  Codex stores it with `thread/name/set` on the thread's owning machine;
  trusted runtime input and handoff text do not become the name.
- Manual and finished names are protected by a shared naming lock and a
  durable pending flag (session schema 5). Refinement keeps a different name
  already chosen in Codex. Imported chats read the shared explicit name in
  the drawer and header after refresh, including changes from another Mike.
  Existing conversations are not renamed in bulk.
- Windows/JDK 21: `:core:test :engine-codex:testDebugUnitTest
  :remote:testDebugUnitTest :workspace:testDebugUnitTest
  :app:testDevDebugUnitTest :app:compileDevDebugKotlin :app:lintDevDebug
  -x :app:prepareCodexRuntime --no-daemon --no-parallel --max-workers=2
  --console=plain` passed. XML: 680 core, 46 engine, 62 remote, 28 workspace
  and 140 app tests (956 total, zero failures/errors, eight existing skips).
  All eight real SQLite session-store tests passed; skipped tests cover
  optional Linux/SSH and shell-script fixtures. App lint: zero errors,
  21 warnings. `git diff --check` passed.
- An isolated, anonymous Windows app-server 0.159.2 accepted the name API
  and read back the exact Hebrew name. No model turn was run. A second
  server did not list the zero-turn thread; this does not prove shared
  catalog visibility after a real task. Probe evidence stays outside Git.
- Xiaomi install, 2026-10-06: `:app:assembleDevDebug
  -PversionCodeOverride=1104 -PversionNameOverride=0.15.0-smart-titles-test
  --no-daemon --no-parallel --max-workers=2 --console=plain` passed with
  normal pinned runtime staging. APK: `dev.androidagent.app.dev`, code 1104,
  `0.15.0-smart-titles-test`, ARM64/x86_64, 329,546,253 bytes. Debug v2 signing
  verified and its certificate matched the installed code-1031 app.
  SHA-256: `49da5e4601b783997da28108cbd342e31939bcfda41fd67ccbf85c10d788a2ec`.
- `adb devices -l` and explicit-serial `getprop` identified Xiaomi
  `23053RN02Y`, Android 15, state `device`. The selected serial stays in
  private installation evidence outside Git.
  `adb -s <selected serial> install -r <verified APK>` returned `Success`.
  Package metadata read back code 1104 and the exact version name;
  `am start -W` returned `Status: ok` and MainActivity was resumed.
  The installed screen showed the new-chat UI and signed-in state.
  The crash buffer contained no new entries after installation. This was
  an in-place update; no uninstall, data clear or instrumentation was run.
  APK and private screen evidence remain outside Git.
- Not tested: actual model title choices, two real Mike clients, Codex
  desktop refresh, voice, release APKs or the full release gate. Phone
  evidence proves update/startup and retained sign-in, not a real naming
  task or cross-client synchronization. Claude titles are local only.

### Saved conversation loading — 2026-10-06

- Saved chats now set `isLoadingMessages` before loading history on IO and
  clear it on the first history emission. Skill discovery no longer delays
  history display. Computer imports mark loading before binding and clear the
  marker in `finally`; send and voice start wait for history loading to end.
- On Windows/JDK 21, `:app:testDevDebugUnitTest :app:assembleDevDebug
  :app:lintDevDebug :app:assembleDevDebugAndroidTest --no-daemon
  --max-workers=2 --console=plain` passed. App XML reports: 139 tests, zero
  failures/errors/skips. Lint: zero errors, 20 warnings. `git diff --check`
  passed. Earlier attempts failed from disk space and a concurrent build;
  the sequential rerun passed without deleting user files.
- UI fixtures cover saved-history loading, its transition to messages, and
  computer-import loading after the local history has arrived. Fixture
  onboarding is complete so first-launch UI does not cover the chat.
  Direct `am instrument` on the x86_64 API 35 `HeyMike_Phone_API_35`
  emulator passed both new tests. The three synthetic captures under
  `artifacts/session-loading-20261006/` were visually reviewed; loading text,
  the imported-chat skeleton and the loaded reply all appear correctly.
- Test APK: Dev Debug, `dev.androidagent.app.dev`, code 28, version 0.14.0,
  Android debug-key signed; no release version or published asset changed.
- Not tested: real phone session switching, SSH history import end to end,
  or the full release gate. Emulator fixtures prove presentation only.

### Computer Claude sign-in from the phone — 2026-10-06

- Added `Connect Claude to <computer>` in Computers and a setup link in the
  model menu of a computer chat. Mike runs the computer's official login,
  opens the link on the phone and relays the masked pasted code to stdin.
  Successful login verifies `auth status` and refreshes that computer's models.
  Codes are not saved or logged; Claude credentials stay on the computer.
- Sign-in progress is separate for each computer. Duplicate taps, failed or
  expired logins, cancellation before the link or during code submission,
  and disconnect have cleanup paths. Failed connection checks no longer
  look like a missing Claude installation. Cancellation exceptions propagate;
  a nonzero login exit cannot report an older saved account as success.
- On Windows/JDK 21, `:engine-claude:testDebugUnitTest
  :remote:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon
  --console=plain` passed. XML reports: 73 engine, 61 remote and 139 app tests,
  zero failures/errors, two remote skips. App lint: zero errors, 21 warnings.
  A first rerun hit disk space while stripping native libraries; the same
  build passed once space was available. No user files were deleted.
- A real Windows batch launch of `claude auth login --claudeai` with
  `BROWSER=true` returned an official HTTPS login link without a TTY. The
  probe was stopped before authentication; no account was changed.
- Test APK: Dev Debug, `dev.androidagent.app.dev`, code 1102,
  `0.15.0-claude-login-test`, signed with the existing Android debug key.
  SHA-256 `7950cda4d4498d326c904c28f491f892fcfac2d3c41b9fc5afa5f8bffb01d485`.
  This is a test build, not a release. No release tag or asset was published.
- Pending: physical Compose fixture checks and installed-screen review.
  Real account login completion, a Claude turn after login, Linux SSH login,
  process restart during sign-in, and the full release gate remain untested.

### Real Bluetooth and Wi-Fi connection conditions — 2026-10-05

- Source audit confirmed the previous host supplied only `power` and `screen`.
  Device conditions accepted arbitrary names and silently ignored `is`; a
  `bluetooth_headphones` rule could be saved without any Android source.
  Dry runs previously used an empty signal map unless the caller simulated it.
- Added a closed signal/value vocabulary and strict connection selectors:
  `bluetooth_headphones` and `bluetooth_device` accept `deviceAddress` and
  `profile`; `wifi` accepts exact `ssid` and optional `bssid`. `mode:signals`
  discovers current names/identifiers. Wi-Fi and Bluetooth callbacks refresh
  real snapshots; names, paired lists and broadcast extras never prove identity
  or connection. Unknown state fails closed, including negated conditions.
- Headphones use Android audio endpoints plus device class; watches and speakers
  are excluded. Generic device conditions separately cover connected GATT,
  HFP/A2DP/LE Audio profiles. This is not arbitrary Bluetooth transport support.
  Bluetooth needs Nearby devices permission on Android 12+; Wi-Fi identity
  needs precise Location permission and the Location toggle. Missing signal
  permissions are reported by the tool and rule summary. No scan or grant is
  hidden in discovery. SSID/BSSID are selectors, not network authentication.
- Device selectors survive voice startup and are rechecked before launch,
  context/opening and throughout the call. Protected headphone output selects
  the matching communication route, mutes locally before asynchronous stop on
  loss, and does not replay on reconnection. Local voice errors release ownership
  even when the remote stop fails. Protected voice requires Android 12+; an
  A2DP-only connection without a communication route is refused.
- Windows/JDK 21: `./gradlew.bat :core:test :voice:testDebugUnitTest
  :automations:testDebugUnitTest :device-tools:testDebugUnitTest
  :app:testDevDebugUnitTest :app:lintDevDebug :voice:lintDebug
  -x :app:prepareCodexRuntime --no-daemon --no-parallel --max-workers=2
  --console=plain` passes: 672 core, 11 voice, 104 device-tools and 137 app tests
  (924 total, zero failures/errors/skips). The automations test task compiles the
  Android wiring but has no test sources. App lint: zero errors, 21 warnings;
  voice lint: zero errors, 2 dependency warnings. `git diff --check` passes.
  Logs are ignored under `Temps_and_logs/connections-20261005/`.
- Twenty-five new tests cover invalid signals/typos/selectors, no-write failed
  updates, live-vs-simulated tests, discovery, missing permissions, exact device
  and profile/SSID/BSSID matching, watch exclusion, selected-device loss while
  another stays connected, launch payloads and no replay after reconnection.
- Read-only phone checks show `all-notifications-voice` remains disabled, with
  no experimental condition. Hey Mike Dev is still the previously installed
  `0.15.0-notification-test` (1101); these new connection changes were not built
  into or installed as an APK. No rule was changed or fired during this audit.
- Not tested: real Bluetooth/GATT/Wi-Fi discovery, permission dialogs/revocation,
  background Location redaction, classic/BLE audio, audible disconnect timing,
  two-device handoff, cold startup/Stop, Android 11, Wi-Fi roaming/VPN or the
  full release gate. Android callbacks and buffered audio are not a guarantee
  of instantaneous silence; physical validation remains necessary. Screen-on/
  unlocked attention gating is unchanged. This does not add locked driving mode.

### Notification rules for all apps, with voice context and first speech — 2026-10-05

- A notification trigger can choose `package:"*"` for all apps, while an
  omitted, null or empty package is still refused. The listener admits the
  wildcard only from an enabled rule, before reading notification content;
  own, ongoing and group-summary notifications remain excluded. Named-app
  rules, sender filters, conditions, exports and rate limits keep their scope.
- `voice_call` now carries its bound `opening` and optional `context`, rule ID
  and expiry through Android startup. After the voice connection is ready,
  context is sent as quoted data, then the opening is dispatched through
  `appendSpeech` without microphone input. Later firings use the live call.
  Intent consumption, pending microphone permission and Recents handling
  preserve the startup payload without replaying an ended call.
- Checked on Windows/JDK 21: `./gradlew.bat :core:test
  :voice:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:lintDevDebug :voice:lintDebug -x :app:prepareCodexRuntime --no-daemon
  --no-parallel --max-workers=2 --console=plain` passes (651 core, 7 voice,
  137 app tests; no failures or skips). App lint: 0 errors, 21 warnings;
  voice lint: 0 errors, 2 warnings. `git diff --check` passes.
- The initial Android check staged and verified the pinned runtime, then
  failed during APK packaging because the disk was full. Task-created duplicate
  archives and extracted runtime binaries were removed; retries reused the
  already-staged runtime with `-x :app:prepareCodexRuntime`. The final source
  check rebuilt the dev debug APK successfully.
- Seventeen added regression tests cover wildcard persistence and matching,
  disabled wildcard filtering, sender/condition isolation, context disclosure
  and binding, editable context, payload round trips, exact text preservation,
  context-before-speech dispatch, expiry and failed context injection.
- Not tested: a phone, real first speech or follow-up understanding, background
  activity startup, microphone dialogs/recreation/Recents, Bluetooth, Stop
  during startup, release APKs or the full release gate. No APK was installed.
  The current screen-on/unlocked voice gate remains; this is not locked-screen
  driving support.

### Codex retries stay active and errors keep their chat — 2026-10-05

- A Codex `error` notification with `willRetry=true` updates that turn's
  activity instead of ending the run. Terminal notifications retain their
  thread and turn IDs, so an error in one chat does not end another chat.
- Process stderr goes to the redacted `CodexEngine` logcat diagnostic sink,
  separately from conversation errors. RPC and turn errors retain only their
  own data, cause, additionalDetails and codexErrorInfo. Transport failures
  still affect every turn sharing the broken connection.
- Checked on Windows/JDK 21: `./gradlew.bat :engine-codex:testDebugUnitTest
  :core:test --no-daemon --console=plain` (46 engine, 634 core tests), then
  `./gradlew.bat :remote:testDebugUnitTest :app:testDevDebugUnitTest
  :app:assembleDevDebug :app:lintDevDebug --no-daemon --console=plain`
  (55 remote, 137 app tests; 0 failures, 2 remote skips; app lint 0 errors,
  21 warnings). `git diff --check` is clean.
- Final source check: `./gradlew.bat :engine-codex:testDebugUnitTest
  :app:assembleDevDebug :app:lintDevDebug --no-daemon --console=plain` passes.
- Seven new regression tests cover retry-to-completion, scoped terminal and
  legacy errors, redacted current error details, unrelated stderr kept out of
  RPC/turn/JSON failures, and two parallel chats recovering/failing separately
  while a shared transport failure still ends both.
- Not tested: this fix on a phone, real backend retry/fallback over SSH,
  realtime voice, release builds or the full release gate. No APK was
  installed. The source of the observed image JSON truncation and the cause
  of backend WebSocket closure remain unresolved; this change does not claim
  to fix either or bypass usage limits.

### Media from a computer is loaded when looked at — 2026-10-03

Asked: when Mike shows a picture or video from a computer, do not copy it into
the chat; load it from there when the user looks, and add a download button.

- `show_media` on a computer file now only measures it and stores a
  `RemoteMediaRef` in the message. The tile loads it from the computer when
  looked at (pictures up to 20 MB on appearing, the rest on a tap) into a
  400 MB cache, and a corner button saves it to `Pictures/Hey Mike/` or
  `Movies/Hey Mike/`. Design: "Remote media loads when it is looked at" in
  `docs/ARCHITECTURE.md`.
- Checked on Windows with offline Gradle: `:core:test`, `:app:testDevDebugUnitTest`,
  `:remote:testDebugUnitTest` pass; `:app:assembleDevDebug` builds;
  `:app:lintDevDebug` 0 errors, 14 warnings. New tests cover the reference
  round trip, that a computer file is measured and not downloaded, the loader's
  cache, retry, save and trimming. The full gate was not run.
- On a Nothing Phone (A059), 2026-10-04: the dev debug APK was installed with
  `adb -s <serial> install -r`. `show_media` with a PNG and an MP4 on the
  computer returned both under `notCopied`. Seconds later
  `cache/remote-media/` held the PNG at its exact size on the computer
  (27,899 bytes) and no video, so the picture loaded on its own over SFTP and
  the video waited.
- Not seen on the phone: the tiles themselves (the app's window is left out of
  its own screenshots, and the user did not report on them), the progress
  ring, a video loaded and played by a tap, the Save button and the gallery
  showing the saved file, and a failure with the computer off.
- Not done: a video's thumbnail and length appear only after it is loaded,
  since a frame cannot be read without the bytes. A message made before this
  change keeps its copied files.

### `ask_user` and `show_media` — 2026-10-03

Asked: a way for Mike to ask the user (pick an answer or type one), answered
from a notification when the app is not open; and pictures and videos shown
in the chat, from the phone or from a computer.

- Branch `feat/ask-tool-and-media-ui`, on top of `feat/claude-subscription`
  (PR #99), so both engines get the tools. Design: "The chat's own tools" in
  `docs/ARCHITECTURE.md`.
- Checked on Windows with offline Gradle: `./gradlew.bat test
  assembleDevDebugAndroidTest` passes for every module (`:core` 624 tests,
  `:app` dev debug 130, `:remote` 55), `:app:assembleDevDebug` builds,
  `:app:lintDevDebug` reports 0 errors and 14 warnings (none in the new
  files), and `git diff --check` is clean. The full gate
  (`assembleDevRelease`, `:voice:lintDebug`, `tools.test_prepare_runtime`)
  was not run.
  New tests: the coordinator asks without taking the phone, takes an option
  number, a typed reply, a skip and a timeout, and drops the question on Stop;
  `show_media` leaves out a non-media file and one that cannot be fetched;
  `chatCopy` copies once per call and refuses a path outside the chat; the
  notification rule and the numbered body.
- On a Nothing Phone (A059), 2026-10-03: the dev debug APK was installed
  over 0.14.0 with `adb -s <serial> install -r`; the sign-in and the
  accessibility service survived. In a chat that was already open, on a
  computer (Claude), the runtime context listed `ask_user` and `show_media`.
  `ask_user` with three options was answered from the notification, by the
  user, outside the app. `show_media` showed a PNG and then a 6 second MP4,
  both copied from the computer over SFTP; both calls returned `ok`.
- Not checked: the user did not report back on the video tile and the
  full-screen player. The in-app question card, Skip, a free-text reply, the
  timeout, opening the chat from the notification, a question on a phone with
  notifications blocked, and a Codex chat calling the tools were not run on a
  phone. The Redmi was not used.
- Open: a question in a voice conversation is shown on the voice screen but
  cannot be answered by speaking. A chat thread opened before this build gets
  the tools the way "A resumed thread gets the tools this version has"
  describes; that was not re-checked for these two.

### Usage widget shows the Claude account — 2026-10-01

Asked: the home screen usage widget should show the Claude account too.

- The widget draws the signed-in Claude account as the last orb, marked
  "Claude" with the age of its reading. It is a saved reading: Claude reports
  its quota only while a Claude process runs, and the widget cannot start one.
- `LastUsageStore` also keeps the account's name (`saveAccount`,
  `clearAccount`); the view model saves it from the Claude sign-in status and
  refreshes the widget on a new reading, a new name and a sign-out.
  `readSaved()` gives the widget the reading with its expired windows, which
  it draws as empty like the Codex rows.
- With more than four orbs the Claude row stays and Codex accounts make room.

Checks (Windows 11): `./gradlew.bat :core:test :app:testDevDebugUnitTest
--tests "*UsageWidgetTest" :app:lintDevDebug --no-daemon`: passed (new:
`UsageWidgetTest` 4, `AccountUsageBookTest` +4, `LastUsageStoreTest` +3; lint
0 errors).

Not tested: the widget on a phone, so the real look of the fifth row and its
text width are unchecked. The full gate was not re-run after this change.

### Claude as a full engine: switch mid-chat, one model menu, computers, voice — 2026-10-01

Asked: Claude should be part of the whole system, not a separate kind of
chat. Built on PR #99 after merging `dev` (parallel chats, the model menu)
into it. On a phone only the install and the database upgrade ran so far;
see "On a phone" and "Not tested".

What changed:

- A chat can change engine at any point. It keeps one thread per engine
  (`ChatSession.parked`, `sessions.db` v4) and the engine it moves to is told
  what it missed as text built from the chat's own messages (`EngineSwitch`,
  `ChatHandoff`). The turn names its engine (`QueuedTurn.engine`).
- One model menu: ChatGPT (Codex) models, then Claude models. Picking one of
  the other engine moves the open chat there and adds a note to the chat.
- A chat on a computer can run on that computer's own Claude Code (the
  user's install and sign-in there; no sign-in is copied either way). The
  phone tools travel on the process's own streams (`StdioMcp`), and a tool
  that needs permission asks on the phone (`can_use_tool`).
- Voice in a Claude chat: Claude Code has no speech-to-speech mode (its
  `/voice` is dictation in the interactive terminal, no SSH, no `-p`, no
  Hebrew; https://code.claude.com/docs/en/voice-dictation). So voice stays
  Codex's; a Claude chat talks on its own Codex thread and goes back after.
- `sessions.db` upgrades add a column only when missing. Found while reading
  the merge: `dev` keeps a newer file open by ignoring the downgrade, which
  sets the version back and keeps the columns, and the next upgrade then
  failed on `duplicate column name: engine`.

Checks (Windows 11, this worktree):

- Full gate: `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :app:lintDevDebug --no-daemon --console=plain --continue`:
  BUILD SUCCESSFUL in 1m 39s. 1953 unit tests over debug and release, 0
  failures, 16 skipped (remote 4, workspace 12).
  Lint: 0 errors (app 21 warnings, voice 2). `python -m unittest
  tools.test_prepare_runtime`: 15 OK. `git diff --check`: clean.
- APK: `app-dev-release-unsigned.apk` holds `lib/arm64-v8a/libld_musl.so`
  (723,480 bytes) and no file named `claude`.
- New unit tests: `EngineSwitchTest`, `ChatHandoffTest`, three coordinator
  tests (a turn for the other engine moves the chat and carries the chat; an
  engine that is up to date gets the prompt as typed; a lost thread is given
  the chat again), three `LocalSessionStoreTest` cases (one thread per engine
  across reopen, an empty chat owes nothing, a file an older build opened in
  between), `ClaudeComputerTest` (9), `ClaudeOnComputerTest` (11), and model
  menu, approval card and engine choice cases in `:app`.
- Against the real CLI on this PC (Claude Code 2.1.286, the user's own
  sign-in, two short Haiku turns):
  - `--mcp-config {"mcpServers":{"mike":{"type":"sdk","name":"mike"}}}` with
    `initialize {sdkMcpServers:["mike"]}`: the CLI sent `mcp_message`
    `initialize`, `notifications/initialized`, `tools/list` and `tools/call`;
    `mcp_status` then read `mike: connected, source sdk`. The model called the
    tool and got the answer sent back as `mcp_response`.
  - `--permission-mode acceptEdits --permission-prompt-tool stdio`: `echo hi`
    ran without a prompt; `curl -s https://example.com -o out.html` sent
    `can_use_tool`, and the `deny` answer reached the model as the tool error.
  - With `ENABLE_TOOL_SEARCH=false` the tool was called directly; without it
    the model first used `ToolSearch`.
- Launch scripts against the real CLI, with stdin and stdout as pipes:
  - The `.cmd` shape, in a folder named `פרויקט 100%` under a path with a
    space, under `cmd.exe /c` and under `powershell -c "& '...'"`: `initialize`
    answered in both, and the process ended when stdin closed.
  - Hebrew through that `.cmd` in both directions, with and without
    `chcp 65001`, under both shells: bytes intact.
  - The POSIX shape (`cd -- '…it'\''s פרויקט'`, `export`, `exec`, an empty
    argument) under Git Bash `sh`: `initialize` answered, and it ended when
    stdin closed.

On a phone (Redmi 23053RN02Y, Android 15, serial `cd4928027d76`, dev debug
of `f1a07d0`, run by a Sonnet subagent with the owner's approval):

- The phone had the automated dev build (versionCode 1018, `sessions.db`
  `user_version` 2, no `engine` column). `adb install -r` was refused as a
  downgrade (this build is versionCode 28); `adb install -r -d` succeeded.
  No Xiaomi install dialog appeared.
- First launch: the process stayed up and the log had no `FATAL EXCEPTION`
  or `SQLiteException`. `sessions.db` then read `user_version` 4 with the
  columns `engine`, `parked`, `catch_up` added; 60 chats and 968 messages
  were still there, all `CODEX`. Only schema and counts were read from a
  pulled copy, which was deleted.
- The app opened on the consent screen, as it should for consent version 2,
  with the new sentence about a chat that changes model. The test stopped
  there: accepting it is the owner's to do.
- The accessibility service was already off before the install
  (`enabled_accessibility_services` was `null`) and still is.

Not tested:

- **On a phone, everything after the consent screen.** Switching a real chat
  between Codex and Claude and back, the hand-over text as the models read
  it, the model menu on a screen, and voice in a Claude chat (needs sound).
- **A real computer over SSH.** The Claude probe scripts were not run on a
  computer (the Linux one not at all), the launch was not run through JSch
  and SFTP, and no Claude turn ran from the phone on a PC. The `.sh` script
  was run only under Git Bash, not on Linux.
- A permission prompt answered with Allow on a real CLI (only Deny was), and
  `bypassPermissions` for a full-access computer.
- A queued turn that crosses an engine switch, outside unit tests.

Known gaps, by choice:

- A computer must still be set up through Codex, so with a ChatGPT sign-in,
  before its Claude can be used.
- Claude conversations kept on a computer are not listed or imported.
- The computer's Claude skills are not listed in the composer.
- A Claude session file the CLI itself removed starts again under the same
  id, and the chat's history is not sent again in that case.
- The hand-over carries text only: no tool results, and pictures by path.

### Chats run in parallel; the phone goes to one at a time — 2026-10-01

A new chat opened while another ran showed that run's "Working · Stop active
task" and queued its first message behind it. Now `AgentRuns` runs any number
of chats at once, each with its own `AgentCoordinator`, and `DeviceLease` gives
the phone's screen to one at a time: a run that reads or acts on the screen
keeps it to its end, any other tool call holds it for that call only (a waiting
run says "Waiting for the phone"). Engine events are routed by thread. The chat
screen shows the open chat's run; the drawer marks every running chat. New chat
no longer asks to confirm while a chat runs. See ARCHITECTURE "Parallel chats,
one phone".

Also: a chat nobody wrote in is not shown in history and is deleted on leaving
it or at the next start; the "Where should Mike work?" list folds to the chosen
place, with a chevron for the rest.

Evidence: `AgentRunsTest` (10: a new chat starts while another runs, each chat
gets only its own words, the phone to one chat at a time, a thinking chat leaves
the other's card alone, stopping one leaves the other running and the engine
open, a stray tool call is refused, six chats start at once and a finished
chat's coordinator is reused, a non-screen call does not keep the phone, a chat
that read the screen keeps it to its end, a chat's second turn waits for that
chat), `:core:test` 579, `:app:testDevDebugUnitTest`
92, 0 failures; `:app:assembleDevDebug`, `:app:lintDevDebug`,
`:app:compileDevDebugAndroidTestKotlin`.

Not tested yet: two chats at once on a phone with the real app-server; a
phone-driving chat beside a computer chat; approvals in two chats at once; the
overlay hand-over between two chats on a device; voice beside a running chat
(refused by design). A turn whose start races a stop, with another chat
running, is not interrupted (the engine is not closed); its tool calls are
refused.

### Model menu, and Codex 0.159.2 for GPT-6.1 — 2026-09-30

GPT-6.1 Sol arrives in the model list only with a newer app-server: the list
comes from `model/list` of the pinned Codex, and upstream added it to the
bundled catalog (default model) in rust-v0.159.1. The pin moved 0.156.0 ->
0.159.2 (the latest stable; its only change over 0.159.1 is a Windows console
fix, which matters to the computers we install on). Changed together, because
the phone and the computer must speak one protocol: `tools/prepare_runtime.py`
(ARM64 and x86_64), `LinuxHost.kt`, `WindowsHost.kt` (version and hashes), the
license file name, NOTICE, README. The hashes are the GitHub release digests of
`codex-app-server-package-*`. `prepare_runtime.py` ran clean: layout unchanged,
two code-mode host strings, patch applied. A computer that has 0.156.0 installed
has no 0.159.2 yet, because the install path carries the version: connecting it
from the Computers sheet installs the new one (about 120 MB).

The model choice is a small menu over its chip instead of a bottom sheet:
Intelligence levels (Low ... Max, a check on the one in effect), then a Model
row that opens the list. The chip reads "6 Luna Extra High". Picking a model's
own default level stores Auto again.

Evidence: `python -m unittest tools.test_prepare_runtime`,
`python tools/prepare_runtime.py`, `:remote:testDebugUnitTest
:engine-codex:testDebugUnitTest :app:testDevDebugUnitTest` 172 tests, 0
failures, `:app:assembleDevDebug`, `:app:lintDevDebug` 0 errors,
`:app:assembleDevDebugAndroidTest` compiles.

On a Redmi 23053RN02Y (HyperOS, Android 15; `install -r` with
`versionCodeOverride=1018`, because the phone had the nightly 1017): the model
list shows GPT-6.1 Sol first, then 6 Astra, 6 Sol, 6 Luna, 5.6 Sol, 5.6 Terra,
5.6 Luna and 5.5; 6.1 Sol offers Low to Ultra (Ultra is new) and defaults to
Low. A turn with a picture on 6.1 Sol answered correctly, and the session log
says `"model":"gpt-6.1-sol"`, `"cli_version":"0.159.2"`. Not run: a computer
install of 0.159.2, and `ChatUiTest`.

### The plus attaches a photo, a camera shot or a file, also on a computer — 2026-09-30

The plus was one "Attach file" button into the system file browser, and a
computer chat refused anything but pictures. Now it opens Photo / Camera / File,
and a file attached in a computer chat is copied to the project folder on the
computer (`.hey-mike/attachments/<time>/`) before the turn is queued.

Evidence: `:remote:testDebugUnitTest :app:testDevDebugUnitTest` 127 tests, 0
failures (new: file names safe on Windows and Linux, the path a file lands on,
and `RemoteHub.sendAttachments` against an in-process SFTP server: two files
land whole in one `.hey-mike/attachments/<time>/` folder, no `.part` left);
`:app:assembleDevDebug`; `:app:lintDevDebug` 0 errors.

On the Redmi: the plus opens Photo, Camera and File. Photo opens the system
picker and several can be chosen; the picture is attached and a turn with it
answered. Camera opens the system one-shot camera, the shot returns as
"Photo <date>.jpg" and `cache/captures` is empty afterwards. File opens the
system file picker (nothing was picked). Long press on the answer gives Copy
and Select text. Not run: the activity being recreated while the camera is
open, and an upload from a real computer chat (the Redmi has no saved computer).

### Claude usage before the first message (fix-2) — 2026-09-30

Found on a phone: the usage sheet was empty for Claude until a message was
sent, and Refresh usage did nothing. Unit tests only; not yet on a phone.

- `ClaudeCodeEngine.refreshUsage()` sends `get_usage` (shape from
  `sdk.d.ts` of `@anthropic-ai/claude-agent-sdk` 0.3.285) to a running
  chat, or else to a probe process that gets no message and writes no
  transcript. The reply maps to the same `5-hour` / `weekly` rows; its
  utilization is a percent, not a fraction. A refusal emits nothing.
- The last Claude reading is kept in app-private `claude-usage.json` with
  the time it was seen; the sheet shows "Updated 3h ago" and drops a window
  past its reset time. Sign-out clears it. The refresh button spins for
  Claude too, and the start refresh reads usage and models in one probe.

Gaps: that CLI 2.1.285 on the phone answers `get_usage` and accepts
`--no-session-persistence`, and that the usage endpoint passes the proxy.

Checked on the Galaxy Tab S7 afterwards, with the signed-in official
binary run through the app's loader (`run-as`, a local CONNECT proxy via
`adb reverse`), sending `initialize` then `get_usage` and no message:
the CLI answers `get_usage` with `"subscription_type":"max",
"rate_limits_available":true,"rate_limits":null`. A fresh process has no
limits until its first model request, so a probe can never fill the sheet.
Changed: with no chat running, `refreshUsage()` asks nothing (it only fills
a missing model list); a running chat still answers, and the saved last
reading covers restarts. The empty sheet now says the limits appear after
the first message. The same probe, without `--strict-mcp-config`, retried
`mcp-proxy.anthropic.com` (the account's claude.ai connectors) over 30
times in 20 s against the proxy; the app's own processes pass that flag,
and the host now also sets `ENABLE_CLAUDEAI_MCP_SERVERS=false`.

### Claude tablet findings fixed (fix-1) — 2026-09-30

From real use on a Galaxy Tab S7 (Android 13, Hebrew) with a Claude
subscription. Unit tests only; not yet checked on the tablet.

- Model picker and chip: Claude chats show the CLI's names ("Sonnet 5.5",
  "Opus 5.5", "Fable 5.1", "Haiku 4.5") with its description; a pinned
  alias table covers a missing name. Fable's row says "May use extra
  usage". `--model` still gets the CLI's value. Codex is unchanged.
- Binary check: `claude.verified` next to the binary records version,
  size, mtime and sha256 after a good hash; a start skips the 232 MB hash
  only when all of them still match. A download is always hashed.
- RTL: every Material text style uses `TextDirection.Content`, so English
  reads left to right on a Hebrew phone; the layout stays mirrored.
- Usage rows: the bar fills with the used part (as the ring does), so the
  label now says "31% used" instead of "69% left". Both engines.
- Proxy log: a denial names the requested host and port; the
  `verifyListening()` probe no longer logs `unreadable-head`.
- Stop note: not changed. `AgentCoordinator.stop()` bumps the run epoch,
  so `finalizeRun` never appends "Stopped. Actions already completed were
  not undone." after a user stop, for Codex or Claude. The reply streamed
  so far is kept and marked interrupted; the note only appears when an
  engine ends a turn as interrupted by itself.

Gaps: the tablet check of each item above.

### Claude engine on a real phone, without a sign-in (WP-F) — 2026-09-30

On `wp-f` (from `feat/claude-subscription` at `14e1828`), `dev` debug
0.14.0 (versionCode 28) on the Redmi Note 12 `PVRWC6JJJN8P9LPR` (Android 15,
HyperOS, arm64, Wi-Fi, no SIM). Built with `./gradlew.bat
:app:assembleDevDebug :app:assembleDevDebugAndroidTest`, installed with
`adb -s PVRWC6JJJN8P9LPR install -r -t` (the Xiaomi USB-install dialog
tapped; it only appears once the keyguard is dismissed with `wm
dismiss-keyguard`). The UI was driven with `adb input` and read with
`uiautomator dump`. Screenshots stay off the repo.

| # | Check | Result |
|---|---|---|
| 1 | Onboarding and Settings > Accounts: "ChatGPT (Codex)" / "Claude subscription" choice, the Claude card with the required notice, 232 MB size, confirm dialog, Anthropic privacy link | Pass |
| 2 | Download from the card: cancel at 46.7 MB deletes `claude.part` and the card shows Download again; a full download plus hash took 7.25 s (232 MB, about 32 MB/s); `run-as … sha256sum` on the phone gives `31efc413…cd62ee8` (the pin) | Pass |
| 3 | Force-stop and reopen: "Claude Code is ready" within 1 s, same file (inode, 16:22 mtime), no `claude.part`, nothing downloaded | Pass |
| 4 | "Sign in to Claude": `claude auth login` gave a link and Chrome opened `claude.ai/login?…returnTo=/oauth/authorize…redirect_uri=https://platform.claude.com/oauth/code/callback`. Back to the app: the card shows the masked code field, "Finish sign-in", "Open the sign-in page again". Not signed in further | Pass |
| 5 | Unsigned Claude chat, one message: "The task could not finish. Sign in to Claude in Settings first." within 2 s, send button back, no overlay | Pass |
| 6 | From the app process (`ClaudeEngineDeviceTest`, real `ClaudeCodeEngine` + `AndroidClaudeHost` + `LoopbackMcpServer`, no sign-in): `system/init` has `claude_code_version` 2.1.285, `mcp_servers` mike `connected`, 32 `mcp__mike__*` tools (all 32 phone tool definitions), built-ins `Edit, Glob, Read, Skill, Write`, `permissionMode` dontAsk, `apiKeySource` none, `model` claude-sonnet-5-5. The turn ends `failed` in 1.1–1.2 s with "Not logged in · Please run /login" | Pass |
| 7 | Proxy during 4–6: no CONNECT at all (unsigned `claude` makes no network request), so nothing was denied. Every `claude` launch logs one `denied unknown: unreadable-head`: that is the proxy's own `verifyListening()` probe, not traffic | Pass, log noise |
| 8 | Stop: the UI cannot reach a starting Claude turn without a sign-in (the coordinator refuses first, item 5). At engine level a stop right after sending took 3.0 s and ended `interrupted` via the kill fallback; the CLI never answers an interrupt that arrives while the message is still queued. The next message then failed with "Claude is not running for this chat." (the killed process still read as alive): fixed in `938622e`, after which it gets its answer in 0.4 s | Fixed |
| 9 | Codex: a new chat can switch to "ChatGPT (Codex)"; an unsigned Codex message says "Sign in to Codex in Settings first."; the Codex account block (Sign in to Codex, Log in, Refresh) is unchanged, now under its own "ChatGPT (Codex)" heading | Pass |
| 10 | Claude chat: header "Claude subscription", no mic (send button only); a Codex chat shows "Start voice conversation" | Pass |

Commands for 6 and 8: `adb -s PVRWC6JJJN8P9LPR shell am instrument -w -e
class dev.androidagent.app.ClaudeEngineDeviceTest
dev.androidagent.app.dev.test/androidx.test.runner.AndroidJUnitRunner`: OK
(2 tests). `./gradlew.bat :engine-claude:testDebugUnitTest`: 50 tests, 0
failures (new: `aTurnRightAfterAKilledStopStartsANewProcess`, which failed
before the fix). To get past onboarding without an account for 5, 9 and 10,
`onboardingFinished` in the app's `ui` preferences was set to true and set
back to false afterwards. The androidTest package was uninstalled; the app
stays installed with the verified binary.

Found, not fixed: the proxy's denial log never names the host
(`LocalhostConnectProxy` passes `""` to `onDenied` for every reason), so a
host missing from the allowlist during a real sign-in shows only as
`denied unknown: host-not-allowed`; the liveness probe adds a false denial
per launch. With the phone in Hebrew the chrome is mirrored and English
sentences show their end punctuation on the wrong side ("?Which account
should I use").

Needs the user's sign-in (not done): the code paste and `claude auth
status` after it, a real reply, a `mcp__mike__*` tool call round trip,
steer `priority:"next"`, `/compact`, the `5-hour` / `weekly` usage
windows, stop during a tool call, `Grep`/`Bash`, hosts the real sign-in
and chat reach (watch `adb logcat -s AndroidClaudeHost`), Android 16.

### Claude subscription chats wired into the app (WP-E) — 2026-09-30

On `wp-e` (from `feat/claude-subscription`, WP-A..D merged), the Claude engine
is now reachable from the app. Decisions are in `docs/ARCHITECTURE.md`,
"Claude subscription chats: one engine per chat".

- `AgentGraph` builds `AndroidClaudeHost` and `ClaudeCodeEngine` with a
  `LoopbackMcpServer` adapter, passes the Claude home to
  `installDefaultSkills`, and re-checks an already downloaded binary at start
  (hash only, never a download).
- `RoutingAgentEngine` routes by the chat's engine, remembers Claude threads,
  finds them again through their chat after a restart, tags Claude request
  ids `claude|`, and refuses voice in Claude chats. The coordinator checks the
  chat's own engine and says "Starting Claude", "Sign in to Claude in Settings
  first.", "Claude could not finish".
- The view model keeps model, effort and quota per engine; the chips,
  `/status` and the usage sheet follow the open chat. The widget stays Codex.
- Onboarding and Settings > Accounts: "ChatGPT (Codex)" / "Claude
  subscription" choice (the default for new chats), and the Claude card: size
  first (232 MB) and a confirm, progress, cancel, errors, "Not available on
  this device", sign-in page opened with `ACTION_VIEW`, a masked paste field
  cleared on submit, sign-out, the required notice, Anthropic's privacy link.
  An empty phone chat can switch engine; the top bar names the account.
- No mic in Claude chats; the assistant press and voice start use a Codex
  chat. `/compact` on a Claude chat opens it first and notes before waiting.
- The consent text names Anthropic, so `CONSENT_VERSION` is 2. A 300-minute
  quota window now reads "5-hour" instead of "Daily" (Codex too).
- CI also runs `:engine-claude`, `:mcp-loopback`, `:runtime` and `:workspace`
  unit tests.

Checked on 2026-09-30 on Windows, JDK 17 (Android Studio jbr):
`./gradlew.bat test assembleDevDebug assembleDevRelease
assembleDevDebugAndroidTest :app:lintDevDebug` BUILD SUCCESSFUL (1761 unit
tests, 0 failures, 20 skipped; new: 4 coordinator, 5 router, 12
`EngineChoicesTest`, 7 `ClaudeSetupTest`, 1 `UsageSummaryTest`); lint 0
errors, 21 warnings, none in touched files; `python -m unittest
tools.test_prepare_runtime` 15 tests OK; `git diff --check` clean. The dev
debug APK carries `lib/arm64-v8a/libld_musl.so` and `musl-COPYRIGHT` and no
`claude` binary.

Not tested: anything on a phone (WP-F): the real download and hash check,
the sign-in link and a real subscription sign-in with a pasted code, a Claude
chat turn, MCP tools reaching `claude`, stop during a tool call, compact after
a restart, the Compose screens themselves (no screenshot exists), Android 16,
and TalkBack on the new controls.

### Wireless ADB: the real state, a dropped pairing, a one-tap switch — 2026-09-30

Reported: Mike showed Wireless ADB as off while the switch was on, and turning
it on meant leaving the app. On the Nothing A059 (Android 16): `adb_wifi_enabled=1`,
adbd on 42121, Mike's saved port 33453 (stale), no loopback connection, and
`dumpsys adb` listed only two trusted keys, not Mike's. adbd logged
`CERTIFICATE_VERIFY_FAILED` in bursts of five (Kadb's own retries) every ~13s
from uid 10318, the dev build: Android had dropped the pairing, most likely
after 7 days without a connection. Fixed in `:adb`, `:core` and the UI:

- `AdbStatus` carries the switch, the pairing and a refused key; the status
  sheet and checklist say which is missing. A refused key stops the retries.
- `discover()` no longer overwrites CONNECTED (opening the Wireless ADB page
  used to drop and remake a live connection).
- A settings observer drops a connection when the switch goes off.
- "Turn on" writes the switch directly, after Mike grants itself
  WRITE_SECURE_SETTINGS over its own ADB on first connect.

Evidence: `:core:test :adb:testDebugUnitTest :app:testDevDebugUnitTest` 672
tests, 0 failures; `:app:assembleDevDebug`; `:app:lintDevDebug` 0 errors. On
the Nothing, logcat showed `SSLProtocolException ... SSLV3_ALERT_CERTIFICATE_UNKNOWN`
recognised and a single burst of handshakes, then none.

On the Redmi (paired and connected): the status sheet read "Connected · port
46333"; `WRITE_SECURE_SETTINGS` was `granted=true`, so Mike had granted itself
over its own ADB. With `settings put global adb_wifi_enabled 0` the row became
"Wireless debugging is off" with a Turn on button within three seconds and the
orb went amber; tapping Turn on set the switch back to 1, the row showed "Looking
for this phone…" and then "Connected · port 39617", the sheet staying open
throughout. Not verified: pairing again after a dropped pairing (the Redmi's
pairing was still good), and the same flow on the Nothing.

### Computer chat panel: activity as one row, a plainer header — 2026-09-29

A UX review of a screenshot of a computer chat (Mike on the phone, Codex on a
PC over SSH) became fixes on top of `fix/remote-account-model-activity`
(`bfe4dd9`, not yet merged: this branch needs it first). In that screenshot
seven activity rows, all reading "Command execution" or "Thinking", took 46% of
the screen before the answer began.

- A computer's back-to-back activity is now one `RemoteActivityRow`: "Working
  on Server" while live, "Worked on Server · 4 commands" after, with "N
  failed" in the error color. Steps say what they did ("Ran ls -la", "Thought",
  the shell Codex wraps a command in taken off), show a spinner or a failure
  icon, and a step left streaming by a run that ended reads as stopped. The
  phone's group stays separate. Thinking has its own icon instead of Skills'
  sparkle. Expand arrows take their label's color.
- The top bar reads "Server · folder · This phone", each with its icon, and
  drops the folder when it is the chat's own name; `remoteChats` is gone.
- Copying is a long press on a block (a user's prompt, or the agent's whole
  reply after it) with Copy and Select text; there is no copy button, and
  the "Working" line sits under the chat's last line while it runs. A Hebrew or Arabic word is joined to the number after it with a
  no-break space; "Jump to latest" shows only 96dp or more from the end, in
  the corner on that side. Composer and chip outlines are at least 3:1.

Withdrawn after reading the code, not fixed because not broken: the composer
already switches between voice, send, steer and stop; the ring around the
sphere is the quota meter; title, answer and hint are all 17sp (a height
measured from the screenshot had included the caret); the white strip under
the composer is very likely the keyboard's, since `MainActivity` already draws
edge-to-edge with a black navigation bar. Left open: English-only strings and
no RTL mirroring of the chrome (there is no string resource file), the model
chip's wording, the accent palette, per-step durations.

Checked on 2026-09-29 on Windows: `:app:testDevDebugUnitTest` (85 tests, 17
new, 0 failures), `:app:assembleDevDebug`, `:app:lintDevDebug` (0 errors, 22
warnings, none new) and `git diff --check` pass.

Not tested: any of this on a phone, so no screenshot of the new panel exists;
a live run on a paired computer (the command-unwrapping pattern is only
tested on hand-written strings, not on real Codex output); TalkBack; the jump
button's corner and the header on a real Hebrew chat.

### Computer chats use Mike's account, model and live activity — 2026-09-29

Remote app-servers now receive the active ChatGPT access token from Mike on
the phone before any remote Codex call. The phone's refresh token stays on the
phone; a remote refresh request asks the phone to refresh its own account.
Remote calls fail if Mike has no usable ChatGPT sign-in. The selected model is
sent when opening a remote thread and again at each turn. Remote item events
now appear as expandable rows in the chat, including thinking, commands, file
changes and tools, with a running or failed state. The computer setup no
longer asks for a separate Codex device-code sign-in.

Windows checks passed: `:engine-codex:testDebugUnitTest`
(42 tests), `:remote:testDebugUnitTest` (36 tests, 2 Linux-only skipped),
`:core:test` (568 tests), `:app:testDevDebugUnitTest` (68 tests),
`:app:assembleDevDebug`, `:app:lintDevDebug`, and `git diff --check`.
The DevDebug APK (0.14.0, code 28) installed with `adb -s <Nothing A059>
install -r --user 0`, and `MainActivity` launch returned successfully. The
wireless ADB connection then dropped before inspecting the screen or sending
a remote turn.

Not verified: a live turn on a paired computer, which account the backend
billed, model selection on that computer, token refresh after expiry, or live
activity rows on the phone. The external-token login is an experimental
Codex 0.156.0 app-server API, so compatibility with later versions needs a
fresh check before changing the pinned runtime.

### Assistant panel over the current app — 2026-09-28

Holding the power button now opens Mike over the current app, instead of
switching to Mike. An edge glow sweeps in, a card rises from the bottom, and
live voice starts with the screen's text already given to the model. See
`docs/ARCHITECTURE.md`, "Assistant panel over the current app". Voice
ownership moved from `AgentViewModel` to the app-scoped `VoiceConversation`.

Checked on 2026-09-28 on Windows: `:app:testDevDebugUnitTest` (with the new
`ScreenTextTest`, 7 tests), `:voice:testDebugUnitTest`, `:core:test`,
`:app:assembleDevDebug` and `:app:lintDevDebug` (0 errors, 22 warnings) pass.
The dev debug APK (versionCode 28) was installed with `install -r` on the
Nothing A059, where Mike was already the digital assistant. The user held the
power button there and approved the result. The log shows the
`VoiceInteractionSession` window, no crash and no start failure.

Not tested: the Redmi (installed, but Google is still its assistant); a
device-control task started from the panel, so whether Mike's taps pass the
faded card; an approval asked from the panel; the path without "Use text from
screen"; "Open Mike" handoff; the full release gate.

### Files across devices: places, `copy_file`, a skill — 2026-09-28

One tool copies files between places: `copy_file(from, to, replace)` with
`chat:`, `phone:` (or a `content://` uri) and a computer's name, and a bare
path where the chat's shell runs. It replaces `push_file`, `pull_file`,
`copy_to_phone` and the computer-chat rewriting of `push_file`.
`install_apk(file)` takes a phone address and refuses a computer one with
the copy to make first. Use cases (install an APK built on a computer, share
a computer file, a phone photo to a computer) are recipes in the new
`files-across-devices` skill; a computer chat's instructions carry the same
recipes because Codex there reads the computer's skills. Every copy shows
the progress banner; SFTP uploads create missing folders, are written as a
part file and renamed.

Windows checks passed: `.\gradlew.bat :core:test :device-tools:test
:workspace:test :remote:testDebugUnitTest :engine-codex:test
:app:testDevDebugUnitTest :app:lintDevDebug :app:assembleDevDebug
:app:assembleDevDebugAndroidTest --no-daemon` (core 568, device-tools 104,
remote 34 with 2 skipped, workspace 18 with 6 skipped, engine-codex 41, app
58; no failures), `python -m unittest tools.test_prepare_runtime`,
`git diff --check`. New `CopyFileGatewayTest` (addresses, bare paths in
phone and computer chats, no overwrite, no escape from the chat folder,
install refuses a computer file, Stop) and `ComputerPlaceTest` (both ways
against a MINA SFTP server, whole files, no part file left, no overwrite).

Not verified: anything on a phone. `PhoneStoragePlace` (MediaStore insert
and reads, the ADB fallback), the banner for the new copies, a real PC copy
and the skill's recipes in a live chat are untested. `install_apk` still
needs Wireless ADB. An SFTP upload stopped mid-way may leave a hidden
`.<name>.part` on the computer.

### Chat library restyle, on the Nothing A059 — 2026-09-28

Build of `ad465e4` (local versionCode 1024) installed with `install -r` on the
Nothing A059; accessibility stayed enabled. With two computers saved (Pc on
Windows, Server on Ubuntu): device chips showed All devices / This phone /
Pc / Server with status dots; Projects listed projects from both computers;
the Pc chip showed "Connected over VPN" and its projects from Codex on the
PC. A new chat in the Android-agent-use project ran on the PC and answered
"What git branch is this folder on?" with the branch the main checkout is
on. On the Xiaomi Redmi 12 (phone chats only) the device row and the "This
phone" labels are hidden. Not checked: long-press rename/delete on a device,
the instrumented `ChatLibraryUiTest` (not run: installing the test APK
turns off accessibility).

## Chat library UI — 2026-09-28

Replaced the nested computer/project/chat tree with recent chats, a device
filter, search and a separate Projects tab. A project opens its own chat list;
Back restores the project search and scroll position. One New chat action uses
that project or offers a folder on the selected computer. Automations, Files
and Settings have compact footer entries. Large text puts New chat on its own
row; filters scroll on short screens.

Windows checks passed:

- `.\gradlew.bat :app:testDevDebugUnitTest :app:assembleDevDebug
  :app:assembleDevDebugAndroidTest :app:lintDevDebug
  -PversionCodeOverride=1023 --no-daemon`: 58 app tests passed, build passed,
  lint had 0 errors. `git diff --check` passed.
- On an API 35 x86_64 emulator, installed both APKs with `install -r`, then
  ran `ChatLibraryUiTest` directly with `am instrument`: 4 tests passed.
  Fixtures use 257 phone chats, 41 PC projects and one server project. They
  cover device/project routing, search, Back and navigation while scrolling.
- The responsive test passed again at font scale 2.0, and in landscape.
  Inspected the captured recent, project, project-chat, large-text and short
  screen PNGs under ignored `app/build/ui-review/`. These are synthetic UI
  fixtures, not evidence of live Codex, SSH, voice or a physical phone run.

APK: `dev.androidagent.app.dev`, DevDebug, 0.14.0, code 1023, debug signed (v2).
Alignment and signature verification passed. SHA-256:
`95a30bd94accaa60c51a7242f056e037230d68ec2a473dc90ab21fe9fef05721`.
Installed this exact APK on Nothing A059 with
`adb -s <Nothing target> install -r --user 0`. Installation returned Success;
the installed version is 0.14.0, code 1023. Its signer matched the previous
APK. The original first install time and CE/DE data directory identifiers
remained unchanged. MainActivity was resumed and the app process was running
after launch. This proves the update and launch only; the new drawer was not
inspected on the phone while it was in other active use. The full release
gate was not run.

Remote Codex still uses the computer's sign-in. The pinned 0.156.0 protocol
supports experimental externally managed ChatGPT access tokens, including a
refresh callback. A per-computer choice to use Mike's active account needs
that token lifecycle and has not been implemented in this UI change.

## PR #89 review fixes — 2026-09-27

Fixed SSH key trust on reconnect, permission approval replies, Stop during
SFTP copies, and imported chat ownership on resume. Also fixed a missing test
brace and added `engine-codex` and `remote` tests to Android CI. The approval
card now shows the requested network/file access and its turn scope.

Local checks passed on Windows:

- `:remote:testDebugUnitTest :engine-codex:testDebugUnitTest
  :app:testDevDebugUnitTest` and `:core:test :device-tools:testDebugUnitTest`:
  792 tests passed, 2 skipped. The skips need Linux (a shell script and the
  staged Linux app-server); neither was run on Windows.
- `:app:assembleDevDebug :app:lintDevDebug --no-daemon`: build passed;
  lint reported 0 errors. `git diff --check` passed.
- An in-process SSH server proved that a reused link rejects a changed key
  before password authentication. SFTP fixtures stalled both read and write
  replies: cancellation released the client within 3 seconds, and a later
  transfer reused the same SSH session.

Built again with `-PversionCodeOverride=1022` and installed with
`adb -s <Nothing target> install -r --user 0` on Nothing A059. The APK reports
`dev.androidagent.app.dev`, DevDebug, 0.14.0, code 1022. Alignment and signature
checks passed; its debug signer matched the installed APK before replacement.
Installation succeeded, the original first install time remained unchanged,
and MainActivity was resumed with the app process running. This proves the
update and launch only, not the remote flows. APK SHA-256:
`5f50f6c4cbe04b5c23a53cbe4fd3e139ffde7725dd0f812d7e9944d16d9a2cc3`.

Android CI passed for code commit `f0cb483` on both push and pull request runs,
including the newly added engine and remote tests.

The Nothing's computer screen reported that neither saved address answered.
The addresses matched this PC, but its Windows `sshd` service was stopped and
port 22 had no listener. With user approval, the service was started; it now
listens on port 22. A direct probe from the Nothing received the Windows SSH
banner over the VPN. The home address still timed out. This proves network
reachability over the VPN, not password login or Codex setup. The in-app retry
is pending while the phone is in an active voice conversation.

Still open: in-app computer login, real phone-to-PC SSH reconnect, Allow/Deny,
Stop during file transfer, and the desktop-lock copy offer after resuming an
imported chat. The expanded approval card has no physical UI proof yet. The
full release gate was not run for these fixes; this APK is a debug test build.

## Dev release preparation — 2026-09-26

The next planned version is 0.14.0 (base versionCode 28). The physical QA
evidence below is from the earlier 0.13.0 Dev Release candidate; the 0.14.0
candidate has not been built or run on a phone.

The preparation branch merges the v0.13.0 `main` history into current `dev`
without changing `main`. The stable updater now requires matching `Package:`
release metadata and verifies the downloaded APK package, newer versionCode,
and installed signing certificate before installation. Direct
`shell uiautomator dump` is refused in favor of guarded `read_ui`. Android CI
now runs the `app` and `device-tools` unit tests that cover these fixes.

Local gate passed: `test assembleDevRelease assembleDevDebugAndroidTest
:voice:lintDebug :app:lintDevDebug --no-daemon` (905 tasks); all five runtime
staging Python tests passed. A nondebuggable Dev Release QA APK (code 1017,
`0.13.0-dev.release-qa`) was zip-aligned, debug-key signed with APK Signature
Scheme v3, and installed in place on Xiaomi 23053RN02Y. The installed signer
matched before replacement; `firstInstallTime` remained unchanged, Accessibility
stayed bound, and the five runtime native libraries were extracted. On this
exact APK, a signed-in chat opened Gym Test and read its UI through
Accessibility. A manual standing rule created and fired a local notification;
`describe` reported `firedToday: 1`. Full evidence and remaining hardware
matrix: `docs/RELEASE_READINESS_2026-09-26.md`.

A Saturday 21:52 scheduled rule also delivered `QA-timer-ran` while the
display was OFF. Exact alarms were allowed. This proves one screen-off alarm
delivery on this phone; it does not prove deep Doze or reboot recovery. Both
QA rules were then deleted; a final rule listing reported `count: 0`.

On the same installed APK, a further Gym Test turn checked device status,
opened the app, read its UI, set the exercise search field to `RELEASEQA`,
verified it, cleared it, and verified the original placeholder. A direct
`shell("uiautomator dump")` was refused with the `read_ui` guidance, and
`accelerometer_rotation` remained `0`. Nothing was submitted.

Still open: production signing/package and migration choice, phone updater
flow, first-launch and account switching, broad device tools, scheduled and
background automation edge cases, voice, and Wireless ADB. These are not established by
the passing build or these phone smoke runs. No release or `main` merge was
performed.

## Computer chat ownership and phone file sharing — 2026-09-27

- A conversation imported from desktop Codex is marked in the sealed computer
  binding. Only an imported conversation with a writer lock can offer a copy;
  Mike-owned chats do not offer to fork themselves. Older bindings without an
  origin mark still use the lock check plus Mike's loaded thread list.
- In a computer chat, `push_file` copies the PC file over SFTP, then saves it
  through MediaStore on the phone without Wireless ADB. It returns a URI for
  `files_media share`. The live tool snapshot now lists that route as ready
  while ADB is off. `install_apk` and `pull_file` still need ADB.
- `:app:assembleDevDebug --no-daemon --quiet` passed after rebasing this PR
  onto `dev` at `724d072`. This proves compilation and packaging only.
- `RemoteStoreTest` and `ComputerFilesGatewayTest` passed on Windows after
  adding checks for saved conversation origin and `push_file` readiness with
  ADB off. A DevDebug APK with versionCode override 1020 installed in place on
  Xiaomi 23053RN02Y. The installed package reports code 1020, and its
  `firstInstallTime` stayed at 2026-09-23 16:24:36. MainActivity opened and
  the saved chat list remained visible.

Not verified: a real PC-to-phone copy with ADB disconnected, WhatsApp sharing,
and the fork banner on a physical phone. Xiaomi's Computers screen says
"No computers yet", so those remote flows could not be exercised there.

## Compact chat history — 2026-09-27

Local and computer chat rows now show one title line with a 48dp minimum touch
target. Per-chat date and time text is removed; the day headings remain. An
icon identifies computer chats. Built dev debug APK with versionCode override
1021, then installed it with `adb -s cd4928027d76 install -r`. Package state
showed versionCode 1021 and the original first install time. On Xiaomi
23053RN02Y, the drawer showed compact one-line chat titles without per-chat
timestamps and kept the Today/Yesterday headings and prior chat history.
Computer-chat rows remain unverified on device because no computer is paired.

## Xiaomi QA stability fixes — 2026-09-25

The adversarial Dev Nightly run on Xiaomi 23053RN02Y found five concrete gaps:
mixed-case HTTPS intent resolution, empty `set_text` read-back, ambiguous
`act_plan` verification, separate legacy and declarative workflow formats,
and a package-filtered `read_ui` reply with no foreground context. The fixes
normalize URI schemes, check the refreshed or newly observed editable field
and treat its displayed hint as an empty value after clearing, mark completed steps without a condition as
`verification:"not_requested"`, add `workflow_runner(mode="save")` and a
separate legacy listing, and show the active package in zero-match hints.
New chat turns are recorded in `session-trace.jsonl`, with timestamps, exact
tool arguments and results, assistant messages, and links to image artifacts.
Only new turns can be traced; older runs cannot be reconstructed. On-device
workflow guidance uses the declarative save path. The old literal workflow API
remains available for saved sequences and is not silently converted into a
verified definition.
Package-scoped `workflow_runner` lookup now refuses a definition belonging to
another app instead of falling back to all definitions. A focused gateway test
confirms this refusal happens before any device tool is called.

Verified in an isolated worktree based on `dev` commit `2309e9f0dad9`: the full
`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug` gate,
five `tools.test_prepare_runtime` tests, and `git diff --check` passed. The
gate exposed one old `:engine-codex` test with a stale resume-count assertion;
it now matches the existing tools-first, plain-resume, then fresh-thread
behavior. DevDebug QA build 1014 (`0.12.0-dev.qa-stability3`) was signed with
the installed Dev app certificate and installed with `adb install -r`, which
kept app data. The unsigned lower-version release artifact was not installed.

Physical Xiaomi run on build 1014, package `dev.androidagent.app.dev`, using
Gym Test (`dev.androidagent.jevgym`): `open_app` succeeded; `read_ui` returned
`source:"accessibility"` and `nodeActionsAvailable:true`; the empty Search
exercises field was set to `QA_CLEAR_20260925` and verified; then `set_text`
cleared it and a fresh read confirmed its empty hint. Both `set_text` results
reported `verified:true`. The fresh session trace records the exact calls,
arguments, results, and final assistant response. No Gym Test form was
submitted.

Physical follow-up on the same Xiaomi: DevDebug QA build 1015
(`0.12.0-dev.qa-stability4`) was built from the current tree. `aapt` reported
`dev.androidagent.app.dev`, code 1015, and that version name. Its signing
certificate SHA-256 matched the installed build 1014, then `adb install -r`
succeeded. The package reports code 1015 and the same first install time;
Accessibility stayed enabled and bound. The model picker showed `6-luna`.
In a fresh QA chat, `workflow_runner(mode="save")` stored an observe-only Gym
definition with `nothingRan:true`; `mode="list"` returned it. Running its id
with package `com.android.chrome` returned `workflow_not_found` and
`nothingRan:true`, with no nested device action. An `act_plan` observe step in
Gym returned `status:"done"`, `verified:false`, and
`verification:"not_requested"`. `resolve_intent` alone resolved
`Https://example.com` as `https://example.com` to Chrome; no URL was opened.
A Gym `read_ui` filtered to a nonexistent package returned zero matches,
`activePackage:"dev.androidagent.jevgym"`, `source:"accessibility"`,
`nodeActionsAvailable:true`, and a hint naming the active package. The fresh
session trace records the exact calls and results. No Gym form was submitted.

Still open: rerun the broader adversarial matrix across every agent tool on
the updated Xiaomi. The two focused phone runs directly cover the tools and
paths named above, plus the earlier `set_text` read-back; other tools and
positive paths still lack new physical coverage. After the package lookup fix, the current-tree full gate
(`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug
--no-daemon`) passed, as did all five `tools.test_prepare_runtime` tests and
`git diff --check`. These build checks do not establish the untested physical
paths. The package-filtered read did not exercise keyboard/IME context.
## Computers over SSH (Windows) — 2026-09-24

On `claude/model-ssh-capability-ifuz1p`, a chat can run on the user's Windows
PC. The side panel's *Computer* button opens a sheet: add a PC (IP, user,
password, and *Ask me first* or *Full access*), connect, and pick a project
folder. The app installs the pinned Codex 0.156.0 app-server on the PC
(sha256-checked), runs it over SSH, and the chat talks to it. Codex then has
its own tools, the project's AGENTS.md and the PC's skills. See
`docs/ARCHITECTURE.md`, "Computers".

Verified here, on Linux with a new SDK install (no phone, no Windows PC):
- `:remote:testDebugUnitTest` 13/13. Includes an in-process SSH server
  (Apache MINA): password login, host-key pinning, a wrong key refused, a
  wrong password named. Also the **real Linux Codex 0.156.0 app-server
  started over an SSH channel**: `account/read` answered, and `skills/list`
  found a skill placed in the remote project's `.agents/skills`.
- `:core:test` 547/547, `:app:testDevDebugUnitTest` 48/48 (new approval-card
  case), new `CodexEngineTest` cases (Windows cwd and access in
  `thread/start`, inline pictures, skill paths with backslashes).
- `:app:assembleDevDebug`, `:app:assembleDevRelease` (JSch present in the
  dex), `:app:lintDevDebug` with 0 errors.
- The Windows package sha256 values were computed from the downloaded
  release assets (x86_64 and aarch64).

Pre-existing, not from this change: `CodexEngineTest.openSessionFallsBackToThreadStartOnResumeFailure`
fails on `dev` too (expects one `thread/resume`; the engine tries with tools,
then without).

Not verified (needs a phone and a Windows PC):
- JSch on Android (it uses the Java 8 classes; ed25519/curve25519 may be
  dropped for ECDSA/ECDH) against Windows OpenSSH Server.
- The PowerShell probe, install and folder scripts on real Windows, the cmd
  quoting of a user folder with spaces, and a PowerShell `DefaultShell`.
- `codex-app-server.exe` over a Windows OpenSSH exec channel, the device-code
  sign-in on the PC, a full turn with edits, an approval answered on the
  phone, Stop, and whether Windows OpenSSH ends the process when the
  channel closes.
- What *Ask me first* enforces without the Codex Windows sandbox set up.
- The Computers sheet and folder picker on a real screen.

### Computers sheet: setup guide, VPN address, default computer — 2026-09-25

- The add form opens with a 5-step "set up the PC" guide (OpenSSH Server,
  `Get-Service sshd`, `ipconfig`, `whoami`, Codex installs itself), copy
  buttons, and a share button that sends the steps to the PC. Fields carry
  hints; the button says what is still missing. Sheet text colour fixed (it
  was dark on dark).
- A computer can have a second, VPN address (e.g. Tailscale). Connect tries
  the address that answered last first, the home one to begin with (6 s
  when another is left), and moves on only when an address does not answer
  at all. A refused password or a changed host key stops. The pinned key
  holds for both addresses.
- Several computers, one default (the first added; passes on when removed).
  The side panel's *Computer* button connects straight to the default and
  opens its folder picker; Back shows the list.

Verified on Windows: `:remote:testDebugUnitTest --tests *RemoteStoreTest*`
passes (new default and VPN cases), `:app:assembleDevDebug` builds, the APK
installs on the Nothing A059 with `install -r`. The two `SshLinkTest` cases
that run Linux commands and the Linux app-server fail on a Windows host, as
expected. Not verified: the sheet on screen, the VPN fallback against a
real PC, `:app:lintDevDebug`. A phone-chat request "do X on the computer"
is not routed to the default computer: a computer chat is still chosen when
it is opened.

### Computers: projects, PC conversations, file transfer — 2026-09-25

- Computers screen is a full screen (scrolling a folder list no longer
  closes it). Adding a computer is two steps: the PC's setup steps, then the
  sign-in. One address is enough: home, VPN, or both.
- Side panel: each computer first, with status and folding projects; under
  each project the chats here plus the conversations Codex on the PC has
  (`thread/list`), "From Codex on the PC". Opening one binds a chat to that
  thread and copies its messages from `thread/read`. The phone's chats fold
  under "On this phone". The panel connects quietly once per app run
  (never installs Codex).
- New chat: "Where should Mike work?" chips (this phone, recent projects,
  another folder) until the first message. Title bar of a computer chat:
  computer · folder + phone.
- Files: `push_file`/`install_apk` in a computer chat take a path on the PC
  and are copied over SFTP; `pull_file` copies into the project folder.
- Instructions: the Windows desktop is reached through a one-off interactive
  scheduled task (screenshots etc.); adb on the PC must not be used for the
  phone.

Verified on Windows with the Nothing A059: the PC's OpenSSH (Win32-OpenSSH
10.0 via winget) answers on the Tailscale address from the phone
(`nc -z 100.81.116.55 22` open; the LAN address times out because the
Ethernet profile is Public and the firewall rule is Private only). A real
computer chat ran on the PC; before the file bridge, copying a picture to
the phone failed through `push_file` and only worked through the PC's own
adb, which is why the bridge exists. Unit tests: new `PcChatsTest`,
`ComputerFilesGatewayTest`, thread list/read parsing, store projects;
`:app:lintDevDebug` clean; `:core:test` and `:app:testDevDebugUnitTest`
pass. Not verified: the new side panel and screens on the phone,
`thread/list` answers from the real PC (parameters taken from the 0.156.0
binary), SFTP transfer against Windows, the desktop scheduled-task recipe.

### Voice on computers, no send size cap, the `computers` tool — 2026-09-25

- Voice works in a computer chat: realtime starts on the thread's own
  app-server (the PC's Codex); WebRTC, so only SDP crosses SSH.
- `push_file`/`install_apk`: no size cap; the timeout grows with the size.
  Also no cap on the SFTP copy from the PC.
- New tool `computers` (modes `status`, `browse`, `new_project`,
  `open_chat`, `add`) in every chat. `add` opens the app's form filled in,
  with a warning to check the address; the password is typed there only.
  `open_chat` opens a project chat with the task in the composer, unsent.

Verified: `ComputerToolGatewayTest` (add fills the form and the schema has
no password field, missing address refused, status with no computer,
unknown computer/project name what exists, nothing after Stop),
`:core:test`, `:device-tools:test`, `:app:testDevDebugUnitTest`,
`:app:lintDevDebug`, `:app:assembleDevDebug`. Not verified on the device:
voice in a computer chat, a 310 MB push, the tool's modes against the real
PC, the filled-in form and the composer draft.

### Linux (Ubuntu) computers — 2026-09-25

The system is found on the first connection (`uname -s`) and kept. Linux
computers use POSIX `sh` scripts (`LinuxHost`) and the phone's own pinned
Linux Codex package, installed under `~/.local/share/heymike`. The add
screen's setup step has Windows and Ubuntu guides (`apt install
openssh-server`, `hostname -I`, `whoami`). Paths, the folder picker, file
transfer and project grouping handle `/` paths.

Verified: the generated probe, folder list (a quote in a name, hidden
folders skipped, git detection), create-folder and missing-folder scripts
ran in a real Linux `sh` (busybox, WSL) and gave the expected `HEYMIKE`
answers; `LinuxHostTest` (uname reading, Linux paths, quoted app-server
path; the script test runs only where a Linux `sh` exists and was skipped
on Windows); full remote/core/device-tools/app unit tests, lint, build. Not
verified: the install script's download and sha256 check and the app-server
start on Linux (running a downloaded binary was not allowed here), a real
Ubuntu machine over SSH, the Linux desktop recipe.

### Side panel: Material 3 pass and loading states — 2026-09-25

- Every slow step shows where it happens: an indeterminate line and a
  spinner under the computer's header while it connects or lists its
  conversations, placeholder rows until the first projects arrive, an error
  card with Try again and What to check, a sign-in card, and a loading view
  (progress line, "Loading the conversation from <computer>", placeholder
  bubbles) while a PC conversation's messages come in.
- Material 3 look: 56 dp section headers, 48 dp project rows with a count
  badge, 52 dp pill chat rows with the secondary-container indicator, a
  lifted drawer surface with an explicit text color, item animations.
- Speed: the grouping is remembered per input instead of rebuilt each frame,
  and rows carry content types.
- The panel's top scrolls with the list, so a phone on its side still shows
  chats. "Work on your computer" sits under the search box, not below every
  phone chat. The computers screen is an in-app layer (Back steps back) that
  clears the gesture bar.

Verified on the Xiaomi Redmi 12 (`cd4928027d76`, Android 15, dev build with
a local versionCode 1016): the panel opens with readable text and the pill
selection; in landscape the fixed top had left no room for chats (fixed);
the computer entry was last in a long list (fixed); the setup step's bottom
button sat under the gesture bar in a dialog window (fixed by the in-app
layer); Back goes form, setup, list, closed; Windows and Ubuntu guides both
render. Not verified on a device: the connecting, error, sign-in and PC
conversation loading states (no computer is set up on that phone, and
entering a password is not something the tester does).

## Chat streaming scroll — 2026-09-24

On `fix/chat-stream-scroll-jank`, programmatic chat scrolling now has one
writer. It follows measured row growth and viewport changes with pixel scrolls,
and scrolls to the end once when a new row appears below the viewport. Manual
scrolling up pauses follow mode; the jump-to-latest button resumes it.

Verified: `:app:compileDevDebugKotlin`, `:app:assembleDevDebug`,
`:app:lintDevDebug`, and `git diff --check` passed. No phone visual run was
done. The attached emulator has the user's running Hey Mike Dev app, so it
was left untouched. Streaming behavior, keyboard resize behavior, and the jump
button still need visual validation on a device.

## New user experience — 2026-09-23

On branch `claude/new-user-experience-design-b75114` (PR #84), first launch is
a step-by-step flow (`OnboardingFlow`): welcome, consent, sign-in, screen
access (with the restricted-settings rescue), and a handover where the
floating Stop button and notifications are one tap each and Mike sets up
wireless debugging after an in-app confirmation. Settings are regrouped by
ability with a Privacy and consent page that can withdraw; the side panel has
the orb status, chat search and day groups. See `docs/ARCHITECTURE.md`,
"First launch: two things by hand, the rest offered".

Verified: `./gradlew.bat :core:test :app:assembleDevDebug :app:lintDevDebug`
passed (lint 0 errors, no warnings in the changed files), including nine new
`OnboardingTest` and three `ChatDayGroupsTest` cases; `git diff --check` clean.
Not verified: nothing ran on a phone. Still to check on a device: the whole
first launch from a fresh install, the restricted-settings path, an upgrade
from 0.12.0 (should show only consent and handover), "Let Mike set it up" for
wireless debugging end to end (Mike reaching Developer options and the pairing
reader catching the code within five minutes), and withdraw consent.

## Codex runtime 0.156.0 — 2026-09-23

The pinned Codex app-server moved from 0.153.4 to 0.156.0 so the GPT-6 models
show up: the backend only lists them to newer clients. Both package hashes
match the GitHub release digests, the package layout is unchanged, and the
`codex-code-mode-host` string still appears twice with the constant last, so
the helper patch applies as before. The APK's runtime metadata files are now
overwritten on every start, so an updated phone no longer shows the old version.

Verified: `python -m unittest tools.test_prepare_runtime`,
`python tools/prepare_runtime.py`, and
`gradlew :core:test :engine-codex:test :runtime:test :app:assembleDevDebug` passed.
On the Nothing A059 (`install -r`, `versionCodeOverride=43` over the nightly
build): the 0.156.0 app-server started, `models_cache.json` reports
`client_version 0.156.0`, and the model picker lists 6-astra, 6-sol, 6-luna,
5.6-sol, 5.6-terra, 5.6-luna and 5.5. One turn on 5.6-luna ran one device
tool and answered the battery level.

Not verified: realtime voice, a turn on a GPT-6 model, code-mode, the release
APK, and the full gate (`:app:lintDevDebug`, `assembleDevRelease`).

## Jev isolation cleanup — 2026-09-23

This branch reverts the five Jev implementation commits that landed on `dev`
before PR #78. The unrelated Dev launcher icon commit `d2dfc2b` remains.
Jev work continues in PR #78 after this cleanup is merged; this revert does
not remove the original commits from Git history.

Verified locally: `:core:test`, `:a11y:testDebugUnitTest`,
`:device-tools:testDebugUnitTest`, `:app:testDevDebugUnitTest`,
`:app:assembleDevDebug`, and `:app:lintDevDebug` passed. The host-side
accessibility schema tests required
lazy initialization of Android scroll constants after the revert. No phone
run or release build was performed for this cleanup.

## Usage widget for every account — 2026-09-23

On branch `claude/account-usage-widget-8vccz5`, a home screen widget shows the
quota left on every saved Codex account, each as a still frame of the agent
orb (see `docs/ARCHITECTURE.md`, "Usage widget"). The live account is read
live; the others show their last reading and its age, kept by the new
`AccountUsageBook` in `:core`.

Verified: `./gradlew :core:test :app:assembleDevDebug :app:lintDevDebug`
passed (the CI set), including eleven new `AccountUsageBookTest` cases, with
no lint findings in the new files. The design was checked only as a browser
render of the same orb drawing, not on a launcher. Not verified on a phone:
placing the widget, its size on a real launcher grid, that a switch moves
"In use" and records the new account's quota under the right account, and the
30-minute refresh.

## Multiple Codex accounts — 2026-09-22

On branch `claude/multiple-accounts-branch-i6emwt`, the app keeps several
Codex sign-ins and switches between them in one tap from Settings → Account or
the usage sheet in the top bar. Only `CODEX_HOME/auth.json` is swapped (see
`docs/ARCHITECTURE.md`, "Several Codex accounts"); chats and threads stay, and
the quota is read again for the new account.

Verified: `./gradlew :core:test` passed, including six new
`CodexAccountVaultTest` cases (dedupe by email, detach/add, swap keeps chat
files, refreshed tokens survive a round trip, remove). Not verified: the
`:app` changes were not compiled (no Android SDK in that environment), and
nothing ran on a phone. Still to check on a device: a device-code sign-in for
a second account, a switch followed by a turn in an existing chat (the resumed
thread carries reasoning items created under the other account), and that the
quota bars change.

## Status — 2026-09-15

Android Agent is a Developer Preview. It is useful for local testing, but it is
not production-ready.

- 2026-09-21: Prepared the separate `dev-nightly` update channel. The `dev`
  flavor checks the prerelease by package and monotonic `versionCode`, and the
  workflow builds only for a new `dev` commit. The workflow uses the existing
  dev debug keystore from repository secrets; no physical install was performed.

### Verified

- Run summaries and the tool-schema sweep — on 2026-09-16, `:core:test` passed
  (483 tests). Every run that touched the phone now ends with one system line in
  the chat naming total, thinking, phone time across N calls, and time waiting
  for the user; `AgentCoordinator` takes an injected `nowNanos` so the split is
  asserted against a virtual clock rather than machine speed, and
  `RunSummaryTest` plus three `AgentCoordinatorTest` cases cover the line, its
  absence on a run that called no tool, and a 20-second send approval landing in
  the approval bucket rather than in tool time. The schema sweep fixed
  `remember_capability.fallbacks`, `automation_rule.places`, `.deviceState` and
  `.rule`, and `ToolSchemaAudit` now runs over every tool this module advertises.
  The audit also runs in `:a11y` (`A11yToolSchemaTest`) and `:device-tools`
  (`DeviceToolSchemaTest`), over the accessibility backend, the ADB backend and
  the native capability tools, and `thread/resume` re-binds the tool list so a
  chat opened before an app update can call what the update added.
  **Not verified on a phone:** no summary line has been seen in the app's chat UI
  (a `system` message renders as text, but that was not run), and the buckets
  have not been checked against a real slow run.
  **Written without an Android SDK here**, so nothing outside `:core` was
  compiled locally. CI on `5e71235` (`:core:test :app:assembleDevDebug
  :app:lintDevDebug`) is green, which does compile the *main* sources it depends
  on - so the `CodexEngine` resume change and the `A11yDeviceTools` visibility
  change build and lint clean. **Still never compiled:** the three test files,
  `A11yToolSchemaTest`, `DeviceToolSchemaTest` and the new `CodexEngineTest`
  case, because CI does not build non-`:core` test sources. Their schemas were
  checked by hand first - every parameter is string, integer, boolean, or the one
  `object` the accessibility helper marks open, and no `required` name is missing
  from its properties - so the audits are expected to pass, but `./gradlew test`
  on a machine with the SDK is the first thing that proves it. Whether the
  app-server accepts `dynamicTools` on `thread/resume` is unverified too; if it
  refuses, the retry keeps the thread and the old behaviour.
- `act_plan`, one call for a sequence already on screen — on 2026-09-16,
  `:core:test` passed (471 tests once dev was merged in, 23 of them the new
  `ActPlanTest`), covering:
  a three-step focus/type/send plan dispatching `tap_node`, `set_text`,
  `tap_node` in order for one call; the trailing forced observation and
  `observe=false`; refusal of a target named by `nodeId` and of an
  `observationId` on the call, with nothing dispatched; the 8-step ceiling
  pointing at `workflow_runner`; a malformed plan refused before the phone is
  touched; a failing step reporting the prefix that ran, and a `resume` block
  naming `act_plan` with `startAt`; a resume skipping the committed steps; and
  the app in front being read rather than asked for; Stop mid-plan reporting the
  step that had run and going back to the phone for nothing; and a closing read
  that fails not unreporting the step that did run.
  The advertised schema was wrong on the first device run: `steps` was a bare
  `{"type":"array"}`, which a client renders as an array of strings, and the
  agent sent each step as quoted JSON and was refused. `steps.items` now spells
  out the step object with the action enum and the target fields (and so do
  `run_workflow` and `save_workflow`), a quoted step is parsed rather than
  refused, and the example in the refusal, the tool description and the test is
  one shared constant that the test executes.
  Per-step phase timing (`resolve`/`act`/`settle`/`verify`) and the 60s `verify`
  ceiling are covered by three `WorkflowRunnerTest` cases against a clock that
  moves only when the phone is touched, plus one parse case. Both came from a
  real post-with-media run on X; **neither has been re-run on a phone**, so
  whether 60s is enough for a video import, and whether the phase split points
  at the right culprit on real hardware, is unproven.
  **Not run in this environment:** no Android SDK, so `:device-tools:test`,
  `:a11y:testDebugUnitTest`, `:app:testDevDebugUnitTest`,
  `:app:assembleDevDebug` and `:app:lintDevDebug` were not executed here — CI
  runs `:core:test :app:assembleDevDebug :app:lintDevDebug`. **Not verified on
  a phone at all:** no plan has run against a real app, so the step budget, the
  settle timing between steps and a send approval landing mid-plan are unproven
  on hardware. The UI label and pulse mappings for `act_plan` were not compiled
  or seen on a screen.
- Native capability APIs and API-capable workflows — on 2026-09-15,
  `:core:test :device-tools:test :workspace:testDebugUnitTest
  :app:testDevDebugUnitTest :app:assembleDevDebug :app:lintDevDebug` passed
  together (405 actionable tasks). A forced `:device-tools:test` rerun passed
  200 test executions across debug and release variants with zero failures;
  the current `:core:test` results contain 301 passing tests. The five native
  tools cover contacts, calendar, MediaStore and run-workspace files,
  communication drafts/dialer, and apps/settings. Declarative workflows can
  call those tools with JSON, capture typed output, use it in later steps and
  carry it through resume without re-running completed calls. The callable set
  is explicit; shell, install and workflow recursion are blocked. Runtime
  grants use one Android permission request for only the missing allowlisted
  permissions, with no second Hey Mike approval. `python -m unittest
  tools.test_prepare_runtime` passed (5 tests) and `git diff --check` is clean.
  Not verified on a physical phone: provider rows, selected-photo behavior,
  OEM editor/settings intent handling, the runtime permission dialog, or a
  complete workflow that carries one real API result into another call.
- Standing rules (`automation_rule`) — on 2026-09-15 the full CI gate passes:
  `gradlew test :app:assembleDevDebug :app:lintDevDebug`, with 0 lint errors and
  no lint warning naming an automations file. `:core` carries 390 tests, 116 of
  them new. `AutomationOverview`/`AutomationSummaries` — the layer that turns a
  rule into the sentences the panel shows and decides which three chips and
  which one sentence the strip carries — is covered by 28 of those: trigger and
  conditions as one line, named days, intervals in hours, the three states, what
  sorts first, the strip never growing past three chips, the sentence choosing a
  blocked rule over a schedule, and the exported fields named field by field.
  Across `CoordinatorAutomationTest`, `AutomationRuleTest`, `AutomationEvaluatorTest`, `AutomationLibraryTest`,
  `AutomationToolGatewayTest`, `AutomationJournalTest`, `AutomationRunnerTest`,
  `AutomationWakeupsTest` and `SessionRunQueueExpiryTest`. What the
  tests actually establish: the when/if/then format round-trips through its own
  parser; a placeholder the trigger cannot provide is refused where it is
  written rather than reaching the model as literal braces; only the fields an
  action interpolates are exported, so matching on a message body does not send
  that body; a `time_between` window that crosses midnight is not an empty
  window; an alarm at the wrong minute does not fire a scheduled rule; the
  cooldown, the daily limit and the attention gate each hold and each say which
  one held; and evaluating records nothing, so a dry run cannot consume the
  quota it reports on. On the run side: actions run in order, the fire is
  recorded *before* the first action so a crash cannot replay a side effect, a
  failure never claims the earlier actions were undone, a host that cannot ask
  refuses an approval-gated action rather than running it, and a queued turn
  past its deadline is dropped by `SessionRunQueue` without holding up the turn
  behind it. On device ownership: a rule claims the phone exclusively, releases
  it even when the action throws, waits for a turn that already holds it rather
  than reporting busy, gives up rather than surfacing long after its moment, and
  a Stop mid-rule revokes the gateways and keeps the teardown.
- Standing rules, the edit form — on 2026-09-22 `:core:test` passes, 536 tests,
  none failing; `AutomationEditorTest` is new (14). It establishes: a rule
  becomes plain values with no braces on screen, grouped under their condition
  or action; saving an untouched form changes nothing; moving the time changes
  only `when`; a loose "7:5" saves as 07:05; clearing the days means every day;
  text inside a workflow parameter, a notification and a condition is edited in
  place; an emptied optional value is removed; the ask-first switch round-trips;
  limits are written as one guard and keep an untouched sub-minute cooldown; the
  workflow's own name is not offered for typing over; and each bad value is
  named on its own field. `:app:compileDevDebugKotlin` and `:app:lintDevDebug`
  pass, 0 lint errors and no warning in a changed file.
- Standing rules, delete/edit and reliability — on 2026-09-22 `:core:test`
  passes, 516 tests, none failing; 20 of them new across `AutomationLibraryTest`,
  `AutomationToolGatewayTest` and `AutomationEvaluatorTest`.
  `:automations:compileDebugKotlin`, `:app:compileDevDebugKotlin` and
  `:app:lintDevDebug` pass, with 0 lint errors and no warning naming a changed
  file (the runtime staging step was skipped; it does not affect Kotlin). What
  they establish: a mutating lookup needs the exact id and a near miss is only
  suggested; an edit replaces only the keys it names, `null` removes one, an
  invalid edit leaves the old file intact, and an edit cannot rename; `create`
  refuses an existing id without `replace:true`; every change tells the host to
  re-arm and reads do not; a late alarm still runs its slot, a served slot is not
  run twice, a slot past its window and one before the rule was saved are not
  run; an interval runs only once its interval has passed and its alarm counts
  from its last run, never landing in the past. Run in a container without
  Maven Central (rate limited, 429), through Google's Maven Central mirror.
- Note (run against this container's toolchain, not a phone): the one
  `:workspace` test failure seen here — `uriParametersArePercentEncoded` — is
  the container's `LC_CTYPE=POSIX` mangling Hebrew in `act.sh`, not a
  regression. It passes under `LANG=C.UTF-8`, and nothing in this change
  touches `quick-actions`.

- The agent's own messages now reach the floating card, not only the chat.
  `ControlOverlay.say` is a separate channel from the status label;
  `AgentCoordinator` mirrors every assistant segment to it as it is stored,
  and the card keeps the last line through the tool calls that follow. On
  2026-09-15 on Linux/JDK 21: `:core:test`, `:overlay:test`,
  `:app:assembleDevDebug`, `:app:lintDevDebug` (0 errors, 18 warnings) and
  `:app:assembleDevDebugAndroidTest` all pass. That proves the mapping, the
  coordinator wiring and that everything compiles — nothing more. Not tested:
  any phone. The card itself, the new
  `FloatingControlOverlayTest.theAgentsOwnWordsStayOnTheCardAcrossToolCalls`,
  and how a long message reads in the four lines it now gets have not been run
  on hardware; no device was available in this environment. The full gate
  (`test assembleDevRelease assembleDevDebugAndroidTest :voice:lintDebug`)
  was not run.
  On 2026-09-23 the card draws those words as Markdown (Markwon core, no
  links or taps, one line break between blocks, headings as bold lines)
  instead of flattening them to one plain line. After merging `dev`:
  `:overlay:test` (9, with the new `cardKeepsTheMarkdownOfWhatTheAgentSaid`),
  `:overlay:lintDebug`, `:core:test`, `:app:assembleDevDebug`,
  `:app:lintDevDebug` and `:app:assembleDevDebugAndroidTest` pass. Still not
  run on a phone, including the new Markdown check in the instrumented test.

- The v0.13.0 dev release candidate was built from `origin/main` on 2026-09-15.
  `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :overlay:lintDebug :app:lintDevDebug --no-daemon` passed
  (832 actionable tasks), `python -m unittest tools.test_prepare_runtime`
  passed (5 tests), and `git diff --check` is clean. `aapt2` reports package
  `dev.androidagent.app.dev`, versionCode 27, versionName 0.13.0, label
  "Hey Mike Dev" and not debuggable. The exact APK was zip-aligned and passed
  APK Signature Scheme v3 verification with the local debug key. SHA-256:
  `B965F9EC93A16873DB2E95B433006DD05BF9A9A7264C09ADB027A91D7C5BBDD7`.
  It was installed with `adb -s Q8G64TD6ZTB6H6ZL install -r` on the Xiaomi
  2201116TG (Android 13); all staged `lib*.so` files were present in the
  arm64 native library directory. A task typed into the exact APK opened
  Settings and reached About phone showing Android 13. Not tested: WhatsApp,
  voice, Accessibility control, or a production-signed build.

- `workflow_runner`, intents with extras, workflow parameters and "Suggest
  workflows" — on 2026-09-14: `:core:test` (274), `:a11y:testDebugUnitTest`
  (35), `:app:testDevDebugUnitTest` (45) and `:workspace:testDebugUnitTest`
  (18) pass, and `:app:assembleDevDebug` builds. On a Nothing Phone (A059,
  Android 16) and a Xiaomi (2201116TG, Android 13), dev builds of this branch:
  - A learned `timer-seconds` workflow with an `open_intent` SET_TIMER step and
    a `seconds` parameter started a real timer on both phones; the Xiaomi rang
    after the 60 seconds it was given.
  - `open_intent` with `android.settings.WIFI_SETTINGS` and no uri opened the
    Wi-Fi screen (Nothing).
  - A learned `google-tasks-add-task` workflow ran clean, and the "Suggest
    workflows" chip appeared after a multi-step run and was answered (Nothing).
  - A Settings-search workflow found the search bar, typed, and — after an
    approval — flipped the Wireless debugging switch in Developer options
    (Nothing, resumed from the switch step). Its `open_result` tap failed twice
    before the row-click fix; that fix is unit-tested and seen working on the
    Xiaomi search bar, but the result tap has not been re-run on the Nothing.
  - The Xiaomi clock ignores SET_TIMER extras when its task already exists;
    `CLEAR_TASK` for an intent with extras was confirmed from adb there.
  No workflow ships with the app: Settings search has different field ids on
  the two phones, so each phone learns its own.
- Recording a workflow is **authoring, not capture.** The spec asked for a
  Record mode that watches the user's own taps.
  `AgentAccessibilityService.onAccessibilityEvent` deliberately does not read
  the events it receives — the text a user types is not the app's to look at —
  and a passive recorder would reverse that decision, so it was not built.
  A workflow is worked out once with the device tools and written as a
  definition instead, which is also the only form that can carry verification
  conditions and confirmation flags. Turning that into a guided
  "save what just happened" flow is open work.

- The public v0.12.0 dev APK was built from merged `main` (with #61 and #62)
  on 2026-09-14. A first 0.12.0 candidate was built from a local `main` that
  had not pulled #62, so it had no digital assistant; it was installed on the
  Nothing A059 only, never published, and was replaced by this build.
  `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :overlay:lintDebug :app:lintDevDebug --no-daemon` passed
  (832 actionable tasks; app lint 0 errors, 19 warnings),
  `python -m unittest tools.test_prepare_runtime` passed (5 tests) and
  `git diff --check` is clean. `aapt2` reports versionCode 26 versionName
  0.12.0, label "Hey Mike Dev", not debuggable, with `libcodex_codemode.so`
  for ARM64 and x86_64, and `AgentVoiceInteractionService` plus the
  `ACTION_ASSIST` entry in the manifest; zip alignment and APK Signature
  Scheme v3 verification passed with the local debug key. SHA-256
  `FAF22D892F7EE0C244D92DC09E0AA1011C76AF1192FE4E4809EEB7E2324E0E89`. The
  exact asset was installed on the Nothing A059 (Android 16) over the first
  candidate with the sign-in kept: every staged `lib*.so` was in the native
  library folder, the accessibility service bound as "Hey Mike Dev", and
  `pm query-services` listed Mike's voice interaction service beside Gemini.
  Not run on this asset before publishing: a short agent task that uses a
  device tool (the phone was in use at the time), and a real power-button
  press with Mike picked as the assistant. The assistant flow itself was
  verified with #62's debug builds on the Redmi Note 11 Pro and this phone.
- The public v0.11.0 dev APK was built from merged `main` (with #59) on
  2026-09-13. `./gradlew.bat test assembleDevRelease
  assembleDevDebugAndroidTest :voice:lintDebug :overlay:lintDebug
  :app:lintDevDebug --no-daemon` passed (832 actionable tasks; app lint
  0 errors, 19 warnings), `python -m unittest tools.test_prepare_runtime`
  passed (5 tests) and `git diff --check` is clean. `aapt2` reports
  versionCode 25 versionName 0.11.0, label "Hey Mike Dev", not debuggable,
  with `libcodex_codemode.so` for ARM64 and x86_64 and the `quick-actions`
  skill assets; zip alignment and APK Signature Scheme v3 verification passed
  with the local debug key. SHA-256
  `72A8A4CCF4613A89DD723E26DEB7B296B8573150133BAED706E36D8E8EC26ED6`. The
  exact asset was installed on the Redmi Note 11 Pro (Android 13) over the
  #59 dev debug build with the sign-in kept: `libcodex_codemode.so` was in the
  native library folder, the accessibility service bound as "Hey Mike Dev",
  and "Open Settings and tell me the Android version" answered Android 13.
  The #59 behaviour (no false "needs ADB", global preferences, quick actions
  via `phone.dial`) was verified on the same phone with the debug build before
  merge. Not verified on hardware: `whatsapp.send` and `sms.send` end to end,
  to avoid messaging a real person.
- The public v0.10.1 dev APK was built from merged `main` (with #58) on
  2026-09-11. `./gradlew.bat test assembleDevRelease
  assembleDevDebugAndroidTest :voice:lintDebug :overlay:lintDebug
  :app:lintDevDebug --no-daemon` passed (832 actionable tasks; app lint
  0 errors, 19 warnings), `python -m unittest tools.test_prepare_runtime`
  passed (5 tests) and `git diff --check` is clean. `aapt2` reports
  versionCode 24 versionName 0.10.1, label "Hey Mike Dev", not debuggable,
  with `libcodex_codemode.so` for ARM64 and x86_64; zip alignment and APK
  Signature Scheme v3 verification passed with the local debug key. SHA-256
  `0CECB9AD07C1B1128507043C47820556C294656E10DEB2F56FFEF399A0ECBB46`. The
  exact asset was installed on the Redmi Note 11 Pro (Android 13) over
  v0.10.0 with the sign-in kept: the helper was in `nativeLibraryDir`, the
  accessibility service bound as "Hey Mike Dev", and "Open Settings and tell
  me the Android version" reached About phone showing Android 13. Not
  verified on hardware: the voice-mode labels ("MIKE", "Mike is speaking")
  and the voice-mode title colour, which need a live spoken conversation.
- The public v0.10.0 dev APK, the first named Hey Mike, was built from merged
  `main` (with #57) on 2026-09-11. `./gradlew.bat test assembleDevRelease
  assembleDevDebugAndroidTest :voice:lintDebug :overlay:lintDebug
  :app:lintDevDebug --no-daemon` passed (832 actionable tasks; app lint
  0 errors, 19 warnings), `python -m unittest tools.test_prepare_runtime`
  passed (5 tests) and `git diff --check` is clean. `aapt2` reports
  `dev.androidagent.app.dev` versionCode 23 versionName 0.10.0, label
  "Hey Mike Dev", not debuggable, with `libcodex_codemode.so` for ARM64 and
  x86_64; zip alignment and APK Signature Scheme v3 verification passed with
  the local debug key. SHA-256
  `EA49FD1DD40107054FE35A67CC3118549CA8506AC2529EB25D07F8F62AA7B3B1`. The exact
  asset was installed with `adb install -r` on the Redmi Note 11 Pro
  (Android 13) over the dev build, with the sign-in kept: the helper was in
  `nativeLibraryDir`, the accessibility service bound as "Hey Mike Dev", and
  "Open Settings and tell me the Android version" (5.6-luna, low) opened
  Settings and reached About phone showing Android 13. `compareVersions`
  compares numerically, so v0.7.3 and older see 0.10.0 as newer.
- The rename to Hey Mike passed on 2026-09-11: `./gradlew.bat test
  assembleDevDebug assembleDevDebugAndroidTest :app:lintDevDebug
  :voice:lintDebug :overlay:lintDebug --no-daemon` (app lint 0 errors,
  19 warnings), then `:adb:test :app:assembleDevDebug` after the ADB pairing
  name change; `python -m unittest tools.test_prepare_runtime` and
  `git diff --check` passed. `aapt2` reports the dev label "Hey Mike Dev". On
  the Redmi Note 11 Pro the updated dev debug build installed over v0.7.3
  with the sign-in kept, the accessibility service is bound as "Hey Mike Dev",
  and "Who are you?" in a new chat was answered with the new identity: "I'm
  Mike, an AI agent that runs on your Android phone and uses it for you. I
  run on OpenAI's Codex models through the Codex app-server on the phone."
  Two earlier prompt wordings were rejected by that test: giving the Hebrew
  spelling as a plain aside made the model answer an English question in
  Hebrew, and then add "(מייק)" to its English answer, which the per-line
  right-to-left rule turned into a right-aligned English line. The prompt now
  says to write מייק only in Hebrew replies and to answer in the language of
  the user's latest message. The old repository API URL answers `301` to the renamed repository
  and still returns v0.7.3, so installed updaters keep working. Not verified:
  a new Wireless ADB pairing showing the "hey-mike" name.
- The public v0.7.3 dev APK was built from merged `main` (with #55) on
  2026-09-11. `./gradlew.bat test assembleDevRelease
  assembleDevDebugAndroidTest :voice:lintDebug :overlay:lintDebug
  :app:lintDevDebug --no-daemon` passed (832 actionable tasks; app lint
  0 errors, 19 warnings), `python -m unittest tools.test_prepare_runtime`
  passed and `git diff --check` is clean. `aapt2` reports
  `dev.androidagent.app.dev` versionCode 22 versionName 0.7.3, not
  debuggable, with `libcodex_codemode.so` for ARM64 and x86_64; zip alignment
  and APK Signature Scheme v3 verification passed with the local debug key.
  SHA-256 `2157EED1D1D1CB936CA927377AD2BDA59D3D236583763B47C2B378FF00D9D09E`.
  **This release APK was installed on a physical phone**, the first since
  v0.6.1: on the Redmi Note 11 Pro (Android 13), `adb install -r` of the
  exact asset kept the sign-in, `libcodex_codemode.so` was in
  `nativeLibraryDir`, and "Open Settings and tell me the Android version"
  (5.6-luna, low) opened Settings and reached About phone showing Android 13.
- Device control in release builds was broken and is fixed on 2026-09-11.
  The published v0.7.2 APK, installed on a Redmi Note 11 Pro (Android 13,
  HyperOS 1.0), could chat but could not call any tool: the model reported
  that "the device tool host failed to start" and no device action ran. The
  code-mode helper `codex-code-mode-x.so` was missing from `nativeLibraryDir`,
  because Android extracts only `lib*.so` entries from a non-debuggable APK
  (AOSP `NativeLibraryHelper.cpp`; debuggable apps are exempt). Every
  published APK from 0.6.1 to 0.7.2 is non-debuggable. A dev debug build of
  the same commit extracted the helper, and "Open the Clock app and set a
  timer for 5 minutes" completed on the same phone with the same model
  (5.6-luna, low). With the helper renamed to `libcodex_codemode.so`, a
  `assembleDevRelease` APK, zip-aligned and v3-signed with the debug key,
  installed on the same phone with the helper present in `nativeLibraryDir`
  and `run-as` refused (not debuggable); "Open Settings and tell me the
  Android version" opened Settings and reached About phone (Android 13).
  `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :app:lintDevDebug` passed (app lint 0 errors,
  19 warnings), `python -m unittest tools.test_prepare_runtime` passed
  (5 tests) and `git diff --check` is clean. Not verified: other phones, the
  x86_64 helper, and a published release carrying this fix.
- The public v0.7.2 dev APK was built from merged `main` at tag `v0.7.2` on
  2026-09-11. The full Gradle gate (including clean `:app:lintDevDebug`),
  runtime staging tests and `git diff --check` passed. `aapt2` reports
  `dev.androidagent.app.dev` versionCode 21 versionName 0.7.2 for ARM64 and
  x86_64; zip alignment and APK Signature Scheme v3 verification passed with
  the local debug key. SHA-256
  `F5FA50A871CED900488736E8EB7113FBE1A36EB17CD0E75C5E73F90A6F72AE25`. The
  release APK itself was not installed on a physical phone.
- The agent-orb top bar passed JVM tests and a dev debug build on
  2026-09-11: `./gradlew.bat :app:testDevDebugUnitTest
  :app:compileDevDebugAndroidTestKotlin :app:assembleDevDebug`. New tests
  cover which backend the status names (accessibility alone counts), the
  blocked, ready and working sentences, and the backend notes. `ChatUiTest`
  now opens the status sheet from the orb instead of tapping an ADB pill; it
  compiles but was not run. Not verified on hardware: the orb and quota ring
  in the bar, the status sheet's fix buttons, and the title opening the
  drawer.
- The Workspace files sheet passed JVM tests and a dev debug build on
  2026-09-11: `./gradlew.bat :app:testDevDebugUnitTest
  :app:compileDevDebugAndroidTestKotlin :app:assembleDevDebug`. New tests
  cover splitting the user's files from the files the app seeds, newest
  first, attachment names without their stored ID, file kinds, and the type
  offered to other apps. Not verified on hardware: opening and sharing a file
  through the FileProvider, and which apps offer to open notes and JSON.
- The public v0.7.1 dev APK was built from merged `main` at tag `v0.7.1` on
  2026-09-11. The full Gradle gate, runtime staging tests and `git diff --check`
  passed. `aapt2` reports `dev.androidagent.app.dev` versionCode 20
  versionName 0.7.1 for ARM64 and x86_64; zip alignment and APK Signature
  Scheme v3 verification passed with the local debug key. SHA-256
  `97D8D1E13F0A19BACB3EE4C881AFD67C96D15A03D030A83A85F1FE49319D7E60`. The
  release APK itself was not installed on a physical phone.
- The Skills chip, the `/` menu and composer commands passed JVM tests on
  2026-09-11: `./gradlew.bat :core:test :engine-codex:test
  :app:testDevDebugUnitTest :app:compileDevDebugAndroidTestKotlin`. New tests
  cover reading a skill's interface block, plan mode sent as a
  `collaborationMode` on `turn/start`, the `/` and `$` query rules, whole-draft
  commands, the default prompt a picked skill brings, and the /status line.
  Not verified on hardware: the menu, the sheet and the chips on a phone, plan
  mode and `thread/compact/start` against the pinned app-server, and how a
  skill's brand colour and default prompt look for real skills.
- The public v0.7.0 dev APK was built from `main` with the release version on
  2026-09-11. The full Gradle gate (539 JVM tests, no failures), runtime
  staging tests and `git diff --check` passed. `aapt2` reports
  `dev.androidagent.app.dev` versionCode 19 versionName 0.7.0 for ARM64 and
  x86_64; zip alignment and APK Signature Scheme v3 verification passed with
  the local debug key. SHA-256
  `B5CB6196FFDDC50C8AD33FB2650F60E98EEE9B547DF800C00762A5E5C6089625`. The
  release APK itself was not installed on a physical phone.
- Full-screen voice mode, the floating status pill, the new composer, folded
  device actions, per-line right-to-left text, the queue fix after a pause and
  the sphere launcher icon passed the full gate on 2026-09-11:
  `./gradlew.bat test assembleDevRelease assembleDevDebugAndroidTest
  :voice:lintDebug :overlay:lintDebug` (539 JVM tests, no failures; lint
  warnings only), `python -m unittest tools.test_prepare_runtime` and
  `git diff --check`. New tests cover the voice level meter, the overlay's
  plain-language statuses, folding tool messages, the per-line RTL marks and
  list alignment, and a message sent after Stop running while held work waits.
  Dev debug APKs were installed by hand on a Nothing Phone (3a); the user
  reported voice mode, the pill and the composer from real use, but no command
  evidence was captured on the phone. Not verified on hardware: the launcher
  icon (its build was not confirmed installed), the live audio
  level and mute over WebRTC, the window-by-window screenshot (whether the
  keyboard and system bars are captured, its rate limit, secure windows), and
  the instrumented UI tests, which compile but were not run.
- The public v0.6.2 dev APK was built from merged `main` at tag `v0.6.2` on
  2026-09-10. The full Gradle gate, runtime staging tests, APK metadata check,
  zip alignment check, and APK Signature Scheme v3 verification passed. The
  artifact uses the local debug key and was not installed on a physical phone.
- The public v0.6.1 dev APK was built from merged `main` at tag `v0.6.1` on
  2026-09-09. The full Gradle gate, runtime staging tests, APK metadata check,
  zip alignment check, and APK Signature Scheme v3 verification passed. The
  artifact uses the local debug key and was not installed on a physical phone.
- The v0.6.0 dev APK builds with the Codex runtime staging step.
- The recorded v0.6.0 full gate passed on 2026-09-08:
  `test`, `assembleDevRelease`, `assembleDevDebugAndroidTest`, and
  `:voice:lintDebug`, plus the runtime staging tests.
- The current screen-awake change passed focused JVM tests and a dev debug
  build on 2026-09-09. A designated Android 13 test phone kept its screen on
  during a typed run and released the wake lock when the run ended.
- Focused `read_ui` queries and paging (issue #45) passed JVM tests and a dev
  debug build on 2026-09-10: `./gradlew.bat test :app:assembleDevDebug`, with
  31 tests in `UiObservationSerializerTest`, 21 in `NodeTraversalTest` and 33 in
  `AndroidDeviceToolsTest`, all passing. One test pages a synthetic 3 000-node
  screen through `nextOffset` and asserts every node comes back exactly once and
  in order, which is the behavior the issue reported as unreachable. This proves
  the serializer, both parsers and the ADB gateway wiring only; it was **not**
  run against WhatsApp or any physical phone, so the original reproduction is
  still unverified on hardware.
- The `open_intent` prefilled-text path passed JVM tests and a dev debug build
  on 2026-09-10. New tests cover percent-encoding into the uri, that attaching a
  body never downgrades a decision below `NeedsConfirmation`, the ambiguity and
  length refusals, that a waiting approval raises the app and labels the
  floating card, that an unanswered approval expires with a different error than
  a denial, and that a stopped approval clears its card. Not proven on a phone:
  the app-raise itself is an Android `startActivity` from the application
  context and has no unit coverage, and the reported WhatsApp deep link with a
  message body has not been re-run on hardware.
- Per-operation device capability reporting (issue #44) passed JVM tests and a
  dev debug build on 2026-09-10. New tests cover the composite union over live
  backends, the ready/blocked split, an ADB gateway that reports nothing while
  disconnected, and a runtime snapshot that lists the accessibility tools by
  name and no longer emits "Do not call device tools". This proves the snapshot
  text and the gateway plumbing only; the reported scenario — accessibility on,
  Wireless ADB off, `open_intent` on a WhatsApp deep link — has **not** been
  re-run on a phone. The accessibility gateway's own `readyTools()` is not unit
  tested, because `A11yServiceHandle` needs a bound service.
- The app has Compose chat, per-session workspace storage, Codex app-server
  integration, Wireless ADB support, an accessibility device backend, visible
  control state, local Stop, skills, workflows, and experimental realtime voice
  paths.
- The setup hub, quota meter and run indicator were built and run on an
  attached Android 13 phone on 2026-09-09. The hub read every readiness signal
  correctly, including the overlay permission the app never reported before;
  the quota ring and its breakdown matched the account's real windows; and
  `:core:test`, `:adb:test`, `:a11y:testDebugUnitTest` plus
  `:app:compileProdDebugKotlin` and `:app:compileProdDebugAndroidTestKotlin`
  passed. Two bugs were found by running it rather than by reading it: system
  back closed the whole settings sheet from a detail page, and
  `WIRELESS_DEBUGGING_SETTINGS` does not resolve at all on HyperOS, so every
  tap on it was an unguarded `startActivity`. Both are fixed and the back fix
  was re-verified on the phone.

### Not proven yet

- **No rule has ever fired on hardware.** The `:automations` module compiles,
  its components merge into the app manifest, and `automation_rule` is in the
  composite — but an alarm landing at 19:00, a notification listener the user
  granted, a rule taking the screen from a real run, and Stop interrupting a
  firing rule have all only been reasoned about. Everything decided in `:core`
  is unit-tested; nothing about the wake-ups is. This is the single largest gap
  in the feature, and **the checklist for closing it is now written**: see
  "Verifying standing rules on a phone" in `docs/TESTING.md`. It has not been
  run.
- `:automations` has no unit tests of its own. It is alarms, broadcasts and a
  notification listener, which need Robolectric or an instrumented run, and
  neither was added. That is why the module was kept thin, but it is still
  untested code.
- OEM behaviour. Doze, and the aggressive background killing on HyperOS and
  similar builds, decide whether a 19:00 alarm actually lands on a sideloaded
  app. The existing foreground service helps; nothing here proves it is enough,
  on any phone.
- `place` triggers are served by nothing and are reported dormant. One of the two
  reasons is now gone: `RuntimePermissionBroker`, merged with the capability
  tools, is the machinery for requesting `ACCESS_BACKGROUND_LOCATION`. The other
  stands — nothing provides a geofence, and adding one means either a Google Play
  Services dependency in a project that ships outside Play, or
  `LocationManager.addProximityAlert`, which is unreliable enough that shipping
  it quietly would be worse than the gap. That is a decision for the owner, not
  a task.
- **The whole side panel is unseen.** The strip, the rules sheet, the chain on a
  rule's screen and the amber dot on the hamburger compile and are driven by
  unit-tested logic, but no one has looked at them on a phone or in an emulator,
  and no screenshot exists. Spacing, truncation at a long rule name, the sheet's
  height on a short screen, and whether the dot reads at 22dp are all unproven.
  The opening push is unseen too: the shift, the 0.88 scale and the 28dp
  corner were chosen against the mockup, not against a phone, so whether the
  chat reads as a card set aside or as a glitch is the one thing only a device
  can answer — as is how it behaves mid-drag, where the chat animates towards
  the drag's target rather than tracking the finger.
  `AGENTS.md` asks for UI evidence on visual changes; there is none for this.
- The settings screen is partial. Settings > Standing rules now counts the rules,
  names the dormant ones, shows the next run and grants both permissions — but
  it does not **list** the rules, show when each last fired or why it did not,
  or turn one off. That still goes through the agent, which is not good enough
  for a feature that runs unattended. It was left until the checklist above has
  been run, so the screen is built over behaviour that is known rather than
  assumed.
- **Delete and edit on a rule's screen are unseen.** The buttons, the confirm
  dialog and the edit form compile, and the form was rendered once off-device
  (Robolectric, native graphics, 400dp wide, dark theme) from a scratch test
  that was not kept; nobody has used any of it on a phone, and the clock
  dialog has not been seen at all. The catch-up on unlock and at start, and
  evaluation under the run lock, are in `:automations`, which still has no
  tests of its own.
- `notify` taps open the app, not the rule's own chat: deep-linking to one chat
  needs a selection path `MainActivity` does not expose.

- A complete signed-in Codex chat and device-control flow on a supported phone.
- Reliable same-phone Wireless ADB pairing, reconnect, and app-UID self-ADB.
- Reading the pairing code off the system dialog end to end. The parser has
  unit tests, but AGP disables the accessibility service on every reinstall and
  re-pairing was out of scope, so the live capture has not run once.
- That each readiness dot flips after a trip to a system settings screen, and
  that `ChatUiTest` still passes after the composer and settings changes.
- Full physical checks for accessibility, voice, overlay visuals, recovery,
  and all device tools.
- Production signing, production packaging, and production readiness.

The x86_64 emulator app-server currently exits with `SIGSYS` (exit code 159),
so emulator runtime success must not be inferred from APK installation or
Compose fixture tests. See [Known issues](docs/KNOWN_ISSUES.md).

Build output and test results are evidence for those exact checks only. They do
not prove real phone, visual, account, network, or end-to-end success.

Detailed historical evidence is preserved in
[docs/history/PROGRESS-2026-09-09.md](docs/history/PROGRESS-2026-09-09.md).
See the [testing guide](docs/TESTING.md) for the current evidence rules.
