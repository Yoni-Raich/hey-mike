# New user experience - design

Status: design proposal, not implemented. The screens live as Design
Component sources in [new-user-experience/](new-user-experience/) and on
a private canvas (ask the owner for access).

## Problem

First launch shows the whole `SetupChecklist`: eight items, four marked
required. A new user has to work through runtime, account, screen
control, floating control, wireless ADB, notifications, updates and the
microphone before they can ask for anything.

## Principle

The user does by hand only what Android or the account provider forbids
an app to do for them. Everything else is either automatic, done by Mike
with the user's approval, or asked for when a task first needs it.

| Group | Items | How |
| --- | --- | --- |
| Background, no question | Runtime preparation | Starts at launch. A thin progress bar, no step of its own. A failure shows a retry card and never blocks sign-in. |
| User must do it | 1. Codex sign-in | Only the user can give a password. One button, browser, back. |
| | 2. Accessibility service | No app can enable it for itself. The screen shows exactly where to tap, with a rescue path for the Android 13+ restricted-settings block (`SetupState.BLOCKED`). |
| Mike does it, user approves | Floating stop control | Drawn as an accessibility overlay once the service is on, so `SYSTEM_ALERT_WINDOW` is no longer a separate step (to verify). |
| | Wireless debugging + pairing | Mike opens Developer options, turns it on, reads the pairing code (`onCapturePairing`) and pairs. |
| | Notifications | Mike opens Android's permission dialog; the user taps Allow. |
| Asked when needed, in chat | Microphone | First tap on the voice button. |
| | Install updates | When an update is ready. |
| | Notification access | When a rule needs to read notifications. |
| | Wireless debugging (if skipped) | First task that needs shell, files or installs. |

Rules for every screen: one request, one primary button. Mike shows what
it is about to change and waits for Allow / Not now before changing it.
The Stop control is always on screen while Mike acts. Android's own
permission dialogs are always tapped by the user, never by Mike.

## Flow

1. **Welcome** (`Main.dc.html`) - the orb, one promise, "two quick steps".
2. **Consent** (`Consent.dc.html`) - three statements the user must check
   before continuing:
   - full control of the phone is a real risk;
   - Mike can make mistakes, and done actions may not be undone;
   - data goes only to Codex (OpenAI) and is covered only by the Codex
     policies; Hey Mike has no servers of its own.
3. **Sign in** (`SignIn.dc.html`) - step 1 of 2.
4. **Screen access** (`ScreenAccess.dc.html`) - step 2 of 2, with
   **Restricted** (`Restricted.dc.html`) when Android blocks the switch.
5. **Handover** (`Handover.dc.html`) - Mike offers to set up the rest;
   the user can untick items or skip to chat.
6. **Approval** (`Approval.dc.html`) - Mike acting in Settings, the
   floating card with Stop, and a bottom sheet asking before the change.
7. **Ready** (`Ready.dc.html`) - first-task suggestions.
8. **Just in time** (`JustInTime.dc.html`) - an in-chat permission card.

## Everyday screens

- **Side panel** (`Drawer.dc.html`) - orb and plain status line in the
  header (replaces the unexplained red ring), chat search, chats grouped
  by day, running chat marked, Files and Settings in one row with an
  attention dot.
- **Settings** (`Settings.dc.html`) - a status card with the orb, then
  three groups: *What Mike can do* (abilities, not permissions, each with
  On / Set up / Fix), *How Mike works* (rules, ask before sending, model,
  power button assistant), *Account and privacy*. Screen control and
  floating control merge into one row. Local runtime moves under
  *Advanced and diagnostics*. Jev is left out while its PR is open.
- **One ability** (`Ability.dc.html`) - what it enables, how Mike sets it
  up, "Let Mike set it up".
- **Privacy and consent** (`Privacy.dc.html`) - where data goes, what was
  agreed and when, re-read the full text, withdraw consent.

## Visual language

Matches the current app: black chat background, `#1E1E20` panels with
`#262628` cards, blue `#2F7CF6` primary action, and the `AgentOrb` sphere
(teal working, blue controlling, amber stopping or warning, grey idle) as
the main visual. `Orb.dc.html` reproduces `AgentOrb.kt` for the mockups.

## Open questions

- Verify that an accessibility overlay can replace `SYSTEM_ALERT_WINDOW`
  for the floating control on the target devices.
- Verify that accessibility clicks work in Developer options on the test
  phone.
- The consent text needs legal review. The claim "collects nothing" must
  be checked against the code: update checks also leave the phone
  (`docs/PERMISSIONS_AND_PRIVACY.md`), though they carry no user data.
- Decide what "Withdraw consent" does exactly (stop, sign out, open
  accessibility settings, keep or delete chats).
- Where consent is stored, and whether a changed text asks again.
