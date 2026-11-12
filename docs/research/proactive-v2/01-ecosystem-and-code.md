# Proactive systems in practice: code evidence and choices for Mike

Independent research · 25 October 2026 · Scope: Grok Bot and open source proactive agent systems.

## Main finding

**Commitments and events support reliable follow-through. They do not, by themselves, discover new needs.** That also needs an observation policy: which sources to inspect, which uncertainty is worth reducing, when to inspect again, and how to turn a fact into a useful proposal without turning it into permission.

The three systems reviewed in depth—OpenClaw, Hermes and nanobot—solve different parts of this problem. None of the evidence reviewed proves a general system that correctly infers a user's needs over time.

Their useful mechanisms include run ownership, small task-specific memory, separate execution and delivery state, cautious recovery, intentional silence, and limits on self-maintenance. These mechanisms also have costs. Silence can hide failure. Replaying an incoming event can repeat an action. A completed agent turn can still mean that the user's task failed.

**Proposed direction, not a product change:** compare three options for Mike: commitment follow-through; bounded discovery on top of that base; and a continuous server-side manager for clearly assigned domains. If the product goal is “Mike knows me and suggests new help,” the first option alone is incomplete. The second is my preferred pilot, but it should have to beat the first in a measured comparison. The strongest counterargument and a test that can reject this recommendation are in Section 12.

## 1. Method, evidence limits and fixed versions

I read AGENTS.md, PROGRESS.md, docs/ARCHITECTURE.md, the repo-structure-guard skill, and docs/research/proactive-mike-2026-10-25.md. I treated the earlier report as a list of claims to check.

The research used GitHub read tools for fixed-commit source files, repository trees, releases, code search and issues, plus web search for primary sources. A repository-search timeout did not block direct source reads. The English conversion continues the saved research; it does not restart it.

No third-party project was installed or run. The tests below were **read, not executed**. Failure paths come from source review, attributed issue reports, or clearly marked thought experiments. This is not runtime proof, a model quality benchmark, an API bill measurement, or a phone test.

The following refs were resolved separately from GitHub on the research date. A main-branch snapshot is not a release:

| System | Reviewed main/master snapshot | Separately resolved release | Actual comparison |
|---|---|---|---|
| OpenClaw | [c9db73543a42d3e53e23c8b70c3997bc5d4a0fff](https://github.com/openclaw/openclaw/commit/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff) | [v2026.10.7](https://github.com/openclaw/openclaw/releases/tag/v2026.10.7), commit [157d8c2bc3ea2fa183afdc11088e69690bf8e64e](https://github.com/openclaw/openclaw/commit/157d8c2bc3ea2fa183afdc11088e69690bf8e64e) | Heartbeat docs, queue paths and recovery. The main-branch worker queue file studied here does not exist at the same path in this release. Its full behavior must not be attributed to the release. |
| Hermes | [a5d3c25664a5201cffc38b99760c999099f93597](https://github.com/NousResearch/hermes-agent/commit/a5d3c25664a5201cffc38b99760c999099f93597) | [v0.22.5](https://github.com/NousResearch/hermes-agent/releases/tag/v0.22.5), commit [bf0203a794a07aac3ff764972ab9087baa4e4f4e](https://github.com/NousResearch/hermes-agent/commit/bf0203a794a07aac3ff764972ab9087baa4e4f4e) | executions.py, delivery_queue.py, scheduler_authority.py and suggestions.py have identical contents at these two refs. This is not a claim that the whole project is identical. |
| nanobot | [4701218719eb8a9206f0447006d7914f15590876](https://github.com/HKUDS/nanobot/commit/4701218719eb8a9206f0447006d7914f15590876) | [v0.3.5](https://github.com/HKUDS/nanobot/releases/tag/v0.3.5), commit [1bb712d3488915ca4ed9ccc1a93067ff722f5ab9](https://github.com/HKUDS/nanobot/commit/1bb712d3488915ca4ed9ccc1a93067ff722f5ab9) | gateway_runtime.py and cron/service.py differ. channels/outbox.py is absent from this release. The outbox findings below apply to main. |
| Letta Code, focused breadth review | [6f4e4886caa0934341eea75c306fc0364509218e](https://github.com/letta-ai/letta-code/commit/6f4e4886caa0934341eea75c306fc0364509218e) | [v0.34.24](https://github.com/letta-ai/letta-code/releases/tag/v0.34.24) was found; no file-by-file comparison against the tag | Scheduling, target-conversation admission and reflection. Not a full product audit. |
| Khoj, focused breadth review | master [ae229ca894c0b80ad84664afcfdde523b5e87057](https://github.com/khoj-ai/khoj/commit/ae229ca894c0b80ad84664afcfdde523b5e87057), dated 2 August 2026 | No tag reviewed | The schedule → chat → notification path in helpers.py. |
| Grok Bot | Closed product; reviewed documentation updated 22 October 2026 | No verified public runtime commit found | Product behavior and documented UX contracts only. |

Source links use full commit hashes and line ranges. Service documentation and issue bodies can change even when their URLs stay the same. A closed issue does not establish which release contains a fix.

### What changes from the earlier report

1. Its distinction between commitment follow-through and inferred initiative is useful. But “start with commitments” is a development order, not proof that commitments are enough for the final product goal.
2. OpenClaw really changed heartbeat. Its newer recovery policy matters too: some agent jobs interrupted before delivery can receive one continuation attempt. Receipts do not imply that work is never replayed.
3. Describing nanobot only as a notification gate misses two queues with different recovery rules: incoming events and outgoing results.
4. Letta is no longer adequately described through the old MemGPT/V1 server. Active development moved to another codebase.
5. Grok Bot presents persistent ownership and improving judgment. Public code reviewed here does not expose the mechanism that picks a new need or its next observation time.

## 2. Grok Bot: verified product claims and an unverified runtime

### Identity and lineage

Grok Bot is a separate xAI/Cursor product. Its official announcement is dated 11 August 2026 and describes persistent agents with a shared cloud computer, memory and coordination. Claims that they learn when to interrupt and start useful work before being asked are **vendor claims**. The reviewed sources do not provide an independent measure or an exposed internal implementation. [Official announcement](https://x.ai/news/introducing-grok-bot).

Clawdbot/Moltbot/OpenClaw is a different lineage. Peter Steinberger describes a late-2025 project, followed by Moltbot and then OpenClaw in January 2026. There is a small date mismatch: the announcement says 29 January, while the lore page says 30 January. That does not affect the conclusion: OpenClaw predates Grok Bot and is not established as its fork. [Developer announcement](https://openclaw.ai/blog/introducing-openclaw), [official lore](https://docs.openclaw.ai/start/lore).

Search also found repositories named “Grok Bot.” A name, a README claiming to be official, or an installer is not proof of vendor ownership. None was used as runtime source evidence for this product.

### Documented path: successful work → skill → routine

The docs separate a skill, which describes how to work, from a routine, which defines when a specific Bot runs it. They advise testing a one-off task first, adding failure and approval rules to a skill learned from a demonstration, and defining ownership, time zone, inputs and output. External triggers need their own connection and matching rule. A routine test performs real work and can have side effects. [Skills and routines](https://docs.x.ai/grok-bot/skills-routines-and-automations).

The product-level path is:

`schedule/event → routine-owning Bot → skill and connected inputs → tool/computer work → required approval → conversation result → run history`.

This does not establish durable admission, leases, idempotency keys, pre-action checkpoints, or reconciliation after a lost response. It provides no basis for an exactly-once claim.

Auto Review supports personal and team rules; Ask first takes priority over allow. An approval from unattended work expires after about ten minutes without executing the expired action. The docs explicitly describe review as model-based. Bots share the cloud computer, files and connections; separate Bots are not security boundaries. [Approvals, security, and privacy](https://docs.x.ai/grok-bot/approvals-security-and-privacy).

### Failure, delivery and attention

The troubleshooting guide says cloud work may continue after the app disconnects. Recovery/update should preserve durable work, while reset can lose unsynced work. These are documented contracts, not recovery tests performed here. The primary sources reviewed do not explain enough about duplicate sends or lost acknowledgements to establish their recovery semantics. [Troubleshooting](https://docs.x.ai/grok-bot/troubleshooting).

The UI separates Working, unread results and Needs attention. Notification routing also considers desktop activity, and a request for input can reach the phone later. This is useful attention management; it does not establish that the initiative itself was useful. [Settings and notifications](https://docs.x.ai/grok-bot/settings-and-notifications).

**Ideas worth studying:** clear work ownership, inspectable results, promotion of a proven method into a routine, and separate result/attention states. **Still unknown from public code:** new-need inference, retirement of old goals, and the gap between an external action and its saved receipt.

## 3. OpenClaw: proactive work inside durable execution ownership

### 3.1 Release and main describe different heartbeat models

In release v2026.10.7, heartbeat is a system-owned automation. Configuration remains the desired-state input, and the Gateway maintains its job from that configuration. There is no independent fallback timer when cron is off. Completion events can wake work separately from the periodic cadence. [Release heartbeat, lines 15–29](https://github.com/openclaw/openclaw/blob/157d8c2bc3ea2fa183afdc11088e69690bf8e64e/docs/gateway/heartbeat.md#L15-L29).

In the reviewed main snapshot, Doctor converts monitors into ordinary jobs with schedules, scratch, policies and history. A provisioning receipt preserves deletion as a lasting choice. The conversion removes special duplicate suppression and no-route skipping. This is a change in behavior, not just scheduler cleanup. [Migration guide, lines 31–90](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/docs/gateway/heartbeat.md#L31-L90).

Explicit setup also provisions a default Proactive check. It runs every 30 minutes unless the provider chooses another cadence, uses the main conversation, waits for idle, and targets the owner. Missing scratch can still run; explicitly empty scratch is configured to skip. “Always on” here can mean a service that occasionally starts a turn, not a model thinking continuously. [default-proactive-job.ts:19–52](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/default-proactive-job.ts#L19-L52).

### 3.2 A concrete source path

Scenario: a periodic job is authorized to inspect blocked work and report only a meaningful change.

| Stage | Reviewed implementation | Meaning and limit |
|---|---|---|
| Event source | A schedule and time slot bound to a receipt | A chance to run, not evidence that action is useful |
| First admission | Queue worker checks existence, queued/running state, enabled state, configRevision and slot | Stale or duplicate requests can be rejected before launch |
| Claim and queue | State transaction saves the request; draining respects capacity, pause and ownership | Durable acceptance is not execution |
| Authority admission | Principal, parent session, owner, conversation generation, worktree and authority are checked | A prompt does not grant access to a path |
| Context | Job instruction, time, previous meaningful result, bounded scratch and its revision | Task-specific continuity; scratch is not a scheduler |
| Initiative decision | A normal agent turn; guidance asks for useful work and silence if unchanged | Relevance still depends on the model |
| Execution | Main-session or detached execution through executeJobCore | Background work has an execution owner |
| Verification | Turn result, errors and record_result where available | No general verifier of business-task correctness |
| Delivery | Work outcome and delivery outcome are separate; silence/no_change can suppress delivery | Generated text is not proof of recipient receipt |
| State update | Receipt, history and job state finish in one transaction | Local atomicity does not include an external server that already accepted an action |

Path evidence: [run-queue.worker.ts:126–295](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-queue.worker.ts#L126-L295), [drain:378–489](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-queue.worker.ts#L378-L489), [execution-context-admission.ts:205–365](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/execution-context-admission.ts#L205-L365), [timer-execution.ts:43–355](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/service/timer-execution.ts#L43-L355), [run-prompt.ts:19–106](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/run-prompt.ts#L19-L106), [finalization:25–170](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-finalization.worker.ts#L25-L170).

The queue separates a slot from an attempt. A receipt search by job and slot prevents a second request from claiming the same occurrence, while a continuation can have its own fence. Before draining, the code checks event validity, owner availability and job eligibility again. Queued payload edits can take effect; active runs retain their committed snapshot. [run-queue.worker.ts:157–227](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-queue.worker.ts#L157-L227), [lines 391–489](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-queue.worker.ts#L391-L489).

### 3.3 Where a new initiative comes from

The default prompt tells the agent to inspect scratch and relevant session context, while avoiding inferred or repeated old tasks from earlier conversations. This is an assigned observation area, not an unlimited new-goal engine. The model chooses its next tool within the turn; the scheduler allocates the turn. [default-proactive-job.ts:19–48](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/default-proactive-job.ts#L19-L48).

The clearest example of proposing a need that was not already a job is promoting repeated work into an automation. The docs explicitly say there is **no repetition-detection engine and no new history store**. The model notices repetition in the conversation, lists existing jobs, and proposes a schedule. Confirmation, job creation and an immediate test form a documented workflow. Recognition itself is model guidance, not a code-enforced behavioral classifier. [how-it-works.md:63–107](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/docs/automation/cron-jobs/how-it-works.md#L63-L107).

This is also a counterpoint to over-design: a general model can propose new help without a separate inference service. But the usefulness, authority and missed-opportunity rate still need evaluation. Cron supplies none of those by itself.

### 3.4 Crash, disconnect and duplicate paths

**Duplicate before launch:** two wakes for the same job/slot should not create two executions. Receipt and job checks share the state transaction. The source test covers deduplication and capacity; no live race was run here. [run-queue.worker.test.ts:73–101](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-queue.worker.test.ts#L73-L101).

**Crash after a saved result:** recovery looks for a terminal result with the exact identity and restores state without rerunning payload or delivery. Without a saved result, it records interrupted work and possibly unknown delivery. There is a deliberate exception: an unchanged, time-scheduled agent-turn job whose delivery did not start can receive one continuation attempt. Its prompt asks it to inspect previous work before acting. This remains reconciliation limited by the model and available tools. Command/script and event/process-exit jobs are not automatically replayed by that path. [run-recovery.kernel.ts:175–255](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-recovery.kernel.ts#L175-L255), [recovery policy:44–58](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/docs/automation/cron-jobs/how-it-works.md#L44-L58), [continuation guidance:28–32](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/run-prompt.ts#L28-L32).

**Thought experiment:** the agent creates an external ticket, then crashes before saving the tool response or sending its summary. deliveryNotStarted can be true even though the ticket exists. A scheduler receipt alone cannot prevent a second ticket. Recovery needs a stable target-side key or a lookup, or must retain unknown state.

**Duplicate completion:** finalization conditionally writes the terminal receipt and commits history/job state with it. A repeated completion should not duplicate local terminal state. [finalization:73–91](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-finalization.worker.ts#L73-L91), [regression test:26–110](https://github.com/openclaw/openclaw/blob/c9db73543a42d3e53e23c8b70c3997bc5d4a0fff/src/cron/store/run-finalization.worker.test.ts#L26-L110).

### 3.5 Developer and incident lessons

Issue [#175645](https://github.com/openclaw/openclaw/issues/175645) reports that, in 2026.10.3, a valid NO_REPLY after child completion was classified as empty_response. That triggered fallback costs and another completion announcement. This is the reporter's observation and source analysis, not our reproduction or proof that the reviewed main still has the bug. Lesson: silence needs an explicit result type.

Issue [#159858](https://github.com/openclaw/openclaw/issues/159858) reports about 650 MB of steady-state worker-isolate heap on a small host running 2026.9.6. The author explicitly says it is not a leak report. The number must not be transferred to Android or the current version. It does show why an always-on service has resident infrastructure costs even without inference.

The official advisory [GHSA-3mq7-q27j-mq7q](https://github.com/openclaw/openclaw/security/advisories/GHSA-3mq7-q27j-mq7q) describes reusable exec approvals bound to arguments but not the working directory, first fixed in 2026.8.1. The lesson is to bind existing permission to the reviewed resource and environment. A path in a prompt is not that binding.

## 4. Hermes: persistent identity, canonical admission and separate delivery

### 4.1 Bot Mode does not mean continuous thinking

Bot Mode wraps a profile with its own configuration, memory, skills, credentials and history, plus a canonical Bot Chat. Cron runs and ordinary side sessions remain separate. Durable identity survives a shorter working context; keeping one transcript open is not the whole mechanism. [Bot Mode documentation](https://hermes-agent.nousresearch.com/docs/user-guide/bot-mode).

The deep path below is agent-backed cron. The four core files checked for release parity have identical contents in v0.22.5. Other main-branch details are not a blanket release claim.

### 4.2 Concrete path: scheduled observation → work → delivery

| Stage | Concrete mechanism | Meaning |
|---|---|---|
| Event/time | Tick loads due jobs | An idle tick does not require a model |
| Admission | File lock, Stop/dispatch gate, store-health check, recurring schedule advance, pool and claims | A live scheduler does not mean a job was admitted |
| Ownership | request_id and admission journal before submit to the canonical session owner | A worker does not start a competing conversation writer |
| Context | Job prompt, skill, script output, context_from, notepad, identity and memory | Selected continuity, not every past chat |
| Decision | Script wakeAgent=false gate; no_agent skips inference; otherwise AIAgent | Code can decide whether inference is needed |
| Permission | Toolset restrictions, default scheduling-recursion block, dangerous-command cron_mode | Unattended work is not automatic permission |
| Execution/verification | Fresh agent session and iteration limit; exceptions, timeout and declared task failure | Completed still does not mean business-task verified |
| Output | Saved result and fire identity, then delivery handoff | Delivery failure need not repeat the research |
| Delivery | SQLite pending → delivering → terminal queue; execution_id deduplication | Local send claim is not a recipient receipt |
| State | Execution ledger, delivery outcome, next run and job metadata | Execution and delivery have different states |

Path evidence: [scheduler_tick.py:92–205](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_tick.py#L92-L205), [scheduler_authority.py:55–147](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_authority.py#L55-L147), [scheduler_prompt.py:129–190](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_prompt.py#L129-L190), [prompt construction:283–355](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_prompt.py#L283-L355), [agent construction:2508–2542](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler.py#L2508-L2542), [delivery_queue.py:165–354](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/delivery_queue.py#L165-L354).

The notepad is quota-limited, job-private KV memory injected into the prompt. It can hold a watermark such as the last inspected commit. context_from can reuse the latest usable output of the same job or upstream jobs. It skips blank/silent answers and clips context. It is not a dependency graph that waits for all upstream jobs in the same tick. [notepad.py:68–165](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/notepad.py#L68-L165), [scheduler_prompt.py:129–190](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_prompt.py#L129-L190).

Verification remains limited. The hint defines SILENT and CRON_FAILURE so an agent can report no change or task failure even after a technically complete turn. This improves on treating every model response as success, but still depends on correct model use of the protocol. [scheduler_prompt.py:260–279](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_prompt.py#L260-L279).

### 4.3 Self-planning and learning: code versus prompt

By default, the scheduler removes cronjob, messaging and clarify toolsets from cron agents. allow_agent_scheduling opts into job management; the user's general disabled toolsets still constrain the result. The recursion guard is code, not only a sentence asking the model not to schedule again. [scheduler.py:414–435](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler.py#L414-L435).

The model can choose its next tool inside a turn. Choosing a future adaptive turn requires a schedule update or one-shot through an available tool. A normal scheduler does not independently infer that a network failure deserves another observation in seven minutes.

The suggestions store records source, dedup_key, a bounded backlog, and pending/accepted/dismissed state. A blueprint can produce a pending suggestion; acceptance creates a normal job. This separates proposing a routine from activating it. [suggestions.py:93–129](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/suggestions.py#L93-L129), [acceptance:167–191](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/suggestions.py#L167-L191), [blueprints.py:148–165](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/tools/blueprints.py#L148-L165).

**A schema is not capability proof.** Suggestions accept sources named usage and integration, but part of the docs describes them as later additions. The source review and searches did not establish a complete producer that infers usage patterns and feeds such suggestions end to end. An enum or a test inserting source=usage does not prove that loop works. [creating-skills.md:374–398](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/website/docs/developer-guide/creating-skills.md#L374-L398).

By contrast, background_review is a concrete learning mechanism. A fork inspects context and updates skills or memory. Its prompt teaches what to learn from corrections and warns against preserving temporary failures as lasting tool bans. A dispatch whitelist restricts writes to maintenance tools; read-before-write guards require a fresh skill read. This improves future work. It does not establish discovery of new external needs. [background_review.py:365–505](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/agent/background_review.py#L365-L505), [dispatch whitelist:1092–1132](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/agent/background_review.py#L1092-L1132).

One comment/code mismatch matters: scheduler_prompt describes cron as auto-approving, but approval_context actually reads cron_mode and defaults to deny. I use the executed policy code, not the older comment, as evidence. [approval_context.py:347–359](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/tools/approval_context.py#L347-L359).

### 4.4 Failure paths that must remain separate

**Submit accepted, acknowledgement lost:** the request ID is saved before submit. When the owner can resolve it, recover returns queued/started/terminal and observation continues through the same receipt. An unresolved result raises CronExecutionUnknown; reconciliation does not create another admission. Losing the connection is not permission to start another worker. [scheduler_authority.py:61–145](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_authority.py#L61-L145), [reconciliation:149–230](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_authority.py#L149-L230).

**Execution owner disappears:** the ledger marks abandoned attempts unknown without scheduling a replay. It checks process identity and progress, not just PID. A path can also release a wedged live owner's claim without killing that process. Therefore unknown does not prove that old code is physically unable to continue; effect-time fencing still matters. [executions.py:426–499](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/executions.py#L426-L499).

**Gateway dies after delivery starts:** an atomic claim changes pending to delivering before transport. Recovery marks dead-owner sends unknown and does not send again. It also avoids resending after a same-process failure to persist the send outcome. Tombstones retain deduplication after terminal-row pruning. The tradeoff is possible loss of a message that was never actually sent: avoiding duplicates is preferred over guaranteed delivery. [delivery_queue.py:165–203](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/delivery_queue.py#L165-L203), [lines 228–325](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/delivery_queue.py#L228-L325).

Tests read, not run, cover lost acknowledgements, dead owners, send-time timeouts and no-resend recovery: [test_canonical_cron.py:59–80](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/tests/cron/test_canonical_cron.py#L59-L80), [test_execution_ledger.py:249–298](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/tests/cron/test_execution_ledger.py#L249-L298), [test_delivery_queue.py:130–237](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/tests/cron/test_delivery_queue.py#L130-L237).

### 4.5 Project criticism and developer lessons

Issue [#83484](https://github.com/NousResearch/hermes-agent/issues/83484) reports a follow-up scheduled into a stateless HTTP API session that could not accept push messages. Hundreds of log errors followed instead of a useful terminal failure. This is historical evidence, not proof that current code behaves identically. Routability belongs in admission; a past conversation is not necessarily a current delivery route.

Issue [#144455](https://github.com/NousResearch/hermes-agent/issues/144455), opened on the research date, reports v0.22.5 executing cron jobs for a secondary profile but failing delivery through the primary profile's Telegram connection. It directly illustrates completed execution versus delivered output. It was not reproduced or resolved here.

Issue [#142461](https://github.com/NousResearch/hermes-agent/issues/142461) reports child-completion wakes repeatedly refused because Discord thread route identity included mismatched metadata. A durable queue can remain durably stuck. It needs a failure classification and repair path, not endless retry.

Learning has a visible cost in the source too: cron sets skip_background_review=True with a developer comment estimating around 30K tokens per review event. This is not a universal benchmark. It explains why that developer avoided nesting review into each cron run. [scheduler.py:2533–2539](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler.py#L2533-L2539).

## 5. nanobot: separate observation, notification and memory maintenance

### 5.1 The old small HeartbeatService is not the current path

In the reviewed main, heartbeat is registered as a system cron job. gateway_runtime reads HEARTBEAT.md and invokes AgentLoop. The evaluator is another model call after the work. The older nanobot/heartbeat/service.py path was unavailable at this SHA, so it was not treated as current implementation. [System job registration:936–964](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L936-L964), [heartbeat path:720–791](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L720-L791).

### 5.2 Concrete heartbeat path

1. Cron wakes the system job. Local code reads the file. Missing content or no task lines under Active Tasks exits without an agent call. This is a cheap structural filter, not semantic relevance reasoning. [gateway_runtime.py:254–276](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L254-L276).
2. A target is selected from sessions and enabled channels, excluding archived, CLI and system targets. Without a routable conversation, this path does not run the heartbeat agent. It captures the target generation before work. [Target selection:279–306](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L279-L306), [generation:731–738](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L731-L738).
3. The agent receives the checklist in the heartbeat session. Its message tool is suppressed and progress is silent so output cannot bypass the evaluator through that tool. This guards a specific delivery path; it does not prove that all other possible tool side effects are disabled. [Lines 740–761](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L740-L761).
4. The evaluator receives task context and output, and returns should_notify. No valid tool call, a non-boolean value, or an exception returns the default false. [evaluator.py:84–144](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/utils/evaluator.py#L84-L144).
5. If it chooses notification, deliver_to_channel receives the saved session key and generation. Otherwise the result still returns to cron. Cron's ok describes the callback, not necessarily delivery. [gateway_runtime.py:768–791](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L768-L791), [cron/service.py:616–675](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cron/service.py#L616-L675).

Ordinary scheduled jobs are bound to a conversation. bound_runner saves a queued record, submits an independent turn that defers until idle, waits for completion, then saves its response. The coordinator maintains waiters and completion/error paths. Queued is not finished. [bound_runner.py:64–146](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cron/bound_runner.py#L64-L146), [automation_turns.py:44–107](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/agent/automation_turns.py#L44-L107).

### 5.3 Initiative, memory and permission are different mechanisms

Heartbeat reads an existing task list. The evaluator decides whether to speak after work; it does not decide whether the work was worth its inference cost. The agent chooses its next tool with ordinary memory, skills and history. No separate planner that infers a new personal need and optimizes the next observation time was established in this path.

Dream has a different job: build a history batch from a cursor, run with restricted memory tools, inspect completion, advance the cursor only after completion, and inspect a real file diff. A successful run can make no memory change. A diff proves changed bytes, not correct memories. [gateway_runtime.py:661–717](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cli/gateway_runtime.py#L661-L717), [MemoryStore](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/agent/memory.py#L58-L89).

The normal turn path checks a required session and generation, then removes session-disabled tools before execution. It resolves the effective workspace from message/session scope and binds that scope to the request. This provides concrete code-level constraints around session-bound automated work; the notification evaluator is a later, separate decision. [loop.py:2091–2114](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/agent/loop.py#L2091-L2114), [workspace scope:1248–1278](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/agent/loop.py#L1248-L1278).

The notification evaluator is not an authority gate because it runs **after** the work. Suppressing the message tool is not permission for an external write. All nanobot authorization paths were not audited here; no general least-privilege guarantee is claimed.

### 5.4 Recovery paths have different delivery semantics

**Schedule missed while Gateway is down:** _resume_next_run preserves a valid future interval, but recomputes an expired one. A past one-shot has no next run; cron chooses a future matching time. Missed occurrences are skipped rather than replayed as a backlog. [cron/service.py:45–91](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cron/service.py#L45-L91), [reliability documentation](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/docs/automations.md#delivery-and-reliability).

**Incoming trigger claimed and interrupted:** local_store atomically writes inbox files, moves them to processing on claim, and requeues processing files after restart. This is at-least-once. If the agent already performed an action before interruption, the event can reach it again. A trigger ID is not an external action's idempotency key. [local_store.py:161–239](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/triggers/local_store.py#L161-L239), [interruption and terminal-failure tests:453–590](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/tests/triggers/test_local_triggers.py#L453-L590).

**Outgoing result interrupted:** the outbox saves a message before queue acceptance and claims it as sending. On restart, sending becomes unknown and its payload is cleared; there is no blind resend. A failure known by the adapter to have sent nothing can return to pending. Limits include 256 pending/sending messages, 1 MiB per serialized message, a one-hour TTL and a 30-second retry delay. These are project choices, not automatic defaults for Mike. [outbox.py:21–90](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/channels/outbox.py#L21-L90), [claim and finish:121–143](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/channels/outbox.py#L121-L143), [documented delivery contract](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/docs/automations.md#scheduled-result-delivery).

**Late persistence has limits:** _run_timer first tries to save a dirty in-memory snapshot before reloading or running more work, to avoid using stale disk state after external effects. That protection depends on the process still holding the snapshot. It is not durable proof if the process crashes before the write. Status and schedule are updated after the callback; the external effect is not in that transaction. [service.py:563–614](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cron/service.py#L563-L614), [completion:616–675](https://github.com/HKUDS/nanobot/blob/4701218719eb8a9206f0447006d7914f15590876/nanobot/cron/service.py#L616-L675).

### 5.5 Silence has failure modes too

Issue [#6029](https://github.com/HKUDS/nanobot/issues/6029) reports v0.3.5 leaking “Compressing context” notices during work meant to stay quiet. Issue [#6106](https://github.com/HKUDS/nanobot/issues/6106) reports compaction of an empty session and repeated compaction overnight. Both were closed when reviewed; the fixing release was not established. They show why suppressing only the final answer is insufficient, and why maintenance can create its own work.

Issue [#6410](https://github.com/HKUDS/nanobot/issues/6410) adds a deeper lesson: after an earlier fix serialized compaction with active turns, idle eligibility still needed rechecking after the lock was acquired. A queue can wait correctly and then act on stale conditions.

The main-branch evaluator uses agent.model. It is not necessarily cheap because the call is described as lightweight. Issue [#4431](https://github.com/HKUDS/nanobot/issues/4431) requests a heartbeat-specific override, but describes an older service design. The direct current evidence is model=agent.model at lines 772–778. Silence can cost an extra model decision after the work itself.

## 6. Related systems: memory improvement is not the same as new-need discovery

### 6.1 Letta and Letta Code: review the maintained implementation

The reviewed letta-ai/letta main is a landing repository. Its README directs active work to letta-ai/letta-code and keeps the retired V1 server on archive. Repository metadata also marks letta-ai/lettabot archived and describes it as replaced by Letta Code channels/schedules. Treating the old server or a standalone historical bot as the current product would distort this comparison. [Pinned landing README:5–38](https://github.com/letta-ai/letta/blob/8816d3a8516893bfdc0fa14bfb1235226012e295/README.md#L5-L38), [lettabot repository](https://github.com/letta-ai/lettabot).

Two mechanisms in current Letta Code are relevant:

- **Scheduled work enters a conversation queue.** fireCronTask resolves a target conversation, obtains its runtime, rechecks that an automatic task is still active immediately before enqueueing, and reports queue_full or runtime_unavailable explicitly. Its run log can say status=ok together with outcome=queued. Reading only the first field would turn acceptance into false completion. A manual run records its own occurrence without consuming the automatic one. [scheduler.ts:334–460](https://github.com/letta-ai/letta-code/blob/6f4e4886caa0934341eea75c306fc0364509218e/src/cron/scheduler.ts#L334-L460).
- **Reflection is a separately triggered memory operation.** Post-turn code launches reflection after a configured compaction event or step count, or disables it. The reflection prompt asks for cross-session patterns, correction of contradictions, reusable procedures and preferences, while excluding temporary task state. The trigger is code; deciding what the evidence means is a model task. This is a concrete route to improved continuity, not proof that a new external task will be inferred. [post-turn-reflection.ts:18–80](https://github.com/letta-ai/letta-code/blob/6f4e4886caa0934341eea75c306fc0364509218e/src/cli/helpers/post-turn-reflection.ts#L18-L80), [reflection-v2.md:14–37](https://github.com/letta-ai/letta-code/blob/6f4e4886caa0934341eea75c306fc0364509218e/src/agent/subagents/builtin/reflection-v2.md#L14-L37).

The scheduler also marks an active one-shot as missed when it is more than five minutes late. That favors explicit missed-work reporting over running arbitrarily stale work. The five-minute threshold is a product policy; it should not be copied into Mike without considering the task. [scheduler.ts:463–490](https://github.com/letta-ai/letta-code/blob/6f4e4886caa0934341eea75c306fc0364509218e/src/cron/scheduler.ts#L463-L490).

Limit: this was a focused source review. It did not trace all downstream execution receipts, permission paths, cloud scheduling or send recovery. Letta is not ranked as more or less crash-safe than the three deep reviews on that incomplete evidence. The source file named listener/heartbeat.ts inspected during discovery was a transport ping/pong watchdog, illustrating another naming trap: transport heartbeat is not proactive agent reasoning.

### 6.2 Khoj: a useful opposite choice for notification failure

Khoj documents scheduled queries, local time zones and email results. Self-hosted email needs authentication and Resend configuration. [Official automations guide](https://docs.khoj.dev/features/automations/).

The reviewed code builds a CronTrigger with jitter and a user/query/schedule-derived identity, then calls scheduled_chat through a process lock. scheduled_chat checks recent run time, reconstructs an authenticated chat API request and the conversation ID, obtains the result, calls should_notify, and formats/sends the output. A six-hour recent-run guard is present in this path. It is a coarse suppression policy, not proof of slot-based exactly-once execution. [helpers.py:2524–2695](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/routers/helpers.py#L2524-L2695).

The strongest contrast with nanobot is the evaluator fallback. Khoj returns false for empty inputs, but **returns true on a notification-decision exception**. nanobot's heartbeat defaults false. Both systems therefore make a product choice about failure, not just a technical JSON choice. [Khoj should_notify:2489–2521](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/routers/helpers.py#L2489-L2521).

For Mike, neither default fits every case. Suppress-on-error is suitable for optional suggestions but can hide an explicitly requested alert. Notify-on-error preserves visibility but can turn a model outage into notification spam. A separate operational-error state, quiet aggregation and task-specific delivery requirements are a better design candidate.

This source review did not establish a durable outgoing-delivery receipt path for Khoj. It also did not establish how its whole scheduler recovers after process death. A call to send_task_email is not a delivery guarantee. No such guarantee is inferred.

### 6.3 Newer work around the field: OpenClaw Enterprise

The 29 September announcement presents OCE as a separate control-plane effort with tenant boundaries, sandboxing, permissions and auditability. It describes the project at announcement time as pre-1.0 and suitable for internal pilots. That is evidence of the developers' priorities and stated maturity, not a current release audit or proof of stronger proactive judgment. [Official OCE announcement](https://openclaw.ai/blog/openclaw-enterprise).

The useful lesson is that persistent agents bring an operations problem alongside a reasoning problem. Ownership, identity, authority and audit need a stable control plane. OCE was not given a deep source audit here, and no code or runtime guarantees from it are claimed. The existing evidence is enough for the decision in this report; expanding the project list would add breadth without resolving the central uncertainty.

## 7. Mechanism comparison

This table summarizes the reviewed paths, not every feature in each product. “Not established” means the review did not prove it, not that the project cannot do it.

| Question | Grok Bot | OpenClaw main | Hermes | nanobot main | Letta Code / Khoj breadth |
|---|---|---|---|---|---|
| What starts work? | Documented routines, triggers and handoffs | Schedules, events, completion follow-ups, default proactive check | Cron, script gates and canonical session admission in reviewed path | Scheduled jobs, local triggers, system heartbeat/Dream | Letta schedule → queue; Khoj scheduled chat |
| Where is initiative decided? | Closed implementation | Model inside an allocated turn | Script/model split; agent chooses its work | Checklist agent, then a separate notification evaluator | Letta reflection model; Khoj post-run notification model |
| Is new-need discovery proved? | Vendor claim only | Conversation-based repetition proposal, guided by prompt | Maintenance learning is concrete; complete usage-pattern suggestion producer not established | Existing checklist and memory consolidation; no general need-discovery planner established | Reflection and query scheduling, not general discovery proof |
| What persists? | Documented memory/files/history | Job state, receipts, history, revisioned scratch, session context | Profile identity, execution ledger, admission journal, notepad, outputs, delivery queue | Jobs, sessions, trigger files, Dream cursor, main-branch outbox | Letta memory/reflection and task state; Khoj schedule/conversation linkage |
| Who chooses the next wake? | Unknown internals | Schedule; agent tool can manage jobs within authority | Schedule; agent scheduling is opt-in | Normal next-run computation; existing heartbeat interval | Schedule or configured reflection trigger |
| What happens after an uncertain send? | Not publicly established here | Outcome/recovery checks; possible delivery is not blindly replayed in documented path | Unknown, no automatic resend after claimed send | Unknown, no automatic resend from sending state | Not traced deeply enough |
| Can an incoming event repeat? | Not established | Receipt/slot/event checks; recovery depends on occurrence type | Original request identity is recovered instead of new admission | Yes: interrupted local-trigger processing is requeued | Not established |
| How is silence represented? | Product attention/notification states | NO_REPLY and structured automation result | SILENT plus saved output; task failure can be separate | should_notify=false; evaluator failure also defaults false | Khoj evaluator error defaults true |
| Is success externally verified? | No code evidence | Not generally; needs task/tool evidence | Not generally; completed turn and declared failure protocol | Not generally; callback ok can coexist with quiet/no delivery | Not established as a general verifier |
| Biggest lesson for Mike | Own work and make results inspectable | Bind execution to current authority and a durable occurrence | Keep execution, admission and delivery identities separate | Separate work from speaking; specify recovery per boundary | Memory reflection and scheduling are distinct loops |

The detailed source links supporting each column are in Sections 2–6. No aggregate reliability score is assigned: source review and historical issues are not a common benchmark.

### Observed sequence: Hermes agent cron with an uncertain delivery

The diagram abstracts the source path described in Section 4. It shows two different lost-response boundaries. It is not a trace captured from a running system.

```mermaid
sequenceDiagram
    participant T as Scheduler tick
    participant J as Admission journal
    participant O as Canonical session owner
    participant A as Agent and tools
    participant Q as Durable delivery queue
    participant X as External destination

    T->>T: Due, dispatch, store-health and claim checks
    T->>J: Save job ID and stable request ID
    T->>O: Submit original request ID
    alt Submit reply is lost
        T->>O: Recover using the same identity
        O-->>T: Existing receipt or unknown
        Note over T,O: Unknown does not authorize a new execution
    else Receipt returned
        O-->>T: Admitted identity
    end
    O->>A: Run with job context, memory and allowed tools
    A-->>O: Terminal work result
    O-->>T: Observe result through receipt
    T->>Q: Persist result handoff keyed by execution
    Q->>Q: Claim pending as delivering
    Q->>X: Send saved output
    alt Send outcome is saved
        X-->>Q: Adapter result
        Q->>Q: Record delivered or failure
    else Owner dies before outcome is saved
        Q->>Q: Recovery marks unknown
        Note over Q,X: No automatic resend; execution is not repeated
    end
```

Supporting code: [canonical submit/recover](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/scheduler_authority.py#L55-L147), [queue claim and recovery](https://github.com/NousResearch/hermes-agent/blob/a5d3c25664a5201cffc38b99760c999099f93597/cron/delivery_queue.py#L228-L325). “Delivered” here is a transport/adapter result, not a human read receipt.

## 8. Are commitments plus events enough?

They answer **what is already owed** and **what changed**. They do not automatically answer **what should be observed**, **what new help is worth considering**, or **what the user would prefer left alone**.

Consider three different situations:

| Situation | What commitments/events can do | What else is needed |
|---|---|---|
| “Follow this approved computer task until it finishes.” A child later completes. | Link completion to existing work, verify the artifact, continue within permission | Reliable receipt and task-specific completion checks; no new-need inference required |
| A user repeatedly asks for a morning research brief but never asks for automation. | Preserve conversations and completed results | Recognize repetition, propose a routine, check for an existing routine, and retain rejection; OpenClaw does this mainly through model guidance |
| A calendar meeting moves earlier while a relevant preparation file is still stale, with no explicit preparation task | Capture changed time if that source is connected | An allowed observation scope, a hypothesis that preparation matters, evidence linking the file to the meeting, and a choice to prepare quietly or offer help |

The third case needs more than an event-to-commitment join. It needs a small model of the user's ongoing context and possible opportunities. It does **not** follow that Mike needs a large knowledge graph, continuous screen recording or a second agent engine.

A minimal candidate model could contain:

- **Facts:** source, observed time, freshness limit and confidence based on evidence.
- **Interests and preferences:** where they came from, when corrected, and which context they apply to.
- **Open commitments:** explicit scope, current state, cancellation and completion evidence.
- **Hypotheses:** possible need, supporting facts, missing fact, expiry and reason it might help.
- **Observation requests:** next source to check, reason, deadline, cost bound and wake condition.
- **Attention state:** already shown, rejected, deferred, resolved, or waiting for a real change.

These are proposed concepts. They were not implemented. A hypothesis must not become an authorized commitment just because the model assigns it a high score. “The user might need a brief” can justify an optional proposal inside an allowed domain; it does not grant authority to email attendees.

A key distinction is **creating a hypothesis** versus **creating a recurring obligation**. The first can be a cheap part of normal context processing. The second consumes future resources and needs the authority required for that ongoing work. The report does not recommend asking again for actions already covered by the user's request.

### How to choose the next observation

A fixed heartbeat answers “is it time for another turn?” It does not answer “is another turn useful?” An adaptive policy should compare waiting, using a cheap change signal, and making an active query.

An approximate decision rule is:

`observation value = expected improvement in a decision - tool/model cost - privacy exposure - risk of acting on stale data`.

This is a design heuristic, not a calibrated probability model. Model-written confidence should not be treated as measured expected value.

Examples of bounded choices:

- If a job exposes a completion event, subscribe/wait rather than repeatedly ask its agent.
- If a failed check has no relevant deadline, back off rather than keep the same cadence.
- If a decision depends on a meeting time, recheck near the useful preparation deadline, not after it.
- If nothing changed since the last observation, retain a watermark and suppress the same suggestion.
- If observation needs screen control while the user owns the screen, keep the request pending or choose another source. A “useful” score cannot bypass device ownership.

The source evidence supports the components—script gates, scratch/notepad, cursors, revisions and admission checks. It does not yet establish the quality of this adaptive observation policy. That is the proposed part.

## 9. Mechanisms Mike could adopt conceptually

The following pseudocode is original design material. It is not copied implementation, is not executable product code, and has not been tested.

### 9.1 Separate observation, proposal, permission and execution

```text
on_signal(signal):
    event = durable_inbox.accept_once(signal.source_key, signal.minimal_data)
    if event.expired or source_scope_revoked(event):
        close(event, reason)
        return

    facts = update_facts_with_provenance(event)
    existing = find_open_commitments(facts)
    hypotheses = infer_candidates(facts, existing, allowed_interests)
    candidates = merge_and_deduplicate(existing, hypotheses)

    for candidate in candidates:
        if rejected_and_unchanged(candidate) or outside_observation_scope(candidate):
            record_no_action(candidate)
            continue

        choice = choose(wait, inspect, prepare, propose, execute,
                        bounded_context(candidate), remaining_shared_budget)

        if choice == inspect:
            save_observation_request(source, question, useful_deadline, cost_cap)
        elif choice == wait:
            save_wake_condition_and_expiry(candidate)
        elif choice == propose:
            save_attention_item(candidate, supporting_evidence)
        else:
            plan = build_plan_with_verification(candidate)
            authority = current_authority_for(plan)
            if authority.covers(plan):
                admit_existing_execution_path(plan, authority.version)
            else:
                save_concrete_reviewable_proposal(plan)
```

Several safeguards belong in code: event deduplication, current permission, expiry, budgets and run ownership. Relevance, a preparation plan and the missing question can be model decisions. The model should produce structured choices, not silently mutate the scheduler or treat event text as a user command.

The admission step must use Mike's existing execution and device-tool boundaries. This report does not propose a parallel device gateway or an unlimited background session.

### 9.2 Recovery must distinguish no dispatch from unknown effects

```text
recover_effect(effect):
    if saved_terminal_receipt(effect.identity):
        return saved_result

    if verified_never_dispatched(effect):
        return retry_with_same_identity_if_still_authorized

    if target_supports_lookup(effect.stable_target_key):
        observed = inspect_target(effect.stable_target_key)
        if observed.matches_expected_result:
            return save_verified_result(observed)
        if observed.proves_absence_and_no_old_owner_can_act:
            return retry_under_current_authority

    return save_unknown_and_stop_automatic_replay
```

A lease expiring is not proof of non-execution. Before allowing a new owner, the old owner must be unable to make another effect, or the target must reject its stale generation. UI taps rarely support target-side idempotency; an uncertain tap needs fresh observation and task-specific reasoning.

Store the finished artifact and outgoing message separately. Retrying a known-unsent notification should reuse the stored output and destination, not rerun the research or model plan. Keep local job identity, execution attempt identity, effect identity and delivery identity distinct.

### 9.3 Adapt the next check without allowing recursive growth

```text
choose_next_check(candidate, observations):
    if candidate.closed or candidate.expired or candidate.scope_revoked:
        return none
    if observations.have_authoritative_completion_event:
        return wait_for_event_with_expiry
    if observations.outcome_unknown:
        return reconciliation_only
    if candidate.same_blocker_and_no_new_evidence:
        return bounded_backoff_or_close
    if remaining_budget < minimum_useful_check_cost:
        return defer_with_reason

    suggested_time = model_proposes_time_and_reason(candidate)
    return code_clamps(suggested_time,
                       earliest_allowed, useful_deadline,
                       per_topic_budget, global_budget, maximum_attempts)
```

A model may propose a seven-minute follow-up, but code must prevent a zero-delay loop, extending expiry forever, or creating many equivalent jobs. Child work must share the parent's budget. New observation evidence can change the plan; repeating the same blocker should not count as progress.

## 10. The price of cron, heartbeat and continuous agents

These terms describe different costs:

| Approach | Main advantage | Main cost or failure mode |
|---|---|---|
| Deterministic cron/script | Predictable checks; possibly no model | Host availability, stale inputs, polling and maintenance |
| Heartbeat opening model turns | Simple way to inspect broad context | Repeated context cost, empty work, vague goals and notification noise |
| Event-driven continuation | Fast response to a known change | Missing/duplicated events; queue and authority recovery |
| Self-scheduled next checks | Can align with deadlines and uncertainty | Schedule drift, recursive jobs, difficult budget accounting |
| Continuous service with agents | Long work and external connections can stay available | Resident processes, operational recovery and broader exposure |
| Continuous model reasoning | Can keep revising a plan | Repeated inference, goal drift and work that has no clear stop condition |

“Always on” need not mean continuous inference. OpenClaw schedules turns; Hermes has idle ticks and no-agent/script gates; nanobot skips missing or empty heartbeat tasks. Their cheaper paths depend on keeping the first admission decision outside the full agent. The reviewed code supporting this distinction is in Sections 3–5.

A simple derived example: every 30 minutes means 48 wakes per day and 1,440 in 30 days. If each wake submits 6,000 input tokens, that is 8.64 million input tokens presented before task tools, output, retries or notification evaluation. This is arithmetic, not a measured bill. Cache hits, model choice, provider pricing and empty-task skipping change actual cost.

A useful accounting model is:

`total cost = observation checks + context construction + agent work + verification + delivery + maintenance + recovery`.

This includes costs that token counts miss: database writes, network wakeups, resident workers, interrupted foreground work and the user's time handling weak suggestions. Conversely, a well-chosen proactive action can remove repeated manual work; evaluating only raw cost would unfairly favor a silent system.

The review found concrete cost controls worth testing: no-agent execution, explicit wake gates, bounded scratch, history watermarks, idle-only admission, no-change outcomes, iteration limits and restrictions on recursive scheduling. It did not establish their combined savings on Mike.

## 11. Three alternatives for Mike

All three should keep the same durable execution, effect-recovery and delivery base. The difference is how much new work they are allowed to propose and who stays available to observe it. These are design alternatives, not implemented features.

| Alternative | What Mike does | Best fit | Main weakness | Example |
|---|---|---|---|---|
| **A. Commitment follow-through** | Watches explicit work, relevant events and scheduled checks; continues within existing permission | A reliable first release, low budgets, clear user requests | Misses needs that were never represented as work | A child finishes; Mike checks its artifact and completes the authorized next step |
| **B. Bounded discovery over A** | Uses allowed context and occasional observation to form hypotheses; quietly prepares or proposes useful help | A personal assistant expected to know the user and notice gaps | More false positives, data access, evaluation complexity and hidden cost | A changed meeting time and stale related notes lead to a proposed preparation brief |
| **C. Assigned-domain server manager** | A server keeps selected sources and long work available; runs domain-specific planning and observations; phone retains local authority and attention control | Monitoring or long tasks that must work while Pc/phone sleeps | Service operation, cross-device ownership and stale-authority recovery | An assigned research monitor checks source changes overnight and saves a verified brief for the morning |

### When A should win

Use A when the main problem is forgotten follow-ups or unreliable task continuation. It is easier to explain why each run started and easier to verify completion. It can already feel proactive if the user no longer has to ask for progress.

A should also win when source access is sparse, context is unreliable, or the user mostly wants explicit tasks done. Building inference first cannot fix weak completion receipts.

### When B should win

Use B when the user explicitly wants Mike to notice useful connections, and there are narrowly allowed sources with enough context. B should reuse A's executor, not create a second unrestricted loop. Start with preparation and proposals. It should be able to learn “not this topic,” “not now,” or “already handled” without treating one rejection as a permanent global ban.

The product should expose what Mike is watching, its next intended observation, and the evidence behind a suggestion. A learned interest is not a standing obligation, and an ignored suggestion is not approval.

### When C should win

Use C for availability requirements that local scheduling cannot meet, such as an assigned overnight source monitor. A server can run deterministic checks and wake a model only for relevant changes; it need not constantly reason.

One device should own each execution/effect claim. A phone and server must not both retry an unknown action after losing contact. New local device operations must still be admitted through the phone's current controls. A cloud service being awake does not make a locked phone or unavailable desktop session usable.

### Preferred decision

I favor **A as the required reliability base, with a small B pilot that must prove added value**. C is an availability option for specific domains, not a prerequisite for personal initiative. This is more specific than “build commitments first”: the hypothesis and observation interfaces should be part of the design from the start, while their autonomous budget remains off or limited until evaluated.

No current Mike implementation claim is made from its installed version or from another worktree. This file's task is ecosystem research. Product integration needs its own source review and runtime evidence.

## 12. Strongest counterargument and a test that can reject the recommendation

### Strongest counterargument

**A separate discovery loop may be unnecessary and worse than a strong conversational agent on top of A.** OpenClaw's repetition proposal already relies on ordinary model context, not a special detector. Hermes and Letta improve memory after conversations. A well-designed normal turn could infer nearly all valuable opportunities when the user is already engaged.

Adding B could then duplicate those suggestions, inspect more personal data, make stale assumptions, and consume budget on opportunities the user does not value. The reviewed projects provide mechanisms, but not controlled evidence that this extra loop improves user outcomes. Calling it “bounded” does not establish that its complexity is justified.

This argument can overturn the recommendation. If A with good memory and ordinary in-conversation proposals performs as well at lower cost, Mike should keep that simpler design. A persistent identity should not be confused with a need for a separate always-on planner.

### A proposed falsifiable comparison

This is a future evaluation design. It was not run, and the numbers below are proposed decision thresholds, not research findings.

**Compare A against B with the same execution/recovery base, the same allowed sources and the same total resource ceiling.** A may use ordinary in-conversation suggestions; otherwise the comparison would artificially weaken the baseline. B spends some of its budget on additional discovery observations. Start with read-only or preparation-only work.

Use a fixed pilot, for example 20 workdays, with randomized day blocks and balanced task types. Maintain a small independent daily opportunity log from the allowed sources, with hindsight labels from the user. It must include opportunities neither system suggested, so recall is not measured only on the model's own candidates. If there are too few comparable opportunities, report inconclusive rather than declare a win.

Measure separately:

| Measure | Why it matters |
|---|---|
| User-accepted useful outcomes per day, including saved manual work | Tests actual help rather than number of suggestions |
| Valid opportunities missed before their useful deadline | Prevents “always stay silent” from winning on precision |
| Unwanted interruptions and repeated/rejected suggestions | Captures the user's attention cost |
| Observation, model and tool cost per accepted outcome | Includes the cost of silent work and false candidates |
| Time spent correcting Mike or explaining the same preference | Tests whether memory reduces management work |
| Duplicate external effects and unresolved unknown outcomes | Tests operational reliability separately from usefulness |
| Actions outside current permission | Hard failure, regardless of perceived usefulness |

Predeclare the decision before seeing the results. An illustrative bar would require B to add at least **0.5 user-rated useful outcomes per workday**, add no more than **one unwanted interruption per week**, and stay inside the same total ceiling. Any unauthorized effect or automatic replay of an unknown non-idempotent effect fails the execution gate.

**Reject B for the next product slice** if it does not meet the predeclared added-value bar, exceeds the interruption/cost limits, or mostly duplicates what A already proposes during normal conversation. Do not retain it merely because it found a few appealing examples. Report confidence and sample limits; these thresholds are product choices, not statistical power claims.

A separate recovery test should kill or disconnect an authorized test executor at the admission, post-effect/pre-receipt and post-send/pre-result boundaries. The pass condition is truthful state and no unapproved duplicate—not “eventually green.” User usefulness and recovery correctness should not be blended into one score.

## 13. Open questions and handoff to the parent

Questions the evidence does not settle:

1. How much useful new-need inference happens during ordinary conversation, making a separate discovery loop redundant?
2. Which allowed observations have enough signal to support a proposal without broad personal-data collection?
3. Can Mike reliably verify completion for a selected task class, instead of trusting a finished turn or self-declared result?
4. Which phone/computer tools can reconcile an uncertain effect, and which must retain unknown until a person inspects them?
5. Should optional suggestions fail quiet while explicit reminders surface an operational error? Where does that error wait if immediate delivery is unavailable?
6. How should a rejected suggestion expire or become eligible again after a real context change?
7. What task-specific lateness policy should replace a single global catch-up rule?
8. Can shared budgets prevent nested agents, notification evaluators and memory maintenance from multiplying the cost of one initiative?
9. What is the right stable boundary for an ongoing permission: source, topic, operation, destination, workspace, time and authority generation?
10. How much cross-device availability is actually needed before a server manager earns its operating cost?

**Parent integration points:**

- Keep the prior report's reliability ideas, but do not present commitments/events as sufficient new-need discovery.
- OpenClaw offers the strongest reviewed example of occurrence identity, current-authority admission and atomic local finalization. Its main/release difference and conditional continuation retry must remain explicit.
- Hermes offers the clearest reviewed separation of canonical admission, execution state, per-job memory and outgoing delivery. Queued, completed, delivered and unknown mean different things.
- nanobot shows both the value and cost of a post-work notification gate. Its incoming at-least-once trigger queue and cautious outgoing outbox must not be described as one delivery guarantee. The outbox finding is main-only here.
- Letta's maintained code is Letta Code. Reflection teaches continuity; it is not proof of general proactive judgment. Khoj gives an opposite notification-error fallback worth considering.
- No reviewed system supplies runtime proof that B beats A for Mike. Section 12 is the proposed rejection test, not a validation result.

Final evidence checks: all 95 original source references were retained during English conversion; one invalid evaluator line range was corrected to lines 84–144. The 41 distinct code files cited before that correction were fetched at their pinned refs and checked for valid line ranges. The added AgentLoop references were checked against the same pinned source. This verifies accessible files and line boundaries, not every conclusion or runtime behavior. Markdown fences and URL syntax were checked locally; the file contains no Hebrew text.

Only this assigned research file was edited. No product code, other documentation, memory, worktree, commit, PR, installation or automation was created or changed by this research task. Existing checkout changes were left alone. No third-party code was executed and no phone runtime test was performed.
