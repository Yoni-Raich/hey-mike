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

A model change is configuration. An engine change replaces the engine adapter. Runtime packaging must not affect chat or ADB APIs. The UI observes app events, never raw Codex JSON.

The MVP permits one active agent run per phone. A session has its own working directory; this is organization, not a claim of OS-level isolation. Stop revokes dispatch first and interrupts active work next. Unknown raw shell requests are visible device control. The overlay tracks Starting, Thinking, Running, Controlling, Stopping, Done, and Error states for the entire run. MainActivity visibility hides its window inside the app and restores it outside the app until the run ends, including between tool calls. Its floating card separates status and local Stop from the steering composer, with 48dp action targets and native drawn icons. It releases input focus before device actions and returns to MainActivity after a terminal state.

Wireless ADB stores only the last successful local connect port in app-private
preferences. The foreground service runs a bounded reconnect loop: it tries
that port first, then uses Android NSD's `_adb-tls-connect._tcp` result and
ignores pairing services. No arbitrary LAN scan is used. A missing service is
reported as Wireless Debugging off/on-waiting when Android exposes that state;
the loop stays idle until the app has a stored pairing identity, and pairing
codes are never requested by reconnect.

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
The engine retains a short redacted stderr tail and redacted RPC error data so
DNS, TLS and connection failures remain diagnosable without exposing tokens or
device codes. Proxy lifecycle follows the supervised app-server and closes on
stop or failed startup.

The CONNECT allowlist includes `chatgpt.com:443`: in pinned Codex 0.153.4,
ChatGPT account sessions use `https://chatgpt.com/backend-api/codex` for
models and responses. Allowing only auth.openai.com and api.openai.com lets
device-code login succeed while blocking signed-in chat. The runtime sets
NO_COLOR and strips terminal formatting from redacted diagnostics.

## Chat presentation

The app owns presentation only: a black conversation canvas, neutral user bubbles,
selectable assistant text, and expandable diagnostic/activity rows. The composer
uses the existing send, steer, stop, model, and attachment action contracts.
Stop remains reachable while a steering draft exists; STOPPING blocks dispatch
and preserves that draft. Terminal formatting is removed before display.
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

## Session queue and exclusive device ownership

The MVP still allows one active run per phone, because one phone screen cannot
be shared. `SessionRunQueue` makes that limit a queue instead of a rejection:
the UI accepts a turn for any chat, and `AgentCoordinator` publishes an
`available` flag that gates dispatch. Sending into the chat that is already
running still steers it. FIFO order is durable in a `run_queue` SQLite table,
and a turn is dequeued before it starts, so a process crash cannot replay a
side effect. A queue restored at startup is paused and needs an explicit
Resume, and a local stop pauses the queue rather than releasing the next run at
the user unannounced. Deleting a chat cancels its queued turns.

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
reports `verified` from a read-back instead of assuming the write took.

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

## Experimental Jev UI engine

The `experiment/jev-ui-tool` branch adds `JevToolGateway` beside the local
knowledge and workflow gateways. It exposes one static `jev_run_ui_task` tool,
because Codex binds tool definitions at `thread/start`. One call owns a bounded
local loop:

```text
read fresh UI -> build code-owned action space -> one Jev request over one flat
set of concrete actions -> validate the choice -> re-read for freshness -> route
one action through CompositeDeviceToolGateway -> observe and repeat
```

The action space is flat: "Tap Wi-Fi", "Scroll down in the settings list" and
"Set Volume from 20.0 to 75.0" are all choices in one question. It was once two
questions — pick an operation, then pick a target "assuming the operation is
TAP" — but Jev answers every question in one request, so the operation was
chosen without knowing which target it would get and each target was chosen for
an operation that might not be taken. `TAP` and `SCROLL_DOWN` are not comparable
options; the concrete actions are, and that is the shape Jev's probabilities
mean something over.

One flat question means one 255-choice ceiling shared by everything on screen,
so the space is budgeted rather than filled first-come: five slots are reserved
for Back, Home, Wait, Done and Blocked; scrolling, progress and app launches are
each capped; and what is left goes to taps, which is what a screen is actually
navigated with. Over budget, a named control outranks an anonymous container,
because the name is the only thing Jev can reason about. Scrollable regions are
offered on their own axis only — a region taller than it is wide does not scroll
sideways. The one decision still asked separately is which exact string to type:
a goal yields hundreds of candidate spans, and folding them in would crowd out
every control on the screen.

This is the fast path: Codex supplies one complete goal and does not spend a
model turn between UI steps. Jev selects only opaque candidate keys. Local code
maps those keys to installed-app launch, observed node taps, semantic scrolling,
exact focused-field text, semantic progress, Back or Home. Jev cannot
invent selectors, node ids, packages, coordinates, text or range values. Text
is drawn only from explicit `texts` or bounded verbatim goal spans; password
fields never receive a text candidate. Progress values are numeric values or
percentages already present in the goal.

Every mutation still goes through the existing composite device gateway, so
Accessibility/ADB fallback, send approval, visible control and Stop remain in
one place. The loop re-observes immediately before input and discards a stale
decision. A refused action is never retried, but it does not end the run either:
the accessibility backend refuses plenty it definitively did not perform — a
node that rejects the text or the progress value, a coordinate our own overlay
covers — so the screen is re-read, and an unchanged screen proves nothing was
mutated. The refusal is then recorded, marked `refused` in the history Jev sees,
and Jev chooses again. Only a screen that did change stays `uncertain_mutation`,
because that one may already have committed. Repeated action/screen signatures,
stale screens, waits, steps, decisions and wall time are bounded. `DONE` completes
only after one more fresh observation matches the state Jev judged, and returns
`done_visible` with `verified:false`; this is not a task-specific verifier. Raw
Enter is not in Jev's action space because its ADB fallback could bypass the
existing send-approval guard; a visible submit control remains available.

Large UI observations are consumed through the existing `read_ui` paging
contract and merged with the newest observation id. Accessibility observations
also carry editable/selected state and range min/max/current plus supported
semantic actions. `set_progress` uses Android `ACTION_SET_PROGRESS` and reports
the typed range and polls for the requested value rather than simulating a
coordinate swipe. A dispatched but unverified change is reported as uncertain
and is never retried.

A step, wall or decision limit is out of budget, not out of options, so those
three replies carry a `continuation` token: calling `jev_run_ui_task` again with
`resume` set to it continues the same goal with its history and its repeat
detector intact, instead of starting blind and spending a fresh budget getting
back to where the last call stopped. The token carries the goal, so it cannot be
pointed at a different intent; it is single-use, the suspended set is bounded
and cleared when a run begins or control is revoked, and a goal may be spread
over at most five segments before it has to be decomposed. The orchestrator
still re-enters the loop deliberately between segments — the ceiling became a
pacing device, not an unbounded run.

The app keeps the feature flag and Jev token in `JevTokenStore`. The token is
encrypted with an Android Keystore AES/GCM key and is read only by the app's
`AndroidJevProvider` for the fixed TypeSafe endpoint. It is not part of UI
state, tool arguments, session files, or diagnostics. The HTTP adapter sends
one request per cycle carrying the flat action question, plus the text question
when a focused field can receive a value. Probability maps must contain exactly
the offered choices, be finite, sum to one within tolerance, and make the chosen
entry maximal. The text answer is validated and consumed only when the chosen
action types text, so an answer to a question that was not asked cannot block a
decision. Requests, responses and timeouts are bounded, and redirects carrying
the token are disabled.

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
is reported `verified:false`. Without it the runner would be a macro player,
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
`notification` trigger must also name its package: no package would mean every
notification on the phone, which is never what was meant and is the widest
possible read of a person.

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
rules. An app no rule names is never read, and with no notification rule at all
the service reads nothing. Only then are title and text extracted, and they go
no further than the evaluator unless the rule's own actions interpolate them.
Nothing is stored: there is no notification log and no tool that can ask for one.

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

## The side panel: two kinds of thing Mike holds

A chat is something you did. A rule is something that keeps happening. The
panel shows both, but not as equals: the rules sit **above** the chats as a
strip, and the chats keep the rest of the panel.

That ordering is the design. The question people open this panel with is often
not "which chat was that" but "is the standing stuff still working", and a
strip answers it before anyone reads a list. The cost is that a strip has room
for almost nothing, which is what the two constraints below are for.

**The strip may not grow.** At most `AutomationOverview.MAX_CHIPS` chips and
exactly one sentence, however many rules exist. What overflows goes behind it,
and the chips are sorted so that what needs you is what you see: blocked first,
then running, then off.

**The sentence is chosen, not listed.** `AutomationOverview` picks the most
useful true thing in priority order — a rule that cannot run, then the next run
that is due, then the honest nothing — and marks it as a warning or not. A
strip that listed everything would fit nothing and help less.

Both decisions live in `:core` (`AutomationOverview`, `AutomationSummaries`)
rather than in a Composable, because they are the design and a Composable is
not somewhere a test can reach. The same layer turns the rule format into
sentences: the format is written for the model — ids, packages, 24-hour clocks,
a closed vocabulary — and none of that belongs on a panel. `AutomationStrip`,
`AutomationsSheet` and the top bar render strings and choose nothing.

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
