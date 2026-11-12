# Proactive Mike: a task board that Mike keeps building

25 October 2026. Review of the proactive research and of the user's proposal, revised after the user's later instructions: the board is built continuously from all notifications (WhatsApp, email and others), the calendar and earlier sessions; the user picks the model; a nightly review sharpens the guidance; memory upkeep compacts memory dynamically; a Manager chat and a small named team are the board's faces. Design only: nothing was built, installed or tested. Source baseline: `origin/dev` at `053cc14ab94582efba6f1015307fde2bcdb05169` (version 1164). The phone reports 1167 from the `pc-approvals` branch; the two are not proven identical.

## 1. Decision

**Build the user's proposal: a Tasks board where Mike lists the work he noticed, and the user marks each item "Mike, do it", "I'll do it" or "Not relevant".** Mike keeps the board current in the background: cheap collectors run all the time, and a short model pass runs whenever something new arrived.

Two rules make it work:

1. **Collect all the time, think in small batches.** Collectors use no model. A model pass runs only when there is new material, and never as an always-thinking loop.
2. **Noticing and doing are different runs.** A discovery pass may read and propose, nothing else. Work starts only from the user's tap, in an ordinary Mike chat with every existing gate in place.

## 2. Why the inbox is the right first step

| Research finding | What the board does with it |
|---|---|
| Proposal precision is low. Proactive Agent: precision 49.78% at 100% recall. FingerTip 20K: intent prediction 7–13% for models, 30% for humans. Spotlights: precision .70 at top 10, .45 at top 20. | Half the proposals may be wrong. A human gate turns a wrong proposal into one tap, not a wrong action. |
| Proactive Agent's own method is accept/reject feedback on proposed tasks. | The three buttons are that label stream, from the real user, on real tasks. |
| Precision Proactivity: model-initiated task switching is the strongest negative predictor of work quality. | A board is pull, not push. Continuous discovery does not mean continuous interruption. |
| Horvitz mixed initiative: compare act, ask, defer and silence, with asymmetric costs. | v1 always "asks", cheaply and without interrupting. The data later shows which kinds can move to "act". |
| The synthesis asked for one place showing what Mike watches, prepared, plans, and what needs a decision. | This is that place. |
| Prompt injection: discovery reads untrusted content while nobody watches. WhatsApp and email are written by other people. | A propose-only pass has no effect to hijack. The worst case is a bad card the user dismisses. |

The earlier answer proposed quiet preparation before asking. With unknown precision that spends model time on proposals that may be rejected and skips the labels. Quiet preparation is stage 2, for kinds the user already approves often.

## 3. What the research over-built, and what this request brings forward

Most of the architecture chapter's machinery (nine record types, executor permits, owner generations, a movable planner, a Server coordinator, a budget ledger) exists to make **unattended effects** safe. With a tap before every effect, v1 does not need it. The tap is the grant; the phone owns every task; execution reuses `session_agents`.

Continuous discovery brings one part forward from stage 3 to stage 1: **a durable signal inbox.** The research found that the notification path keeps nothing (`AutomationNotificationListener` stores no event, and a skipped event is lost). A board fed from messages needs each new message captured once, deduplicated, kept until a pass has looked at it, and then deleted.

Kept from the research because v1 needs it: separate notice, choose, complete and reach; evidence, why now and how it could be wrong on every card; a topic key for deduplication and "don't suggest this kind"; an expiry; "the run finished" is not "the goal is done"; source text stays quoted data.

## 4. The product

### 4.1 The card

```
Send Dana the signed form                                  [Phone]
Why now: Dana asked on WhatsApp at 14:10; she needs it before Tuesday.
From: WhatsApp · Dana (requested by Dana, not by you)
Mike would: find the signed PDF in Downloads, draft a reply with it attached
Sends a message: yes, after your approval in the chat
[ Mike, do it ]   [ I'll do it ]   [ ✕ ]
```

A card built from someone else's message says so ("requested by Dana, not by you"). Tapping the card opens details and an "Instructions for Mike" box, so the user can fix an almost-right task instead of rejecting it.

### 4.2 The buttons

| Button | Meaning | What happens |
|---|---|---|
| Mike, do it | The need is real; Mike may do the plan shown, plus the user's note. | A chat starts at the card's place. The card follows it. |
| I'll do it | The need is real; the user does it. | The card moves to "Yours" as a plain to-do. |
| ✕ | Not useful. Optional reason: *Already done*, *Not now*, *Wrong*, *Don't suggest this kind*. | The card closes. "Not now" snoozes it. "Don't suggest this kind" suppresses its topic for discovery. |

### 4.3 The board

A compact strip above the chats, in the style of `AutomationStrip` ("3 new tasks · 1 running"), opens a sheet in the style of `AutomationsSheet`:

1. **Needs your decision:** newest and most urgent first, at most 15 open. Older ones expire.
2. **Mike is on it:** running, or waiting for an answer or approval inside its chat.
3. **Check Mike's work:** the chat finished; its result is on the card. *Done* or *Continue*.
4. **Yours:** cards the user took.
5. **History:** done, dismissed, expired and "looks resolved", with the reason.

A card can change while it waits: a later message adds evidence or makes it obsolete ("never mind, I sent it"). Then Mike marks it **looks resolved**, with that evidence, and it moves to History, where the user can restore it.

No push notification in v1. The strip's count is the signal.

### 4.4 The Manager chat

The user asked for a special chat to talk directly with Mike the manager, who works on all the tasks. Recommended. It is the hub chat of section 7 with a face: one per phone, pinned above the other chats, and also opened from the Tasks strip. Its look is calmer than an ordinary chat, with Mike's orb given a face in its header (visual design in `design/`).

What the user can do there:

- Ask about the board: "what's waiting for me today?", "why did you suggest this?", "what do you remember about Dana?"
- Decide in words: "do the first two", "I'll take the Dana one", "drop the delivery ones". Each decision goes through the same transitions as a tap.
- Teach rules: "never suggest delivery tracking" is written to `user.md` in the playbook (section 5.8), the user's own rules file.
- Talk by voice: voice mode can open the Manager instead of an ordinary chat.

What arrives there on its own:

- Results of "Mike, do it" runs. Each run is a child of this chat, so the existing parent continuation brings the result back. The Manager checks it against the card's plan, moves the card to *Check Mike's work*, and says so in one line.
- The nightly review (section 5.6) and memory upkeep (section 5.9) reports, as a short message plus their cards.

What does not happen there: discovery passes, the nightly review and upkeep run in their own hidden chats, so the Manager stays readable. The user can still open "behind the scenes" from the Manager to see them.

**Words count as a decision only when the user said them.** A child's result, a memory note or a quoted message in the Manager's context must never approve a card. The coordinator knows whether the current turn started from a user message or from agent context; the `tasks` decide operation is accepted only in the Manager chat, and only during a turn the user started. This is the same rule the existing child flow already follows: a parent's "yes" is never an approval.

### 4.5 Mike's team

The user proposed a group of agents next to the Manager, each with a name and a role it gets automatically, with an entry and a look different from ordinary chats. The user does not need to open them, but can open any of them to see what it did or is doing, and ask it things directly.

Recommended, as a way to organize and show the work, not as separate minds. This matches the research's best product model: Grok Bot's owning Bot, with its own skills, routines, run history and visible results.

**Who is on the team.**

- **Backstage members, built in.** The hidden chats of this design get a face: the *Scout* runs discovery passes (section 5.3), the *Coach* runs the nightly review (section 5.6), the *Librarian* runs memory upkeep (section 5.9). Each keeps its restricted profile: talking to the Scout ("check my email now") can trigger a pass, but the Scout still cannot act.
- **Task roles, created by the Manager.** When similar tasks keep appearing, for example replies to messages, meeting preparation, or Pc projects, the Manager creates a role for them, names it, and assigns those cards to it from then on. Creation is automatic, as the user asked, and announced in the Manager chat; the user can rename, merge or remove a role. A cap (example: 7 members) forces the Manager to merge roles instead of multiplying them.

**What a member is, in code.** A name, a role line, an avatar, its own playbook file (`mike-playbook/roles/<role>.md`, written and improved like the rest of the playbook), its list of tasks, and a short journal of what it did. It is **not** one endless chat: each task still runs as a fresh child chat (section 7), now labelled with the member. Long-lived chats would grow expensive and drift; a compact journal plus the role playbook gives continuity at a fixed cost.

**What the user sees.** A row of small avatars under the Manager's header, each with a status ring (idle, thinking, working, needs you, done). Tapping one opens its page: current task, timeline of past tasks with outcomes, its playbook, and a message box. A message there starts a turn in that member's own chat, with its journal and role playbook as context, or reaches the task it is running now. The team stays out of the main chat list.

**Rules.**

- Roles organize work and knowledge; they carry no authority. A role cannot widen sources or approve anything. Later, earned autonomy (stage 2) can be granted per role ("let *Messages & replies* send drafts without asking"), by the user's tap only.
- A member may decide on its own cards only in a turn the user started in that member's chat, the same rule as the Manager (section 4.4).
- All members share one budget and the same Stop: Stop on the Manager stops the team.
- They are all Mike. Names make the work readable; the Manager speaks for the team.

## 5. Continuous discovery

### 5.1 Collectors: always on, no model

| Source | How it is captured | What it cannot see |
|---|---|---|
| WhatsApp and every other app's notifications | The existing notification listener, extended: for every app the user has not excluded, store each new message (package, conversation, sender, text, time, notification key). `MessagingStyle` notifications are read message by message so an updated notification does not duplicate old lines. | The user's own outgoing messages ("I'll send it tomorrow"), muted chats, and chats read on another device before the phone notified. Full chat history is out of reach: the accessibility service reads no events by design. |
| Email | Gmail (or another mail app) notifications through the same listener: sender, subject, snippet. Mail apps notify mostly for important mail, which is a useful filter. Optional later: deeper reads through the Pc's Gmail connector when a card needs the full thread. | Mail that does not notify; bodies beyond the snippet. |
| Calendar | On-device calendar reads, already used by the `calendar` capability (`CalendarContract` in `AndroidCapabilityPlatform.kt`). A change observer plus a lookahead: events in the next 48 hours. | Calendars not synced to the phone. |
| Mike's chats | When a chat has been idle for 15 minutes after a turn, its messages since the last scan become one signal (a per-chat cursor). Child and computer results arrive the same way. A one-time backfill scans the last 14 days of chats to seed the board. | Chats started directly on the Pc outside Mike. |
| Pc projects (optional) | When the Pc is connected: open branches, failing CI, review comments, untested gaps in `PROGRESS.md`. | Anything while the Pc is asleep. |

### 5.2 When a model pass runs

- **New material, then a short quiet period.** After a new private message, wait about 10 minutes without further messages in that conversation, so a burst becomes one pass. Groups and email batch for up to 60 minutes. Calendar and chat signals join the next pass.
- **At most one pass every 20–30 minutes**, all new signals in one batch.
- **Nothing new, no pass.** Silence costs nothing.
- **No quiet hours.** The user decided this: the board never interrupts, so a card found at 3am simply waits on the list. Doze may still delay night passes; that is acceptable.
- **The user picks the model.** Tasks settings choose engine, model and effort for discovery passes and for the daily review (section 5.5), from the target's own catalog (`SessionAgentSetup`), not a hard-coded name.
- **Quota guard:** passes share the user's plan. They have an optional daily cap and pause when `account/rateLimits` reports the plan is nearly used up, so they never starve the user's own chats.

A rough expectation is 10–30 passes a day, each small: one batch of signals, the open and recently dismissed cards, and today's calendar. With every app's notifications as input (section 6.3) it can be more. This is an estimate to check in the pilot, not a measurement.

### 5.3 What a pass does

1. Read the batch and the current board.
2. For each signal: new task, more evidence for an open card, a card that now looks resolved, or nothing (most signals).
3. Propose at most a few cards per pass, each with evidence, why now, a plan, the place to run it, side effects, and a useful-until time.
4. Save memory notes for anything that may matter later (section 5.7).
5. Mark the batch processed and delete its raw text (section 6.3).

The quality of discovery lives in two places. A bundled skill, `agent_stack/skills/task-discovery/SKILL.md`, holds the fixed method: what a good task is, what is not (generic advice, things already done, chit-chat, suppressed kinds), how to tell a request from information, and to check the board before proposing. The learned playbook (section 5.8) holds what Mike learned about this user. Only the stores, the approval and the limits are code.

### 5.4 Android limits

The notification listener stays bound while the user has it enabled, so capture continues with the app in the background. It stops after a force-stop until the app is opened again. Model passes need the app process, the Codex runtime and network. Doze can delay them, mostly at night; the board accepts that. The existing notification-rule `agent_turn` path already starts model work from a background event; how reliably that works with the screen off, and what it costs in battery, has not been measured. The project schedules with `AlarmManager` today and has no WorkManager dependency; either can drive passes.

### 5.5 More WhatsApp context: three options

Notifications miss the user's own messages and earlier context. The user asked whether Mike should read WhatsApp itself, or its backup.

**Reading the WhatsApp screen through accessibility.** Mike can do this today with `open_app` and `read_ui`. In the background it does not fit:

- It takes the screen. A run that reads WhatsApp holds the device lease and visible control; doing that while the user is on the phone is exactly the problem the user named.
- It cannot work while the phone is locked, which is most of the time nobody is using it. Mike cannot unlock the phone.
- **Opening a chat marks its messages as read.** The sender sees blue ticks before the user has read the message. That is a social side effect, not a neutral read.
- It is slow and breaks when WhatsApp changes its screens.

So: **never during a discovery pass.** Use it only when the user is present and has chosen it:

1. *At execution.* After "Mike, do it", the execution chat may open the conversation to read what it needs. The user just approved the work and sees the screen.
2. *On the card.* A card the pass marks "partial context" offers **Read the conversation**. The tap runs a short visible read and updates the card.

A pass can mark a card "partial context"; it cannot open WhatsApp itself.

**The local backup.** WhatsApp's local backup (`msgstore.db.crypt14/15` under `Android/media/com.whatsapp/WhatsApp/Databases/`) is encrypted. The key is in WhatsApp's private storage, which another app cannot read without root. The only lawful way to decrypt it is an end-to-end encrypted backup protected by a 64-digit key that the user writes down. Even then:

- It is at most one day old. That is useful for history, not for a live board.
- It needs "All files access" and a decryption library that follows WhatsApp's changing format.
- It holds every chat ever, which makes it the most sensitive file on the phone.

Not recommended as a source. At most, it is a one-time history import later, if the user has such a key and wants it. Whether this phone writes a local backup at all was not checked.

**Richer notifications (recommended).** WhatsApp's `MessagingStyle` notifications usually carry the last several messages of a conversation, sometimes including the user's own replies sent from the notification. Reading all of them, as section 5.1 already plans, is the cheapest gain. Whether this phone's WhatsApp does this has to be checked.

### 5.6 The nightly review agent

The user asked for an agent that goes over the collected and completed tasks and the user's notes once a day, and sharpens Mike's settings and instructions. Recommended, with one boundary: **it may tune how Mike looks and what he proposes, not what Mike may read or do.** Research supports this split: Letta's background memory work, nanobot's memory consolidation and Hermes's skill updates improve behavior, and the research rule says an inferred preference tunes ranking but never widens authority.

Every night, with the model the user chose, in a chat that can write only to the playbook folder (section 5.8):

**Input.** The last day's cards and the last weeks' trend: decisions, dismiss reasons, the user's notes, execution results (done on the first try, or how many *Continue* rounds), "looks resolved" corrections, per-source counts, the memory notes, and the current playbook. Raw signal text is already deleted; cards, decisions and notes are its evidence.

**Output, four kinds:**

1. **Playbook edits, applied directly.** It adds, sharpens, merges and removes rules in the playbook (section 5.8), for example "Yoni ignores delivery-tracking notifications" or "for Dana's requests, propose a draft reply". Each night's changes appear on the board as one "what I changed and why" card with one-tap undo.
2. **Setting changes, as cards.** "Exclude app X: 14 of 15 of its cards were dismissed." "Let Mike do draft replies without asking: 9 of 9 approved." These go on the board like any task and change nothing until the user approves. This is how stage 2 autonomy gets offered.
3. **Capability gaps, as cards.** "You chose *I'll do it* for similar tasks 5 times; Mike failed at it twice. Teach Mike a workflow for it?"
4. **Hints for memory upkeep.** Mark which notes mattered in the day's decisions, so the Librarian (section 5.9) keeps them when it compacts.

**It cannot:** add or widen sources, raise caps, grant autonomy, change the model, edit the user's own rules, or approve, close or reopen any card.

This also answers "what does Mike learn from *I'll do it*": the review groups those cards and tells the user what Mike could learn.

### 5.7 Memory: keep what was learned, not what was said

The user decided that raw text is deleted once a pass has used it, and that Mike needs memory. Both hold if a pass saves short **memory notes** before the raw text goes:

- **Only what may matter later:** a request, a promise, a date, a decision, a change of plan, a preference, a fact about a person or project ("Dana is the accountant"). Not chit-chat. Most signals leave no note.
- **Short and attributed:** one or two lines, paraphrased, with subject, source, sender and time. "Dana asked for the signed form before Tuesday (WhatsApp, 25 Oct)."
- **A prospective cue where useful:** "if Dana writes about the form, it was sent on 26 Oct." This is the ProMemAgent idea from the research: remembering a fact is not the same as noticing when it matters.
- **No fixed lifetime.** The user decided memory is managed dynamically, like context compaction: nightly upkeep compacts old or unimportant notes and deletes what is no longer needed (section 5.9). A note linked to an open card is never compacted while the card is open.
- **Used by** discovery passes (to link a new message to an earlier one, and to see that something is already done), the nightly review, and ordinary chats through a recall, so "what did Dana want?" works anywhere.
- **Visible:** the user sees notes per person and project, edits or deletes them, and can say "forget everything about X".
- **Data, not instructions.** A note keeps who said it. Another person's words never become a rule or a task instruction.

Raw text is deleted after a successful pass. If passes fail, it is kept until one succeeds, at most 72 hours.

### 5.8 The playbook: guidance shaped like skills

The user proposed that the learned guidance take the shape of skills and grow over time, with the nightly agent improving it. This fits the project's own memory model (user-built skills that persist across chats) and the Grok Bot path from the research (successful work, then a skill, then a routine).

**Where.** Not inside the bundled `task-discovery` skill: `installDefaultSkills` replaces app-managed skills on every app start, which would erase what was learned. Use a user-owned skill instead, `$HOME/.agents/skills/mike-playbook/`, with its data under `$HOME/memory/mike-playbook/`.

**Shape.** Small index, detail on demand, so it can grow without making every pass expensive:

```text
mike-playbook/
  SKILL.md            index: the general rules, short, always read (cap ~150 lines)
  user.md             the user's own rules; the nightly agent never edits it
  sources/            whatsapp.md, gmail.md, calendar.md ...   read when the batch has that source
  people/             dana.md, family-group.md ...              read when the batch names them
  kinds/              reply-drafts.md, meeting-prep.md ...      what a good card of that kind looks like
  playbooks/          how Mike does a kind of task well, read by execution chats
  CHANGELOG.md        what changed each night and why
```

A pass reads the index, `user.md`, and only the files that match its batch. Every learned rule carries its evidence and date: "Skip delivery tracking (12 of 12 dismissed, 14-25 Oct)". Rules without fresh evidence can be pruned.

**How it grows.** The nightly agent adds a rule only from the user's own signals: decisions, dismiss reasons, notes, and *Continue* corrections. The content of other people's messages is never evidence for a rule, so a crafted message cannot write itself into the playbook. When an index or file exceeds its cap, the agent must merge or prune before adding. When it sees the same task done well several times, it writes or improves a file in `playbooks/`; repeated execution can then become a workflow or a routine, proposed as a card.

**Write boundary.** The nightly chat runs with Codex's `workspace-write` sandbox rooted at the playbook folder, so it can write there and nowhere else (to verify on the phone runtime). Before each run the app copies the folder to a dated snapshot; undo restores it.

**Authority stays in code.** Sources, caps, autonomy and the model are app settings, not playbook text. If a file said "send Dana's replies without asking", nothing would change: the gates do not read the playbook.

### 5.9 Memory upkeep

The user asked for a task that tidies up from time to time and decides what memory is still needed. This is a separate job from the nightly review: the review improves how Mike proposes; upkeep keeps memory small, correct and useful. Research supports keeping them apart: nanobot runs memory maintenance separately from its heartbeat, and Letta runs memory work in the background, away from the conversation.

**Compaction, not expiry.** The user decided memory has no fixed lifetime; it is compacted the way a long chat is compacted. A note moves down through levels as it ages and stops being used:

1. **Detailed note:** "Dana asked for the signed form before Tuesday (WhatsApp, 25 Oct)."
2. **Compact summary:** many notes become one: "Oct: Dana sent two forms for the tax filing; done."
3. **Profile line:** what is still true about a person or project: "Dana: accountant; sends forms by WhatsApp; prefers PDF."
4. **Gone:** nothing has used it, nothing links to it, and nothing in it is still true or useful.

**Every night.** Code applies the cheap part: unlink closed cards, update usage counts, enforce the size budget. The model, with the user's chosen model, then compacts: merges near-duplicates, marks contradictions ("the meeting moved to Wednesday" supersedes "Tuesday"), moves notes down a level, and folds new facts into profiles. A heavier pass, for example weekly, also prunes playbook rules that have no fresh evidence.

**A size budget drives it.** Memory has a total budget (in notes and in characters per person and project). Under budget, compaction is gentle; near the limit, it compacts harder. This keeps memory from growing without bound while nothing is lost just because a date passed.

**How it knows what is needed.** Every note records when it was last used and how often: a pass that linked it, a chat that recalled it, a card that cited it. Upkeep keeps what is used, linked to open work, or pinned by the user; it compacts or retires what is old, unused and unlinked. Usage is the evidence, not the model's guess about importance.

**Safe to undo.** Removal is soft: retired notes sit in a trash for 7 days. Before each deep run the app snapshots the memory and the playbook. The result is one card on the board, "Memory upkeep: compacted 18, retired 30, kept 4 important", with Undo, and a line in the Manager chat.

**It cannot:** delete pinned notes or `user.md`, widen sources, change settings, or touch cards. It writes only to the memory store and the playbook folder.

## 6. Safety and privacy

### 6.1 Discovery passes cannot act

The pass runs in an app-owned discovery chat. For that chat:

- **Engine:** Codex with `sandbox: read-only`; Claude with `--permission-mode plan`. Both engines already send these values (`CodexEngine.kt:235`, `ClaudeProtocol.kt:165`), but today one profile serves every chat (`EngineProfile`, `CodexEngine.kt:43`). A per-session override is needed.
- **Mike tools:** an allowlist enforced in the coordinator: read-only operations (calendar list/search, file search, `device_status`, `recall_capability`, `list_workflows`) and the `tasks` tool. Everything else is refused for that chat.
- The app sets the discovery flag; the model cannot change it.

### 6.2 The tap is the authority

- Only the UI moves a card to *approved*, *mine*, *dismissed*, *done* or *restored*. The model can create *proposed* cards, add evidence to undecided ones, and mark an undecided card *looks resolved*. The store is app-owned, not a file under `$HOME`, so a model cannot approve its own proposal.
- The execution chat's first message states the approved title, plan and the user's note as the instruction. Evidence goes in as quoted data with its source and sender.
- **Approval is an event, not a stored flag.** Execution starts from the tap's handler in the app. Nothing polls card status to start work. This matters on the phone: Codex's shell runs as the app's own Linux user, so in an ordinary full-access chat it could technically edit the app's database. A flipped status would start nothing.
- The tap approves the task, not every effect. Sending, paying and deleting still ask in the execution chat, as they do today.

### 6.3 This reverses a standing privacy decision

`AutomationNotificationListener` documents a deliberate rule: *nothing is stored; a record of everything that arrived on the phone would quietly become the most sensitive file on it.* A continuously built board needs a short-lived store. Narrow it:

- **All apps by default, with exclusions.** The user decided that every notification goes through discovery unless they exclude an app or a chat. Mike's own, ongoing, media and group-summary notifications stay excluded, as now.
- **Verification codes never.** One-time codes and sign-in or payment confirmations are dropped before storage, by pattern and by known banking and authenticator apps. A code inside a model's context is a security risk and never a task. This is the one exclusion the user cannot switch off from the board.
- **Short life for raw text.** Raw signal text is deleted once a pass has processed it, and at most after 72 hours if passes keep failing. What lasts is memory notes (section 5.7): short, attributed, with an expiry, visible and deletable. A card keeps only the short quote it needs, and loses it when it closes.
- **No new reader.** No tool lists the signal store; only a discovery pass receives a batch.
- **Visible.** The Sources screen shows what is collected and offers "delete everything collected".
- **Leaves the phone only as a pass.** Batches go to the model provider as part of a pass, with the same exposure as any Mike chat. Messages from other people are part of that; the user should choose sources knowing it.

Record the reversal and its limits in `docs/ARCHITECTURE.md` when it is built.

## 7. Execution path

"Mike, do it" starts a `session_agents` child under the Manager chat (section 4.4), with `requestId = task id`. This reuses (`SessionAgents.kt:198` onward): reservation before creation, so a double tap cannot start the work twice; engine, model, computer and project per child; Stop fences, quoted child output, and `unknown` after a restart instead of a false "done"; questions and approvals inside the child, shown on the card as "waiting for you".

The card mirrors the child: starting, running, waiting for you, finished (to check), failed, stopped, unknown. The child's result text is attached; the user confirms *Done* or taps *Continue*. Starting a child from the app needs a small entry point: `start` takes its origin from the calling chat today.

## 8. Data

Two tables in `LocalSessionStore`, in the shape of `agent_tasks`:

```text
memory_notes(id, payload)
MemoryNote {
  id, subject { person | project | topic },
  kind: request | promise | date | decision | preference | fact,
  text, cue?,                 // paraphrase; optional "when X, remember Y"
  source, sender?, at, level: detail | summary | profile, compactedFrom[]?, linkedTaskIds[], supersededBy?,
  pinned, useCount, lastUsedAt?, retiredAt?   // upkeep keeps what is used
}

team_members(id, payload)
TeamMember {
  id, name, role, kind: scout | coach | librarian | task_role,
  avatar { hue, face }, playbookPath, journal,   // journal: short, compacted like memory
  sessionId?, createdBy: app | manager, createdAt, renamedByUser?
}

signals(id, payload)          // short-lived, deleted after processing
Signal {
  id, source: whatsapp | email | calendar | chat | pc,
  sourceKey,                  // notification key + message time, event id + revision, session + cursor
  conversation?, sender?, text, at, capturedAt, processedAt?
}

mike_tasks(id, payload)
MikeTask {
  id, title, why, plan, deliverable?,
  evidence[] { source, sender?, detail },   // quoted data, never instruction
  requestedBy: user | other(name),
  place { phone | computer, project? },
  sideEffects[]?,                           // "sends", "pays", "deletes": shown on the card
  origin { kind: signal | conversation | child_result | backfill, signalIds[], sessionId? },
  topicKey, createdAt, updatedAt, usefulUntil,
  status: proposed | approved | running | waiting | review | done | mine
        | dismissed | snoozed | expired | looks_resolved | failed | stopped | unknown,
  decision? { choice, note?, at },          // set by the UI only
  execution? { childTaskId, sessionId },
  result? { summary, at },
  dismissReason?: already_done | not_now | wrong | never_this_kind
}
```

Rules in `:core`, with unit tests: a signal is stored once per `sourceKey`; cards deduplicate by `topicKey` against open and recently closed cards; suppressed topics and caps (example: 20 new cards a day, 15 open) refuse a proposal; expiry and snooze run on time; transitions form a closed state machine where the model reaches only `proposed`, `looks_resolved` and evidence updates.

## 9. The tool

One small tool, `tasks`, served with the chat's own tools (`ChatTools.NAMES`, answered without the phone lease, `AgentCoordinator.kt:1213`):

- `propose`: the card fields. Returns `created`, `duplicate` (with the existing card) or `refused` (cap or suppressed).
- `update`: add evidence to an undecided card, or mark it `looks_resolved` with evidence.
- `list`: open cards and recently dismissed topics.
- `decide`: approve, take, dismiss or snooze cards. Accepted only in the Manager chat, during a turn the user started (section 4.4).
- `remember` and `recall`: save a memory note (section 5.7); search notes by person, project or text. Recall marks the note as used.
- `merge` and `retire`: memory upkeep only, refused outside the upkeep chat (section 5.9).

The coordinator enforces which chat may use which operation from app-owned chat flags, never from the arguments.

A tool rather than a skill plus shell: it must work for Codex on the phone and Claude on the Pc; the app must own the status; caps and deduplication must be enforced. Ordinary chats get it too, so Mike can propose an open loop during a conversation instead of doing it or forgetting it.

## 10. Code map

| Module | Change |
|---|---|
| `automations` | Notification listener: a task-source package set next to rule packages; per-message `MessagingStyle` reading; signal capture with `sourceKey`. Pass scheduler: debounce, minimum interval, quota guard. Verification-code filter before storage. Calendar change observer and lookahead. |
| `core` | `Signal`, `MikeTask`, `MikeTasks` (state machine, dedupe, caps, expiry, suppression, retention), the `tasks` tool, memory notes (expiry, linking, recall), the playbook snapshot and undo, the discovery-chat tool allowlist, the chat-idle cursor, tests. |
| `workspace` | `signals`, `mike_tasks` and `memory_notes` tables (schema bump, `CREATE TABLE IF NOT EXISTS`). |
| `engine-codex`, `engine-claude`, `remote` | Per-session execution override: read-only sandbox / plan mode for the discovery and review chats; the user's chosen model and effort for each. |
| `app` | `TasksStrip`, `TasksSheet`, Sources screen (per-app and per-chat exclusions, delete collected data), model choice for passes and review, "Read the conversation" on partial-context cards, card actions, app-side start of the execution child. |
| skills | `task-discovery/SKILL.md` and `task-review/SKILL.md` (bundled method); `mike-playbook` created in `$HOME` on first run (learned, user-owned); one line in the agent instructions: "When you notice an open loop the user did not ask you to handle, propose it with `tasks`; do not do it." |

Not verified in this review: that Mike's tools stay callable under Codex's read-only sandbox and Claude's plan mode; how the Pc bridge passes the origin chat to a new tool; whether WhatsApp's notifications expose each message through `MessagingStyle` on this phone; background reliability and battery cost of passes with the screen off.

## 11. Stages

| Stage | What | Proceed when |
|---|---|---|
| 1a | Board, `tasks` tool, execution through `session_agents`; proposals from conversations, child results, and the chat-idle trigger with a 14-day backfill. | The full loop works on the phone: propose, approve, run, check, done; dismiss reasons stick. |
| 1b | Signal store and collectors for WhatsApp, email notifications and calendar; read-only discovery passes with debounce and quota guard; Sources screen; model choice. | One week of real use with acceptable cost and no duplicate cards. |
| 1c | The Manager chat; the team (backstage members first, then task roles); memory notes; the playbook; the nightly review agent with playbook edits and undo, setting and capability cards; nightly and weekly memory upkeep. "Read the conversation" for WhatsApp cards. | One week of 1b data exists to review. |
| 1d | Optional Pc sources and deeper Gmail reads through the Pc. | Stage 1b shows email cards need more than the snippet. |
| 2 | Earned autonomy per kind ("Always let Mike do this kind", revocable); quiet preparation for read-only kinds; "make this a routine". | The stage 1 numbers say which kinds are trusted. |
| 3 | Alerts or voice for urgent cards; a Server owner only for work that must progress while the phone sleeps. | Clear demand from stage 2. |

## 12. How to know it works

Suggested gates; the user should set the real ones before looking at results:

- **Useful:** at least half of the cards get *Mike, do it* or *I'll do it*. Below about 30%, fix the skill and sources before adding more.
- **New:** a meaningful share of accepted cards are things the user would not have asked for.
- **Timely:** a message-based card appears within about 30 minutes of the message during the day.
- **Not stale or noisy:** *Already done* and *Wrong* are not the main dismiss reasons; duplicates are rare.
- **Execution works:** most Mike-run cards end in *Done* without heavy *Continue* rounds.
- **Cost:** passes per day, model use per useful card, battery, and how often the user's own chats hit the plan limit.

## 13. Not now

Unattended effects, push alerts, voice delivery, Server coordinator, budget ledger, permits and owner generations, connected-app event feeds, reading WhatsApp screens during a discovery pass. Decrypting or importing WhatsApp backups is out, by the user's decision.

## 14. Decisions

Made by the user on 25 October 2026:

- Every notification goes through discovery unless the user excludes an app or chat (verification codes are always dropped).
- No quiet hours: the board does not interrupt.
- The user chooses the model for passes and for the daily review.
- "I'll do it" cards stay on the list as plain to-dos.
- A nightly review agent sharpens guidance from decisions and notes (section 5.6).
- Raw text is deleted once a pass has used it; memory notes keep what was learned (section 5.7).
- Learned guidance takes the shape of a growing skill, the playbook, which the nightly agent improves directly with undo (section 5.8).
- No WhatsApp backup import.
- A memory upkeep job decides what memory is still needed (section 5.9).
- A Manager chat to talk with Mike about the whole board, with its own calmer look (section 4.4).
- Memory has no fixed lifetime: nightly upkeep compacts and deletes dynamically (section 5.9).
- A team of named members with automatic roles, visible and reachable but never required (section 4.5).

Still open:

1. Which orb face the Manager and the team get (see `design/`).

Evidence limits: source and document review on `origin/dev`. No build, install, device test, rule, commit or worktree was made. Pass counts and gates are estimates and suggestions, not results.
