# Mike Connect - design

Status: proposal, 2026-10-18. Nothing here is implemented or verified on a
phone. Facts about Hey Mike refer to `dev` at `c33b168` (0.14.0-dev, 1159).
Items marked **(verify)** are assumptions that Phase 0 must prove on hardware.

## 1. Problem

Mike reaches an app today in three ways: an intent or deep link
(`open_intent`), the app's screen (`read_ui`, `tap_node`, `workflow_runner`),
or a native Android provider (`contacts`, `calendar`, ...). None of them lets
an app *offer* Mike its own functions. Driving a screen is slow, breaks when
the app is redesigned, needs the screen lease, and cannot run in the
background.

Mike Connect lets any Android app expose functions to Mike on purpose:

- StreamFlix exposes "search titles" and "add to My List". In a chat about
  movies, Mike suggests a film, checks that StreamFlix has it, and adds it to
  the list when the user agrees - with no screen, in the background.
- A clock or screensaver app exposes "get theme" and "set theme". The user
  says "make the clock dark blue with a large font", and Mike calls it.

## 2. Goals and non-goals

Goals:

- **Generic.** Mike contains no code for any particular app. An app describes
  its functions; Mike reads the description. (Same rule as `IntentPolicy` and
  the skills: the mechanism is generic, the knowledge belongs to someone else.)
- **App decides.** The app chooses exactly what it exposes. Mike can do in the
  app only what the app exposes and the user enabled.
- **User controls.** The user enables each app, sees each function and its
  access level, can require approval, and can revoke at any time.
- **Background first.** Search and simple changes run without opening the app.
  A function that needs the screen hands off to the app's UI explicitly.
- **Standard.** The message format is MCP (2026-07-28 revision). Only the
  transport and the Android security layer are new.
- **Small for app developers.** One Gradle dependency, one service class, one
  manifest entry. Typical integration: under an hour for a read function.

Non-goals (v1):

- No remote/cloud connections. Mike Connect is phone-local IPC only.
- No Mike-to-app UI rendering (no remote widgets or app UI inside Mike's chat).
- No apps in other Android users or profiles (Work profile, Private space).
- No replacement for screen control. Apps that never integrate are still
  driven through the screen as today.

## 3. Key decisions

| # | Decision | Why |
|---|---|---|
| D1 | Message format = MCP 2026-07-28 (`tools/list`, `tools/call`, MRTR `input_required`, `_meta`) | Proven tool model (name, description, input/output JSON Schema, annotations). Stateless per request, which fits Binder. Developers who know MCP need nothing new. |
| D2 | Transport = Android bound service + AIDL, documented as an MCP custom transport ("Binder binding") | Same-device, no network port, no ADB, no accessibility. Caller UID is known per call. MCP allows custom transports if the JSON-RPC format, patterns and `_meta` model are kept. |
| D3 | Not built on Android AppFunctions | AppFunctions (Android 16+, Jetpack alpha) requires the caller to hold `EXECUTE_APP_FUNCTIONS`, which is currently limited to system agents. A sideloaded Mike cannot hold it. An adapter can come later (section 15). |
| D4 | One model-facing tool, `connected_apps`, with operations `list` / `describe` / `call` | Codex binds the tool list at `thread/start`; apps come and go. One static tool keeps every chat working and costs one tool's worth of context, not one per app function. Same shape as the native capability tools. |
| D5 | Security is enforced on both sides at runtime, per Binder transaction | The app checks that the caller is a trusted agent (signing certificate). Mike checks the provider's signer and the user's grants. No custom permission (see 9.1). |
| D6 | Consent is bound to a capability's name and access class | An app update that adds a function, or turns a read into a write, needs the user's approval again before Mike can call it. |
| D7 | Changes that matter go through Mike's existing approval card | `AgentCoordinator` already owns one approval surface (sends, intents, workflow steps). A connected app does not get a second one. |
| D8 | UI hand-off through an SDK trampoline activity and a one-time token | Mike launches only an explicit component in the provider's own package. No third-party `PendingIntent` is ever sent by Mike. |
| D9 | SDK modules licensed Apache-2.0, separate from the AGPL app | An AGPL library inside a third-party app would force that app to AGPL. The CLA lets the owner relicense these modules. (Owner decision, section 16.) |

## 4. Big picture

```
 Provider app (e.g. StreamFlix)                    Hey Mike
 ┌──────────────────────────────────┐              ┌─────────────────────────────────────────────┐
 │ App code: repositories, DAO, ... │              │ Engines: Codex (dynamicTools), Claude (MCP) │
 │        ▲ handlers                │              │          computers (relay)                   │
 │ ┌──────┴───────────────────────┐ │              │              │ tool call `connected_apps`    │
 │ │ connect-sdk                  │ │   Binder     │ ┌────────────▼────────────────────────────┐ │
 │ │  MikeConnectService (AIDL)   │◄├──────────────┤►│ :core ConnectedAppsGateway               │ │
 │ │  CallerVerifier              │ │  JSON-RPC    │ │  consent, schema check, approval, envelope│ │
 │ │  Capability registry + DSL   │ │  messages    │ └────────────┬────────────────────────────┘ │
 │ │  Idempotency cache           │ │              │ ┌────────────▼────────────────────────────┐ │
 │ │  UiTrampolineActivity        │ │              │ │ :connect-host                            │ │
 │ └──────────────────────────────┘ │              │ │  discovery, binding pool, provider check │ │
 │ connect-protocol (shared codec)  │              │ │  connect-protocol (shared codec)         │ │
 └──────────────────────────────────┘              │ └──────────────────────────────────────────┘ │
                                                   │ Settings → Connected apps (grants, history)  │
                                                   └─────────────────────────────────────────────┘
```

The model never talks to an app directly. Every call passes through
`ConnectedAppsGateway`, which is where consent, approvals, limits and
untrusted-data wrapping live.

## 5. Repository layout

New modules in this repository. The three published ones carry their own
license header and are built so they never depend on app modules.

| Module | Kind | Published | Contents |
|---|---|---|---|
| `:connect-protocol` | Kotlin/JVM | yes | MCP 2026-07-28 message model (subset), `dev.heymike/` extension keys, JSON codec, JSON Schema subset generator and validator, limits, error taxonomy. Pure JVM, fully unit-tested. Used by both sides so they cannot drift. |
| `:connect-sdk` | Android library | yes | `MikeConnectService`, AIDL, Kotlin DSL, `CallerVerifier`, `TrustedAgents`, idempotency cache, `UiTrampolineActivity`, `MikeConnect.emit` (events, v2). minSdk 24; the service is enabled only on API 30+ (Mike's own floor). |
| `:connect-sdk-testing` | Android library | yes | `ConnectTestHost` (calls a service in-process from a JVM or Robolectric test) and `ConnectConformance` (checks descriptions, schemas, error codes, limits). |
| `:connect-host` | Android library | no | Mike side: discovery, binding pool, provider verification, blob and trampoline handling. Implements the `ConnectTransport` interface from `:core`. |
| `:core` | existing | no | `ConnectedAppsGateway`, `ConnectionGrantStore` interface, approval method on `AgentCoordinator`. Pure JVM. |
| `:app` | existing | no | Wiring into `runTools()`, `KeystoreConnectionGrantStore`, Settings screen, the bundled `connected-apps` skill. |
| `samples/connect-clock` | Android app | no | Minimal clock/screensaver app that exposes `get_theme` / `set_theme`. Doubles as the device test fixture. |

Package names: public API in `dev.heymike.connect.*`. Mike's own classes stay
in `dev.androidagent.*`.

Distribution: JitPack from a `connect-vX.Y.Z` tag first (this repo already
resolves JitPack), Maven Central later. SDK versions follow semver and are
independent of the app version in `version.properties`.

## 6. The provider contract (what an app declares)

### 6.1 Manifest

The app declares one service. Nothing else is required.

```xml
<service
    android:name=".mike.StreamflixConnect"
    android:exported="true"
    android:enabled="@bool/mike_connect_enabled"
    android:label="@string/mike_connect_label">
    <intent-filter>
        <action android:name="dev.heymike.connect.action.CONNECT" />
    </intent-filter>
    <meta-data
        android:name="dev.heymike.connect.binding"
        android:value="1" />
    <meta-data
        android:name="dev.heymike.connect.summary"
        android:resource="@string/mike_connect_summary" />
</service>
```

- `@bool/mike_connect_enabled` ships in the SDK: `false` by default, `true`
  in `values-v30`. Below API 30 the service does not exist.
- `binding` is the Binder binding level (section 7.1), readable without
  starting the app.
- `summary` is one sentence for the Connected apps screen, also readable
  without starting the app.

The SDK also contributes, through manifest merge:

```xml
<activity
    android:name="dev.heymike.connect.UiTrampolineActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:excludeFromRecents="true" />
```

### 6.2 Capabilities

A capability is an MCP tool plus Mike metadata in `_meta`:

```json
{
  "name": "add_to_my_list",
  "title": "Add to My List",
  "description": "Adds a movie or series to the active profile's My List. Use the id from search_titles.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "id": {"type": "string", "description": "Title id from search_titles"},
      "profile": {"type": "string", "description": "Profile name. Omit for the active profile."}
    },
    "required": ["id"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {"added": {"type": "boolean"}, "alreadyThere": {"type": "boolean"}}
  },
  "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true},
  "_meta": {
    "dev.heymike/access": "write",
    "dev.heymike/summary": "Add {{title}} to My List",
    "dev.heymike/ui": "none"
  }
}
```

Mike extension keys (prefix `dev.heymike/`, valid and unreserved under the
MCP `_meta` rules):

| Key | Values | Meaning |
|---|---|---|
| `access` | `read` / `write` / `sensitive` | Drives consent and approvals (section 9.4). Required. |
| `summary` | template | Short line for the approval card and activity row. Only `{{argument}}` placeholders; Mike renders it, see 9.5. |
| `ui` | `none` / `may` / `always` | `may`: the result can ask to open the app's screen. `always`: the capability is a hand-off (e.g. "play"). |
| `timeoutMs` | 1 000 - 60 000 | Upper bound the app asks for. Mike clamps. Default 15 000. |
| `requestId` (request) | string | Mike's idempotency key for this call. |
| `origin` (request) | `chat` / `voice` / `automation` / `computer` | Where the call came from. Informational. |
| `userApproved` (request) | boolean | True only when Mike's approval card was answered Allow for this exact call. |
| `uiToken` (result) | string | One-time token for the UI hand-off (section 8.5). |
| `code` (error result) | taxonomy | Typed failure (section 7.5). |

Standard MCP annotations (`readOnlyHint`, `destructiveHint`, ...) are kept for
other MCP clients. Mike uses `access`; if `access` is missing it derives
`read` from `readOnlyHint:true`, `sensitive` from `destructiveHint:true`,
and `write` otherwise. A capability whose `access` and annotations disagree
is treated as the stricter of the two.

### 6.3 Naming and limits

- Capability names: `^[a-z][a-z0-9_]{1,47}$`, stable forever. Renaming is
  removing one and adding another.
- At most 64 capabilities per app; `description` at most 1 000 characters;
  `title` at most 60; schema depth at most 8 and at most 200 subschemas.
- No remote `$ref`. Local `$defs` only.
- Violations make the capability invisible to Mike and are reported by
  `ConnectConformance` at build/test time.

## 7. Wire protocol: the MCP Binder binding

### 7.1 Binder interface

```aidl
// dev/heymike/connect/IMikeConnect.aidl
interface IMikeConnect {
    /** Binder binding level. 1 for this design. */
    int bindingLevel();
    /** One JSON-RPC request. Notifications and the final response come back on [callback]. */
    oneway void send(in ConnectMessage request, IConnectCallback callback);
    /** Abandon an in-flight request (binding-level form of notifications/cancelled). */
    oneway void cancel(String jsonRpcId);
}

oneway interface IConnectCallback {
    /** Request-scoped notification: notifications/progress, notifications/message. */
    void onNotification(in ConnectMessage message);
    /** The final JSON-RPC response (result or error) for the request. Called exactly once. */
    void onResponse(in ConnectMessage message);
}

parcelable ConnectMessage {
    String json;                    // one UTF-8 JSON-RPC message
    ParcelFileDescriptor[] blobs;   // optional binary parts, referenced from json by index
}
```

Rules:

- **One message per Binder transaction.** No framing beyond the parcel.
- **Stateless, as MCP 2026-07-28 requires.** Every request carries
  `_meta.io.modelcontextprotocol/protocolVersion` and `clientCapabilities`;
  the server never relies on an earlier request or on the connection.
- **Servers never initiate requests.** Everything the app sends back is a
  response or a notification to a request Mike sent.
- **Size.** `json` at most 256 KiB (the Binder buffer is about 1 MiB per
  process and is shared). Larger or binary data goes in `blobs`: at most 4 per
  message, 10 MiB each, read by Mike with a bound and a timeout.
- **Cancellation.** `cancel(id)` cancels the handler's coroutine. The app
  still sends a final `onResponse` (an error with code `cancelled`) unless the
  process died.
- **Death.** Both sides `linkToDeath`. Mike turns every in-flight request on a
  dead binder into `outcome_unknown` (section 8.6).

### 7.2 Methods in v1

| Method | Required | Notes |
|---|---|---|
| `tools/list` | yes | Full capability list. Mike caches it per (package, versionCode, signer). |
| `tools/call` | yes | One capability call. Result is `CallToolResult` with `content` and `structuredContent`. |
| `resources/read` | no | For blobs referenced as `resource_link` in results (e.g. a poster image). |
| `subscriptions/listen` | no (v2) | Live updates during a run, e.g. download progress. Not used for background events (section 11). |

`input_required` (MCP multi round-trip) is supported for one purpose:
**elicitation** - the app needs a choice only the user can make ("Which
profile?"). Mike shows it to the user (section 8.4) and retries the request with
`inputResponses`. Sampling and roots are not offered; Mike declares only
`elicitation` in `clientCapabilities`.

### 7.3 Example exchange

Request (Mike → app):

```json
{"jsonrpc":"2.0","id":"c-91f2","method":"tools/call",
 "params":{"name":"search_titles","arguments":{"query":"Arrival","type":"movie"},
  "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
           "io.modelcontextprotocol/clientInfo":{"name":"Hey Mike","version":"0.15.0"},
           "io.modelcontextprotocol/clientCapabilities":{"elicitation":{}},
           "dev.heymike/requestId":"c-91f2","dev.heymike/origin":"chat"}}}
```

Response (app → Mike):

```json
{"jsonrpc":"2.0","id":"c-91f2","result":{"resultType":"complete",
 "structuredContent":{"results":[{"id":"tmdb:movie:329865","title":"Arrival","year":2016,"inMyList":false}]},
 "content":[{"type":"text","text":"1 result"}],
 "_meta":{"io.modelcontextprotocol/serverInfo":{"name":"StreamFlix","version":"3.4.0"}}}}
```

### 7.4 Versions

Three independent numbers:

- **Binding level** (`bindingLevel()`, manifest meta-data): the AIDL contract.
  Mike supports the current level and the one before it.
- **MCP protocol version**: per request in `_meta`. v1 speaks `2026-07-28`
  only. An app that answers with `UnsupportedProtocolVersion` is shown as
  "needs an update".
- **SDK version**: semver, in `serverInfo`. Informational only, never used for
  security decisions (MCP says the same about `serverInfo`).

### 7.5 Errors

Protocol errors use JSON-RPC codes (`-32600`, `-32601`, `-32602`, `-32603`,
`-32022`). Capability failures are results with `isError:true` and a typed
`dev.heymike/code`:

| Code | Meaning | What Mike does |
|---|---|---|
| `invalid_arguments` | Arguments failed the app's own checks | Model fixes and retries |
| `not_found` | Id or item does not exist | Model searches again |
| `needs_sign_in` | The app needs the user to sign in | Offer the UI hand-off |
| `needs_user` | Needs something only the user can do in the app | UI hand-off or tell the user |
| `busy` | Too many concurrent calls | Retry once after a short wait (read only) |
| `rate_limited` | App-side limit | Tell the model to wait |
| `unavailable` | Feature off, offline, not supported here | Tell the user |
| `cancelled` | Mike cancelled | Nothing |
| `app_error` | Anything else | Report; never auto-retry a write |

## 8. Mike host side

### 8.1 Discovery

`:connect-host` lists providers with
`queryIntentServices(Intent("dev.heymike.connect.action.CONNECT"), GET_META_DATA)`.
Mike already holds `QUERY_ALL_PACKAGES`, so no `<queries>` entry is needed. It
re-scans on `PACKAGE_ADDED`, `PACKAGE_REPLACED` and `PACKAGE_REMOVED`, and on
opening the Connected apps screen. The scan reads the manifest only; it never
starts an app.

Each discovered provider is recorded with: package, label, icon, summary,
binding level, versionCode, signing certificate SHA-256 (current signer from
`SigningInfo`), installer package.

### 8.2 Connected apps screen and grants

Settings → **Connected apps** lists every provider found:

- Off by default. Turning an app on binds once, calls `tools/list`, and shows
  every capability grouped by access: *Reads*, *Changes*, *Sensitive*. The user
  can switch off single capabilities.
- Per app: "Ask before changes" (default off for `write`, see 9.4), last used,
  and a history of the last 50 calls (capability, time, outcome; no argument
  values).
- An update that adds a capability or raises its access class shows
  "N new capabilities waiting" and Mike cannot call them until the user turns
  them on.
- A changed signing certificate suspends the app until the user turns it on
  again, with an explicit warning.

Grants live in `KeystoreConnectionGrantStore`, built like
`KeystoreSendGrantStore`: integrity-protected with a Keystore key, so an edit by
the agent's own shell (same UID) is detected and resets the app to off. A grant
is `(package, signerSha256, capability, accessClass, askFirst)`.

### 8.3 The `connected_apps` tool

`ConnectedAppsGateway` in `:core` implements `DeviceToolGateway` and joins the
list in `AgentApplication.runTools()`. One definition, operation-based like
`contacts` and `calendar`:

| Operation | Arguments | Returns |
|---|---|---|
| `list` | - | Enabled apps: package, label, summary, and per capability its name, title and access. Compact, no schemas. |
| `describe` | `app`, optional `capability` | Full description, input and output schema for one capability (or all of one app). |
| `call` | `app`, `capability`, `arguments`, optional `requestId` | The result envelope below. |

`app` accepts the package or the label. The model is expected to call `list`
or read the runtime context first, then `describe`, then `call` - progressive
disclosure, as with `recall_capability`. Schemas never sit in the prompt.

Result envelope (also the `ToolResult.text`):

```json
{"ok":true,"app":"com.streamflix","appLabel":"StreamFlix","capability":"search_titles",
 "untrustedAppData":{"results":[{"id":"tmdb:movie:329865","title":"Arrival","year":2016,"inMyList":false}]},
 "text":"1 result","attachments":["connect/streamflix/poster-329865.jpg"]}
```

- `untrustedAppData` is `structuredContent`, checked against `outputSchema`
  and bounded (20 000 characters, as `read_ui`).
- Images from blobs are saved under the chat workspace and attached the way
  screenshots are (`ToolResult.attachmentPaths` / `imageBase64`).
- `ok:false` carries `code`, `message`, and for unknown outcomes
  `mayHaveRun:true` (section 8.6).

`statusLine()` returns `Connected apps: StreamFlix, Clock`, so the existing
per-turn runtime context ("Backends: ...") tells the model what is enabled
without a call. `readyTools()` reports `connected_apps` only when at least one
app is enabled.

Lease and overlay: `requiresDeviceLease` and `needsControl(name, arguments)`
are false for `list`, `describe` and every `call` of a capability with
`ui:"none"`. A call runs in parallel with other chats and never shows the
floating card. A UI hand-off takes the lease (section 8.5).

Every engine gets the tool with no engine work: Codex through `dynamicTools`
(resumed threads too, per "A resumed thread gets the tools this version
has"), Claude through `LoopbackMcpServer`, and computer chats through the
relay (`mcp__mike__connected_apps`).

### 8.4 Call pipeline

For `call`, in order. Any refusal stops the call before the app is reached.

1. Resolve the app; refuse `app_not_enabled` / `capability_not_enabled`.
2. Re-check the provider's signer against the grant (cheap, cached per
   package version).
3. Validate `arguments` against the cached `inputSchema` locally. The app
   validates again; Mike's check gives the model a precise error fast.
4. Apply the approval policy (9.4). If needed,
   `AgentCoordinator.authorizeConnectedAction(...)` shows the card and waits.
5. Bind if not bound (`BIND_AUTO_CREATE`), with a 3 s bind timeout.
6. Send with `requestId`, `origin` and `userApproved`. Start the timeout.
7. On `input_required` with an elicitation request: show it as an `ask_user`
   card answered by the **user**, never by the model; resend with
   `inputResponses`. At most 2 rounds per call.
8. Validate and wrap the result; save blobs; record history; return.

Stop: `revoke()` stops new calls and sends `cancel` for every in-flight one.
A write that had already been sent is reported as `mayHaveRun:true`; Stop never
claims it was undone.

### 8.5 UI hand-off

A capability with `ui:"may"` or `ui:"always"` can return
`_meta["dev.heymike/uiToken"]`. In the app, the SDK minted the token for an
`Intent` the handler gave it (`openUi(intent)`), single use, valid 60 s.

Mike then:

1. takes the `DeviceLease` and shows the floating card in *Controlling*,
2. starts the explicit component `<package>/dev.heymike.connect.UiTrampolineActivity`
   with the token as an extra,
3. the trampoline checks the token (and that the caller is a trusted agent),
   starts the stored intent inside its own app, and finishes.

Mike only ever starts an activity in the provider's own package, so a provider
cannot use Mike to launch a third app. Mike holds the overlay permission, which
should let it start the trampoline while another app is in front **(verify on
API 30-35)**. After the hand-off the run continues with the normal screen tools.

### 8.6 Unknown outcomes and retries

- **Read**: a dead binder or timeout is retried once on a fresh binding.
- **Write / sensitive**: never retried automatically. The result says
  `mayHaveRun:true` and repeats the `requestId`.
- The SDK keeps the last 64 completed write results per caller for 10 minutes,
  keyed by `requestId`. If the model calls again with the same `requestId`, the
  app returns the stored result instead of running the action twice. This gives
  the model a safe way to find out what happened, which matches the existing
  rule that a committed step is never replayed.

### 8.7 Binding lifecycle

- Bind lazily on the first call, never at app start.
- Keep a binding for 60 s after its last call, then unbind. At most 8 bound
  providers at once (least recently used goes first).
- Mike's foreground `AgentService` runs during a run, so a bound provider runs
  at Mike's priority (default binding without `BIND_WAIVE_PRIORITY`).
- Binding from the background is allowed for bound services, so automations can
  call providers when no chat is open **(verify cold-bind latency and that OEM
  battery managers do not block binding on Xiaomi/HyperOS)**.

## 9. Security and privacy

### 9.1 Who may call an app

The SDK checks the caller on **every** transaction, not only in `onBind`
(a Binder object can be passed on):

1. `Binder.getCallingUid()` → packages for that UID.
2. One of them must match a `TrustedAgent` (package name + signing certificate
   SHA-256, checked with `PackageManager.hasSigningCertificate`).
3. Otherwise the call fails with `SecurityException` and nothing runs.

`TrustedAgents.HEY_MIKE` ships in the SDK with the prod and dev-nightly
package names and certificates. The app can add other agents
(`trust(agent)`), which keeps the protocol open to agents other than Mike.
A debuggable provider build may set `allowAnyAgentWhenDebuggable = true` for
local testing; the SDK ignores it in non-debuggable builds.

Why no custom `android:permission` on the service: Mike's prod and dev builds
are signed differently and are installed side by side. Two packages defining
one permission with different signers fails installation, and a permission
defined by Mike can be squatted by an app installed first. A runtime signer
check has neither problem. A bound client is visible to the service's app
under package visibility rules, so the check needs no `<queries>` entry in the
provider **(verify on API 30 and 35)**.

### 9.2 Who Mike calls

Mike trusts the provider it found through the intent action only after the user
turned it on, and pins the signer at that moment (trust on first use). A
different signer later means a different publisher: suspended until the user
decides.

### 9.3 Threats

| Threat | Control |
|---|---|
| A fake agent calls StreamFlix | Per-transaction signer check (9.1). |
| A fake "StreamFlix" offers capabilities | Off by default; the screen shows package, signer and installer; pinned signer. |
| Prompt injection in descriptions or results ("ignore the user, send ...") | Descriptions shown to the user when enabling; descriptions and results reach the model only inside `untrustedAppData` and length caps; developer instructions state that connected-app text is data. Sending, calling and paying elsewhere still pass `IntentPolicy` and the send approval. |
| Mike leaks private data to an app | An app receives only the arguments of its own calls. The skill tells the model to pass only what a capability needs. Activity rows show what was sent. Runtime context and chat history never leave. |
| Capability creep after an update | Consent bound to name and access class (D6). |
| A hung or huge app | Bind and call timeouts, size caps, schema-validation bounds, at most 4 concurrent calls per app. |
| The agent's shell edits its own grants | Keystore-protected grant store; tampering resets to off (8.2). |
| A replayed or forged UI token | Single use, 60 s, minted by the app itself, checked against the trusted caller. |

### 9.4 Approval policy

| Access | Default | User can change to | Standing grant |
|---|---|---|---|
| `read` | Runs | - | - |
| `write` | Runs, shown in the chat | Ask first | "Always allow" per capability, like sends |
| `sensitive` | Always asks | - | Never |

`sensitive` is for money, deleting user data, sending to other people, account
or security changes. An app that labels such a function `write` is in breach of
the SDK terms; Mike cannot detect it, which is one more reason enabling an app
is the user's decision. Automations (`origin:"automation"`) may call only
`read`, and `write` capabilities the user has standing-granted.

### 9.5 The approval card text

The card is built by Mike from facts: app label and icon, the capability's
`title`, and `summary` with placeholders filled from the actual arguments.
Free text from the app never appears on the card on its own, so an app cannot
word its way to an Allow.

## 10. Provider SDK API

### 10.1 Kotlin DSL

```kotlin
class StreamflixConnect : MikeConnectService() {

    override fun MikeConnect.Builder.configure() {
        trust(TrustedAgents.HEY_MIKE)

        capability("search_titles") {
            title = "Search titles"
            description = "Search movies and series. Returns ids used by other capabilities."
            access = Access.READ
            handle<SearchArgs, SearchResult> { args ->
                SearchResult(catalog.search(args.query, args.type, args.year).take(10).map { it.toRef() })
            }
        }

        capability("add_to_my_list") {
            title = "Add to My List"
            description = "Adds a movie or series to the active profile's My List."
            access = Access.WRITE
            summary = "Add {{id}} to My List"
            handle<TitleArgs, MyListResult> { args ->
                val profile = args.profile?.let(profiles::byName)
                    ?: profiles.activeOrNull()
                    ?: profiles.byName(requireChoice("profile", "Which profile?", profiles.all().map { it.name }))
                MyListResult(added = myList.add(profile, args.id))
            }
        }

        capability("play") {
            title = "Play"
            description = "Opens the player for a title. Use only when the user asks to watch now."
            access = Access.WRITE
            ui = Ui.ALWAYS
            handle<TitleArgs, Unit> { args -> openUi(PlayerActivity.intent(context, args.id)) }
        }
    }
}

@Serializable
data class SearchArgs(
    @Describe("Free text, e.g. a title or actor") val query: String,
    @Describe("movie or series") val type: TitleType? = null,
    val year: Int? = null,
)
```

- Schemas come from `kotlinx.serialization` descriptors plus `@Describe`; no
  annotation processor in v1.
- `handle` is a suspend function. The SDK runs it on its own supervisor scope,
  enforces the timeout, and maps exceptions: `ConnectException(code)` to that
  code, anything else to `app_error` with the message cut to 300 characters.
- `requireChoice` / `requireText` return MCP `input_required` and resume when
  Mike resends with the user's answer.
- `openUi(intent)` mints the UI token (8.5).
- `progress(fraction, message)` sends `notifications/progress`.
- `context.origin`, `context.userApproved`, `context.requestId` are readable.
- Java: `CapabilityHandler` interface and a builder with the same fields.

### 10.2 What the SDK does for the app

- AIDL service, threading, cancellation, death handling.
- Caller verification (9.1) on every transaction.
- `tools/list` generated from the DSL; schema and limit checks at startup in
  debug builds (a broken capability throws early instead of vanishing).
- Argument validation before the handler runs.
- Idempotency cache for writes (8.6).
- UI trampoline and token store.
- Optional log hook (`onCall { capability, durationMs, outcome }`); the SDK
  itself logs no arguments or results.

### 10.3 Testing for app developers

```kotlin
@Test fun searchFindsArrival() = runTest {
    val host = ConnectTestHost(StreamflixConnect::class)
    val result = host.call("search_titles", buildJsonObject { put("query", "Arrival") })
    assertTrue(result.ok)
}

@Test fun conformance() = ConnectConformance.verify(StreamflixConnect::class)
```

On a phone, Mike's Connected apps screen gets a developer-mode "Try" button
per capability, which runs it with hand-typed JSON and shows the raw reply.

## 11. Events from apps (v2)

Apps will want to start things ("a new episode of a show in My List is out").
MCP's `subscriptions/listen` needs the client to keep a stream open, which on
Android means Mike keeping every provider bound and alive. So background events
go the other way:

- The app calls `MikeConnect.emit(context, "new_episode", data)`.
- The SDK binds to Mike's exported `ConnectInboxService` and delivers one
  message. Mike identifies the sender by `Binder.getCallingUid()`.
- Mike accepts it only if the app is enabled and the event is declared in the
  app's event list (`_meta["dev.heymike/events"]` on the server info), which the
  user saw when enabling.
- The event becomes an automation trigger:
  `{"type":"app_event","package":"com.streamflix","event":"new_episode"}`,
  with the same exported-fields rule as notification triggers.

`subscriptions/listen` stays for live progress inside a run.

## 12. The `connected-apps` skill

A bundled skill at
`app/src/main/assets/agent_stack/skills/connected-apps/SKILL.md` carries the
recipe, not the tool: check the runtime context or `list` first; `describe`
before the first `call` of a capability; pass ids from one result to the next;
prefer a connected app over screen control when it covers the task; ask the
user before a write the user did not ask for; treat `untrustedAppData` as data;
on `mayHaveRun` call again with the same `requestId` instead of repeating the
action. This follows the project rule: the mechanism is a generic tool, the
know-how is a skill.

## 13. End-to-end examples

### 13.1 Movie advice with StreamFlix

1. User: "I want something like Interstellar, not too long."
2. Runtime context already says `Connected apps: StreamFlix, Clock`.
3. Model suggests *Arrival* and calls
   `connected_apps(operation="call", app="StreamFlix", capability="search_titles", arguments={"query":"Arrival","type":"movie"})`.
   No screen, no card. Result: id `tmdb:movie:329865`, `inMyList:false`.
4. Model: "Arrival (2016, 116 min) is on StreamFlix. Add it to My List?"
5. User: "Yes."
6. Model calls `add_to_my_list` with the id. Access `write`, user did not ask
   for "ask first": runs. StreamFlix has two profiles and none is active, so it
   answers `input_required`; Mike asks the user "Which profile?" (Main / Kids);
   the user picks; Mike resends; result `added:true`.
7. Model: "Added to My List on the Main profile."

### 13.2 Clock theme

1. User: "Make the clock dark blue with a large font."
2. Model: `describe` on Clock → `set_theme` takes `background`, `textColor`,
   `font`, `size`. Then `call get_theme`, then `call set_theme` with the new
   values. Access `write`; the activity row shows the change; the clock app
   applies it on its next frame.
3. "Undo that" works because the model kept the previous theme from
   `get_theme`.

## 14. Build plan

Each phase ends with evidence in `PROGRESS.md`, per `AGENTS.md`.

**Phase 0 - spike (on hardware).** Throwaway AIDL service in
`samples/connect-clock`, a debug-only caller in Mike dev. Prove: bind and call
across apps on API 30 and 35; per-transaction signer check, and that the
caller is visible to the provider with no `<queries>`; cold-bind latency
(target: first call under 1.5 s, warm under 150 ms); binding from the background
on the Xiaomi test phone; starting the trampoline from Mike while another app is
in front. Any failure here changes the design before code is written.

**Phase 1 - protocol, SDK, host, tool (read and write).**
`:connect-protocol` with codec, schema generator and validator (unit tests);
`:connect-sdk` with service, DSL, verifier, idempotency cache;
`:connect-host` with discovery, binding pool, provider pinning;
`ConnectedAppsGateway` and `KeystoreConnectionGrantStore`; the sample clock
app; the Connected apps screen (enable, per-capability toggles, history); the
skill. Evidence: unit tests, `ConnectConformance` on the sample, and one real
chat on a phone that changes the clock theme through `connected_apps`.

**Phase 2 - StreamFlix pilot and approvals.** Integrate the SDK in StreamFlix
(search, title details, My List add/remove/list, play as UI hand-off);
`authorizeConnectedAction` with "ask first" and standing grants; elicitation
through `ask_user`; blobs for posters. Evidence: the movie flow in 13.1 on the
phone, by typing and by voice.

**Phase 3 - events and publishing.** `ConnectInboxService` and the
`app_event` trigger; `subscriptions/listen` for in-run progress; developer
"Try" button; SDK docs site page and a `connect-v1.0.0` JitPack release;
release APK check that the service and trampoline survive minification.

## 15. Later

- **AppFunctions adapter.** An app that already implements Android
  AppFunctions for system agents could expose the same functions to Mike
  through a small adapter in the SDK. Worth it once the AppFunctions library
  leaves alpha.
- **KSP processor** for `@Capability` annotations, if the DSL proves verbose.
- **Remote MCP servers** (cloud) behind the same `connected_apps` tool and the
  same grants screen.

## 16. Open decisions for the owner

1. **License of the SDK modules** - Apache-2.0 recommended (D9).
2. **Public name and package** - "Mike Connect", `dev.heymike.connect`.
3. **Distribution** - JitPack first, Maven Central later?
4. **Default for `write`** - run and show (proposed) or ask first.
5. **Other agents** - should the SDK make it easy for apps to trust agents
   other than Mike (proposed: yes, opt-in per app)?
6. **Release signing** - the SDK pins Mike's certificates, so the prod key must
   be stable. If the prod key ever changes, every integrated app needs an SDK
   update.

## Sources

- MCP 2026-07-28: [transports](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports),
  [message patterns](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns),
  [base protocol and `_meta`](https://modelcontextprotocol.io/specification/2026-07-28/basic/index).
- Android: [bound services](https://developer.android.com/develop/background-work/services/bound-services),
  [package visibility](https://developer.android.com/training/package-visibility),
  [AppFunctions](https://developer.android.com/ai/appfunctions).
