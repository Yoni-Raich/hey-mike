# Progress

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
