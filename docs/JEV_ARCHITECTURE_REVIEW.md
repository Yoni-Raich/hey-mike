# Jev engine architecture review

This review records the implementation in checkpoint `019370c` (Astra) and
the follow-up fixes on the Luna branch. The target is one local controller:
Mike gives Jev one complete goal, and the phone-local loop observes, chooses,
acts, verifies and recovers until the goal is done, stopped, blocked or out of
the caller's budget.

The controller does not call Mike between UI actions. Jev is the decision
engine; the existing device gateway remains the executor and safety boundary.

## Implemented design

### One owned task loop

`jev_run_ui_task` is the only public Jev tool. `JevToolGateway` owns one
invocation at a time and runs the complete observe -> decide -> act -> observe
cycle. Step and wall-clock limits are caller budgets, not per-action model
turns. A bounded continuation token preserves the same goal, history, ledger
and repeat detector when a caller budget expires.

The provider has its own request generation. Stop, a new run, or a timeout
invalidates older HTTP responses before they can re-enter the loop.

### Code-owned action space

`JevActionCatalog` creates concrete choices from the current observation. Jev
receives opaque keys and labels; it cannot invent node ids, selectors,
packages, coordinates, text or progress values.

The catalog covers:

- observed taps, including clickable-ancestor targeting;
- semantic scrolling in both axes, with coordinate swipe fallback;
- long press and drag when Accessibility is live;
- semantic range changes and coordinate slider fallback;
- exact text values from `texts`, quoted goal values or bounded goal spans;
- app launch, Back, Home, Recents, notifications and Quick Settings;
- action paging when a screen has more choices than Jev can score at once.

App lookup is goal-directed and paged. Ordinary in-app tasks do not enumerate
every installed package. An app goal searches by label/package and still has a
bounded unfiltered fallback.

The ADB text fallback now targets the observed editable field before typing.
Accessibility still prefers `set_text`, which addresses the field directly
and can verify its value without using the keyboard.

### Immutable observations

Both Accessibility and ADB keep the parsed screen behind the observation id.
Paging re-renders that same snapshot instead of dumping the device again, so
one Jev decision cannot combine nodes from two different screens. Snapshots
are cleared on mutation, revoke and run start. Viewport, windows, package,
node bounds, editable state, range values and supported actions are exposed as
facts in the semantic envelope.

The loop still performs one fresh read immediately before a mutation. If the
fingerprint changed, the old choice is discarded and Jev chooses again.

### Evidence and failure handling

`JevTaskLedger` keeps compact evidence from earlier screens and audits all
requirements before `DONE`. `JevMutationEvidence` retains exact text writes
until a later observation matches the requested value.

`ToolDispatch` separates:

- `NOT_DISPATCHED` — safe to consider another route;
- `ACKNOWLEDGED` — the executor accepted the action, but visible success is
  not established;
- `VERIFIED` — the backend checked the value;
- `UNKNOWN` — the action may have landed and must not be blindly retried.

An unchanged screen after a refusal is not treated as proof that nothing
happened. Unknown mutations end the run with `uncertain_mutation`; definite
pre-dispatch refusals are recorded and suppressed on that screen. Send
approval and visible-control policy stay in the composite gateway.

## Verification completed

With Android SDK configured, this command passed on 2026-09-22:

```text
:core:test
:a11y:testDebugUnitTest
:device-tools:testDebugUnitTest
:app:testDevDebugUnitTest
:app:lintDevDebug
:app:assembleDevDebug
```

The build produced the Dev APK. Unit coverage includes action paging, app
lookup paging, system recovery, semantic slider control, immutable ADB UI
paging, typed failure outcomes, continuation tokens, and the ADB field-focus
fallback. This is source/build evidence, not proof of a real Jev token or an
AndroidGym end-to-end run.

## Known boundaries

1. `done_visible` is still an audited Jev judgment and returns
   `verified:false`. A task-specific deterministic verifier is not inferred
   from a stable screen.
2. Custom canvas and other surfaces with no semantic Accessibility nodes have
   no visual target resolver in this adapter. `screenshot` remains available,
   but the local catalog cannot safely invent a visual coordinate.
3. A caller can still choose a small `maxSteps` or timeout. The controller
   returns a continuation instead of silently restarting; continuation storage
   is intentionally bounded to five segments.
4. Physical device validation with a real Jev token, AndroidGym, keyboard,
   service reconnect and Stop during network I/O is still required.

These boundaries are explicit. They are not reasons to add an outer model
call for every action or to bypass the typed device gateway.
