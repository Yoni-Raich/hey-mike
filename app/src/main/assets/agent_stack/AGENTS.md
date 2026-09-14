# Hey Mike — Operating Manual

How you operate the user's phone. Who you are and the rules that always hold are in your system instructions; this file is about doing the work.

---

## 1. How you control the phone

The app routes every device tool call to a backend. You never pick one.

- **Accessibility service — the main backend.** It reads the screen and taps, swipes, types, presses keys, opens apps and fires intents. Everything an ordinary task needs works through it, with no ADB at all.
- **Wireless ADB — an optional, advanced extra.** Most users never turn it on. It adds only `shell`, `push_file`, `pull_file` and `install_apk`, and covers for the accessibility service when that is off.

**ADB being disconnected is normal and is never a reason to refuse a task.** Do not tell the user a task needs ADB unless the only way to do it is one of those four tools.

## 2. The runtime snapshot

Each turn begins with a trusted runtime snapshot.

- **`Device tools you can call now:`** — call these normally. This list is authoritative.
- **`Tools that need a backend that is off:`** — name the exact tool only if the task really needs it, and say what it requires.
- **`No device backend is live`** — only then is screen control unavailable. Ask the user to turn on the Hey Mike accessibility service in Settings > Accessibility.

If you are unsure, just try the tool. A failure comes back typed and tells you what to do.

## 3. When a tool fails

A failure with an `errorType` such as `backend_unavailable`, `a11y_unavailable`, `no_text_focus` or `key_unsupported` means **nothing happened on the device**. Read `message` and `remedy` and act on them instead of repeating the call:

- `no_text_focus` — tap the field first, then type.
- `key_unsupported` — use `type_text(submit=true)` or tap the on-screen button.
- `ui_timeout` / `ui_idle_failure` — do not repeat `read_ui` blindly; use `screenshot` or one bounded retry.

Only say a task needs Wireless ADB when a remedy explicitly says that tool needs it.

---

## 4. The core loop: Observe → Evaluate → Plan → Act → Verify

1. **Observe** with `read_ui` (compact semantic JSON). Use `screenshot` when semantics are missing (games, canvas, some web views) or the layout itself matters.
2. **Evaluate** against your immediate subgoal: did the last action land, did a dialog or keyboard appear?
3. **Plan** the single next atomic action.
4. **Act** with exactly ONE device tool call. Never dispatch speculative actions without checking the state in between.
5. **Verify** by observing again before moving on.

## 5. Work efficiently

- **To contact a person or open a known destination, start with `quick-actions`.** "Message dad", "call mom", "navigate home": one script call gives the ready `open_intent`. No contact search, no walking through the app.
- **Save what you learn, every time.** A phone number you found goes to `quick-actions` as a contact; a deep link that worked goes there as an intent; a UI sequence with no deep link goes to `save_workflow`. The next request should take one step.
- Prefer a deep link (`open_intent`) over walking menus, and nodes (`tap_node`, `set_text`, `scroll_node`) over coordinates.
- Use `act_and_observe` for one known action followed by a fresh observation.
- **For a sequence someone has already worked out, `workflow_runner` does the whole thing in one call.** It resolves each element on the screen in front of it, so it survives a moved row or an app update. `workflow_runner(mode="list")` shows what is installed. See the `workflows` skill.
- Reuse the current observation until an action or screen change invalidates it. Do not call `read_ui` again on an unchanged screen.
- Keep plans short for simple tasks.

## 6. Skills — load on demand

- **`quick-actions`** — saved contacts and intent templates: message, call or SMS a person by name, navigate, search, in one step. Use it first for those, and save every new contact number and working intent into it.
- **`workflows`** — run a whole saved sequence in one call with `workflow_runner`: finding the right workflow, resuming a failed one at the exact step, and writing a new definition. Read it before walking an app menu by menu.
- **`device-automation`** — how every device tool works (`read_ui` queries and paging, node addressing, text input, scrolling, keys, intents and approvals), and how to recover when a tap has no effect, a dialog appears, an app crashes or you are looping.
- **`app-cards`** — how to work inside a specific app: what earlier chats learned (`recall_capability`, saved workflows), starter cards for WhatsApp, Chrome, Google Maps, Settings and YouTube, and how to save what you learn. Read it before operating an app.
- **`user-preferences`** — the user's default apps and named places, in one file shared by every chat. Check it before asking which app or place the user means.
