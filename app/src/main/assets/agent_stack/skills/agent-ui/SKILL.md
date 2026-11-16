---
name: agent-ui
description: Build a small interactive panel in the chat with show_ui when the user has to step in — fields to fill (an email or name for a website, a code, an address), a choice with a few settings, live buttons that really do it on tap (a flashlight switch, Do not disturb, media keys), a readout of current values with an apply button, or one of Mike's own settings right in the chat (turn on Wireless ADB, screen control, the floating Stop button, add a computer). It also puts live buttons outside the chat: a floating card over any app, an ongoing notification (a music banner) or a home-screen widget, which the user approves first. Read it before the first show_ui call in a chat.
---

# Agent UI: panels in the chat and outside it

`show_ui` draws a panel above the composer and waits until the user taps a button, closes it, or types in the chat instead. You compose it from a closed set of blocks; the app draws them. You never send code, HTML or tool calls for buttons.

## When to use it

- The user wants **buttons that do something** (turn the flashlight on and off, play or skip music, set the volume): use `action` tiles. A tap runs the tool at once. Never build a toggle plus Apply for this, and never wait for the panel's answer to act.
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
| `action` | `id`, `label`, `detail`, `tool`, `arguments`, `off_arguments`, `value`, `icon`, `accent` | A live button that runs your tool itself. See below. |
| `slider` | `id`, `label`, `min`, `max`, `step` (default 1), `value`, `detail`, `tool`, `arguments` | A number on a track. Returns the number as text. With `tool` and `arguments` it is live. See below. |

## Live actions: buttons that really do it

An `action` tile runs `tool` with `arguments` **the moment the user taps it**, with no round trip to you. The panel stays open, so the user can tap again (a remote control). Give `off_arguments` too and the tile becomes an on/off switch: on runs `arguments`, off runs `off_arguments`; `value` ("true"/"false") is where it starts, so read the real state first.

Only tools that do not read or touch the screen are allowed: `system_controls` (torch, volume, brightness, ringer, dnd, media keys), and similar. Screen tools, chat tools and unknown tools are refused when the panel is shown. The tile shows the real call under its label, and the normal approvals still apply.

`icon`: flashlight, bolt, wifi, bluetooth, volume, mute, brightness, moon, music, play, pause, next, alarm, timer, phone, message, location, camera, home, lock, power, star, heart, light, fan, tv, car, rotate, airplane, battery, bell, sparkle. `accent`: teal, amber, violet, rose, sky, lime. Up to 12 actions.

## Live sliders

A `slider` with `tool` and `arguments` runs the call **when the user lets go**, like an action tile. Put the string `"{value}"` in `arguments` where the number goes; the app puts the real number there. When `min`, `max` and `step` are whole numbers the number is an integer (`40`, not `40.0`). At most 1000 steps from `min` to `max`. Live sliders count toward the 12 actions. Set `value` to the real current level, read first. If a call fails, the slider goes back to the last value that worked.

```json
{"type": "slider", "id": "media", "label": "Media volume", "min": 0, "max": 100, "step": 5, "value": 40,
 "tool": "system_controls", "arguments": {"operation": "volume", "stream": "media", "percent": "{value}"}}
```

A slider without `tool` is a plain input: its value comes back in `values` when a button is tapped.

The result has an `actions` list of what the user ran (`id`, `on`, `ok`, `result`, and `value` for a slider), on every exit. Those calls did happen: never run them again, and report them truthfully.

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

A flashlight switch and two media keys (call `system_controls` `status` first and use its torch state as `value`):

```json
{"title": "Quick controls", "blocks": [
  {"type": "action", "id": "torch", "label": "Flashlight", "icon": "flashlight", "accent": "amber",
   "tool": "system_controls", "arguments": {"operation": "torch", "on": true},
   "off_arguments": {"operation": "torch", "on": false}, "value": "false"},
  {"type": "action", "id": "play", "label": "Play / pause", "icon": "play", "accent": "violet",
   "tool": "system_controls", "arguments": {"operation": "media_control", "command": "toggle"}},
  {"type": "action", "id": "next", "label": "Next track", "icon": "next", "accent": "sky",
   "tool": "system_controls", "arguments": {"operation": "media_control", "command": "next"}}]}
```

A one-shot tile has no `off_arguments`; each tap runs it once.

## Outside the chat: floating, notification, widget

By default a panel lives in the chat for this turn. Three more arguments put it somewhere else, next to `title` and `blocks`:

| argument | values | notes |
|---|---|---|
| `surface` | `chat` (default), `floating`, `notification`, `widget` | `floating` is a card over any app. `notification` is an ongoing notification with buttons. `widget` is a home-screen widget. |
| `lifetime` | `turn`, `ttl`, `pinned` | `chat` is always `turn`. Off the chat: `ttl` ends after `ttl_seconds`, `pinned` stays until the user removes it. |
| `ttl_seconds` | 5 to 86400 | Only with `ttl`. A floating card without a lifetime gets 60 seconds. |

What you get back:

- A **floating card with a ttl of 60 seconds or less** is shown at once: `{"ok": true, "panelId": "...", "status": "shown"}`.
- **Everything else** (a longer floating card, any notification or widget, anything pinned) waits for the user: `"status": "awaiting_approval"`. The app, not you, shows every call the panel can make, in words and as the raw call. You cannot approve it, and approval is refused while you are working. Tell the user in one short line that it waits in Hey Mike, Saved panels, and end your turn.
- Say a panel is shown or waits for approval **only** when `show_ui` returned that `status`. A refusal, a failure or a cancelled call means no panel exists: say it was not created, and do not claim it waits anywhere.

A panel outside the chat is a remote control, not a form. Nobody waits for its answer and you are not called when it is tapped:

- Only `text`, `status`, `action` and, on a floating card only, live `slider` blocks. No fields, toggles, choices, controls or buttons. At most 8 blocks, and a notification shows at most 3 actions.
- Its calls run **without you**, so they are limited to `system_controls` operations `torch`, `volume`, `ringer`, `dnd`, `brightness`, `auto_rotate` and `media_control`. Anything else, and anything that reads or touches the screen, is refused. Every call is checked again each time the panel loads and each time it is tapped; a call that no longer passes blocks the panel and the user sees why.
- A widget shows text and tiles only. A floating card hides while you control the screen and while Hey Mike is open.
- The user lists, shows again and removes every saved panel in Hey Mike, Settings, Saved panels. Removing it really takes away the card or the notification; a widget then says it was removed, and the user takes it off the home screen.

### Live values: `bind`

A `status` (or an `action` switch) outside the chat can show a value that the app reads again while the panel is on screen:

| key | meaning |
|---|---|
| `tool`, `arguments` | The read. Only `system_controls` with `operation` `status` or `media_status`. |
| `path` | Where the value is in the result, keys joined by dots: `current.title`, `current.positionMs`, `torch`. |
| `format` | `text` (default) or `time` (milliseconds as m:ss). |
| `equals` | For a switch: on while the value equals this. Without it, true, on, playing, 1 and yes mean on. |
| `every` | Seconds between reads, 2 to 300 (default 5). A notification reads at most every 15 s, a widget every 30 s. |

Give `value` the current text too; it shows until the first read.

### Music banner

A pinned notification with the song name and three keys (read `system_controls` `media_status` first and use `current.title` as `value`):

```json
{"title": "Now playing", "surface": "notification", "lifetime": "pinned", "blocks": [
  {"type": "status", "label": "Playing", "value": "Song name",
   "bind": {"tool": "system_controls", "arguments": {"operation": "media_status"}, "path": "current.title", "every": 5}},
  {"type": "action", "id": "back", "label": "-10 s", "icon": "rotate",
   "tool": "system_controls", "arguments": {"operation": "media_control", "command": "seek_by", "offset_ms": -10000}},
  {"type": "action", "id": "play", "label": "Play / pause", "icon": "play",
   "tool": "system_controls", "arguments": {"operation": "media_control", "command": "toggle"}},
  {"type": "action", "id": "ahead", "label": "+10 s", "icon": "next",
   "tool": "system_controls", "arguments": {"operation": "media_control", "command": "seek_by", "offset_ms": 10000}}]}
```

The result is `awaiting_approval`. Say: "It waits for your OK in Hey Mike, Saved panels." Then stop. For a card over the app instead, use `"surface": "floating"` and `"lifetime": "ttl", "ttl_seconds": 60`: it shows at once and goes away after a minute.

## Style

Title and labels in the user's language, short. Put the most important input first. Prefill every value you already know. One chat panel at a time; a refused panel's error says exactly what to fix.
