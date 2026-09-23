/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class AgentCoordinator(
    private val scope: CoroutineScope,
    private val engine: AgentEngine,
    private val sessions: SessionStore,
    private val tools: DeviceToolGateway,
    private val overlay: ControlOverlay,
    /** Standing "always allow" answers to send approvals. */
    private val sendGrants: SendGrantStore = InMemorySendGrantStore(),
    /**
     * Bring the app's own window to the front.
     *
     * An approval card only exists inside the app, and during device control
     * the app is by definition not the foreground window — the agent is
     * driving another app. Without this the user sees a floating card that
     * says "waiting" and has nothing to tap, while the tool call sits unanswered
     * until it expires. Best-effort and optional: a host that has no window
     * leaves it a no-op.
     *
     * Declared before [adbStatus] on purpose: [adbStatus] stays the trailing
     * parameter, so the `AgentCoordinator(...) { adb.status.value }` form every
     * existing caller uses keeps binding to the parameter it always did.
     */
    private val bringToForeground: () -> Unit = {},
    /**
     * The monotonic clock every duration in [RunMetrics] is measured against.
     *
     * Injected for the same reason the runner's is: a summary that says where a
     * run's time went is only worth what it can be tested against, and a test
     * driving a virtual clock cannot verify a real one. Declared before
     * [adbStatus] so that stays the trailing parameter.
     */
    private val nowNanos: () -> Long = System::nanoTime,
    private val adbStatus: () -> AdbStatus = { AdbStatus() },
) {
    private val mutableState = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = mutableState.asStateFlow()
    private val availableState = MutableStateFlow(true)
    val available = availableState.asStateFlow()
    private val epoch = AtomicLong()
    private val lifecycleLock = Any()
    private val toolLock = Mutex()
    private val assistantFlushLock = Mutex()
    private var runJob: Job? = null
    private val toolJobs = mutableSetOf<Job>()
    private val controlJobs = mutableSetOf<Job>()
    private var completion: CompletableDeferred<Unit>? = null
    private var thread: String? = null
    private var turn: String? = null
    private var assistantItemId: String? = null
    private var lastMessageWasFinal = false
    private var runStartedNanos = 0L
    private var firstResponseMs: Long? = null
    private var toolCalls = 0
    private var toolMs = 0L

    /**
     * Time a person was being waited on, kept apart from [toolMs].
     *
     * A send approval happens inside the tool call that asks for it, so without
     * this the summary would report 20 seconds of "the phone" for 20 seconds of
     * someone deciding whether to send a message. Atomic because the wait is
     * counted where it happens and read where the run ends.
     */
    private val approvalNanos = java.util.concurrent.atomic.AtomicLong(0)
    private val metricsState = MutableStateFlow<Map<String, RunMetrics>>(emptyMap())
    val metrics = metricsState.asStateFlow()
    private var assistantId: String? = null
    private val assistantText = StringBuilder()
    private var assistantOutcome = "complete"
    private var controlTakeover = false
    private var awaitingTurn = false
    private var voiceMode = false
    private val startupEvents = ArrayDeque<EngineEvent>()
    private var textRevision = 0L
    private var textFlushJob: Job? = null
    /** The agent's words already mirrored onto the overlay, so the same line is not resent. */
    private var overlaySpeech: String? = null
    private var pendingLocalApproval: PendingLocalApproval? = null

    init { scope.launch { engine.events.collect { event ->
        try { handleEvent(event) } catch (cancelled: CancellationException) {
            // A local stop can invalidate an event while its storage write suspends.
            // Keep collecting for the next run unless the app scope itself ended.
            currentCoroutineContext().ensureActive()
        }
    } } }

    fun send(
        sessionId: String,
        prompt: String,
        images: List<File> = emptyList(),
        model: String? = null,
        reasoningEffort: String? = null,
        skill: AgentSkill? = null,
        planMode: Boolean = false,
    ) {
        if (prompt.isBlank() && images.isEmpty()) return
        synchronized(lifecycleLock) {
            if (state.value.active) {
                if (state.value.sessionId == sessionId && state.value.phase != RunPhase.STOPPING) steer(prompt)
                return
            }
            if (!availableState.value) return
            availableState.value = false
            val token = epoch.incrementAndGet()
            val runCompletion = CompletableDeferred<Unit>()
            mutableState.value = RunState(RunPhase.STARTING, sessionId, "Starting Codex")
            completion = runCompletion
            thread = null
            turn = null
            assistantId = null
            assistantItemId = null
            lastMessageWasFinal = false
            runStartedNanos = nowNanos()
            firstResponseMs = null
            toolCalls = 0
            toolMs = 0L
            approvalNanos.set(0)
            assistantText.clear()
            assistantOutcome = "complete"
            controlTakeover = false
            awaitingTurn = false
            startupEvents.clear()
            textRevision = 0L
            overlaySpeech = null
            runJob = scope.launch { run(token, runCompletion, sessionId, prompt, images, model, reasoningEffort, skill, planMode) }
        }
    }

    /**
     * Attach the device-tool gateway to turns delegated by a realtime voice
     * session. Realtime owns the conversation transport; this coordinator owns
     * only local tool safety and the delegated turn identity.
     */
    fun beginVoice(sessionId: String, threadId: String, workspace: File) {
        require(threadId.isNotBlank()) { "Voice thread ID is required." }
        synchronized(lifecycleLock) {
            check(availableState.value && !state.value.active) { "Another agent run is already active." }
            availableState.value = false
            val token = epoch.incrementAndGet()
            tools.beginRun(token.toString(), workspace)
            voiceMode = true
            thread = threadId
            turn = null
            assistantId = null
            assistantText.clear()
            assistantOutcome = "complete"
            controlTakeover = false
            awaitingTurn = false
            startupEvents.clear()
            overlaySpeech = null
            completion = null
            runJob = null
            mutableState.value = RunState(RunPhase.THINKING, sessionId, "Voice ready")
        }
    }

    /**
     * Take the phone for a standing rule's own actions, run [block], release it.
     *
     * A rule that drives the screen needs the same exclusive ownership a turn
     * has — one phone screen cannot be shared, and an automation firing
     * underneath a person's run would fight them for it. It claims that
     * ownership the way [beginVoice] does, and waits at most [waitMs] for it:
     * `null` means the device stayed busy, and a rule whose moment has passed
     * is better reported than run half an hour later behind someone else's
     * work.
     *
     * The wait exists because the common case is not a collision at all — it is
     * the agent firing a rule from inside a turn, which by definition already
     * owns the device. Refusing there would make "run my evening rule now"
     * always answer "the phone is busy", with the busy run being the one that
     * asked. A short wait covers that and the ordinary case of a trigger
     * landing mid-task; anything longer is the staleness `validUntil` is for.
     *
     * No model is involved. This arms the gateways and shows the control card;
     * what runs inside is a workflow or an intent the rule named, decided
     * before anything was claimed.
     *
     * Stop still works throughout: [stop] sees an active state, revokes the
     * tools — which is what aborts a workflow between steps — and owns the
     * teardown from there, so the epoch is re-checked here before releasing
     * anything a stop has already released.
     */
    suspend fun <T> runAutomation(
        label: String,
        workspace: File,
        waitMs: Long = DEFAULT_AUTOMATION_WAIT_MS,
        block: suspend () -> T,
    ): T? {
        if (waitMs > 0 && !availableState.value) {
            // Losing the race after the wait is fine: the claim below is what
            // decides, and it refuses rather than double-claiming.
            withTimeoutOrNull(waitMs) { availableState.first { it } }
        }
        val token = synchronized(lifecycleLock) {
            if (!availableState.value || state.value.active) return null
            availableState.value = false
            val claimed = epoch.incrementAndGet()
            tools.beginRun(claimed.toString(), workspace)
            mutableState.value = RunState(RunPhase.CONTROLLING, null, label, controlling = true)
            claimed
        }
        runCatching { overlay.showState(OverlayState(OverlayPhase.CONTROLLING, label)) }
        return try {
            block()
        } finally {
            val stillOurs = synchronized(lifecycleLock) {
                val ours = epoch.get() == token
                if (ours) {
                    tools.revoke()
                    mutableState.value = RunState(status = "Ready")
                    availableState.value = true
                }
                ours
            }
            if (stillOurs) runCatching { overlay.finish(OverlayState(OverlayPhase.DONE, label)) }
        }
    }

    /** Revoke local voice-delegated work without closing the shared app-server. */
    fun endVoice() {
        val context = synchronized(lifecycleLock) {
            if (!voiceMode) return
            val stoppingEpoch = epoch.incrementAndGet()
            cancelLocalApprovalLocked()
            voiceMode = false
            val controls = controlJobs.toList().also { jobs ->
                jobs.forEach { it.cancel() }
                controlJobs.clear()
            }
            val toolsInFlight = toolJobs.toList().also { jobs ->
                jobs.forEach { it.cancel() }
                toolJobs.clear()
            }
            val result = VoiceStopContext(
                stoppingEpoch = stoppingEpoch,
                sessionId = state.value.sessionId,
                threadId = thread,
                turnId = turn,
                controls = controls,
                toolsInFlight = toolsInFlight,
            )
            turn = null
            controlTakeover = false
            mutableState.value = state.value.copy(
                phase = RunPhase.STOPPING,
                status = "Ending voice",
                controlling = false,
                approval = null,
            )
            result
        }
        tools.revoke()
        runCatching { overlay.updateState(OverlayState(OverlayPhase.STOPPING, "Voice")) }
        scope.launch {
            withContext(NonCancellable) {
                context.controls.forEach { job -> runCatching { withTimeout(2_000) { job.join() } } }
                val deviceStop = async { runCatching { withTimeout(2_000) { tools.cancel() } } }
                val engineStop = async {
                    runCatching {
                        withTimeout(2_000) {
                            if (context.threadId != null && context.turnId != null) {
                                engine.interrupt(context.threadId, context.turnId)
                            }
                        }
                    }
                }
                deviceStop.await()
                context.toolsInFlight.forEach { job -> runCatching { withTimeout(2_000) { job.join() } } }
                engineStop.await()
                synchronized(lifecycleLock) {
                    if (epoch.get() == context.stoppingEpoch) {
                        thread = null
                        assistantId = null
                        assistantText.clear()
                        completion = null
                        mutableState.value = RunState(sessionId = context.sessionId, status = "Voice ended")
                    }
                }
                runCatching { overlay.finish(OverlayState(OverlayPhase.DONE, "Voice ended")) }
                availableState.value = true
            }
        }
    }

    private suspend fun run(
        token: Long,
        runCompletion: CompletableDeferred<Unit>,
        sessionId: String,
        prompt: String,
        images: List<File>,
        model: String?,
        reasoningEffort: String?,
        skill: AgentSkill?,
        planMode: Boolean,
    ) {
        try {
            // Read-only chat does not take over the user's screen. The first
            // device action checks and shows the overlay before dispatch.
            overlay.updateState(OverlayState(OverlayPhase.STARTING))
            sessions.append(message(sessionId, "user", prompt, attachments = images.map { it.absolutePath }))
            val session = sessions.getSession(sessionId) ?: error("Chat no longer exists")
            if (session.title == "New chat") sessions.rename(sessionId, prompt.take(48).ifBlank { "Image chat" })
            val work = sessions.workspace(sessionId)
            tools.beginRun(token.toString(), work)
            engine.connect()
            check(engine.account().signedIn) { "Sign in to Codex in Settings first." }
            ensureCurrent(token)
            val openedThread = engine.openSession(work, session.engineThreadId, model, tools.definitions)
            synchronized(lifecycleLock) {
                ensureCurrentLocked(token)
                thread = openedThread
            }
            sessions.setThread(sessionId, openedThread)
            ensureCurrent(token)
            synchronized(lifecycleLock) {
                ensureCurrentLocked(token)
                mutableState.value = state.value.copy(phase = RunPhase.THINKING, status = "Working")
            }
            overlay.updateState(OverlayState(OverlayPhase.THINKING))
            beginTurn(token)
            val startedTurn = engine.startTurn(
                openedThread, prompt, images, reasoningEffort, skill,
                DeviceCapabilities.of(tools, adbStatus()),
                planModel = if (planMode) model else null,
            )
            if (!activateTurn(token, startedTurn)) return
            ensureCurrent(token)
            runCompletion.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isCurrent(token)) {
                sessions.append(message(sessionId, "system", error.message ?: "Run failed"))
                synchronized(lifecycleLock) {
                    if (isCurrentLocked(token)) {
                        assistantOutcome = "error"
                        mutableState.value = state.value.copy(phase = RunPhase.ERROR, status = error.message ?: "Run failed", controlling = false)
                    }
                }
            }
        } finally {
            finalizeRun(token, sessionId)
        }
    }

    /**
     * Answer the waiting approval with the user's own words, spoken or typed.
     *
     * Returns true when [reply] was a plain yes or no and an approval was
     * waiting, so the caller does not also send it to the agent. Anything else
     * returns false and is left for the agent: "yes, but at 7pm" is an
     * instruction. Callers must pass only text the user produced.
     *
     * @param record add the reply to the chat. A voice transcript is recorded
     *   by the voice path already, so it passes false.
     */
    fun answerApprovalByReply(reply: String, record: Boolean = true): Boolean {
        val allow = ApprovalReply.parse(reply) ?: return false
        val (approval, sessionId) = synchronized(lifecycleLock) {
            val approval = state.value.approval?.takeIf { state.value.phase != RunPhase.STOPPING }
            approval to state.value.sessionId
        }
        if (approval == null) return false
        if (record && sessionId != null) {
            scope.launch { runCatching { sessions.append(message(sessionId, "user", reply.trim())) } }
        }
        approve(approval.requestId, allow)
        return true
    }

    fun steer(prompt: String) {
        if (prompt.isBlank()) return
        // Typing "yes" while an approval waits answers it. Steering it to the
        // agent instead would leave the tool call blocked on a card the user
        // may not be looking at.
        if (answerApprovalByReply(prompt)) return
        val request = synchronized(lifecycleLock) {
            val current = state.value
            val currentThread = thread
            val currentTurn = turn
            if (!current.active || current.phase == RunPhase.STOPPING || currentThread.isNullOrBlank() || currentTurn.isNullOrBlank()) {
                null
            } else {
                SteerRequest(epoch.get(), current.sessionId, currentThread, currentTurn, prompt)
            }
        } ?: return
        launchControl {
            try {
                if (!isCurrentTurn(request.token, request.threadId, request.turnId)) return@launchControl
                engine.steer(request.threadId, request.turnId, request.prompt)
                if (isCurrentTurn(request.token, request.threadId, request.turnId)) {
                    request.sessionId?.let { sessions.append(message(it, "user", request.prompt)) }
                }
            } catch (error: Exception) {
                if (error !is CancellationException && isCurrent(request.token)) {
                    request.sessionId?.let { sessions.append(message(it, "system", error.message ?: "Could not send instruction")) }
                }
            }
        }
    }

    fun stop() {
        if (synchronized(lifecycleLock) { voiceMode }) {
            endVoice()
            return
        }
        val context = synchronized(lifecycleLock) {
            val snapshot = state.value
            if (!snapshot.active || snapshot.phase == RunPhase.STOPPING || completion?.isCompleted == true) return
            val stoppingEpoch = epoch.incrementAndGet()
            cancelLocalApprovalLocked()
            val controls = controlJobs.toList().also { jobs ->
                jobs.forEach { it.cancel() }
                controlJobs.clear()
            }
            val toolsInFlight = toolJobs.toList().also { jobs ->
                jobs.forEach { it.cancel() }
                toolJobs.clear()
            }
            val flush = textFlushJob.also { it?.cancel() }
            textFlushJob = null
            val result = StopContext(
                stoppingEpoch = stoppingEpoch,
                snapshot = snapshot,
                threadId = thread,
                turnId = turn,
                assistantId = assistantId,
                text = assistantText.toString(),
                controls = controls,
                toolsInFlight = toolsInFlight,
                flush = flush,
                runJob = runJob,
            )
            awaitingTurn = false
            startupEvents.clear()
            controlTakeover = false
            mutableState.value = snapshot.copy(phase = RunPhase.STOPPING, status = "Stopping", controlling = false, approval = null)
            result
        }
        tools.revoke()
        runCatching { overlay.updateState(OverlayState(OverlayPhase.STOPPING)) }
        context.runJob?.cancel()
        scope.launch {
            withContext(NonCancellable) {
                context.controls.forEach { job -> runCatching { withTimeout(2_000) { job.join() } } }
                val deviceStop = async { runCatching { withTimeout(2_000) { tools.cancel() } } }
                val engineStop = async {
                    val interrupted = runCatching {
                        withTimeout(2_000) {
                            if (context.threadId != null && context.turnId != null) engine.interrupt(context.threadId, context.turnId)
                            else engine.close()
                        }
                    }.isSuccess
                    if (!interrupted) runCatching { engine.close() }
                }
                deviceStop.await()
                context.toolsInFlight.forEach { job -> runCatching { withTimeout(2_000) { job.join() } } }
                engineStop.await()
                context.flush?.let { job -> runCatching { withTimeout(1_000) { job.join() } } }
                context.assistantId?.let { id ->
                    runCatching {
                        assistantFlushLock.withLock {
                            sessions.updateMessage(id, context.text.ifBlank { "Stopped." }, "interrupted")
                        }
                    }
                }
                runCatching { overlay.setCaptureHidden(false) }
                synchronized(lifecycleLock) {
                    if (epoch.get() == context.stoppingEpoch) {
                        thread = null
                        turn = null
                        assistantId = null
                        assistantText.clear()
                        assistantOutcome = "complete"
                        completion = null
                        mutableState.value = RunState(sessionId = context.snapshot.sessionId, status = "Stopped")
                    }
                }
                runCatching { overlay.finish(OverlayState(OverlayPhase.DONE, "Stopped")) }
                availableState.value = true
            }
        }
    }

    /**
     * @param scope for an allowed send, whether to remember the answer. Only a
     *   user action can pass anything but [ApprovalScope.ONCE]; a spoken "yes"
     *   never grants a standing permission.
     */
    fun approve(requestId: String, allow: Boolean, scope: ApprovalScope = ApprovalScope.ONCE) {
        val handledLocally = synchronized(lifecycleLock) {
            val pending = pendingLocalApproval
            if (
                pending != null && pending.id == requestId &&
                state.value.approval?.requestId == requestId &&
                isCurrentTurnLocked(pending.token, pending.threadId, pending.turnId)
            ) {
                val send = pending.send
                if (allow && send != null) {
                    val grant = when (scope) {
                        ApprovalScope.ONCE -> null
                        // No recipient on screen means nothing to pin the grant
                        // to; never widen it to the whole app on the user's behalf.
                        ApprovalScope.CONTACT -> send.recipient?.let { SendGrant(send.packageName, send.appLabel, it) }
                        ApprovalScope.APP -> SendGrant(send.packageName, send.appLabel, null)
                    }
                    grant?.let { runCatching { sendGrants.add(it) } }
                }
                pending.decision.complete(allow)
                true
            } else {
                false
            }
        }
        if (handledLocally) return
        val request = synchronized(lifecycleLock) {
            val approval = state.value.approval
            if (
                approval == null || approval.requestId != requestId || pendingLocalApproval != null ||
                state.value.phase == RunPhase.STOPPING || !approvalMatchesLocked(approval)
            ) {
                null
            } else {
                ApprovalRequest(epoch.get(), approval)
            }
        } ?: return
        launchControl {
            try {
                if (!isCurrentApproval(request)) return@launchControl
                engine.answerApproval(request.approval.requestId, allow)
                synchronized(lifecycleLock) {
                    if (isCurrentApprovalLocked(request)) mutableState.value = state.value.copy(approval = null)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && isCurrent(request.token)) {
                    state.value.sessionId?.let { sessions.append(message(it, "system", error.message ?: "Approval failed")) }
                }
            }
        }
    }

    /**
     * Wait for one app-owned approval and dispatch exactly the request that was
     * shown. The model cannot mint, persist, or replay this permission.
     */
    suspend fun authorizeLocalIntent(
        request: LocalIntentRequest,
        dispatch: () -> ToolResult,
    ): ToolResult {
        val pending = openLocalApproval(
            prefix = "local-intent",
            method = "open_intent",
            details = buildJsonObject {
                put("reason", request.reason)
                put("action", request.action)
                request.uri?.let { put("uri", it) }
                request.packageName?.let { put("package", it) }
            },
            send = null,
        )
        return when (awaitLocalApproval(pending)) {
            LocalOutcome.STOPPED -> localIntentRejected("intent_not_approved", "Run stopped before the intent was approved.")
            LocalOutcome.TIMED_OUT -> localIntentRejected(
                "approval_timeout",
                "Nobody answered the approval within ${LOCAL_APPROVAL_TIMEOUT_MS / 1_000} seconds. " +
                    "It was shown in the Hey Mike app above the message box. Ask the user again: " +
                    "they can tap Allow or just say \"yes\". Call open_intent again once they agree.",
            )
            LocalOutcome.DENIED -> localIntentRejected(
                "intent_denied",
                "The user denied this intent. Do not retry it; ask what they want instead.",
            )
            LocalOutcome.ALLOWED -> dispatch()
        }
    }

    /**
     * Gate the tap that sends a message from another app.
     *
     * A standing grant the user gave ("always for this contact" or "always in
     * this app") dispatches at once. Otherwise the user sees who gets what and
     * answers; [dispatch] runs only after an Allow, and is responsible for
     * getting back to the app it sends from, because asking raised this one.
     */
    suspend fun authorizeSend(
        request: SendRequest,
        dispatch: suspend () -> ToolResult,
    ): ToolResult {
        if (runCatching { sendGrants.covers(request) }.getOrDefault(false)) return dispatch()
        val pending = openLocalApproval(
            prefix = "send",
            method = "send_message",
            details = buildJsonObject {
                put("kind", "send")
                put("app", request.appLabel)
                put("package", request.packageName)
                request.recipient?.let { put("recipient", it) }
                request.message?.let { put("message", it) }
            },
            send = request,
        )
        return when (awaitLocalApproval(pending)) {
            LocalOutcome.STOPPED -> localIntentRejected("send_not_approved", "Run stopped before the send was approved. Nothing was sent.")
            LocalOutcome.TIMED_OUT -> localIntentRejected(
                "approval_timeout",
                "Nobody answered the send approval within ${LOCAL_APPROVAL_TIMEOUT_MS / 1_000} seconds, so " +
                    "nothing was sent. Ask the user again: they can tap Allow or just say \"yes\".",
            )
            LocalOutcome.DENIED -> localIntentRejected(
                "send_denied",
                "The user did not approve sending this. Nothing was sent. Do not retry; ask what they want instead.",
            )
            LocalOutcome.ALLOWED -> dispatch()
        }
    }

    /**
     * Gate one sensitive step of a workflow.
     *
     * The runner performs a whole sequence inside a single tool call, so
     * nothing else gets a chance to stop it. Turning on wireless debugging,
     * sending, paying, deleting or changing a permission is therefore marked in
     * the definition and asked about here, with the same card and the same
     * spoken "yes" as a send.
     *
     * Unlike a send there is no standing grant: a workflow step is approved for
     * the run in front of the user, never for every later run of that workflow.
     */
    suspend fun authorizeWorkflowStep(request: WorkflowConfirmation): WorkflowConfirmationOutcome {
        val pending = try {
            openLocalApproval(
                prefix = "workflow",
                method = "workflow_step",
                details = buildJsonObject {
                    put("kind", "workflow_step")
                    put("workflow", request.workflowId)
                    put("step", request.stepId)
                    put("action", request.action)
                    put("what", request.summary)
                    put("package", request.packageName)
                    request.appLabel?.let { put("app", it) }
                },
                send = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: IllegalStateException) {
            // Another approval already holds the card. Refusing is the safe
            // answer: the step is skipped, not run unattended.
            return WorkflowConfirmationOutcome.UNAVAILABLE
        }
        return when (awaitLocalApproval(pending)) {
            LocalOutcome.ALLOWED -> WorkflowConfirmationOutcome.ALLOWED
            LocalOutcome.DENIED -> WorkflowConfirmationOutcome.DENIED
            LocalOutcome.TIMED_OUT -> WorkflowConfirmationOutcome.TIMED_OUT
            LocalOutcome.STOPPED -> WorkflowConfirmationOutcome.DENIED
        }
    }

    private enum class LocalOutcome { ALLOWED, DENIED, TIMED_OUT, STOPPED }

    private fun openLocalApproval(
        prefix: String,
        method: String,
        details: kotlinx.serialization.json.JsonObject,
        send: SendRequest?,
    ): PendingLocalApproval {
        val pending = synchronized(lifecycleLock) {
            val token = epoch.get()
            val currentThread = thread ?: throw CancellationException("Run stopped")
            val currentTurn = turn ?: throw CancellationException("Run stopped")
            if (!isCurrentTurnLocked(token, currentThread, currentTurn)) {
                throw CancellationException("Run stopped")
            }
            check(state.value.approval == null && pendingLocalApproval == null) {
                "Another approval is already waiting for the user."
            }
            val id = "$prefix-${UUID.randomUUID()}"
            val approval = EngineEvent.Approval(
                requestId = id,
                method = method,
                details = details,
                threadId = currentThread,
                turnId = currentTurn,
            )
            PendingLocalApproval(
                id = id,
                token = token,
                threadId = currentThread,
                turnId = currentTurn,
                decision = CompletableDeferred(),
                send = send,
            ).also {
                pendingLocalApproval = it
                mutableState.value = state.value.copy(
                    approval = approval,
                    status = "Waiting for your approval",
                )
                // Name where the card is. The floating card is all the user can
                // see while another app is in front, so "waiting for approval"
                // on its own tells them nothing they can act on.
                overlay.updateState(OverlayState(OverlayPhase.RUNNING, "Approve in Hey Mike"))
            }
        }
        // Outside the lock: this hands control to the host's UI thread.
        runCatching { bringToForeground() }
        return pending
    }

    private suspend fun awaitLocalApproval(pending: PendingLocalApproval): LocalOutcome {
        // null means nobody answered; false means the user said no. They are
        // different outcomes and the model has to be able to tell them apart.
        val askedAt = nowNanos()
        val decision = try {
            withTimeoutOrNull(LOCAL_APPROVAL_TIMEOUT_MS) { pending.decision.await() }
        } catch (cancelled: CancellationException) {
            approvalNanos.addAndGet(nowNanos() - askedAt)
            clearLocalApproval(pending)
            throw cancelled
        }
        approvalNanos.addAndGet(nowNanos() - askedAt)
        return synchronized(lifecycleLock) {
            val stillCurrent = pendingLocalApproval === pending &&
                isCurrentTurnLocked(pending.token, pending.threadId, pending.turnId)
            clearLocalApprovalLocked(pending)
            when {
                !stillCurrent -> LocalOutcome.STOPPED
                decision == null -> LocalOutcome.TIMED_OUT
                decision == false -> LocalOutcome.DENIED
                else -> LocalOutcome.ALLOWED
            }
        }
    }

    private fun beginTurn(token: Long) {
        synchronized(lifecycleLock) {
            ensureCurrentLocked(token)
            awaitingTurn = true
            turn = null
            startupEvents.clear()
        }
    }

    private suspend fun activateTurn(token: Long, startedTurn: String): Boolean {
        val replay = synchronized(lifecycleLock) {
            if (!isCurrentLocked(token) || startedTurn.isBlank()) {
                startupEvents.clear()
                awaitingTurn = false
                null
            } else {
                turn = startedTurn
                awaitingTurn = false
                val events = startupEvents.filter { turnIdOf(it) == startedTurn }
                startupEvents.clear()
                events
            }
        } ?: return false
        replay.forEach { handleEvent(it) }
        return true
    }

    private fun bufferStartupEvent(event: EngineEvent): Boolean = synchronized(lifecycleLock) {
        if (!awaitingTurn) return@synchronized false
        val expectedThread = thread
        val eventThread = threadIdOf(event)
        val eventTurn = turnIdOf(event)
        if (expectedThread.isNullOrBlank() || eventThread != expectedThread || eventTurn.isNullOrBlank()) {
            false
        } else {
            startupEvents.addLast(event)
            true
        }
    }

    private fun matches(threadId: String?, turnId: String?): Boolean = synchronized(lifecycleLock) {
        state.value.active && state.value.phase != RunPhase.STOPPING &&
            !threadId.isNullOrBlank() && !turnId.isNullOrBlank() &&
            threadId == thread && turnId == turn
    }

    private fun approvalMatchesLocked(approval: EngineEvent.Approval): Boolean =
        state.value.active && state.value.phase != RunPhase.STOPPING &&
            !approval.threadId.isNullOrBlank() && !approval.turnId.isNullOrBlank() &&
            approval.threadId == thread && approval.turnId == turn

    private fun failureMatches(event: EngineEvent.Failure): Boolean = synchronized(lifecycleLock) {
        if (!state.value.active || state.value.phase == RunPhase.STOPPING) return@synchronized false
        if (event.threadId.isNullOrBlank() && event.turnId.isNullOrBlank()) true
        else event.threadId == thread && event.turnId == turn
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lifecycleLock) { isCurrentLocked(token) }

    private fun isCurrentLocked(token: Long): Boolean =
        epoch.get() == token && state.value.active && state.value.phase != RunPhase.STOPPING

    private fun isCurrentTurn(token: Long, threadId: String, turnId: String): Boolean = synchronized(lifecycleLock) {
        isCurrentTurnLocked(token, threadId, turnId)
    }

    private fun isCurrentTurnLocked(token: Long, threadId: String, turnId: String): Boolean =
        isCurrentLocked(token) && thread == threadId && turn == turnId

    private fun isCurrentApproval(request: ApprovalRequest): Boolean = synchronized(lifecycleLock) { isCurrentApprovalLocked(request) }

    private fun isCurrentApprovalLocked(request: ApprovalRequest): Boolean =
        isCurrentLocked(request.token) && state.value.approval == request.approval && approvalMatchesLocked(request.approval)

    private fun ensureCurrent(token: Long) {
        synchronized(lifecycleLock) { ensureCurrentLocked(token) }
    }

    private fun ensureCurrentLocked(token: Long) {
        if (!isCurrentLocked(token)) throw CancellationException("Run stopped")
    }

    private fun clearLocalApproval(pending: PendingLocalApproval) {
        synchronized(lifecycleLock) { clearLocalApprovalLocked(pending) }
    }

    private fun clearLocalApprovalLocked(pending: PendingLocalApproval) {
        if (pendingLocalApproval !== pending) return
        pendingLocalApproval = null
        if (state.value.approval?.requestId == pending.id) {
            mutableState.value = state.value.copy(approval = null, status = "Working")
        }
    }

    private fun cancelLocalApprovalLocked() {
        val pending = pendingLocalApproval ?: return
        pending.decision.complete(false)
        pendingLocalApproval = null
        // The waiter clears its own card, but only while it still owns the
        // pending slot. It no longer does, so leaving the card in the state
        // would strand it: every later approval, local or engine, is refused
        // while one is already showing.
        if (state.value.approval?.requestId == pending.id) {
            mutableState.value = state.value.copy(approval = null)
        }
    }

    private fun localIntentRejected(errorType: String, message: String): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", false)
            put("errorType", errorType)
            put("message", message)
        }.toString(),
        success = false,
    )

    private suspend fun handleEvent(event: EngineEvent) {
        if (bufferStartupEvent(event)) return
        when (event) {
            is EngineEvent.TurnStarted -> synchronized(lifecycleLock) {
                if (voiceMode && event.threadId == thread && event.turnId.isNotBlank()) {
                    turn = event.turnId
                    mutableState.value = state.value.copy(
                        phase = RunPhase.THINKING,
                        status = "Working from voice",
                        controlling = false,
                    )
                }
            }
            is EngineEvent.TextDelta -> if (matches(event.threadId, event.turnId) && !isVoiceMode()) {
                appendAssistant(event.text, event.itemId)
            }
            is EngineEvent.MessageCompleted -> if (matches(event.threadId, event.turnId) && !isVoiceMode()) {
                if (assistantItemId != event.itemId || assistantText.toString() != event.text) {
                    if (assistantItemId == event.itemId) {
                        synchronized(lifecycleLock) { assistantText.clear(); textRevision++ }
                    }
                    appendAssistant(event.text, event.itemId)
                }
                lastMessageWasFinal = event.phase == "final_answer"
                flushAssistantSegment(clear = false)
            }
            is EngineEvent.GeneratedImage -> if (matches(event.threadId, event.turnId)) {
                val token = epoch.get()
                val sessionId = state.value.sessionId ?: return
                flushAssistantSegment()
                val image = try { withContext(Dispatchers.IO) {
                    val workspace = sessions.workspace(sessionId).canonicalFile
                    val saved = event.savedPath?.let(::File)?.canonicalFile?.takeIf {
                        it.toPath().startsWith(workspace.toPath()) && it.isFile && it.length() <= 20L * 1024 * 1024
                    }
                    saved ?: run {
                        require(event.base64.length <= 28 * 1024 * 1024) { "Generated image exceeds the preview limit." }
                        val bytes = java.util.Base64.getDecoder().decode(event.base64)
                        require(bytes.isNotEmpty()) { "Generated image is empty." }
                        val ext = if (bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) "jpg" else "png"
                        File(workspace, "images/${UUID.randomUUID()}.$ext").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
                    }
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    sessions.append(message(sessionId, "system", "Could not show the generated image: ${failure.message}"))
                    return
                }
                ensureCurrent(token)
                sessions.append(message(sessionId, "assistant", "Generated image", listOf(image.absolutePath)))
            }
            is EngineEvent.ToolCall -> {
                if (!matches(event.threadId, event.turnId)) {
                    runCatching { engine.answerTool(event.requestId, ToolResult("Run stopped. No device action was performed.", success = false)) }
                    return
                }
                val token = synchronized(lifecycleLock) { epoch.get() }
                val sessionId = synchronized(lifecycleLock) { state.value.sessionId } ?: return
                flushAssistantSegment()
                lastMessageWasFinal = false
                launchTool {
                    toolLock.withLock {
                        if (!isCurrentTurn(token, event.threadId.orEmpty(), event.turnId.orEmpty())) return@withLock
                        val visible = tools.needsControl(event.name)
                        val capture = tools.hidesOverlayDuringCapture(event.name)
                        // act_and_observe wraps the real action, so report that
                        // instead: callers care that a tap is happening, not
                        // which envelope carried it.
                        val toolName = if (event.name == "act_and_observe") {
                            runCatching { event.arguments["action"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                                ?.takeIf { it.isNotBlank() } ?: event.name
                        } else {
                            event.name
                        }
                        val status = toolName.replace('_', ' ')
                        val overlayState = if (visible) {
                            OverlayState(OverlayPhase.CONTROLLING, status)
                        } else {
                            OverlayState(OverlayPhase.RUNNING, status)
                        }
                        var captureHidden = false
                        var result: ToolResult
                        try {
                            if (capture) {
                                overlay.setCaptureHidden(true)
                                captureHidden = true
                            }
                            if (visible) {
                                val takeover = synchronized(lifecycleLock) { controlTakeover }
                                if (takeover) overlay.updateState(overlayState)
                                else {
                                    overlay.showState(overlayState)
                                    synchronized(lifecycleLock) {
                                        if (isCurrentTurnLocked(token, event.threadId.orEmpty(), event.turnId.orEmpty())) controlTakeover = true
                                    }
                                }
                            } else {
                                overlay.updateState(overlayState)
                            }
                            ensureCurrentTurn(token, event.threadId.orEmpty(), event.turnId.orEmpty())
                            synchronized(lifecycleLock) {
                                mutableState.value = state.value.copy(phase = if (visible) RunPhase.CONTROLLING else RunPhase.TOOL, controlling = visible, status = status, toolName = toolName)
                            }
                            val toolStart = nowNanos()
                            // Any approval this call raises is subtracted below,
                            // so tool time stays device time.
                            val approvalsBefore = approvalNanos.get()
                            toolCalls++
                            try { result = tools.invoke(event.name, event.arguments) }
                            finally {
                                val waited = approvalNanos.get() - approvalsBefore
                                toolMs += ((nowNanos() - toolStart) - waited).coerceAtLeast(0) / 1_000_000
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            result = ToolResult(error.message ?: "Device action failed", success = false)
                        } finally {
                            if (captureHidden) runCatching { overlay.setCaptureHidden(false) }
                            synchronized(lifecycleLock) {
                                if (isCurrentTurnLocked(token, event.threadId.orEmpty(), event.turnId.orEmpty())) {
                                    mutableState.value = state.value.copy(phase = RunPhase.THINKING, controlling = false, status = "Working")
                                    overlay.updateState(OverlayState(OverlayPhase.THINKING))
                                }
                            }
                        }
                        if (isCurrentTurn(token, event.threadId.orEmpty(), event.turnId.orEmpty())) {
                            sessions.append(message(sessionId, "tool", "${event.name}: ${result.text.take(4_000)}", attachments = result.attachmentPaths))
                            engine.answerTool(event.requestId, result)
                        }
                    }
                }
            }
            is EngineEvent.Activity -> synchronized(lifecycleLock) {
                if (isCurrentLocked(epoch.get()) && !state.value.controlling &&
                    (event.threadId == null || (event.threadId == thread && event.turnId == turn))) {
                    mutableState.value = state.value.copy(status = event.text)
                    overlay.updateState(OverlayState(OverlayPhase.RUNNING, event.text))
                }
            }
            is EngineEvent.Approval -> {
                val accepted = synchronized(lifecycleLock) {
                    if (approvalMatchesLocked(event) && state.value.approval == null && pendingLocalApproval == null) {
                        mutableState.value = state.value.copy(approval = event, status = "Waiting for your approval")
                        overlay.updateState(OverlayState(OverlayPhase.RUNNING, "Waiting for approval"))
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    runCatching { engine.answerApproval(event.requestId, false) }
                }
            }
            is EngineEvent.TurnFinished -> if (matches(event.threadId, event.turnId)) {
                if (isVoiceMode()) {
                    if (event.status == "failed") {
                        state.value.sessionId?.let { sessions.append(message(it, "system", event.error ?: "Voice task failed")) }
                    }
                    synchronized(lifecycleLock) {
                        if (voiceMode && thread == event.threadId && turn == event.turnId) {
                            turn = null
                            mutableState.value = state.value.copy(
                                phase = RunPhase.THINKING,
                                status = "Listening",
                                controlling = false,
                                approval = null,
                            )
                        }
                    }
                } else when (event.status) {
                    "failed" -> {
                        val error = event.error ?: "Codex could not finish"
                        synchronized(lifecycleLock) {
                            assistantOutcome = "error"
                            mutableState.value = state.value.copy(phase = RunPhase.ERROR, status = error)
                        }
                        state.value.sessionId?.let { sessions.append(message(it, "system", error)) }
                    }
                    "interrupted" -> synchronized(lifecycleLock) {
                        assistantOutcome = "interrupted"
                        mutableState.value = state.value.copy(status = "Interrupted")
                    }
                }
                if (!isVoiceMode()) completion?.complete(Unit)
            }
            is EngineEvent.Failure -> if (failureMatches(event)) {
                state.value.sessionId?.let { sessions.append(message(it, "system", event.message)) }
                if (isVoiceMode()) {
                    synchronized(lifecycleLock) {
                        if (voiceMode) {
                            turn = null
                            mutableState.value = state.value.copy(
                                phase = RunPhase.THINKING,
                                status = "Voice task failed",
                                controlling = false,
                                approval = null,
                            )
                        }
                    }
                } else {
                    synchronized(lifecycleLock) {
                        assistantOutcome = "error"
                        mutableState.value = state.value.copy(phase = RunPhase.ERROR, status = event.message)
                    }
                    completion?.complete(Unit)
                }
            }
            is EngineEvent.AccountChanged, is EngineEvent.UsageChanged, EngineEvent.SkillsChanged -> Unit
        }
    }

    private fun ensureCurrentTurn(token: Long, threadId: String, turnId: String) {
        synchronized(lifecycleLock) {
            if (!isCurrentTurnLocked(token, threadId, turnId)) throw CancellationException("Run stopped")
        }
    }

    private fun isVoiceMode(): Boolean = synchronized(lifecycleLock) { voiceMode }

    private suspend fun appendAssistant(text: String, itemId: String?) {
        if (text.isEmpty()) return
        val token = epoch.get()
        if (itemId != null && assistantItemId != null && itemId != assistantItemId) flushAssistantSegment()
        ensureCurrent(token)
        if (assistantId == null) {
            val session = state.value.sessionId ?: return
            val id = UUID.randomUUID().toString()
            synchronized(lifecycleLock) { assistantId = id; assistantItemId = itemId }
            sessions.append(ChatMessage(id, session, "assistant", "", System.currentTimeMillis(), "streaming"))
        }
        synchronized(lifecycleLock) {
            ensureCurrentLocked(token)
            if (firstResponseMs == null) firstResponseMs = (nowNanos() - runStartedNanos) / 1_000_000
            assistantText.append(text)
            textRevision++
        }
        scheduleAssistantFlush()
    }

    /**
     * Mirror the agent's own words onto the floating card, so what it says on
     * its way to an action is visible outside the app too. Same text as the
     * chat message; the overlay decides how much of it fits.
     */
    private fun speakOnOverlay(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        val fresh = synchronized(lifecycleLock) {
            if (overlaySpeech == line) false else { overlaySpeech = line; true }
        }
        if (fresh) runCatching { overlay.say(line) }
    }

    private suspend fun flushAssistantSegment(clear: Boolean = true) {
        val flush = synchronized(lifecycleLock) { textFlushJob.also { it?.cancel(); textFlushJob = null } }
        flush?.join()
        val id = assistantId
        if (id != null) {
            val text = assistantText.toString()
            assistantFlushLock.withLock { sessions.updateMessage(id, text, "complete") }
            speakOnOverlay(text)
        }
        if (clear) synchronized(lifecycleLock) {
            assistantId = null; assistantItemId = null; assistantText.clear(); textRevision = 0
        }
    }

    private fun scheduleAssistantFlush() {
        synchronized(lifecycleLock) {
            val id = assistantId ?: return
            if (textFlushJob != null) return
            val token = epoch.get()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(75)
                    val snapshot = synchronized(lifecycleLock) {
                        if (epoch.get() != token || assistantId != id) null
                        else AssistantTextSnapshot(textRevision, assistantText.toString())
                    } ?: return@launch
                    runCatching {
                        assistantFlushLock.withLock { sessions.updateMessage(id, snapshot.text, "streaming") }
                    }
                    speakOnOverlay(snapshot.text)
                    val settled = synchronized(lifecycleLock) {
                        epoch.get() == token && assistantId == id && textRevision == snapshot.revision
                    }
                    if (settled) return@launch
                }
            }
            textFlushJob = job
            job.invokeOnCompletion {
                synchronized(lifecycleLock) { if (textFlushJob === job) textFlushJob = null }
            }
            job.start()
        }
    }

    private suspend fun finalizeRun(token: Long, sessionId: String) {
        val final = synchronized(lifecycleLock) {
            if (epoch.get() != token) null
            else {
                cancelLocalApprovalLocked()
                val flush = textFlushJob.also { it?.cancel() }
                textFlushJob = null
                awaitingTurn = false
                voiceMode = false
                startupEvents.clear()
                controlTakeover = false
                AssistantFinal(assistantId, assistantText.toString(), assistantOutcome, flush)
            }
        } ?: return
        final.flush?.let { job -> runCatching { withTimeout(1_000) { job.join() } } }
        runCatching { tools.revoke() }
        runCatching { overlay.setCaptureHidden(false) }
        final.id?.let { id ->
            runCatching {
                assistantFlushLock.withLock { sessions.updateMessage(id, final.text, final.outcome) }
            }
        }
        if (final.text.isBlank()) {
            val text = when (final.outcome) {
                "error" -> "The task could not finish. ${state.value.status}"
                "interrupted" -> "Stopped. Actions already completed were not undone."
                else -> "The run ended without a final reply. Check the activity details for the actions that completed."
            }
            sessions.append(message(sessionId, "assistant", text))
        }
        val terminalOverlay = synchronized(lifecycleLock) {
            when {
                state.value.phase == RunPhase.ERROR -> OverlayState(OverlayPhase.ERROR, state.value.status)
                final.outcome == "interrupted" -> OverlayState(OverlayPhase.DONE, "Interrupted")
                else -> OverlayState(OverlayPhase.DONE)
            }
        }
        synchronized(lifecycleLock) {
            if (epoch.get() == token) {
                if (state.value.phase != RunPhase.ERROR) {
                    mutableState.value = RunState(
                        sessionId = sessionId,
                        status = if (final.outcome == "interrupted") "Interrupted" else "Ready",
                    )
                }
                thread = null
                turn = null
                assistantId = null
                assistantText.clear()
                assistantOutcome = "complete"
                awaitingTurn = false
                startupEvents.clear()
                completion = null
            }
        }
        runCatching { overlay.finish(terminalOverlay) }
        val metrics = RunMetrics(
            firstResponseMs = firstResponseMs,
            totalMs = (nowNanos() - runStartedNanos) / 1_000_000,
            toolCalls = toolCalls,
            toolMs = toolMs,
            approvalMs = approvalNanos.get() / 1_000_000,
        )
        metricsState.value = metricsState.value + (sessionId to metrics)
        // Into the chat, not a log: the run that felt slow is the one someone
        // will ask about, and the answer belongs where they are already looking.
        RunSummary.line(metrics)?.let { summary ->
            runCatching { sessions.append(message(sessionId, "system", summary)) }
        }
        availableState.value = true
    }

    private fun launchControl(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(lifecycleLock) { controlJobs.add(job) }
        job.invokeOnCompletion { synchronized(lifecycleLock) { controlJobs.remove(job) } }
        job.start()
        return job
    }

    private fun launchTool(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(lifecycleLock) { toolJobs.add(job) }
        job.invokeOnCompletion { synchronized(lifecycleLock) { toolJobs.remove(job) } }
        job.start()
        return job
    }

    private fun threadIdOf(event: EngineEvent): String? = when (event) {
        is EngineEvent.TurnStarted -> event.threadId
        is EngineEvent.TextDelta -> event.threadId
        is EngineEvent.ToolCall -> event.threadId
        is EngineEvent.TurnFinished -> event.threadId
        is EngineEvent.Approval -> event.threadId
        is EngineEvent.Failure -> event.threadId
        is EngineEvent.MessageCompleted -> event.threadId
        is EngineEvent.GeneratedImage -> event.threadId
        is EngineEvent.Activity -> event.threadId
        is EngineEvent.UsageChanged, is EngineEvent.AccountChanged, EngineEvent.SkillsChanged -> null
    }

    private fun turnIdOf(event: EngineEvent): String? = when (event) {
        is EngineEvent.TurnStarted -> event.turnId
        is EngineEvent.TextDelta -> event.turnId
        is EngineEvent.ToolCall -> event.turnId
        is EngineEvent.TurnFinished -> event.turnId
        is EngineEvent.Approval -> event.turnId
        is EngineEvent.Failure -> event.turnId
        is EngineEvent.MessageCompleted -> event.turnId
        is EngineEvent.GeneratedImage -> event.turnId
        is EngineEvent.Activity -> event.turnId
        is EngineEvent.UsageChanged, is EngineEvent.AccountChanged, EngineEvent.SkillsChanged -> null
    }

    private fun message(session: String, role: String, text: String, attachments: List<String> = emptyList()) =
        ChatMessage(UUID.randomUUID().toString(), session, role, text, System.currentTimeMillis(), attachmentPaths = attachments)

    private data class SteerRequest(val token: Long, val sessionId: String?, val threadId: String, val turnId: String, val prompt: String)
    private data class ApprovalRequest(val token: Long, val approval: EngineEvent.Approval)
    private data class PendingLocalApproval(
        val id: String,
        val token: Long,
        val threadId: String,
        val turnId: String,
        val decision: CompletableDeferred<Boolean>,
        /** Set for a send approval, so an "always" answer knows what to remember. */
        val send: SendRequest? = null,
    )
    private data class AssistantTextSnapshot(val revision: Long, val text: String)
    private data class AssistantFinal(val id: String?, val text: String, val outcome: String, val flush: Job?)
    private data class StopContext(
        val stoppingEpoch: Long,
        val snapshot: RunState,
        val threadId: String?,
        val turnId: String?,
        val assistantId: String?,
        val text: String,
        val controls: List<Job>,
        val toolsInFlight: List<Job>,
        val flush: Job?,
        val runJob: Job?,
    )

    private data class VoiceStopContext(
        val stoppingEpoch: Long,
        val sessionId: String?,
        val threadId: String?,
        val turnId: String?,
        val controls: List<Job>,
        val toolsInFlight: List<Job>,
    )

    /** Internal rather than private so tests can advance to the real deadline. */
    internal companion object {
        const val LOCAL_APPROVAL_TIMEOUT_MS = 120_000L

        /**
         * How long a firing rule waits for the phone before giving up.
         *
         * Long enough to outlast the turn that fired it and a short task in
         * front of it, short enough that a rule never surfaces long after its
         * moment — which is what `validUntil` covers properly.
         */
        const val DEFAULT_AUTOMATION_WAIT_MS = 90_000L
    }
}
