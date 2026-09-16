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
automation_rule(mode="do", action={...})           -> do one action now, no rule
automation_rule(mode="places")                     -> the named places
automation_rule(mode="save_place", place={...})    -> name where the phone is
automation_rule(mode="forget_place", place="home")
```

## Places

A `place` trigger names a place; it does not say where it is. Until the name is
saved, the rule is dormant. Name one from where the user actually is:

```text
location(operation="current")                      -> latitude, longitude, age_ms
automation_rule(mode="save_place", place={"id":"home","label":"Home",
                                          "latitude":32.0853,"longitude":34.7818})
```

Check `age_ms` before you save: a ten-minute-old fix is where they were, not
where they are standing. If it is stale, say so and try again rather than
naming the wrong spot — a wrong "home" is a rule that fires at the supermarket.

The radius defaults to 150m and cannot go below 80m. That is not caution, it is
the resolution: arriving is worked out from the coarse network position, so a
rule fires within a minute or two of crossing, never at the instant. Do not
promise the user otherwise.

Watching needs location "all the time", which no tool can request. If a `place`
rule reports its trigger dormant, tell the user to turn it on in
Settings > Standing rules; do not try to request it yourself.

## Once is not a rule

`mode="do"` performs a single action right now with nothing saved: no trigger,
no conditions, no cooldown, and no entry in the rules list.

```text
automation_rule(mode="do", action={"type":"notify","text":"The rice is done"})
automation_rule(mode="do", action={"type":"open_intent","action":"android.intent.action.VIEW","uri":"geo:0,0?q=pharmacy"})
```

Use it whenever the user asked for the **thing**, not for it to keep happening.
"Put a reminder on my shade" is `do`. "Remind me every evening" is a rule. Do
not create a rule, run it once and delete it — that leaves a rule behind if
anything goes wrong in the middle, and the user sees it.

Three things `do` refuses, all on purpose:

- **`{{placeholders}}`** — there is no event to fill them from. Write the final
  text.
- **`agent_turn`** — you already are the turn. Do the thing yourself.
- **`requiresApproval`** — that exists so an unattended rule can put a person in
  the loop. The person is in the loop; ask them in the conversation.

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
