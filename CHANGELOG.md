# Changelog

## Unreleased

- Hey Mike is now dual-licensed. The project moves from Apache-2.0 to the
  **GNU AGPL v3.0** for everyone, plus a separate **commercial license** for
  anyone who wants to ship it inside a closed-source product. Personal use,
  study, research and contributions stay free; a proprietary fork now needs an
  agreement. Releases made before this change keep the license they shipped
  under. See `LICENSE`, `LICENSE-COMMERCIAL.md` and `NOTICE`.
- Contributions are now accepted under a Contributor License Agreement
  (`CLA.md`), signed once in your first pull request.
- Mike can hold standing rules: when something happens, and the conditions are
  true, do this. A rule is woken by the clock ("every day at 19:00", "weekdays
  at 07:30"), by arriving at or leaving a place, by a notification from one
  named app, by a device signal such as charging, or by name. It can then run a
  saved workflow, open an app screen, tell you something, work out what to do
  in a thinking turn, ask you a yes/no question, or start a voice conversation.
- Every rule says who has to be awake for it, and Mike works that out from what
  the rule does rather than taking its word for it. A rule that only runs a
  workflow runs at a locked phone; one that wants to talk to you waits until
  you can answer instead of talking to a pocket.
- A rule sends on only the parts of an event it actually writes into what it
  does. A rule that reads a message to decide, but whose reply only uses the
  sender's name, never sends the message itself anywhere — and Mike says which
  fields those are when it saves the rule. A notification rule has to name the
  app it listens to; there is no "every notification".
- A new rule can be dry-run against any moment — "pretend Dad messaged at
  21:40" — and a rule that would not fire says which of its own clauses stopped
  it, so "nothing happened at 19:00" has an answer. A dry run changes nothing.
- A rule cannot run away: it has a cooldown and a daily limit, and an alarm
  that fires at the wrong minute does not fire the rule.
- Rules now actually run. Mike keeps one alarm pointed at whichever rule is due
  next and re-arms it after every firing and after a restart, watches for
  charging, unplugging and the screen, and — once you grant it in Settings —
  watches notifications from the apps your rules name.
- A rule that drives the phone takes it the same way a task does: the same
  control card, the same Stop. If you are already using the phone the rule says
  so instead of fighting you for the screen.
- A rule that needs a thinking turn writes into its own chat, one per rule, so
  something firing at 3am does not appear in the middle of your conversation and
  there is a record of what each rule has been doing.
- A rule that wants a yes/no puts a notification up with two buttons. No answer
  in five minutes is a no — a question you never saw never becomes a yes.
- A rule Mike cannot serve on this phone is saved and reported as dormant with
  the reason, instead of looking live and doing nothing. Place rules are dormant
  on every phone for now: nothing tracks location yet.
- You can ask Mike to run a rule now, so a new rule can be shown working
  straight away instead of waiting until tonight. Naming it stands in for its
  trigger; everything else about it — the hours it is allowed, its cooldown, its
  daily limit — still applies.
- A rule you ask Mike to run while it is already busy now waits for it to finish
  instead of answering "the phone is busy".
- Settings > Standing rules shows how many rules are on, how many cannot run on
  this phone and why, when the next one is due, and has the buttons for the two
  permissions rules need: notification access and exact alarms.
- The side panel now opens on your rules, not just your chats. A strip above the
  chat list says what is running, what needs you, and the one thing worth
  knowing right now — "Home lights can't run, Mike can't track location yet", or
  "Next: Evening post, in 6 hours".
- Tap the strip for every rule, grouped by what needs you: the ones that cannot
  run, the ones that are running, and the ones you turned off. Tap a rule to see
  what it does as a chain — when, and, then — what it costs, and exactly which
  parts of an event leave your phone. Turn it off there, or run it now.
- The panel opens from a menu button at the top left instead of from the chat's
  name, so the name is just the name of what you are reading.
- That button takes a small amber dot when a rule is switched on and cannot run.
  That was invisible before: you would find out by the thing never happening. No
  dot when everything is fine.
- Rules now describe themselves in words rather than in their own format:
  "WhatsApp from Dad, after 19:00 and before 07:00", "Mon, Tue, Wed, Thu and Fri
  at 07:00", "Ran yesterday at 21:40 - twice today".

- Mike can run a whole saved sequence in one go. `workflow_runner` takes the
  name of a workflow and does every step itself — open the app, find the
  search box, type, open the result, flip the switch — with no thinking turn
  in between. No workflow ships with the app: Mike learns each one on your
  phone, because every phone's screens are different.
- A workflow can take values each time it runs — one "timer" workflow for any
  number of minutes — and can open an app screen or start a feature directly
  by intent, tapping only where no intent reaches.
- Mike can now reach any Android feature an intent reaches, such as a timer or
  a settings screen, without an app update for each one.
- After Mike does something on your phone in several steps, a "Suggest
  workflows" button offers to save it as a workflow.
- A workflow is written down as what each step *means*, not where it was on
  the screen the day it was recorded. Mike finds each element live, by its id
  or by the label you can read, so a row that moved, a phone with a different
  screen, or an app update does not break it. There is no way to save a
  coordinate into one.
- Every step says how to tell it worked, and Mike checks before moving on. A
  tap that landed on nothing is now a reported failure instead of a run that
  quietly continues against the wrong screen.
- A step that turns something on, sends, pays, deletes or changes a permission
  stops and asks you first — the same card as a send, and the same spoken
  "yes" or "כן". A step that flips a switch is skipped when the switch is
  already the way it should be.
- When a step does fail, the reply names that exact step, what had already
  run, whether that step may have half-happened, what was on screen instead,
  and how to carry on from there. Mike continues from where it stopped instead
  of starting the whole sequence again.
- Reading the screen now reports whether a switch or checkbox is on. Mike used
  to have to guess from the label whether a toggle had taken effect.

## 0.12.0 — 2026-09-14

- Mike can be the phone's digital assistant. Pick Hey Mike under Default apps >
  Digital assistant app, and holding the power button opens a live voice
  conversation with Mike instead of Gemini. A press starts a new chat unless
  the open one is empty, and never opens over the lock screen. Settings >
  Digital assistant shows whether it is on and opens the picker. "Hey Google"
  stays with Google. The voice screen comes up the moment the button is held,
  with the sphere flying in from the power button edge while Mike starts.
- Approvals you can see, answer by voice, and remember:
  - The approval card is pinned above the message box, and shown on the voice
    screen. It used to sit at the top of the chat, out of sight in any long
    chat, and under the voice screen entirely.
  - It says who gets which message instead of an encoded link.
  - Say or type "yes" / "כן" or "no" / "לא" to answer it.
- Mike asks at the right moment: opening a chat with the text typed in no
  longer asks, since it sends nothing; pressing Send does. Tap "Always allow
  for <contact>" or "Always allow sending in <app>" to stop being asked, and
  review or remove those in Settings > Sending approvals. The list is signed
  with a key in the Android Keystore, so the agent cannot add to it.
- After you allow a send, Mike returns to the open chat with its draft and
  presses Send in the same turn. It used to land on the app's chat list, lose
  the Send button and ask a second time.
- Mike knows the phone's date, time and time zone. Asked about "today", it
  used to guess the day from the calendar on screen.
- Opening an app waits until the app is in front, so Mike no longer decides
  that Calendar did not open and falls back to the web.
- Opening an app or a link works with the accessibility service off. Mike
  used to refuse a deep link it could open.
- Known gap: with Wireless ADB on, shell and key events can still send without
  asking.

## 0.11.0 — 2026-09-13

- Quick actions: "message dad", "call mom" or "navigate home" is one step.
  Mike saves each contact's number and each deep link that worked, and a
  script on the phone turns the request into the ready intent, with no contact
  search and no walking through the app. It ships with WhatsApp, SMS, dialer,
  Google Maps, Waze, web and YouTube intents, and Mike adds its own as it
  learns them. Messages still wait for your approval in the app.

- Mike no longer says a task needs ADB when it doesn't. Screen control runs
  on the accessibility service, and Wireless ADB is an optional extra, but
  several things still told the agent otherwise:
  - Every chat got an old copy of its instructions, written from the days when
    everything went through ADB. It replaced the current one each time a chat
    opened.
  - The system prompt called the per-turn snapshot "ADB availability", and
    the snapshot opened with the ADB phase.
  - A call the accessibility service turned down for a reason of its own,
    like typing with no field focused or pressing Enter, fell through to a
    disconnected ADB and came back as "ADB is not connected".
  - `act_and_observe` worked only over ADB.
- Wireless ADB is no longer a setup step. The chat stops showing "1 thing to
  finish before the agent can run" on a phone without it, and Settings lists
  it under "Nice to have".
- When neither the accessibility service nor ADB is on, Mike now says so and
  asks for the accessibility service. The always-available knowledge and
  workflow tools used to hide that.
- Mike remembers your preferences across chats. Default apps, addresses and
  contacts live in one file for the whole app instead of a copy per chat,
  where a preference saved in one chat was gone in the next. A preference
  you already taught an older version carries over.
- Mike's guidance is simpler: a chat folder holds just `AGENTS.md`, and three
  skills replace four. `device-automation` now includes recovery, and
  `app-cards` points Mike at what earlier chats learned about an app before
  the hand-written cards. Typing guidance matches how the accessibility
  service types (it replaces a field rather than appending), and scrolling
  no longer assumes one screen size.

## 0.10.1 — 2026-09-11

- Voice mode speaks as Mike: the caption labels the agent "MIKE" instead of
  "CODEX", the status reads "Mike is speaking" and the notification says
  "Talking with Mike". The chat title at the top of voice mode is readable
  again; it was drawn in dark text on the dark background.

## 0.10.0 — 2026-09-11

- Android Agent is now **Hey Mike**, and the agent is **Mike**. The app,
  its Accessibility entry, the notification and the floating card use the
  new name, and the agent knows its name: it answers to "Mike" or
  "Hey Mike" and introduces itself as Mike. The repository moved to
  `Yoni-Raich/hey-mike`; GitHub redirects the old address, so v0.7.3 and
  earlier still find updates. The package id (`dev.androidagent.app.dev`)
  is unchanged, so updating keeps the sign-in and every chat.

## 0.7.3 — 2026-09-11

- Device control works in the published APKs. Codex runs tool calls for
  code-mode models through a helper process, and the helper was packaged as
  `codex-code-mode-x.so`. Android extracts only `lib*.so` files from a release
  APK, so every published APK from 0.6.1 to 0.7.2 installed without it: the
  agent could chat but could not read the screen, tap or open an app. Debug
  builds are exempt from that rule, which is why builds installed from a
  computer worked. The helper is now `libcodex_codemode.so`, and the build
  fails if any packaged native file is not named `lib*.so`.

## 0.7.2 — 2026-09-11

- The top bar is redesigned around the agent's sphere. The chat title opens
  the chat list, and the sphere is the status: teal when the agent can reach
  the phone, blue and busy while it works, amber when nothing lets it control
  the phone, with the quota as a ring around it. It opens one sheet with each
  backend and its fix, the quota windows, the chat's files and settings. The
  status no longer says "ADB · disconnected" while accessibility is in
  control, and the bar no longer repeats the run status or the drawer's
  Files and Settings.

- Workspace files open in a sheet instead of a card at the bottom of the
  chat. The files you attached and the ones the agent saved come first,
  newest on top, with their real names; a tap opens one in another app and
  the share button sends it on. The instructions and app cards the app puts
  in every chat fold away under "Agent files". Notes and JSON are offered as
  plain text, so a text viewer can open them.

## 0.7.1 — 2026-09-11

- Skills are easy to find: a Skills chip under the field opens every skill and
  command with search, and typing `/` opens the same list above the field
  (`$` still lists skills only). Skills show their own name, colour and short
  description from Codex, and a picked skill sits in the field as a chip and
  fills in its default prompt.
- `/` also offers commands: New chat, Compact, Plan mode, Model, Rename and
  Status. Plan mode stays on, shown as a chip, until you turn it off; its
  turns ask Codex for a plan before acting. Compact and Status leave a short
  note in the chat.

## 0.7.0 — 2026-09-11

- Voice now has its own full-screen mode instead of a line under the composer.
  Starting voice dissolves the chat, and the voice button lifts off and grows
  into a sphere of points that swells with the live audio level: teal while you
  talk, blue while Codex answers. The live caption and the controls rise in
  below it. Ending voice plays the same animation back into the button.
- The microphone can be muted mid-conversation without ending it. On WebRTC
  the local track is disabled; on WebSocket the stream carries silence.
- The voice controller now publishes the audio level of whoever is talking.
  Over WebRTC, Codex's playback level also marks it as speaking.
- The floating controls are redesigned as a pill under the status bar. It
  says what the agent is doing in plain words ("Tapping", "Reading the
  screen") next to a live sphere coloured by the run's state, with Stop always
  on it. A tap grows it into a card with the agent's latest words and the
  steer field; an approval shows a button to review it in the app. It is
  always dark, like the app, and it can still be dragged anywhere.
- On Android 14+, accessibility screenshots are assembled window by window
  and leave the floating controls out, so the pill no longer disappears while
  the agent looks at the screen. Older Android and the ADB fallback still step
  it aside for the moment of capture.
- `act_and_observe` now reports the action it carries (for example `tap`) as
  the run status, instead of `act and observe`.
- The composer is one rounded field with one button that is voice while the
  field is empty, send once there is text and stop while the agent works;
  while steering, a separate stop sits beside send. Model and reasoning share
  one chip under the field that opens a sheet. The run's status sits above the
  field with the agent's pulse, in plain words ("Reading the screen").
- Back-to-back device actions fold into one row ("5 actions on your phone")
  that opens to the steps, and each step to what the tool returned, instead of
  one "Device activity" row per tool call.
- A message sent after Stop or after a voice conversation no longer sits in a
  paused queue. Pausing still holds the work that was already waiting until
  Resume, but anything submitted after the pause runs as soon as the device is
  free.
- The launcher icon is the agent sphere on a transparent background, as an
  adaptive icon with a monochrome layer for themed icons. The notification
  keeps the star.
- The composer's run status and a reply that is still streaming show the
  voice-mode sphere, coloured by the run's state, instead of the phone-and-gears
  glyph.
- Right-to-left text is decided per line: a line with any Hebrew or Arabic
  letter reads right to left even when it starts with Latin, in replies, your
  messages and the composer. A reply's list with any Hebrew item keeps all its
  items on the same side.

## 0.6.2 — 2026-09-10

- `open_intent` now takes the prefilled message body as `text` and encodes it
  into the deep link, instead of expecting a hand-built `?text=` that an
  unencoded space or `&` would truncate or make unparseable. Attaching a body
  still needs the user's approval, and that approval no longer looks like a
  hung tool call: the app is raised so the card can be answered, the floating
  card says "Approve in Android Agent", and a denial, an unanswered approval
  and a stopped run are now three distinct typed errors instead of one "denied
  or expired". A cancelled approval no longer strands its card and block every
  later approval.
- Device control is no longer reported as one global ADB-dependent switch. Each
  turn now carries a snapshot naming the tools that can be called right now and
  the tools whose backend is down, so a disconnected Wireless ADB no longer
  blocks `read_ui`, `tap`, `type_text`, `open_app` or `open_intent` — all of
  which the accessibility service serves with no ADB at all. The legacy "Do not
  call device tools" instruction and the ADB-only framing in the on-device
  `AGENTS.md` are gone. Fixes #44.
- `read_ui` can now be asked a focused question instead of returning the whole
  screen and silently dropping the tail. It takes `text`, `resourceId`, `class`,
  `package`, `rootNodeId`, `clickableOnly` and `scrollableOnly` filters plus
  `offset`, `maxNodes` and `maxChars`, and every reply reports `totalNodes`,
  `returnedNodes`, `matchedNodes` and a `nextOffset` cursor when it left
  something out. Filters change only what is listed, so node ids stay valid for
  `tap_node`, `set_text` and `scroll_node`. Fixes #45.

- Added public Developer Preview documentation, privacy notes, contribution
  guidance, and release evidence rules.
- Added the current screen-awake behavior for active typed and voice runs.
  Physical voice and broader end-to-end checks remain open.

## 0.6.1 — 2026-09-09

- Added the `repo-structure-guard` skill for agents that develop this
  repository. It documents module ownership, safe paths, generated files,
  worktree rules, evidence boundaries, and release checks.
- Added the setup hub, quota meter, run indicator, pairing scan, and bounded
  ADB setup flow from the merged `main` changes.
- Published a dev-flavor test APK with v3 debug signing for local testing.

## 0.6.0 — 2026-09-08

- Added workflows, intent handling, knowledge storage, accessibility capture,
  and proxy diagnostics.
- Published a dev-flavor test APK with v3 debug signing.

## 0.5.0 — 2026-09-08

- Added the in-process Accessibility device backend and shared observation
  routing.
- Added redaction and password-node handling to semantic observations.

## 0.4.0 — 2026-09-08

- Improved chat presentation, session queue behavior, overlay controls, voice
  stop handling, and audio-route policy.

## 0.3.x — 2026-09-07

- Added the staged `read_ui` path, on-device skill catalog behavior, richer chat
  rendering, and the WebRTC voice path.

## 0.2.x — 2026-09-06

- Added experimental realtime voice contracts and the app-private feature gate.

## 0.1.x — 2026-09-06

- Established the Android Agent app, on-phone Codex runtime boundary, session
  workspace, Wireless ADB path, device gateway, and floating control UI.

All entries describe repository changes, not a promise that every feature is
verified on every Android device. See [Testing](docs/TESTING.md) and
[Known issues](docs/KNOWN_ISSUES.md).
