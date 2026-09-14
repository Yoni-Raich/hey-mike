---
name: device-automation
description: How every device tool works and how to recover when the phone does not respond as expected — read_ui queries and paging, node addressing, text input, scrolling, keys, deep links and approvals, plus fixes for taps with no effect, dialogs, keyboards, crashes and loops.
---

# Android Device Automation

The exact mechanics of each device tool, and what to do when an action does not land.

The accessibility service serves every tool below except `shell`, `push_file`, `pull_file` and `install_apk`, which need the optional Wireless ADB. The `source` field in an observation says which backend answered (`accessibility` or `uiautomator`), and `stable:false` means the screen had not settled when it was read.

---

## 1. Compact Semantic Observation (`read_ui`)

Always call `read_ui` to inspect screen elements before tapping.

### Result Structure
The normal result is compact JSON. Raw XML is available only with `raw=true` for debugging:
```json
{"ok":true,"revision":12,"activePackage":"com.example","stable":true,"nodes":[{"nodeId":"n3","text":"Search","resourceId":"com.example:id/search_box","contentDescription":"Search query","bounds":[72,140,936,260],"clickable":true,"enabled":true}]}
```

### Focused Queries and Paging
A busy screen does not fit in one reply. Never treat that as "the rest is not
there" — narrow the question, or page through it.

| Argument | Effect |
|---|---|
| `text` | Node whose `text` or `contentDescription` contains this (case-insensitive). |
| `resourceId` | Node whose `resourceId` contains this. |
| `class` | Node whose class name contains this, e.g. `EditText`, `RecyclerView`. |
| `package` | Node from this package, e.g. `package="whatsapp"`. |
| `rootNodeId` | That node and every node under it, and nothing else. |
| `clickableOnly` / `scrollableOnly` | Only what can be tapped, or only what can be scrolled. |
| `offset` | Skip this many matches. Use the `nextOffset` the previous reply handed you. |
| `maxNodes` / `maxChars` | Lower the caps for a small, cheap reply. |

Filters combine with AND. They change only what is **listed**: every node is
still on screen, and its `nodeId` still works with `tap_node`, `set_text` and
`scroll_node`.

Every reply reports what it left out:
```json
{"ok":true,"revision":12,"truncated":true,"totalNodes":812,"returnedNodes":96,"matchedNodes":240,
 "query":{"package":"whatsapp"},"nextOffset":96,"hint":"Nodes 1-96 of 240 matching. ...","nodes":[...]}
```
- `truncated:true` with `nextOffset` means there is more. Call `read_ui` again
  with `offset=<nextOffset>`, or ask a narrower question.
- `matchedNodes:0` means your filter matched nothing while `totalNodes` were on
  screen. That is a bad filter, not an empty screen.
- `rootNodeId` naming a node that is not on screen fails with
  `errorType:"ui_unknown_node"` instead of returning an empty list.

Worked example — find one contact in a long chat list:
```text
read_ui(package="whatsapp", text="Amir")   -> the row and its labels only
read_ui(rootNodeId="n42")                  -> everything inside that row
read_ui(clickableOnly=true, maxNodes=40)   -> just what can be tapped
```

### Unchanged Screens
When the screen and the query are both identical to the previous observation,
the node list is not resent:
```json
{"ok":true,"revision":13,"activePackage":"com.example","stable":true,"unchanged":true,"unchangedSinceRevision":12,"nodeCount":41}
```
Reuse the nodes from revision 12; they are still valid. This is diagnostic
information, not an error. If the action before it was meant to change the
screen, the action did not land — pick a different target or dismiss whatever is
covering it rather than repeating the same tap. Use `force=true` only when the
earlier node list is no longer available to you. Changing the query is enough on
its own to get a fresh reply, so a different filter or `offset` is never
suppressed as unchanged.

### Addressing Rules
1. **Search Criteria**: Look for elements where:
   - `text` contains or equals your target label.
   - `contentDescription` matches the accessibility label.
   - `resourceId` matches the Android or app view ID.
2. **Bounds Center Formula**:
   From `bounds:[x1,y1,x2,y2]`:
   - $x_{center} = \lfloor (x_1 + x_2) / 2 \rfloor$
   - $y_{center} = \lfloor (y_1 + y_2) / 2 \rfloor$
   - Example: `[72,140][936,260]` $\rightarrow x = (72+936)/2 = 504$, $y = (140+260)/2 = 200$.
   - Action: `tap(x=504, y=200)`.
3. **Clickable Ancestor Rule**:
   If the matched node has `clickable:false`, use `clickableAncestor.bounds` when supplied instead of guessing a parent.

### Typed Read Failures
- `ui_timeout` and `ui_idle_failure` are bounded failures. Do not repeat the same read in a loop.
- Use `screenshot` when visual state is enough, or perform one bounded retry only after a real state change.
- `ui_parse_failure` means semantic parsing failed safely. Use `read_ui(raw=true)` only to debug it.
- `ui_unknown_node` means the `rootNodeId` you passed is not on the current screen. Re-read without it and use an id from that reply.

---

## 1b. Node Addressing (only when these tools appear in your tool list)

`tap_node`, `set_text`, `scroll_node` and `wait_for_change` are served by the
accessibility backend. **They exist only in chats started after they shipped.**
If they are not in your tool list, this whole section does not apply — use the
bounds-centre maths in section 1 instead. Never call a tool you were not given.

When they are available, prefer them over coordinates: they act on the node
itself, so they cannot miss because the screen scrolled a few pixels.

- `tap_node(nodeId, observationId)` — both ids are required. `observationId`
  comes from the `read_ui` reply the node was listed in. A node from a stale
  observation is refused with an explanation rather than tapped blindly.
  After an `"unchanged":true` reply, the `observationId` you already hold is
  still accepted.
- `set_text(nodeId, observationId, text, submit?)` — replaces the field's whole
  contents. Check `verified` in the reply: some chat and Compose inputs accept
  the action and keep their old value. If `verified` is false, fall back to
  tapping the field and using `type_text`.
- `scroll_node(nodeId, observationId, direction)` — `forward`, `backward`, `up`,
  `down`, `left`, `right`. More reliable inside a list than a swipe gesture.
  `success:false` usually means the list is already at that end.
- A `Switch`, checkbox or radio reports `"checkable":true` with `"checked":true|false`.
  A node with no `checkable` field is not a toggle at all, which is a different
  answer from a toggle that is off. Read it before tapping a switch and again
  after, rather than assuming the tap flipped it.
- `wait_for_change(timeoutMs?)` — blocks until the screen changes and settles.
  Use it after an action that starts a transition instead of polling `read_ui`.
  `changed:false` means nothing moved, so the previous action did not land.

## 2. Text Input (`type_text`, `set_text`)

1. **Focus first.** `tap` (or `tap_node`) the text field so it holds input focus. Without focus `type_text` fails with `no_text_focus` and types nothing.
2. **Send the whole final text in one call.** On the accessibility backend `type_text` **replaces the field's entire content**; it does not append. To add to existing text, include the existing text in your call. Never type a message in pieces.
3. **Submit** with `submit=true` when Enter should run the search or send. Otherwise tap the on-screen Send or Search button.
4. **Any language works** — Hebrew, Arabic, emoji — with no escaping.
5. **Verify** with `read_ui` that the field holds exactly the intended text. `set_text` reports `verified`; when it is false, tap the field and use `type_text` instead.

---

## 3. Scrolling (`scroll_node`, `swipe`)

- **Inside a list, prefer `scroll_node`** on the scrollable node from `read_ui` (`scrollableOnly=true` finds it). It does not depend on screen size.
- **Use `swipe` only when no node is scrollable** (maps, canvases, carousels). Compute coordinates from this screen, never from a fixed resolution: take width W and height H from the largest `bounds` in `read_ui`, or from a screenshot. To reveal content below, swipe from (W/2, 0.7·H) to (W/2, 0.3·H) with `durationMs` around 350; reverse it to go up. For horizontal pages use 0.8·W → 0.2·W at mid-height.
- **Verify** with `read_ui` that new content appeared; `success:false` from `scroll_node` means the list is already at that end.

---

## 4. Keys (`key`)

- `key(keycode="BACK")`: close the keyboard, dismiss a popup, or go back a screen.
- `key(keycode="HOME")`: return to the launcher.
- `key(keycode="APP_SWITCH")`: open recent apps.
- `NOTIFICATIONS`, `QUICK_SETTINGS`, `POWER` and `LOCK` also work without ADB.
- To press **Enter**, use `type_text(..., submit=true)` or tap the on-screen button: the accessibility service cannot send `ENTER`, and other raw key codes need the optional Wireless ADB (`key_unsupported`).

---

## 5. App Lifecycle (`open_app`)

- To open an app by package: `open_app(package="com.example.app")`.
- When an app is already open but in the background, `open_app` brings it directly to the foreground without resetting state.

---

## 6. Deep Links and Intents (`resolve_intent`, `open_intent`)

A deep link that lands on the target beats `open_app` plus a sequence of taps. Use `resolve_intent` first when you are not sure the link is supported.

### Prefilled message bodies

Pass the body as `text`. **Do not build `?text=` into the uri yourself** — an unencoded space or `&` either truncates the message at the first separator or fails uri parsing outright:

```text
open_intent(uri="https://wa.me/972500000000", text="on my way & almost there", package="com.whatsapp")
```

The body is percent-encoded and attached for you. `text` needs a uri to attach to, is capped at 400 characters, and is refused if the uri already carries a payload (`text`, `body`, `subject`, `message`, `amount`, `cc`, `bcc`) — two payloads is ambiguous, so pass one or the other, never both.

A successful launch only means the intent was dispatched. Confirm with `read_ui` that the expected screen actually opened.

### Sending asks the user — opening a draft does not

Opening a chat with the text typed in (`wa.me/…` with `text`, `smsto:`, `mailto:`) sends nothing and opens at once. **The approval is on the Send itself**: when you tap a Send button (`tap`, `tap_node`, `act_and_observe`), or submit typed text in a messaging app (`type_text`/`set_text` with `submit=true`), the call pauses until the user answers.

- The app is raised and shows who gets which message, above the message box or on the voice screen.
- The user taps **Allow** or **Deny**, or just says or types **"yes" / "כן"** or **"no" / "לא"**. You cannot answer for them.
- They may tap **Always allow for <contact>** or **Always allow sending in <app>**. Then later sends it covers go through without pausing.
- After an Allow the app is brought back and Send is pressed for you. Confirm with `read_ui` that the message appears in the chat.

So **never end your turn to ask for permission to send, and never ask in the chat.** Write one short line saying who it goes to and what it says, then press Send in the same turn: the app itself stops and asks, and the tool call returns once the user has answered. In a voice conversation, say that line out loud.

A payment link (`amount`) still asks before it opens.

| `errorType` | Meaning | What to do |
|---|---|---|
| `send_denied`, `intent_denied` | The user said no. Nothing was sent. | Do not retry. Ask what they want instead. |
| `approval_timeout` | Nobody answered in time. Nothing was sent. | Tell the user it is waiting, then try again once they agree. |
| `send_not_approved`, `intent_not_approved` | The run stopped first. | Nothing was sent or launched. |
| `send_control_gone`, `send_app_gone` | Allowed, but the chat or Send button was gone on return. | `read_ui`, get back to the chat, press Send again. |

---

## 7. Recovery

### A tap had no effect
`read_ui` shows the same screen, or `"unchanged":true`, after an action that should have changed it.
1. **Non-clickable target.** Use the node's `clickableAncestor.bounds`, or `tap_node` on the clickable node.
2. **Still animating or loading.** Call `wait_for_change` once rather than tapping again.
3. **Covered or clipped.** A dialog, the keyboard or the screen edge is in the way. Dismiss it, or `scroll_node` the target into the middle first.
4. Never repeat the identical tap more than twice.

### The keyboard hides what you need
`key(keycode="BACK")` closes it without leaving the screen. Then `read_ui` again.

### An unexpected dialog
Read its title and buttons with `read_ui`.
- A **permission** the task genuinely needs ("allow contacts" to message someone): allow it, preferring "While using the app".
- **"App isn't responding"**: tap "Wait" once; if it comes back, tell the user.
- **Updates, promos, rating prompts**: "Not now", "Skip" or `BACK`.
- Anything asking for a **password, PIN, payment or deletion**: stop and ask the user.

### The app crashed or closed
`read_ui` shows the launcher or another app. `open_app(package=...)` brings it back; check where it resumed before continuing.

### Stuck in a loop
If two different attempts leave the screen unchanged, stop. Take one `screenshot` to see what `read_ui` may be missing (a canvas, a web view, an overlay), then either try a clearly different approach or tell the user what is blocking and ask how to proceed.

### Read failures
`ui_timeout` and `ui_idle_failure` are bounded. Do not call `read_ui` in a loop: use `screenshot`, or one retry after something changed.
