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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.ZoneId
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A live value on a panel that outlives its turn: [call] is read every
 * [everySeconds] while the panel is on screen, and [path] picks the value out
 * of its JSON result. A switch with [equals] is on while the value equals it.
 */
data class PanelBind(
    val call: PanelCall, val path: List<String>, val equals: String?, val format: Format, val everySeconds: Int,
) {
    enum class Format { TEXT, TIME }

    /** The value at [path] in [resultText], formatted; null when it is not there. */
    fun read(resultText: String): String? {
        var node: JsonElement = runCatching { Json.parseToJsonElement(resultText) }.getOrNull() ?: return null
        for (key in path) {
            node = when (val here = node) {
                is JsonObject -> here[key] ?: return null
                is JsonArray -> key.toIntOrNull()?.let(here::getOrNull) ?: return null
                else -> return null
            }
        }
        val value = node as? JsonPrimitive ?: return null
        if (value is JsonNull) return ""
        return when (format) {
            Format.TEXT -> value.content.take(AgentPanels.MAX_LABEL)
            Format.TIME -> value.longOrNull?.let(::clock) ?: value.content.take(AgentPanels.MAX_LABEL)
        }
    }

    /** Whether a bound switch is on at [value]. */
    fun isOn(value: String): Boolean =
        if (equals != null) value.trim().equals(equals, ignoreCase = true) else value.trim().lowercase() in ON

    companion object {
        const val MIN_EVERY = 2
        const val MAX_EVERY = 300
        const val DEFAULT_EVERY = 5
        private val ON = setOf("true", "on", "playing", "1", "yes")
        private val PATH = Regex("[A-Za-z0-9_]{1,40}(\\.[A-Za-z0-9_]{1,40}){0,7}")

        fun parse(spec: JsonObject): Result<PanelBind> = runCatching {
            val tool = (spec["tool"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            require(tool.isNotEmpty()) { "needs a tool." }
            val args = when (val raw = spec["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw IllegalArgumentException("arguments must be an object.")
            }
            require(args.toString().length <= AgentPanels.MAX_ARGUMENTS) { "arguments is too long." }
            require(!AgentPanels.holdsValue(args)) { "arguments cannot hold \"${AgentPanels.VALUE}\"." }
            val path = (spec["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            require(PATH.matches(path)) { "path must be keys joined by dots, e.g. current.title." }
            val equals = (spec["equals"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(AgentPanels.MAX_LABEL)
            val rawFormat = (spec["format"] as? JsonPrimitive)?.contentOrNull?.trim()
            val format = if (rawFormat.isNullOrEmpty()) Format.TEXT else Format.entries.firstOrNull { it.name.equals(rawFormat, ignoreCase = true) }
                ?: throw IllegalArgumentException("format \"$rawFormat\" is not text or time.")
            val every = spec["every"]?.let { raw ->
                (raw as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toLong() }
                    ?: throw IllegalArgumentException("every must be a number of seconds.")
            } ?: DEFAULT_EVERY.toLong()
            require(every in MIN_EVERY..MAX_EVERY) { "every must be $MIN_EVERY to $MAX_EVERY seconds." }
            PanelBind(PanelCall(tool, args), path.split('.'), equals, format, every.toInt())
        }

        /** Milliseconds as m:ss, or h:mm:ss from an hour. */
        fun clock(ms: Long): String {
            val total = (ms.coerceAtLeast(0)) / 1_000
            val h = total / 3_600
            val m = (total % 3_600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/** A panel that outlives its turn, as the store and every surface see it. */
data class SavedPanel(
    val id: String,
    val sessionId: String,
    /** The `show_ui` arguments as Mike sent them; parsed again on every load. */
    val arguments: JsonObject,
    val createdAtMs: Long,
    /** When the user approved it, or when it was shown for a short card that needs no approval. */
    val approvedAtMs: Long?,
    val expiresAtMs: Long?,
    val hidden: Boolean = false,
    /** Null when it no longer passes the checks; then [blocked] says why. */
    val panel: AgentPanel?,
    val blocked: String? = null,
) {
    enum class Status { PENDING, ACTIVE, BLOCKED }

    val status: Status
        get() = when {
            blocked != null || panel == null -> Status.BLOCKED
            approvedAtMs == null -> Status.PENDING
            else -> Status.ACTIVE
        }
    val placement: PanelPlacement? get() = panel?.placement ?: runCatching { PanelPlacement.parse(arguments) }.getOrNull()
    val title: String get() = panel?.title ?: (arguments["title"] as? JsonPrimitive)?.contentOrNull?.take(AgentPanels.MAX_LABEL).orEmpty()

    /** Kept on disk: only an approved panel that needed approval. A short card lives in memory. */
    internal val persisted: Boolean get() = approvedAtMs != null && (panel?.needsApproval ?: (placement?.needsApproval == true))
}

/** A timer on a saved panel ran out. */
data class PanelAlert(val panelId: String, val title: String, val label: String)

/**
 * A `panel_id` definition change that can do more than the approved panel.
 * It waits for the user while the approved version keeps running. Kept in
 * memory only, like a pending panel.
 */
data class PanelRevision(val panelId: String, val sessionId: String, val arguments: JsonObject, val panel: AgentPanel, val proposedAtMs: Long)

/** What a shown saved panel displays right now: tile states and live values by block index. */
data class PanelLive(val actions: Map<String, PanelActionState> = emptyMap(), val values: Map<Int, String> = emptyMap())

/**
 * The saved-panel list on disk, signed so a file planted or edited behind the
 * app's back is refused. [write] signs with a fresh key every time, so an
 * older copy (one with a panel the user since removed) no longer verifies.
 */
interface SavedPanelVault {
    sealed interface Read {
        data object Empty : Read
        data class Valid(val body: String) : Read
        data class Invalid(val reason: String) : Read
    }

    fun read(): Read
    fun write(body: String)
}

/** A [SavedPanelVault] with a real HMAC and an in-memory key, for tests and as a reference. */
class InMemorySavedPanelVault : SavedPanelVault {
    private var key = newKey()
    /** The stored body and signature, as a file on disk would hold them. Tests overwrite it to replay. */
    var stored: Pair<String, ByteArray>? = null

    override fun read(): SavedPanelVault.Read {
        val (body, mac) = stored ?: return SavedPanelVault.Read.Empty
        return if (MessageDigest.isEqual(sign(body), mac)) SavedPanelVault.Read.Valid(body)
        else SavedPanelVault.Read.Invalid("signature does not match")
    }

    override fun write(body: String) {
        key = newKey()
        stored = body to sign(body)
    }

    private fun sign(body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun newKey() = ByteArray(32).also(SecureRandom()::nextBytes)
}

/**
 * Runs panels that outlive their turn. Mike proposes one with `show_ui`; the
 * user approves it here, from the app's own screen, never from the chat; taps
 * then run straight to [invoke] (a gateway that is not part of any run) with no
 * model turn. Every bound call passes [PanelAllowlist] and [checkCall] when the
 * panel is shown, when it loads and on every tap.
 */
class PanelHost(
    private val scope: CoroutineScope,
    private val vault: SavedPanelVault,
    private val sessions: SessionStore,
    private val invoke: suspend (String, JsonObject) -> ToolResult,
    /** Why a tool call may not be bound right now (an unknown tool), or null. */
    private val checkCall: (String, JsonObject) -> String? = { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * True while any run on the phone is active. Approval waits until none is,
     * so Mike can never be the one who taps Approve: he is told to end his
     * turn after proposing, and nothing of his runs while the user approves.
     */
    private val runActive: () -> Boolean = { false },
    /** False while the screen is off: live values are not read then. */
    private val screenOn: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where each kept panel's state is stored, signed like [vault]. Null keeps state in memory. */
    private val stateVault: SavedPanelVault? = null,
    /** The phone's time zone, for daily and weekly resets. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * Starts one Mike turn for an `ask_mike` tap: in chat [sessionId], or a
     * new chat when it is null. [prompt] goes to the model, [displayPrompt]
     * is the user's line in the chat. Returns null when it started, else why not.
     */
    private val startTurn: suspend (sessionId: String?, prompt: String, displayPrompt: String) -> String? =
        { _, _, _ -> "This build cannot ask Mike from a panel." },
) {
    private val _panels = MutableStateFlow<List<SavedPanel>>(emptyList())
    /** Every saved panel: pending, active and blocked. The one list the user removes them from. */
    val panels: StateFlow<List<SavedPanel>> = _panels.asStateFlow()

    private val _live = MutableStateFlow<Map<String, PanelLive>>(emptyMap())
    val live: StateFlow<Map<String, PanelLive>> = _live.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Something the user should know about the store, such as a list that failed its check. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _approvals = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** The id of each panel that now waits for the user's approval. */
    val approvals: SharedFlow<String> = _approvals.asSharedFlow()

    private val busy = ConcurrentHashMap<String, Mutex>()
    private val shown = ConcurrentHashMap<String, Long>()
    private val lastRead = ConcurrentHashMap<String, Long>()
    private val lastValues = ConcurrentHashMap<String, String>()
    private val writeLock = Any()
    private var ticker: Job? = null

    private val _states = MutableStateFlow<Map<String, PanelState>>(emptyMap())
    /** Each panel's own state by panel id: its lists, counters, timers and notes. */
    val states: StateFlow<Map<String, PanelState>> = _states.asStateFlow()

    private val _revisions = MutableStateFlow<Map<String, PanelRevision>>(emptyMap())
    /** Changes Mike made through `panel_id` that wait for the user, by panel id. */
    val revisions: StateFlow<Map<String, PanelRevision>> = _revisions.asStateFlow()

    private val _alerts = MutableSharedFlow<PanelAlert>(extraBufferCapacity = 8)
    /** A timer on an active panel ran out. */
    val alerts: SharedFlow<PanelAlert> = _alerts.asSharedFlow()

    private val stateLock = Any()
    private val stateWriteLock = Any()
    private var saveJob: Job? = null

    init {
        load()
        loadStates()
    }

    fun dismissNotice() { _notice.value = null }

    fun get(id: String): SavedPanel? = _panels.value.firstOrNull { it.id == id }

    /** The active panel [id], or null when it is pending, blocked, expired or gone. */
    fun active(id: String): SavedPanel? = get(id)?.takeIf { it.status == SavedPanel.Status.ACTIVE && !expired(it) }

    /** Mike's `show_ui` for a panel that outlives the turn. Returns at once: nobody waits for it. */
    suspend fun propose(sessionId: String, arguments: JsonObject): ToolResult {
        val id = "saved-" + UUID.randomUUID().toString().take(12)
        val panel = parse(id, arguments).getOrElse {
            return toolRefusal("invalid_panel", "The panel was not shown: ${it.message}")
        }
        val placement = panel.placement
        val now = clock()
        val list = _panels.value
        if (panel.needsApproval) {
            if (list.count { it.status == SavedPanel.Status.PENDING } >= MAX_PENDING) {
                return toolRefusal("too_many_pending", "$MAX_PENDING panels already wait for the user's approval. Ask them to approve or remove those first.")
            }
            if (list.count { it.persisted } >= MAX_SAVED) {
                return toolRefusal("too_many_saved", "The user already keeps $MAX_SAVED panels. They can remove one in Hey Mike, Saved panels.")
            }
        } else if (list.count { it.status == SavedPanel.Status.ACTIVE && !it.persisted } >= MAX_SHORT) {
            return toolRefusal("too_many_cards", "$MAX_SHORT floating cards are already shown.")
        }
        val approved = if (panel.needsApproval) null else now
        val saved = SavedPanel(
            id, sessionId, arguments, now, approved,
            expiresAtMs = if (approved != null) placement.ttlSeconds?.let { approved + it * 1_000 } else null,
            panel = panel,
        )
        _panels.update { it + saved }
        if (approved != null && panel.stateful.isNotEmpty()) _states.update { it + (id to PanelStates.initial(panel, now)) }
        trace(sessionId, "saved_panel", buildJsonObject {
            put("panelId", id); put("surface", placement.surface.key); put("status", if (approved == null) "awaiting_approval" else "shown")
        })
        if (approved == null) _approvals.tryEmit(id)
        return ToolResult(buildJsonObject {
            put("ok", true)
            put("panelId", id)
            if (approved == null) {
                put("status", "awaiting_approval")
                put("stored", false)
                put("message", "The panel waits for the user to approve it in Hey Mike (Saved panels). You cannot approve it. " +
                    "It is not saved until they approve it: if Hey Mike closes first, it is gone. " +
                    "Tell them in one short line, then end your turn; its taps run without you once approved.")
            } else {
                put("status", "shown")
                put("stored", false)
                put("message", "The card is shown for ${PanelPlacement.duration(placement.ttlSeconds ?: 0)}. Its taps run without you.")
            }
        }.toString())
    }

    /**
     * The user approved [id] on the app's own screen. Returns why it could not
     * be approved, or null. Parsed and checked again first; a ttl counts from now.
     */
    suspend fun approve(id: String): String? {
        val saved = get(id) ?: return "This panel is gone."
        if (saved.status != SavedPanel.Status.PENDING) return "This panel is not waiting for approval."
        if (runActive()) return "Mike is still working. Approve when his run has ended."
        val panel = parse(id, saved.arguments).getOrElse { error ->
            val reason = "It no longer passes the checks: ${error.message}"
            update(id) { it.copy(panel = null, blocked = reason) }
            return reason
        }
        val now = clock()
        update(id) {
            it.copy(panel = panel, approvedAtMs = now, expiresAtMs = panel.placement.ttlSeconds?.let { s -> now + s * 1_000 })
        }
        // A countdown starts when the panel goes live, so state starts here.
        if (panel.stateful.isNotEmpty()) synchronized(stateLock) { _states.update { it + (id to PanelStates.initial(panel, now)) } }
        persist()
        trace(saved.sessionId, "saved_panel_approved", buildJsonObject { put("panelId", id) })
        return null
    }

    /** Removes [id] for good: every surface drops it and the store forgets it. */
    suspend fun remove(id: String) {
        val saved = get(id) ?: return
        _panels.update { list -> list.filterNot { it.id == id } }
        forget(id)
        if (saved.persisted) persist()
        trace(saved.sessionId, "saved_panel_removed", buildJsonObject { put("panelId", id) })
    }

    suspend fun removeAll() {
        val all = _panels.value
        _panels.value = emptyList()
        all.forEach { forget(it.id) }
        persist()
    }

    /** A notification the user swiped away stays saved but hidden until they show it again. */
    suspend fun setHidden(id: String, hidden: Boolean) {
        val saved = get(id) ?: return
        if (saved.hidden == hidden) return
        update(id) { it.copy(hidden = hidden) }
        if (saved.persisted) persist()
    }

    /** A tap on action [actionId]. For a switch, [turnOn] is where it goes; null flips it. */
    fun runAction(panelId: String, actionId: String, turnOn: Boolean? = null): Boolean {
        val saved = active(panelId) ?: return false
        val action = saved.panel!!.actions.firstOrNull { it.id == actionId } ?: return false
        when (val effect = action.effect) {
            is PanelEffect.Local -> return edit(panelId, effect.op, "action:$actionId") == null
            is PanelEffect.AskMike -> {
                if (state(panelId, actionId).running) return false
                setAction(panelId, actionId) { it.copy(running = true, message = "") }
                scope.launch { ask(panelId, actionId) }
                return true
            }
            // A surface starts an open itself, through [openTarget], so it comes from the user's tap.
            is PanelEffect.Open -> return false
            is PanelEffect.Steps -> return startSteps(saved, action, effect.steps)
            null -> Unit
        }
        val on = if (action.switch) turnOn ?: !(state(panelId, actionId).on ?: action.on) else null
        val call = action.callFor(on ?: true) ?: return false
        return start(saved, actionId, action.label, call, on, null)
    }

    /** [panelId]'s state now: what was stored or changed, else its start. Null when it has no panel. */
    fun panelState(panelId: String): PanelState? =
        _states.value[panelId] ?: get(panelId)?.panel?.let { PanelStates.initial(it, clock()) }

    /**
     * A change to [panelId]'s own state, from a surface ([source] "surface")
     * or a local action. It touches nothing but that panel's state: no device
     * call, no model. Logged like every other model-free tap. Returns why it
     * was not done, or null.
     */
    fun edit(panelId: String, op: PanelOp, source: String = "surface"): String? {
        val saved = active(panelId) ?: return "This panel is gone."
        val panel = saved.panel!!
        val now = clock()
        val result = if (source == "surface" && !PanelStates.surfaceAllows(panel, op)) {
            Result.failure(IllegalArgumentException("This panel does not offer that here."))
        } else synchronized(stateLock) {
            val current = _states.value[panelId] ?: PanelStates.initial(panel, now)
            PanelStates.apply(panel, current, op, now).onSuccess { next -> _states.update { it + (panelId to next) } }
        }
        val reason = result.exceptionOrNull()?.let { it.message ?: "That cannot be done." }
        scope.launch {
            trace(saved.sessionId, "panel_state", buildJsonObject {
                put("panelId", panelId); put("source", source); put("op", op.toJson()); put("ok", reason == null)
                reason?.let { put("reason", it.take(300)) }
            })
        }
        if (reason == null && saved.persisted) saveStates()
        return reason
    }

    /**
     * A tap on `ask_mike` action [actionId]: one new Mike turn, with the
     * panel's state marked as the user's data. Refused while any run is
     * active, so it never steers a running chat; it does not queue. Returns
     * why it did not start, or null.
     */
    suspend fun ask(panelId: String, actionId: String): String? {
        val saved = active(panelId) ?: return "This panel is gone."
        val panel = saved.panel!!
        val action = panel.actions.firstOrNull { it.id == actionId } ?: return "This button is gone."
        val effect = action.effect as? PanelEffect.AskMike ?: return "This button does not ask Mike."
        val refusal = if (runActive()) {
            "Mike is busy with another task. Tap again when he is done."
        } else {
            val now = clock()
            val state = _states.value[panelId] ?: PanelStates.initial(panel, now)
            val prompt = askPrompt(saved, effect.prompt, PanelStates.view(panel, state, now))
            val origin = saved.sessionId.takeIf { id -> id.isNotEmpty() && runCatching { sessions.getSession(id) }.getOrNull() != null }
            try {
                startTurn(origin, prompt, effect.prompt)
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error.message ?: "Mike could not be asked."
            }
        }
        setAction(panelId, actionId) {
            it.copy(running = false, ok = refusal == null, message = refusal?.take(160).orEmpty(), runs = it.runs + if (refusal == null) 1 else 0)
        }
        trace(saved.sessionId, "panel_ask", buildJsonObject {
            put("panelId", panelId); put("actionId", actionId); put("ok", refusal == null)
            refusal?.let { put("reason", it.take(300)) }
        })
        return refusal
    }

    /**
     * Where `open` action [actionId] goes, checked against the allowlist again
     * at the tap. The app starts it from its own activity.
     */
    fun openTarget(panelId: String, actionId: String): Result<PanelOpen> = runCatching {
        val saved = active(panelId) ?: throw IllegalStateException("This panel is gone.")
        val action = saved.panel!!.actions.firstOrNull { it.id == actionId } ?: throw IllegalStateException("This button is gone.")
        val effect = action.effect as? PanelEffect.Open ?: throw IllegalStateException("This button does not open anything.")
        val target = PanelOpen.parse(effect.target.toJson(), "open")
        scope.launch {
            trace(saved.sessionId, "panel_open", buildJsonObject {
                put("panelId", panelId); put("actionId", actionId); put("open", target.toJson()); put("words", target.words)
            })
        }
        target
    }

    /** The next moment a timer ends or a reset is owed on any active panel, for one alarm. Null when none. */
    fun nextDueAtMs(): Long? {
        val now = clock()
        val zone = zone()
        return _panels.value.filter { it.status == SavedPanel.Status.ACTIVE && !expired(it) }.mapNotNull { saved ->
            val panel = saved.panel ?: return@mapNotNull null
            val state = _states.value[saved.id] ?: return@mapNotNull null
            PanelStates.nextDueAtMs(panel, state, now, zone)
        }.minOrNull()
    }

    /** Letting go of live slider [sliderId] at [value]. */
    fun runSlider(panelId: String, sliderId: String, value: Double): Boolean {
        val saved = active(panelId) ?: return false
        val slider = saved.panel!!.liveSliders.firstOrNull { it.id == sliderId } ?: return false
        val call = slider.callAt(value) ?: return false
        return start(saved, sliderId, slider.label, call, null, slider.format(slider.snap(value)))
    }

    /** The surface for [panelId] is on screen ([visible]) or not. Live values are read only while it is. */
    fun setShown(panelId: String, visible: Boolean, minIntervalMs: Long = 0) {
        if (visible) shown[panelId] = minIntervalMs else shown.remove(panelId)
    }

    /** Starts the clock that ends ttl panels and reads live values. */
    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
    }

    /** One step of the clock: ends expired panels, then reads the live values that are due. */
    suspend fun tick() {
        _panels.value.filter(::expired).forEach { saved ->
            _panels.update { list -> list.filterNot { it.id == saved.id } }
            forget(saved.id)
            if (saved.persisted) persist()
            trace(saved.sessionId, "saved_panel_expired", buildJsonObject { put("panelId", saved.id) })
        }
        tickStates()
        if (!screenOn()) return
        val now = clock()
        shown.forEach { (id, floor) ->
            val panel = active(id)?.panel ?: return@forEach
            val binds = panel.binds.values
            if (binds.isEmpty()) return@forEach
            val every = maxOf(binds.minOf { it.everySeconds } * 1_000L, floor)
            val last = lastRead[id]
            if (last == null || now - last >= every) refresh(id)
        }
    }

    /** Reads every live value on [panelId] now. */
    suspend fun refresh(panelId: String) {
        val saved = active(panelId) ?: return
        val panel = saved.panel!!
        val binds = panel.binds
        if (binds.isEmpty()) return
        lastRead[panelId] = clock()
        val results = mutableMapOf<PanelCall, String?>()
        binds.values.map { it.call }.distinct().forEach { call ->
            val refusal = PanelAllowlist.checkRead(call) ?: checkCall(call.tool, call.arguments)
            results[call] = if (refusal != null) null else runCatching { invoke(call.tool, call.arguments) }
                .getOrNull()?.takeIf { it.success && !refused(it.text) }?.text
            val text = results[call]
            val key = "$panelId|$call"
            val changed = text != null && lastValues.put(key, text) != text
            // Every model-free read is logged; the result text only when it changed, so a poll stays small.
            trace(saved.sessionId, "tool_result", buildJsonObject {
                put("name", call.tool); put("arguments", call.arguments); put("success", text != null)
                put("source", "saved_panel_bind"); put("panelId", panelId)
                when {
                    refusal != null -> put("refused", refusal.take(300))
                    text == null -> put("text", "The read failed.")
                    changed -> put("text", text.take(2_000))
                    else -> put("unchanged", true)
                }
            })
        }
        val values = binds.mapValues { (_, bind) -> results[bind.call]?.let(bind::read) ?: "" }
        val actionsOn = panel.actions.mapNotNull { action ->
            val bind = action.bind ?: return@mapNotNull null
            val value = results[bind.call]?.let(bind::read) ?: return@mapNotNull null
            action.id to bind.isOn(value)
        }.toMap()
        _live.update { all ->
            val current = all[panelId] ?: PanelLive()
            val actions = current.actions.toMutableMap()
            actionsOn.forEach { (aid, on) -> actions[aid] = (actions[aid] ?: PanelActionState()).copy(on = on) }
            all + (panelId to current.copy(values = values, actions = actions))
        }
    }

    fun state(panelId: String, actionId: String): PanelActionState =
        _live.value[panelId]?.actions?.get(actionId) ?: PanelActionState()

    private fun start(saved: SavedPanel, actionId: String, label: String, call: PanelCall, on: Boolean?, value: String?): Boolean {
        // Checked on the call exactly as it is sent, every time.
        val refusal = PanelAllowlist.checkAction(call) ?: checkCall(call.tool, call.arguments)
        if (refusal != null) {
            setAction(saved.id, actionId) { it.copy(running = false, ok = false, message = refusal.take(160)) }
            scope.launch {
                trace(saved.sessionId, "tool_refused", buildJsonObject {
                    put("name", call.tool); put("arguments", call.arguments); put("source", "saved_panel")
                    put("panelId", saved.id); put("actionId", actionId); put("reason", refusal)
                })
            }
            return false
        }
        val lock = busy.getOrPut(saved.id) { Mutex() }
        if (!lock.tryLock()) return false
        setAction(saved.id, actionId) { it.copy(running = true, message = "") }
        scope.launch {
            try {
                run(saved, actionId, label, call, on, value)
            } finally {
                settle(saved.id, actionId)
                lock.unlock()
            }
        }
        return true
    }

    /** A run that was cancelled midway never leaves its tile spinning. */
    private fun settle(panelId: String, actionId: String) {
        if (state(panelId, actionId).running) setAction(panelId, actionId) { it.copy(running = false) }
    }

    /**
     * A tap on a steps action. Every device step is checked against the
     * allowlist first, so a refused step stops the tap before anything runs.
     * Then the steps run one at a time, in order, under the panel's lock, and
     * the first that fails stops the rest; earlier steps stay done.
     */
    private fun startSteps(saved: SavedPanel, action: PanelBlock.Action, steps: List<PanelStep>): Boolean {
        steps.forEachIndexed { index, step ->
            val call = (step as? PanelStep.Device)?.call ?: return@forEachIndexed
            val refusal = PanelAllowlist.checkAction(call) ?: checkCall(call.tool, call.arguments) ?: return@forEachIndexed
            val text = "Nothing ran: step ${index + 1} of ${steps.size} is not allowed. $refusal"
            setAction(saved.id, action.id) { it.copy(running = false, ok = false, message = text.take(160)) }
            scope.launch {
                trace(saved.sessionId, "tool_refused", buildJsonObject {
                    put("name", call.tool); put("arguments", call.arguments); put("source", "saved_panel")
                    put("panelId", saved.id); put("actionId", action.id); put("step", index + 1); put("reason", refusal)
                })
            }
            return false
        }
        val lock = busy.getOrPut(saved.id) { Mutex() }
        if (!lock.tryLock()) return false
        setAction(saved.id, action.id) { it.copy(running = true, message = "") }
        scope.launch {
            try {
                runSteps(saved, action, steps)
            } finally {
                settle(saved.id, action.id)
                lock.unlock()
            }
        }
        return true
    }

    private suspend fun runSteps(saved: SavedPanel, action: PanelBlock.Action, steps: List<PanelStep>) {
        val panel = saved.panel!!
        var failed: Int? = null
        var reason: String? = null
        for ((index, step) in steps.withIndex()) {
            reason = if (active(saved.id) == null) {
                "the panel is gone"
            } else when (step) {
                is PanelStep.Device -> {
                    val (ok, text) = invokeLogged(saved, action.id, "${action.label}, step ${index + 1} of ${steps.size}", step.call, index + 1)
                    if (ok) null else short(text)
                }
                is PanelStep.Local -> edit(saved.id, step.op, "action:${action.id}")
            }
            if (reason != null) {
                failed = index
                break
            }
        }
        // Never claims an earlier step was undone: it was not. The reason is cut, never the step or the tail.
        val message = failed?.let { index ->
            val head = "Step ${index + 1} of ${steps.size} (${PanelOpWords.step(panel, steps[index]).take(48)}) failed: "
            val tail = if (index > 0) ". The steps before it did run." else "."
            head + reason.orEmpty().trimEnd('.', ' ').take((160 - head.length - tail.length).coerceAtLeast(0)) + tail
        }
        setAction(saved.id, action.id) {
            it.copy(running = false, ok = failed == null, message = message?.take(160).orEmpty(), runs = it.runs + 1)
        }
        trace(saved.sessionId, "panel_steps", buildJsonObject {
            put("panelId", saved.id); put("actionId", action.id); put("steps", steps.size); put("ok", failed == null)
            failed?.let { put("failedStep", it + 1) }
            message?.let { put("reason", it.take(300)) }
        })
        if (failed == null) refresh(saved.id)
    }

    private suspend fun run(saved: SavedPanel, actionId: String, label: String, call: PanelCall, on: Boolean?, value: String?) {
        val (ok, text) = invokeLogged(saved, actionId, label, call, null)
        setAction(saved.id, actionId) {
            it.copy(
                running = false, ok = ok, message = if (ok) "" else short(text), runs = it.runs + 1,
                on = if (on != null && ok) on else it.on,
                value = if (value != null && ok) value else it.value,
            )
        }
        if (ok) refresh(saved.id)
    }

    /**
     * One model-free device call from a panel, logged like every other: its
     * call, its result and a line in the panel's chat. [step] is its place in
     * a steps action. Returns whether it worked, and its result text.
     */
    private suspend fun invokeLogged(saved: SavedPanel, actionId: String, label: String, call: PanelCall, step: Int?): Pair<Boolean, String> {
        val requestId = "saved-panel-${UUID.randomUUID()}"
        val started = clock()
        trace(saved.sessionId, "tool_call", buildJsonObject {
            put("requestId", requestId); put("name", call.tool); put("arguments", call.arguments)
            put("source", "saved_panel"); put("panelId", saved.id); put("actionId", actionId)
            step?.let { put("step", it) }
        })
        val result = try {
            invoke(call.tool, call.arguments)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult(error.message ?: "${call.tool} failed", success = false)
        }
        val ok = result.success && !refused(result.text)
        trace(saved.sessionId, "tool_result", buildJsonObject {
            put("requestId", requestId); put("name", call.tool); put("success", ok); put("text", result.text)
            put("elapsedMs", (clock() - started).coerceAtLeast(0)); put("source", "saved_panel")
        })
        message(saved.sessionId, "${call.tool}: ${result.text.take(4_000)}  [from the panel \"${saved.title}\": $label]")
        return ok to result.text
    }

    /**
     * `show_ui` with `panel_id`, from any chat. `"*"` lists the saved panels;
     * an id alone reads one; `ops` or `state` change its state with no
     * approval; anything else replaces its definition, which applies at once
     * only when it can do nothing the approved panel could not.
     */
    suspend fun manage(sessionId: String, arguments: JsonObject): ToolResult {
        val target = (arguments["panel_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (target.isEmpty()) return toolRefusal("invalid_panel", "panel_id must be a saved panel's id, or \"*\" to list them.")
        val rest = JsonObject(arguments.filterKeys { it != "panel_id" })
        if (target == "*") {
            if (rest.isNotEmpty()) return toolRefusal("invalid_panel", "panel_id \"*\" only lists. Pass one panel's id to change it.")
            return ToolResult(buildJsonObject {
                put("ok", true)
                put("panels", buildJsonArray {
                    _panels.value.forEach { saved ->
                        add(buildJsonObject {
                            put("panelId", saved.id); put("title", saved.title)
                            saved.placement?.let { put("surface", it.surface.key) }
                            put("status", if (expired(saved)) "expired" else saved.status.name.lowercase())
                            put("hasState", saved.panel?.stateful?.isNotEmpty() == true)
                            if (_revisions.value.containsKey(saved.id)) put("revision", "awaiting_approval")
                        })
                    }
                })
            }.toString())
        }
        val saved = get(target) ?: return toolRefusal("unknown_panel", "There is no saved panel \"$target\". Pass panel_id \"*\" to list them.")
        if (rest.isEmpty()) return ToolResult(describe(saved).toString())
        val changesState = rest.keys.any { it in STATE_KEYS }
        if (changesState && rest.keys.any { it !in STATE_KEYS }) {
            return toolRefusal("invalid_panel", "Change a panel's state (ops, state) or its definition in one call, not both.")
        }
        return if (changesState) changeState(sessionId, saved, rest) else revise(sessionId, saved, rest)
    }

    /** Approves Mike's waiting change to [id]. Returns why it could not be approved, or null. */
    suspend fun approveRevision(id: String): String? {
        val revision = _revisions.value[id] ?: return "This change is gone."
        val saved = get(id) ?: return "This panel is gone.".also { _revisions.update { it - id } }
        if (runActive()) return "Mike is still working. Approve when his run has ended."
        val panel = parse(id, revision.arguments).getOrElse { error ->
            _revisions.update { it - id }
            return "The change no longer passes the checks: ${error.message}"
        }
        if (saved.placement?.surface?.let { it != panel.placement.surface } == true) {
            _revisions.update { it - id }
            return "The change moves the panel to another surface."
        }
        if (!saved.persisted && panel.needsApproval && _panels.value.count { it.persisted } >= MAX_SAVED) {
            return "You already keep $MAX_SAVED panels. Remove one first."
        }
        applyDefinition(saved, revision.arguments, panel, approvedNow = true)
        trace(saved.sessionId, "saved_panel_revision_approved", buildJsonObject { put("panelId", id) })
        return null
    }

    /** Drops Mike's waiting change to [id]; the approved panel stays as it is. */
    suspend fun rejectRevision(id: String) {
        _revisions.value[id] ?: return
        _revisions.update { it - id }
        get(id)?.let { trace(it.sessionId, "saved_panel_revision_rejected", buildJsonObject { put("panelId", id) }) }
    }

    private fun describe(saved: SavedPanel): JsonObject = buildJsonObject {
        put("ok", true); put("panelId", saved.id); put("title", saved.title)
        put("status", if (expired(saved)) "expired" else saved.status.name.lowercase())
        saved.blocked?.let { put("blocked", it) }
        put("definition", saved.arguments)
        if (_revisions.value.containsKey(saved.id)) put("revision", "awaiting_approval")
        val panel = saved.panel ?: return@buildJsonObject
        if (panel.stateful.isEmpty()) return@buildJsonObject
        val now = clock()
        put("stateNote", PanelUserData.NOTE)
        put("state", PanelUserData.wrap(saved.id, PanelStates.view(panel, _states.value[saved.id] ?: PanelStates.initial(panel, now), now)))
    }

    private suspend fun changeState(sessionId: String, saved: SavedPanel, rest: JsonObject): ToolResult {
        if (saved.status != SavedPanel.Status.ACTIVE || expired(saved)) {
            val why = when {
                saved.status == SavedPanel.Status.PENDING -> "waits for the user's approval"
                expired(saved) -> "has ended"
                else -> "is blocked"
            }
            return toolRefusal("panel_not_active", "The panel \"${saved.title}\" $why, so its state cannot change now.")
        }
        val panel = saved.panel!!
        if (panel.stateful.isEmpty()) return toolRefusal("invalid_state", "This panel has no list, counter, timer or note.")
        val now = clock()
        val next = runCatching {
            val ops = rest["ops"]?.let { raw ->
                val array = raw as? JsonArray ?: throw IllegalArgumentException("ops must be a list.")
                require(array.size in 1..MAX_OPS) { "ops takes 1 to $MAX_OPS changes." }
                array.mapIndexed { index, element ->
                    PanelOp.parse(element as? JsonObject ?: throw IllegalArgumentException("ops[${index + 1}] must be an object."), "ops[${index + 1}]")
                }
            }.orEmpty()
            val whole = rest["state"]?.let { it as? JsonObject ?: throw IllegalArgumentException("state must be an object keyed by block id.") }
            synchronized(stateLock) {
                var state = _states.value[saved.id] ?: PanelStates.initial(panel, now)
                whole?.let { state = PanelStates.set(panel, state, it, now).getOrThrow() }
                ops.forEach { state = PanelStates.apply(panel, state, it, now).getOrThrow() }
                _states.update { it + (saved.id to state) }
                state
            }
        }.getOrElse { return toolRefusal("invalid_state", "The state did not change: ${it.message}") }
        val details = buildJsonObject {
            put("panelId", saved.id); put("source", "mike")
            rest["ops"]?.let { put("ops", it) }
            rest["state"]?.let { put("state", it) }
        }
        trace(sessionId, "panel_state", details)
        if (saved.sessionId != sessionId) trace(saved.sessionId, "panel_state", details)
        if (saved.persisted) saveStates()
        return ToolResult(buildJsonObject {
            put("ok", true); put("panelId", saved.id); put("status", "state_changed")
            put("stateNote", PanelUserData.NOTE)
            put("state", PanelUserData.wrap(saved.id, PanelStates.view(panel, next, now)))
        }.toString())
    }

    private suspend fun revise(sessionId: String, saved: SavedPanel, definition: JsonObject): ToolResult {
        val panel = parse(saved.id, definition).getOrElse {
            return toolRefusal("invalid_panel", "The panel did not change: ${it.message}")
        }
        val surface = saved.placement?.surface
        if (surface != null && panel.placement.surface != surface) {
            return toolRefusal(
                "surface_change",
                "A panel cannot move from ${surface.key} to ${panel.placement.surface.key}. Make a new panel with show_ui, " +
                    "and ask the user to remove this one if they no longer want it.",
            )
        }
        val old = saved.panel
        val status = when {
            saved.status == SavedPanel.Status.PENDING -> {
                update(saved.id) { it.copy(arguments = definition, panel = panel, blocked = null) }
                "replaced_pending"
            }
            old != null && saved.status == SavedPanel.Status.ACTIVE && !expired(saved) &&
                PanelCapabilities.of(panel).within(PanelCapabilities.of(old)) -> {
                applyDefinition(saved, definition, panel, approvedNow = false)
                "applied"
            }
            else -> {
                _revisions.update { it + (saved.id to PanelRevision(saved.id, sessionId, definition, panel, clock())) }
                _approvals.tryEmit(saved.id)
                "awaiting_approval"
            }
        }
        val details = buildJsonObject { put("panelId", saved.id); put("source", "mike"); put("status", status) }
        trace(sessionId, "saved_panel_revised", details)
        if (saved.sessionId != sessionId) trace(saved.sessionId, "saved_panel_revised", details)
        return ToolResult(buildJsonObject {
            put("ok", true); put("panelId", saved.id); put("status", status)
            put("message", when (status) {
                "replaced_pending" -> "The panel still waits for the user's approval, now with this definition."
                "applied" -> "The panel changed at once: it can do nothing it could not do before. Its state was kept for every block whose id and type stayed the same."
                else -> "The approved panel keeps running as it was. This change can do more, so it waits for the user to approve it in " +
                    "Hey Mike (Saved panels). You cannot approve it. Tell them in one short line, then end your turn."
            })
        }.toString())
    }

    private suspend fun applyDefinition(saved: SavedPanel, definition: JsonObject, panel: AgentPanel, approvedNow: Boolean) {
        val now = clock()
        update(saved.id) {
            it.copy(
                arguments = definition, panel = panel, blocked = null,
                approvedAtMs = if (approvedNow) now else it.approvedAtMs,
                expiresAtMs = if (approvedNow) panel.placement.ttlSeconds?.let { s -> now + s * 1_000 } else it.expiresAtMs,
            )
        }
        synchronized(stateLock) {
            _states.update { all ->
                if (panel.stateful.isEmpty()) all - saved.id else all + (saved.id to PanelStates.fit(panel, all[saved.id], now))
            }
        }
        // Tiles and live values are by id and index: read them again for the new definition.
        _live.update { it - saved.id }
        lastRead.remove(saved.id)
        lastValues.keys.removeAll { it.startsWith("${saved.id}|") }
        _revisions.update { it - saved.id }
        persist()
    }

    /** Timers that ran out and resets that are owed, on every active panel with state. */
    private suspend fun tickStates() {
        val now = clock()
        val zone = zone()
        _panels.value.forEach { saved ->
            if (saved.status != SavedPanel.Status.ACTIVE || expired(saved)) return@forEach
            val panel = saved.panel ?: return@forEach
            if (panel.stateful.isEmpty()) return@forEach
            val tick = synchronized(stateLock) {
                val current = _states.value[saved.id] ?: PanelStates.initial(panel, now)
                PanelStates.tick(panel, current, now, zone).also { if (it.changed) _states.update { all -> all + (saved.id to it.state) } }
            }
            if (!tick.changed) return@forEach
            if (saved.persisted) saveStates()
            tick.finished.forEach { timer ->
                _alerts.tryEmit(PanelAlert(saved.id, saved.title, timer.label))
                trace(saved.sessionId, "panel_timer_done", buildJsonObject { put("panelId", saved.id); put("blockId", timer.id) })
            }
            if (tick.reset.isNotEmpty()) {
                trace(saved.sessionId, "panel_reset", buildJsonObject {
                    put("panelId", saved.id); put("blocks", buildJsonArray { tick.reset.forEach { add(JsonPrimitive(it)) } })
                })
            }
        }
    }

    private fun askPrompt(saved: SavedPanel, prompt: String, view: JsonObject): String = buildString {
        append(prompt)
        append("\n\n(Sent by a tap on the saved panel \"").append(saved.title).append("\", panel_id ").append(saved.id).append(". ")
        append(PanelUserData.NOTE).append(")\n")
        append(PanelUserData.wrap(saved.id, view))
    }

    private fun loadStates() {
        val now = clock()
        var stored: JsonObject? = null
        val vault = stateVault
        if (vault != null) {
            when (val read = runCatching { vault.read() }.getOrElse { SavedPanelVault.Read.Invalid(it.message ?: "unreadable") }) {
                SavedPanelVault.Read.Empty -> Unit
                is SavedPanelVault.Read.Invalid -> {
                    addNotice("Panel lists, counters and notes failed their check (${read.reason}) and started over.")
                    runCatching { vault.write(encodeStates(emptyMap())) }
                }
                is SavedPanelVault.Read.Valid -> {
                    stored = runCatching { Json.parseToJsonElement(read.body).jsonObject["states"] as JsonObject }.getOrNull()
                    if (stored == null) {
                        addNotice("Panel lists, counters and notes could not be read and started over.")
                        runCatching { vault.write(encodeStates(emptyMap())) }
                    }
                }
            }
        }
        _states.value = _panels.value.mapNotNull { saved ->
            val panel = saved.panel ?: return@mapNotNull null
            if (panel.stateful.isEmpty()) return@mapNotNull null
            saved.id to PanelStates.decode(panel, stored?.get(saved.id) as? JsonObject, now)
        }.toMap()
    }

    private fun addNotice(text: String) {
        _notice.value = listOfNotNull(_notice.value, text).joinToString(" ")
    }

    /** Writes state after a short pause, so a run of taps is one write. Never per tick of a running timer. */
    private fun saveStates() {
        stateVault ?: return
        synchronized(stateWriteLock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(STATE_WRITE_DELAY_MS)
                writeStates()
            }
        }
    }

    /** Writes every kept panel's state now, for a test or before the app goes. */
    suspend fun flushStates() {
        synchronized(stateWriteLock) { saveJob?.cancel(); saveJob = null }
        writeStates()
    }

    private suspend fun writeStates() {
        val vault = stateVault ?: return
        val kept = _panels.value.filter { it.persisted }.map { it.id }.toSet()
        val body = encodeStates(_states.value.filterKeys { it in kept })
        withContext(io) { synchronized(stateWriteLock) { vault.write(body) } }
    }

    private fun encodeStates(states: Map<String, PanelState>): String = buildJsonObject {
        put("v", 1)
        put("states", buildJsonObject { states.forEach { (id, state) -> put(id, PanelStates.encode(state)) } })
    }.toString()

    private fun setAction(panelId: String, actionId: String, change: (PanelActionState) -> PanelActionState) {
        _live.update { all ->
            val current = all[panelId] ?: PanelLive()
            all + (panelId to current.copy(actions = current.actions + (actionId to change(current.actions[actionId] ?: PanelActionState()))))
        }
    }

    private fun parse(id: String, arguments: JsonObject): Result<AgentPanel> =
        AgentPanels.parse(id, arguments, checkCall).mapCatching { panel ->
            require(panel.placement.detached) { "it no longer outlives the turn." }
            panel
        }

    private fun expired(saved: SavedPanel): Boolean = saved.expiresAtMs?.let { clock() >= it } == true

    private fun update(id: String, change: (SavedPanel) -> SavedPanel) {
        _panels.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun forget(id: String) {
        synchronized(stateLock) { _states.update { it - id } }
        _revisions.update { it - id }
        _live.update { it - id }
        shown.remove(id)
        lastRead.remove(id)
        busy.remove(id)
        lastValues.keys.removeAll { it.startsWith("$id|") }
    }

    private fun load() {
        val read = runCatching { vault.read() }.getOrElse { SavedPanelVault.Read.Invalid(it.message ?: "unreadable") }
        val body = when (read) {
            SavedPanelVault.Read.Empty -> return
            is SavedPanelVault.Read.Invalid -> {
                _notice.value = "Saved panels failed their check (${read.reason}) and were cleared. Ask Mike to make them again."
                runCatching { vault.write(encode(emptyList())) }
                return
            }
            is SavedPanelVault.Read.Valid -> read.body
        }
        val entries = runCatching { (Json.parseToJsonElement(body).jsonObject["panels"] as JsonArray).map { it.jsonObject } }
            .getOrElse {
                _notice.value = "Saved panels could not be read and were cleared."
                runCatching { vault.write(encode(emptyList())) }
                return
            }
        val now = clock()
        var dropped = false
        val loaded = entries.mapNotNull { o ->
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
            val id = str("id") ?: return@mapNotNull null.also { dropped = true }
            val arguments = o["arguments"] as? JsonObject ?: return@mapNotNull null.also { dropped = true }
            val expires = long("expiresAtMs")
            if (expires != null && now >= expires) return@mapNotNull null.also { dropped = true }
            val parsed = parse(id, arguments)
            SavedPanel(
                id, str("sessionId").orEmpty(), arguments, long("createdAtMs") ?: now,
                approvedAtMs = long("approvedAtMs") ?: return@mapNotNull null.also { dropped = true },
                expiresAtMs = expires, hidden = (o["hidden"] as? JsonPrimitive)?.contentOrNull == "true",
                panel = parsed.getOrNull(),
                blocked = parsed.exceptionOrNull()?.let { "It no longer passes the checks: ${it.message}" },
            )
        }
        _panels.value = loaded
        if (dropped) runCatching { vault.write(encode(loaded)) }
    }

    private suspend fun persist() {
        val body = encode(_panels.value.filter { it.persisted })
        withContext(io) { synchronized(writeLock) { vault.write(body) } }
        // The state store follows the list: a removed panel's state goes with it.
        saveStates()
    }

    private fun encode(list: List<SavedPanel>): String = buildJsonObject {
        put("v", 1)
        put("panels", buildJsonArray {
            list.forEach { saved ->
                add(buildJsonObject {
                    put("id", saved.id); put("sessionId", saved.sessionId); put("arguments", saved.arguments)
                    put("createdAtMs", saved.createdAtMs)
                    saved.approvedAtMs?.let { put("approvedAtMs", it) }
                    saved.expiresAtMs?.let { put("expiresAtMs", it) }
                    put("hidden", saved.hidden)
                })
            }
        })
    }.toString()

    /** Logged in the chat the panel came from, like a panel tap in a turn. Not at all when that chat is gone. */
    private suspend fun trace(sessionId: String, type: String, details: JsonObject) {
        if (sessionId.isEmpty()) return
        runCatching {
            if (sessions.getSession(sessionId) == null) return
            sessions.appendTrace(sessionId, buildJsonObject {
                put("timestampMs", clock()); put("type", type)
                details.forEach { (key, value) -> put(key, value) }
            })
        }
    }

    private suspend fun message(sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        runCatching {
            if (sessions.getSession(sessionId) == null) return
            sessions.append(ChatMessage(UUID.randomUUID().toString(), sessionId, "tool", text, clock()))
        }
    }

    private fun refused(text: String): Boolean = runCatching {
        (Json.parseToJsonElement(text).jsonObject["ok"] as? JsonPrimitive)?.contentOrNull == "false"
    }.getOrDefault(false)

    private fun short(text: String): String = runCatching {
        (Json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank)?.take(160) ?: text.take(160)

    companion object {
        const val MAX_PENDING = 4
        const val MAX_SAVED = 12
        const val MAX_SHORT = 3
        /** The most state changes one `panel_id` call may make. */
        const val MAX_OPS = 50
        const val STATE_WRITE_DELAY_MS = 400L
        private val STATE_KEYS = setOf("ops", "state")
    }
}
