---
name: agent-ui
description: Build a small interactive panel in the chat with show_ui when the user has to step in — fields to fill (an email or name for a website, a code, an address), a choice with a few settings, a readout of current values with an apply button, or one of Mike's own settings right in the chat (turn on Wireless ADB, screen control, the floating Stop button, add a computer). Read it before the first show_ui call in a chat.
---

# Agent UI: panels in the chat

`show_ui` draws a panel above the composer and waits until the user taps a button, closes it, or types in the chat instead. You compose it from a closed set of blocks; the app draws them. You never send code, HTML or tool calls for buttons.

## When to use it

- You need **two or more facts** only the user has (sign-in email and name for a site, a delivery address, a one-time code). One plain question: use `ask_user`.
- The user should **see current values and pick new ones** (ringer mode, brightness, Do not disturb). Read them first, show them as `status` blocks and inputs prefilled with `value`, then apply what comes back with your normal tool (`system_controls`, `device-capabilities`, ...).
- One of **Mike's own settings** is the next step: show it as a `control` block instead of sending the user to Settings.
- Never for a yes/no confirmation of an action: approvals already do that.

## Blocks

| type | fields | notes |
|---|---|---|
| `text` | `text`, `tone` | `tone`: neutral, good, warn, bad. Short. |
| `status` | `label`, `value`, `tone` | A readout. Not an input. |
| `field` | `id`, `label`, `kind`, `placeholder`, `value`, `required` | `kind`: text, email, secret, number, url, phone, multiline. |
| `toggle` | `id`, `label`, `value` ("true"/"false"), `detail` | Returns "true" or "false". |
| `choice` | `id`, `label`, `options` (2–12), `value`, `required` | Returns the option text. |
| `control` | `control` | The app's live setting. See below. |

Buttons: up to 4, each `id`, `label`, `style` (primary, secondary, danger, cancel). No buttons gives one **Done**. A non-cancel button stays disabled until every `required` input is filled.

## Controls

`wireless_adb`, `screen_control`, `floating_stop`, `notifications`, `install_updates`, `microphone`, `model`, `send_approvals`, `assistant`, `automations`, `account`, `add_computer`.

A control changes the real setting the moment the user touches it, exactly as in Settings. The result lists the controls shown and does **not** say what the user did: check afterwards (`device_status`, the runtime snapshot, `computers`) before you say anything changed. `add_computer` saves the computer and its password on the phone; the password never reaches you.

## What comes back

```json
{"ok": true, "button": "send", "buttonLabel": "Send", "values": {"email": "dana@example.com", "name": "Dana"}}
```

Closed without a button: `{"ok": false, "errorType": "closed"}` — do not act on the values. Typed in the chat instead: `{"ok": false, "errorType": "dismissed", "typed": "..."}` — act on the user's words. No answer within 8 minutes: `no_answer`.

A `secret` field's value reaches you, but the chat history and traces show `••••`. Use it once, for the task it was given for, and never repeat it in a message.

## Examples

Email for a website sign-up:

```json
{"title": "Sign up for the newsletter", "blocks": [
  {"type": "text", "text": "The site needs these to sign you up."},
  {"type": "field", "id": "name", "label": "Full name", "required": true},
  {"type": "field", "id": "email", "label": "Email", "kind": "email", "required": true}],
 "buttons": [{"id": "send", "label": "Sign up"}, {"id": "skip", "label": "Not now", "style": "cancel"}]}
```

Connect a new computer:

```json
{"title": "Add your computer", "blocks": [
  {"type": "text", "text": "Fill in the address and user name. The password stays on the phone."},
  {"type": "control", "control": "add_computer"}]}
```

Then call `computers` to see it and connect.

Turn on Wireless ADB (Mike suggests it):

```json
{"title": "Let me run commands", "blocks": [
  {"type": "text", "text": "Wireless ADB lets me install apps and run commands. Turn it on here.", "tone": "warn"},
  {"type": "control", "control": "wireless_adb"}]}
```

Then check `device_status`.

A readout with apply:

```json
{"title": "Sound", "blocks": [
  {"type": "status", "label": "Ringer now", "value": "Vibrate", "tone": "warn"},
  {"type": "choice", "id": "ringer", "label": "Ringer", "options": ["Normal", "Vibrate", "Silent"], "value": "Vibrate"},
  {"type": "toggle", "id": "dnd", "label": "Do not disturb", "value": "false"}],
 "buttons": [{"id": "apply", "label": "Apply"}, {"id": "cancel", "label": "Cancel", "style": "cancel"}]}
```

Then apply the values with `system_controls`, and say what changed.

## Style

Title and labels in the user's language, short. Put the most important input first. Prefill every value you already know. One panel at a time; a refused panel's error says exactly what to fix.
