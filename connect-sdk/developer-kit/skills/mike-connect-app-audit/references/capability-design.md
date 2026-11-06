# Capability design for Mike Connect v1

## Choose operations by user outcome

Good capabilities describe a bounded app operation: `list_projects`,
`create_note`, `set_clock_theme`, `create_clock_font`, or `open_project`.
Keep a creation operation distinct from applying a saved result when preview
and reuse matter. Stable IDs let a later call refer to the actual saved object.
Return enough state to verify the outcome without leaking unrelated data.

Avoid mirroring every tap. A `tap_save_button` endpoint depends on navigation
state and tells the model little about the operation's real result. Also avoid
one giant `run_command` endpoint: Mike grants access per named capability, so
that design hides scope and weakens useful user choices.

For each write, inspect the existing operation's transaction boundary, account
ownership, validation, current permission and cancellation. A read-only function
must not schedule work, mark records read, mutate caches with user effects or
write settings without declaring that behavior. Internal nonobservable memoization
is not a reason to misclassify a true read.

## Access and UI

| Mode | Use |
| --- | --- |
| `READ` | Read authorized app state without user-visible effects |
| `WRITE` | Create/edit/apply ordinary content or settings |
| `SENSITIVE` | Delete data, spend money, send communications, or another operation needing explicit approval |
| `Ui.NONE` | Completes without opening a screen |
| `Ui.MAY` | May return an SDK UI handoff |
| `Ui.ALWAYS` | The operation requests an app screen through the SDK handoff |

These are caller-consent descriptors, not substitutes for the app's own
permissions. Describe the action's actual target and consequences in a short
summary. Keep two meaningfully different permission scopes in separate capabilities.

## Bound the data

The service supports up to 64 capabilities. Inputs/outputs use a bounded JSON
Schema profile: primitive types, objects, arrays, enum/const, numeric/length/item
bounds, nullable/composition branches and local definitions. It rejects unknown
assertions (for example `pattern`, `format`, `uniqueItems`) and external refs.
Put constraints not expressible in this profile in app-side validation before
any side effect. Reject unknown object fields with `additionalProperties:false`.

Wire JSON is capped at 256 KiB, but the host accepts less than 19,000 characters
of combined structured/text result before adding its envelope. Aim well below
that bound. Use page limits, cursor IDs and concise summaries. v1 has no general
resource/blob API, remote transport, app-event automation or cross-profile
access. Do not send images, documents, fonts or model weights as large base64
strings and call that a supported resource API. Small bounded geometry or
structured content can be a valid domain input when the app validates it.

## Creative features need domain support

If a user wants Mike to create something new, locate the model and persistence
path that represent it. Examples:

- A font: glyph geometry/import, validation, compiler/loader, preview and a saved
  font ID. The adapter passes a bounded design to the app's implementation.
- A drawing: a document/scene model and renderer, not screenshots of a preset.
- A workout: exercise availability, units, validation and saved plans, not a
  free-form title that the app never uses.

State the gap if any part is missing. The SDK gives authenticated calls and
typed contracts; it does not generate fonts or implement the app's domain.

## Reliability and migration

The SDK reserves a write request before dispatch, caches completed results for
ten minutes and reports unfinished reservations as `outcome_unknown`. It does
not replace the app's transaction or external service's idempotency key. Never
promise exactly-once execution for arbitrary network or multistep work.

Questions restart the handler with answers. Plan them before irreversible work.
Stop cancels cooperative coroutine work; completed effects remain completed.
Consider a bounded start/status pattern for tasks longer than the 60-second
capability timeout limit, using the app's existing job system.

New capabilities and changed schema/access/UI modes need renewed user review
in Mike. Keep stable names for compatible operations. Plan how old app state
and saved selections survive an update; do not clear storage for convenience.
