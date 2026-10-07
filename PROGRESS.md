# Progress

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
