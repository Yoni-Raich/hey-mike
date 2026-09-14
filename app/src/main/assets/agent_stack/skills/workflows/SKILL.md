---
name: workflows
description: Run a whole multi-step phone sequence in one call with workflow_runner — how to find the right workflow, read its steps, resume a failed one, and write a new definition. Read this before walking an app menu by menu when the task sounds like something that has been done before.
---

# Workflows

A workflow is a saved sequence — open Settings, search, open the result, flip the
switch — stored as a JSON file and executed by **one** tool call. No model turn
per step.

Use it when the task is a sequence someone has already worked out. Use the
ordinary device tools when you are exploring, deciding, or doing something once.
---

## 1. Find the workflow

```text
workflow_runner(mode="list")                      -> every installed workflow
workflow_runner(mode="list", package="com.android.settings")
```

Each entry names the workflow, its package, what it does, how many steps it has,
and whether any step stops to ask the user.

To see the steps before committing to them:

```text
workflow_runner(workflow="wifi-toggle", mode="describe")
```

`describe` runs nothing. It prints the step list so you can tell the user what is
about to happen, or check that the workflow really matches the request.

## 2. Run it

```text
workflow_runner(workflow="wifi-toggle", mode="run")
workflow_runner(workflow="timer", mode="run", params={"seconds": 600})
```

That is the whole call. A workflow whose listing shows `parameters` takes their
values in `params`; convert the user's words to the unit it names. The runner then, for every step:

1. reads the screen,
2. finds the element the step describes — by `resourceId`, by visible text, by
   `contentDescription` — on the screen in front of it,
3. taps, types, scrolls or presses,
4. waits for the screen to settle,
5. checks the step's own `verify` condition before moving on.

Because every element is resolved at run time, a row that moved, a different
screen size or a renamed view id does not break the workflow. Nothing positional
is stored: `nodeId` and `observationId` are created during the run and are gone
when it ends.

A clean run comes back as:

```json
{"ok":true,"workflow":"wifi-toggle","ranSteps":5,"skippedSteps":0,
 "steps":[{"id":"open_settings","action":"open_app","status":"done","verified":true}, ...]}
```

## 3. When a step fails

The reply names exactly where it stopped:

```json
{"ok":false,"failedStep":"open_result","failedStepIndex":3,
 "errorType":"target_not_found","failedStepDoes":"tap text=\"Wi-Fi\"",
 "stepMayAlreadyHaveRun":false,
 "steps":[... the steps that did run ...],
 "screen":{"activePackage":"com.android.settings","visible":[{"text":"Network & internet"}, ...]},
 "resume":{"tool":"workflow_runner","arguments":{"workflow":"wifi-toggle","mode":"resume","startAt":"open_result"}}}
```

**Never start the workflow again from the top.** The steps listed as `done`
already happened; replaying them can send a message twice or turn a switch back
off. Deal with whatever is in the way, then call `workflow_runner` with the
`resume` arguments the reply handed you.

- `stepMayAlreadyHaveRun:true` — the failing step may already have changed the
  phone. Read the screen before you repeat anything.
- `stepMayAlreadyHaveRun:false` — that step changed nothing, so resuming from it
  is safe.

| `errorType` | What happened | What to do |
|---|---|---|
| `target_not_found` | The element the step describes is not on this screen. | Look at `screen.visible`. The app may have changed, or an unexpected dialog is on top. Clear it, then resume. |
| `verification_failed` | The step ran, but the state it expected did not appear. | The action landed somewhere else, the screen is still loading, or this screen is not the one the definition was written for. Read the screen, finish by hand, and fix the definition's selectors from what `read_ui` shows. |
| `confirmation_denied` | The user said no. | Nothing ran. Do not retry; ask what they want instead. |
| `confirmation_timeout` | Nobody answered the approval. | Tell the user it is waiting, then resume from that step once they agree. |
| `confirmation_unavailable` | Nothing could ask the user. | Do that one step yourself, with the user's agreement, then resume from the next step. |
| `budget_exhausted` | The run hit its time limit. | Nothing was lost. Resume from the step named. |
| `stopped` | The user pressed Stop. | Report what had already run. Do not continue. |
| `workflow_not_found` / `workflow_ambiguous` | The name did not resolve. | The reply lists the installed workflows. Use one of those names. |

## 4. Steps that ask first

A step marked `requiresConfirmation` stops and asks the user before it runs —
turning on wireless debugging, sending a message, paying, deleting, changing a
permission. The Hey Mike app comes to the front with one line saying what the
step does, and the user taps **Allow** or **Deny**, or just says **"yes" / "כן"**
or **"no" / "לא"**.

You cannot answer for them, and you must not end your turn to ask: the call
itself pauses and returns once they have answered. Say in one short line what is
about to happen, then make the call.

## 5. Writing a new workflow

Work the sequence out once with the ordinary device tools. When it runs cleanly,
write it as `<id>.json` in `{{WORKFLOW_DEFINITIONS_DIR}}` so the next chat costs
one call instead of ten turns. `save_workflow` does **not** write there.

Build the selectors from what `read_ui` actually returned **on this phone**, on
the run that worked — not from what the screen looks like, from another phone,
or from these examples:

- A search field is often not an `EditText` by class name, and its id differs
  by maker (`android:id/search_src_text` on a Nothing phone, `android:id/input`
  on a Xiaomi). Name it by the `resourceId` you read.
- After typing, the search field's own text equals the result's title. Target
  the result with its `resourceId` (`android:id/title`) and `"exact": true`.
- A switch usually carries its row's label as `contentDescription`. Target it
  with that label **and** `"className": "Switch"`; the class alone matches every
  switch on the screen and is never enough.

```json
{
  "id": "silence-for-an-hour",
  "version": 1,
  "package": "com.android.settings",
  "description": "Turn on Do Not Disturb.",
  "steps": [
    { "id": "open", "action": "open_app", "arguments": {"package": "com.android.settings"},
      "verify": {"package": "com.android.settings"} },
    { "id": "search", "action": "tap", "target": {"text": "Search settings", "clickable": true},
      "verify": {"present": {"resourceId": "android:id/search_src_text"}} },
    { "id": "query", "action": "type_text", "text": "Do Not Disturb",
      "target": {"resourceId": "android:id/search_src_text", "className": "EditText"},
      "verify": {"present": {"resourceId": "android:id/title", "text": "Do Not Disturb", "exact": true}} },
    { "id": "open_result", "action": "tap",
      "target": {"resourceId": "android:id/title", "text": "Do Not Disturb", "exact": true, "clickable": true},
      "verify": {"absent": {"resourceId": "android:id/search_src_text"}} },
    { "id": "turn_on", "action": "tap", "target": {"text": "Turn on now", "clickable": true},
      "requiresConfirmation": true, "skipIfVerified": true,
      "verify": {"present": {"text": "Turn off now"}} }
  ]
}
```

### Actions

| `action` | Needs | Does |
|---|---|---|
| `open_app` | `arguments.package` (defaults to the workflow's package) | Brings the app to the front and waits for it. |
| `open_intent` | `arguments`: `action` and/or `uri`, optional `package`, `text`, `extras` | Exactly the `open_intent` tool, with its policy and approval. Lands on a screen, or does the whole job (a timer), with no taps. |
| `tap` | `target` | Clicks the node, by handle where the backend has one, otherwise at its current centre. |
| `type_text` | `text`, optional `target`, optional `submit` | Replaces a field's contents. With no `target`, types into whatever holds focus. |
| `scroll` | optional `target`, `arguments.direction` | Scrolls a list. Default direction `forward`. |
| `key` | `arguments.keycode` | `BACK`, `HOME`, `APP_SWITCH` and the rest. `"action":"back"` is shorthand. |
| `wait` | optional `timeoutMs` | Waits for the screen to change and settle. |
| `observe` | — | Reads the screen. A checkpoint step whose whole job is its `verify`. |

**Start with an intent when one reaches the screen.** A deep link or a settings
action lands exactly where the taps would have, faster and with nothing to
break on the way. Keep taps for the part no intent reaches.

### Parameters

Anything that changes between runs — minutes, a name, a message — is a
parameter, not a second workflow. Declare it, use it as `{{name}}`, and the
caller passes `params`:

```json
{
  "id": "timer",
  "version": 1,
  "package": "com.android.deskclock",
  "description": "Start a timer for any number of seconds.",
  "parameters": {
    "seconds": {"type": "integer", "min": 1, "max": 86400, "description": "Timer length in seconds"},
    "label": {"type": "string", "default": "Timer"}
  },
  "steps": [
    { "id": "start", "action": "open_intent", "waitForChange": false,
      "arguments": {"action": "android.intent.action.SET_TIMER",
        "extras": {"android.intent.extra.alarm.LENGTH": "{{seconds}}",
                   "android.intent.extra.alarm.MESSAGE": "{{label}}",
                   "android.intent.extra.alarm.SKIP_UI": true}} }
  ]
}
```

```text
workflow_runner(workflow="timer", params={"seconds": 600})   -> a 10-minute timer
```

- Types: `string`, `integer`, `number`, `boolean`. `min`/`max` bound numbers,
  `maxLength` bounds text, `default` makes a parameter optional.
- A value that is **exactly** `"{{name}}"` keeps its type — the timer above
  gets the integer 600, which is what the clock reads. Inside longer text,
  `"{{label}} ({{seconds}}s)"`, it is spliced in as text.
- The clock app differs by phone (`com.google.android.deskclock`,
  `com.android.deskclock`, …). Run `resolve_intent` with the step's action
  first and put the package it reports in the definition's `package`. Leave
  `package` out of the step's `arguments` so any handler can take it.
- A step that opens no screen (`SKIP_UI`) has nothing to `verify` on screen;
  do not verify the app's package there.
- There is no arithmetic. Name the parameter in the unit the step needs
  (`seconds`), and convert what the user said ("10 minutes") yourself.
- A missing, out-of-range or unknown value is refused before anything runs, with
  the parameter list. `mode="list"` and `mode="describe"` show the parameters.

### Targets

Name **more than one** way to recognise the element. The fields are scored, not
combined with AND, so a workflow that names both the view id and the visible
label still resolves after the app renames the id.

```json
{"resourceId": "com.android.settings:id/switch_widget", "text": "Wireless debugging",
 "className": "Switch", "clickable": true}
```

- `resourceId`, `text`, `contentDescription`, `className`, `package`
- `exact: true` — require the whole field to equal the value, not contain it
- `index: 1` — take the second-best match when several are equally good
- `clickable: true` / `scrollable: true` — only consider nodes that can be
- `scrollIntoView: true` — scroll the screen looking for it before giving up

**Never store coordinates.** There is no way to write one into a definition, and
that is deliberate: they are what made the old `run_workflow` break on any screen
but the one it was recorded on.

### Verification

A step with no `verify` is reported as `verified:false`. Give every step that
changes something a condition, or the run will report success for a tap that
landed on nothing.

- `{"package": "com.android.settings"}` — that app is in front
- `{"present": {"text": "Wireless debugging"}}` — a matching node is on screen
- `{"absent": {"text": "Cancel"}}` — the dialog is gone
- `{"checked": true}` — the step's own target is a switch and it is now on.
  `"checkedOf": {...}` points the check at a different node.
- `{"timeoutMs": 8000}` — how long to keep looking. Default 5000.

### Step flags

- `requiresConfirmation` — stop and ask the user. Use it for anything that
  sends, pays, deletes, changes a permission or opens the phone up.
- `skipIfVerified` — check `verify` **before** acting and skip the step when it
  already holds. Put this on every toggle: re-running "turn it on" on something
  already on turns it off, and it is what makes resuming safe.
- `optional` — a step that may have nothing to do, like a consent dialog that
  does not always appear. A missing target is recorded and skipped, not failed.
- `timeoutMs`, `waitForChange` — per-step timing.

### Where definitions live

One file per workflow, named `<id>.json`, in `{{WORKFLOW_DEFINITIONS_DIR}}`. `workflow_runner(mode="list")` reports any file
that is present but does not parse, with the reason — so a definition you wrote
that does not show up in the list is a file to fix, not a file to write again.

For a sequence you do not want to write a definition for, `save_workflow` still
stores a literal list of tool calls that `run_workflow` replays. It is the older,
weaker mechanism: it resolves nothing at run time and breaks when the screen
moves. Prefer a definition.

## 6. Suggesting workflows from a chat

When the user asks for workflow suggestions (the app's **Suggest workflows**
button sends that request), look back over what you did on the phone in this
chat and propose **at most three**:

- only sequences likely to be repeated — not a one-off lookup,
- each with a name, one line on what it does, and which values become
  parameters ("the contact", "the minutes"),
- an intent for every step one reaches, taps only for the rest.

Check `workflow_runner(mode="list")` first and do not propose what is already
installed. Write a definition only after the user picks one, run it once with
real values, and tell them it is saved and how to ask for it next time.
