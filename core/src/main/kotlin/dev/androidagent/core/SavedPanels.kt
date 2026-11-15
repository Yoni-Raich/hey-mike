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
    internal val persisted: Boolean get() = approvedAtMs != null && placement?.needsApproval == true
}

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
    /** True while any run is in visible control; approval waits until it ends. */
    private val controlling: () -> Boolean = { false },
    /** False while the screen is off: live values are not read then. */
    private val screenOn: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    init {
        load()
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
        if (placement.needsApproval) {
            if (list.count { it.status == SavedPanel.Status.PENDING } >= MAX_PENDING) {
                return toolRefusal("too_many_pending", "$MAX_PENDING panels already wait for the user's approval. Ask them to approve or remove those first.")
            }
            if (list.count { it.persisted } >= MAX_SAVED) {
                return toolRefusal("too_many_saved", "The user already keeps $MAX_SAVED panels. They can remove one in Hey Mike, Saved panels.")
            }
        } else if (list.count { it.status == SavedPanel.Status.ACTIVE && !it.persisted } >= MAX_SHORT) {
            return toolRefusal("too_many_cards", "$MAX_SHORT floating cards are already shown.")
        }
        val approved = if (placement.needsApproval) null else now
        val saved = SavedPanel(
            id, sessionId, arguments, now, approved,
            expiresAtMs = if (approved != null) placement.ttlSeconds?.let { approved + it * 1_000 } else null,
            panel = panel,
        )
        _panels.update { it + saved }
        trace(sessionId, "saved_panel", buildJsonObject {
            put("panelId", id); put("surface", placement.surface.key); put("status", if (approved == null) "awaiting_approval" else "shown")
        })
        if (approved == null) _approvals.tryEmit(id)
        return ToolResult(buildJsonObject {
            put("ok", true)
            put("panelId", id)
            if (approved == null) {
                put("status", "awaiting_approval")
                put("message", "The panel waits for the user to approve it in Hey Mike (Saved panels). You cannot approve it. " +
                    "Tell them in one short line, then end your turn; its taps run without you once approved.")
            } else {
                put("status", "shown")
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
        if (controlling()) return "Mike is controlling the screen. Approve when he is done."
        val panel = parse(id, saved.arguments).getOrElse { error ->
            val reason = "It no longer passes the checks: ${error.message}"
            update(id) { it.copy(panel = null, blocked = reason) }
            return reason
        }
        val now = clock()
        update(id) {
            it.copy(panel = panel, approvedAtMs = now, expiresAtMs = panel.placement.ttlSeconds?.let { s -> now + s * 1_000 })
        }
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
        val on = if (action.switch) turnOn ?: !(state(panelId, actionId).on ?: action.on) else null
        return start(saved, actionId, action.label, action.callFor(on ?: true), on, null)
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
            if (text != null && lastValues.put(key, text) != text) {
                trace(saved.sessionId, "tool_result", buildJsonObject {
                    put("name", call.tool); put("arguments", call.arguments); put("success", true)
                    put("text", text.take(2_000)); put("source", "saved_panel_bind"); put("panelId", panelId)
                })
            }
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
                lock.unlock()
            }
        }
        return true
    }

    private suspend fun run(saved: SavedPanel, actionId: String, label: String, call: PanelCall, on: Boolean?, value: String?) {
        val requestId = "saved-panel-${UUID.randomUUID()}"
        val started = clock()
        trace(saved.sessionId, "tool_call", buildJsonObject {
            put("requestId", requestId); put("name", call.tool); put("arguments", call.arguments)
            put("source", "saved_panel"); put("panelId", saved.id); put("actionId", actionId)
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
        setAction(saved.id, actionId) {
            it.copy(
                running = false, ok = ok, message = if (ok) "" else short(result.text), runs = it.runs + 1,
                on = if (on != null && ok) on else it.on,
                value = if (value != null && ok) value else it.value,
            )
        }
        if (ok) refresh(saved.id)
    }

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
    }
}
