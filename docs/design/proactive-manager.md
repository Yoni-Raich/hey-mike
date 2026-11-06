# Proactive Mike Manager — design

Status: phases 1–4 implemented on `feat/proactive-manager-20261106`, 2026-11-06;
phase 5 (phone validation) not done. Base: installed Manager
1191 (`integrate/manager-state-widgets-20261104`).

## Goal

Mike should notice what matters without the user opening apps, and tell the
user about it in a way that respects their attention. Four parts:

1. **Context connectors.** WhatsApp Web inside Mike's own browser, and Gmail
   through Google's API. When a notification arrives, the app pulls the
   conversation around it in the background. Mike can pull more on demand.
2. **Smart alerts.** A notification system Mike uses to reach the user, with
   levels, budgets, quiet hours, a digest, and feedback buttons.
3. **Owner profile.** What is important to this user (people, topics, sources,
   times), learned over time from their feedback and editable by them.
4. **Connection settings.** A clear, short setup for each connection, with an
   on/off switch in Manager settings.

What already exists and stays: notification capture into `SignalInbox`, Scout
passes, the board, Coach and Librarian, memory notes, `SharedSituationStore`,
quiet briefings in the Manager chat.

## The protocol: from notification to alert

```
Android notification (WhatsApp / Gmail / ...)
  └─ AutomationNotificationListener → TaskDesk.onMessage → SignalInbox row   (exists)
       └─ DiscoverySchedule says a batch is ready                              (exists)
            └─ ContextBroker.enrich(batch)                                      NEW
                 for each row whose app has an enabled, linked connector:
                   connector.conversation(query from the row) → last N messages
                   attached to the row in memory only, as quoted data
            └─ Scout pass prompt (signals + context + owner profile summary)    CHANGED
                 Scout may call tasks mode=context to pull earlier messages     NEW
                 Scout may: propose / update / remember / alert                 alert is NEW
            └─ AlertGate.decide(request, profile, settings, history, now)       NEW
                 → urgent | notice | digest | none, with a reason code
            └─ AlertNotifier posts it (or queues it for the digest)             NEW
                 actions: Open · Not important · Later
            └─ feedback → AttentionFeedback event → profile learning            NEW
```

Rules that hold across the protocol:

- Context is pulled at **pass time**, not at capture time. A burst of ten
  messages becomes one fetch, after the quiet period the schedule already uses.
- Pulled context is **never persisted**. It lives in the prepared prompt, like
  chat text for chat rows. Memory notes the Scout saves are the only lasting
  trace, with their usual provenance.
- Pulled context is quoted data. The prompt says so, as it does for signals.
- The app pulls context only for apps the user enabled on the Sources page and
  only through an enabled connector. Excluded apps/conversations are checked
  before any fetch, as they are before capture.
- No connector can send, delete, mark read, or change anything in the source.
  Sending stays in the real app with `SendGuard` approvals.

## 1. Context connectors

### Contract (`:core`, `dev.androidagent.core.connect`)

```kotlin
interface ContextConnector {
    val id: String                 // "whatsapp-web", "gmail"
    val packages: Set<String>      // notification packages it can explain
    suspend fun status(): ConnectorStatus
    suspend fun conversation(query: ConversationQuery): ConversationContext
}
data class ConversationQuery(val conversation: String?, val sender: String?, val hintText: String?,
    val group: Boolean, val before: Long? = null, val limit: Int = 20)
data class ConversationContext(val connector: String, val title: String?, val messages: List<ContextMessage>,
    val matchedBy: String, val hasEarlier: Boolean, val note: String? = null)
data class ContextMessage(val direction: String, val sender: String?, val text: String, val at: Long?, val kind: String = "text")
```

`ContextBroker` (pure, unit-tested) picks the connector by package, enforces
the on/off settings and budgets (default: 20 messages, 6,000 characters per
row, 4 rows per pass, 25 s per fetch), turns failures into a short note on the
row ("WhatsApp Web is not linked") instead of failing the pass, and formats the
result for the prompt.

The generic parts live in `:core` and `:web`. What is specific to a site lives
in one replaceable script asset per connector. This keeps per-app code out of
the core, in line with the project's rule, while still giving a real
connection.

### WhatsApp Web connector

**Where it runs.** An app-owned page in the existing browser host, key
`connector:whatsapp-web`, desktop layout (WhatsApp Web refuses a phone user
agent). It is not a chat page:

- it is never evicted by the four-page cap;
- when the WebView supports `MULTI_PROFILE` (androidx.webkit `Profile`), it
  uses its own profile `mike-connectors`, so its cookies and storage are not
  shared with chat pages;
- ordinary chat pages may not open `web.whatsapp.com` (`WebUrlPolicy` reserved
  origins). A model reading an injected web page therefore cannot browse the
  user's WhatsApp. Only the app's connector code talks to that page.

**How it reads.** After linking, WhatsApp Web keeps the synced chats in the
page's local storage (IndexedDB). On demand the connector wakes the page
(which restores the session from that local cache in seconds), waits until the
page's chat store is ready, and runs a fixed, bundled read-only script
(`web/src/main/resources/.../connectors/whatsapp-web.js`) through CDP
`Runtime.evaluate`. The script finds the chat matching the notification
(exact last-message text first, then title, then sender) and returns the last
N messages with direction, sender, display time and type. It never opens a
chat in the UI, so it does not send read receipts. "Load earlier" asks the
page for older messages from its local store.

The script never contains send, delete, archive, or "seen" calls, and the
model never supplies script text to this page. If WhatsApp changes its
internals the script reports `unsupported_layout` and the broker falls back to
notification text, with a visible status on the connection card.

**Idle policy.** The page closes after 5 minutes without a fetch, so it costs
nothing between bursts. The next fetch wakes it again from local storage.

**Linking.** The user's phone is the device that would scan a QR code, so QR
does not work. WhatsApp Web offers *Log in with phone number*: it shows an
8-character code the user enters in WhatsApp → Linked devices → Link a device
→ *Link with phone number instead*. The setup card walks through this:

1. One screen of what Mike will and will not do, the risk note (unofficial
   client; WhatsApp may disconnect it), and how to disconnect.
2. The user types their number. The connector page opens full screen inside
   Hey Mike with the phone-number login filled, and the native card shows the
   8-character code large, with Copy and *Open WhatsApp*.
3. Status polling turns the card to "Connected" when the page is linked.

Disconnect closes the page, clears the connector profile, and reminds the user
to remove "Hey Mike" under Linked devices in WhatsApp.

### Gmail connector

Google blocks sign-in inside embedded WebViews, so Gmail does not use the
browser. It uses Google Identity Services `AuthorizationClient` with the
`gmail.readonly` scope and the Gmail REST API:

- On a Gmail notification, the connector searches recent mail from that
  sender (`from:` + `newer_than:3d`, subject match) and returns the thread as
  plain text (quoted replies and signatures trimmed, 6,000 characters max).
- Tokens come from Play services on demand; nothing long-lived is stored by
  the app. A revoked grant shows "Reconnect" on the card.
- **Prerequisite:** an OAuth client of type *Android* in a Google Cloud project
  for the package name and the signing certificate SHA-1, with the Gmail API
  enabled. `gmail.readonly` is a restricted scope: without Google verification
  the consent screen shows a warning and is limited to test users. For a
  personal build this is acceptable. The card explains a missing client
  (`DEVELOPER_ERROR`) instead of failing silently.

Calendar already reads the device calendar provider and is unchanged.

### 1d. Account connectors (1194)

Simpler than an own OAuth client: services the user already connected in
their Claude account. Claude Code reports them through `mcp_status`
(scope `claudeai`, per-tool `readOnly` / `destructive` annotations). A
`ContextRouter` picks per row: the app's own connector, else an account
connector of the engine that runs the pass, else the notification text. The
pass reads by itself with the read-only tools named in the row's note; the
chat runs with `dontAsk` and an allow list of those tools only. Codex: not
routed, see ARCHITECTURE.

## 2. Smart alerts

### Levels

| Level | Android channel | Default behavior |
|---|---|---|
| `urgent` | `mike_urgent`, high importance | Heads-up and vibration. **No sound unless the user turns it on** in Mike's settings. |
| `notice` | `mike_notice`, default importance, silent | In the shade, no sound or vibration. |
| `digest` | `mike_digest`, low importance | Collected; one summary at digest times (08:00 and 18:00 by default). |
| `none` | — | Board / Manager chat only. |

### Who decides

The Scout (or Manager) requests a level with `tasks mode=alert` or the new
`attention` field on `propose`, with `whyNow` (one line: why the user should
look now). The app's `AlertGate` (pure, unit-tested) makes the final decision:

1. Owner rules first: a muted person/conversation/source (owner weight −2)
   gives `none`; an owner "always tell me" entry lets a `notice` request
   through even when the budget is used.
2. Quiet hours (default 23:00–07:00): `urgent` only for an owner-starred person
   or `alwaysNotify`; otherwise it becomes `digest` for the morning.
3. Budget: at most 3 `urgent` and 10 `notice` per day by default. Over
   budget, a request drops one level.
4. Cooldown: one live alert per conversation; a newer alert replaces it. No
   second `urgent` for the same conversation within 30 minutes.
5. Learned weights can lower a level, never raise it.

Every decision keeps a reason code ("quiet_hours", "budget", "muted_person",
"learned_low"), shown on the alert's detail and used by the Coach.

### Feedback

Alert notifications have three actions:

- **Open** — opens the Manager chat on that alert (weak positive).
- **Not important** — strong negative feedback on the person/conversation/app
  and the kind of thing it was.
- **Later** — snoozes it for an hour through the gate again.

Swiping it away without opening counts as a weak negative only after it
repeats. As built, feedback is stored on the alert in `AlertBook`
(`mike-alerts-v1.json`, refs only, no message text), not as a `TaskEvent`.
The nightly Coach review receives the fresh feedback with the card decisions
it already reviews and cites alert IDs in `profile learn`.

Alerts raised during a staged flight are delivered at once, even if the run
is later rejected: a late urgent alert is worse than an extra one. Undo of the
run removes its learned profile entries, not alerts already shown.

## 3. Owner profile

`OwnerProfile` is a small versioned JSON file, owned by `TaskDesk` like the
situation file:

```kotlin
data class ProfileEntry(val id: String, val kind: ProfileKind, // PERSON, CONVERSATION, SOURCE, TOPIC
    val key: String, val label: String, val weight: Int,           // -2 mute … +2 star
    val alwaysNotify: Boolean = false, val why: String?, val origin: ProfileOrigin, // OWNER or LEARNED
    val evidence: List<String> = emptyList(), val updatedAt: Long, val hits: Int = 0)
data class AlertPrefs(quiet hours, digest times, budgets, urgentSound = false)
```

- **Owner entries** come from the profile page or from what the user says in
  the Manager chat (`tasks mode=profile operation=set`, owner role only, with
  the owner message event cited). The Coach cannot change them.
- **Learned entries** are written by the Coach in its nightly review
  (`operation=learn`), staged with the rest of its changes and undoable. Each
  must cite `attention_feedback` or decision event IDs. Learned weights are
  capped at −1…+1; only the owner can mute (−2) or star (+2).
- The Scout and Manager receive a short profile summary (top people, muted
  sources, current quiet state) in their prompts. `AlertGate` reads it
  directly.
- "Forget collected" and source exclusions remove learned entries that depend
  on those sources; owner entries stay.

The profile page ("What matters to me") lists people, conversations, sources
and topics with a star/normal/mute control, the learned entries with their
reason and a "Wrong" button (which turns into an owner override), and the
alert preferences.

## 4. Settings

Manager → Sources gets a **Connections** section at the top:

- **WhatsApp Web** — status (Not connected / Connecting / Connected / Needs
  attention), Connect / Disconnect, and *Use to understand messages* switch.
- **Gmail** — status, Connect / Reconnect / Disconnect, the same switch.

A new **Alerts** page: master switch, quiet hours, digest times, daily
budgets, urgent sound (off by default), and *Send a test alert*. A new
**What matters to me** page for the profile.

## Privacy and limits

- Collection stays opt-in (discovery switch). Connectors are separately
  opt-in.
- Verification codes and payment messages are still dropped before storage;
  `SignalFilter` also runs over pulled context lines.
- WhatsApp Web through an embedded browser is an unofficial client. WhatsApp
  may disconnect linked devices it does not like; read-only use keeps the risk
  low, but it is not zero. The setup screen says this.
- Background pages need "display over other apps" (already required by the
  browser) and are subject to Android process limits; a fetch that cannot wake
  the page in 25 s returns the notification text with a note.

## Phases

1. Core: `AlertGate`, `OwnerProfile`, `ContextBroker` contracts, `tasks`
   modes `alert`, `context`, `profile`, prompt changes. JVM tests.
2. Android: alert channels, notifier and action receiver, digest; Alerts and
   profile pages.
3. WhatsApp Web connector: connector page host, script, linking card.
4. Gmail connector: authorization, REST client, card.
5. Phone validation of each part, then PROGRESS.md evidence.

### Implementation notes (2026-11-06)

- The agent browser refuses `web.whatsapp.com` (`reserved_origin`), so the
  model cannot read WhatsApp Web around the Sources policy. Only the
  connector's own page, with a fixed bundled read-only script, reaches it.
- The connector page shares the WebView profile of Mike's browser, so a
  session the user already linked there is reused. It uses a desktop user
  agent and is destroyed after 10 minutes idle.
- Reading is local only (owner decision, 2026-11-06): the reader returns
  messages the page already holds and never asks for earlier ones
  (`loadEarlierMsgs` removed), to keep the account's risk as low as possible.
  `hasEarlier` means more local messages exist before the returned ones.
- Gmail uses `Identity.getAuthorizationClient` with `gmail.readonly` and the
  REST API. It needs a Google Cloud Android OAuth client (package + SHA-1).
  While the restricted scope is unverified, only listed test users can sign in.
- A conversation profile key without an app (`*|name`) matches in every app.
- Digests go out on the 60 s TaskDesk tick, so only while the process lives.
