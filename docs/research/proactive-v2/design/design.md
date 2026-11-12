# Hey Mike: Tasks board, Manager chat and Mike's team. Visual design

25 October 2026. Design only. Nothing here is built in Compose, installed or tested on a phone. The mockups are HTML, rendered with headless Edge. Source baseline for the existing UI: `origin/dev` (read with `git show`, no checkout).

The feature itself is in [04-task-inbox.md](../04-task-inbox.md). This document is how it should look, move and read, in Hebrew (RTL) first.

## 0. Files

All paths are in `docs/research/proactive-v2/design/`. Every HTML file is self-contained (inline CSS, SVG icons, a small canvas script, system fonts, no network). Open one in a browser: it animates. Add `?still` to freeze it, `?theme=light` for the light proposal.

| File | What it shows |
|---|---|
| `board.html` / `.png`, `board-full.png`, `board-light.png` | The Tasks board (sheet): Manager row, all five sections, six card states |
| `card-detail.html` / `.png`, `card-detail-full.png`, `card-detail-light.png` | One card opened: why now, plan, side effect, quote, "Read the conversation", instructions box, dismiss reasons |
| `cards.html` / `.png`, `cards-light.png` | One card in every status (18 variants) |
| `strip.html` / `.png`, `strip-light.png` | The Tasks strip in the chat drawer, with the Manager as one pinned chat |
| `strip-states.html` / `.png` | The strip in seven states (needs, quiet, thinking, working, blocked, done, paused) |
| `manager-chat.html` / `.png`, `manager-chat-light.png`, `manager-chat-thinking/-working/-done.png` (the page takes `?state=idle\|thinking\|working\|needs\|done`) | Mike the Manager: presence header, team face pile, messages, inline cards, composer |
| `orb-faces.html` / `.png`, `orb-faces-light.png` | Three face directions by five states, small sizes, paused, live switcher |
| `team.html` / `.png`, `team-light.png` | The team page |
| `member.html` / `.png`, `member-full.png`, `member-light.png`, `member-librarian.png`, `member-coach.png` | A crew member page (Scout, Librarian, Coach) |
| `avatars.html` / `.png`, `avatars-light.png` | The avatar family, seven members by five states, small sizes |
| `sources.html` / `.png`, `sources-light.png` | Sources settings |
| `memory.html` / `.png`, `memory-light.png` | Memory notes view |
| `_src/` | `parts/` (tokens, components, orb engine), `pages/`, `build.py` (inlines parts into the HTML), `render.py` + `jobs.py` (PNG renders). Python standard library only. |

The orb engine in `_src/parts/orb.js` is written so that each piece maps to one Compose draw call (section 5.7). The mockups use invented names only.

## 1. Principles

1. **Pull, not push.** The board is a place you visit. The strip's count is the only signal. Nothing flashes, nothing makes sound, no badge pulses. The Manager's orb may breathe slowly; that is the whole alert.
2. **Color means one thing.** Teal: Mike is live or something went well. Amber: something waits for you. Red: only for a failed task. The strong blue of "Mike is controlling your screen" is not used here. A screen has one accent at a time.
3. **Quiet by default.** A card shows a title, one reason, one plan line, at most two tags and one primary action. Everything else is one tap away.
4. **Say what will happen.** Every card shows who asked ("requested by Dana, not by you"), the side effect (sends, pays, deletes) and the place (phone, Pc). Quoted messages are labelled as information, not instruction. Stop never claims undone work. "Read the conversation" says it will mark messages as read.
5. **Mike has a character, the content does not.** The orb with a face lives in headers and avatars. Cards, notes and settings stay plain.
6. **Native first.** Reuse the current scheme, shapes, type and idioms (the strip that may not grow, the two-level sheet, teal/amber). Add as few tokens as possible, each justified (section 2.2).
7. **Mirror, do not translate.** Layout uses start/end only. Text direction follows the words, as `agentTypography()` already does.
8. **Calm motion.** One slow moving thing at a time. Reduced motion shows a pose, not nothing.

## 2. Tokens

### 2.1 Reused from the current app (dark)

| Token | Value | Where it lives today | Used here for |
|---|---|---|---|
| `background` | `#000000` | `AgentDarkColors` | Manager chat and member page ground |
| `surface` | `#202020` | `AgentDarkColors` | Task card |
| `surfaceVariant` | `#303030` | `AgentDarkColors` | Tonal buttons, inset blocks (at 45 percent, like `RuleChip`) |
| `primaryContainer` | `#252525` | `AgentDarkColors` | User bubble |
| `primary` / `onPrimary` | `#F4F4F4` / `#111111` | `AgentDarkColors` | The one white action (Done, Answer in chat, Retry) |
| `onSurface` / `onSurfaceVariant` | `#F2F2F2` / `#AAAAAA` | `AgentDarkColors` | Text |
| `outlineVariant` | `#3A3A3A` | `AgentDarkColors` | Hairlines, card border at 55 percent (like `AutomationStrip`) |
| `secondary` | `#83D9CA` | `AgentDarkColors`, `RuleReady`, `OrbWorking` | Teal: live, ok |
| `secondaryContainer` / `onSecondaryContainer` | `#174E47` / `#A1F2E1` | `AgentDarkColors` | The "Mike, do it" button (new use of an existing pair) |
| `tertiaryContainer` | `#1E3A34` | `AgentDarkColors` | Result block tint |
| `RuleBlocked` | `#F6B86A` | `AutomationStrip.kt` | Amber: waiting for you |
| `BlockedContainer` / `BlockedOutline` | `#241E14` / `#4A3A24` | `AutomationStrip.kt` | Amber card and chip |
| `errorContainer` / `error` | `#2B1D1B` / `#FFB4AB` | `AgentDarkColors` | Failed card only |
| `StatusSheetFill` | `#1B1B1B` | `ChatTopBar.kt` | Board and detail sheet |
| `LibraryGround` / `LibraryLight` / `LibraryMuted` | `#141413` / `#ECEBE6` / `#8E8C85` | `ChatLibraryDrawer.kt` | The strip's drawer; Mike's own words in the Manager chat |
| `FieldBorder` | `#5C5C5C` | `ChatComposer.kt` | Composer outline (on black) |

The strip lives in the chat drawer, whose accent for "live" is sand (`LibraryAmber`). The Tasks strip uses the strip family (`RuleReady` teal, `RuleBlocked` amber) so it matches `AutomationStrip`, not the drawer's own accent.

### 2.2 New tokens, each justified

| Token | Dark / Light | Why it has to exist |
|---|---|---|
| `ControlLine` | `#707070` / `#85837B` | Border of an interactive outline control (dismiss chips, outline buttons, instruction box). `outlineVariant` is 1.5:1 and fine for hairlines, but a control border needs 3:1. Measured 3.5 on the sheet, 3.3 on cards. |
| `CrewHue` x6 | Scout `#9EC5E8`, Librarian `#BCAAE8`, Coach `#B8D68C`, Messages `#D6CAB2`, Meetings `#DEBAA0`, Pc `#B4C4D2` | Identity of a team member. Pastel, one lightness, away from teal (live), amber (waiting) and the saturated blue of "controlling the screen". |
| `AuraTeal` / `AuraAmber` | teal / amber at 10 percent alpha | The Manager screen takes a faint tint from the orb's state, as `VoiceSphere` tints the screen. Alpha of existing colors, not new hues. |
| Light scheme | table below | The app is dark only. Light is a proposal for the same roles. |

Light scheme, proposed (same roles): `background #FAF9F6`, `sheet #F3F2EE`, `surface #FFFFFF`, `onSurface #1A1A19`, `onSurfaceVariant #5A5953`, `surfaceVariant #ECEBE6`, `primary #1A1A19`, `onPrimary #F4F4F4`, `outlineVariant #D8D6CE`, teal ink `#0F6B5E` (teal fill and rings `#14776A`), `secondaryContainer #D3EFE8` / `onSecondaryContainer #00352E`, amber ink `#8A5A00` (amber fills and rings `#B87400`), amber container `#FBEFD9` / outline `#E7CFA3`, `error #B3261E` on `errorContainer #FCE8E6`. The warm neutral echoes the drawer's palette. Shipping it needs a light `AndroidAgentTheme`, which does not exist today.

### 2.3 Type

The scale is `agentTypography()`: every style takes its direction from its own text (`TextDirection.Content`).

| Role | Compose style | Size / line | Used for |
|---|---|---|---|
| Sheet title | `headlineSmall` bold | 24 / 32 | "Tasks", "Sources" |
| Detail title | `titleLarge` bold | 22 / 30 | Task title in the detail |
| Card title | `titleMedium` semibold | 16 / 24 | Task title on a card |
| Manager message | `bodyLarge` | 17 / 27 | Mike's words (same as any agent text) |
| Body | `bodyMedium` | 14 / 21 | Why-now, plan, notes |
| Row title / note | the 15sp / 12.5sp pair that `BackendRow` already uses | 15 / 20, 12.5 / 17 | Settings rows |
| Label | `labelMedium` / `labelSmall` | 12 / 16, 11 / 16 | Source line, tags, chips, section labels |

The mockups use 16/25 for Manager messages to fit one screen; build with `bodyLarge`. Hebrew has no capitals, so `RuleGroup`'s `uppercase()` is a no-op: section labels are bold accent-colored text with a count and a hairline, with no letter spacing (letter spacing hurts Hebrew).

### 2.4 Shape and spacing

| Element | Radius |
|---|---|
| Sheet top corners | 28 |
| Strip, task card, groups | 18 |
| Inset blocks, panels, rows | 14 |
| User bubble | 20 |
| Chips | 16 (34 tall, 48 target) |
| Buttons | full pill, 48 tall |
| Badge | 7 |

Spacing: sheet side padding 20, gap between cards 12, section gap 26 above and 12 below the label, card padding 16 (14 for inline cards in the Manager chat), tags gap 6, buttons gap 8.

### 2.5 Contrast (computed with the WCAG 2.x formula)

| Pair | Dark | Light |
|---|---|---|
| Text on sheet / on card | 15.4 / 14.6 | 15.6 / 17.4 |
| Secondary text on card | 7.0 | 7.0 |
| Teal text on card | 9.9 | 6.4 |
| Amber text on its container | 9.4 | 5.2 |
| "Mike, do it" label on its button | 7.4 | 11.2 |
| Primary button label | 17.2 | 15.8 |
| Error text on error container | 9.6 | 5.6 |
| Drawer muted text (`LibraryMuted`) | 5.5 | 4.9 |
| Control border on sheet / card | 3.5 / 3.3 | 3.4 / 3.8 |
| Teal / amber status ring on the page ground | 12.7 / 12.0 | 5.2 / 3.6 |

Found and fixed while measuring: the drawer's `LibraryFaint` (`#6C6A64`) is 3.4:1, so it may carry icons and rules but not text; the mockups use `LibraryMuted` for the drawer's section labels and times. A light amber of `#C98512` was 2.9:1 on the page ground; `#B87400` passes 3:1 everywhere.

## 3. Where things sit

```
Chat drawer (LibraryGround)
  Chats                                 [search] [close]
  +---------------------------------+
  | TasksStrip   header, 3 chips max, 1 sentence       |  opens the board
  +---------------------------------+
  Pinned
    Mike . Manager   (orb, last line, unread)           |  opens the Manager chat
  Today / Yesterday ... ordinary chats                   |  the team is NOT here
  Footer: Automations . Files . Settings

Board sheet (two levels, like AutomationsSheet)
  Board  --card-->  Card detail
    header: Sources (tune icon)  -->  Sources  -->  Memory notes
    Manager row                -->  Manager chat

Manager chat
  top bar: menu . board (count badge) . more
  presence header: orb, name, state line, team face pile
  face pile  -->  Team page  -->  Member page (timeline, playbook, message box)
```

Rules: the board is one place; the Manager is one pinned chat, not a section; **the team never appears in the chat list**. It is reached from the Manager (face pile under the header, or the overflow menu). Back walks one level at a time, as `AutomationsSheet` does.

## 4. Components

### 4.1 Tasks strip (`TasksStrip`)

Same anatomy as `AutomationStrip`: bordered 18dp surface, header row, at most three chips, exactly one sentence. It **may not grow**: with 40 tasks it still shows three chips and one sentence. `:core` chooses chips and sentence; the UI renders.

Header: the Manager's orb at 30dp with its status ring, "Tasks", a chevron. Chips are 34dp visible with a 48dp target (the `RuleChip` trick). Tapping a chip opens the board scrolled to that section; tapping elsewhere opens the board.

| State | Chips | Sentence | Orb |
|---|---|---|---|
| needs | amber "3 waiting", teal-dot "1 running", "1 to check" | "Most urgent: Dana's form, by Tuesday" | needs (amber ring, badge) |
| quiet | none | "All quiet. Mike checked 12 minutes ago" (muted dot) | idle |
| thinking | none | "Mike is scanning 12 new messages..." (teal, live dot) | thinking (turning arc) |
| working | teal-dot "2 running", "2 waiting" | "Mike is working on Dana's form" | working |
| blocked | amber "1 waiting for your answer" | the question itself, amber. The whole strip takes `BlockedContainer`. | needs |
| done | "2 to check" with a check | "Mike finished: moving the wedding photos" | done (check badge) |
| paused | amber "Paused" | "Collection is off, so the board does not update" | eyes closed |

The strip is one button, so TalkBack reads one sentence ("Tasks. 3 waiting, 1 running, 1 to check. Most urgent: Dana's form").

### 4.2 Board sheet (`TasksSheet`)

A `ModalBottomSheet` at 94 percent height, like `AutomationsSheet`. Header: back, title, subtitle ("Mike scanned 12 minutes ago"), Sources button. Then the Manager row (orb 48, "Mike . Manager", "3 decisions waiting for you . 1 task running"), then sections in this order. Section label: bold 12sp in the section color, a count, a hairline.

| Section | Color | Contents | Rules |
|---|---|---|---|
| Waiting for your decision | amber | proposed cards | Newest and most urgent first. At most 15 open, 3 shown, then "2 more" |
| Mike is on it | teal | starting, running, waiting-for-you | Waiting cards use the amber card; running cards show a hairline of progress |
| To check | teal | review cards, the nightly card, failed, stopped, unknown | The nightly card sits first. Failed and unknown use the error or amber card |
| Yours | gray | "I'll do it" cards as plain to-dos with a checkbox | Stay until checked |
| History | gray | done, dismissed, snoozed, expired, looks resolved | Collapsed to three rows with a count; "looks resolved" has Restore |

Empty states: no cards at all shows one calm line and the strip's quiet sentence, never an illustration.

### 4.3 Task card (`TaskCard(status)`)

```
[source icon] WhatsApp . Dana . 14:10            [phone] On the phone
Send Dana the signed form                                    title, 16 semibold, 2 lines max
She needs it by Tuesday.                                      why now, 14, 1-2 lines
[sparkle] Mike would: find the form, draft a reply with it    plan, 14, 2 lines max
( Requested by Dana, not by you )  ( Sends a message )        tags, at most two
[ Mike, do it ]  [ I'll do it ]  [x]                          one primary, one tonal, one ghost
```

Content limits keep the card calm: title 2 lines, why 2, plan 2, tags 2, one action row. The place sits at the end of the source line as quiet text with an icon (phone, or computer plus project). The "requested by someone else" tag has a person icon and a neutral border; the side-effect tag has an amber icon only (the text stays neutral). Partial context adds one dashed block with "I saw only the latest messages" and a text button "Read the conversation".

Statuses (the model reaches only `proposed`, `looks_resolved` and evidence updates; the UI owns every other transition):

| Status | Section | Surface | Middle row | Actions |
|---|---|---|---|---|
| `proposed` | Waiting for you | card | why, plan, tags | Mike, do it . I'll do it . x |
| `proposed` + partial context | Waiting for you | card | dashed notice | same, plus Read the conversation |
| dismiss open | (in place) | card | "Why not?" and four chips | chips, Skip |
| `approved` (starting) | Mike is on it | card | "Starting" and an indeterminate hairline | none (reservation: a double tap cannot start twice) |
| `running` | Mike is on it | card | teal live dot, elapsed, what it is doing, hairline | Open chat . Stop |
| `waiting` | Mike is on it | amber card | "Mike asks" and the question | Answer in chat (white) . Stop |
| `review` | To check | card | result block, teal tint | Done (white) . Continue |
| `failed` | To check | error card | "Could not" plus what was done and what was not | Try again (white) . I'll do it |
| `stopped` | To check | card | "You stopped at step 2 of 3. What was already sent was not undone." | Continue . I'll do it . Close |
| `unknown` | To check | amber card | "The app restarted mid-task, so it is unclear whether it was sent. Check the chat first." | Check in chat (white) . I'll do it |
| `mine` | Yours | row | checkbox | check |
| `snoozed` | History | row | "Not now . returns tomorrow 09:00" | Return now |
| `done` | History | row | "Done . yesterday 18:20" | none |
| `dismissed` | History | row | "Dismissed: already done" | none |
| `expired` | History | row | "Expired . was relevant until Sunday" | none |
| `looks_resolved` | History | card | the new message as a quote | "Not resolved, restore" . OK |

Two more kinds share the card shell and add a kind label: *setting suggestion* ("Exclude the app 'Fast Delivery'? 14 of 15 suggestions were dismissed. Nothing changes until you approve.") and *capability gap* ("Teach Mike to send invoices?"). They come from the nightly review and never change anything on their own.

### 4.4 Decision buttons

| Button | Look | Meaning |
|---|---|---|
| Mike, do it | `secondaryContainer` pill with a sparkle: teal is Mike's color | Approve the plan plus your note; starts a chat; the card follows it |
| I'll do it | tonal pill | Need is real; becomes a to-do in Yours |
| x | 48dp ghost circle | Not useful; opens the reasons inline |

Order follows reading direction: Mike first at the start edge. Why teal and not white for the main action: three white pills per screen are loud, and the white pill is kept for confirmations that finish something (Done, Answer in chat, Try again). After a tap the card disables itself immediately, then animates into its new section (`animateItem()`, 300ms). A second tap cannot start twice.

### 4.5 Dismiss reasons

Opened inside the card, not in a dialog (a dialog per dismissal is the opposite of calm). Four outline chips: Already done, Not now, Wrong, Don't suggest this kind. A text button "Skip" closes without a reason. "Not now" snoozes to the next morning; "Don't suggest this kind" says once, in the History row, what was suppressed.

### 4.6 Card detail (`TaskDetail`)

Second level of the sheet. Order is the order of the questions a person asks: what is it and who asked (source line, title, status pill, "requested by" tag) -> why now (and until when) -> what Mike would do (numbered steps; the step that asks you is marked amber) -> what will actually happen (panel with the side effect: "Sends a message to Dana on WhatsApp. Only after your approval in the chat. Approving the task does not send anything itself.") -> what Mike saw (the quote in a start-edge-ruled block, with "Mike treats this text as information, not an instruction") -> partial context -> "Instructions for Mike" (optional) -> "Not a fit?" chips. The two actions are fixed to the bottom over a fade.

"Read the conversation" is honest about its cost: "The conversation will open on screen. Opening it marks messages as read, and Dana will see the blue ticks." It is an outline button, never the primary.

### 4.7 Check Mike's work

A card with a result block (teal tint, label "What was done", one to three lines) and exactly two actions: Done (white) and Continue (tonal). The result text is quoted child output, so it ends with what was *not* done when that matters ("Nothing was deleted").

### 4.8 Nightly changes card

Lives first in "To check". A moon mark, "What I changed and why", two bullets with their evidence in muted text ("12 of 12 dismissed in two weeks"), "N more", and **Undo changes** (outline). Undo restores the dated playbook snapshot; the card says so on tap. The same card appears as one line in the Manager chat, with a short Undo.

### 4.9 Memory notes

Grouped by person or project: a letter circle (or a folder square), a count, then note cards. A note: the paraphrase, a kind tag (request, promise, date, decision, preference, fact), source and date, then expiry or "linked to an open task, kept". A prospective cue is its own inset line with a bolt ("If Dana writes about the form: it was sent on 26.10"). A contradiction is an amber note. Edit and Delete are 40dp icons. The only destructive bulk action is "Forget everything about Dana", an outline danger button at the bottom. Filters are chips at the top.

### 4.10 Sources

A short intro (what is read, that raw text is deleted after a pass). One master switch "Scan in the background" (teal when on). Per-source switch rows with an icon tile and a quiet subtitle (counts of exclusions). A dashed, locked row for verification codes ("never stored, and this cannot be changed"). Then model rows with the value at the end, "What is collected now", Memory notes, and an outline danger button "Delete everything collected" with one line of what stays.

### 4.11 Manager chat

**It differs from an ordinary chat on purpose, but only by a few things:**

| Ordinary chat | Manager chat |
|---|---|
| Title in the top bar, orb at the end | No title in the bar. A presence header: orb (176), name, one state line, team face pile |
| Black ground | Black ground with a faint aura from the orb's state (amber 9 percent when something waits, teal 10 percent otherwise) |
| Agent text `onSurface` | Mike's text in warm `LibraryLight` (`#ECEBE6`), same `bodyLarge` |
| Tool rows and device cards | Result cards, a night report line, inline proposal cards, an activity pill |
| Composer hint "Message, or type / for skills" | "Talk to Mike about the board", plus three suggestion chips: What is urgent?, What did you do last night?, Stop suggesting... |

Messages: Mike's words have no bubble, as today. Yours is the usual `primaryContainer` bubble at the end edge. An inline proposal is a compact card (14dp padding, no why line) with the same three buttons at 44dp. The activity pill (`plain` orb at 26, "Working . Dana's form", stop) shows a running task. The night report is one row with Undo.

Scroll behavior: the presence header collapses with the list (`nestedScroll`): the orb goes from 176dp to 40dp and docks at the start of the top bar, the name becomes the bar title, the state line fades. Messages fade under the header with a 22dp mask.

## 5. Mike's orb with a face

All three directions are the app's own dotted orb (`AgentOrb`: points on a tilted, turning sphere, glow behind) with a face drawn into it. At large sizes the point count rises like `VoiceSphere`. `orb-faces.html` shows them side by side with five states each, plus small sizes, a paused state and a live switcher.

### 5.1 The three directions

**A. Singularity eye.** Dots step back around a void. In the void sit a black pupil, a thin photon ring (three strokes: a faint wide one, a medium one, a bright thin one), and an accretion disk of dots seen at a tilt: the far half arcs over the pupil as if light were bent, the near half crosses in front. The eye is carved from the orb's own points. It looks toward the cards, blinks rarely (a vertical squash of about 300 ms, every 8 to 11 seconds), and closes when collection is paused.

**B. Quiet face.** Two soft vertical points of light in the orb. They squint when thinking, look down when working, widen and tilt slightly when something waits, and rest as closed arcs when done. No mouth.

**C. Iris of light.** The orb's dots organize into an iris: radial fibers, two rings of dots and a dark pupil with a corona. A blink closes the light inward. Working streams the dots outward from the pupil.

### 5.2 Five states

| State | What triggers it | A: singularity eye | B: quiet face | C: iris |
|---|---|---|---|---|
| idle | nothing to do | Disk turns very slowly, dim teal, rare blink | Two points, rare blink | Calm iris, slow shimmer |
| thinking | a discovery pass is running | Disk widens and speeds up, comet on the ring, eye scans sideways, ring pulses at about 0.9 Hz | Squint, glancing up | Iris turns, pupil breathes |
| working | tasks are running | One satellite per running task; steady ring | Small focused eyes looking down | Light streams outward |
| needs you | a card waits for a decision | Amber; the disk drifts toward the cards (gaze down); a second ring breathes at 0.33 Hz; no blink | Tall amber eyes, head tilted 7 degrees | Wide held pupil, amber |
| done | a run finished | Disk settles flat onto the ring, eye relaxes to 78 percent, one ripple | Eyes closed as soft arcs | Pupil shrinks to a point, a catchlight |
| paused | collection is off | Eye closed, no motion, dim | Eyes closed | Iris closed inward |

Priority when several apply: paused, then needs you, working, thinking, done (transient, about 6 seconds), idle.

### 5.3 Recommendation: A

1. **It is native.** It is carved from the existing orb. B and C add a new object on top of it.
2. **It reads small.** At 24 to 48dp a dark hole and a bright ring are still an eye; the dots and details drop away gracefully. B becomes two specks; C becomes a fuzzy disc. (`orb-faces.png`, "small sizes" row.)
3. **State is not only color.** Disk shape (wide, thin, flat), a comet, satellites and a doubled ring each carry meaning. It passes a grayscale check.
4. **It is not a face watching you.** This app reads notifications, so a staring, human eye is the wrong signal. A black-hole eye is abstract; its gaze goes to the cards, never at the user; and it visibly closes when collection is paused, which is an honest privacy state.
5. **It scales into a family.** Team members keep the eye and the ring and drop the disk (section 6).
6. **It is cheap.** About 220 to 560 points, at most 190 disk dots and 12 comet dots, in one Canvas with no shaders or blur filters.

Risks: it can read as ominous. Mitigations: soft teal, never red; slow motion; no vertical slit pupil; the disk is a gentle dotted band. If tests dislike it, **B is the fallback** (friendliest, cheapest) and **C is best as a hero-only** orb (its eye-realism intensifies the "being watched" feeling at the needs-you state, and its fibers vanish below 48dp).

### 5.4 Sizes

| Size | Where | What is drawn |
|---|---|---|
| 24 to 30 | Strip header, face pile, activity pill | Dots, void, pupil and ring; the disk is about 20 specks and barely reads |
| 40 to 56 | Rows, status button | A bigger pupil (x1.5 at small sizes), ring badges; the disk starts to read as a band from about 44 |
| 76 | Manager entry card | The disk is a clear band (about 140 dots) |
| 176 and up | Manager presence header | Full: 560 points, 190 disk dots, comet, satellites |

### 5.5 Light theme

Same geometry. The pupil stays near-black, which is bolder on light. Teal and amber become the darker ink values, dot alpha rises slightly, glow alpha drops to 0.7, and highlights blend toward dark ink instead of white.

### 5.6 Reduced motion

The pose, not nothing. With the system animator scale at zero (`animationsEnabled()` is false, the same guard `AgentOrb` uses) the frame loop does not run. Each state has a fixed pose time (`idle` 1.9s, `thinking` 3.1s, `working` 2.0s, `needs` 1.6s, `done` 1.3s, `paused` 1.0s) at which the orb is drawn once; the poses are the stills in the mockups. Color changes still happen, as an instant swap. The blink, the comet, the radar arc, the ring sweep and the pulse are all off. The needs-you state keeps its second ring drawn statically at 0.5 alpha, so it still differs from idle.

### 5.7 Prototype pieces and the Compose call each becomes

| Prototype | Compose |
|---|---|
| Fibonacci sphere points, spin, tilt 0.38 rad | the existing `AgentOrb` arrays and math |
| Glow | two-stop `Brush.radialGradient`, as `AgentOrb` |
| Carve (dots step back near the eye) | multiply each point's alpha by a smoothstep of its distance to the eye |
| Pupil | `drawCircle` in the background color |
| Photon ring | three `drawCircle` with `Stroke` (widths x5.2, x2.4, x1) |
| Accretion disk | up to 190 `drawCircle`, two passes (far before the pupil, near after) |
| Comet | 12 `drawCircle` with falling alpha |
| Satellites | one glow circle plus one dot per running task, two passes (behind the orb, in front) |
| Radar arc | 16 short `drawArc` with rising alpha |
| Status ring | `drawArc(useCenter = false, style = Stroke(cap = StrokeCap.Round))` |
| Blink | `Animatable` lid factor applied with `scale(1f, lid)` around the eye |
| Gaze | `animateOffsetAsState` (spring) |

Suggested API: `MikeFace(state: MikeFaceState, who: CrewLook = Manager, runningTasks: Int = 0, ring: Boolean = false, gaze: Offset = Offset.Zero)`, with `MikeFaceState { IDLE, THINKING, WORKING, NEEDS_YOU, DONE, PAUSED }` derived in `:core` from the tasks overview (and, for a member, from its own run).

## 6. Mike's team

### 6.1 Concept and entry

Besides the Manager, Mike has a small crew. Each has a name and a role: **Scout** (scans messages and the calendar for tasks), **Librarian** (tidies memory every night), **Coach** (reviews decisions and improves the playbook), and **task areas** the Manager creates when similar tasks pile up (Messages and replies, Meetings, Pc projects). You never have to open them. You can open any one to see what it did and is doing, and write to it.

Hebrew names used in the mockups: צופה (Scout), ספרן (Librarian), מאמן (Coach), and the area names as titles.

Entry points, kept quiet:

1. **Face pile** under the Manager header (`manager-chat.png`): six 30dp avatars overlapping by 9dp inside one 40dp pill, "Team" and a chevron. A row of six named avatars with permanent rings was rejected as too loud. The pile is one tap target and one TalkBack node.
2. **Sorted by attention.** Whoever needs you is first (start edge) and drawn on top of its neighbor, so its badge is never covered. Then working, thinking, the rest.
3. **Overflow menu** of the Manager chat has "Team".
4. **Never in the chat list.** The drawer shows the Manager as one pinned chat and nothing else about the crew.

### 6.2 Team page (`team.html`)

A sheet like the board. Title "Mike's team", subtitle "They work in the background. You can open any one and write to it." Then:

- The Manager card: orb 76 with its ring, name, one line of role. The only card with a tint (a faint teal wash at the start).
- **Core** (3 rows): avatar 56 with ring, name, role in one line, status line (a teal live dot and "Scanning now . 12 new messages"; "Resting . next run at 03:00"; a check and "Finished last night . updated 3 rules").
- **By area** (3 rows, created automatically): same row, with a "new" tag on a recently created area. A row that needs you gets a faint amber wash and an amber status line.
- One line explaining that Mike opens a new area when similar tasks pile up, and a "Manage areas" text button (merge, hide).

### 6.3 Member page (`member.html`, `member-librarian.html`, `member-coach.html`)

A page, not a sheet: black ground with a faint aura in the member's hue (amber when it needs you).

1. Top bar: back, more.
2. Presence: the avatar at 156 with its status ring, name (24 bold), role in one line, a state pill ("Scanning now", "Resting . next run at 03:00", "Finished last night . updated 3 rules").
3. **Now**: one card: what it is doing, a hairline of progress while it works, and the next-run hint ("about 20 minutes, or when messages arrive"). When resting, "Last run" with counts instead.
4. **What it did**: a timeline, newest first, grouped by day. A rail runs along the start edge; each entry has a marker (hollow, teal fill for accepted, amber fill for waiting on you, a halo while running), the time, a title, a muted detail line, and an outcome chip: "Proposed", "Approved by you" (teal, with a check), "Dismissed: already done", "Merged", "Rule updated" with an Undo link, "Waiting for your review" (amber). A link goes to the card or note. Quiet runs are one muted line ("14 more scans with no new tasks"), not entries.
5. **The conversation lives in the timeline.** Your message to the member is the usual bubble at the end edge; its answer is plain text with a small avatar and a line of what was saved ("Saved in your rules"). No separate chat screen.
6. **Links**: its playbook ("Scout's playbook . 9 rules . updated last night by the Coach") and what it sees ("3 sources . 2 apps excluded"; for the Librarian "Memory notes"; for the Coach "What it cannot change").
7. **Message box** pinned at the bottom with two suggestion chips for that member and one line: "Instructions written here are saved in your own rules, and the Coach does not change them." This follows 04-task-inbox section 5.8 (`user.md` is never edited by the nightly agent). Whether messages to a member really land in `user.md` is an open question (section 11).

How it differs from an ordinary chat: it opens on what the member did, not on an empty conversation; the composer is secondary; its header has a hue and a status; nothing in it starts a task run.

### 6.4 Avatar system (`avatars.html`)

One family. Every avatar is the dotted orb with the eye (dark pupil, photon ring) and a status ring around it. The Manager is the most distinct on purpose.

| Member | Hue | Face detail |
|---|---|---|
| Mike, the Manager | state-colored (teal, amber) | The only accretion disk; the comet; satellites for running tasks; always larger (76 and up in lists) |
| Scout | ice `#9EC5E8` | A radar arc turning around the ring |
| Librarian | lilac `#BCAAE8` | Eye half-closed (50 percent); opens fully when it needs you |
| Coach | leaf `#B8D68C` | A double ring |
| Messages and replies | stone `#D6CAB2` | Two dots orbiting the ring |
| Meetings | clay `#DEBAA0` | One dot orbiting |
| Pc projects | mist `#B4C4D2` | Three dots orbiting |

Rules: pastel hues at one lightness; never teal, amber or the strong blue (the hue is identity, the ring is status); a team member never grows past 56dp in a list; the Manager is never below 76dp outside the face pile and the strip.

**Status ring**, the same for everyone (drawn with `drawArc`, 1.8dp, round caps):

| State | Ring | Badge | Face |
|---|---|---|---|
| idle | faint track only | none | eye open, slow blink (Librarian half-closed) |
| thinking | one 27 percent arc in the member's hue, turning at 0.45 turns per second | none | eye scans / radar arc speeds up |
| working | full teal ring at 50 percent plus a 20 percent bright segment turning at 0.38 turns per second | none | steady eye (Manager: satellites) |
| needs you | full amber ring, breathing 1.05 to 1.25 of its width | amber dot with an exclamation mark, bottom-end | eye open, member tinted toward amber by half |
| done | full teal ring | teal dot with a check, bottom-end | relaxed |

Badges sit at the bottom-end corner (left in Hebrew). State is readable without color: arc, full ring and badge shape differ. Sizes: 24 and 30 draw ring and dots only; 40 and up add the badge.

### 6.5 Five states at small size

`avatars.png` shows every member in all five states at 56, and the Manager, Scout and Coach at 24, 30 and 40. Findings: at 30 the ring and badge carry the state, the face carries identity by hue; at 24 only the ring still distinguishes thinking from working, so lists never use 24 for crew members (only the Manager's strip orb). The needs-you tint (amber mixed into the hue) makes members look alike in that one state, which is intended: the ring and badge are the message.

## 7. Motion (Compose)

One clock for every orb on screen, as `AgentOrb` does today. Draw into a `Canvas` and read the frame counter inside the draw lambda.

```kotlin
// One frame clock; avatars at 56dp or less may step every second frame.
val frame = remember { mutableLongStateOf(0L) }
val moving = animationsEnabled()                       // the existing guard
LaunchedEffect(moving) {
    if (!moving) return@LaunchedEffect
    var previous = 0L
    while (true) withInfiniteAnimationFrameNanos { now ->
        val dt = if (previous == 0L) 0.016f else (now - previous).coerceIn(0L, 50_000_000L) / 1e9f
        previous = now
        motion.step(dt, target)          // eases every parameter toward the state's targets
        frame.longValue = now
    }
}
Canvas(modifier) { frame.longValue; drawMikeFace(motion, state, who) }
```

| Element | Compose | Timing |
|---|---|---|
| State change (color, disk, pupil) | parameters eased in `motion.step` (`1 - exp(-dt * 4.5)`), color via `animateColorAsState(tween(400))` as `AgentOrb` does | about 0.5 s |
| Blink | `LaunchedEffect(state)`: `delay(Random.nextLong(8_000, 11_000))`, `lid.animateTo(0.06f, tween(90))`, hold 40 ms, `lid.animateTo(1f, tween(170, easing = FastOutSlowInEasing))`; apply with `withTransform { scale(1f, lid, eye) }` | rare; none while thinking, waiting or done |
| Gaze | `animateOffsetAsState(target, spring(dampingRatio = 1f, stiffness = 40f))`; target is the direction of the newest card, mirrored in RTL | slow, never a snap |
| Disk | per-dot angle advanced by `diskSpeed * rho^-1.5` (Kepler-like); far half drawn before the pupil, near half after | 0.05 to 0.2 turns per second |
| Status ring sweep | `rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)))` feeding `drawArc` | one turn in about 2.2 s (thinking), 2.6 s (working) |
| Needs-you breath | the same transition, sine, 3 s period; amplitude only 5 percent of pupil and ring width | one slow beat |
| Card moves between sections | `Modifier.animateItem(placementSpec = tween(300))` in a `LazyColumn` | 300 ms |
| Dismiss reasons | `AnimatedVisibility(expandVertically() + fadeIn(), tween(200))` | 200 ms |
| Progress hairline | `rememberInfiniteTransition` with `infiniteRepeatable(tween(2600), RepeatMode.Reverse)` on the fill offset | 2.6 s each way |
| Live dot | alpha 1.0 to 0.35 over 2.4 s | slow |
| Presence header collapse | scroll fraction drives orb size 176 to 40 dp and text alpha | follows the finger |

**Reduced motion** (`animationsEnabled()` false): no frame loop; draw the pose once (section 5.6); `snap()` for every state change; the live dot, progress hairline, ring sweep and comet are static; card moves and expansions use `snap()`. The information is unchanged: ring shape, badge, state line, amber color.

Performance (a prototype measurement, not a phone): the JS prototype draws 50 mixed orbs in about 15 ms per frame on a desktop CPU with no GPU. On the Manager screen the worst case is the hero (560 points) plus six 30dp avatars (170 points each) plus a 26dp pill orb. Suggested limits: avatars at 56dp or less step at 30 fps; nothing animates off screen; the team page animates only rows with a state other than idle.

## 8. Accessibility

**Touch targets, 48dp.** Strip chips are 34dp visible with a 48dp target. The dismiss ✕ is 48. Inline-card buttons are 44 in the mockup to save height; build them at 48. Timeline links and "Read the conversation" are 40 visible, so wrap them with `minimumInteractiveComponentSize()`. The face pile is one 48dp node, not six. Edit and Delete icons on notes are 40 visible in a 48 target.

**Contrast.** Section 2.5. All text pairs pass 4.5:1 in both schemes. Control borders pass 3:1. The only failures found were fixed (drawer faint text, light amber).

**Not by color alone.** Status uses shape too: arc, full ring, badge with a check or a mark; section labels carry words; the amber card also says "Mike asks".

**TalkBack labels** (Hebrew, with the English meaning):

| Element | Spoken |
|---|---|
| Strip | "משימות. 3 מחכות לך, אחת בעבודה, אחת לבדיקה. הכי דחוף: הטופס של דנה" (one node; chips are separate only when focused deliberately) |
| Task card | "הצעה: לשלוח לדנה את הטופס החתום. וואטסאפ, דנה, 14:10. הבקשה של דנה, לא שלך. שולח הודעה. בטלפון." with custom actions: "מייק, תעשה", "אני אעשה", "לא רלוונטי", "פתח פרטים" (so no swipe or tiny target is needed) |
| Manager orb | `contentDescription` equals the state line ("מייק, המנהל. 3 הצעות מחכות להחלטה שלך"). The canvas is cleared from semantics when the text beside it says the same, to avoid reading twice |
| Face pile | "הצוות של מייק. שישה חברים. לאחד יש שאלה אליך" (button) |
| Avatar in a row | "צופה, סורק הודעות ויומן. סורק עכשיו. 12 הודעות חדשות" |
| Dismiss reasons | The group is announced "למה לא?"; each chip is a button |
| Waiting card | The question has `liveRegion = Assertive`, like `QuestionCard`; everything else on the board is `Polite` |
| Timeline entry | "14:12. הטופס של דנה. הוצעה משימה, אושרה על ידך. לכרטיס" |

**Live regions.** The strip's sentence and the Manager's state line are polite. Only a running task's question is assertive.

**Font scale to 200 percent.** Card actions stack: Mike, do it on its own row, I'll do it and ✕ below. Tags wrap. The strip keeps its rule: header, then chips that scroll horizontally if they no longer fit (still at most three), then one sentence (up to three lines). Timeline entries wrap; the rail stretches with them. The presence header's name and state line wrap, and the orb does not shrink below 120dp.

**Reduced motion.** Section 7.

**Focus order.** Header, Manager row, then sections in order; inside a card: source, title, why, plan, tags, actions. In the Manager chat: top bar, presence header (name, state line, face pile), messages, suggestions, composer.

## 9. Dark and light

Dark is the shipped look and the primary design. Light is a proposal (section 2.2) rendered in every `*-light.png`. What changes: surfaces become warm whites, the pupil stays near-black, teal and amber use their darker ink values for text and rings, the Aura becomes a 10 percent wash, shadows are not used in either theme (borders only). The orb's colors and alphas come from one palette table in `orb.js` (`PAL.dark`, `PAL.light`); in Compose they belong in the scheme, not hard-coded per file.

## 10. Right to left

The mockups are `dir="rtl"` with logical properties only (no `left` or `right`), so they mirror by construction.

| Case | Rule |
|---|---|
| Direction-bearing icons (back, chevron, send, undo, restore) | Mirror. Use auto-mirrored vectors (`Icons.AutoMirrored`). |
| Phone, computer, clock, check, close, sparkle, eye, shield, moon | Never mirror. |
| Layout order | Back arrow at the start (right), actions at the end (left). The sheet drawer opens from the start; the dim sliver is at the end. |
| Buttons | Primary action first at the start edge. |
| Progress and timelines | Fill and rail begin at the start edge. |
| Badges | Ring badges at the bottom-end corner (left). The board's count badge on the Manager top bar sits at the icon's top-start corner (right). |
| Quote and rule bars | `border-inline-start` equivalent: the bar is on the right, next to the first word. |
| Orb gaze | "Toward the cards" is vertical and unaffected; any sideways gaze is stored in logical units and flipped by `LayoutDirection`. The status-ring badge uses the same flip. |
| Face pile | The first avatar (whoever needs you) is at the start; later ones overlap toward the end. |
| Numbers, times, Latin names | Digits read left to right inside Hebrew text. Wrap a Latin name or a number-with-hyphen run in first-strong isolates (`⁨ ... ⁩`) when it sits next to punctuation, so "ב-15:30" does not break. Rewrite where it reads badly ("בשעה 15:30"). |
| Mixed words | `agentTypography()` already sets `TextDirection.Content`. Keep it, and keep `withRtlLines()` for model text. |
| Quotes and abbreviations | Hebrew quotes and geresh: ״חתונה״, ׳ in "דק׳". |
| Canvas drawing | Read `LocalLayoutDirection`; mirror x offsets for anything that is "toward the end". |

Copy rules: buttons use the infinitive (לפתוח, לעצור, לבטל) so they carry no gender; "מייק, תעשה" and chips addressed to a crew member may use the imperative because they address Mike or the member; 24-hour times; no emoji.

### Hebrew strings used in the mockups

| Hebrew | Meaning |
|---|---|
| משימות | Tasks |
| מחכים להחלטה שלך / מייק עובד על זה / לבדיקה שלך / שלך / היסטוריה | Section labels |
| מייק, תעשה / אני אעשה / לא רלוונטי | Decision buttons |
| כבר עשיתי / לא עכשיו / טעות / אל תציע דברים כאלה | Dismiss reasons |
| הבקשה של דנה, לא שלך | Requested by Dana, not by you |
| שולח הודעה / קריאה בלבד · לא שולח דבר | Sends a message / read only, sends nothing |
| לקרוא את השיחה | Read the conversation |
| הוראות למייק | Instructions for Mike |
| מה שיניתי ולמה / לבטל שינויים | What I changed and why / Undo changes |
| מקורות / מה מייק זוכר | Sources / What Mike remembers |
| הצוות של מייק / צופה / ספרן / מאמן | Mike's team / Scout / Librarian / Coach |
| בוצע / להמשיך | Done / Continue |

## 11. Open questions and what is not verified

**Decisions for you**

1. **Orb direction.** I recommend A. This is taste: look at `orb-faces.png` and `orb-faces.html` (open it, press the chips).
2. **Teal "Mike, do it" or white.** Teal is calmer and gives Mike a color; white is the app's usual primary. One-line change.
3. **Light theme.** Ship it, or keep the app dark only? It needs a light `AndroidAgentTheme`.
4. **Do messages to a team member go into the user's own rules (`user.md`)?** The mockup says so, which matches 04-task-inbox section 5.8, but it is a behavior decision.
5. **Names.** Hebrew names are placeholders (Scout, Librarian, Coach). Should members have personal names, or only roles?
6. **Task areas.** Can the user hide or merge them? The team page offers "Manage areas" without defining it.
7. **Where the strip lives.** I drew it in the chat drawer, above the list. On `origin/dev` `AutomationStrip` has no call site (the drawer only has an "Automations" footer link), so this placement is my reading of 04-task-inbox section 4.3.
8. **"Read the conversation" consent copy.** It says Dana will see blue ticks. A native speaker and the product owner should review it.

**Not verified**

- Nothing was built in Compose, run on a phone, or tested with TalkBack. The orb is a JS canvas prototype; the Compose port (section 5.7) is described, not written.
- Hebrew text was rendered with the system font on Windows (Segoe UI fallback), not Android's Roboto, so line breaks and widths on a phone will differ a little.
- Motion was judged from deterministic stills and by reading the code, not by watching it for long. Blink, comet and breathing timing are my starting values.
- The 15 ms per frame for 50 orbs is a desktop number. A phone and Compose will differ; measure with the real draw code.
- Native Hebrew copy review has not happened. Gender forms and the exact wording of side-effect lines need it.

## 12. Suggested build order

1. Tokens (`ControlLine`, `CrewHue`), `MikeFace` with the Manager look and the status ring, behind the `animationsEnabled()` guard.
2. `TasksStrip` and `TasksSheet` with `TaskCard` for `proposed`, `running`, `waiting`, `review`; the dismiss reasons inline.
3. Card detail and the partial-context flow.
4. Manager chat (presence header, inline cards, report line).
5. Sources and Memory notes.
6. Team page and member page, after the Manager has real data.
