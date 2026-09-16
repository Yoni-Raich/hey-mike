# Progress

## Status — 2026-09-15

Android Agent is a Developer Preview. It is useful for local testing, but it is
not production-ready.

### Verified

- `act_plan`, one call for a sequence already on screen — on 2026-09-16,
  `:core:test` passed (319 tests, 18 of them the new `ActPlanTest`), covering:
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
