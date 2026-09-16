---
name: automations
description: Standing rules — when something happens, and the conditions hold, do this. How to write one with automation_rule, how to pick the cheapest action, what a rule is allowed to read, and why every new rule is dry-run before you tell the user it works. Read this whenever the user asks for something to happen on a schedule, when they arrive somewhere, or when a message arrives.
---

# Standing rules

A workflow is **how** to do something on this phone. A rule is **when** it should
happen, and **who has to be awake** for it. A rule names a workflow; it never
contains one.

```text
automation_rule(mode="list")                       -> every rule, on and off
automation_rule(mode="describe", rule="evening-post")
automation_rule(mode="create", rule={...})
automation_rule(mode="test", rule="evening-post", event={...}, now="...")
automation_rule(mode="run", rule="evening-post")   -> fire it now, for real
automation_rule(mode="enable"|"disable"|"delete", rule="evening-post")
```

## The shape

```json
{"id": "dad-after-seven",
 "description": "Reply to Dad after hours",
 "when": {"type": "notification", "package": "com.whatsapp", "from": "Dad"},
 "if":   [{"type": "time_between", "after": "19:00", "before": "07:00"}],
 "then": [{"type": "agent_turn", "prompt": "Tell {{notification.title}} I can't talk."}]}
```

`when` wakes it. `if` is every test that must hold. `then` is up to four actions.

**when** — `schedule` (`at:"19:00"` with optional `days`, or `everyMinutes`,
minimum 15), `place` (`place` + `enter`/`exit`), `notification` (a named
`package`, optionally `from`), `device_state` (`state` + `is`), `manual`.

**if** — `time_between` (wraps past midnight, so 19:00→07:00 works),
`day_of_week`, `at_place`, `text` (on a field such as `notification.text`),
`device_state`. Any of them takes `"not": true`.

## Pick the cheapest action that does the job

| Action | Needs | Use it for |
|---|---|---|
| `run_workflow` | nothing | a sequence that is the same every time |
| `open_intent` | nothing | one screen, one deep link |
| `notify` | nothing | telling the user something |
| `agent_turn` | a thinking turn | the content is different every time |
| `ask` | the user | a yes/no before something consequential |
| `voice_call` | the user | hands-free, conversational |

This is the decision the user cares most about. A fixed reply — "tell him I
can't talk right now" — is a **workflow** with a parameter, not an
`agent_turn`: it costs nothing, runs at a locked phone, and works the same
every night. Reach for `agent_turn` only when something has to be read,
decided or composed.

**A workflow a rule names can `call` the device capabilities** — contacts, the
calendar, a drafted SMS or email, app info — so a rule like "every weekday at
07:00, tell me my first meeting" is a `run_workflow`, not an `agent_turn`. It
needs no screen, no accessibility and no thinking turn. See the
`device-capabilities` skill for what a `call` step can reach, and `workflows`
for how to write one. Only reach past this when the rule has to *decide*
something.

Mike works out `attention` from the actions, so you cannot write it yourself. A
rule whose attention is `user` is **held** while the phone is locked rather
than fired, and one whose attention is `model` is held when no turn can run.
Say which of the three a new rule is when you describe it.

## What a rule is allowed to read

Only the fields you actually write into an action ever leave the phone. Matching
happens here.

```json
"if":   [{"type": "text", "field": "notification.text", "contains": "dinner"}],
"then": [{"type": "agent_turn", "prompt": "Reply to {{notification.title}}."}]
```

That rule reads the message body to decide and sends only the sender's name.
**Do not interpolate a body you do not need** — `{{notification.text}}` in a
prompt means that text is transmitted every time the rule fires. The `create`
reply lists what the rule will send; repeat that list to the user.

A `notification` trigger must name its package. There is no "every
notification".

## Always dry-run before you say it works

```text
automation_rule(mode="test", rule="dad-after-seven", now="2026-09-15T21:40",
                event={"type":"notification","package":"com.whatsapp","title":"Dad","text":"call me"})
```

Nothing runs and no quota is touched. A rule that would not fire names the
clause that stopped it — `condition_failed`, `cooling_down`, `needs_you`. Test
the case that should fire **and** one that should not, then tell the user in one
sentence what will happen and when.

`mode:"run"` is the other half: it fires the rule **for real**, now. Naming the
rule supplies its trigger, so a rule set for 19:00 can be proved at 11am without
waiting — but its conditions, cooldown and daily limit still apply, and the run
counts against its quota. Use it to show the user their new rule working instead
of asking them to wait until tonight. The reply says it *started*; check
`mode:"describe"` to see whether the count actually went up.

One thing to expect: a rule that drives the screen, fired from inside your own
turn, waits for that turn to finish before it takes the phone. That is normal —
say so rather than reporting it as stuck.

## Things that will bite

- **Dormant.** If `create` comes back `dormant:true`, the rule is saved but this
  phone cannot serve its trigger yet — the permission is missing. Say so, and
  send the user to **Settings > Standing rules**, which has the buttons for
  notification access and exact alarms. Do not report it as live. `place` is
  dormant on every phone today: no geofence source is built.
- **A rule cannot run away.** Each has a cooldown (default 1 minute) and a daily
  limit (default 20). For a busy app set them deliberately.
- **A rule's moment can pass.** A queued turn expires after `validForMinutes`
  (default 30) rather than running late.
- **Your own `notify` cannot trigger your own rule** — Mike's own notifications
  are dropped before anything reads them.
- **The clock is checked, not trusted.** An alarm Android delivers early fires
  nothing, so `at:"19:00"` means that minute.
