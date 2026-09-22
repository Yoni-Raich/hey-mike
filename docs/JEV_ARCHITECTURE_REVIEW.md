# Jev engine architecture review

Reviewed against PR #77, commit `2d47543`, with local follow-up changes.
This is a source review. No claim of full-device reliability follows from it.
The user requested no further builds or test runs during this review.

## Required contract

Mike gives one complete goal and exact text values. A phone-local controller
owns observation, Jev decisions, execution, verification and recovery until the
goal is complete, stopped, genuinely blocked or reaches the caller's overall
budget. Jev inference is expected; another Mike turn per action is not.

The composite gateway is a useful executor. Calling it from a Kotlin loop does
not itself invoke Mike. Replacing it with another executor would duplicate the
device backends, cancellation and approval routing without removing a model
round trip. The problem is the controller's state and action coverage.

## Findings and local changes

1. **App discovery silently loses destinations.** `loadApps` requested 50 apps;
   `AndroidCapabilityPlatform.queryApps` sorts before taking that limit. Matching
   the goal afterwards cannot recover a missing app. Local changes add offset
   paging, duplicate-page detection and package-name matching. This establishes
   the defect in the code, not Settings' actual rank on the user's phone.

2. **The controller cannot express common navigation.** Its menu omitted the
   executor's swipe, Recents, notification shade and Quick Settings operations.
   Local changes offer these operations. Gestures use an observed region and
   retain pre-action freshness checks; they do not invent screen dimensions.
   The largest observed region is only a fallback, not a proven display bound.

3. **Aspect ratio is not scroll capability.** A portrait launcher can contain a
   horizontal pager. Local changes offer both axes instead of excluding one.
   A future capability list should identify actual supported directions.

4. **Repeat detection rejects legitimate revisits.** A global screen/action set
   used to stop the run even after successful navigation back to a menu. Local
   changes remember failed or visibly unchanged attempts and remove them before
   the next decision. Successful changed-screen actions remain available on a
   later visit. This still needs a separate bounded cycle detector: alternating
   between two screens is different from a no-op on one screen.

5. **Execution acknowledgement was mistaken for usable evidence.** `set_text`
   can return `success=true` with `verified=false`. Its result was discarded.
   Local changes retain the bounded executor result in history and the Jev
   request. Quoted exact values are preferred over hundreds of arbitrary spans.
   This improves evidence but does not implement a deterministic text verifier.

6. **Task memory loses earlier work.** Requests included only the last eight
   actions. Local changes retain the bounded run history, including outcomes.
   A compact evidence ledger is still needed to keep long runs below the
   provider's 150 KB request ceiling without losing completed requirements.

7. **Observation-to-action grounding is weak.** Jev saw an index and label but
   not resource ids, bounds, package or enabled state. Local changes preserve
   those fields; serialized UI nodes now include their owning package. This
   helps distinguish app controls from keyboard or system controls. It does
   not prove that the reported keyboard failure had a particular cause.

8. **Metrics are ambiguous.** `modelCalls` counts calls to the Jev provider, not
   Mike. Add explicit `jevDecisionCalls` and `orchestratorModelCalls` (zero inside
   this controller), retaining the old field for compatibility. Outer chat
   turns are outside the controller's measurement scope.

## Remaining architectural gaps

### Completion has no requirement ledger

`DONE` plus two matching screen digests only proves the screen stayed still.
It does not prove Notes was submitted, nor preserve evidence from a previous
screen. Keep one goal ledger with pending/satisfied/uncertain requirements and
the observation/action that supports each status. Completion must check every
requirement and distinguish Jev's judgment from deterministic verification.
Do not fix this with a special case for the AndroidGym Submit button.

### Recovery needs typed execution outcomes

`ToolResult.success` cannot distinguish not-dispatched, acknowledged, verified,
no visible progress, and outcome-unknown. Equal screenshots do not prove that
an action had no external side effect. An exception after dispatch must not be
treated as a normal refusal. The executor contract should carry this distinction
and the controller should reconcile unknown results before considering another
mutation. Text replacement and navigation can have different recovery rules
from submitting a form.

### One-call ownership still has short segment limits

The default is 20 steps/60 seconds and the hard ceiling is 50 steps/90 seconds.
Resuming requires an outer tool call. This does not meet the whole-goal contract
for longer tasks. Separate internal decision/settle budgets from a caller-owned
overall deadline. Internal continuation must preserve the same goal and ledger;
Stop must remain responsive. Raising numbers alone does not solve recovery.

### Observation and capabilities are incomplete

The semantic tree is not the screen: custom canvas content may have no useful
nodes. No visual interpretation or coordinate-target discovery exists in the
current Jev adapter. Long press, drag paths and richer gestures are not represented
in the current action menu. Viewport/window/IME identity and available node
actions should be explicit facts from the backend. Full control cannot be
claimed from a menu of labels alone, or by guessing that a large node is the
whole display.

### Stability and failure paths need a single controller policy

Paging repeatedly rereads the whole tree, then actions read again before and
after dispatch. This is costly even with fast Jev inference. Capture one immutable
snapshot, page that snapshot, and validate the selected target immediately before
execution. Preserve the existing freshness protection while reducing repeated
tree reads. Observation failures currently escape from several loop locations;
classify them into retryable observation failure versus unavailable backend.

### Cancellation and parallel calls need stronger ownership

The provider has one active connection and the gateway has one revoked boolean.
A second invocation can compete with the first; a new run can reset the boolean.
Use one invocation owner plus a run generation checked before every dispatch.
Coroutine timeout does not by itself interrupt blocking HttpURLConnection I/O;
disconnect must also be coupled to request cancellation, not just user Stop.

## Code design direction

Split the current large gateway by responsibility:

- `JevTaskController`: one goal, deadline, Stop, lifecycle and terminal status.
- `ObservationSource`: immutable snapshots, windows, viewport and target freshness.
- `ActionCatalog`: backend-supported atomic actions, stable target identities,
  choice budgeting and alternate action pages rather than silently dropped nodes.
- `ActionExecutor`: the existing composite gateway behind typed outcomes.
- `TaskLedger`: requirements, evidence, unknown effects and cycle detection.
- `JevDecisionProvider`: transport only; no device actions or task lifecycle.

The controller asks Jev for the next choice and feeds it observed outcomes. All
recovery stays inside that controller. Mike receives the final result or a
specific need for missing information, rather than coordinating each UI step.

## Review boundary

The local follow-up fixes concrete action-discovery and evidence-loss defects.
It is not a completed redesign and must not be described as full control from
every screen. Completion, typed recovery, whole-goal ownership, custom-rendered
screens and cancellation ownership remain explicit work, not inferred successes.
