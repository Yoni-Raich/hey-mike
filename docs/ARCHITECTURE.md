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
  Three skills ship: `device-automation` (tool mechanics and recovery),
  `app-cards` (the knowledge store, saved workflows and starter cards) and
  `user-preferences`. Installing them deletes the retired `recovery-and-safety`.
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
- **`ACTION_CALL` is not an available action.** It places a call with no
  confirmation. `ACTION_DIAL` reaches the same screen and leaves the
  irreversible press to the user, so the capability is kept and the
  irreversible half is not.
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
