# State machine map

Source review: 2026-11-10, on `refactor/state-machines-20261110`, based on
`dev` commit `e2c44025`. This refreshes the earlier Manager-branch inventory
against the actual files in this branch. It does not identify the installed
APK's source or prove phone behavior. Other worktrees remain untouched.

## Decision

Keep each machine with its owner. Make shared decisions explicit at the
boundaries. A single global state enum would multiply unrelated combinations
of chat, voice, transport, permissions, task status and UI state.

Start with user interaction: `RunPhase` describes execution; `RunInteraction`
describes whether a live approval or question needs an answer. Stopping and
terminal phases expose no live gate, even if a payload remains. Approval takes
precedence over question, matching the existing reply routing order. Both
can still be retained as raw data; this is a projection, not a migration.

- UX: the source chat gets only a live child gate; its own Stop takes priority.
- DX: `pendingApproval`, `pendingQuestion`, and `waitingForUser` share one rule.
- AX: child status and updates stop carrying obsolete requests after Stop or
  completion. `unknown` outcomes still require inspection, never replay.

No persisted names, engine event names, tool schemas or RunPhase values change.
Coordinator revocation, execution locking, permission enforcement and timeouts
keep their current owners. The projection does not grant permission to act.

`RunOutcome` makes completed, failed, stopped and unknown outcomes independent
of the wording in `RunState.status`. The coordinator records interruption as
`stopped`; an unconfirmed remote result still wins over a normal ending.
`AgentTaskStatus` gives child chats, computer tasks, cards, the task board and
voice updates one typed vocabulary. Stored receipts remain strings. Computer
tasks still emit `cancelling`, read as `STOPPING`; phone children emit `stopping`.
An unfamiliar stored value stays nonterminal, as before.

An engine approval answer is claimed under the coordinator's lifecycle lock
before launching transport work. A second answer cannot race it. Stop and the
turn/epoch checks still fence dispatch; failed transport releases the claim.

## Discovery and limits

The scan at this revision covers 281 production source files and flags 144
candidate files. Many files share a machine, so that is not a machine count.
Run `python tools/state_machine_inventory.py` for source paths and evidence
line numbers, or add `--summary` for counts. It scans tracked production
Kotlin, Java, Python and JavaScript under `src/main`; it writes nothing.
It finds explicit types, observable state, async lifecycle handles and mutable
flags. These are candidates: comments can match, and a machine spread across
collections, native code or external Android callbacks can escape the scan.
Tests, samples, build tooling and dependency code are outside that scan.

The tables below group related machines by owner. Transition words describe
behavior, not necessarily literal enum values. Entries marked mixed have some
explicit types, with the remaining state encoded in jobs, nullable IDs, flags,
timestamps or collections. This is a source inventory, not an exhaustive proof
of every possible runtime transition or a list of confirmed defects.

## Runs and agent interaction

Paths in this table are under `core/src/main/kotlin/dev/androidagent/core/`.

| Machine | Owner | Hidden state / transition rule |
|---|---|---|
| Typed turn lifecycle (mixed) | `AgentCoordinator.kt` | Starting → work/tools → finish/error; epoch, thread, turn, completion and cleanup jobs must agree. |
| Stop and cleanup | `AgentCoordinator.kt` | Revoke → cancel → interrupt → cleanup → available; local completion is separate from confirmed remote stop. |
| Startup event buffering | `AgentCoordinator.kt` | Events before start reply wait for IDs; stale epochs cannot rejoin. |
| Assistant streaming | `AgentCoordinator.kt` | Deltas → periodic flush → final item; revision and item identity prevent late overwrite. |
| Human gates (mixed) | `Contracts.kt`, `AgentCoordinator.kt` | Open → answer/deny/timeout/stop; raw payload is separate from live `RunInteraction`. |
| Parallel run allocation | `AgentRuns.kt` | Idle slot → reserved/running → reusable; events route to the owner thread. |
| Parent/child presentation | `AgentRuns.kt` | Own gate first, then child gate; delegated activity does not own the parent's composer. |
| Screen ownership | `DeviceLease.kt` | Free → owned → released; some calls borrow per call, screen runs hold to end. |
| Queue and restart | `SessionRunQueue.kt` | Restore held work → explicit resume; new submissions and already-held turns differ. |
| Child receipt and retry | `SessionAgents.kt` | Prepare → dispatch → run/wait/stop → terminal/unknown; stable request and message IDs fence repeats. |
| Child update delivery | `SessionAgentUpdate.kt`, `SessionAgentDelivery.kt` | Accumulate/coalesce → deliver; Stop invalidates the delivery fence. |
| Child task status | `AgentTaskStatus.kt`, `Contracts.kt` | Run phase and typed outcome → receipt status; display text never decides success or cancellation. |
| Engine handoff | `EngineSwitch.kt` | Save old engine context → open/resume target thread → send on selected engine. |
| Approval grants | `SendApproval.kt` | One answer versus contact/app grant; revoked grants must not become implicit approvals. |
| Workflow execution (mixed) | `WorkflowRunner.kt` | Resolve → approve → act → settle → verify; failure may already be committed, resume at exact step. |
| Transfer lifecycle | `FileTransfer.kt`, `CopyFileGateway.kt`, `DeltaTransfer.kt` | Resolve source → copy/delta → verify → publish; overwrite and cancellation are separate decisions. |

## Runtime, devices and connections

Paths name the owning module and source file; the inventory command gives full paths.

| Machine | Owner | Hidden state / transition rule |
|---|---|---|
| Codex process lifecycle (mixed) | `runtime/AndroidRuntimeHost.kt`, `engine-codex/CodexEngine.kt` | Prepare → spawn → initialize → ready → exit; process, reader and initialized flag can diverge. |
| RPC requests and reconnect | `engine-codex/CodexEngine.kt` | Register request → write → reply/error/cancel; process death must settle pending replies. |
| Account/login | `engine-codex/CodexEngine.kt`, `core/CodexAccountVault.kt` | Unknown/signed out → login pending → signed in; account switching is distinct from runtime readiness. |
| Claude process and background cycles | `engine-claude/ClaudeCodeEngine.kt` | Per-chat start/resume → result; a later background cycle is another visible run, fenced by turn ID. |
| Claude runtime update (mixed) | `runtime/ClaudeRuntimeInstaller.kt`, `ClaudeRuntimeUpdates.kt` | Download → verify → install/failed/cancelled; bundled, active and downloading versions differ. |
| CONNECT proxy | `runtime/LocalhostConnectProxy.kt` | Listen → accept/tunnel → close; proxy lifetime follows the runtime. |
| ADB discovery/pair/reconnect (mixed) | `adb/AndroidAdbTransport.kt`, `AdbServiceDiscovery.kt` | Discover → pair/connect → live → disconnect/retry; identity, rejected pairing and reconnect job matter. |
| Accessibility binding | `a11y/AgentAccessibilityService.kt` | Declared enabled differs from actually bound; handles are process local. |
| Observation handles | `a11y/A11yDeviceTools.kt` | Observe revision → export handles → act only on fresh handles; revoked run invalidates access. |
| Device dispatch/fallback | `core/CompositeDeviceToolGateway.kt`, `device-tools/AndroidDeviceTools.kt` | Pick live backend → invoke; fallback only when no side effect could have happened. |
| Unicode input and IME restoration | `device-tools/AndroidDeviceTools.kt`, `app/ime/` | Switch → probe → commit acknowledgement → restore; ambiguous delivery cannot trigger retry/Enter. |
| Connected-app grants and calls | `connect-host/AndroidConnectTransport.kt`, `app/ConnectedAppsController.kt` | Discover/bind → grant/check → invoke → reply or unknown; stable retry ID matters. |
| Connected-app service lifecycle | `connect-sdk/MikeConnectService.kt`, `connect-protocol/Protocol.kt` | Bind → negotiate → invoke → cancel/unbind; an app connection is separate from permission to call. |
| Remote setup and engine routing (mixed) | `remote/RemoteHub.kt`, `RoutingAgentEngine.kt` | Probe/install/sign in → route; a computer connection does not prove its engine ready. |
| SSH and relay recovery | `remote/SshLink.kt`, `CodexRelayProcess.kt` | Connect → active → lost → reconnect/terminal; reply loss is not execution failure. |
| Computer child tasks | `remote/ComputerTasks.kt` | Reserve → prepare → dispatch → status/result; unknown receipt cannot be replaced silently. |
| Computer sign-in and thread refresh | `remote/ComputerClaudeSignIn.kt`, `ThreadRefreshGate.kt` | Pending → success/failure; refresh throttles and cancellation must not erase live state. |
| MCP loopback service | `mcp-loopback/` | Server/session open → tool request → reply → close; request cancellation and run authorization differ. |

## Voice and browser

| Machine | Owner | Hidden state / transition rule |
|---|---|---|
| Voice call (mixed) | `voice/AndroidRealtimeVoiceController.kt` | Starting → listening/speaking → stopping/error; started, SDP and ICE readiness are separate gates. |
| WebRTC media | `voice/WebRtcRealtimeAudioSession.kt` | Offer/answer → connected → audio enabled → closed; mute and playback suppression are independent. |
| Audio routing and focus | `voice/CommunicationAudioRoute.kt`, `BluetoothHeadphones.kt` | Acquire focus/route → armed → blocked/released; Bluetooth connectivity alone is not routing proof. |
| Voice chat/delegation | `app/VoiceConversation.kt` | Enter call → bind chat/thread → deliver child updates → return; call epoch rejects stale callbacks. |
| Voice child announcements | `core/VoiceChildUpdates.kt` | Receive/coalesce → reserve → speak/interrupt/acknowledge; microphone, playback, pause and user turns fence delivery. |
| Assistant entry | `app/assist/AgentVoiceInteractionSessionService.kt` | Invocation → setup → voice/panel → dismissed; Stop during setup must prevent late start. |
| Browser page lifecycle | `web/AndroidBrowserHost.kt`, `BrowserSession.kt` | Create/load → ready → navigation/popup → closed; document changes invalidate DOM references. |
| Browser user handoff | `web/BrowserSession.kt`, `WebToolGateway.kt` | Agent-owned → shown/user-controlled → returned; lifecycle state differs from screen visibility. |
| Browser protocol | `web/CdpClient.kt` | Open → request/reply → disconnect; pending calls need a terminal outcome. |

## Automations and task board

Core paths are under `core/src/main/kotlin/dev/androidagent/core/`.

| Machine | Owner | Hidden state / transition rule |
|---|---|---|
| Rule lifecycle | `AutomationLibrary.kt`, `AutomationEvaluator.kt` | Enabled/disabled → eligible/ineligible → fired; dry run never consumes quota. |
| Trigger ownership | `automations/AutomationHost.kt` | Host started → register/watch → fire → stop; duplicate callbacks and remembered connection state matter. |
| Rule action execution | `AutomationRunner.kt`, `AutomationJournal.kt` | Record firing → ordered actions → success/failure; committed partial work cannot replay automatically. |
| Deferred human output | `AutomationOutputGate.kt`, `AutomationVoiceRequest.kt` | Hold while user unavailable → recheck validity → deliver/expire; a delay is not approval. |
| Task decision (mixed) | `TaskBoardModels.kt`, `TaskBoard.kt`, `app/TaskDesk.kt` | Proposed/snoozed → user decision → execution/review → history; discovery cannot authorize action. |
| Task execution receipt | `TaskBoard.kt`, `TaskDesk.kt` | Approved → start child → track matching run → review/done/failed/stopped/unknown. |
| Background worker lifecycle | `app/TaskBackgroundHost.kt` | Eligible → scheduled → worker running → reschedule/stop; Android lifetime differs from board state. |
| Signal ingestion and ownership | `TaskSignals.kt`, `TaskSourcePolicy.kt` | Receive → filter/deduplicate → assign lane → consume; provenance and exclusion gates apply. |
| Follow-through and briefing delivery | `TaskFollowThrough.kt`, `app/TaskBriefingNotifier.kt` | Due occurrence → pending briefing → notification; retain occurrence identity over restart. |
| Memory and playbook evolution | `TaskMemory.kt`, `TaskPlaybook.kt` | Evidence → attributed note → recall/revision/retire; data is not permission or a user instruction. |

## App presentation and durable data

| Machine | Owner | Hidden state / transition rule |
|---|---|---|
| Session selection and hydration | `app/AgentViewModel.kt`, `workspace/LocalSessionStore.kt` | Select → load messages/remote sync → display; stale responses must not overwrite a newer selection. |
| Composer | `app/ui/ChatComposer.kt` | Draft/attachments/skill → send/steer/answer; stopping, voice and loading gates interact. |
| Chat scrolling | `app/ui/AndroidAgentScreen.kt` | Follow latest → user scroll → detached → return; programmatic scroll must not look like user intent. |
| Modal and navigation state | `app/ui/AgentSettings.kt`, `ChatLibraryDrawer.kt`, `ComputersSheet.kt`, `NewChatPlace.kt` | Route/tab/menu → edit/confirm → close; rememberSaveable lifetime and selected chat keys matter. |
| Setup and permissions | `core/Onboarding.kt`, `SetupChecklist.kt`, `app/RuntimePermissionBroker.kt` | Unknown/check/request → ready/blocked; optional ADB cannot block supported local tools. |
| Usage recovery/keepalive | `core/UsageWindows.kt`, `app/UsageWindowKeeper.kt`, `ui/LimitRecoveryUi.kt` | Limit/reset timer → recover/continue; engine limit differs from transport error. |
| Screen wake and run services | `app/ConversationKeepAwake.kt`, `AgentApplication.kt` | Active foreground work → acquire → release; phone lifetime and remote work lifetime differ. |
| App update/install | `app/update/AppUpdateManager.kt` | Check → available → download → verify → installer → installed/cancelled/failed. |
| Media loading | `app/RemoteMediaLoader.kt`, `ui/InlineMedia.kt` | Unloaded → fetching → ready/error; viewing and copying to phone are distinct. |
| Usage home-screen widget | `app/widget/UsageWidget.kt` | Account snapshot → update/render → tap; stale account data differs from a live quota read. |
| Question notifications | `app/QuestionNotifier.kt` | Live question → source-chat projection → notify/hide/cancel; terminal payloads never reopen a notification. |
| Floating controls and pulse | `overlay/FloatingControlOverlay.kt`, `OverlayPresentation.kt`, `app/ui/AgentPulse.kt` | Visible/hidden/capture-hidden → terminal → release; presentation must follow owner run. |
| Session/message persistence | `workspace/LocalSessionStore.kt` | Append/stream/finalize → persist → restore; restored in-flight work is not new authorization. |

## Other branch boundaries

The earlier inventory also covered shared situation freshness, proactive alerts,
account/context connectors, a WhatsApp Web connector and interactive chat
widgets. Their owner files are absent from this `dev` base. They are not counted
as current implementations here, and this change neither imports nor validates
those separate features.

## Next changes, in order

1. Extend typed outcomes only when another boundary has a demonstrated need.
   Keep old serialized values readable; distinguish failed, cancelled and unknown.
2. Extract lifecycle reducers only where event/epoch invariants are duplicated.
   Cover stale callbacks, Stop and restart before moving execution code.
3. Separate capability readiness, screen ownership and user reachability in shared
   status projections; a single connected/busy flag loses necessary information.
4. Add runtime evidence for pause/resume, reconnect and gate/Stop races on a
   selected physical phone. Unit tests alone cannot establish these behaviors.

These are follow-up design choices, not changes implemented by this patch.
