package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.TimeSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A complete bounded observe-decide-act Jev loop inside one Codex tool call. */
class JevToolGateway(
    private val provider: JevDecisionProvider,
    private val router: () -> DeviceToolGateway,
) : DeviceToolGateway {
    @Volatile private var revoked = true
    private val tokens = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val invocation = Mutex()
    private class RunEpoch(val value: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RunEpoch>
    }

    /**
     * Runs that spent a call budget without finishing, keyed by resume token.
     *
     * A wall or step limit used to be the end of the goal: the next call
     * started blind, repeated what had already been done and spent its budget
     * getting back to where the last one stopped. Keeping the history and the
     * repeat-detector here makes a budget a pacing device instead of a ceiling,
     * while the orchestrator still re-enters the loop deliberately between
     * segments. Bounded, because an abandoned run must not pin a node list
     * forever.
     */
    private val suspended = object : LinkedHashMap<String, SuspendedRun>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SuspendedRun>) =
            size > MAX_SUSPENDED_RUNS
    }

    override val definitions: List<ToolDefinition> = listOf(TOOL_DEFINITION)

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        generation.incrementAndGet()
        provider.cancelActiveRequest()
        provider.beginRun()
        synchronized(suspended) { suspended.clear() }
        revoked = false
    }

    override fun revoke() {
        revoked = true
        generation.incrementAndGet()
        synchronized(suspended) { suspended.clear() }
        provider.cancelActiveRequest()
    }

    override fun needsControl(name: String): Boolean = name == TOOL_NAME
    override fun deviceBackendLive(): Boolean = false
    override fun readyTools(): Set<String> = if (provider.state.value.ready) setOf(TOOL_NAME) else emptySet()

    override fun statusLine(): String {
        val state = provider.state.value
        return when {
            !state.enabled -> "Jev: off"
            !state.tokenConfigured -> "Jev: enabled, token missing"
            else -> "Jev: ready"
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        check(invocation.tryLock()) { "A Jev task already owns the device loop" }
        val epoch = generation.get()
        return try {
            withContext(RunEpoch(epoch)) { invokeOwned(name, arguments) }
        } finally {
            invocation.unlock()
        }
    }

    private suspend fun invokeOwned(name: String, arguments: JsonObject): ToolResult {
        if (name != TOOL_NAME) throw ToolNotServiceable("jev_unsupported", "Jev does not implement \"$name\".")
        checkActive()
        val state = provider.state.value
        if (!state.enabled) throw ToolNotServiceable("jev_disabled", "Jev is disabled in Hey Mike settings.")
        if (!state.tokenConfigured) throw ToolNotServiceable("jev_not_configured", "Jev is enabled but its API token is missing.")
        // An argument dropped silently changes the task: an ignored package
        // once left Jev with "open the app" and no app.
        val unknown = arguments.keys - ARGUMENTS
        require(unknown.isEmpty()) { "Unknown jev_run_ui_task argument(s): ${unknown.joinToString()}. Accepted: ${ARGUMENTS.joinToString()}." }
        val pkg = arguments.string("package")
        require(pkg == null || PACKAGE_NAME.matches(pkg)) { "package must be an Android package name" }
        val statedGoal = arguments.string("goal")?.let { stated -> pkg?.let { "In the app $it: $stated" } ?: stated }

        // The token carries the goal, so a resumed segment cannot be pointed at
        // a different intent by a caller that restated it loosely.
        val resumed = arguments.string("resume")?.let { token ->
            synchronized(suspended) { suspended.remove(token) } ?: throw IllegalArgumentException(
                "resume token \"$token\" is unknown, already used or expired. " +
                    "Call jev_run_ui_task with goal to start a new run.",
            )
        }
        val goal = resumed?.goal
            ?: statedGoal
            ?: throw IllegalArgumentException("goal is required unless resume is given")
        require(goal.length <= MAX_GOAL_CHARS) { "goal is too long" }
        val suppliedTexts = arguments["texts"]?.jsonArray?.map { element ->
            val value = element as? JsonPrimitive
            require(value != null && value.isString && value.content.length <= MAX_TEXT_CHARS) { "texts must contain exact strings of at most $MAX_TEXT_CHARS characters" }
            value.content
        }?.distinct().orEmpty()
        require(suppliedTexts.size <= MAX_TEXT_VALUES) { "Too many text values" }
        if (resumed != null) {
            require(statedGoal?.let { it == resumed.goal } != false) { "A resume token cannot change the goal" }
            require(suppliedTexts.isEmpty() || suppliedTexts == resumed.texts) { "Start a new goal to change its exact text values" }
        }
        val texts = suppliedTexts.ifEmpty { resumed?.texts.orEmpty() }
        val maxSteps = (arguments["maxSteps"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_STEPS)
            .coerceIn(1, MAX_STEPS)
        val wallMs = (arguments["timeoutMs"]?.jsonPrimitive?.intOrNull ?: DEFAULT_WALL_MS.toInt())
            .toLong().coerceIn(MIN_WALL_MS, MAX_WALL_MS)

        val journal = RunJournal(
            ledger = resumed?.ledger ?: JevTaskLedger(JevTaskLedger.requirements(goal,
                arguments["requirements"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())),
            history = resumed?.history.orEmpty().toMutableList(),
            repeated = resumed?.repeated.orEmpty().toMutableSet(),
            carriedSteps = resumed?.history?.count { it.operation != "RECOVER" } ?: 0,
            segment = (resumed?.segment ?: 0) + 1,
            writes = resumed?.writes ?: JevMutationEvidence(),
        )
        val result = withTimeoutOrNull(wallMs) {
            try { runLoop(goal, texts, maxSteps, journal) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                terminal(when { journal.pendingMutation != null -> "uncertain_mutation"
                    failure is JevAuditFailure -> "completion_audit_error"
                    else -> "observation_error" },
                    journal.history, journal.timings, journal.observation,
                    failure.message ?: "Jev task failed", journal.started, segment = journal.segment)
            }
        }
            ?: if (journal.pendingMutation != null) {
                terminal(
                    status = "uncertain_mutation",
                    history = journal.history,
                    timings = journal.timings,
                    observation = journal.observation,
                    message = "Timed out while ${journal.pendingMutation} may have been dispatched. " +
                        "Its result is unknown and it was not retried.",
                    started = journal.started,
                    segment = journal.segment,
                )
            } else {
                budgetExhausted(
                    status = "timeout",
                    goal = goal,
                    texts = texts,
                    journal = journal,
                    observation = journal.observation,
                    baseMessage = if (journal.history.isNotEmpty()) {
                        "Jev reached its ${wallMs}ms wall limit. Completed actions are recorded, but the " +
                            "final UI may be unknown; do not repeat the last action blindly."
                    } else {
                        "Jev UI task reached its ${wallMs}ms wall limit."
                    },
                )
            }
        return result.copy(text = JsonObject(Json.parseToJsonElement(result.text).jsonObject + mapOf(
            "taskLedger" to journal.ledger.state(), "unverifiedWrites" to journal.writes.state(),
        )).toString())
    }

    /**
     * A terminal status that means "out of budget", not "out of options".
     *
     * These are the only statuses that hand back a resume token: the goal was
     * never judged unreachable, the call simply ran out of steps or wall time.
     */
    private suspend fun budgetExhausted(
        status: String,
        goal: String,
        texts: List<String>,
        journal: RunJournal,
        observation: JevObservation?,
        baseMessage: String,
        model: String? = null,
    ): ToolResult {
        val continuation = registerContinuation(goal, texts, journal)
        val token = continuation?.string("token")
        val message = if (token == null) {
            "$baseMessage It has no resume budget left after ${journal.segment} segments; " +
                "decompose the remaining work into smaller goals."
        } else {
            "$baseMessage Call jev_run_ui_task again with resume=\"$token\" to continue this same " +
                "goal from here with its history; the token carries the goal, so goal may be omitted."
        }
        return terminal(
            status, journal.history, journal.timings, observation, message,
            journal.started, model, continuation, journal.segment, journal.ledger.state(),
        )
    }

    private suspend fun registerContinuation(goal: String, texts: List<String>, journal: RunJournal): JsonObject? {
        checkActive()
        if (journal.segment >= MAX_RESUME_SEGMENTS) return null
        val epoch = currentCoroutineContext()[RunEpoch]?.value
        val token = "jev-resume-${tokens.incrementAndGet()}"
        val run = SuspendedRun(goal, texts, journal.history.toList(), journal.repeated.toSet(), journal.segment, journal.ledger, journal.writes)
        synchronized(suspended) {
            if (revoked || epoch != generation.get()) throw CancellationException("Cannot resume a stopped run")
            suspended[token] = run
        }
        return buildJsonObject {
            put("token", token)
            put("stepsCompleted", journal.history.count { it.operation != "RECOVER" })
            put("segment", journal.segment)
            put("segmentsRemaining", MAX_RESUME_SEGMENTS - journal.segment)
            put("goal", goal)
        }
    }

    override suspend fun cancel() {
        provider.cancelActiveRequest()
    }

    private suspend fun runLoop(
        goal: String,
        suppliedTexts: List<String>,
        maxSteps: Int,
        journal: RunJournal,
    ): ToolResult {
        val started = journal.started
        val timings = journal.timings
        val history = journal.history
        val repeated = journal.repeated
        val textOptions = textCandidates(goal, suppliedTexts)
        val (apps, initialObservation) = coroutineScope {
            val apps = async { loadApps(goal) }
            val observation = async { observe(timings) }
            apps.await() to observation.await()
        }
        var observation = initialObservation
        journal.observation = observation
        var consecutiveWaits = 0
        var consecutiveStale = 0
        var rejectedDone: String? = null
        var blockedScreen: String? = null
        var actionPage = 0
        var textPage = 0
        var menuScreen = ""
        val inspectedPages = mutableSetOf<Int>()
        val transitions = mutableMapOf<String, Int>()
        val idleWaits = mutableMapOf<String, Int>()

        // Paging, stale observations and recovery decisions are not device
        // steps. Give them their own generous internal budget so the public
        // maxSteps/deadline remain the only normal continuation boundaries.
        repeat(maxSteps * DECISIONS_PER_STEP + DECISION_OVERHEAD) {
            checkActive()
            journal.writes.reconcile(observation.json)
            journal.ledger.observe(observation.fingerprint, observation.json, null, null)
            val space = JevActionCatalog.build(goal, observation, textOptions.values, apps,
                router().readyTools(), repeated, actionPage, textPage)
            if (menuScreen != observation.fingerprint) { inspectedPages.clear(); menuScreen = observation.fingerprint }
            inspectedPages += space.page
            val modelStart = TimeSource.Monotonic.markNow()
            val response = try {
                provider.choose(space.request(goal, observation, history, textOptions.source,
                    journal.ledger.decisionState(), journal.writes.state()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return terminal("model_error", history, timings, observation, failure.message ?: "Jev request failed.", started, segment = journal.segment)
            } finally {
                timings.modelMs += modelStart.elapsedNow().inWholeMilliseconds
                timings.modelCalls++
            }
            checkActive()

            var selected = try {
                space.select(response)
            } catch (failure: IllegalArgumentException) {
                return terminal("invalid_decision", history, timings, observation, failure.message, started, segment = journal.segment)
            }
            timings.decisions.merge(selected.operation, 1, Int::plus)
            if (selected.operation == "TYPE_TEXT" && space.hasTextOptions) {
                val textStarted = TimeSource.Monotonic.markNow()
                selected = try {
                    space.selectText(selected, provider.choose(space.textRequest(selected, goal)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return terminal("text_decision_error", history, timings, observation,
                        failure.message ?: "Jev could not select an exact field value", started, segment = journal.segment)
                } finally {
                    timings.modelCalls++
                    timings.modelMs += textStarted.elapsedNow().inWholeMilliseconds
                }
                checkActive()
            }
            // Paging wraps, so without a stop Jev could flip pages until the wall
            // limit. Once every page of this screen has been shown, stay put.
            if (selected.operation == "MORE_ACTIONS") {
                val next = (space.page + 1) % space.pageCount
                if (next in inspectedPages) repeated += "${observation.fingerprint}:MORE_ACTIONS" else actionPage = next
                return@repeat
            }
            if (selected.operation == "MORE_TEXT") {
                val next = (space.textPage + 1) % space.textPageCount
                if (next == 0) repeated += "${observation.fingerprint}:MORE_TEXT" else textPage = next
                return@repeat
            }
            if (selected.operation == "BLOCKED") {
                val nextPage = (0 until space.pageCount).firstOrNull { it !in inspectedPages }
                if (nextPage != null) {
                    actionPage = nextPage
                    history += JevHistoryEntry("RECOVER", "Inspect another page of available actions before declaring the goal blocked")
                    return@repeat
                }
                // Give Jev a fresh screen and an explicit recovery phase before
                // returning to Mike. No action is guessed or forced here.
                if (blockedScreen != observation.fingerprint) {
                    blockedScreen = observation.fingerprint
                    history += JevHistoryEntry("RECOVER", "Look for another route: Back, recent apps, quick settings, drawer swipes or another action page.")
                    observation = observe(timings)
                    journal.observation = observation
                    return@repeat
                }
                if (rejectedDone == observation.fingerprint) {
                    return terminal("incomplete", history, timings, observation,
                        "Completion was rejected: the requirement ledger still has pending work.", started,
                        response.model, segment = journal.segment, ledger = journal.ledger.state())
                }
                return terminal("blocked", history, timings, observation, "Jev found no supported action that can advance the goal.", started, response.model, segment = journal.segment)
            }
            if (selected.operation == "DONE") {
                val final = observe(timings)
                if (final.fingerprint != observation.fingerprint) {
                    observation = final
                    journal.observation = observation
                    consecutiveStale++
                    if (consecutiveStale >= MAX_CONSECUTIVE_STALE) {
                        return terminal("unstable_screen", history, timings, observation, "The screen kept changing before DONE could be verified.", started, response.model, segment = journal.segment)
                    }
                    return@repeat
                }
                journal.observation = final
                journal.ledger.observe(final.fingerprint, final.json, null, null)
                val complete = auditRequirements(goal, journal) && journal.writes.resolved
                val auditedScreen = observe(timings)
                journal.observation = auditedScreen
                if (auditedScreen.fingerprint != final.fingerprint) {
                    observation = auditedScreen
                    return@repeat
                }
                if (!complete) {
                    if (rejectedDone == final.fingerprint) {
                        return terminal("incomplete", history, timings, final,
                            "Completion was rejected: the requirement ledger still has pending work.", started,
                            response.model, segment = journal.segment, ledger = journal.ledger.state())
                    }
                    rejectedDone = final.fingerprint
                    repeated += "${final.fingerprint}:DONE"
                    history += JevHistoryEntry("RECOVER", "DONE rejected by requirement audit. Continue the pending requirements in taskLedger; reveal missing evidence, for example by scrolling.")
                    observation = final
                    return@repeat
                }
                return terminal(
                    "done_visible",
                    history,
                    timings,
                    final,
                    "Jev audited every requirement against recorded evidence. Semantic judgments are not deterministic verification.",
                    started,
                    response.model,
                    segment = journal.segment,
                    ledger = journal.ledger.state(),
                )
            }
            // With values supplied, "none of them" is about this field (it may
            // already hold the value, or be the wrong field), not a missing input.
            val declined = selected.target?.let { space.attemptKey(observation.fingerprint, it) }
            if (selected.operation == "TYPE_TEXT" && selected.action == null &&
                textOptions.source == "supplied" && declined != null && declined !in repeated) {
                repeated += declined
                history += JevHistoryEntry("RECOVER", "No supplied value fits that field; it is withdrawn on this screen.")
                return@repeat
            }
            if (selected.operation == "TYPE_TEXT" && selected.action == null) {
                val reason = if (textOptions.overflow) {
                    "The goal contains too many possible text spans. Pass the exact field value in texts."
                } else {
                    "The required field value is not available. Pass it in texts."
                }
                return terminal("needs_input", history, timings, observation, reason, started, response.model, segment = journal.segment)
            }
            if (history.count { it.operation != "RECOVER" } - journal.carriedSteps >= maxSteps) {
                return budgetExhausted(
                    "step_limit", goal, suppliedTexts, journal, observation,
                    "Jev UI task reached $maxSteps executed steps in this call.", response.model,
                )
            }

            if (selected.operation == "WAIT") {
                val waitMs = (WAIT_BASE_MS * (1 shl consecutiveWaits.coerceAtMost(4))).coerceAtMost(WAIT_MAX_MS)
                val waitStart = TimeSource.Monotonic.markNow()
                delay(waitMs)
                timings.waitMs += waitStart.elapsedNow().inWholeMilliseconds
                consecutiveWaits++
                if (consecutiveWaits > MAX_CONSECUTIVE_WAITS) {
                    return terminal("loading_timeout", history, timings, observation, "The screen did not become actionable after repeated waits.", started, response.model, segment = journal.segment)
                }
                val waitedOn = observation.fingerprint
                observation = observe(timings)
                journal.observation = observation
                if (observation.fingerprint == waitedOn) {
                    val idle = (idleWaits[waitedOn] ?: 0) + 1
                    idleWaits[waitedOn] = idle
                    if (idle >= MAX_IDLE_WAITS_PER_SCREEN) repeated += "$waitedOn:WAIT"
                }
                return@repeat
            }

            val fresh = observe(timings)
            if (fresh.fingerprint != observation.fingerprint) {
                timings.staleRetries++
                observation = fresh
                journal.observation = observation
                consecutiveStale++
                if (consecutiveStale >= MAX_CONSECUTIVE_STALE) {
                    return terminal("unstable_screen", history, timings, observation, "The screen changed before three consecutive actions.", started, response.model, segment = journal.segment)
                }
                return@repeat
            }
            consecutiveStale = 0
            consecutiveWaits = 0
            val freshSpace = JevActionCatalog.build(goal, fresh, textOptions.values, apps,
                router().readyTools(), repeated, space.page, space.textPage)
            val action = freshSpace.resolve(selected)
                ?: return terminal("stale_action", history, timings, fresh, "The selected action is no longer available on the fresh screen.", started, response.model, segment = journal.segment)
            val signature = "${fresh.fingerprint}:${selected.target.orEmpty()}:${action.label}"
            if (signature in repeated) {
                return terminal("stuck", history, timings, fresh, "Jev selected the same action on the same screen twice.", started, response.model, segment = journal.segment)
            }

            checkActive()
            journal.pendingMutation = action.label
            val actionStart = TimeSource.Monotonic.markNow()
            var stepActionMs = 0L
            var outcome: String? = null
            var dispatch = ToolDispatch.UNKNOWN
            val refusal = try {
                val actionResult = router().invoke(action.tool, action.arguments)
                dispatch = actionResult.dispatch
                outcome = actionResult.text.take(MAX_RESULT_MESSAGE)
                if (action.tool in setOf("set_text", "type_text")) outcome = buildJsonObject {
                    put("executorResult", outcome)
                    put("exactRequestedText", action.arguments.getValue("text"))
                }.toString().take(MAX_RESULT_MESSAGE)
                if (actionResult.success) null else actionResult.text.take(MAX_RESULT_MESSAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (absent: ToolNotServiceable) {
                dispatch = ToolDispatch.NOT_DISPATCHED
                absent.message ?: "Action unavailable before dispatch"
            } catch (failure: Exception) {
                failure.message ?: "action result unknown"
            } finally {
                stepActionMs = actionStart.elapsedNow().inWholeMilliseconds
                timings.actionMs += stepActionMs
            }
            if (refusal != null) {
                // The backend refuses plenty it definitively did not perform: a
                // node that rejects the text or the progress value, a coordinate
                // our own overlay covers. An unchanged screen does NOT prove
                // that no side effect happened. Record the failure without a
                // success claim and suppress that action on this screen.
                val refusedObserveStart = timings.observationMs
                val after = try {
                    observe(timings)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // A settings deep link only lands on a screen. Treating a
                // refused one as a possible mutation would end the whole run
                // over a destination this phone simply does not declare.
                val navigation = action.tool in setOf("open_app", "scroll_node") ||
                    action.tool == "open_intent" &&
                    action.arguments.string("action")?.startsWith("android.settings.") == true ||
                    action.tool == "key" && action.arguments.string("keycode") in setOf("BACK", "HOME", "RECENTS", "QUICK_SETTINGS", "NOTIFICATIONS")
                val exactWrite = action.tool in setOf("set_text", "type_text", "set_progress") &&
                    dispatch in setOf(ToolDispatch.ACKNOWLEDGED, ToolDispatch.VERIFIED)
                if (after == null || dispatch != ToolDispatch.NOT_DISPATCHED && !navigation && !exactWrite) {
                    after?.let { journal.observation = it }
                    return terminal(
                        "uncertain_mutation", history, timings, after ?: fresh,
                        "${action.label}: $refusal. The action may have been dispatched and was not retried.",
                        started, response.model, segment = journal.segment,
                    )
                }
                journal.pendingMutation = null
                history += JevHistoryEntry(selected.operation, "${action.label} - unsuccessful: $refusal", failed = true,
                    outcome = "dispatch=$dispatch; inspect the fresh state; no success is assumed",
                    actionMs = stepActionMs).also { it.observeMs = timings.observationMs - refusedObserveStart }
                repeated.add(signature)
                observation = after
                journal.observation = observation
                journal.ledger.observe(after.fingerprint, after.json, action.label, "dispatch=$dispatch; $refusal")
                recordTextWrite(journal, fresh, action, dispatch)
                return@repeat
            }
            history += JevHistoryEntry(selected.operation, action.label, outcome = outcome, actionMs = stepActionMs)
            journal.pendingMutation = null
            val observeStart = timings.observationMs
            observation = try {
                observe(timings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return terminal(
                    "observation_failed_after_action",
                    history,
                    timings,
                    fresh,
                    "${action.label} was acknowledged, but its resulting screen could not be read: ${failure.message}. Do not repeat the action blindly.",
                    started,
                    response.model,
                    segment = journal.segment,
                )
            }
            journal.observation = observation
            history.last().observeMs = timings.observationMs - observeStart
            actionPage = 0
            if (action.tool in setOf("set_text", "type_text")) {
                recordTextWrite(journal, fresh, action, dispatch)
                journal.writes.reconcile(observation.json)
            }
            history.last().screenChanged = observation.fingerprint != fresh.fingerprint
            if (!history.last().screenChanged) repeated.add(signature)
            journal.ledger.observe(observation.fingerprint, observation.json, action.label, outcome)
            val transition = "$signature:${observation.fingerprint}:${journal.ledger.revision}"
            val visits = (transitions[transition] ?: 0) + 1
            transitions[transition] = visits
            // A toggle alternates between two screens, so "same action, same
            // screen" never fires; a transition seen twice is a loop.
            if (visits >= MAX_TRANSITION_VISITS) repeated.add(signature)
            // Internal checkpoints retain ownership of the same goal. Only the
            // caller's overall step/deadline budget can return a continuation.
            if (history.count { it.operation != "RECOVER" } % 8 == 0) auditRequirements(goal, journal)
        }
        return budgetExhausted(
            "decision_limit", goal, suppliedTexts, journal, observation,
            "Jev exhausted its decision budget for this call.",
        )
    }

    /** Record an exact field write only when the action carried a grounded node. */
    private fun recordTextWrite(
        journal: RunJournal,
        before: JevObservation,
        action: JevSafeAction,
        dispatch: ToolDispatch,
    ) {
        if (dispatch == ToolDispatch.NOT_DISPATCHED) return
        val nodeId = action.arguments["nodeId"]?.jsonPrimitive?.contentOrNull ?: return
        val text = action.arguments["text"]?.jsonPrimitive?.contentOrNull ?: return
        journal.writes.recordText(before.json, nodeId, text, dispatch)
    }

    private suspend fun auditRequirements(goal: String, journal: RunJournal): Boolean {
        checkActive()
        val questions = journal.ledger.questions()
        val started = TimeSource.Monotonic.markNow()
        val response = try {
            provider.choose(JevDecisionRequest(buildJsonObject {
                put("goal", goal)
                put("taskLedger", journal.ledger.state())
                put("unverifiedWrites", journal.writes.state())
                put("purpose", "Check completion evidence; do not execute actions")
            }, questions))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw JevAuditFailure(failure)
        } finally {
            journal.timings.modelCalls++
            journal.timings.modelMs += started.elapsedNow().inWholeMilliseconds
        }
        checkActive()
        val choices = try { questions.mapIndexed { index, question ->
            index to validateJevChoice(response.answers[question.name], question.criteria, question.name)
        }.toMap() } catch (invalid: IllegalArgumentException) { throw JevAuditFailure(invalid) }
        journal.ledger.lastAudit = buildJsonArray {
            questions.forEachIndexed { index, question ->
                val answer = response.answers[question.name]
                add(buildJsonObject {
                    put("id", "R$index")
                    put("choice", choices.getValue(index))
                    answer?.confidence?.let { put("confidence", it) }
                    answer?.probabilities?.get("PENDING")?.let { put("pPending", it) }
                })
            }
        }
        return journal.ledger.apply(choices)
    }

    private suspend fun observe(timings: Timings): JevObservation {
        val started = TimeSource.Monotonic.markNow()
        try {
            repeat(MAX_PAGING_RETRIES) { attempt ->
                try {
                    return observePages()
                } catch (changed: PagedObservationChanged) {
                    if (attempt == MAX_PAGING_RETRIES - 1) throw changed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Read-only retries cannot replay an action. Start from page
                    // zero so an expired snapshot never mixes two screens.
                    if (attempt == MAX_PAGING_RETRIES - 1) throw failure
                    delay(100L * (attempt + 1))
                }
            }
            error("unreachable")
        } finally {
            timings.observationMs += started.elapsedNow().inWholeMilliseconds
        }
    }

    private suspend fun observePages(): JevObservation {
        val merged = mutableListOf<JsonElement>()
        var offset = 0
        var page = 0
        var latest: JsonObject? = null
        var screenDigest: String? = null
        var snapshotId: String? = null
        var backend: String? = null
        do {
            checkActive()
            val result = router().invoke("read_ui", buildJsonObject {
                put("force", true)
                put("offset", offset)
                put("maxChars", UiObservationSerializer.MAX_OUTPUT_CHARS)
                snapshotId?.let { put("snapshotId", it) }
            })
            if (!result.success) throw IllegalStateException("read_ui failed: ${result.text.take(MAX_RESULT_MESSAGE)}")
            val json = runCatching { Json.parseToJsonElement(result.text).jsonObject }
                .getOrElse { throw IllegalStateException("read_ui returned invalid JSON") }
            if (json["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                throw IllegalStateException(json.string("message") ?: "read_ui failed")
            }
            val pageDigest = json.string("screenDigest")
                ?: throw IllegalStateException("read_ui returned no screenDigest")
            val pageBackend = json.string("source")
            if (backend != null && backend != pageBackend) throw PagedObservationChanged()
            backend = pageBackend
            if (json.bool("snapshotPaging") && snapshotId == null) snapshotId = json.string("observationId")
            if (snapshotId != null && json.string("observationId") != snapshotId) throw PagedObservationChanged()
            if (screenDigest != null && screenDigest != pageDigest) {
                throw PagedObservationChanged()
            }
            screenDigest = pageDigest
            latest = json
            merged += json["nodes"]?.jsonArray.orEmpty()
            val next = json["nextOffset"]?.jsonPrimitive?.intOrNull
            if (next == null) break
            if (next <= offset) throw IllegalStateException("read_ui paging made no progress")
            offset = next
            page++
        } while (page < MAX_UI_PAGES)
        val last = latest ?: throw IllegalStateException("read_ui returned no observation")
        if (last["truncated"]?.jsonPrimitive?.booleanOrNull == true && last["nextOffset"] != null) {
            throw IllegalStateException("UI exceeds the Jev paging limit")
        }
        val observationId = last.string("observationId")
            ?: throw IllegalStateException("read_ui returned no observationId")
        val normalized = buildJsonObject {
            last.string("activePackage")?.let { put("activePackage", it) }
            last["viewport"]?.let { put("viewport", it) }
            last["windows"]?.let { put("windows", it) }
            last["treeTruncated"]?.let { put("treeTruncated", it) }
            put("nodes", JsonArray(merged))
        }
        return JevObservation(observationId, normalized, "$backend:${checkNotNull(screenDigest)}:${last["viewport"]}:${last["windows"]}")
    }

    private suspend fun loadApps(goal: String): List<JevInstalledApp> {
        val explicitPackages = PACKAGE_RE.findAll(goal).map { it.value }
            .distinct()
            .map { JevInstalledApp(it, it) }
            .toList()
        val query = appQuery(goal)
        // App enumeration is the slowest part of a local observation on some
        // phones. Do not list every installed app for an ordinary in-app task.
        // Only goals that can launch or name an app get a bounded lookup.
        if (query == null) return explicitPackages
        val apps = linkedMapOf<String, JevInstalledApp>()
        for (offset in 0 until MAX_APP_QUERY_RESULTS step MAX_APPS) {
            val page = loadAppsPage(offset, query)
            val before = apps.size
            page.forEach { apps[it.packageName] = it }
            if (page.size < MAX_APPS || apps.size == before) break
        }
        // An unqualified label can miss a launcher because the platform sorts
        // before taking the page. A small unfiltered fallback is still cheaper
        // than the former 10,000-app scan and keeps navigation recoverable.
        if (apps.isEmpty()) {
            loadAppsPage(0, null).forEach { apps[it.packageName] = it }
        }
        return (explicitPackages + apps.values).distinctBy { it.packageName }
    }

    private suspend fun loadAppsPage(offset: Int, query: String?): List<JevInstalledApp> {
        checkActive()
        val result = try {
            router().invoke("apps_settings", buildJsonObject {
                put("operation", "list_apps")
                put("limit", MAX_APPS)
                put("offset", offset)
                put("include_system", true)
                query?.let { put("query", it) }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        if (!result.success) return emptyList()
        return runCatching {
            Json.parseToJsonElement(result.text).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                val app = item.jsonObject
                val pkg = app.string("package") ?: return@mapNotNull null
                JevInstalledApp(pkg, app.string("label") ?: pkg)
            }
        }.getOrDefault(emptyList())
    }

    private fun appQuery(goal: String): String? {
        val lower = goal.lowercase()
        if (!APP_GOAL_WORDS.any { word -> Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(word)}([^\\p{L}\\p{N}]|$)").containsMatchIn(lower) }) {
            return null
        }
        listOf("settings", "chrome", "telegram", "whatsapp", "youtube", "maps", "gym")
            .firstOrNull { it in lower }
            ?.let { return it }
        val target = Regex("(?i)(?:open|launch|start|switch\\s+to|go\\s+to|navigate\\s+to)\\s+(.+)")
            .find(goal)?.groupValues?.getOrNull(1)
            ?.split(Regex("\\s+|[,.;:()\\[\\]]"))
            ?.map { it.trim('-', '_') }
            ?.filter { it.length >= 3 && it.lowercase() !in APP_STOP_WORDS }
            ?.lastOrNull()
        return target?.take(MAX_APP_QUERY_CHARS)
    }

    private fun terminal(
        status: String,
        history: List<JevHistoryEntry>,
        timings: Timings,
        observation: JevObservation?,
        message: String?,
        started: TimeSource.Monotonic.ValueTimeMark? = null,
        model: String? = null,
        continuation: JsonObject? = null,
        segment: Int = 1,
        ledger: JsonObject? = null,
    ): ToolResult = ToolResult(
        buildJsonObject {
            put("status", status)
            ledger?.let { put("taskLedger", it) }
            if (status == "done_visible") put("verified", false)
            put("steps", history.count { it.operation != "RECOVER" })
            if (segment > 1) put("segment", segment)
            message?.let { put("message", it) }
            model?.let { put("model", it) }
            continuation?.let { put("continuation", it) }
            put("timings", buildJsonObject {
                put("modelMs", timings.modelMs)
                put("observationMs", timings.observationMs)
                put("actionMs", timings.actionMs)
                put("waitMs", timings.waitMs)
                put("modelCalls", timings.modelCalls)
                put("jevDecisionCalls", timings.modelCalls)
                put("orchestratorModelCalls", 0)
                put("staleRetries", timings.staleRetries)
                put("decisions", buildJsonObject { timings.decisions.forEach { (operation, count) -> put(operation, count) } })
                started?.let { put("wallMs", it.elapsedNow().inWholeMilliseconds) }
            })
            put("history", buildJsonArray {
                history.forEach { entry ->
                    add(buildJsonObject {
                        put("operation", entry.operation)
                        put("label", entry.label)
                        put("screenChanged", entry.screenChanged)
                        if (entry.failed) put("refused", true)
                        entry.actionMs?.let { put("actionMs", it) }
                        entry.observeMs?.let { put("observeMs", it) }
                        entry.outcome?.let { put("result", it) }
                    })
                }
            })
            observation?.let { put("finalObservation", it.json) }
        }.toString(),
        success = status == "done_visible",
    )

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (revoked || currentCoroutineContext()[RunEpoch]?.value?.let { it != generation.get() } == true) {
            throw CancellationException("Run stopped or superseded. Jev UI control was revoked.")
        }
    }

    private class PagedObservationChanged : IllegalStateException("UI changed while read_ui pages were being collected")
    private class JevAuditFailure(cause: Exception) : IllegalStateException("Jev completion audit failed: ${cause.message}", cause)
    private data class SuspendedRun(
        val goal: String,
        val texts: List<String>,
        val history: List<JevHistoryEntry>,
        val repeated: Set<String>,
        val segment: Int,
        val ledger: JevTaskLedger,
        val writes: JevMutationEvidence,
    )
    private data class RunJournal(
        val ledger: JevTaskLedger,
        val writes: JevMutationEvidence,
        val started: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
        val timings: Timings = Timings(),
        val history: MutableList<JevHistoryEntry> = mutableListOf(),
        /** Screen-and-action signatures already tried, carried across segments. */
        val repeated: MutableSet<String> = mutableSetOf(),
        /** Steps inherited from earlier segments; maxSteps is per call. */
        val carriedSteps: Int = 0,
        val segment: Int = 1,
        var observation: JevObservation? = null,
        var pendingMutation: String? = null,
    )
    private data class Timings(
        var modelMs: Long = 0,
        var observationMs: Long = 0,
        var actionMs: Long = 0,
        var waitMs: Long = 0,
        var modelCalls: Int = 0,
        var staleRetries: Int = 0,
        val decisions: MutableMap<String, Int> = sortedMapOf(),
    )
    private data class TextOptions(val values: List<String>, val source: String, val overflow: Boolean)


    private companion object {
        const val TOOL_NAME = "jev_run_ui_task"
        const val MAX_GOAL_CHARS = 2_000
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_TEXT_VALUES = 254

        /** Calls one goal may be spread over before it has to be decomposed. */
        const val MAX_RESUME_SEGMENTS = 5
        const val MAX_SUSPENDED_RUNS = 8

        const val DEFAULT_MAX_STEPS = 120
        const val MAX_STEPS = 500
        const val DECISIONS_PER_STEP = 8
        const val DECISION_OVERHEAD = 64
        const val DEFAULT_WALL_MS = 300_000L
        const val MIN_WALL_MS = 5_000L
        const val MAX_WALL_MS = 900_000L
        const val MAX_UI_PAGES = 128
        const val MAX_PAGING_RETRIES = 3
        const val MAX_APPS = 50
        const val MAX_APP_QUERY_RESULTS = 100
        const val MAX_APP_QUERY_CHARS = 80
        const val MAX_CONSECUTIVE_STALE = 3
        const val MAX_CONSECUTIVE_WAITS = 8
        const val MAX_IDLE_WAITS_PER_SCREEN = 2
        const val MAX_TRANSITION_VISITS = 2
        private val ARGUMENTS = setOf("goal", "package", "resume", "requirements", "texts", "maxSteps", "timeoutMs")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        const val WAIT_BASE_MS = 100L
        const val WAIT_MAX_MS = 1_000L
        const val MAX_RESULT_MESSAGE = 1_000
        val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val APP_GOAL_WORDS = setOf("open", "launch", "start", "switch", "navigate", "settings", "app", "application")
        val APP_STOP_WORDS = setOf(
            "the", "and", "then", "screen", "page", "into", "with", "from", "to", "on", "in", "at", "after",
        )
        val TOOL_DEFINITION: ToolDefinition = ToolDefinition(
            TOOL_NAME,
            "Run a complete Android UI task with the fast Jev engine in ONE tool call. Jev repeatedly observes, decides, acts and checks the next screen locally; do not call read_ui or per-step UI tools first. " +
                "Use texts for exact values that must be typed. Returns done_visible (Jev judgment, not task-specific verification), blocked, needs_input, stuck or a bounded failure with timings and the final fresh UI. " +
                "A goal too large for one call ends in step_limit, timeout or decision_limit with a \"continuation\" token: call this tool again with resume set to that token to continue the same goal with its history, instead of restarting it blind.",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("goal", buildJsonObject {
                        put("type", "string"); put("minLength", 1); put("maxLength", MAX_GOAL_CHARS)
                        put("description", "The complete UI task, including every requested final state. Required unless resume is given.")
                    })
                    put("package", buildJsonObject {
                        put("type", "string"); put("maxLength", 255)
                        put("description", "Optional Android package the goal is about. The goal is scoped to that app; name it here or in goal.")
                    })
                    put("resume", buildJsonObject {
                        put("type", "string"); put("minLength", 1)
                        put("description", "A continuation token from a previous budget-limited reply. It carries that run's goal and history, so goal may be omitted. Each token is single-use.")
                    })
                    put("requirements", buildJsonObject {
                        put("type", "array"); put("maxItems", 31)
                        put("items", buildJsonObject { put("type", "string"); put("maxLength", 2000) })
                        put("description", "Optional success requirements supplied ONCE with the whole goal. Jev audits each against retained evidence inside this call; do not split them into separate tool calls.")
                    })
                    put("texts", buildJsonObject {
                        put("type", "array")
                        put("maxItems", MAX_TEXT_VALUES)
                        put("items", buildJsonObject { put("type", "string"); put("maxLength", MAX_TEXT_CHARS) })
                        put("description", "Optional exact field values. Jev selects among them but cannot invent text.")
                    })
                    put("maxSteps", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", MAX_STEPS); put("description", "Executed steps allowed in this call, not across resumed calls.") })
                    put("timeoutMs", buildJsonObject { put("type", "integer"); put("minimum", MIN_WALL_MS); put("maximum", MAX_WALL_MS) })
                })
                put("anyOf", buildJsonArray {
                    add(buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("goal")))) })
                    add(buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("resume")))) })
                })
            },
        )

        private fun textCandidates(goal: String, supplied: List<String>): TextOptions {
            if (supplied.isNotEmpty()) return TextOptions(supplied.distinct(), "supplied", false)
            val quoted = Regex("[\"“]([^\"”]+)[\"”]").findAll(goal)
                .map { it.groupValues[1] }.filter { it.length <= MAX_TEXT_CHARS }.distinct().toList()
            if (quoted.isNotEmpty() && quoted.size <= MAX_TEXT_VALUES) return TextOptions(quoted, "quoted_goal", false)
            val matches = Regex("\\S+").findAll(goal).toList()
            val values = linkedSetOf<String>()
            for (length in 1..minOf(8, matches.size)) {
                for (start in 0..matches.size - length) {
                    val first = matches[start]
                    val last = matches[start + length - 1]
                    val value = goal.substring(first.range.first, last.range.last + 1)
                        .trim().trim('"', '\'', '“', '”', '‘', '’', '(', ')', '[', ']', '{', '}', ',', '.', '!', '?', ';', ':')
                    if (value.isNotEmpty()) values += value.take(MAX_TEXT_CHARS)
                    if (values.size > MAX_TEXT_VALUES) return TextOptions(emptyList(), "goal", true)
                }
            }
            return TextOptions(values.toList(), "goal", false)
        }

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
    }
}
