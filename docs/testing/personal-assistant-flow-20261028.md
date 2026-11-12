# Manager follow-through — 2026-10-28

## Scope and source

Continues Astra's reviewed 1178 source snapshot after its usage-limit stop.
Worktree: `C:/Users/SHIRA/.codex/worktrees/personal-assistant-flow-20261028`,
branch `feat/personal-assistant-flow`, snapshot commit `c65fe575`.
The original 1178 checkout, other worktrees and phone data are unchanged.
Candidate: code 1179 / `0.14.0-dev.assistant-flow1179`.

## Behavior

- Only actual owner messages in Manager/member chats enter the new feedback path.
  Automated replies and housekeeping messages remain excluded. Capture saves the
  cursor, signals and attributed owner feedback together. The recent feedback
  journal is bounded to 500 entries; durable extracted memory still has no age TTL.
- Owner turns receive bounded relevant memories, recent feedback and owner rules.
  Selection follows current source exclusions and forgotten-note checks. Injected
  context keeps its provenance when it contributes to a card or memory. Retrieval
  counts include injected memory; those counts are not proof of usefulness.
- Taking a task creates a commitment with an owner and next-check time. Actual
  deadlines are separate from proposal expiry. Owners can change the time in chat
  or choose Check tomorrow in the detail page. Checks do not start task execution.
- Scout can attach evidence to decided open work without rewriting its frozen
  plan. Possible completion pauses repeated checks and requests confirmation;
  it does not silently close or cancel approved work.
- Automatic Manager reviews prepare up to three concise evidence-based briefings
  per review, using relevant personal context. Existing limits remain: at most
  four reviews/day, thirty minutes apart, under discovery and account quota.
  Waiting reasons are visible. A review that saves no required brief is failed
  rather than reported as a successful briefing.
- A briefing is an outbox field committed with its source-checked staged result.
  Delivery uses a stable message ID. A crash between chat append and receipt does
  not append/notify twice. Policy and task eligibility are checked again at
  delivery. Notifications are quiet, hidden from the lock screen, and only open
  the Manager chat. Disabled notifications leave the message in that chat; no
  screen takeover, approval answer, sending or task dispatch is involved.
- Coach receives real owner feedback as well as board decisions/outcomes. New
  learned changes must cite a supplied feedback event. Optional `mike-filter`
  JSON blocks run positive and negative examples before save; Scout proposals
  enforce these narrow skip filters. Filters are bound to the current owner
  rules; editing those rules disables old suppression until a new tested filter
  is saved. Free-form guidance has provenance and
  storage checks, not an automatic semantic improvement score. Existing staged
  generation commits, source revocation and compensating undo remain in place.
- Approved work explicitly carries the execution engine/model/thinking level.
  Without an override it uses the Manager's profile. An explicit different engine
  never borrows the other engine's model. Sources exposes execution model/effort.

## Validation record

Scoped validation passed; this document is not phone-runtime proof.
The first build hit the existing pinned Alpine URL's 404. Reused cached musl
archive and copyright only after both matched the existing source SHA-256 pins.
No runtime version, URL pin, signature check, lint baseline or test gate changed.
Initial compilation errors in a suspend IO helper and the Sources layout opt-in
were fixed. Two new fixtures were corrected to supply the source evidence
required by the existing board. No validation rule was weakened.

Logs and candidate artifacts are retained in
`%TEMP%/hey-mike/personal-assistant-flow-20261028/`; central build bucket is
`build/65d8120bbb846202`. Task-local build storage reports `removed:[]`.

Final Gradle command (with the task's existing private Gradle home/storage init):

```powershell
.\gradlew.bat :core:test :workspace:testDebugUnitTest :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest :app:lintDevDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
```

- `BUILD SUCCESSFUL`, 4m 52s, 683 tasks (246 executed, 437 up-to-date).
- Core: 881 tests, zero failures/errors/skips, including 12 follow-through cases.
- Workspace: 57 tests, zero failures/errors, 14 existing skipped cases.
- App JVM: 171 tests, zero failures/errors/skips.
- App lint: zero errors, 21 warnings, three information entries. No baseline added.
- `python -m unittest tools.test_prepare_runtime`: 16 tests, OK. Expected rejection
  messages are the negative integrity fixtures.
- Direct `TaskBoardUiTest` runner on this task's isolated API 35 emulator
  `emulator-5582`: `OK (9 tests)`, 15.847s. This includes the Hebrew follow-up
  detail and the Check tomorrow callback, plus existing board/model/face fixtures.
  Fixture screenshot `follow-up.png` was inspected: follow-up time, completion
  hint, instructions and action buttons are visible without clipping. Its text
  is fixture data. `mike-face.png` is retained with it.
- Local APK: `dev.androidagent.app.dev`, code 1179,
  `0.14.0-dev.assistant-flow1179`, target SDK 35, 340,460,958 bytes.
  SHA-256 `1c5e58b96b64506ab4978d2c0fe1703fb9c1cd9eb6681a6d858172c1d1e5eb24`.
  `apksigner verify`: Android Debug certificate, v2 valid; v3 absent. This is a
  test APK, not a release. `zipalign -c -P 16 4` passed.
  Retained as `hey-mike-assistant-flow1179-dev-debug.apk` in the task temp folder.
- Installed only on the task emulator with `adb -s emulator-5582 install -r`;
  the Android test APK was installed separately and invoked with `am instrument`.
  No connected-test Gradle task, uninstall or physical-phone operation was used.
  Installed version read-back matched 1179. The task emulator was then stopped;
  a new inventory showed only the pre-existing phone and emulator-5566.
- `git diff --check` passed. The feature worktree and named build/temp folders
  are retained for source review and an eventual separately authorized delivery.

## Remaining proof

No hosting-phone installation, real model background pass, sleeping-phone test,
personal notification read or measured suggestion-quality improvement is claimed.
The first follow-up defaults to the next day (or earlier near a real deadline).
Further reminders need a new owner-selected check time. An undelivered briefing
is checked against current task state and source policy; prolonged process
absence can delay its delivery, and relative wording may age while it waits.
No full release gate, push, merge or publication. WhatsApp/mail history sync and
independent project watching are outside this change. Android scheduling and
local engine sign-in remain requirements for actual background work.
