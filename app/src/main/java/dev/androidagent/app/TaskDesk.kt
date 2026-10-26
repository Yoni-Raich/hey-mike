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

package dev.androidagent.app

import dev.androidagent.core.AgentRuns
import dev.androidagent.core.CapturedMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.DiscoverySchedule
import dev.androidagent.core.EngineKind
import dev.androidagent.core.MemberKind
import dev.androidagent.core.MemoryBook
import dev.androidagent.core.MikeTask
import dev.androidagent.core.QueuedTurn
import dev.androidagent.core.RunPhase
import dev.androidagent.core.SessionAgents
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.core.SessionStore
import dev.androidagent.core.SignalInbox
import dev.androidagent.core.TaskAction
import dev.androidagent.core.TaskBoard
import dev.androidagent.core.TaskBoardSettings
import dev.androidagent.core.TaskChatRole
import dev.androidagent.core.TaskExecution
import dev.androidagent.core.TaskExecutor
import dev.androidagent.core.TaskPlaybook
import dev.androidagent.core.TaskPrompts
import dev.androidagent.core.TaskRolePolicy
import dev.androidagent.core.TaskSignal
import dev.androidagent.core.TaskStatus
import dev.androidagent.core.TaskTools
import dev.androidagent.core.TeamMember
import dev.androidagent.core.TeamRoster
import dev.androidagent.workspace.LocalTaskBoardStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Everything the board's screens show, in one value. */
data class TaskDeskState(
    val ready: Boolean = false,
    val tasks: List<MikeTask> = emptyList(),
    val members: List<TeamMember> = emptyList(),
    val notes: List<dev.androidagent.core.MemoryNote> = emptyList(),
    val settings: TaskBoardSettings = TaskBoardSettings(),
    val pendingSignals: Int = 0,
    val managerSessionId: String? = null,
    /** Chats the library leaves out: backstage members, their passes, and task chats under the Manager. */
    val hiddenChats: Set<String> = emptySet(),
    /** Member id to what it is doing now. */
    val activity: Map<String, String> = emptyMap(),
    val sourceApps: Map<String, String> = emptyMap(),
    val calendarGranted: Boolean = false,
    val error: String? = null,
)

/**
 * The task board on this phone: the Manager, the team's chats, the user's
 * decisions turned into child chats, and discovery in the background.
 *
 * Collect all the time, think in small batches. Notifications and quiet chats
 * become signals with no model involved; a pass runs only when something new
 * arrived and has gone quiet, in the Scout's read-only chat. The nightly
 * Coach and Librarian run in the same restricted way. Nothing here acts on the
 * phone: work starts only from the user's decision, through [executor].
 */
class TaskDesk(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val home: File,
    private val agents: () -> SessionAgents,
    private val runs: () -> AgentRuns,
    private val queue: () -> SessionRunQueue,
    /** True when the live Codex account is nearly out of quota. */
    private val quotaLow: (EngineKind) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val store = LocalTaskBoardStore(context)
    val board = TaskBoard(store, now, zone)
    val memory = MemoryBook(store, now)
    val signals = SignalInbox(store, now)
    val team = TeamRoster(store, now)
    val playbook = TaskPlaybook(home)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(TaskDeskState())
    val state: StateFlow<TaskDeskState> = mutable.asStateFlow()
    @Volatile private var ready = false
    @Volatile private var paused = false
    private var ticker: Job? = null

    /** The pass or nightly job now running: its member, batch and start. */
    private data class InFlight(
        val member: String,
        val batch: List<String>,
        val startedAt: Long,
        /** The chat's last outcome before this turn: a different one means this turn ran. */
        val previousRun: String?,
        var seenActive: Boolean = false,
        @Volatile var finished: Boolean = false,
    )
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, InFlight>()
    private val sourceApps = java.util.concurrent.ConcurrentHashMap<String, String>()

    val tools = TaskTools(board, memory, team, roleOf = ::roleOf, roleChat = { member -> createChat(member) },
        addUserRule = { rule -> kotlinx.coroutines.withContext(Dispatchers.IO) { playbook.addUserRule(rule) } }, zone = zone, now = now,
        playbook = playbook, finishPass = { session, ids ->
            lock.withLock {
                val flight = inFlight[session] ?: error("No active discovery pass.")
                check(!paused && board.settings.value.discoveryOn && roleOf(session) == TaskChatRole.SCOUT) { "Discovery stopped." }
                check(ids.size == flight.batch.size && ids.toSet() == flight.batch.toSet()) { "Finish must name the exact current batch." }
                flight.finished = true
            }
        })

    fun isBackgroundSession(id: String): Boolean = roleOf(id) in setOf(TaskChatRole.SCOUT, TaskChatRole.COACH, TaskChatRole.LIBRARIAN) ||
        (!ready && sessions.sessions.value.any { it.id == id && it.title.endsWith(BACKSTAGE_SUFFIX) })

    fun pauseBackground() {
        paused = true
        scope.launch { board.editSettings { it.copy(discoveryOn = false) }; stopBackgroundTurns() }
    }

    suspend fun stopBackgroundTurns() = lock.withLock {
        team.members.value.filter { it.kind.backstage }.mapNotNull { it.sessionId }.forEach {
            queue().cancelSession(it)
            runs().stop(it)
        }
        inFlight.clear()
        publish()
    }

    suspend fun pulse() { tick() }
    fun hasBackgroundWork(): Boolean = inFlight.isNotEmpty()
    fun refreshPermissions() { if (ready) publish() }

    fun roleOf(sessionId: String): TaskChatRole {
        val member = team.bySession(sessionId) ?: return TaskChatRole.ORDINARY
        return when (member.kind) {
            MemberKind.MANAGER -> TaskChatRole.MANAGER
            MemberKind.SCOUT -> TaskChatRole.SCOUT
            MemberKind.COACH -> TaskChatRole.COACH
            MemberKind.LIBRARIAN -> TaskChatRole.LIBRARIAN
            MemberKind.ROLE -> TaskChatRole.MEMBER
        }
    }

    /** For the coordinator: why a tool may not run in this chat. */
    fun toolRefusal(sessionId: String, name: String, arguments: JsonObject): String? {
        if (paused && isBackgroundSession(sessionId)) return "Background work has stopped. Nothing ran."
        if (isBackgroundSession(sessionId) && name == "calendar" && !board.settings.value.readCalendar) return "Calendar reading is disabled in Sources."
        if (ready) return TaskRolePolicy.refusal(roleOf(sessionId), name, arguments)
        // Before the team is loaded, a backstage chat is known by its name only;
        // refusing there is the safe side of not knowing yet.
        val title = sessions.sessions.value.firstOrNull { it.id == sessionId }?.title.orEmpty()
        return if (title.endsWith(BACKSTAGE_SUFFIX)) "The task board is still starting. Nothing ran." else null
    }

    fun start() {
        scope.launch {
            try {
                board.load(); memory.load(); signals.load(); team.load()
                runCatching { playbook.seed() }
                val prior = team.members.value.associate { it.id to it.sessionId }
                team.ensureBuiltIns { member, existing ->
                    val saved = existing?.let { sessions.getSession(it) }
                    if (saved == null) createChat(member)
                    else {
                        if (!saved.hasMessages) sessions.append(dev.androidagent.core.ChatMessage(
                            UUID.randomUUID().toString(), saved.id, "system", teamContext(member), now()))
                        saved.id
                    }
                }
                if (prior[TeamRoster.SCOUT] != team.of(MemberKind.SCOUT)?.sessionId) {
                    prior.values.filterNotNull().filter { sessions.getSession(it) == null }.forEach { queue().cancelSession(it) }
                    board.editSettings { it.copy(lastPassAt = null) }
                }
                board.executor = executor
                ready = true
                launch { combine(board.tasks, team.members, memory.notes, board.settings, signals.pending) { _, _, _, _, _ -> Unit }.collect { publish() } }
                launch { sessions.sessions.collect { publish() } }
                launch { agents().tasks.collect { children ->
                    val manager = team.of(MemberKind.MANAGER)?.sessionId
                    board.syncChildren(children.filter { it.parentSessionId == manager })
                } }
                // A backstage turn can be short: watch it start rather than hope a tick sees it.
                launch { runs().sessionStates.collect { states ->
                    inFlight.forEach { (session, flight) -> if (states[session]?.active == true) flight.seenActive = true }
                } }
                ticker = launch { while (true) {
                    try { tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { mutable.value = mutable.value.copy(error = "Task discovery: ${e.message}") }
                    delay(TICK_MS)
                } }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutable.value = mutable.value.copy(error = "The task board could not start: ${error.message}")
            }
        }
    }

    private suspend fun createChat(member: TeamMember): String {
        val title = when (member.kind) {
            MemberKind.MANAGER -> "Mike · Manager"
            MemberKind.ROLE -> "${member.name} · team"
            else -> "${member.name}$BACKSTAGE_SUFFIX"
        }
        val chat = sessions.createRetainedSession(EngineKind.CODEX, title, teamContext(member))
        return chat.id
    }

    private fun teamContext(member: TeamMember): String =
        if (member.kind == MemberKind.MANAGER) "Mike's Manager chat. Talk here about your tasks, decisions and team. Execution starts only after your decision."
        else "${member.name}: ${member.role} This team chat is kept between runs; each background pass uses a fresh model context."

    private fun publish() {
        val manager = team.of(MemberKind.MANAGER)?.sessionId
        val backstage = team.members.value.filter { it.kind != MemberKind.MANAGER }.mapNotNull { it.sessionId }.toSet()
        val chats = sessions.sessions.value
        val underManager = chats.filter { it.id != manager && boardChat(it.id, chats) }.map { it.id }
        val activity = inFlight.values.associate { it.member to if (it.batch.isNotEmpty()) "Reading ${it.batch.size} new things" else "Working" }
        mutable.value = TaskDeskState(
            ready = ready,
            tasks = board.tasks.value,
            members = team.members.value,
            notes = memory.notes.value,
            settings = board.settings.value,
            pendingSignals = signals.pending.value.size,
            managerSessionId = manager,
            hiddenChats = backstage + underManager,
            activity = activity,
            sourceApps = sourceApps.toMap(),
            calendarGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            error = mutable.value.error,
        )
    }

    // ----- The user's decisions -------------------------------------------------

    private val executor = object : TaskExecutor {
        override suspend fun start(task: MikeTask, attempt: Int): TaskExecution {
            val manager = team.of(MemberKind.MANAGER)?.sessionId ?: error("The Manager's chat is missing.")
            val member = task.assignee?.let(team::member)
            val requestId = "${task.id}#$attempt"
            val result = agents().invoke(manager, buildJsonObject {
                put("mode", "start"); put("requestId", requestId)
                put("title", task.title.take(80))
                put("task", TaskPrompts.execution(task, attempt, playbook.directory.absolutePath, member))
                task.computer?.let { put("computer", it) }
                task.project?.let { put("project", it) }
            }) { true }
            val body = Json.parseToJsonElement(result.text).jsonObject
            if (!result.success) {
                val message = body["message"]?.jsonPrimitive?.contentOrNull ?: "The task chat could not start."
                val receipt = agents().tasks.value.firstOrNull { it.parentSessionId == manager && it.requestId == requestId }
                if (receipt == null || (receipt.status == "failed" && receipt.runId == null)) throw dev.androidagent.core.TaskStartRejectedException(message)
                error(message)
            }
            val child = body["task"]?.jsonObject
            member?.let { scope.launch { team.appendJournal(it.id, "Started: ${task.title}") } }
            return TaskExecution(
                requestId = requestId,
                childTaskId = child?.get("taskId")?.jsonPrimitive?.contentOrNull,
                sessionId = child?.get("sessionId")?.jsonPrimitive?.contentOrNull,
                rounds = attempt,
                startedAt = now(),
            )
        }

        override suspend fun continueWork(task: MikeTask, message: String) {
            val manager = team.of(MemberKind.MANAGER)?.sessionId ?: error("The Manager's chat is missing.")
            val child = task.execution?.childTaskId ?: error("This card has no chat to continue.")
            val result = agents().invoke(manager, buildJsonObject {
                put("mode", "message"); put("taskId", child); put("message", message); put("messageId", UUID.randomUUID().toString())
            }) { true }
            check(result.success) { Json.parseToJsonElement(result.text).jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "The follow-up was not sent." }
        }

        override suspend fun stop(task: MikeTask) {
            val manager = team.of(MemberKind.MANAGER)?.sessionId ?: return
            val child = task.execution?.childTaskId ?: return
            val result = agents().invoke(manager, buildJsonObject { put("mode", "cancel"); put("taskId", child) }) { true }
            check(result.success) { "Stop was not confirmed. Check the existing task chat." }
        }

        override suspend fun apply(task: MikeTask) {
            val action = task.action ?: error("This card has nothing to apply.")
            when (action.type) {
                TaskAction.EXCLUDE_APP -> board.editSettings { it.copy(excludedApps = it.excludedApps + action.value.trim()) }
                TaskAction.EXCLUDE_CONVERSATION -> board.editSettings { it.copy(excludedConversations = it.excludedConversations + action.value.trim()) }
                TaskAction.UNDO_SNAPSHOT -> undo(action.value)
                else -> error("Unknown action ${action.type}.")
            }
        }
    }

    /** One tap on a card. The only entry from the screen into the board's decisions. */
    fun decide(id: String, choice: String, note: String? = null, reason: dev.androidagent.core.DismissReason? = null, until: Long? = null) {
        scope.launch {
            runCatching { board.decide(id, choice, "tap", note, reason, until) }
                .onFailure { mutable.value = mutable.value.copy(error = it.message) }
        }
    }

    fun setNote(id: String, note: String?) { scope.launch { runCatching { board.setNote(id, note) } } }

    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }

    /** Undo a night: the playbook and memory as they were in [snapshotId]. The user's own rules stay. */
    suspend fun undo(snapshotId: String) {
        check(playbook.exists(snapshotId)) { "That snapshot is gone." }
        val notes = playbook.restore(snapshotId)
        memory.restore(notes)
    }

    // ----- Settings ---------------------------------------------------------------

    fun setDiscovery(on: Boolean) {
        paused = !on
        scope.launch {
            board.editSettings { it.copy(discoveryOn = on) }
            if (!on) { stopBackgroundTurns(); signals.clear() } else { backfillOnce(); tick() }
        }
    }

    fun editSettings(change: (TaskBoardSettings) -> TaskBoardSettings) { scope.launch { board.editSettings(change) } }

    fun forgetCollected() { scope.launch { signals.clear() } }
    fun liftSuppression(entry: dev.androidagent.core.TaskSuppression) { scope.launch { board.removeSuppression(entry) } }

    fun addUserRule(rule: String) { scope.launch(Dispatchers.IO) { runCatching { playbook.addUserRule(rule) } } }

    fun forgetNote(id: String) { scope.launch { memory.forget(listOf(id)) } }
    fun pinNote(id: String, pinned: Boolean) { scope.launch { runCatching { memory.setPinned(id, pinned) } } }
    fun forgetSubject(subject: String) { scope.launch { memory.forgetSubject(subject) } }

    // ----- Collecting -------------------------------------------------------------

    /** What the notification listener asks first, by package name alone, and then hands over. */
    val sink = object : dev.androidagent.core.TaskSignalSink {
        override val ready: Boolean get() = this@TaskDesk.ready
        override fun wants(app: String): Boolean {
            val settings = board.settings.value
            return ready && settings.discoveryOn && app != context.packageName && app !in settings.excludedApps
        }
        override fun onMessage(message: CapturedMessage) = this@TaskDesk.onMessage(message)
    }

    /** From the notification listener: a message as its notification shows it. No model is involved. */
    fun onMessage(message: CapturedMessage) {
        if (!ready) return
        scope.launch {
            signals.capture(message, board.settings.value, context.packageName)
            if (!sourceApps.containsKey(message.app) && sourceApps.size < 100) {
                sourceApps[message.app] = withIo { runCatching {
                    val info = context.packageManager.getApplicationInfo(message.app, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(message.app) }
                publish()
            }
        }
    }

    private fun boardChat(id: String, chats: List<ChatSession>): Boolean {
        val ours = team.sessionIds
        if (id in ours) return true
        var parent = chats.firstOrNull { it.id == id }?.parentSessionId
        var depth = 0
        while (parent != null && depth++ < 6) {
            if (parent in ours) return true
            parent = chats.firstOrNull { it.id == parent }?.parentSessionId
        }
        return false
    }

    /** The user's own chats that went quiet become one signal each: what was said since the last look. */
    private suspend fun collectQuietChats() {
        val settings = board.settings.value
        if (!settings.readChats) return
        val clock = now()
        val chats = sessions.sessions.value
        val cursors = settings.chatCursors.toMutableMap()
        var changed = false
        chats.filter { it.hasMessages && !boardChat(it.id, chats) }.forEach { chat ->
            val seen = cursors[chat.id]
            if (seen == null) { cursors[chat.id] = chat.updatedAt; changed = true; return@forEach }
            if (chat.updatedAt <= seen || clock - chat.updatedAt < QUIET_CHAT_MS || runs().stateOf(chat.id)?.active == true) return@forEach
            val text = transcript(chat.id, since = seen)
            if (text.isNotBlank()) {
                signals.add(TaskSignal(id = SignalInbox.idOf("chat", chat.id, chat.updatedAt), source = "chat", conversation = chat.title,
                    text = text, at = chat.updatedAt, capturedAt = clock, sessionId = chat.id))
            }
            cursors[chat.id] = chat.updatedAt; changed = true
        }
        if (changed) board.editSettings { it.copy(chatCursors = cursors.filterKeys { id -> chats.any { it.id == id } }) }
    }

    /** The first time discovery is turned on: one signal per recent chat, so the board starts with what is already open. */
    private suspend fun backfillOnce() {
        val settings = board.settings.value
        if (settings.backfillDone || !settings.readChats) return
        val clock = now()
        val chats = sessions.sessions.value
        chats.filter { it.hasMessages && !boardChat(it.id, chats) && clock - it.updatedAt < BACKFILL_MS }
            .sortedByDescending { it.updatedAt }.take(BACKFILL_CHATS).forEach { chat ->
                val text = transcript(chat.id, since = 0, lastMessages = 20)
                if (text.isNotBlank()) signals.add(TaskSignal(id = SignalInbox.idOf("backfill", chat.id), source = "backfill",
                    conversation = chat.title, text = text, at = chat.updatedAt, capturedAt = clock, sessionId = chat.id))
            }
        board.editSettings { s -> s.copy(backfillDone = true, chatCursors = s.chatCursors + chats.associate { it.id to it.updatedAt }) }
    }

    private suspend fun transcript(sessionId: String, since: Long, lastMessages: Int = 30): String {
        // A chat's history is read from disk the first time; keep that off the main thread.
        val messages = kotlinx.coroutines.withContext(Dispatchers.IO) { sessions.messages(sessionId).first() }
            .filter { it.createdAt > since && it.role in setOf("user", "assistant") && it.text.isNotBlank() }
            .takeLast(lastMessages)
        return messages.joinToString("\n") { (if (it.role == "user") "User: " else "Mike: ") + it.text.take(600) }.takeLast(SignalInbox.MAX_TEXT)
    }

    // ----- Thinking in small batches ---------------------------------------------

    private suspend fun tick() = lock.withLock {
        if (!ready) return@withLock
        board.sweep()
        memory.emptyTrash()
        val settings = board.settings.value
        signals.prune(settings.discoveryOn)
        settleInFlight()
        if (!settings.discoveryOn || paused) return@withLock
        backfillOnce()
        collectQuietChats()
        maybePass()
        maybeNightly()
        publish()
    }

    private fun busy(member: TeamMember?): Boolean = member?.sessionId?.let { id ->
        inFlight.containsKey(id) || runs().stateOf(id)?.active == true
    } ?: true

    private suspend fun maybePass() {
        val scout = team.of(MemberKind.SCOUT) ?: return
        val clock = now()
        var settings = board.settings.value
        val today = Instant.ofEpochMilli(clock).atZone(zone()).toLocalDate().toString()
        if (settings.passDay != today) settings = board.editSettings { it.copy(passDay = today, passesToday = 0) }
        val calendarDue = settings.readCalendar && clock - (settings.lastCalendarCheckAt ?: 0) > CALENDAR_EVERY_MS
        if (calendarDue && signals.pending.value.none { it.source == "calendar" }) {
            signals.add(TaskSignal(id = SignalInbox.idOf("calendar", clock / CALENDAR_EVERY_MS), source = "calendar",
                text = "Time to look at the next 48 hours of the calendar.", at = clock, capturedAt = clock))
        }
        val plan = DiscoverySchedule.plan(signals.pending.value, clock, settings.lastPassAt, settings.passesToday, settings.dailyPassCap, busy(scout))
        if (!plan.due || quotaLow(settings.passEngine)) return
        val session = scout.sessionId ?: return
        val prompt = TaskPrompts.scoutPass(plan.batch, clock, zone(), plan.batch.any { it.source == "calendar" }, playbook.directory.absolutePath)
        inFlight[session] = InFlight(scout.id, plan.batch.map { it.id }, clock, runs().outcomeOf(session)?.runId)
        board.editSettings { it.copy(lastPassAt = clock, passesToday = it.passesToday + 1,
            lastCalendarCheckAt = if (plan.batch.any { s -> s.source == "calendar" }) clock else it.lastCalendarCheckAt) }
        submit(session, prompt, "Discovery pass · ${plan.batch.size} new", settings.passEngine, settings.passModel, settings.passEffort)
    }

    private suspend fun maybeNightly() {
        val clock = now()
        val hour = Instant.ofEpochMilli(clock).atZone(zone()).hour
        val settings = board.settings.value
        val night = hour in 2..6
        val coach = team.of(MemberKind.COACH)
        val librarian = team.of(MemberKind.LIBRARIAN)
        val lastReview = settings.lastReviewAt ?: 0
        if (coach != null && !busy(coach) && ((night && clock - lastReview > 20 * HOUR) || clock - lastReview > 36 * HOUR)) {
            val recent = board.tasks.value.filter { clock - it.updatedAt < 7 * 24 * HOUR && it.decision != null }
            if (recent.isNotEmpty() && !quotaLow(settings.reviewEngine)) {
                val snapshot = withIo { playbook.snapshot("review", memory.notes.value, clock) }
                val session = coach.sessionId ?: return
                inFlight[session] = InFlight(coach.id, emptyList(), clock, runs().outcomeOf(session)?.runId)
                submit(session, TaskPrompts.coachReview(recent.take(80), clock, zone(), snapshot, playbook.directory.absolutePath),
                    "Nightly review", settings.reviewEngine, settings.reviewModel, settings.reviewEffort)
                board.editSettings { it.copy(lastReviewAt = clock) }
                return
            }
        }
        val lastUpkeep = settings.lastUpkeepAt ?: 0
        if (librarian != null && !busy(librarian) && !busy(coach) && ((night && clock - lastUpkeep > 20 * HOUR) || clock - lastUpkeep > 36 * HOUR)) {
            val deep = clock - (settings.lastDeepUpkeepAt ?: 0) > 6 * 24 * HOUR + 12 * HOUR
            val live = memory.live
            if (live.isNotEmpty() && !quotaLow(settings.reviewEngine)) {
                val snapshot = withIo { playbook.snapshot(if (deep) "deep" else "upkeep", memory.notes.value, clock) }
                val session = librarian.sessionId ?: return
                inFlight[session] = InFlight(librarian.id, emptyList(), clock, runs().outcomeOf(session)?.runId)
                val open = board.tasks.value.filter { !it.status.closed }.map { it.id }.toSet()
                val candidates = live.sortedWith(compareBy<dev.androidagent.core.MemoryNote> { it.pinned || it.linkedTaskIds.any(open::contains) }
                    .thenBy { it.useCount }.thenBy { it.lastUsedAt ?: it.at }).take(400)
                submit(session, TaskPrompts.librarianUpkeep(deep, candidates, memory.pressure(), clock, zone(),
                    snapshot, playbook.directory.absolutePath, open), if (deep) "Weekly memory upkeep" else "Nightly memory upkeep",
                    settings.reviewEngine, settings.reviewModel, settings.reviewEffort)
                board.editSettings { it.copy(lastUpkeepAt = clock, lastDeepUpkeepAt = if (deep) clock else it.lastDeepUpkeepAt) }
            }
        }
    }

    /** A backstage turn: a fresh thread each time, quoted input, and a deadline so a stale pass never runs late. */
    private suspend fun submit(session: String, prompt: String, label: String, engine: EngineKind, model: String?, effort: String?) {
        check(!paused && board.settings.value.discoveryOn) { "Discovery stopped." }
        sessions.clearThread(session)
        val skill = when (roleOf(session)) {
            TaskChatRole.SCOUT -> "task-discovery"
            TaskChatRole.COACH -> "task-review"
            TaskChatRole.LIBRARIAN -> "memory-upkeep"
            else -> error("Only backstage team members run background passes.")
        }
        val guidance = withIo { context.assets.open("agent_stack/skills/$skill/SKILL.md").bufferedReader().use { it.readText() } }
        check(!paused && board.settings.value.discoveryOn) { "Discovery stopped." }
        queue().submit(QueuedTurn(
            sessionId = session, prompt = "$guidance\n\n$prompt", model = model, effort = effort.takeIf { model != null }, engine = engine,
            displayPrompt = label, agentContext = true, validUntil = now() + PASS_DEADLINE_MS,
        ))
    }

    /** A finished pass hands its batch back: deleted when it succeeded, kept for another try when it did not. */
    private suspend fun settleInFlight() {
        val clock = now()
        inFlight.entries.toList().forEach { (session, flight) ->
            val live = runs().stateOf(session)
            if (live?.active == true) { flight.seenActive = true; return@forEach }
            val outcome = runs().outcomeOf(session)
            val ran = outcome != null && outcome.runId != flight.previousRun
            val started = flight.seenActive || ran || clock - flight.startedAt > PASS_DEADLINE_MS
            if (!started) return@forEach
            val ok = ran && outcome?.phase == RunPhase.IDLE && outcome.status == "Ready" && !outcome.outcomeUnknown &&
                (flight.batch.isEmpty() || flight.finished)
            if (flight.batch.isNotEmpty()) {
                if (ok) signals.processed(flight.batch) else signals.failed(flight.batch)
            }
            if (!ok) reportError("Background work did not finish: ${outcome?.status ?: "the queued pass expired"}. Input stays available for another pass.")
            team.appendJournal(flight.member, (if (ok) "Done " else "Did not finish ") + Instant.ofEpochMilli(clock).atZone(zone()).toLocalTime().withNano(0))
            inFlight.remove(session)
        }
    }

    private suspend fun <T> withIo(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /** The words before the user's own in the Manager's or a member's chat, or null for any other chat. */
    fun ownerContext(sessionId: String): String? {
        if (!ready) return null
        val member = team.bySession(sessionId)?.takeIf { it.kind == MemberKind.MANAGER || it.kind == MemberKind.ROLE } ?: return null
        val mine = board.tasks.value.filter { member.kind == MemberKind.MANAGER || it.assignee == member.id }
        return TaskPrompts.ownerContext(member,
            waiting = mine.filter { it.status.undecided },
            running = mine.filter { it.status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING, TaskStatus.REVIEW) },
            zone = zone(), playbook = playbook.directory.absolutePath)
    }

    companion object {
        private const val TICK_MS = 60_000L
        private const val HOUR = 3_600_000L
        private const val QUIET_CHAT_MS = 15 * 60_000L
        private const val BACKFILL_MS = 14 * 24 * HOUR
        private const val BACKFILL_CHATS = 25
        private const val CALENDAR_EVERY_MS = 6 * HOUR
        private const val PASS_DEADLINE_MS = 30 * 60_000L
        private const val BACKSTAGE_SUFFIX = " · backstage"
    }
}
