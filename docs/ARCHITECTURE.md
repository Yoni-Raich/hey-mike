# Architecture

One Android project, with replaceable modules and small core contracts.

| Module | Responsibility |
|---|---|
| app | Compose chat, setup, foreground lifecycle, dependency wiring |
| core | Neutral contracts, coordinator, run state, local cancellation |
| engine-codex | Bidirectional Codex app-server protocol and event mapping |
| voice | Android microphone, speaker, and realtime audio lifecycle |
| runtime | On-phone executable provisioning and process supervision |
| workspace | Durable sessions, messages, artifacts and session directories |
| adb | Persistent pairing identity, discovery, localhost transport |
| device-tools | Sole agent-facing device gateway, reads/control/shell/files |
| a11y | Optional in-process accessibility screen observation and control |
| overlay | Floating steering card, status and direct local stop |
| remote | Computers over SSH: sealed profiles, Codex on Windows, chat routing |

A model change is configuration. An engine change replaces the engine adapter. Runtime packaging must not affect chat or ADB APIs. The UI observes app events, never raw Codex JSON.

The MVP permits one active agent run per phone. A session has its own working directory; this is organization, not a claim of OS-level isolation. Stop revokes dispatch first and interrupts active work next. Unknown raw shell requests are visible device control. The overlay tracks Starting, Thinking, Running, Controlling, Stopping, Done, and Error states for the entire run. MainActivity visibility hides its window inside the app and restores it outside the app until the run ends, including between tool calls. Its floating card separates status and local Stop from the steering composer, with 48dp action targets and native drawn icons. It releases input focus before device actions and returns to MainActivity after a terminal state.

Wireless ADB stores only the last successful local connect port in app-private
preferences. The foreground service runs a bounded reconnect loop: it tries
that port first, then uses Android NSD's `_adb-tls-connect._tcp` result and
ignores pairing services. No arbitrary LAN scan is used. A missing service is
reported as Wireless Debugging off/on-waiting when Android exposes that state;
the loop stays idle until the app has a stored pairing identity, and pairing
codes are never requested by reconnect.

`AdbStatus` carries the switch (`adb_wifi_enabled`), whether Mike holds a
pairing, and whether adbd refused it, next to the connection phase. The UI
names what is missing ("Wireless debugging is on · connecting…", "is off",
"Android dropped the pairing · pair again") rather than "Not connected", which
read as "off" on a phone whose switch was on. Android forgets a wireless
pairing after 7 days without a connection (`adb_allowed_connection_time`); adbd
then fails the TLS handshake (`SSLV3_ALERT_CERTIFICATE_UNKNOWN` on the client).
The loop recognises that, stops retrying (each try was five handshakes inside
Kadb) and waits for a new pairing, the switch moving, or five minutes. A
`ContentObserver` on the switch wakes the loop at once and drops a connection
whose adbd went away. `discover()` no longer announces itself while connected:
it used to overwrite CONNECTED, and the loop then closed a live connection.

Mike switches Wireless debugging on itself by writing the same global setting
the Settings switch writes, so Android still shows its own "allow on this
network" prompt on a new network and keeps it off without Wi-Fi. That needs
WRITE_SECURE_SETTINGS, which Mike grants itself with `pm grant` over its own
ADB shell on its first connect: no power beyond the shell the user already
paired. Until then the "Turn on" button opens the Settings screen.

Immediately before each typed Codex turn, `AgentCoordinator` snapshots what
device control can do (see "Per-operation device capability" below). The engine
adds a small application-owned runtime-context text item before the user's text
with each backend's status and the tools that can run now. The newest snapshot
replaces older snapshots in the thread.
The pinned app-server's `turn/start` contract has no per-turn developer-
instructions field, so this context uses a supported input item while the
thread-level developer instructions define its trust and precedence rules.
Wireless ADB is an optional backend: a disconnected transport blocks only the
tools that need it, and only a snapshot with no live device backend sends the
user to the accessibility service. The gateway remains the enforcement
boundary if connection state changes after the snapshot.

Codex app-server is preferred over parsing terminal UI output. The model remains a cloud service; the agent process and workspace live on the phone. The APK packages the official ARM64 and x86_64 Linux-musl app-server variants, and Android selects the matching native library directory. The x86_64 emulator now avoids ARM translation, but its app-process launch currently exits with `SIGSYS` (exit code 159), so emulator runtime support remains unproven.

The Android APK stages the code-mode helper as `libcodex_codemode.so`. Android
extracts an APK native-library entry into `nativeLibraryDir` only when its name
starts with `lib` and ends with `.so`; debuggable apps are exempt from the
prefix rule. The helper was once staged as `codex-code-mode-x.so`, which worked
in debug builds and was silently left out of every release build, so code-mode
models could not call any tool there. The staging script now fails if any staged
name is not `lib*.so`. It patches the helper-name lookup in the pinned
app-server copy to this exact filename, which must keep the upstream name's
20-byte width, and fails closed if the upstream binary layout changes. The
downloaded official archive stays unchanged, and the manifest records the
staged file hashes.

## Android network compatibility

The app-server packages are the official `aarch64-unknown-linux-musl` and
`x86_64-unknown-linux-musl` builds. On
Android, the app UID does not have `/etc/resolv.conf`, so the musl resolver can
fail even when Android/Bionic and the browser can reach OpenAI. Runtime starts
one app-owned HTTP `CONNECT` proxy on `127.0.0.1` before spawning Codex. The
proxy resolves and opens only allowlisted OpenAI HTTPS destinations, then
blindly tunnels TLS; it never terminates TLS or records request data.

The APK contains a hash-pinned Mozilla-derived PEM bundle. Runtime copies and
validates it under app-private files and passes both `SSL_CERT_FILE` and
`CODEX_CA_CERTIFICATE`. `HTTPS_PROXY` and `HTTP_PROXY` are set in both cases,
`NO_PROXY` keeps stdio/local traffic direct, and `CODEX_SANDBOX` is removed.
The engine drains stderr to a separate redacted, bounded-per-line diagnostic
sink (the `CodexEngine` logcat tag). Process logs have no reliable turn scope
and are never appended to RPC, turn or transport failures. Error messages keep
only their own redacted data, cause, additionalDetails and codexErrorInfo.
Proxy lifecycle follows the supervised app-server and closes on stop or failed
startup.

The CONNECT allowlist includes `chatgpt.com:443`: in pinned Codex 0.159.2,
ChatGPT account sessions use `https://chatgpt.com/backend-api/codex` for
models and responses. Allowing only auth.openai.com and api.openai.com lets
device-code login succeed while blocking signed-in chat. The runtime sets
NO_COLOR and strips terminal formatting from redacted diagnostics.

The model list is never hard-coded. `model/list` returns what the OpenAI
backend sends the app-server, and the backend filters by the client version
the app-server reports. New models therefore appear only after the pinned
package is bumped in `tools/prepare_runtime.py` (0.153.4 -> 0.156.0 on
2026-09-22 for the GPT-6 models, then 0.156.0 -> 0.159.2 on 2026-09-30 for GPT-6.1 Sol). Cached archives are named with the version,
so a bump downloads the new package instead of failing the hash check.

## Chat presentation

New chats keep a durable `titlePending` flag (`sessions.db` schema 5). The
coordinator takes an initial name from the first real user message, before
handoff text or the trusted runtime snapshot is added to the engine input.
Computer folder labels are provisional too. During the ordinary task the
agent calls `set_chat_title` once with a short topic name in the user's
language; no separate model request or title-generation chat is started.
This is a chat tool, handled without the phone's device lease.

`ChatTitleManager` is shared by runs and the rename UI. It serializes naming,
protects manual and finished titles, and leaves existing chosen names intact
on migration. Before refinement it reads the explicit server name and keeps
a different name already chosen in Codex. Codex names are saved with
`thread/name/set`, routed to the
thread's owning computer or phone. A new thread gets its provisional name
after its first turn starts. Naming has a five-second budget; a provisional
name's sync failure does not fail the user's task, and refinement remains
available. A manual rename is saved locally even if the remote write fails.
The computer catalog distinguishes an explicit `name` from an unnamed
preview, so an already imported chat's row and header use the shared name
after refresh, including a name chosen by another Mike. Existing sessions
are not renamed in bulk. Claude receives local chat titles only; its adapter
does not persist names in the computer's Claude history.

Selecting a saved chat sets message loading before reading its history on IO.
The first history emission clears it; skill discovery runs separately within
the selection's coroutine scope. Computer imports keep their loading marker
from binding through history import, and clear it in `finally`. Send and voice
start stay disabled while either kind of history loading is active.

The app owns presentation only: a black conversation canvas, neutral user bubbles,
selectable assistant text, and expandable diagnostic/activity rows. The composer
uses the existing send, steer, stop, model, and attachment action contracts.
Stop remains reachable while a steering draft exists; STOPPING blocks dispatch
and preserves that draft. Terminal formatting is removed before display.
A Hebrew or Arabic word stays on the line of the number after it (a no-break
space, `bindNumbersToLabels`), there is no copy button: a long press on a
block opens Copy and Select text. A block is a user's prompt, or the agent's
reply, which is everything after that prompt up to the next one. While this
chat's own run works, its status line sits under the last line of the chat
instead of above the composer. "Jump to latest" appears
only 96dp or more from the end, in the corner on that same side. The composer's
field and chip outlines are at least 3:1 against the black behind them.
With computers saved, an empty new chat is one question, "Where should Mike
work?", answered from a card of rows (this phone, recent projects, another
folder) in `NewChatPlace.kt`; without computers it keeps the plain headline.
Compose fixture tests exercise UI callbacks without starting or authenticating
Codex. They do not establish real runtime, device-control, or network success.

## Realtime voice (experimental)

Voice is isolated behind `RealtimeVoiceEngine` and the separate `voice` module.
The pinned Codex 0.153.4 app-server remains the single JSON-RPC stdio process.
The default app path creates an Android WebRTC peer connection with a local
microphone track and the `oai-events` data channel, then starts
`thread/realtime/start` with `outputModality: "audio"`, protocol V3, and
`transport: { type: "webrtc", sdp: "..." }`. The pinned app-server maps V3
to the AVAS request header `OpenAI-Alpha: quicksilver=v2`, then returns the
remote answer through `thread/realtime/sdp`. The Android WebRTC audio device
module handles the negotiated microphone and speaker media. V2 WebSocket voice
remains available only as an explicit `RealtimeTransport.WEBSOCKET` fallback;
it still needs API-key auth on the pinned app-server and does not fix the
ChatGPT-account error.

Realtime is enabled in the app-private `CODEX_HOME/config.toml` through a small
startup migration. It adds only `[features] realtime_conversation = true`,
preserves existing user settings, and replaces the file atomically. The
migration also repairs the comment-only config created by older builds. The
app-server is restarted before a new voice thread is created; existing threads
created while the feature was disabled are not retrofitted.

The WebRTC path uses the bundled native WebRTC audio device module for live
microphone capture and speaker playback, with hardware echo cancellation and
noise suppression enabled when supported. It waits for `thread/realtime/started`,
the SDP answer, and ICE connection before enabling the microphone. The explicit
WebSocket fallback records and plays signed PCM16, 24 kHz, mono audio in bounded
20 ms chunks. Both paths use audio focus and foreground microphone service state.
Raw microphone audio and SDP are never saved or logged; only finalized user and
assistant transcripts enter the session store.

Realtime turns still pass through `AgentCoordinator`. A matching realtime turn
may use the same device-tool gateway as typed chat. Local stop first revokes new
tool calls, then interrupts an active delegated turn, stops microphone capture,
and asks app-server to stop the realtime conversation. Completed side effects
cannot be undone.


### Assistant panel over the current app

Holding the power button with Mike as the digital assistant opens a panel over
the app the user is in, instead of switching to Mike. The
`VoiceInteractionSession` draws it: a glow sweeps around the screen edge from
the power button, then a card rises from the bottom with the voice orb, the
live transcript, and Mute, Open Mike and End. Only the card takes touches, so
the app underneath can still be read and scrolled. While Mike acts on the
screen, the card fades and takes no touches, so Mike's taps reach the app.

The screen comes from Android itself: `onHandleAssist` delivers the focused
app's `AssistStructure`, which the system sends only while "Use text from
screen" is on. `ScreenText` flattens it to at most 4,000 characters and never
reads a password field; one view longer than that is cut, not dropped. It
enters the realtime conversation as two messages. A `developer` message holds
only the app's guidance: wait for the user, and treat the quoted screen as
data. The screen text follows as a plain conversation message between
`<<<SCREEN_TEXT>>>` markers, with markers inside the screen removed. Another
app writes that text and can put instructions in it, so it must never carry
developer weight. Neither message is saved in the chat. When no structure
arrives within two seconds, only the guidance goes in, and it says the screen
is unavailable; the model can then delegate to Codex, which reads the screen
with its device tools.

The panel has no activity, so the voice conversation moved out of the chat
screen's view model into the app-scoped `VoiceConversation`. It owns the chat
the conversation records into, the transcript and the typed-line echo check.
The chat screen and the panel both drive it, so "Open Mike" hands a live
conversation to the app without restarting it. Closing the panel (End, Back)
ends a conversation that the panel started. Without microphone permission or
consent, a press opens the app's voice mode as before, because the panel has
nowhere to ask for either.

## Unicode input

Device tools temporarily select the bundled IME and probe its actual editor
connection through a package-scoped broadcast. Android enforces DUMP permission
on the sender via receiver registration. The outgoing broadcast must not set
receiver-permission DUMP, because the receiving app does not hold it. A hidden
sender UID on Android 14+ is accepted only behind that platform permission gate;
known non-shell UIDs are rejected. No text payloads are logged.

The gateway stops immediately after an acknowledged commit. Only explicit
no-delivery/no-editor responses can retry. Missing or ambiguous acknowledgements
fail without sending Enter. Cleanup restores the user's previous IME. Runtime
probe responses replace device-specific dumpsys parsing.

## Release versioning

Every distributed APK increments versionCode and updates versionName in
version.properties before building. Release tag, asset filename and embedded
APK version must match. Previously published assets remain available. 0.1.1 is
versionCode 2; the older 0.1.0 fix releases all used versionCode 1.

## On-device agent instruction and skill stack

The on-device agent uses progressive disclosure and Codex's standard skill
catalog:

- **Layer 0: Engine Developer Instructions (`developerInstructions`)**: Dense,
  inviolable system prompt in `CodexEngine` establishing identity, wireless ADB
  ownership, prompt injection boundaries (screen text as untrusted data),
  the 5-step operational loop, and Tier-1 Semantic UI preference.
- **Layer 1: Workspace Harness (`AGENTS.md`)**: Root execution harness seeded
  into every session workspace (`sessions/$sessionId/workspace/AGENTS.md`).
  Enforces the **Observe → Evaluate → Plan → Act → Verify** cycle and routes
  to app cards and skills on demand.
- **Layer 2: 3-Tier Addressing Strategy**:
  1. *Tier 1 (Semantic First)*: `read_ui` returns compact semantic JSON with an
     observation revision, package, elapsed time, node state, bounds, and
     clickable-ancestor targeting. Raw XML is debug-only. Coordinate actions
     still require verification because Android state can change.
  2. *Tier 2 (Vision Fallback)*: `screenshot` is used when semantic observation
     fails, is unexposed (canvas, games, webviews), or images need verification.
  3. *Tier 3 (Hardware Navigation)*: `key` events (`BACK`, `HOME`, `ENTER`) and
     calibrated swipes.
- **Layer 3: Modular Skills & App Cards**: Complete bundled skill sources live
  under `app/src/main/assets/agent_stack/skills/<skill-name>/SKILL.md`. App
  startup installs the app-managed defaults into the app-private
  `$HOME/.agents/skills` before the Codex app-server starts. It removes only
  app-managed legacy copies from the workspace `.agents/skills/`,
  `.codex/skills/`, and `$CODEX_HOME/skills`; unrelated skills are preserved.
  Four skills ship: `device-automation` (tool mechanics and recovery),
  `app-cards` (the knowledge store, saved workflows and starter cards),
  `user-preferences` and `quick-actions`. Installing them deletes the retired
  `recovery-and-safety`. A skill's extra files (`SKILL_FILES`) are copied with
  it; `{{PREFERENCES_PATH}}`, `{{QUICK_ACTIONS_DIR}}` and `{{SKILLS_DIR}}` are
  replaced with absolute paths, and CRs are stripped because the phone's
  `/system/bin/sh` reads them as part of a command.
- **Quick actions**: `quick-actions/scripts/act.sh` (POSIX sh, awk and od only,
  which is all a phone has) resolves a contact alias and an intent template
  into one `open_intent` JSON call; the model makes that call through the
  gateway, so intent policy and approvals still apply and the script never
  touches the device. Built-in templates ship beside the script and are
  replaced on every install; contacts and saved templates live in
  `$HOME/quick-actions/*.tsv`, which installs never touch. This is a skill with
  a script rather than a new tool, so what the agent learns stays in files it
  owns.
- **Layer 4: Durable Preferences**: one `$HOME/preferences.json` shared by every
  chat retains user defaults (preferred apps, addresses, contacts). It used to
  be copied into each session workspace, so a preference saved in one chat was
  gone in the next. The skill carries its absolute path, substituted at install.
  On first start the newest customized per-chat copy seeds it; untouched copies
  are deleted from workspaces, customized ones are left in place.
- **Catalog and composer**: For each session workspace, the pinned app-server
  is queried through `skills/list` with that workspace as the CWD. The catalog
  keeps each skill's interface block (display name, short description, brand
  colour, default prompt). The composer reaches it three ways: the Skills chip
  opens a sheet with search, a leading `/` opens skills and commands above the
  field, and a leading `$` opens skills only. A picked skill rides as a chip
  and is sent as `$skill-name` text plus Codex's native skill input item with
  the catalog-provided name and path. `skills/changed` refreshes the catalog.
  `WorkspaceSeeder` writes only `AGENTS.md` (from the bundled
  `agent_stack/AGENTS.md`, on every seed) into the workspace, and deletes the
  `RECOVERY.md` and `cards/` files older releases planted.
- **Composer commands**: Codex has no call that lists its slash commands, so
  `/` offers the app's own set (`ComposerCommand`): New chat, Compact
  (`thread/compact/start`), Plan mode (`collaborationMode` with mode `plan` on
  each `turn/start`, carrying the turn's model and effort), Model, Rename and
  Status. What a command did is recorded in the chat as a `note` message, which
  never reaches the model. Goals are left out on purpose: an active
  `thread/goal` lets Codex start continuation turns that the coordinator does
  not own.

## Bounded UI observation

`read_ui` has a six-second default total budget instead of inheriting the
gateway's 30-second shell timeout. Exactly one dump runs per observation: the
hierarchy is staged to `/sdcard/window_dump.xml` and read back. A timeout or
`could not get idle state` result returns a typed failure and never starts a
second dump.

Dumping straight to `/dev/tty` is deliberately not attempted. `uiautomator`
reports success and exits 0 on that path while emitting no hierarchy unless the
shell service forwards raw stdout, which the app's transport does not. On the
supported device it cost roughly 2.2 seconds per call and never once returned
data; the staged dump costs about the same and always works.

Successful XML is parsed inside the device gateway with external entities and
DOCTYPEs disabled. The model receives only labeled or actionable semantic
nodes plus clickable-ancestor bounds; decorative empty nodes are filtered.

An observation whose semantic payload is byte-identical to the previous one is
answered with `"unchanged":true` and `"unchangedSinceRevision"` instead of the
node list. The dump still runs every time, so a changed screen is never missed;
only the resend is suppressed. The fingerprint is cleared on `beginRun` and
after any failed observation, so the gateway never claims "unchanged" across a
gap in its own knowledge, and `raw=true` never participates. `force=true`
resends the full list for an agent that no longer holds it. A device trace of
one WhatsApp send showed three consecutive identical observations of the chat
list, so this suppression removes repeated payloads the model has already read.
Each result includes monotonic elapsed time and an observation revision. This
removes raw XML token cost and caps the observed 22-second idle-wait tail, but
it does not prove a faster real WhatsApp workflow until measured on Q8.

### Focused queries and paging

A reply is capped at 20 000 characters, which a busy screen exceeds. Truncation
on its own was a dead end: the reply said `"truncated":true` and the omitted
nodes — typically the lower part of the screen, including contacts and controls
the task needed — had no way back (issue #45). `read_ui` now takes a `UiQuery`,
parsed in `:core` and applied by both backends through the same
`UiObservationSerializer.render`:

- `text`, `resourceId`, `class` and `package` are case-insensitive substring
  filters, combined with AND. A password node is matched on its description
  only, never on the text it never emits, so the filter cannot be used to read
  a masked field one probe at a time.
- `rootNodeId` returns one node and its descendants. `UiNode.parentId` carries
  the nearest ancestor that was **itself emitted**, so a subtree resolves from
  the flat node list in one forward pass over the pre-order traversal; it is
  never serialized, so it costs the character budget nothing. A `rootNodeId`
  that is not on screen is a typed `ui_unknown_node` failure, because an empty
  node list would read as "that part of the screen is empty".
- `offset` is the cursor. Every reply reports `totalNodes`, `returnedNodes`,
  and — when filtered — `matchedNodes` and the query it was given; a reply that
  left something out carries `nextOffset` and a hint naming it. Paging over a
  screen therefore terminates and covers every node exactly once.
- `maxNodes` and `maxChars` only ever lower the caps.

When a package-filtered read matches nothing, the hint also names the active
package and the number of nodes on screen. This makes a keyboard or another
foreground app visible in the result instead of suggesting the requested app
has no controls. A dump failure remains a typed error, not an empty result.
Every successful read also says whether `nodeActionsAvailable` is true. ADB
dump ids can identify nodes in that one reply, but they are not accessibility
handles, so `tap_node`, `set_text` and `scroll_node` cannot use them. The ADB
reply gives a short action hint; the node tools return a typed service or
missing-observation error before touching the phone.

A filter narrows what is *emitted*, never what is read. The dump and the
traversal are unchanged, node ids stay stable, and the accessibility backend
keeps handles for the whole traversal, so `tap_node`, `set_text` and
`scroll_node` still reach a node a query did not list.

The query is part of the unchanged-suppression digest. The same screen answers
two different queries differently, so suppressing the second as "unchanged"
would point the model at a node list that answers the wrong question; an empty
query contributes nothing to the digest, so every unfiltered observation
fingerprints exactly as it did before. The character-budget fit is a binary
search over the node count rather than the previous shrink-by-an-eighth loop,
because paging makes an oversized screen the normal case.

## Prefilled intents and the approval gate

`open_intent` takes the message body as `text` rather than expecting the model
to build `?text=` into the uri. `IntentPolicy.withText` percent-encodes it and
attaches it to the destination, because a hand-built payload is where this
breaks: an unencoded space or `&` truncates the message at the first separator
or fails `java.net.URI` parsing, which the policy then reports as
`uri_malformed`. It refuses a uri that already carries a payload key rather than
overwriting one — two bodies is ambiguous, and silently picking one would send
something the caller did not mean to send. Composition happens **before**
`IntentPolicy.evaluate`, so the body is judged as the payload it is; attaching
text can only ever move a decision toward `NeedsConfirmation`, never away.

That is also the trap the feature carries. The same link that launches instantly
without a body becomes a `NeedsConfirmation` the moment one is attached, and a
`NeedsConfirmation` suspends the tool call on `AgentCoordinator`'s approval gate
for up to two minutes. The approval card is rendered only by the app's chat
screen, and during device control the app is by definition not the foreground
window, so the user saw a floating card reading "waiting for approval" with
nothing on it to tap while the model saw a tool call that never came back.

Three things close that gap. The coordinator now takes a `bringToForeground`
callback and raises the app's own window when it publishes a local approval; the
floating card says "Approve in Hey Mike" rather than just "waiting"; and
the outcomes are separated — `intent_denied` when the user said no,
`approval_timeout` when nobody answered, `intent_not_approved` when the run
stopped first. A single "denied or expired" told the model nothing it could act
on. `cancelLocalApprovalLocked` also clears the published card, which it did not
before: a stranded card refuses every later approval, local or engine, because
one is already showing.

`bringToForeground` is declared before `adbStatus` in the constructor so that
`AgentCoordinator(...) { adb.status.value }` keeps binding its trailing lambda to
the parameter it always did.

## Per-operation device capability

The advertised tool list is static, because Codex binds it at `thread/start`
and never re-sends it on resume. Availability is therefore a **per-turn
snapshot**, not a smaller tool list.

`DeviceToolGateway.readyTools()` reports what one backend can serve right now:
the ADB gateway answers nothing unless the transport is `CONNECTED`, the
accessibility gateway answers nothing unless its service is bound, and a purely
local gateway (workflows, knowledge) answers everything it declares.
`CompositeDeviceToolGateway` takes the union over live members, so a name whose
first choice is dead but whose fallback is live is still ready — which is what
the fallback chain is for. `DeviceCapabilities.of` splits the advertised surface
into `ready` and `blocked` and never throws: a snapshot is not worth failing a
turn over. `deviceBackendLive()` is kept apart from `readyTools()`: local
gateways are always ready yet cannot operate the screen, so they answer false
and cannot mask "no device backend is live".

A chain that runs out of backends is explained by its most useful refusal. The
ADB gateway refuses with `adb_not_connected` before any device call when the
transport is down, and that and `a11y_unavailable` only mean "switched off";
any other refusal (`no_text_focus`, `key_unsupported`, …) leads the failure.
`act_and_observe` is served by the accessibility gateway as well as ADB.

`AgentCoordinator` builds that snapshot per turn and `CodexEngine` renders it as
the trusted runtime context, listing both sets by name.

This replaces a single `Device tools available: yes/no` derived from the ADB
phase alone, which also emitted "Do not call device tools" whenever the
transport was down. That was too coarse and factually wrong: it disabled the
entire accessibility surface — `read_ui`, `tap`, `type_text`, `open_intent` —
for a reason that had nothing to do with any of them, and it blocked a WhatsApp
deep link that never needed ADB (issue #44). The on-device `AGENTS.md` carried
the same legacy framing ("you operate the device ... over local Wireless ADB")
and is corrected with it.

The setup hub already separates the two as their own checklist rows,
`SetupItem.SCREEN_CONTROL` and `SetupItem.WIRELESS_ADB`, each with its own state
and remedy, so the UI half of the distinction needed no change.

## Native capability API gateway

Common phone data and system entry points do not need to be rebuilt from taps.
`AndroidCapabilityTools` exposes five stable, operation-based tools —
`contacts`, `calendar`, `files_media`, `communications` and `apps_settings` —
with a rich JSON object per call. This keeps the advertised surface small while
letting one policy and one platform seam cover many use cases. The exact
operation and argument keys are allowlisted, strings, rows and serialized
results are bounded, and every reply is a typed JSON envelope whose `ok` value
also controls `ToolResult.success`.

Reads go through Android providers and `PackageManager`. Contact and event
creation, SMS and email, phone dialing, sharing and settings changes only open
the relevant visible system editor or screen; they never save, send, call or
change a setting directly. Media access uses `MediaStore` and the permission
model for the running Android version, including selected-photo access on
Android 14+. File reads and writes outside MediaStore are limited to the
current run workspace. Paths are relative, real-path checked and atomically
replaced; there is no general filesystem or recursive delete operation.

`apps_settings.request_permissions` accepts only the permissions declared for
these capabilities. `RuntimePermissionBroker` asks only for grants that are
still missing and makes one Android `RequestMultiplePermissions` request. The
system dialog is the approval: Hey Mike does not put a second confirmation card
in front of it. Other special access operations only open a setup screen and do
not report the access as granted. Notification support is deliberately status
and setup only — there is no `NotificationListenerService`, so the gateway
cannot read notification content.

The gateway shares the normal run revoke boundary and the visible control
state. Its Android calls live behind `CapabilityPlatform`, while policy and
dispatch are JVM-testable without a phone. Provider behavior, OEM intent
handlers and the permission dialog still need physical-device proof.

## Parallel chats, one phone

Chats run at once, with no cap, as Codex does on a computer: `AgentRuns`
gives each running chat an `AgentCoordinator` of its own, reusing an idle one
before making another, so a new chat started while others work starts straight
away. Each coordinator is unchanged in what it does for one run. What they
share:

- The phone. It has one screen and one foreground app, so two runs cannot both
  drive it: one would open an app and the other's tap would land in it.
  `DeviceLease` gives the device gateway to one run at a time. A run takes it at
  a tool call, not at its start, so a chat that only thinks, or one whose agent
  works on a computer, never waits. A call that leaves the screen alone (files,
  contacts, calendar, a computer, knowledge) holds it for that call only. Once a
  run reads or acts on the screen it keeps the lease to its end: the handles a
  read returns are what its next tap uses, re-arming the gateway clears them,
  and the tap needs the screen it was planned on. A run waiting for it says
  "Waiting for the phone". The gateway's `beginRun`/`revoke` follow the lease,
  so the gateway always serves exactly the run that holds it; making the
  gateways per run would let non-screen calls overlap too.
- The floating card. Only the lease holder (or anyone, when nobody holds it)
  updates or finishes it, so a thinking chat never relabels or closes another
  chat's control card. Its Stop, like the notification's, stops every chat:
  stopping only the chat on the phone would hand the phone to the next one.
- The engine. Events are routed by thread to the coordinator that owns it; a
  coordinator collecting them itself would refuse every other chat's tool calls
  and approvals. A thread nobody owns has its tool calls and approvals refused.
  A stop never closes the engine while another chat runs; a turn that cannot be
  interrupted is then left to finish, with its tool calls refused.

Voice still needs every chat idle. Approvals are per chat; a yes typed into a
chat answers that chat's card. The chat screen shows the open chat's run only.

`SessionRunQueue` holds a turn only while its own chat is still running; any
other turn starts at once.
Sending into the chat that is already running still steers it. FIFO order is durable in a `run_queue` SQLite table,
and a turn is dequeued before it starts, so a process crash cannot replay a
side effect. A queue restored at startup is paused and needs an explicit
Resume, and a local stop pauses the queue rather than releasing the next run at
the user unannounced. Deleting a chat cancels its queued turns.

## Codex retries and turn failures

An app-server `error` notification with `willRetry=true` is a scoped activity
update, not a failed run. Codex keeps the turn and performs its own retries or
transport fallback. A non-retry error keeps `threadId` and `turnId` on the
failure, so only that turn ends; a missing retry flag keeps the legacy terminal
behavior. `AgentRuns` routes both events by thread, and the coordinator checks
the turn before changing state. A broken shared JSON/stdio connection remains
an unscoped failure because every turn on that connection is affected. This
does not repair or hide malformed/truncated JSON or backend usage limits.

## Assistant message segmentation

`item/completed` from the app-server, not only text deltas, drives assistant
message boundaries. Each `agentMessage` item becomes its own stored message, so
commentary before a tool call stays above that tool row and the answer after it
starts a new block. A completed item whose full text differs from the
accumulated deltas replaces them rather than appending, so a full final message
never duplicates its own stream. A turn that ends with no assistant text is
recorded as an explicit "no final reply" message, and an errored or interrupted
turn says so, so a run never ends on a bare activity line. The active status
label is "Working" everywhere, including the overlay.

## Usage and quota

`thread/tokenUsage/updated` and `account/rateLimits/updated` map to a single
`UsageChanged` event; `account/rateLimits/read` fetches the same shape on
demand at sign-in and refresh. Token usage is kept per engine thread and shown
for the visible chat only. A missing `usedPercent` is surfaced as unknown and
never rendered as zero. `RunMetrics` records first-response latency, total run
time, and tool count/time per session.

## Several Codex accounts

Codex keeps an account in exactly one file, `CODEX_HOME/auth.json`; chats,
rollouts, skills and config in CODEX_HOME belong to no account. So switching
account is a file swap, done by `CodexAccountVault` (`:core`) while the
app-server is stopped:

- Saved sign-ins live in `<files>/runtime/accounts/<id>.auth.json` with an
  `accounts.json` index, beside CODEX_HOME and never in it, so Codex only
  ever sees the live one. Files are owner-only and written atomically.
- The live account is captured under the label Codex reports (the email) at
  sign-in, prepare and refresh; the same email is updated, not duplicated.
- Before any swap the live file is copied back into its slot, because Codex
  rewrites it when it refreshes a token.
- *Switch*: stop the app-server, copy the chosen slot to `auth.json`, restart,
  re-read account, quota and models. *Add*: save the live one, remove
  `auth.json`, start the normal device-code sign-in. *Log out* removes the
  live one from the list; *remove* forgets a saved one that is not live.
- A switch refuses while a run or voice is active and pauses the turn queue
  for its duration, so nothing restarts Codex half-way.

Chats are untouched: the app's sessions keep their engine thread IDs and the
next turn resumes that thread from its local rollout under the new account.
Token usage stays per thread; the quota bars are cleared and read again,
because quota is the only thing that follows the account.

### Usage widget

The home screen widget (`app/.../widget/UsageWidget.kt`) shows every saved
account's quota as a still frame of the agent orb. Only the live account's
quota can be read, so each reading is kept by `AccountUsageBook` (`:core`,
`<files>/runtime/accounts/usage.json`, percentages and reset times only)
under the account the vault names as live when it arrives; the vault, not the
UI state, decides, because during a switch the new quota arrives before the UI
catches up. Other accounts show their last reading and its age, and a window
whose reset time has passed since then is drawn empty. The widget is redrawn on
every reading and account change, and by the platform every 30 minutes.

The signed-in Claude account is the last orb, marked "Claude". Claude has one
sign-in and reports its quota only while a Claude process runs, so its row is
always a reading with an age: `LastUsageStore` (`claude-usage.json`) already
keeps the reading, and now also the account's name (`claude-usage.json.account`,
written when the status says signed in, deleted when it says signed out or on
logout) so the widget can name the account without starting Claude. The widget
shapes the raw reading with the same reset rule as the Codex rows
(`readSaved`, `AccountUsageOverview.rows(..., claude)`). When there are more
than four orbs the Claude row keeps its place and the Codex accounts make room.

## Rich chat presentation

Assistant markdown is rendered with Markwon (tables, strikethrough, prism4j
syntax highlighting) inside an `AndroidView`, and images with Coil. The link
resolver opens only `https`, `http`, and `mailto` URLs, so markdown from an
untrusted screen cannot launch a file or intent URL. Generated images and
screenshots are stored inside the session workspace and attached to the
message; a generated image is accepted only from inside that workspace and
under a size cap. Image generation is enabled through the app-server
`features.image_generation` config, and the agent is instructed never to
present a screenshot as generated artwork.

The chat follows the measured end of the latest row while a reply grows or
the keyboard changes the viewport. One coroutine owns programmatic scrolling:
it moves by the pixels added to a visible row and jumps to the end only when a
new last row appears below the viewport. Scrolling up pauses following, and
the jump-to-latest button resumes it. Streaming text does not restart a scroll
animation on each update.

## The chat's own tools: `ask_user` and `show_media`

Decided 2026-10-03. Two tools put something in the conversation instead of
acting on the phone: `ask_user` asks the user one question and waits, and
`show_media` shows pictures and videos in the chat.

- **Served by the coordinator, not a gateway.** `ChatToolGateway` only puts the
  two definitions in the list every engine is given, so a thread on Codex or
  Claude, on the phone or a computer, has them. `AgentCoordinator` answers the
  call itself, before `claimDevice`. Every other tool takes the phone's lease
  for at least one call; a question can wait minutes, and a chat holding the
  lease that long would stop every other chat's tools. It also needs no armed
  gateway: the workspace comes from the run.
- **`ask_user(question, options?)`.** Up to six options; with or without them
  the user can type an answer. The question is `RunState.question`; the chat
  screen pins `QuestionCard` above the composer, as it does an approval,
  because a card in the list scrolls away. An option is one tap. Free text is
  typed in the composer that is already there: `steer` hands what the user
  types to the waiting question before it would steer the turn. Skip tells the
  model the user is not answering. The question and the answer are stored as
  an assistant and a user message, so the history reads as asked and answered.
- **Outside the app it is a notification.** `QuestionNotifier` posts one for
  every waiting question whose card is not on screen (the app is in the
  background, or shows another chat) and cancels it when the question goes
  away. It is a `MessagingStyle` notification with a reply field
  (`RemoteInput`) that offers the options as choices; one or two options also
  get a button each, since Android shows three actions at most. The body
  numbers the options, and `UserQuestion.resolve` reads a reply that is only a
  number as that option, so the reply field works on a phone that does not draw
  the choices. A tap on the notification opens the asking chat. With
  notifications blocked the app comes to the front on that chat instead.
- **It does not raise the app.** An approval brings Hey Mike forward because
  its card exists only there. A question can be answered where the user is, so
  it leaves the app they are in alone.
- **Eight minutes, then the model is told.** Claude Code gives an MCP tool call
  ten minutes (`MCP_TOOL_TIMEOUT`), so the wait ends before that with
  `no_answer` and the instruction to say what it is waiting for and end the
  turn. A skipped question is `skipped`. The wait counts as approval time in
  `RunMetrics`, not tool time. Stop cancels the question with the run.
- **`show_media(files, caption?)`.** Addresses are `copy_file`'s: `chat:`,
  `phone:` or a `content://` uri, `<computer>:`, or a bare path where the
  chat's shell runs, so a computer chat names a file by its path there.
  `CopyFileGateway.chatMedia` returns what the message should hold. A file
  already in the chat's folder is used where it is, and one from the phone's
  storage is copied into `media/` there, under a name no earlier copy has. A
  file on a computer is **not copied**: the computer is asked only that it
  exists and how big it is (`FilePlace.stat`, one SFTP `stat`), and the message
  holds a `RemoteMediaRef`, text of the form `remote:<computer>:<bytes>:<path
  there>` in the ordinary attachment list, so messages and the session store
  did not change. The call returns at once, however big the file, and a file
  that is missing or a computer that cannot be reached is reported to the
  model before anything is shown. The files become one assistant message with
  the caption. A name that is not a picture or a video is refused before any
  byte moves. It is a tool and not a markdown image because the bytes may be on
  another machine: the renderer would have to open SSH to draw a message.
- **Remote media loads when it is looked at.** `RemoteMediaLoader` (`:app`)
  fetches a reference into `cache/remote-media/` the first time a tile needs
  it: a picture up to 20 MB when its message comes on screen, a bigger one or
  any video on a tap. The fetch belongs to the loader, not to the tile, so
  scrolling away does not stop it and two tiles for one file share one
  transfer. Until then the tile says where the file is and how big. The cache
  is keyed by computer, path and size and keeps 400 MB, oldest first; the
  system may clear it too, and a tile then offers the file again. A tile shows
  Retry and the reason when the computer cannot be reached.
  A button in each tile's corner, and in the full-screen view, saves the file
  on the phone: it loads it if needed, then `PhoneStoragePlace` stores it under
  `Pictures/Hey Mike/` or `Movies/Hey Mike/` (Downloads for other files), where
  the gallery finds it. Streaming a video over SFTP was not attempted: the
  platform player needs a seekable source, and a full fetch is simpler and
  works offline afterwards.
- **How media is drawn.** `InlineMedia` replaces `InlineImages`. One file is
  shown whole; several share a two-column grid of square tiles. A video tile
  shows a frame and its length from `MediaMetadataRetriever` with a play mark;
  a tap plays it full screen in the platform `VideoView` with its own
  controls. No player library was added. Formats the phone cannot decode say
  so instead of showing a black box.

## What the agent says on the floating card

The agent's own words reach the card, not only the chat. `ControlOverlay.say`
is a channel of its own, separate from the status label: `AgentCoordinator`
mirrors each assistant segment to it as that segment is written to the session,
while it streams and again when it completes, so the card shows the same text
the chat shows. Keeping it off the label matters — a label is parsed for a tool
name, and a short sentence from the agent reads like one. The card holds the
last line until the agent says something new, so a tool call changes the
headline and leaves the words in place. Engine activity lines ("Working",
"Working in session files", "Updating session files") say which kind of work
started, not what the agent thinks, so they stay headlines and never overwrite
what the agent said.

## Overlay bubble and manual exit

The floating card collapses to a 56dp bubble that keeps the status colour, can
be dragged, and long-presses to stop; expanding restores the card. Collapse
state resets when a new run starts. The overlay is no longer shown at run
start: a read-only chat turn never takes over the screen, and the first device
action is what checks overlay permission and shows the card. Leaving the app
manually during a read-only turn therefore shows nothing and does not reopen
the app on completion.

## Voice audio routing

`CommunicationAudioRoute` owns only the route this voice session selected. On
API 31+ it uses `setCommunicationDevice` over `availableCommunicationDevices`,
ranking wired and USB headsets first, then Bluetooth/BLE, then built-in
speaker, and it keeps an external device the user picked in platform UI. Below
31 it falls back to Bluetooth SCO and speakerphone flags. An
`AudioDeviceCallback` re-selects when a headset is plugged or unplugged
mid-call. On release it restores the previous device only if the session's own
device is still selected, so a route the user changed during the call is left
alone. Voice now fails loudly when audio focus is denied instead of talking
into a device it does not own, and losing focus stops the session. Stop
silences capture and playback before any network acknowledgement, and a second
stop while stopping is a no-op.

## App launch

`open_app` resolves a normal launcher intent with `am start` instead of
`monkey`. Monkey is a fuzzing harness whose cleanup changes device state, which
is the same class of hidden side effect as the `uiautomator` rotation thaw. An
explicit activity must belong to the requested package. Launch success requires
both a zero exit code and no `Error`/`Exception` line in the output, so a
failed launch is never reported as "Opened".

## Combined act-and-observe

`act_and_observe` performs one already-decided action and returns a fresh
observation in the same tool call, removing a model round trip per step. It is
never a batch: a failed action returns immediately and is not retried or
observed. When the action commits but the observation fails, the result says
`actionCompleted: true` with `observationSucceeded: false`, so the model cannot
mistake a lost observation for a lost action and repeat a side effect.

## One observation model, many backends

`UiNode`, `UiObservation` and `UiObservationSerializer` live in `:core` so every
device backend reduces to one node model and renders through one serialiser.
The uiautomator XML dump and, later, the accessibility node tree therefore
produce the same envelope, the same `clickableAncestor` rule and the same typed
failure taxonomy, and the seeded app cards keep working whichever backend
answered. The node *lists* still differ — different traversal roots and
inclusion rules — which is what the `source` field records.

`ObservationState` is shared by every gateway rather than owned by one. A
per-gateway revision counter would make `revision` jump backwards when a call
falls through from one backend to another, and `unchangedSinceRevision` could
then name a revision produced by a different backend holding a different node
set. For the same reason `ObservationFingerprint` carries a `backend`, and
unchanged-suppression only fires within one backend.

Two privacy rules live in the serialiser so no backend can forget them: a node
marked `password` never emits its text, and every emitted `text` and
`contentDescription` passes through `SecretRedactor`. On-screen text leaves the
device, so a visible token is redacted at the point of emission rather than at
each call site.

## Routing between device backends

`CompositeDeviceToolGateway` sends each tool to the first backend that declares
it and falls through to the next only when that backend raises
`ToolNotServiceable` — which is a promise that nothing was dispatched. Any
other exception propagates, because an action that may already have committed
must never be retried on a second backend.

The advertised tool surface is deliberately static. Codex binds the tool list at
`thread/start` and never re-sends it on `thread/resume`, so a surface that
shrank when a backend went away would leave every open thread holding a list
that no longer matches reality with no way to correct it. Availability is
reported at invoke time instead, as a typed `backend_unavailable` failure
carrying a remedy and each backend's reason.

`device_status` is answered by the composite, since it is the only object that
sees every backend.

## Accessibility control path

`:a11y` hosts an `AccessibilityService` that observes and drives the screen
in-process, so the agent works with Wireless Debugging off. It is the preferred
backend; ADB remains for shell, `logcat`, `dumpsys`, file transfer, APK
installs and anything privileged, and covers whatever accessibility cannot do.

The backend implements the *existing* tool names instead of introducing new
ones. Codex binds the tool list at `thread/start` and never re-sends it on
`thread/resume`, so every chat opened before this shipped still asks for
`read_ui` and `tap`. Naming the accessibility versions differently would leave
those chats dead the moment ADB is unavailable. The `source` field distinguishes
`accessibility` from `uiautomator`.

`read_ui` waits for the screen to settle before traversing and reports
`stable:false` when it did not, which is what that previously-hardcoded field
was always meant to carry.

The system owns the service instance, so the gateway resolves it through
`A11yServiceHandle` on every call rather than holding one. That also makes "the
user just switched it off" an ordinary state instead of a crash. `detach` is
identity-compared so a late `onDestroy` from a replaced instance cannot clear
the live one.

### Keeping the agent off its own UI

Four layers, because tree filtering alone is not enough. The traversal drops
windows and nodes belonging to our own package and anything not visible to the
user, which covers the overlay, the chat UI and the agent IME — the overlay
window stays in the window manager even when hidden from screenshots, so
filtering is the only thing that removes it. But a coordinate gesture hits
whatever is topmost regardless of the tree, so both backends now call
`overlay.avoidTouch` before a tap or swipe, and the accessibility path refuses
a point still inside one of our windows afterwards. `avoidTouch` existed but
had no callers, so the ADB path carried the same defect.

### Node addressing

`tap_node`, `set_text`, `scroll_node` and `wait_for_change` act on a node
rather than a coordinate. Every one requires both a `nodeId` and the
`observationId` it came from: a node id alone is meaningless once the screen
has been re-read, and quietly acting on a stale one is how an agent taps the
wrong thing. Handles are held for the current observation only and dropped on
revoke. An `unchanged` reply keeps the earlier observation id valid as well,
since that reply explicitly tells the model to reuse those nodes.

`set_text` uses `ACTION_SET_TEXT`, replacing five shell commands and a global
IME switch with one call that leaves the user's keyboard alone. Some Compose
and chat composers accept the action and keep their old value, so the result
reports `verified` from a read-back instead of assuming the write took. A
successful refresh with null node text or a displayed hint counts as empty
after a clear. If the handle still reads the old value, the gateway waits for
the UI to settle and finds the same editable field in a fresh tree without
changing the observation ids available to the model. A missing field does not
count as verification.

### What the service does when no run is active

It runs for as long as the user leaves it enabled, which is most of the phone's
uptime. Outside a run it never reads event text, retains a node or tree, logs
anything derived from an event, or writes to disk; `onAccessibilityEvent` only
stamps a timestamp. A `runActive` flag is pushed on `beginRun` and `revoke`,
and re-pushed at run start so an instance that reconnected after a self-update
is not left idle-gated.

`declaredEnabled && !connected` is the Android 13+ restricted-setting signature
for a sideloaded build. No API reports it, so that divergence is the detector,
and it gets its own message and a route to App info.

## Capture without ADB

`screenshot` is served by the accessibility backend through
`AccessibilityService.takeScreenshot`, so the Vision fallback survives with
Wireless Debugging off. It matters because screenshot is the tier below the
node tree: games, canvas surfaces and unexposed WebViews expose nothing to
`read_ui`, and before this the agent could not see them at all without ADB.

The framework hands back a `HardwareBuffer` the caller owns. It is closed in a
`finally` on every path, including the one where the run was stopped while the
capture was in flight — that callback still fires, and nothing downstream would
ever release it.

Rate limiting, an unavailable display and a missing capture permission all
raise `ToolNotServiceable`, so the composite falls through to ADB. A
`FLAG_SECURE` window is typed too, but says plainly that no backend will
succeed: `screencap` returns a black frame there, and a black frame presented
as the screen is worse than an honest refusal.

The floating controls stay on screen through a capture. On Android 14+ the
screenshot is assembled from `takeScreenshotOfWindow`, one window at a time,
bottom to top, leaving out our own overlay window (matched by package and by
its `AndroidAgentControl` title). Only on-screen windows are captured, so the
wallpaper behind a launcher comes out black. A system window that refuses is
left out; an app window that refuses fails the per-window capture. On older
Android, or when the per-window capture fails for any reason other than a
secure window, the gateway steps the card aside itself for the moment of a
whole-display `takeScreenshot`. `hidesOverlayDuringCapture` is therefore false
for every accessibility tool: window filtering keeps our card out of `read_ui`,
and the screenshot handles its own capture. ADB's `screencap` photographs every
window, so it steps the card aside around the capture itself — the coordinator
only does that when ADB is asked first, not after a fall-through.

## Reaching a destination directly

`resolve_intent` and `open_intent` try an intent or deep link before the agent
walks there through the UI. Both run in-process, so they need no ADB.

Every intent passes `IntentPolicy` in `:core` first, which is pure JVM so the
security boundary is covered by unit tests rather than only by running the app.
The policy lowercases only the URI scheme before resolution and launch, since
Android intent filters match schemes case-sensitively. The raw scheme still
goes through the blocked-scheme check first.
Three rules carry most of the weight:

- **Schemes are blocked structurally, not allowlisted.** A positive allowlist
  was the first design and it does not survive contact with the feature: app
  deep links use private schemes (`waze:`, `spotify:`, `tg:`) and enumerating
  them would block exactly the case the layer exists for. What is refused is
  anything that reads local data (`file`, `content`, `android_resource`),
  injects a component (`intent`, `android-app`), or executes (`javascript`,
  `data`, `jar`). The scheme is read off the raw text before parsing, because
  `android_resource` is not a valid URI and would otherwise be reported as
  merely malformed.
- **Actions are blocked by a short list, not allowlisted.** The action
  allowlist failed the way the scheme allowlist did: a timer, a calendar insert
  or a settings screen each needed a code change before the agent could reach
  it. Any well-formed action is allowed except the few that commit with no
  screen to back out of — `CALL` and its variants (`DIAL` reaches the same
  screen and leaves the press to the user), install/uninstall/delete, and
  factory reset. `VIEW` still needs a uri; any other action may stand alone.
- **Extras are plain values.** `open_intent` takes `extras` as strings,
  integers (an int when it fits, since `getIntExtra` ignores a long), longs,
  doubles, booleans and string lists, with an explicit `{"type","value"}` for
  the rest. There is no Uri, Parcelable or Bundle, so no model-built intent can
  hand another app a grant; a string extra naming a blocked scheme is refused
  too, since many apps parse one as a uri. An `amount` extra asks first, like
  an `amount` query key. Nothing here names a feature: which extras a timer
  takes is knowledge in a skill or a workflow definition.
- **Sending and payment ask first.** A messaging scheme, `SENDTO`, payment
  amount, or prefilled payload pauses the exact tool call on an app-owned
  approval. The card is bound to the current run, turn, action, URI and optional
  package, and the permission is consumed once. The model has no confirmation
  flag; denial, timeout, Stop, or a stale request id prevents dispatch.

`QUERY_ALL_PACKAGES` is required: on API 30+ `queryIntentActivities` returns
nothing for undeclared packages, so both tools would report "nothing handles
this" for apps that are installed. The build is sideloaded, so there is no Play
policy concern, but it widens what the app can see.

## What the agent remembers between chats

`KnowledgeStore` keeps what the agent worked out about an app under
`<homeDirectory>/knowledge/<package>.json`. That directory is global across
chats and is the one place `WorkspaceSeeder` does not rewrite — the session
workspace is reseeded on every access and the bundled skills are force-replaced
on every app start, so anything written there is erased.

The stable key is `(package, resourceId | contentDescription)` and never a
coordinate; a bounds centre is invalidated by any re-render. A coordinate-shaped
selector is refused on write rather than accepted and later mistrusted, because
storing one would quietly undo the reason the store exists.

Every record carries `lastVerified`. After two months it is reported as
`stale:true` — a hint to check, not an expiry, since a stale selector is still
the best guess available, it just is not evidence any more.

Recall is a tool (`recall_capability`) rather than a block injected into every
prompt: the store grows without limit and the prompt does not, so the model
asks about the package it is driving and pays for nothing else. Writes go
through `remember_capability`, which validates the record instead of trusting a
free-form file write.

`KnowledgeToolGateway` rides in the composite because that is the one place a
tool name reaches the model without new plumbing, not because remembering is a
device action — it touches no device, and `needsControl` is false for both
tools.

## Why a 502 from the tunnel is now explained

The proxy the app-server talks through is ours, injected deliberately because
the musl build cannot resolve DNS under the app UID. When it fails the
app-server reports only `HTTP CONNECT failed with status 502`, which reads as
if some external proxy were at fault.

That 502 covers two faults with two different remedies: the name did not
resolve (`dns`), or it resolved and the connection was refused (`connection`).
`LocalhostConnectProxy` already recorded which, and `recentProxyEvents()` had
no callers, so the distinction was collected and thrown away. `ProxyDiagnostics`
turns the most recent entry into one sentence, and the Runtime settings section
shows it.

Last entry rather than first: the buffer spans the process lifetime, and an
error from twenty minutes ago says nothing about the turn that just failed. A
host rejected by the allowlist surfaces as **403**, not 502, so a 502 is never
a sign of a misconfigured host list. Only the category reaches the UI — the
buffer holds metadata, never tunnel bytes or credentials.

## Running a known sequence without a turn per step

`run_workflow` executes a declared list of steps locally. `save_workflow` and
`list_workflows` keep sequences in `<homeDirectory>/workflows/<package>.json`,
one file per package so a package's selectors and its workflows age out
together when the app is redesigned.

Four constraints shape it.

**The allowed step set is closed.** Only navigation, gesture, text and
observation tools may appear in a workflow. A workflow that could run any tool
would be a second, weaker agent loop with none of the coordinator's
guarantees - no approval routing, no per-tool control banner, no revoke
semantics of its own. `shell`, `install_apk`, the knowledge tools and
`run_workflow` itself are all refused before anything runs.

**A committed step is never re-run because a later one failed.** This is
`act_and_observe`'s rule extended rather than changed. The result carries the
completed prefix, the failing index, and `lastCommittedStepMayHaveRun` - true
for the tools that change something - so the model resumes from where it
stopped instead of replaying a send or a tap.

**Every step is bounded and so is the whole run.** The coordinator holds a
process-wide lock for the duration of one tool call and budgets two seconds
for cancel, so a workflow that ran for minutes would make Stop feel broken.
The total budget is clamped to 90 seconds.

**Revoke aborts between steps.** `isRevoked` is consulted before each one, and
a stopped workflow reports what had already run rather than a bare failure.

The engine dispatches steps straight at the composite rather than back through
the coordinator, so it does not re-enter `toolLock`. The router is resolved per
call rather than captured, both because the composite contains this gateway -
which would otherwise be a construction cycle - and so a step reaches whichever
backend currently serves that tool.

## Why `run_workflow` was not enough, and what `workflow_runner` does instead

`run_workflow` replays a literal list of tool calls. That is exactly why it only
ever worked on the screen it was recorded on: a saved `tap(x=504,y=200)` misses
as soon as a row moves, there is no way to say "the Search field" rather than a
coordinate, no way to use what `read_ui` just returned in the next step, and no
way to tell a step that landed from one that did not. `nodeId` and
`observationId` cannot be stored either - they are minted per observation and
the backend refuses a stale one - so the one addressing scheme that does survive
a layout change was unreachable from a saved sequence.

`workflow_runner` executes a **declarative definition** instead. A definition
says what each step means; the runner works out how, against the screen in front
of it:

1. read the screen,
2. find the node the step describes,
3. act on it - by node handle where a backend serves one, at its current centre
   where none does,
4. wait for the screen to settle,
5. check the step's own condition before calling the step done.

`WorkflowDefinition` is the file format, `WorkflowLibrary` is where definitions
live (`<homeDirectory>/workflows/definitions/<id>.json`, beside `WorkflowStore`'s
per-package step lists), and `WorkflowRunner` is the execution engine.
`workflow_runner(mode="save", definition={...})` validates and writes that
definition without running it. `mode="list"` shows the old literal lists in a
separate `legacyWorkflows` field, and a legacy-only name returns
`workflow_legacy_format` rather than looking like a missing definition. The
old `save_workflow` / `run_workflow` path remains readable for existing chats.
New definitions are size-checked before writing so every saved file can be
loaded by the library's existing read limit.
When a caller supplies `package`, lookup stays within that package. An id from
another app is returned as `workflow_not_found` before any device action.

Seven decisions carry the design.

**Selector criteria are scored, not ANDed.** A target naming both
`resourceId` and the visible label still resolves after the app renames the id,
because the label alone identifies the node. ANDing them would make every extra
detail in a definition another way for it to break - the opposite of what
writing them down is for. An exact match scores above a substring, a local id
(`switchWidget`) matches a fully qualified one, and `exact:true` opts back in to
strict matching where a partial hit would be wrong.

**Nothing positional is storable.** There is no coordinate field and no handle
field in the format, so a definition cannot carry the thing that made the old
mechanism brittle. Coordinates exist only as a fallback computed from the
observation taken moments earlier, for a backend with no node addressing.

**Verification is part of a step, not an afterthought.** A step with no `verify`
is reported `verification:"not_requested"` and `verified:false`; the success
ledger counts those steps separately. An action was dispatched, but its desired
effect was not checked. Without an explicit condition the runner would be a macro player,
reporting success for a tap that landed on a disabled control and then running
every later step against the wrong screen. `checked` is what made adding
`checkable`/`checked` to `UiNode` worth doing across both backends: "the switch
is off" and "this is not a switch" are different answers, and a toggle workflow
cannot be verified without telling them apart.

**Resuming is first-class, and `skipIfVerified` is what makes it safe.** A
failure names the step, the completed prefix, whether that step may already have
run, what was on screen instead, and the exact arguments to resume from. Because
resuming re-runs the failing step, a toggle step marked `skipIfVerified` checks
its condition *before* acting: re-running "turn it on" on something already on
turns it off. Running out of the time budget is therefore a recoverable outcome
rather than a lost run, which is why the ceiling can stay short.

**API calls are allowed explicitly, not opened generally.** A declarative step
may use `action: "call"` with one registered tool and a JSON `arguments` object.
The app registers only the five native capability tools. Shell, package install
and every workflow tool are blocked even if wiring tries to register them, so a
workflow cannot recurse or become a weaker agent loop. The called tool still
owns its normal Android permission or approval path; the runner does not add a
duplicate confirmation. An author can still mark the whole step
`requiresConfirmation` when the product flow needs an extra explicit user gate.

**Call outputs are typed, bounded resume state.** `output` captures a successful
tool reply and later JSON may refer to it as `{{outputs.name}}` or a nested
object/array path. A whole-value reference keeps its JSON type. Missing paths,
oversized values and too many bindings fail before another call is dispatched.
Failure replies carry the captured outputs in the resume arguments, so a
completed call is not repeated merely to rebuild context. Whether a failed call
may have committed is classified from its resolved `operation`; known reads are
reported as read-only and unknown operations stay conservative.

**A sensitive step stops and asks, and refuses when nothing can ask.**
`requiresConfirmation` routes through `AgentCoordinator.authorizeWorkflowStep`,
the same card and the same spoken "yes" as a send, and the same reason: the
runner drives a whole sequence inside one tool call, so nothing else gets a
chance to stop it. Unlike a send there is no standing grant - a step is approved
for the run in front of the user, never for every later run. Asking raises Hey
Mike over the app being driven, so the question comes *before* anything is
resolved (a handle read first would be stale by the time the user answered) and
the app is brought back to the front afterwards. A host that wired no approval
path gets `confirmation_unavailable` and the step does not run: a gate that
disappears when unwired is not a gate.

Recording is authoring, not capture. `AgentAccessibilityService` deliberately
does not read the events it receives - the text a user types is not ours to look
at - and a passive recorder of the user's own taps would reverse that decision
to build a feature. A workflow is written instead: worked out once with the
device tools, then saved as a definition, which is also the only form that can
carry verification conditions and confirmation flags at all.

## `act_plan`: one observation, one call

`read_ui` answers more than the question that was asked. A chat screen returns
the message field, the Send button and the row that names the recipient in the
same reply - everything a send needs. The loop then spent a model turn per
action anyway: focus, turn, type, turn, press. Three turns, all of them
re-deriving what the first observation already said.

`act_plan` takes that sequence as one call. It is `run_workflow`'s ergonomics -
inline steps, nothing to save first - with `workflow_runner`'s execution: the
same `WorkflowRunner`, so each step is resolved against the screen in front of
*that* step, settled, and checked against its own `verify` before the next one
runs. `WorkflowDefinition.adHoc` parses the inline steps through the same
`parse` a definition file goes through, so a plan cannot express a step a file
could not, and inherits its limits and refusals.

The final screen observation is useful for the next decision, but it does not
retroactively verify a step that declared no condition. Such steps keep
`verification:"not_requested"` in the ledger.

Four decisions make it safe to plan ahead at all.

**A plan carries labels, never ids.** A `nodeId` belongs to one observation and
the runner re-reads the screen before every step, so ids would be stale by the
second one - the reason a definition file cannot store them either. A plan that
names a target by `nodeId`, `observationId` or `bounds` is refused with the
fields to use instead (`plan_positional`), rather than having them dropped
quietly: a silently ignored id leaves the model believing it named the target.
Resolving by label is also what makes planning ahead sound - the keyboard
opening between step one and step two moves every coordinate and changes no
label.

**Eight steps.** Enough for focus-type-send, a dialog, a search and its result;
short of a sequence whose later steps are about screens the model has not read.
Past that the refusal points at a workflow definition, which can be read, fixed
and reused instead of re-derived in each chat.

**The reply ends on the screen it landed on.** The runner's own reads never
reach the model, so a plan that saved three round trips would cost one back to
find out where it ended up. That final read is forced: unchanged-suppression
answers "the same as revision N", and N is a node list the model never saw.

**A failure resumes by the caller's own steps.** There is no library entry to
name, so `Options.adHocTool` makes the resume block name `act_plan` and a
`startAt`: the model resends the same steps and the committed prefix is skipped.
Everything else is the workflow contract unchanged - the failing step, what
already ran, whether it may have half-happened.

## A resumed thread gets the tools this version has

`thread/start` sends `dynamicTools`; `thread/resume` did not. A thread binds the
tool list it was created with, so a chat opened before an app update could never
call a tool that update added - while the per-turn runtime snapshot, built from
the live gateway, told the model it could. The model then called a tool its own
thread had never been given. That reached a phone with `act_plan`, and the
device-automation skill's warning that some tools "exist only in chats started
after they shipped" was the symptom being documented rather than fixed.

`resumeSessionParams` now takes the tool list, and `openSession` resumes with it
first and retries the plain resume before falling back to a fresh thread. The
order matters: a server that will not accept the parameter costs one extra round
trip, while the fallback it would otherwise hit - starting a new thread - costs
the user the conversation they were in. Null omits the key rather than sending
an empty array, because an empty array reads as "this thread has no tools".

## Where a run's time went

### Exact visible session trace

`LocalSessionStore` appends `session-trace.jsonl` to each chat workspace. The
chat's Files sheet can open or share it. Each line has a timestamp and one
visible event: user prompt, assistant message, tool call with full arguments,
or tool result with full text, success flag and attachment paths. Call and
result share a request id, so the order and the assistant's next visible
message can be reconstructed without hidden reasoning. The chat bubble still
shows a short tool preview; the trace is the complete local record.
It starts recording when this app version runs a turn; older turns cannot be
reconstructed from the shortened chat bubbles.

Screenshot/image results are stored as files under `trace-artifacts/` in the
same private workspace and linked from the result line. A decode or size
failure is marked in the line. Trace write failures produce one visible
system message during a run, while the tool itself can continue. Deleting the
chat removes its workspace and trace together.

`RunMetrics` existed and only an instrumented test ever read it. A run that felt
slow is the one someone asks about, so at the end of every run that touched the
phone the coordinator writes one system line into the chat: total, thinking, time
on the phone across how many calls, and time waiting for a person.

Two decisions make the numbers honest.

**Waiting for a person is its own bucket.** A send approval is raised *inside*
the tool call that asks for it, so counting it as tool time reported twenty
seconds of "the phone" for twenty seconds of somebody deciding whether to send a
message. `awaitLocalApproval` accumulates that wait, and the tool dispatch
subtracts the part of it that happened inside its own call - so tool time is
device time, approval time is human time, and thinking is what is left (model
turns and engine overhead). Three buckets, three different fixes: fewer turns, a
faster path on screen, or nothing at all.

**The clock is injected.** `AgentCoordinator` takes `nowNanos`, for the same
reason `WorkflowRunner` does: a summary claiming to say where time went is worth
what it can be tested against, and a test driving a virtual clock cannot verify a
real one. The tests advance virtual time and assert the split exactly.

A run that called no tool gets no line. One bucket is not a breakdown, and a
line under every short answer teaches the user to skip it.

**A tool schema that says `array` says nothing.** The first device run failed
before touching the phone: `steps` was advertised as a bare
`{"type":"array"}`, a client with no `items` renders that as an array of
strings, and the agent reasonably sent each step as quoted JSON - which the
validator refused. `steps.items` now spells the step object out, including the
`action` enum and the target fields a `read_ui` reply carries, and the same is
done for `run_workflow` and `save_workflow`. The sweep that followed found the
same defect in three more places: `remember_capability`'s `fallbacks`, and
`automation_rule`'s `places`, `deviceState` and `rule` - the last of which
described a whole rule with no `type` and no fields at all. `ToolSchemaAudit`
is now the shared definition of that defect and every gateway's tests run it
over everything they advertise, so the next tool cannot reintroduce it: an
array with no `items`, an object that neither names its keys nor declares them
open, a property with no type and nothing else that says what it takes, or a
`required` name that is not a property. Two things back that up: a quoted
step is parsed rather than refused, the way a quoted number is already accepted
as a number, and the example in the refusal, the tool description and the test
is one shared constant that the test executes - an example that drifts from the
validator is how a caller writes a call that cannot run.

**A step says where its time went.** One `elapsedMs` per step reports that a
step was slow; it cannot say whether the element took finding, the app took
acting, or the screen never settled - and those have different fixes. Each
record carries `timing` split into `resolve`, `act`, `settle` and `verify`
(phases under 50ms are left out, so a fast step stays one line), and a failure
carries `failedStepTiming` for the step the ledger does not otherwise hold. This
came from a real post-with-media run on X that took minutes: the report could
say it was slow, not which part was.

**A `verify` timeout is the caller's estimate of the work.** It was capped at
20s, a screen transition with room to spare, and `millis()` clamps rather than
refuses - so a step that said "this import takes about 45 seconds" waited 20 and
reported the condition false while it was still on its way. The ceiling is now
60s, inside `MAX_TOTAL_MS` with room for the rest of the run, and Stop stays
responsive because the poll loop checks revoke every cycle. Polling stops the
moment the condition holds, so a generous estimate costs nothing when the work
is quick; that is what makes an estimate the right thing to ask the model for.

Nothing about approvals changes. A tap that lands on Send inside a plan reaches
`tap_node` on the accessibility backend and hits `SendGuard` there, so it asks
with the same card and the same spoken "yes" as a send the model dispatched on
its own. A plan is not a way around a gate, because the plan never replaces the
tool that owns it.


## Connected Apps: the surface exists, the answer does not

`.codex-work/runtime/probe_apps.py` probes a running on-phone app-server for
`app/list`, `app/installed`, `plugin/list`, `plugin/installed`,
`mcpServerStatus/list` and `experimentalFeature/list`.

All six answer on the shipped build (rust-v0.153.4) rather than erroring, so
the surface is present. `experimentalFeature/list` returns 135 flags, which is
the most substantial thing there.

But the probe runs against a scratch `CODEX_HOME` under `/data/local/tmp`,
which has no `auth.json` - it is anonymous, and an anonymous server returns
zero connectors regardless of what the account has. So the zeroes it reports
are **not** an answer to "does this account have Gmail or GitHub connected".
The probe now calls `account/read` first and says so in its own output, because
reading those zeroes as a finding is the obvious mistake and it is worth
preventing rather than documenting.

Answering the question properly needs an authenticated session. Copying the
app's `auth.json` into `/data/local/tmp` would do it and is the wrong trade:
that directory is world-readable on the device. The right route is to make the
calls from inside the app, where `CodexEngine` already holds a signed-in
session, behind a debug-only path.

## Screen awake during active conversations

`KeepAwakePolicy` treats a typed run or realtime voice session as active from
its starting phase through `STOPPING`, and releases the requirement only at
`IDLE` or `ERROR`. While `MainActivity` is visible, it applies
`FLAG_KEEP_SCREEN_ON`, which is the platform-managed foreground path.

Android allows an activity with that flag to turn its screen off when the app
goes to the background, and the flag is not a service mechanism. The existing
foreground `AgentService` therefore owns a separate screen wake lock for the
same policy while the activity is hidden, including before the floating
overlay is created. `ConversationKeepAwakeController` makes acquire/release
transitions idempotent, and service destruction always releases the lock. The
overlay does not own a screen-on flag. No display-timeout or other system
setting is written; the manifest declares only the normal `WAKE_LOCK`
permission required by the lock.

The screen wake lock uses the deprecated `SCREEN_BRIGHT_WAKE_LOCK` level because
the requested background screen guarantee has no equivalent activity flag.
Physical verification on the approved Nothing A059 is still required to check
screen behavior, rotation, power-button interaction, terminal release, and
battery/OS policy behavior.

## Digital assistant

`:app/assist` registers a `VoiceInteractionService`, so the user can pick Hey
Mike as the digital assistant and reach voice mode by holding the power button.
No app can take `ROLE_ASSISTANT` for itself, so Settings only reads the holder
and opens the system picker.

- The session draws nothing. It starts `MainActivity` with
  `AssistLaunch.EXTRA_START_VOICE` and `CLEAR_TOP | SINGLE_TOP`, so a press lands
  on the one chat screen (via `onNewIntent` when it is open). It uses a plain
  `startActivity` first: `startAssistantActivity` creates a second copy in an
  assistant task, whose view model does not own the voice conversation.
- `AgentViewModel.startAssistantVoice` never ends a conversation, waits for the
  runtime and the saved chat on a cold start, and opens a new chat unless the
  current one is empty.
- `AssistEntryActivity` handles `ACTION_ASSIST` for OEM paths. It has an empty
  `taskAffinity`: in the app's task, the press that cold-started the app became
  the task root, and later presses only brought the task forward.
- `AgentRecognitionService` exists because the `<voice-interaction-service>`
  parser rejects a registration without one. It fails every request. No hotword
  detector is opened: that needs a privileged app.
- Keyguard launch is off, because Mike controls the phone.
- Role status reads the `voice_interaction_service` secure setting before
  `RoleManager`, since the two have disagreed on hardware.
- A press shows the voice screen at once. `voiceSummon` carries the setup
  status ("Waking Mike", "Opening your conversation") and `shownVoice()`
  draws it as a connecting call until the real call is active. The sphere then
  flies in from the right edge, where the power button usually is, behind a glow
  and two rings. End voice during setup cancels the press.

## Standing rules: when this happens, and that is true, do this

A workflow answers "how do I do this on this phone". A rule answers "when
should it happen, and who has to be awake for it". They are separate files
because a rule that inlined its steps would be a workflow with a clock bolted
on, and every later improvement to `WorkflowRunner` would stop at the
automation boundary. A rule *names* a workflow; it never contains one.

The format is `when` / `if` / `then`, one JSON file per rule under
`<homeDirectory>/automations/<id>.json`, beside the workflow definitions and
surviving `WorkspaceSeeder` for the same reason `KnowledgeStore` does.

```json
{"id": "dad-after-seven",
 "when": {"type": "notification", "package": "com.whatsapp", "from": "Dad"},
 "if":   [{"type": "time_between", "after": "19:00", "before": "07:00"}],
 "then": [{"type": "agent_turn", "prompt": "Tell {{notification.title}} I can't talk."}]}
```

`AutomationRule` is the format, `AutomationLibrary` is where rules live,
`AutomationEvaluator` decides, `AutomationJournal` remembers what already fired
and `AutomationToolGateway` exposes all of it to the model as one tool,
`automation_rule`, with modes create/list/describe/enable/disable/delete/test.

Six decisions carry the design.

**A rule says who has to be awake, and does not get to lie about it.**
`AutomationAttention` is `none`, `model` or `user`, and it is *derived* from
the actions rather than declared: `run_workflow`, `open_intent` and `notify`
need nobody, `agent_turn` spends a thinking turn, and `voice_call` and `ask`
need the person. The host reads this before it fires anything, so "post at
19:00" never wakes a voice call, and a rule that wants to talk is held while
the phone is locked instead of talking to a pocket. Held, not dropped — the
trigger really did happen, and `Skip.retryable` separates "not now" from
"not this".

**What a rule may read is what it says it reads.** `AgentAccessibilityService`
deliberately reads none of the events it receives, and a notification trigger
cannot keep that promise whole — so it is narrowed instead of abandoned. The
whole event is matched *here, on the phone*. What leaves is only the fields the
rule's own actions interpolate (`AutomationRule.exportedFields`). A rule that
matches on the body of a message and writes only `{{notification.title}}` never
sends that body anywhere, and the `create` reply lists the exported fields back
so the model can tell the user exactly what will be transmitted. A
`notification` trigger must choose its package scope explicitly: a named app,
or `package:"*"` when the user asks for all apps. An omitted, empty or null
package is still refused, so an incomplete rule cannot silently widen access.
The wildcard round-trips as `"*"`, matches notification events from any actual
package, and reads as "Any app" in the rules panel. Sender filters, conditions,
exported fields, attention gates and rate limits apply to either scope.

**The clock is verified, not trusted.** Android coalesces, delays and batches
alarms. Whether a schedule is due is re-checked against the event's own
timestamp, so a process woken early for something else cannot post to Facebook
at 18:52. `nextRunAt` is what the host sets the alarm for, seconds dropped so a
rule rescheduled from its own firing does not drift.

**A slot is owed, not a minute.** The first version matched the exact minute
(`isDue`), which made every late alarm a silently missed day: Doze, an inexact
alarm without the exact-alarm grant, a phone off at 19:00 and booted at 19:08.
`AutomationSchedule.dueSlot` replaces it on the live path and needs the history,
which is why the evaluator decides it rather than `AutomationTrigger.matches`:
an `at` schedule owes its latest slot until it has run for it or
`guard.validForMs` (30 minutes by default) has passed — the same line a queued
turn is dropped at, because running after it is the wrong action rather than a
late one. A slot before the rule's file was written is never owed. An interval
is owed once `everyMinutes` have passed since its last run (or since it was
written), and `nextRunAt(after, lastFiredAt, savedAt)` counts from there too.
Counting from "now" had two bugs at once: every re-arm — and the host re-arms
after every event, screen-on included — pushed an interval out again, and any
clock wake-up for any rule fired every interval rule.

Because a served slot is in the journal, catching up is safe to do often: the
host checks the clock at start (which is also boot) and when the phone is
unlocked, so a rule held for "needs you" runs when you can answer, still inside
its window, and never twice. Evaluation happens inside the host's run lock for
the same reason: two events landing together used to both read the history
before either run recorded it.

**Changing a rule is `update`, not a second `create`.** `create` refuses an id
that exists unless `replace:true`, because a rule silently overwritten by a new
one with the same id is a working rule gone. `update` merges only the named
keys onto the saved definition (each replaces the old value whole, `null`
removes it) and re-parses the result like a new rule, so an edit can never save
what `create` would refuse, and a failed edit leaves the file untouched. The id
cannot change — the journal, and so the cooldown and daily count, is keyed by
it. Every mode that changes a rule (update, enable, disable, delete, run) takes
the exact id: the forgiving lookup that is right for `describe` resolved
"delete morning" to "morning-news" when that was the only near match. A near
miss is answered with `rule_id_inexact` and the likely id, never acted on.
Every change calls the gateway's `onChanged`, which the app wires to
`AutomationHost.rearm()`; without it a rule the agent wrote was on disk but its
alarm was not set until some unrelated firing or restart.

**Every rule is reported, fired or not.** A rule that silently does nothing is
this feature's characteristic failure — the person wrote it, it looks right,
and nothing happens at 19:00. So `evaluate` returns an outcome per rule, and a
skip names the clause: `condition_failed` with "the rule needs the time to be
between 19:00 and 07:00", not a debug log. The guard is checked *after* the
conditions so the explanation names the real reason: a cooldown passes on its
own, an hour does not.

**Deciding and doing are separate, and deciding writes nothing.**
`AutomationEvaluator` touches no file and records no fire, which is what makes
`mode:"test"` a real dry run that can be called as often as the model likes
without consuming a rule's daily quota — and what makes the live path and the
dry run give the same answer. `AutomationRunner` owns the doing, and it records
the fire **before the first action, not after**: the same rule `SessionRunQueue`
already follows when it dequeues a turn before starting it. A crash between
acting and recording would let the rule post the same thing again on the next
trigger, and for a standing rule a double post is worse than a missed one. The
cooldown exists to stop runaway repeats, so it is armed by the attempt rather
than by its success. `AutomationGuard` (a cooldown, a daily ceiling and a
deadline, all clamped on parse) exists because the triggers that matter most are
the noisy ones: one busy group chat is otherwise a hundred unattended turns.

**What a rule may do is a closed set**, for the same reason `WorkflowEngine`'s
step set is closed: no shell, no arbitrary tool, no inline steps, at most four
actions. A rule that could run anything would be a second agent loop with none
of the coordinator's approval routing or revoke semantics.

That set stayed closed when the native capability tools landed, and it did not
need to widen: a workflow's own `call` step reaches them, so a rule that names
a workflow reaches contacts, the calendar and a drafted message without any
change to the rule format. This is the split paying off — "every weekday at
07:00, tell me my first meeting" is a `run_workflow` with no screen, no
accessibility and no thinking turn, and `AutomationActionKind` learned nothing
new. Whether a rule should also be able to `call` a capability *directly*,
skipping the one-step workflow wrapper, is open: `WorkflowDefinition` still
requires a `package`, which a capability call has no use for.

Nothing in `:core` fires a rule; the `:automations` module below does.

## Firing a rule: the `:automations` module

The Android half is deliberately thin, because everything worth getting right
is on the other side of `AutomationEvaluator` and `AutomationRunner`, where it
can be tested. `AutomationHost` builds the context, hands events to `:core` and
serialises the runs; the rest is `AutomationAlarms`,
`AutomationNotificationListener` and a runtime-registered receiver for device
state. System-created components reach the host through `AutomationHostOwner`,
implemented by `AgentApplication` — the same shape as `A11yServiceHandle`, and
for the same reason: the system constructs them, so there is nowhere to inject.

**One alarm, not one per rule.** `AutomationWakeups.nextRunAt` returns the
earliest moment any enabled scheduled rule is due, and that is the only alarm
held. Android caps how many exact alarms an app may keep and charges a wake-up
for each; the landing alarm re-checks every rule anyway. It is a wake-up, not a
decision, which is why an alarm the OS coalesced, delivered early, or held over
from a deleted rule fires nothing. It is re-armed after every firing and at
`BOOT_COMPLETED`, because an alarm does not survive a restart and a feature
that silently stops at the first reboot is one nobody trusts again.

Exactness is asked for, never assumed: `canScheduleExactAlarms` is false until
the user grants it on Android 12+, and the fallback is an inexact alarm Doze can
land an hour late. `AutomationHost.canFireOnTime()` reports which, because
"19:00" arriving at 20:10 is a different action rather than a slow one.

**The notification listener enforces the privacy contract in the order its
checks are written.** Our own notifications are dropped first, so a rule's
`notify` can never trigger the rule that posted it; ongoing and group-summary
notifications are dropped as status rather than events; then **the package is
checked before the title or body is touched**, against
`AutomationWakeups.watchedPackages` — the union over enabled notification
rules, with `"*"` included only by an enabled all-apps rule. An app outside that
scope is never read, and with no notification rule at all the service reads
nothing. Only then are title and text extracted, and they go
no further than the evaluator unless the rule's own actions interpolate them.
Nothing is stored: there is no notification log and no tool that can ask for one.

**A voice rule carries its context through startup and speaks first.**
`voice_call.opening` is the first utterance; optional `context` carries the
rule's event details for follow-up. The evaluator binds placeholders in both
fields and reports their exports. The runner packages them with the rule ID
and expiry in `AutomationVoiceRequest`, and the Android action sends that
payload through `AssistLaunch` to `MainActivity`. Intent extras are consumed
once, ignored when reopening from Recents, and a pending microphone grant
keeps the payload through recreation.

`VoiceConversation` waits for the realtime connection, sends fixed developer
guidance plus JSON-quoted rule/event data through its existing context path,
then calls the existing app-server `thread/realtime/appendSpeech` boundary for
the opening. No microphone turn is required by this dispatch. Notification
text never becomes developer instructions or a user reply; the context echo
is not recorded as something the user said. A failed context injection stops
the opening; expiry is checked before launch, before context and before speech.
A later rule firing is serialized into the live voice conversation rather
than restarting it. Normal mic and assistant starts have no automation payload.

The current screen-on/unlocked attention gate remains. Requesting activity
startup is not evidence of audible output: background activity restrictions,
microphone permission, cold starts, Bluetooth audio, interruption, expiry and
real first speech still need physical-device verification on this change.

### Device and network connection conditions

`AutomationDeviceStates` is the closed list of real signal names and values:
power, screen, Bluetooth headphones, Bluetooth devices and Wi-Fi. Conditions
require `equals`; unknown names, values and fields (including a mistaken `is`)
are refused when saved or updated. Missing/unavailable state fails closed even
for a negated condition. Screen `unlocked` is a transition, not a persistent
condition value. The host reads a fresh `AutomationDeviceSnapshot` under the
run lock; test overrides never enter this live firing path.

`automation_rule(mode:"signals")` returns current connection identifiers and
display names on an explicit request. Bluetooth conditions can name
`deviceAddress` and `profile`; Wi-Fi conditions can name an exact `ssid` and/or
`bssid`. Names do not act as Bluetooth identity. MAC address comparison ignores
case; SSID comparison preserves case and whitespace. Identity fields are not
added to event exports or voice model context automatically. These identifiers
select connections; they do not authenticate a device or access point.

The `:voice` module's `BluetoothHeadphones` class uses connected Android audio
endpoints (SCO/HFP, A2DP and BLE headset), plus the remote classic device class.
Watches, speakers, wired audio and paired-only devices do not qualify. BLE
headsets can lack a classic class; Android's BLE-headset endpoint type is then
the evidence. An unclassified classic device fails closed. `:automations`
reuses this reader rather than maintaining a second audio classifier.

`AutomationBluetooth` separately reads connected HFP/A2DP/LE Audio profile
proxies and the system's connected GATT list. It can identify a watch by an
explicit device condition without calling it headphones. Profile proxy startup
or binder/permission failure is unknown; it never substitutes the bonded list.
Only the supported public profiles are promised, not arbitrary Bluetooth
transports. Broadcast extras are ignored; current state and identifiers are
queried again, and an unchanged snapshot does not emit another state event.

`AutomationWifi` reads the current Wi-Fi association without scanning, querying
credentials or connecting. Network callbacks refresh it, including a switch
between access points while still connected. Redacted SSID/BSSID and missing
precise Location permission or Location toggle are unknown. Android documents
these redactions in [WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo).
Bluetooth identity uses Nearby devices (`BLUETOOTH_CONNECT`) on Android 12+;
Android 11 uses the normal Bluetooth permission. The capability gateway can
request the new runtime permissions visibly. Missing signal permissions are
reported dormant by the tool and blocked by the rules summary. The Location
permissions support Wi-Fi identity here, not a geofence or coordinates source.

The runner carries device conditions in `AutomationVoiceRequest`. They are
checked again before activity launch, during startup, before context and speech,
and throughout voice. A positive connected-headphones condition also restricts
the communication route to the matching address/profile. Local WebRTC playback
is muted (or PCM playback paused/flushed) before asynchronous stop on loss; no
speaker fallback or automatic replay is selected. Route callbacks and a bounded
100 ms watchdog check route, identity and permission changes. HFP may take up
to three seconds to become the active route, before new voice media starts.
The output gate latches a lost condition until a new call.

Protected voice needs Android 12+ and a usable Bluetooth communication route:
A2DP-only headphones can satisfy the connection condition but cannot safely
serve this realtime communication route. They are refused rather than routed
to the speaker. Android callback delivery and buffered physical audio are not
instantaneous guarantees. Required physical tests include classic HFP and BLE
audio, two headphones with the same name, selected-device/profile loss while
another device stays connected, watch-only and speaker-only connections,
permission revocation, cold starts, stop/reconnection, A2DP-only refusal, Wi-Fi
roaming, VPN, redacted identity and foreground/background Location behavior.

**A rule takes the device the way a turn does.** `run_workflow` and
`open_intent` go through `AgentCoordinator.runAutomation`, which claims the same
exclusive ownership a person's run claims, shows the same control card and
answers the same Stop — Stop sees an active state, revokes the tools (which is
what aborts a workflow between steps) and owns the teardown, so `runAutomation`
re-checks the epoch before releasing anything. It waits a bounded 90 seconds for
a busy phone and then gives up, because the common collision is not a collision
at all: it is the agent firing a rule from inside a turn that already owns the
device, where refusing would make "run my evening rule now" always answer "the
phone is busy" with the busy run being the one that asked. Anything longer is
the staleness `validUntil` covers. The intent goes out through the same
composite gateway the model calls, so a rule is not a way around the intent
policy or the approval card.

**A queued turn expires.** `agent_turn` lands in the rule's own chat — not the
one in front of you, so a rule firing at 3am does not appear in the middle of
your conversation, and a chat per rule is the readable record of what it has
been doing — carrying `validUntil` from `AutomationGuard.validForMs`.
`SessionRunQueue` drops a stale turn before choosing the next one, so an expired
turn at the head does not hold up the one behind it, and the drop is written
into that chat rather than being silent.

**Asking is a notification with two buttons, and no answer is a no.** A rule
fires when the app is not in front, so `ask` puts a high-priority notification
up and waits five minutes. A question nobody saw must not become a yes by
default. `canAsk()` is false when notifications are blocked, and the runner then
refuses the action outright — the same rule `WorkflowRunner` applies with
`confirmation_unavailable`: a gate that disappears when unwired is not a gate.

**Naming a rule is its trigger.** `mode:"run"` fires one now, through the same
`Manual` event the `manual` trigger kind uses, and the evaluator treats a manual
run that names *this* rule as satisfying its trigger whatever that trigger is.
Without it a scheduled rule could only ever be proved by waiting until 19:00,
which is not a feedback loop anyone checks a standing rule with — and the
`manual` trigger kind itself was unreachable, since nothing called
`AutomationHost.runNow`. Nothing else is waived: the conditions, the cooldown,
the daily limit and the attention gate all apply, and the run counts against the
quota. That is the whole difference from `mode:"test"`, which decides the same
way and does nothing. A host that wired no firing path leaves `mode:"run"`
refusing rather than silently doing nothing.

`AutomationToolGateway` carries `supportedTriggers`, which this host answers
with what it can actually serve: `schedule`, `device_state` and `manual`
always, `notification` once the user has granted the listener by hand. **`place`
is served by nothing yet**, so a geofence rule is saved and reported **dormant**
rather than accepted as live. That is a dependency decision, not an oversight:
`GeofencingClient` means adding Google Play Services to a project that
deliberately ships outside Play, and the AOSP alternative
(`LocationManager.addProximityAlert`) is unreliable enough that shipping it
quietly would be worse than reporting the gap.

Settings > Standing rules is where the feature says whether it actually works:
how many rules are on, how many are **dormant**, when the next one is due, and
the two permissions — notification access and exact alarms — with a button to
each. Both are granted in system Settings and neither is observable, so the
status is re-read on every resume beside the other permissions. A rule that
looks on and cannot run is the failure the user would otherwise only notice by
the thing not happening, so it is counted on the hub row rather than buried.

## The side panel: chats first

`ChatLibraryDrawer` opens on a flat list of recent chats across the phone and
computers. A device picker narrows the list; search matches titles, folders and
computer names. Each row shows its location, so equal titles on two computers
remain distinct. Day headings provide time context; rows omit individual dates
and times, so a row is one 48dp line unless it has a project name, "Running" or
"In Codex" to add. Imported desktop conversations say "From Codex". Rename and
delete on Mike's chats are a long press on the row only; the open chat has no
separate menu button.

Projects have their own tab. Opening a folder shows only its chats, and Back
restores the project search and scroll position while the panel stays composed.
`ChatLibrary` builds these lists from `PcChats`, retaining its path rules and
imported-thread deduplication. In a computer's projects list each project
header carries one "+" that starts a chat in that project, replacing the
"New chat here" row that sat under every open project.
One New chat action uses the open project, offers a folder on the selected
computer, or starts the normal new-chat flow when All devices or This phone is
selected. Connection recovery appears only for the selected computer; managing
computers is one entry in the device picker.

The title, New chat, close, Files and Settings stay reachable while the filters
and list scroll on short screens. Large text moves New chat to its own row.
Automations use one compact footer entry with a count and an attention dot.
Its accessibility label states blocked or on/off status. The detailed rule list
stays in `AutomationsSheet`; the hamburger
still marks blocked rules, and Settings has its setup attention dot. Rule
summaries and status counts come from `AutomationOverview` and
`AutomationSummaries` in `:core`.

**Three states, not two.** `AutomationSummary.Status` is ON, OFF or **BLOCKED**
— on, and this phone cannot serve its trigger. Blocked looks identical to
working until the day nobody notices anything happened, so it gets its own
colour (the amber the status orb already uses for a blocked backend), its own
group in the list, and the reason spelled out in words.

**The door carries the dot.** The panel is the only place a rule's state
lives, so the way in has to carry the one urgent fact: `ChatTopBar`'s
`PanelButton` adds a 6dp dot on the hamburger when any rule is blocked, and
shows nothing when nothing is wrong. The dot is deliberately not folded into
the status orb on the right: the orb answers "can Mike act right now"
(backends, run phase, quota), the button answers "what is inside the panel".
Different questions, different sides, different shapes.

The button itself is the second half of that. The chat name used to be the
door, which made it mean two things at once — what you are reading, and where
you go — and left the dot sitting on a chevron that read as "rename this chat".
A hamburger in the navigation slot is the door; the title is only a title.

**Opening it makes room rather than covering.** `rememberDrawerPush` and
`Modifier.drawerPushed` step the chat back and aside as the panel arrives: it
slides towards the far edge, shrinks to 0.88 and rounds to a 28dp card, so what
is left showing beside the panel reads as the screen you were on rather than a
screen that got cut off. Three directions were drawn (unfolding out of the
button, pushing the chat aside, cascading the contents in) and this is the one
the owner picked; the other two are on the canvas.

Progress is read at draw time inside `graphicsLayer`, so the push never
recomposes the chat, and at zero the layer sets nothing at all — the chat is
byte-for-byte what it was before any of this existed. It rides on the drawer's
`targetValue`, not the sheet's live offset: the target is what the menu button
sets the moment it is tapped, so the push starts *with* the sheet rather than
after it, and a drag carries the chat along once it crosses the anchor. The
price, stated rather than hidden, is that mid-drag the chat animates towards
where the drag is going instead of tracking the finger — invisible on a tap,
slight on a drag. Closing is quicker than opening (260ms against 340ms),
because the sheet is already leaving and a chat still settling reads as lag.
It shares voice mode's easing (`Emphasized`) literally, not by copying the
numbers, signs its shift for the layout direction, and `animationsEnabled()`
leaves the chat still when the system says no motion.

No shadow under the sheet: the scrim already sits between the two on a black
background, where a drop shadow would be invisible. The separation is carried
by the chat's own corners and scale instead.

**What leaves the phone is stated, not implied.** A rule's own screen names the
exported fields in the user's terms — "Only the sender's name", with the note
that the message itself was read on the phone to decide and never sent. The
format already knows this exactly (`AutomationRule.exportedFields`), so there
is no reason to make anyone take it on trust.

The list and one rule are two levels of one `ModalBottomSheet`, the way Settings
already works, so back walks the rule and then the sheet rather than
introducing a second navigation idea. Turning a rule off re-arms the alarm set,
because the earliest due rule may have changed.

A rule's screen also deletes and edits it. Delete asks first — it is the one
change here that cannot be switched back, and the dialog points at the switch
for pausing instead. Edit is a third level of the same sheet: a form, never the
rule's JSON. `AutomationEditor` in `:core` turns a rule into the plain values a
person changes — the time (picked from a clock), the days (seven toggles,
Sunday first), numbers, and the text inside a condition or an action, grouped
under the condition or action they belong to — and turns the edited values back
into the `changes` that `AutomationLibrary.update` takes. So the form saves
through exactly the validation the agent's `mode:"update"` does: a bad value is
named under its own field, a rule refused as a whole shows the reason above
Save, and nothing is written when it fails. Only values that differ from what
the form opened with are written, so opening and saving changes nothing.

What the form leaves out is what changes a rule's *shape* rather than a value
in it: another kind of trigger or action, adding or removing a condition, and
the identifiers underneath (which workflow runs, an intent's action, which
event field a text test reads). Typing over a workflow's name would point the
rule at one that may not exist. Those go through "Want a bigger change?" at the
bottom, which sends the request to Mike, who uses `mode:"update"` and a dry run
like any other request.

## First launch: two things by hand, the rest offered

The old first launch was the settings checklist: eight items, four required,
all at once. Now `OnboardingFlow` shows one screen at a time and the user does
by hand only what no app may do for them: sign in, and turn on the
accessibility service. The full proposal is `docs/design/NEW_USER_EXPERIENCE.md`.

**The order is a function in `:core`.** `Onboarding.step` picks the screen from
what is stored (`OnboardingProgress`: welcomed, consent version and time,
finished) and the live `SetupSignals`, so a test pins it: welcome, consent,
sign-in, screen access, handover, done. Steps already done are skipped, so a
phone that was set up before this version sees only the consent and the
handover. The runtime prepares in the background and shows as one thin bar
with a retry, never a step.

**Consent is versioned.** `Onboarding.CONSENT_VERSION` is stored with the
time the user agreed. Raising it asks everyone again; withdrawing (Settings →
Privacy and consent) stops Mike, signs out and forgets the consent, and the
first-launch consent screen comes back. Screen access is a system switch the
app cannot turn off, so withdrawing opens its screen instead of pretending.
All of it lives in the app's own `ui` preferences and never leaves the phone.

**The handover offers, it does not require.** The floating Stop button and
progress notifications are one tap each on Android's own screens. Wireless
debugging is handed to Mike: after one in-app confirmation, the app arms the
pairing reader while no run is active, then starts a chat in which Mike opens
Developer options, turns on Wireless debugging and opens the pairing dialog.
The reader takes the code from that dialog; the model never sees it. The
floating control stays a separate grant for now because device control refuses
to start without it; drawing it as an accessibility overlay instead would
remove that step and is still to be verified.

**Finished stays finished.** A switch Android turns off later is a settings
problem, not a reason to replay first launch. The side panel's header and the
Settings dot say so instead.

Settings are regrouped the same way: *What Mike can do* lists abilities with
On / Set up / Fix (screen control and the floating control are one row, since
neither works alone), then *How Mike works*, *Account and privacy*, and
*Advanced* (runtime, Jev, app updates). The side panel gained the orb with a
one-line status, chat search, and chats grouped by day (`ChatDayGroups`).

## Computers: Codex on the user's Windows PC, driven from the phone

A chat can run on one of the user's computers instead of on the phone. The
phone does not get an SSH tool; it runs **Codex itself on the computer** and
talks to it over SSH with the same app-server protocol it already speaks to
the phone's own Codex. `CodexEngine` only needs a `Process`, so the new
`:remote` module hands it an SSH exec channel (`SshProcess`) instead of a local
child. Everything Codex does there is native to that computer: its shell,
`apply_patch`, git, the project's `AGENTS.md`, the user's `~/.codex` config,
MCP servers, and skills from `~/.agents/skills` and the repo's
`.agents/skills`, which also appear in the composer's skill picker.

Why not a `remote_shell` tool for the phone's Codex: file edits, reads,
skills and project instructions would all stay on the phone, and every step
would be a mobile round trip wrapped in `cat` and heredocs.

- **Routing.** `RoutingAgentEngine` is the one engine the coordinator sees. A
  chat bound to a computer opens its thread there; later calls about that
  thread go to the same place. Sign-in, models, usage and voice stay the
  phone's. Both app-servers number requests from zero, so a computer's tool
  and approval requests are tagged `remote|<computer>|<id>` before the
  coordinator sees them. An unscoped failure from a computer that is not
  running the current turn is dropped, because the coordinator ends any
  active run on one.
- **Account and model.** Before any remote app-server call, `RemoteHub`
  supplies an externally managed ChatGPT access token from the active Mike
  account on the phone. The phone's refresh token stays on the phone; the
  app-server requests a fresh access token through the SSH stream when it
  expires. An account change during refresh fails the remote turn. If Mike
  cannot provide a ChatGPT token, the remote call stops before running under
  the computer's saved Codex account. `CodexEngine` passes the model selected
  in Mike to both `thread/start` or `thread/resume` and each `turn/start`.
  The computer's Codex config, MCP servers and skills still come from that
  computer.
- **Live remote activity.** `item/started` and `item/completed` notifications
  for reasoning, commands, file changes and other supported tool items become
  `remote_activity` messages in the chat, updated from running to complete or
  failed. Raw app-server JSON never reaches the UI. `chatRows` folds
  back-to-back ones into one `RemoteActivityRow`, drawn like the phone's
  actions group but with the computer's icon: a header that names the computer
  and counts what ran ("Working on Server" while live, "Worked on Server · 4
  commands" after) and steps that say what each one did ("Ran ls -la",
  "Thought"), with the shell Codex wraps a command in taken off. A live group
  nobody opened shows its newest four steps. A step still `streaming` after its
  run ended shows as stopped, never as running. The computer's group and the
  phone's never merge, so what ran where stays readable. The chat's top bar
  says where the chat works as "Server · folder · This phone", each place
  with its icon, and leaves the folder out when it is the chat's own name.
- **The phone is still reachable.** The phone's device tools are advertised to
  the computer's thread as well, so a task on the PC can still act on the
  phone. The computer's thread instructions (`RemoteInstructions`) say which
  is which.
- **SSH.** JSch (pure Java, Android networking and DNS, no native binary).
  Password login; the host key is trusted on first connect, shown as a
  `SHA256:` fingerprint, and pinned in both the sealed store and the live
  `SshLink`. Reconnecting that same link keeps its first key, even after a
  disconnect. A different key refuses the connection before the password is
  sent. A changed home address clears the
  pin. A computer may have a home address, a VPN address (such as Tailscale),
  or both. Connect tries the one that answered last first and moves on only
  when an address does not answer at all; a refused password or a changed
  key stops there. The pin holds for both addresses.
- **Codex on Windows.** Setup runs short PowerShell scripts through
  `powershell.exe -EncodedCommand`, which reads the same under OpenSSH's cmd
  and PowerShell default shells. The computer downloads the official
  `codex-app-server-package-<arch>-pc-windows-msvc.tar.gz` for the version
  pinned on the phone (0.159.2), checks its sha256 against hashes compiled
  into the app, and unpacks it under `%LOCALAPPDATA%\HeyMike\codex\<version>`.
  The phone and computer therefore speak one protocol version.
- **Access is the user's choice per computer.** *Ask me first*:
  `workspace-write` with `on-request` approvals, answered on the phone's
  approval card. *Full access*: `danger-full-access` with no approvals, as on
  the phone. What *Ask* can enforce depends on the Codex Windows sandbox on
  that PC; it is not set up by Hey Mike.
  Permission requests retain their requested profile by request id. Allow
  returns that profile with turn scope; Deny returns an empty profile.
  Command and file-change requests still return accept/decline decisions.
  The phone card names the requested network and file access and its duration.
- **Secrets and bindings are sealed.** The agent's shell on the phone runs as
  the app's own user and can rewrite any app file. Computers, their passwords
  and which chat runs where are one AES-GCM blob under a non-exportable
  Keystore key (`KeystoreSecretBox`). An edited file does not open, so it
  cannot point a saved password at another host or move a chat onto a
  computer; the app then trusts none of it and asks for the computers again.
- **Pictures** are sent inline as data URLs. Any other attachment is copied to
  the computer over SFTP first (`RemoteHub.sendAttachments`), into
  `.hey-mike/attachments/<time>/` in the chat's project folder, and the prompt
  names where it landed ("Attached files on this computer"). They used to be
  refused, because their paths are on the phone. A failed copy sends nothing
  and keeps the attachments. The folder shows up in `git status` of a project
  that is a repository.
- **The composer's plus** opens Photo (system photo picker, several at once, no
  storage permission), Camera (one shot written to `cache/captures/` through
  the app's FileProvider, copied into the chat, then deleted; no CAMERA
  permission, because the system camera app takes the picture) and File. All
  three feed the same pending attachments, in phone chats and computer chats.
- **Files: places, one copy tool, a skill for use cases.** A file lives in
  one of three kinds of place, and every address names one: `chat:<path>`
  (this chat's folder on the phone), `phone:<path>` (shared storage, or a
  `content://` uri), `<Computer>:<path>` (a saved computer, scp style). A
  path with no place is where the chat's shell runs: the chat folder in a
  phone chat, the project folder in a computer chat. `copy_file(from, to,
  replace)` (`CopyFileGateway`, in `:core`) is the only tool that moves bytes
  between places. `:core` owns the `FilePlace` contract; `:device-tools`
  implements the phone (`PhoneStoragePlace`: MediaStore insert with
  `IS_PENDING`, reads by uri or relative path, ADB `pull` only as a fallback
  for a file media access cannot read); `:remote` implements each computer
  (`ComputerPlace`, SFTP over the saved SSH link, relative paths joined to
  the chat's folder in the computer's own style); `:app` wires them. Every
  copy lands whole or not at all: a phone download is renamed from a part
  file, an SFTP upload is written as `.<name>.part` and renamed, and a
  MediaStore insert stays pending until the bytes are in. Nothing is
  overwritten without `replace`. Between two outside places the file passes
  through the phone's cache. `TransferMeter` reports every copy to the
  progress banner, and Stop cancels it. Tools that act on a file take a
  phone address and never copy on their own: `install_apk(file)` takes
  `chat:` or `phone:`, and a computer address is refused with the copy that
  brings it here, so a failed install is retried without copying again.
  `files_media share` and `open` take the uri a `phone:` copy returns. Use
  cases (install an APK built on a computer, send a computer file on
  WhatsApp, a phone photo to a computer) are recipes in the
  `files-across-devices` skill, not tools. A computer chat's instructions
  carry the same recipes, since Codex there reads the computer's skills,
  not the phone's. `push_file`, `pull_file` and `copy_to_phone` are gone:
  one tool per kind of copy is how the tool list grew without order. The
  thread instructions forbid using adb on the computer to reach the phone:
  it can see other devices and skips the app's controls.
- **The desktop.** Commands over SSH run in a Windows session with no screen.
  For screenshots, windows and the clipboard, the instructions teach a
  one-off scheduled task that runs as the signed-in user, interactively. It
  works only while someone is signed in to Windows.
- **Projects and the PC's own conversations.** A project is a folder on a
  computer: one the user picked (sealed in the same store), one a chat here
  runs in, or one Codex on the computer worked in. The side panel filters a
  flat chat list by device, with projects in a separate tab. Codex's own
  `thread/list` (sources `cli`, `vscode`, `appServer`) supplies the
  conversations the PC started; summaries only, up to 200, refreshed when the
  panel opens. Opening one binds a new chat to that thread and copies its
  messages in once from `thread/read`. The binding records that the thread
  came from desktop Codex. If that thread has a writer lock, Mike offers a
  copy; it checks the app-server's loaded threads so its own lock is not
  mistaken for desktop Codex. Resuming keeps the imported origin, so a later
  desktop lock can still offer a copy. Only an explicit fork changes an
  imported binding to Mike-owned. The panel connects
  in the background once per app run, but never installs Codex on its own.
- **Linux computers.** The first connection runs `uname -s` (Windows has no
  `uname`; Git's says MINGW, still Windows; macOS is refused for now) and the
  answer is kept with the computer. `LinuxHost` is the Linux side of the same
  `HostScripts`: POSIX `sh` scripts sent base64 encoded (so the login shell
  does not matter), the same `HEYMIKE {json}` answers, and the same pinned
  `codex-app-server-package-<arch>-unknown-linux-musl` the phone runs,
  checked against the phone build's sha256 pins and unpacked under
  `~/.local/share/heymike`. Paths keep the computer's style everywhere:
  Linux paths keep their case and use `/`. The thread instructions say
  Linux, and for the desktop they point at the user's graphical session
  (XDG_RUNTIME_DIR, DBus, Wayland or X11) instead of a scheduled task.
- **Voice follows the thread.** `RoutingAgentEngine` starts realtime on the
  app-server that owns the chat's thread, so voice in a computer chat runs on
  the computer's Codex with Mike's active account. Audio does not cross SSH: the
  transport is WebRTC, so only the SDP goes through the computer and the
  media flows between the phone and OpenAI.
- **No size cap on copies.** `copy_file` and `install_apk` take files of
  any size; the user decides what is copied. Screenshots keep their cap
  because they go to the model.
- **The `computers` tool, from any chat.** One tool with modes: `status`,
  `browse`, `new_project`, `open_chat`, `add`. It is a tool and not a skill
  because it crosses the sealed store's line: the agent's shell has no SSH
  and must never read a password or rebind a chat. Two modes only prepare
  what the user finishes: `add` fills in the app's add-computer form, which
  says Mike suggested the address (an injected address is how a password
  would be sent to someone else), and the password is typed there, never
  seen by the model; `open_chat` opens a chat in a project with the task in
  the composer, unsent, so an instruction picked up elsewhere cannot reach a
  PC that may have full access. A phone chat does not move to the computer;
  `open_chat` starts a new chat there with what was decided.
- **Where a new chat runs.** A new chat starts on the phone. Until its first
  message it can be moved to a recent project on a computer, or to another
  folder; after that its thread lives where it started. A computer chat's
  title bar shows the computer and folder and that the phone is still in
  reach.
- **Stop** interrupts the turn on the computer. If that fails, the engines
  are closed, which closes the SSH channel. A command Codex already started
  there may keep running; the run summary must not claim it was undone.
  File transfers have a separate cancellation path: cancellation closes the
  active SFTP channel and interrupts its blocking I/O, leaving the shared SSH
  session available for the turn interrupt and future transfers. A cancelled
  copy never advances to phone installation or sharing. Bytes already written
  are not rolled back.

## Claude subscription chats: the engine belongs to the turn

First design: `docs/superpowers/specs/2026-09-30-claude-subscription-design.md`
(phone only, engine fixed per chat). The section "Claude as a full engine"
below records what changed since: a chat can change engine at any point,
both engines' models share one menu, a computer chat can run on Claude, and
voice works in a Claude chat.

- **Engine per turn.** `ChatSession.engine` is the engine the chat's next turn
  runs on (`CODEX` or `CLAUDE`; old chats read as Codex). A new chat takes the
  default engine chosen in onboarding or Settings > Accounts, and after that
  the last model picked. The top bar names the chat's account.
- **Routing.** `RoutingAgentEngine` reads the chat's engine from the session
  store in `openSession` (via the `<sessions>/<id>/workspace` folder) every
  time, keeps Claude thread ids, and finds one again through its chat after a
  restart, whether the chat runs on it now or keeps it parked.
  Claude tool and approval request ids get a `claude|` tag, as computer ids
  get `remote|`, so ids cannot collide. The plain account calls stay Codex's;
  `connect(kind, workspace)` and `account(kind, workspace)` reach the engine
  that will run the chat's turn, and the coordinator uses them, so its
  messages name Claude or Codex. `codexEvents` is the Codex-only stream the
  view model reads for Codex sign-in, quota and the home screen widget, which
  stays Codex-only.
- **Per-engine state in the view model.** Model pick, effort and quota are
  kept per engine (`EngineChoices`); the chip, `/status` and the usage sheet
  show the engine the open chat runs on. Claude reports `5-hour` and `weekly`
  limits.
- **Claude usage without a message.** `refreshUsage()` sends the SDK's
  experimental `get_usage` control request (`skip_behaviors: true`) to a
  running chat, or to a throwaway probe (`--no-session-persistence`, no
  tools, no settings) that also answers `initialize`, so the refresh on start
  costs one process. A refusal or timeout emits nothing. The app keeps the
  last reading in `claude-usage.json` (`LastUsageStore`), shows its age, and
  drops a window once its reset time has passed; sign-out clears it.
- **On-phone runtime.** `AndroidClaudeHost` runs the official, unmodified
  `claude` binary through the pinned Alpine musl loader, packaged as
  `libld_musl.so` for arm64-v8a only; the binary itself is never exec'd, so
  W^X does not apply. The binary (2.1.285, pinned sha256 and size) is never
  bundled: it is downloaded from `downloads.claude.ai` only after the user
  saw its size (232 MB) and tapped Download, with progress and cancel, and
  hashed before use. At app start the host only re-checks a binary that is
  already there. On other ABIs the card says "Not available on this device".
- **Phone tools over loopback MCP.** Each chat process gets its own
  `LoopbackMcpServer` (127.0.0.1, fresh port and bearer token per start), so
  a tool call is always tied to the chat that made it. A `tools/call` becomes
  `EngineEvent.ToolCall` and waits for `answerTool`, so approvals, overlay
  states, revoke-before-interrupt and the trace are the same as for Codex.
- **How `claude` runs (WP-C decisions).** One `claude -p` stream-json process
  per open chat, started lazily by the first turn and restarted with
  `--resume` when model, effort or tools change; at most two chat processes
  live at once, the idle ones stopped first. `--setting-sources user` so the
  skills installed in `<claudeHome>/.claude/skills` load; built-in tools are
  `Read,Edit,Write,Glob,Skill`, with `Read` and `Edit` allowed only inside the
  chat workspace; `Grep` and `Bash` stay off until proven on a phone.
- **Sign-in.** `claude auth login` runs inside the app's private
  `CLAUDE_CONFIG_DIR`; the app opens its link with `ACTION_VIEW` and hands the
  pasted code to that process's stdin. The paste field is masked, is not saved
  across configuration changes and is cleared on submit; the code is not
  logged or stored. Sign-in state comes only from `claude auth status`.
- **Compliance rules** (from the spec): official unmodified binary checked
  against its pinned hash; never bundled; sign-in only inside `claude`; the
  app never reads, copies, backs up or uploads `CLAUDE_CONFIG_DIR` (it is
  excluded from backups); no `setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`, spoofed
  headers or `--bare`; API keys and base-URL variables are scrubbed from the
  child environment; the UI says "Use your own Claude subscription (runs
  Anthropic's Claude Code). Not affiliated with Anthropic." and never uses
  "Claude Code" as a feature name.
- **What Claude chats do not have.** API-key mode and several Claude
  accounts. `/compact` works: after a restart the view model opens the chat
  before compacting, and the note shows before the call, which waits for
  Claude to finish.
- **Privacy and consent.** The consent text and the privacy page name both
  providers, with OpenAI's and Anthropic's policy links, and say that a chat
  which changes model, or uses voice in a Claude chat, gives its earlier
  messages to the other AI too. Naming Anthropic changed the consent in
  substance, so `Onboarding.CONSENT_VERSION` is 2 and existing users confirm
  again.

## Claude as a full engine: switching, one model menu, computers, voice

Decided 2026-10-01, on top of the section above.

- **A chat can change engine between turns.** Codex and Claude each keep
  their own thread and neither can read the other's, so a chat holds one
  thread per engine: `engineThreadId` for the engine it runs on, and
  `ChatSession.parked` for the other, with the message time up to which that
  thread saw the chat. `EngineSwitch.switch` is the whole rule, and
  `SessionStore.setEngine` applies it (`sessions.db` v4: `parked`,
  `catch_up`). Nothing is copied between the engines' own stores.
- **What the other engine missed is carried as text.** `catchUpFrom` marks
  the messages the chat's engine has not seen. On the next turn the
  coordinator builds `ChatHandoff` from the chat's own messages (what the
  user and Mike said, one short line per device action, newest kept when it
  is long) and puts it before the prompt it sends; the stored user message
  stays as typed. It says that device actions listed were already carried
  out. Tool results and run details are not replayed. A thread the engine
  lost and replaced (it hands back another id than the stored one) is given
  the chat again the same way.
- **The turn names its engine.** `QueuedTurn.engine` and `TurnRunner.send`
  carry it, and the coordinator moves the chat there before the turn, so a
  model picked for one engine never reaches the other, also for a turn that
  waited in the queue.
- **One model menu.** `AgentModel.engine` tags each model; the menu lists
  ChatGPT (Codex) first, then Claude. Picking a model of the other engine
  calls `useEngine`, which is allowed whenever the chat is idle, adds a note
  to the chat, and becomes the default for new chats. While Claude is not set
  up on the phone the menu ends with a row that opens Settings. The model
  chip is disabled during a run and during voice, so the engine never changes
  under a running turn.
- **Claude on a computer.** A chat bound to a computer can run on that
  computer's own Claude Code, installed and signed in there by the user.
  Mike never moves a Claude sign-in: Codex on a computer is handed the
  phone's ChatGPT account for each run, Claude uses the computer's own. The
  compliance rules above therefore hold unchanged; the binary is the user's
  own install, started unmodified.
  - `RemoteHub.claude(computerId)` is a `ClaudeCodeEngine` whose host starts
    `claude` over the computer's SSH link. `ClaudeLaunch.probe` finds it on
    `PATH` or in the usual install places (an SSH command gets a bare `PATH`);
    `claude auth status` gives the sign-in and `initialize` the models, kept
    per computer in `RemoteHub.claudeState`. Nothing is installed for the
    user: a computer without Claude Code offers no Claude models.
  - **Sign in from the phone (2026-10-06).** The Computers card offers
    `Connect Claude to <computer>` when Claude is installed but signed out.
    A computer chat's model menu also links to this setup. `ComputerClaudeSignIn`
    runs the computer's unmodified `claude auth login --claudeai`, opens the
    official link on the phone, and relays the pasted code to that process's
    stdin. `BROWSER=true` keeps the computer from opening a browser. After a
    successful process exit, `auth status` verifies the account and the model
    catalog refreshes for that computer. Credentials remain on the computer;
    no phone login or token is copied. The masked code field is not saveable
    and clears before submission. Progress and errors are in memory and keyed
    by computer; repeated taps cannot start two logins on one computer. Cancel
    or disconnect stops the waiting login without signing out an existing
    account. Raw login output never becomes a UI error. A failed connection
    check is shown separately from a missing Claude installation.
  - A launch writes a small script under `~/.hey-mike/claude` on the computer
    and runs it by path. The arguments include an empty string and paths with
    spaces, which cmd, PowerShell and a POSIX shell each quote differently;
    a script file in the computer's own language avoids all three. Checked on
    Windows under both cmd and PowerShell with a real `claude`: the streams
    pass through, Hebrew survives both ways, and the process ends when stdin
    closes.
  - The phone tools cannot use a loopback port from another machine. They
    travel on the process's own streams as an Agent SDK `sdk` MCP server:
    declared in `--mcp-config`, named in `initialize` (`sdkMcpServers`), and
    each MCP message arrives as an `mcp_message` control request (`StdioMcp`).
    A `tools/call` still becomes `EngineEvent.ToolCall`, so approvals, the
    overlay, Stop and the trace are unchanged.
  - Claude Code keeps its own tools, settings, skills and MCP servers there:
    no `--tools`, `--setting-sources` or `--strict-mcp-config`, and Mike's
    text is appended (`--append-system-prompt-file`,
    `RemoteInstructions.forComputer(.., CLAUDE)`). The computer's access
    setting maps to `--permission-mode acceptEdits` plus
    `--permission-prompt-tool stdio` for "ask", so a `can_use_tool` request
    becomes an approval card on the phone, and to `bypassPermissions` for full
    access. A prompt nobody answered is refused when the turn ends.
  - `connect(kind, workspace)` and `account(kind, workspace)` check the
    computer's Claude for such a chat, never the phone's, which need not be
    set up. Request ids are tagged `remote-claude|<computer>|`.
- **Voice in a Claude chat.** Claude Code has no speech-to-speech mode: its
  `/voice` is dictation in the interactive terminal, needs a local microphone,
  does not work over SSH or in `-p` mode, and has no Hebrew. So voice stays
  Codex's realtime session. A chat that runs on Claude talks on its own Codex
  thread: `VoiceConversation.begin` moves it to Codex, gives the voice session
  the chat so far as context (as conversation text, not as an instruction),
  and moves it back when voice ends; Claude is told what was said on its next
  turn. This needs the ChatGPT sign-in; without it a Claude chat has no voice
  button.
- **Not done.** A computer still has to be set up through Codex (and so with
  a ChatGPT sign-in) before its Claude can be used. Claude conversations kept
  on a computer are not listed or imported the way Codex's are. The
  computer's Claude skills are not listed in the composer. A Claude session
  file the CLI removed (its own clean-up of old sessions) starts again under
  the same id without the chat's history being sent again.
