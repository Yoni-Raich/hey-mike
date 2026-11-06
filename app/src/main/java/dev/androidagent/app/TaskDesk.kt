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
import dev.androidagent.core.CalendarSignalBuffer
import dev.androidagent.core.ChatSession
import dev.androidagent.core.DiscoverySchedule
import dev.androidagent.core.EngineKind
import dev.androidagent.core.MemberKind
import dev.androidagent.core.MemoryBook
import dev.androidagent.core.proactive.ManagerConnections
import dev.androidagent.core.MikeTask
import dev.androidagent.core.QueuedTurn
import dev.androidagent.core.RunPhase
import dev.androidagent.core.SessionAgents
import dev.androidagent.core.SessionRunQueue
import dev.androidagent.core.SessionStore
import dev.androidagent.core.SignalInbox
import dev.androidagent.core.SignalFilter
import dev.androidagent.core.SignalInboxFullException
import dev.androidagent.core.SignalRows
import dev.androidagent.core.SignalLane
import dev.androidagent.core.SourceStat
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
import dev.androidagent.core.proactive.AccountConnector
import dev.androidagent.core.proactive.AlertBook
import dev.androidagent.core.proactive.AlertPrefs
import dev.androidagent.core.proactive.ConnectorStatus
import dev.androidagent.core.proactive.ContextBroker
import dev.androidagent.core.proactive.FeedbackAction
import dev.androidagent.core.proactive.LearnRequest
import dev.androidagent.core.proactive.MikeAlert
import dev.androidagent.core.proactive.OwnerProfile
import dev.androidagent.core.proactive.OwnerProfileStore
import dev.androidagent.core.proactive.ProactiveDesk
import dev.androidagent.core.proactive.ProactiveTools
import dev.androidagent.core.proactive.ProfileKind

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
    val nextCheckAt: Long? = null,
    val waitReason: String = "starting",
    val events: List<dev.androidagent.core.TaskEvent> = emptyList(),
    val recentRuns: List<dev.androidagent.core.TaskRun> = emptyList(),
    val maintenanceChanges: Map<String, dev.androidagent.core.TaskChangeSet> = emptyMap(),
    val blockedSignals: Int = 0,
    val briefingWait: String? = null,
    val queueRows: List<TaskSignal> = emptyList(),
    val scanChats: List<ChatSession> = emptyList(),
    /** What matters to the user, and how Mike may reach them. */
    val profile: OwnerProfile = OwnerProfile(),
    val recentAlerts: List<MikeAlert> = emptyList(),
    /** Connector id to its last known state, for the Connections cards. */
    val connections: Map<String, ConnectorStatus> = emptyMap(),
    /** Connectors of the user's AI accounts (claude.ai Gmail, ...), as the engines last reported them. */
    val accounts: List<AccountConnector> = emptyList(),
    /** False until the engines were asked once in this process. */
    val accountsChecked: Boolean = false,
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
    private val situationStore = dev.androidagent.core.SharedSituationStore(File(context.filesDir, "task-situation-v1.json"))
    val board = TaskBoard(store, now, zone)
    val memory = MemoryBook(store, now)
    val signals = SignalInbox(store, now)
    private val calendarChanges = CalendarSignalBuffer()
    val team = TeamRoster(store, now)
    /** Read-only context connections: the app's own and the engines' account connectors. */
    val connections = ManagerConnections({ id -> board.settings.value.connectors[id] == true }, ::contextAllowed)
    private val alertNotifier = MikeAlertNotifier(context, managerChat = { team.of(MemberKind.MANAGER)?.sessionId },
        urgentSound = { proactive.profile.profile.alerts.urgentSound })
    val proactive: ProactiveDesk = ProactiveDesk(
        OwnerProfileStore(File(context.filesDir, "owner-profile-v1.json"), now),
        AlertBook(File(context.filesDir, "mike-alerts-v1.json"), now),
        alertNotifier,
        ContextBroker({ connections.connectors }, { id -> board.settings.value.connectors[id] == true },
            ::contextAllowed, context.packageName),
        zone, now)
    /** The rows of each running Scout pass, so an alert or context call can name only its own input. */
    private val passRows = java.util.concurrent.ConcurrentHashMap<String, Map<String, TaskSignal>>()
    private fun proactiveTools(runId: String?, learn: ((LearnRequest) -> Unit)?) =
        ProactiveTools(proactive,
            signal = { session, id -> passRows[session]?.get(id)?.takeIf { inFlight[session]?.batch?.contains(id) == true } },
            card = { id -> board.task(id)?.takeIf(::sourceAllowed) }, runId = runId,
            learn = learn ?: { r -> proactive.profile.learn(r.kind, r.key, r.label, r.weight, r.why, r.evidence, r.runId); Unit },
            zone = zone, now = now)
    init { alertNotifier.ensureChannels() }
    @Volatile private var activeRules: String? = null
    private fun rootBook() = TaskPlaybook(home, allowedSource = ::sourceAllowed)
    val playbook: TaskPlaybook get() = activeRules?.let { rootBook().generation(it) } ?: rootBook()
    private val lock = Mutex()
    private val situationRefreshLock = Mutex()
    private val mutable = MutableStateFlow(TaskDeskState())
    val state: StateFlow<TaskDeskState> = mutable.asStateFlow()
    @Volatile private var ready = false
    @Volatile private var paused = false
    @Volatile private var managerReviewSession: String? = null
    private var ticker: Job? = null

    /** The pass or nightly job now running: its member, batch and start. */
    private data class InFlight(
        val member: String,
        val batch: List<String>,
        val startedAt: Long,
        /** The chat's last outcome before this turn: a different one means this turn ran. */
        val previousRun: String?,
        val policyRevision: Long = 0,
        val run: dev.androidagent.core.TaskRun? = null,
        val data: dev.androidagent.core.StagedTaskData? = null,
        val tools: dev.androidagent.core.TaskToolHandler? = null,
        val ruleBook: TaskPlaybook? = null,
        val ruleBase: String? = null,
        val ruleFingerprint: String? = null,
        val contextSources: List<dev.androidagent.core.TaskSourceRef> = emptyList(),
        val reviewEvents: List<String> = emptyList(),
        val briefingTaskIds: List<String> = emptyList(),
        val coachDispositions: MutableMap<String, dev.androidagent.core.CoachDisposition> = java.util.concurrent.ConcurrentHashMap(),
        /** Profile entries the Coach learned in this run, applied only when the run commits. */
        val learned: MutableList<LearnRequest> = java.util.concurrent.CopyOnWriteArrayList(),
        var seenActive: Boolean = false,
        @Volatile var finished: Boolean = false,
    )
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, InFlight>()
    private var scoutFailure: Pair<Set<String>, String>? = null
    private val sourceApps = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sourceRefs = java.util.concurrent.ConcurrentHashMap<String, dev.androidagent.core.TaskSourceRef>()
    @Volatile private var forgottenNotes: Set<String> = emptySet()
    @Volatile private var runHistory: List<dev.androidagent.core.TaskRun> = emptyList()
    @Volatile private var maintenanceChanges: Map<String, dev.androidagent.core.TaskChangeSet> = emptyMap()
    @Volatile private var reviewedManager: Set<String> = emptySet()
    @Volatile private var reviewedCoach: Set<String> = emptySet()
    @Volatile private var legacySignals: Int = 0
    @Volatile private var userRulesContext: String = ""
    private suspend fun loadRunHistory() {
        runHistory = store.loadRuns().takeLast(40)
        maintenanceChanges = runHistory.mapNotNull { run -> store.changes(run.id)?.let { run.id to it } }.toMap()
    }

    private fun sourceAllowed(ref: dev.androidagent.core.TaskSourceRef): Boolean {
        if (ref.source == "location" && !locationReadable()) return false
        val direct = dev.androidagent.core.TaskSourcePolicy.allows(ref, board.settings.value) && (ref.noteId == null || ref.noteId !in forgottenNotes)
        if(!direct) return false
        val parent = ref.sessionId?.let { chat -> board.tasks.value.firstOrNull { it.execution?.sessionId == chat } }
        return parent?.sourceRefs.orEmpty().all { dev.androidagent.core.TaskSourcePolicy.allows(it, board.settings.value) && (it.noteId == null || it.noteId !in forgottenNotes) }
    }
    private fun ancestry(ref: dev.androidagent.core.TaskSourceRef): List<dev.androidagent.core.TaskSourceRef> = listOf(ref) +
        board.tasks.value.firstOrNull { it.execution?.sessionId == ref.sessionId && ref.sessionId != null }?.sourceRefs.orEmpty()
    private fun sourceAllowed(task: MikeTask): Boolean = task.sourceRefs.ifEmpty {
        task.evidence.map { dev.androidagent.core.TaskSourceRef(it.source, it.app, it.conversation) }
    }.all(::sourceAllowed)
    private fun sourceAllowed(note: dev.androidagent.core.MemoryNote): Boolean = note.sourceRefs.ifEmpty {
        listOf(dev.androidagent.core.TaskSourceRef(note.source))
    }.all(::sourceAllowed)

    private fun inputSources(session: String, ids: List<String>): List<dev.androidagent.core.TaskSourceRef> {
        val flight = inFlight[session]
        if (isBackgroundSession(session)) {
            if (flight == null) return emptyList()
            check(flight.policyRevision == board.settings.value.policyRevision) { "Sources changed; this pass is no longer allowed." }
            require(ids.all { it in flight.batch || it in flight.run?.eventIds.orEmpty() }) { "Only source IDs from this pass may be used." }
            return ids.flatMap { id ->
                if (id in flight.batch) ancestry(sourceRefs[id] ?: error("The source reference is missing."))
                else {
                    val event = board.events.value.firstOrNull { it.id == id } ?: error("The review event is missing.")
                    val taskRefs = board.tasks.value.firstOrNull { it.id == event.taskId }?.sourceRefs.orEmpty()
                    (event.sourceRefs + taskRefs + dev.androidagent.core.TaskSourceRef(if (event.actor == "user") "user" else "app", verified = true, eventId = id)).also {
                        require(it.all(::sourceAllowed)) { "A review event uses an excluded source." }
                    }
                }
            }.distinct()
        }
        require(ids.isEmpty()) { "Only an active background pass may cite signal IDs." }
        return listOf(dev.androidagent.core.TaskSourceRef("chat", sessionId = session, verified = true))
    }

    private val liveTools = TaskTools(board, memory, team, roleOf = ::roleOf, roleChat = { member -> createChat(member) },
        addUserRule = { rule -> kotlinx.coroutines.withContext(Dispatchers.IO) {
            playbook.addUserRule(rule); userRulesContext = playbook.userRules().take(4_000)
        } }, zone = zone, now = now,
        playbook = playbook, finishPass = { session, ids ->
            lock.withLock {
                val flight = inFlight[session] ?: error("No active discovery pass.")
                check(!paused && board.settings.value.discoveryOn && roleOf(session) == TaskChatRole.SCOUT) { "Discovery stopped." }
                check(ids.size == flight.batch.size && ids.toSet() == flight.batch.toSet()) { "Finish must name the exact current batch." }
                flight.finished = true
            }
        }, permit = { session -> if (isBackgroundSession(session)) dev.androidagent.core.TaskWritePermit(
            inFlight[session]?.policyRevision ?: board.settings.value.policyRevision) else null },
        resolveSources = ::inputSources, allowedSource = ::sourceAllowed, playbookProvider = { playbook },
        situation = ::situationSnapshot, correctSituation = { field, value, until ->
            situationStore.correct(field, value, now(), until); situationSnapshot()
        }, observeSituation = { observation -> situationStore.observe(observation, now()); situationSnapshot() },
        refreshLocation = {
            dev.androidagent.devicetools.AndroidCapabilityPlatform(context).querySituationLocation().also { situationStore.recordLocation(it) }
        }, runtimeStatus = { buildJsonObject {
            val settings = board.settings.value
            val today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()
            put("discoveryOn", settings.discoveryOn); put("runtimePaused", paused)
            put("waitReason", mutable.value.waitReason)
            settings.pauseReason?.let { put("pauseReason", it) }; settings.pausedAt?.let { put("pausedAt", it) }
            put("modelUsageGate", quotaLow(settings.passEngine)); put("engine", settings.passEngine.name)
            put("passesToday", if (settings.passDay == today) settings.passesToday else 0); put("dailyPassCap", settings.dailyPassCap)
            put("pendingInputs", signals.pending.value.size)
            mutable.value.error?.let { put("error", it.take(400)) }
        } }, proactive = proactiveTools(null, null), managerMessages = {
            val manager = team.of(MemberKind.MANAGER)?.sessionId ?: error("The Manager's chat is missing.")
            withIo { sessions.messages(manager).first() }
        })
    val tools = dev.androidagent.core.TaskToolHandler { session, args, user ->
        val flight = inFlight[session]
        if(isBackgroundSession(session) && (paused || !board.settings.value.discoveryOn || (!user && flight == null)))
            dev.androidagent.core.ToolResult("This background pass is no longer active. Nothing was saved.", success = false)
        else (flight?.tools ?: liveTools).invoke(session, args, user)
    }
    private val preparedTurns = java.util.concurrent.ConcurrentHashMap<String, QueuedTurn>()

    suspend fun prepareTurn(turn: QueuedTurn): QueuedTurn? {
        val id = turn.boardJobId ?: return turn
        val flight = inFlight[turn.sessionId]
        val preparedRun = flight?.run
        if(flight?.run?.id != id || paused || !board.settings.value.discoveryOn || flight.policyRevision != board.settings.value.policyRevision) {
            preparedTurns.remove(id)
            if(flight?.run?.id == id) scope.launch { lock.withLock {
                if(inFlight[turn.sessionId]?.run?.id == id) {
                    store.saveRun(preparedRun!!.copy(state = "cancelled", error = "The queued source policy changed."))
                    withIo { flight.ruleBook?.discardStage() }; inFlight.remove(turn.sessionId); publish()
                }
            } }
            return null
        }
        return preparedTurns.remove(id)
    }

    private suspend fun beginFlight(member: TeamMember, batch: List<String>, clock: Long, id: String = UUID.randomUUID().toString(),
        eventIds: List<String> = emptyList(), contextRefs: List<dev.androidagent.core.TaskSourceRef>? = null,
        deep: Boolean = false, nextCursor: Int? = null, inputVersions: Map<String, Long> = emptyMap()): InFlight {
        val session = member.sessionId ?: error("This team chat is missing.")
        val run = dev.androidagent.core.TaskRun(id, session, member.kind.wire, board.settings.value.policyRevision, batch, clock,
            eventIds = eventIds, deepUpkeep = deep, nextUpkeepCursor = nextCursor,
            inputVersions = inputVersions)
        val data = dev.androidagent.core.StagedTaskData.create(store)
        val stagedBoard = TaskBoard(data.store, now, zone).apply { load() }
        val stagedMemory = MemoryBook(data.store, now).apply { load() }
        val stagedTeam = TeamRoster(data.store, now).apply { load() }
        val refs = contextRefs ?: if(batch.isNotEmpty()) batch.flatMap { ancestry(sourceRefs[it] ?: error("A source reference is missing.")) }.distinct()
            else board.tasks.value.filter(::sourceAllowed).flatMap { it.sourceRefs }.distinct()
        val book = withIo { playbook.fork(id, refs.ifEmpty { listOf(dev.androidagent.core.TaskSourceRef("user", verified = true)) }) }
        val dispositions = java.util.concurrent.ConcurrentHashMap<String, dev.androidagent.core.CoachDisposition>()
        val learned = java.util.concurrent.CopyOnWriteArrayList<LearnRequest>()
        val handler = TaskTools(stagedBoard, stagedMemory, stagedTeam, roleOf = ::roleOf,
            roleChat = { role ->
                check(roleOf(session) == TaskChatRole.COORDINATOR) { "Only the Manager review organizes roles." }
                "pending:${role.id}"
            }, playbook = book,
            finishPass = { chat, ids ->
                val flight = inFlight[chat] ?: error("No current pass.")
                check(flight.policyRevision == board.settings.value.policyRevision && ids.toSet() == flight.batch.toSet()) { "This pass changed or its input is incomplete." }
                flight.finished = true
            }, permit = { dev.androidagent.core.TaskWritePermit(run.policyRevision) },
            resolveSources = ::inputSources, allowedSource = ::sourceAllowed, now = now, zone = zone, reportRunId = run.id,
            reviewEvidence = eventIds.toSet(), recordCoachDisposition = { decision -> dispositions[decision.eventId] = decision },
            situation = ::situationSnapshot, proactive = proactiveTools(run.id) { learned += it })
        store.saveRun(run)
        return InFlight(member.id, batch, clock, runs().outcomeOf(session)?.runId, run.policyRevision,
            run, data, handler, book, activeRules, withIo { book.fingerprint() }, refs, coachDispositions = dispositions, learned = learned)
    }

    fun isBackgroundSession(id: String): Boolean = roleOf(id) in setOf(TaskChatRole.SCOUT, TaskChatRole.COACH, TaskChatRole.LIBRARIAN, TaskChatRole.COORDINATOR) ||
        (!ready && sessions.sessions.value.any { it.id == id && it.title.endsWith(BACKSTAGE_SUFFIX) })

    fun pauseBackground(reason: String = "global_stop") {
        paused = true
        scope.launch { board.editSettings { it.copy(discoveryOn = false, pauseReason = reason.take(80), pausedAt = now()) }; stopBackgroundTurns() }
    }

    suspend fun stopBackgroundTurns() = lock.withLock {
        (team.members.value.filter { it.kind.backstage }.mapNotNull { it.sessionId } + listOfNotNull(managerReviewSession)).forEach {
            queue().cancelSession(it)
            runs().stop(it)
        }
        inFlight.values.forEach { flight ->
            flight.run?.let { store.saveRun(it.copy(state = "cancelled", error = "Stopped or sources changed.")); preparedTurns.remove(it.id) }
            withIo { flight.ruleBook?.discardStage() }
        }
        inFlight.clear()
        publish()
    }

    suspend fun pulse() { tick() }
    private fun currentCalendarProfile(): String? = try {
        val users = context.getSystemService(android.content.Context.USER_SERVICE) as? android.os.UserManager
        if (users == null || (if (android.os.Build.VERSION.SDK_INT >= 33) users.isProfile else !users.isSystemUser)) null
        else users.getSerialNumberForUser(android.os.Process.myUserHandle()).takeIf { it >= 0 }?.let { "current_android_user:$it" }
    } catch (_: Exception) { null }
    suspend fun refreshSituation() = kotlinx.coroutines.withContext(Dispatchers.IO) { situationRefreshLock.withLock {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val enabled = board.settings.value.readCalendar
        val policyRevision = board.settings.value.policyRevision
        val clock = now()
        val calendar = if (enabled && granted) dev.androidagent.devicetools.AndroidCapabilityPlatform(context)
            .querySituationCalendar(clock, clock + 7 * 86_400_000L, 50, clock) else null
        val places = dev.androidagent.core.SavedPlaces.read(dev.androidagent.workspace.WorkspaceSeeder.preferencesFile(home))
        val stillGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        situationStore.refresh(board.tasks.value, now(), zone(), board.settings.value.readCalendar, stillGranted, places,
            calendar = calendar?.takeIf { enabled && board.settings.value.readCalendar && stillGranted &&
                policyRevision == board.settings.value.policyRevision && it.profile == currentCalendarProfile() }, allowed = ::sourceAllowed)
        Unit
    } }
    private fun situationSnapshot(): dev.androidagent.core.SharedSituation {
      val profile = currentCalendarProfile()
      return situationStore.snapshot(now(), zone(),
        board.tasks.value.filter { !it.status.closed && sourceAllowed(it) }.map { it.id }.toSet(),
        profile != null && board.settings.value.readCalendar && androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED, profile,
        locationAllowed = locationReadable(),
        locationBootCount = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 },
        elapsedNowMs = android.os.SystemClock.elapsedRealtime(),
        allowedSource = ::sourceAllowed)
    }
    private fun locationReadable(): Boolean = currentCalendarProfile() != null && (androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
        runCatching { (context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager)?.isLocationEnabled == true }.getOrDefault(false)
    /** Local scheduled checks keep working when model quota or network is unavailable. */
    suspend fun pulseFollowups() = lock.withLock {
        if (!ready || paused || !board.settings.value.discoveryOn) return@withLock
        board.sweep()
        deliverBriefings()
        prepareDueChecks()
        deliverBriefings()
        publish()
    }
    fun hasBackgroundWork(): Boolean = inFlight.isNotEmpty()
    fun refreshPermissions() { if (ready) publish() }

    fun roleOf(sessionId: String): TaskChatRole {
        if(sessionId == managerReviewSession) return TaskChatRole.COORDINATOR
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
                legacySignals = store.quarantineLegacySignals(now())
                signals.refresh()
                forgottenNotes = store.forgottenNoteIds()
                withIo { rootBook().eraseForgotten(forgottenNotes) }
                loadRunHistory()
                reviewedManager = store.consumedEvents("manager") + board.settings.value.managerReviewedEvents
                reviewedCoach = store.consumedEvents("coach") + board.settings.value.coachReviewedEvents
                sourceRefs.putAll(store.loadSourceRefs())
                signals.pending.value.forEach { sourceRefs.putIfAbsent(it.id, dev.androidagent.core.TaskSourcePolicy.of(it)) }
                activeRules = store.activeGeneration()
                if(activeRules == null) {
                    val initial = "initial-${UUID.randomUUID()}"
                    val book = withIo { rootBook().fork(initial, emptyList()) }
                    val generation = withIo { book.publishGeneration(initial) }
                    activeRules = store.initializeGeneration(generation)
                }
                withIo { rootBook().installAdapter() }
                userRulesContext = withIo { playbook.userRules().take(4_000) }
                store.loadRuns().filter { it.state == "running" }.forEach { store.saveRun(it.copy(state = "retryable", error = "The app restarted; staged output was not applied.")) }
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
                val reviewId = board.settings.value.managerReviewSessionId
                managerReviewSession = if(reviewId != null && sessions.getSession(reviewId) != null) reviewId else {
                    sessions.createRetainedSession(EngineKind.CODEX, "Mike · board-review", "Internal read-only board organization. Never execute tasks.").id
                        .also { id -> board.editSettings { it.copy(managerReviewSessionId = id) } }
                }
                (team.members.value.filter { it.kind.backstage }.mapNotNull { it.sessionId } + listOfNotNull(managerReviewSession))
                    .forEach { queue().cancelSession(it) } // Legacy queued raw prompts must not bypass fresh job preparation.
                team.repairRoleChats(::createChat)
                // Start at the upgrade boundary. Existing chat text stays in sessions.db,
                // but historical fragments do not become new work again.
                val feedBoundary = sessions.latestMessageSequence()
                val start = board.settings.value.feedStartAt ?: now().also { stamp ->
                    board.editSettings { it.copy(feedStartAt = stamp, feedStartSequence = feedBoundary, backfillDone = true, chatCursors = emptyMap()) }
                }
                signals.migrate(start) { id -> board.tasks.value.any { it.execution?.sessionId == id } }
                signals.pending.value.forEach { sourceRefs[it.id] = dev.androidagent.core.TaskSourcePolicy.of(it) }
                if (prior[TeamRoster.SCOUT] != team.of(MemberKind.SCOUT)?.sessionId) {
                    prior.values.filterNotNull().filter { sessions.getSession(it) == null }.forEach { queue().cancelSession(it) }
                    board.editSettings { it.copy(lastPassAt = null) }
                }
                board.executor = executor
                ready = true
                launch {
                    var revision = board.settings.value.policyRevision
                    board.settings.collect { settings ->
                        if (settings.policyRevision != revision) {
                            revision = settings.policyRevision
                            stopBackgroundTurns()
                        }
                    }
                }
                launch { combine(board.tasks, team.members, memory.notes, board.settings, signals.pending) { _, _, _, _, _ -> Unit }.collect { publish() } }
                launch { combine(board.tasks, board.settings) { _, _ -> Unit }.collect { refreshSituation() } }
                launch { while (true) { refreshSituation(); delay(TICK_MS) } }
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
        scoutFailure?.let { (ids, message) ->
            if (signals.pending.value.none { it.id in ids }) {
                // A retry already consumed these inputs. Preserve any unrelated error.
                if (mutable.value.error == message) mutable.value = mutable.value.copy(error = null)
                scoutFailure = null
            }
        }
        val manager = team.of(MemberKind.MANAGER)?.sessionId
        val backstage = team.members.value.filter { it.kind != MemberKind.MANAGER }.mapNotNull { it.sessionId }.toSet()
        val chats = sessions.sessions.value
        val underManager = chats.filter { it.id != manager && boardChat(it.id, chats) }.map { it.id }
        val activity = inFlight.values.associate { it.member to if (it.batch.isNotEmpty()) "Reading ${it.batch.size} new things" else "Working" }
        val settings = board.settings.value
        val allowed = signals.pending.value.filter { sourceAllowed(dev.androidagent.core.TaskSourcePolicy.of(it)) }
        val plan = DiscoverySchedule.plan(allowed, now(),
            settings.lastPassAt, if (settings.passDay == Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()) settings.passesToday else 0,
            settings.dailyPassCap, inFlight.isNotEmpty(), backgroundPassesToday = if (settings.passDay == Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()) settings.backgroundPassesToday else 0)
        val today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()
        val reason = when {
            !settings.discoveryOn || paused -> "off"
            inFlight.isNotEmpty() -> "running"
            quotaLow(settings.passEngine) -> "quota"
            settings.passDay == today && settings.passesToday >= settings.dailyPassCap -> "cap"
            plan.backgroundLimited -> "background_cap"
            signals.pending.value.isEmpty() -> if(legacySignals > 0) "excluded" else "idle"
            allowed.isEmpty() -> "excluded"
            signals.pending.value.size >= SignalInbox.MAX_PENDING -> "backpressure"
            else -> "waiting"
        }
        val checkDue = dev.androidagent.core.SituationAttention.plan(board.tasks.value.filter(::sourceAllowed), situationSnapshot(), now()).taskIds.isNotEmpty()
        val briefingWait = if(board.tasks.value.any { sourceAllowed(it) && dev.androidagent.core.TaskFollowThrough.needsBrief(it, now()) }) when {
            !settings.discoveryOn || paused -> "off"
            checkDue -> "pending"
            quotaLow(settings.passEngine) -> "quota"
            settings.managerReviewDay == today && settings.managerReviewsToday >= 4 -> "cap"
            now() - (settings.lastManagerReviewAt ?: 0) < 30 * 60_000 -> "interval"
            else -> "pending"
        } else null
        mutable.value = TaskDeskState(
            ready = ready,
            tasks = board.tasks.value,
            members = team.members.value,
            notes = memory.notes.value,
            settings = DiscoverySchedule.settingsForDay(settings, today),
            pendingSignals = signals.pending.value.size,
            managerSessionId = manager,
            hiddenChats = backstage + underManager + listOfNotNull(managerReviewSession),
            activity = activity,
            sourceApps = sourceApps.toMap(),
            calendarGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            error = mutable.value.error,
            profile = proactive.profile.profile,
            recentAlerts = proactive.book.alerts.takeLast(30).reversed(),
            connections = connections.states,
            accounts = connections.accounts,
            accountsChecked = connections.accountsChecked,
            nextCheckAt = plan.nextCheckAt,
            waitReason = reason,
            events = board.events.value,
            recentRuns = runHistory,
            maintenanceChanges = maintenanceChanges,
            blockedSignals = signals.pending.value.size - allowed.size + legacySignals,
            briefingWait = briefingWait,
            queueRows = allowed,
            scanChats = chats.filter { it.hasMessages && !boardChat(it.id, chats) }.sortedByDescending { it.updatedAt },
        )
    }

    // ----- The user's decisions -------------------------------------------------

    private val executor = object : TaskExecutor {
        override suspend fun start(task: MikeTask, attempt: Int): TaskExecution {
            if (!sourceAllowed(task)) throw dev.androidagent.core.TaskStartRejectedException("This task uses an excluded or unverified source. Review its sources before starting.")
            val manager = team.of(MemberKind.MANAGER)?.sessionId ?: error("The Manager's chat is missing.")
            val parent = sessions.getSession(manager)
            val approvedModel = task.execution?.approvedPlan
            val profile = dev.androidagent.core.TaskFollowThrough.executionProfile(approvedModel, parent)
            val member = task.assignee?.let(team::member)
            val requestId = "${task.id}#$attempt"
            val result = agents().invoke(manager, buildJsonObject {
                put("mode", "start"); put("requestId", requestId)
                put("title", task.title.take(80))
                put("task", TaskPrompts.execution(task, attempt, playbook.directory.absolutePath, member))
                put("engine", profile.engine.name.lowercase())
                profile.model?.let { put("model", it) }
                profile.effort?.let { put("reasoningEffort", it) }
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
    private fun userChange(block: suspend () -> Unit) { scope.launch { runCatching { block(); publish() }.onFailure { reportError(it.message ?: "Could not save the change.") } } }
    fun renameMember(id: String, name: String, role: String) = userChange { team.rename(id, name, role, null) }
    fun removeMember(id: String) = userChange {
        check(team.member(id)?.kind == MemberKind.ROLE) { "Built-in roles stay." }
        board.tasks.value.filter { it.assignee == id }.forEach { board.assign(it.id, null) }
        team.remove(id)
    }
    fun mergeMember(id: String, into: String) = userChange {
        val source = team.member(id)?.takeIf { it.kind == MemberKind.ROLE } ?: error("Choose a task role.")
        val target = team.member(into)?.takeIf { it.kind == MemberKind.ROLE && it.id != id } ?: error("Choose another task role.")
        team.rename(into, null, null, (target.taskKinds + source.taskKinds).distinct())
        board.tasks.value.filter { it.assignee == id }.forEach { board.assign(it.id, into) }
        team.appendJournal(into, "Merged ${source.name}; previous activity remains in its chat.")
        team.remove(id)
    }
    fun assign(id: String, member: String?) = userChange {
        check(member == null || team.member(member)?.kind == MemberKind.ROLE) { "Choose a task role." }
        board.assign(id, member)
    }

    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun calendarChanged() { calendarChanges.changed(); scope.launch { refreshSituation() } }

    /** Undo a night: the playbook and memory as they were in [snapshotId]. The user's own rules stay. */
    suspend fun undo(snapshotId: String) {
        check(store.changes(snapshotId) != null) { "This report predates safe undo. No memory was changed." }
        invalidateMemoryWork()
        val result = store.undoChanges(snapshotId)
        proactive.profile.undoRun(snapshotId)
        activeRules = store.activeGeneration()
        memory.refresh(); board.refresh()
        loadRunHistory(); publish()
        if(result.conflicts > 0) reportError("Restored ${result.restored} changes; kept ${result.conflicts} later edits or forgotten items.")
    }

    // ----- Settings ---------------------------------------------------------------

    fun setDiscovery(on: Boolean) {
        paused = !on
        scope.launch {
            val boundary = if (on && !board.settings.value.discoveryOn) sessions.latestMessageSequence() else null
            board.editSettings { it.copy(discoveryOn = on, pauseReason = if (on) null else "discovery_switch", pausedAt = if (on) null else now(),
                feedStartAt = if (on && !it.discoveryOn) now() else it.feedStartAt,
                feedStartSequence = boundary ?: it.feedStartSequence) }
            if (!on) { stopBackgroundTurns(); signals.clear() } else { tick() }
        }
    }

    fun editSettings(change: (TaskBoardSettings) -> TaskBoardSettings) { scope.launch {
        val boundary = sessions.latestMessageSequence()
        board.editSettings { before ->
        val next = change(before)
        if (next.readChats && !before.readChats) next.copy(feedStartAt = now(), feedStartSequence = boundary) else next
    }
        proactive.profile.pruneExcluded(board.settings.value)
        publish()
    } }

    // ----- Alerts, profile and connections -------------------------------------------

    /** A button on one of Mike's alerts, or a tap that opened it. */
    fun alertFeedback(alertId: String, action: FeedbackAction) {
        scope.launch(Dispatchers.IO) { proactive.onFeedback(alertId, action); publish() }
    }

    fun editAlerts(change: (AlertPrefs) -> AlertPrefs) {
        scope.launch(Dispatchers.IO) { proactive.profile.setAlerts(change); publish() }
    }

    fun setProfileEntry(kind: ProfileKind, key: String, label: String?, weight: Int, alwaysNotify: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { proactive.profile.setOwner(kind, key, label, weight, alwaysNotify) }.onFailure { reportError(it.message ?: "Could not save.") }
            publish()
        }
    }

    fun rejectProfileEntry(id: String) { scope.launch(Dispatchers.IO) { proactive.profile.reject(id); publish() } }
    fun removeProfileEntry(id: String) { scope.launch(Dispatchers.IO) { proactive.profile.remove(id); publish() } }

    fun testAlert() {
        val hebrew = java.util.Locale.getDefault().language in setOf("he", "iw")
        scope.launch(Dispatchers.IO) {
            proactive.test(if (hebrew) "בדיקת התראה" else "Test alert",
                if (hebrew) "כך ייראו ההתראות של מייק." else "This is how Mike's alerts look.")
        }
    }

    fun setConnector(id: String, on: Boolean) {
        editSettings { it.copy(connectors = it.connectors + (id to on)) }
        connections.switched(id, on)
        publish()
    }

    /** Whether a pass may pull context for this app and conversation: discovery on and the source allowed. */
    private fun contextAllowed(app: String, conversation: String?): Boolean = board.settings.value.discoveryOn &&
        dev.androidagent.core.TaskSourcePolicy.allows(
            dev.androidagent.core.TaskSourceRef(SignalFilter.sourceOf(app), app, conversation, verified = true), board.settings.value)

    /** Ask every connector for its state, for the Connections cards. */
    fun refreshConnections() { scope.launch(Dispatchers.IO) { connections.refresh(::publish) } }

    fun forgetCollected() { scope.launch { invalidateMemoryWork(); signals.clear(); proactive.book.clear(); proactive.profile.forgetLearned(); store.clearSourceStats(); situationStore.forgetObserved(); legacySignals = store.quarantineLegacySignals(now(), clear = true); publish() } }
    fun liftSuppression(entry: dev.androidagent.core.TaskSuppression) { scope.launch { board.removeSuppression(entry) } }

    fun addUserRule(rule: String) { scope.launch(Dispatchers.IO) { runCatching { playbook.addUserRule(rule) } } }

    private suspend fun invalidateMemoryWork() {
        board.editSettings { it.copy(memoryForgetRevision = it.memoryForgetRevision + 1) }
        stopBackgroundTurns()
    }
    private suspend fun finishForget() {
        forgottenNotes = store.forgottenNoteIds()
        runCatching { withIo { rootBook().eraseForgotten(forgottenNotes) } }.onFailure { reportError("Memory was forgotten; learned-file cleanup needs attention: ${it.message}") }
        loadRunHistory(); publish()
    }
    fun forgetNote(id: String) { scope.launch { invalidateMemoryWork(); memory.forget(listOf(id)); finishForget() } }
    fun pinNote(id: String, pinned: Boolean) { scope.launch { runCatching { memory.setPinned(id, pinned) } } }
    fun forgetSubject(subject: String) { scope.launch { invalidateMemoryWork(); memory.forgetSubject(subject); finishForget() } }

    // ----- Collecting -------------------------------------------------------------

    /** What the notification listener asks first, by package name alone, and then hands over. */
    val sink = object : dev.androidagent.core.TaskSignalSink {
        override val ready: Boolean get() = this@TaskDesk.ready
        override fun wants(app: String): Boolean {
            val settings = board.settings.value
            return ready && !paused && settings.discoveryOn && app != context.packageName && app !in settings.excludedApps
        }
        override fun onMessage(message: CapturedMessage) = this@TaskDesk.onMessage(message)
    }

    /** From the notification listener: a message as its notification shows it. No model is involved. */
    fun onMessage(message: CapturedMessage) {
        if (!ready || paused) return
        scope.launch {
            if (paused) return@launch
            try {
                val capture = signals.captureResult(message, board.settings.value, context.packageName)
                capture.signal?.let {
                    sourceRefs[it.id] = dev.androidagent.core.TaskSourcePolicy.of(it)
                }
                if (capture.lostPeople > 0) board.editSettings { settings ->
                    settings.copy(lostPeopleMessages = settings.lostPeopleMessages + capture.lostPeople)
                }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: SignalInboxFullException) {
                if (message.fromPerson) board.editSettings { it.copy(lostPeopleMessages = it.lostPeopleMessages + 1) }
                else recordCaptureGap()
            }
            catch (e: IllegalStateException) { captureGap(e.message ?: "Capture inbox full") }
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
        val ours = team.sessionIds + listOfNotNull(managerReviewSession)
        if (id in ours) return true
        var parent = chats.firstOrNull { it.id == id }?.parentSessionId
        var depth = 0
        while (parent != null && depth++ < 6) {
            if (parent in ours) return true
            parent = chats.firstOrNull { it.id == parent }?.parentSessionId
        }
        return false
    }

    /** Only new human input opens a row; assistant output can update an existing row. */
    private suspend fun collectMessageFeed() {
        if (!sessions.hasMessageFeed || !board.settings.value.readChats) return
        val clock = now()
        val chats = sessions.sessions.value.associateBy { it.id }
        collectMessageLane("session-owner-priority-v1", clock, chats, priorityOnly = true)
        collectMessageLane("session-messages-v1", clock, chats, priorityOnly = false)
    }

    private suspend fun collectMessageLane(source: String, clock: Long, chats: Map<String, ChatSession>, priorityOnly: Boolean) {
        // Consume bounded pages even when they only contain old history/progress.
        // A separate text-free priority cursor can pass blocked background input.
        // Per-change receipts keep the two cursors from capturing the same work twice.
        repeat(10) {
            val after = store.sourceCursor(source)
            val page = sessions.messageChanges(after, 100)
            if (page.isEmpty()) return
            val captures = page.map { change ->
                val chat = chats[change.message.sessionId]
                val task = board.tasks.value.firstOrNull { it.execution?.sessionId == chat?.id }
                val ownerChat = chat != null && team.bySession(chat.id)?.kind in setOf(MemberKind.MANAGER, MemberKind.ROLE)
                SignalRows.ChatCapture(change, chat?.title.orEmpty(),
                    ordinary = !priorityOnly && chat != null && !boardChat(chat.id, chats.values.toList()),
                    ownerChat = ownerChat,
                    taskResult = task != null && sourceAllowed(task),
                    quietUntil = maxOf(clock, chat?.updatedAt ?: clock) + QUIET_CHAT_MS)
            }
            val through = signals.captureChats(source, after, captures, board.settings.value.feedStartAt ?: clock,
                board.settings.value.feedStartSequence)
            if (through == after) return
            if (!priorityOnly) sessions.acknowledgeMessageChanges(through)
            if (captures.any { it.ownerChat }) board.refresh()
            signals.pending.value.forEach { sourceRefs[it.id] = dev.androidagent.core.TaskSourcePolicy.of(it) }
            if (through < page.last().sequence) return
        }
    }

    /** Explicit user action: historical chats never enter automatic collection. */
    fun scanChat(id: String) = userChange {
        check(board.settings.value.discoveryOn && board.settings.value.readChats && !paused) { "Turn discovery and Mike chats on first." }
        val chat = sessions.getSession(id) ?: error("This chat no longer exists.")
        check(!boardChat(id, sessions.sessions.value)) { "Use a regular chat for a history scan." }
        if (signals.pending.value.none { it.sessionId == id }) {
            signals.add(TaskSignal(SignalInbox.idOf("scan", id, now()), "chat", conversation = chat.title, text = "",
                at = chat.updatedAt, capturedAt = now(), sessionId = id, sourceVerified = true,
                lane = SignalLane.BACKGROUND, reason = SignalRows.HISTORY_SCAN, sinceAt = 0))
        }
    }

    // ----- Thinking in small batches ---------------------------------------------

    private suspend fun tick() = lock.withLock {
        if (!ready) return@withLock
        board.sweep()
        memory.emptyTrash()
        val settings = board.settings.value
        legacySignals = store.quarantineLegacySignals(now(), clear = !settings.discoveryOn)
        signals.pruneSources(settings)
        val beforePrune = signals.pending.value
        signals.prune(settings.discoveryOn)
        val gone = beforePrune.filter { old -> signals.pending.value.none { it.id == old.id } }
        if (gone.isNotEmpty() && settings.discoveryOn) board.editSettings {
            it.copy(lostPeopleMessages = it.lostPeopleMessages + gone.filter { row -> row.lane == SignalLane.PEOPLE && row.app != null }.sumOf { row -> row.items.size },
                expiredBackgroundRows = it.expiredBackgroundRows + gone.count { row -> row.lane == SignalLane.BACKGROUND })
        }
        settleInFlight()
        // Digests and snoozes are the user's own deliveries: they go out even when discovery is off.
        runCatching { proactive.tick() }
        if (!settings.discoveryOn || paused) return@withLock
        userRulesContext = withIo { playbook.userRules().take(4_000) }
        deliverBriefings()
        prepareDueChecks()
        deliverBriefings()
        val calendarGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        calendarChanges.capture(signals, now(), settings.lastCalendarCheckAt, settings.readCalendar && calendarGranted)
        dev.androidagent.core.MailCheck.capture(signals, now(), settings.lastMailCheckAt, connections.mailReadable(settings.passEngine), zone())
        try { collectMessageFeed() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: SignalInboxFullException) { /* Replayable chat input waits at its current cursor. */ }
        catch (e: Exception) { reportError("Chat capture: ${e.message}") } // Let existing input drain under backpressure.
        maybePass()
        maybeNightly()
        maybeManagerReview()
        publish()
    }

    private fun busy(member: TeamMember?): Boolean = member?.sessionId?.let { id ->
        inFlight.containsKey(id) || runs().stateOf(id)?.active == true
    } ?: true

    private suspend fun prepareDueChecks() {
        val tasks = board.tasks.value.filter(::sourceAllowed)
        val plan = dev.androidagent.core.SituationAttention.plan(tasks, situationSnapshot(), now())
        val due = plan.taskIds.mapNotNull { id -> tasks.firstOrNull { it.id == id } }
        val nextCheck = plan.nextCheck
        if (due.isEmpty()) {
            val deferred = plan.deferredTaskIds
            situationStore.recordDecision(dev.androidagent.core.AttentionDecision(if (deferred.isEmpty()) "silence" else "defer",
                plan.reason, deferred, now(), nextCheck))
        }
        val hebrew = java.util.Locale.getDefault().language in setOf("he", "iw")
        for (task in due) {
            // Never replace an unsent message. The delivery path resolves it first.
            if (task.briefing?.let { it.deliveredAt == null } == true) {
                situationStore.recordDecision(dev.androidagent.core.AttentionDecision("defer", "earlier briefing pending", listOf(task.id), now(), nextCheck))
                continue
            }
            val deadline = dev.androidagent.core.TaskFollowThrough.occurrence(task, now())?.startsWith("deadline:") == true
            val text = if (deadline) {
                if (hebrew) "המועד של המשימה מתקרב. מה הצעד הבא?" else "This task's deadline is approaching. What is the next step?"
            } else if (hebrew) "הגיע הזמן לבדוק את המשימה. מה הצעד הבא?" else "It's time to check this task. What is the next step?"
            board.prepareBriefing(task.id, text, task.sourceRefs)
            situationStore.recordDecision(dev.androidagent.core.AttentionDecision("defer", if (deadline) "approaching deadline queued for delivery" else "explicit follow-up queued for delivery", listOf(task.id), now(), nextCheck))
        }
    }

    private suspend fun maybePass() {
        val scout = team.of(MemberKind.SCOUT) ?: return
        val clock = now()
        var settings = board.settings.value
        val today = Instant.ofEpochMilli(clock).atZone(zone()).toLocalDate().toString()
        if (settings.passDay != today) settings = board.editSettings { it.copy(passDay = today, passesToday = 0, backgroundPassesToday = 0) }
        val eligible = signals.pending.value.filter { sourceAllowed(dev.androidagent.core.TaskSourcePolicy.of(it)) &&
            (it.sessionId == null || runs().stateOf(it.sessionId!!)?.active != true) }
        val plan = DiscoverySchedule.plan(eligible, clock, settings.lastPassAt, settings.passesToday, settings.dailyPassCap, busy(scout),
            backgroundPassesToday = settings.backgroundPassesToday)
        if (!plan.due || quotaLow(settings.passEngine)) return
        val session = scout.sessionId ?: return
        val batch = plan.batch.map { row ->
            if (row.sessionId == null) row else row.copy(text = withIo { SignalRows.chatText(row, sessions.messages(row.sessionId!!).first()) })
        }
        val pulled = if (connections.connectors.isEmpty()) emptyList() else try { proactive.broker?.enrich(batch).orEmpty() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val contexts = withIo { connections.passContext(batch, pulled, settings.passEngine) }
        val prompt = TaskPrompts.scoutPass(batch, clock, zone(), plan.batch.any { it.source == "calendar" }, playbook.directory.absolutePath,
            contexts, proactive.profile.summary(clock, zone()), proactive.profile.profile.alerts.enabled)
        passRows[session] = batch.associateBy { it.id }
        plan.batch.forEach { sourceRefs[it.id] = dev.androidagent.core.TaskSourcePolicy.of(it) }
        inFlight[session] = beginFlight(scout, plan.batch.map { it.id }, clock,
            inputVersions = plan.batch.associate { it.id to it.revision })
        board.editSettings { it.copy(lastPassAt = clock, passesToday = it.passesToday + 1,
            backgroundPassesToday = it.backgroundPassesToday + if (plan.batch.all { s -> s.lane == SignalLane.BACKGROUND }) 1 else 0,
            lastCalendarCheckAt = if (plan.batch.any { s -> s.source == "calendar" }) clock else it.lastCalendarCheckAt,
            lastMailCheckAt = if (plan.batch.any { s -> s.reason == dev.androidagent.core.SignalRows.MAIL_CHECK }) clock else it.lastMailCheckAt) }
        submit(session, prompt, "Discovery pass · ${plan.batch.size} new", settings.passEngine, settings.passModel, settings.passEffort)
    }

    private suspend fun maybeManagerReview() {
        val session = managerReviewSession ?: return
        val clock = now()
        var settings = board.settings.value
        val today = Instant.ofEpochMilli(clock).atZone(zone()).toLocalDate().toString()
        if(settings.managerReviewDay != today) settings = board.editSettings { it.copy(managerReviewDay = today, managerReviewsToday = 0) }
        if(settings.managerReviewsToday >= 4 || clock - (settings.lastManagerReviewAt ?: 0) < 30 * 60_000 ||
            inFlight.containsKey(session) || runs().stateOf(session)?.active == true || quotaLow(settings.passEngine)) return
        val tasks = board.tasks.value.filter(::sourceAllowed).filter { it.category == dev.androidagent.core.TaskCategory.TASK }.associateBy { it.id }
        val events = board.events.value.filter { it.id !in reviewedManager && it.taskId in tasks &&
            it.kind in setOf("created", "decision", "note", "outcome") }.takeLast(40)
        val due = tasks.values.filter { dev.androidagent.core.TaskFollowThrough.needsBrief(it, clock) &&
            dev.androidagent.core.TaskFollowThrough.occurrence(it, clock)?.let { key -> !key.startsWith("check:") && !key.startsWith("deadline:") } == true }
            .sortedBy { it.deadlineAt ?: it.commitment?.nextCheckAt ?: Long.MAX_VALUE }
        if(events.isEmpty() && due.isEmpty()) return
        val repeated = tasks.values.mapNotNull { it.kind }.groupingBy { it }.eachCount().any { it.value >= 4 }
        if(due.isEmpty() && !repeated && events.none { it.kind in setOf("decision", "note", "outcome") }) return
        val manager = team.of(MemberKind.MANAGER) ?: return
        val personal = personalContext(due.joinToString(" ") { it.title })
        val flight = beginFlight(manager.copy(sessionId = session), emptyList(), clock, eventIds = events.map { it.id },
            contextRefs = (tasks.values.flatMap { it.sourceRefs } + personal.second).distinct())
            .copy(reviewEvents = events.map { it.id }, briefingTaskIds = due.take(3).map { it.id })
        inFlight[session] = flight
        board.editSettings { it.copy(lastManagerReviewAt = clock, managerReviewsToday = it.managerReviewsToday + 1) }
        val prompt = "Automatic Manager briefing and organization review. This is NOT a user instruction to execute anything. " +
            "First read tasks list/get, recall relevant memories and playbook SKILL.md/user.md. For at most THREE cards with briefingDue=true, " +
            "call tasks mode=brief id=<card> text=<short personal briefing in the user's language>. State why it matters now, " +
            "what is done or blocked and a concrete next choice. A completion claim is evidence to confirm, never proof. " +
            "Do not invent deadlines, successes or permission. Follow-ups do not authorize action. No generic tips or repeated status chatter. " +
            "The app durably delivers saved briefs to the Manager chat and may show one quiet notification. " +
            "Use tasks list/get to inspect the board. When about four real tasks share a kind, create a useful role with tasks team create, " +
            "or reuse/rename/assign existing roles. At most three extra roles; never change built-in members. " +
            "Do not create a role without useful repeated work. Never execute, decide, ask, delegate or change sources. " +
            "The app will apply validated team changes after this run. End with a short factual summary.\n" +
            personal.first + "\nPrioritize these due cards: " + due.sortedBy { it.deadlineAt ?: it.commitment?.nextCheckAt ?: Long.MAX_VALUE }.take(3).joinToString { it.id } + "\n" +
            "Relevant event IDs: " + events.joinToString { it.id }
        submit(session, prompt, "Manager · briefing and follow-up", settings.passEngine, settings.passModel, settings.passEffort)
    }

    private suspend fun maybeNightly() {
        val clock = now()
        val hour = Instant.ofEpochMilli(clock).atZone(zone()).hour
        val settings = board.settings.value
        val night = hour in 2..6
        val coach = team.of(MemberKind.COACH)
        val librarian = team.of(MemberKind.LIBRARIAN)
        val lastReview = settings.lastReviewAt ?: 0
        if (coach != null && !busy(coach) && clock - (settings.lastReviewAttemptAt ?: 0) > 2 * HOUR && ((night && clock - lastReview > 20 * HOUR) || clock - lastReview > 36 * HOUR)) {
            val allStats = store.loadSourceStats()
            val expired = allStats.filter { clock - it.updatedAt > SourceStat.RETENTION_MS || it.app in settings.excludedApps }
            if (expired.isNotEmpty()) store.saveSourceStats(emptyList(), expired.map { it.key })
            val stats = allStats.filter { it !in expired }.groupBy { it.app }.values
                .filter { days -> days.sumOf { it.received } >= 10 && days.sumOf { it.reviewed } > 0 }
                .sortedByDescending { days -> days.sumOf { it.received } }.take(10).flatten()
            val allowedIds = board.tasks.value.filter(::sourceAllowed).map { it.id }.toSet()
            val freshEvents = board.events.value.filter { (it.taskId in allowedIds || (it.kind == "feedback" && it.actor == "user" && it.sourceRefs.all(::sourceAllowed))) &&
                it.id !in reviewedCoach && it.kind in setOf("decision", "note", "outcome", "feedback") }
                .sortedBy { it.at }.take(160)
            val recent = board.tasks.value.filter { task -> freshEvents.any { it.taskId == task.id } && sourceAllowed(task) }
            val attentionFresh = proactive.book.recentFeedback(lastReview).isNotEmpty()
            if ((freshEvents.isNotEmpty() || stats.isNotEmpty() || attentionFresh) && !quotaLow(settings.reviewEngine)) {
                val snapshot = UUID.randomUUID().toString()
                val session = coach.sessionId ?: return
                val ids = recent.take(80).map { it.id }.toSet()
                val feedback = freshEvents.filter { it.taskId in ids || it.kind == "feedback" }
                inFlight[session] = beginFlight(coach, emptyList(), clock, snapshot, eventIds = feedback.map { it.id },
                    contextRefs = (recent.flatMap { it.sourceRefs } + feedback.flatMap { it.sourceRefs } +
                        stats.map { dev.androidagent.core.TaskSourceRef("notification", app = it.app, verified = true) }).distinct())
                submit(session, TaskPrompts.coachReview(recent.take(80), clock, zone(), snapshot, playbook.directory.absolutePath, feedback, stats,
                    proactiveTools(null, null).attention(), proactive.profile.summary(clock, zone()), mailChecked = settings.lastMailCheckAt != null),
                    "Nightly review", settings.reviewEngine, settings.reviewModel, settings.reviewEffort)
                board.editSettings { it.copy(lastReviewAttemptAt = clock) }
                return
            }
        }
        val lastUpkeep = settings.lastUpkeepAt ?: 0
        if (librarian != null && !busy(librarian) && !busy(coach) && clock - (settings.lastUpkeepAttemptAt ?: 0) > 2 * HOUR && ((night && clock - lastUpkeep > 20 * HOUR) || clock - lastUpkeep > 36 * HOUR)) {
            val deep = clock - (settings.lastDeepUpkeepAt ?: 0) > 6 * 24 * HOUR + 12 * HOUR
            val live = memory.live.filter(::sourceAllowed)
            if (live.isNotEmpty() && !quotaLow(settings.reviewEngine)) {
                val snapshot = UUID.randomUUID().toString()
                val session = librarian.sessionId ?: return
                val open = board.tasks.value.filter { !it.status.closed }.map { it.id }.toSet()
                val ordered = live.sortedBy { it.id }
                val offset = settings.upkeepCursor % ordered.size
                val candidates = (ordered.drop(offset) + ordered.take(offset)).take(160)
                inFlight[session] = beginFlight(librarian, emptyList(), clock, snapshot, deep = deep, nextCursor = (offset + candidates.size) % ordered.size,
                    contextRefs = candidates.flatMap { it.sourceRefs + dev.androidagent.core.TaskSourceRef("memory", verified = true, noteId = it.id) }.distinct())
                submit(session, TaskPrompts.librarianUpkeep(deep, candidates, memory.pressure(), clock, zone(),
                    snapshot, playbook.directory.absolutePath, open), if (deep) "Weekly memory upkeep" else "Nightly memory upkeep",
                    settings.reviewEngine, settings.reviewModel, settings.reviewEffort)
                board.editSettings { it.copy(lastUpkeepAttemptAt = clock) }
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
            TaskChatRole.COORDINATOR -> "task-board"
            else -> error("Only backstage team members run background passes.")
        }
        val guidance = withIo { context.assets.open("agent_stack/skills/$skill/SKILL.md").bufferedReader().use { it.readText() } }
        check(!paused && board.settings.value.discoveryOn) { "Discovery stopped." }
        val flight = inFlight[session] ?: error("No prepared background pass.")
        val turn = QueuedTurn(
            sessionId = session, prompt = "$guidance\n\n$prompt", model = model, effort = effort.takeIf { model != null }, engine = engine,
            displayPrompt = label, agentContext = true, validUntil = now() + PASS_DEADLINE_MS,
            boardJobId = flight.run!!.id)
        preparedTurns[flight.run.id] = turn
        queue().submit(turn.copy(prompt = "Background board job ${flight.run.id}"))
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
            var ok = ran && outcome?.phase == RunPhase.IDLE && outcome.status == "Ready" && !outcome.outcomeUnknown &&
                (flight.batch.isEmpty() || flight.finished)
            var committed = false
            var appliedChanges: dev.androidagent.core.TaskChangeSet? = null
            if(ok && flight.data != null && flight.run != null) {
                try {
                    check(flight.policyRevision == board.settings.value.policyRevision) { "Sources changed." }
                    if (flight.run.purpose == "coach") withIo {
                        val base = flight.ruleBase?.let { rootBook().generation(it) } ?: rootBook()
                        val stagedBook = flight.ruleBook!!
                        val basePaths = base.list().toSet()
                        val stagedPaths = stagedBook.list().toSet()
                        dev.androidagent.core.CoachReview.validateFinalFiles(flight.coachDispositions.values.toList(),
                            before = { path -> if (path in basePaths) base.read(path) else null },
                            after = { path -> if (path in stagedPaths) stagedBook.read(path) else null },
                            sources = stagedBook::sourcesOf)
                    }
                    val generation = if(withIo { flight.ruleBook!!.fingerprint() } != flight.ruleFingerprint)
                        withIo { flight.ruleBook!!.publishGeneration(flight.run.id) } else null
                    var changes = flight.data.changes(flight.run.id, flight.policyRevision, flight.ruleBase, generation)
                    if(flight.briefingTaskIds.isNotEmpty()) check(changes.tasks.any { it.after.id in flight.briefingTaskIds && it.after.briefing != it.before?.briefing }) {
                        "The Manager did not save a due briefing. It will be retried within the review limits."
                    }
                    if(flight.batch.isEmpty() && (changes.notes.isNotEmpty() || generation != null) &&
                        changes.tasks.none { it.after.category == dev.androidagent.core.TaskCategory.REPORT && it.after.action?.value == flight.run.id }) {
                        val staged = TaskBoard(flight.data.store, now, zone).apply { load() }
                        val he = java.util.Locale.getDefault().language in setOf("he", "iw")
                        val report = staged.propose(dev.androidagent.core.TaskProposal(
                            title = if(he) "עדכון הזיכרון והכללים" else "Memory and rules update",
                            why = if(he) "מייק סיים תחזוקה ברקע" else "Mike completed background upkeep",
                            plan = if(he) "עודכנו ${changes.notes.size} זיכרונות. כללים עודכנו: ${generation != null}. ניתן לבטל בלי למחוק שינויים מאוחרים."
                                else "Updated ${changes.notes.size} memory notes. Rules changed: ${generation != null}. Undo preserves later changes.",
                            category = dev.androidagent.core.TaskCategory.REPORT,
                            action = TaskAction(TaskAction.UNDO_SNAPSHOT, flight.run.id), sourceRefs = flight.contextSources), "upkeep", session)
                        check(report is dev.androidagent.core.ProposeOutcome.Created || report is dev.androidagent.core.ProposeOutcome.Duplicate) { "A maintenance report could not be saved." }
                        changes = flight.data.changes(flight.run.id, flight.policyRevision, flight.ruleBase, generation)
                    }
                    val completedCoach = if (flight.run.purpose == "coach") dev.androidagent.core.CoachReview.completed(
                        flight.run.eventIds, flight.coachDispositions.values.toList(),
                        changes.generation != null) else emptySet()
                    store.commitChanges(changes, flight.run.copy(coachDispositions = flight.coachDispositions.values.toList()))
                    committed = true
                    flight.learned.forEach { r ->
                        runCatching { proactive.profile.learn(r.kind, r.key, r.label, r.weight, r.why, r.evidence, r.runId) }
                    }
                    if(flight.run.purpose == "manager") reviewedManager = reviewedManager + flight.run.eventIds
                    if(flight.run.purpose == "coach") reviewedCoach = reviewedCoach + completedCoach
                    appliedChanges = changes
                    activeRules = store.activeGeneration()
                    board.refresh(); memory.refresh(); signals.refresh(); team.refresh()
                    if(flight.reviewEvents.isNotEmpty()) team.of(MemberKind.MANAGER)?.sessionId?.let { manager ->
                        sessions.append(dev.androidagent.core.ChatMessage(UUID.randomUUID().toString(), manager, "system",
                            "Board review completed: ${changes.members.size} team changes, ${changes.tasks.size} card updates. No task execution was started.", now()))
                    }
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch(e: Exception) { ok = committed; reportError(if(committed) "Saved the result; display refresh needs attention: ${e.message}" else "Background result was not applied: ${e.message}") }
            }
            if(committed && appliedChanges?.members?.isNotEmpty() == true) runCatching { team.repairRoleChats(::createChat) }
                .onFailure { reportError("Team changes were saved. A role chat will be repaired on restart: ${it.message}") }
            if (flight.batch.isNotEmpty()) {
                if (!ok) signals.failed(flight.batch)
            }
            val failureReason = if (ran && outcome?.status == "Ready" && flight.batch.isNotEmpty() && !flight.finished)
                "Scout ended without acknowledging its input batch."
                else outcome?.status ?: "No completed receipt"
            if(!ok) flight.run?.let { store.saveRun(it.copy(state = "retryable", error = failureReason)) }
            withIo { flight.ruleBook?.discardStage() }
            if (!ok && mutable.value.error == null) {
                val message = "Background work did not finish: $failureReason Input stays available for another pass."
                if (flight.batch.isNotEmpty()) scoutFailure = flight.batch.toSet() to message
                reportError(message)
            }
            team.appendJournal(flight.member, (if (ok) "Done " else "Did not finish ") + Instant.ofEpochMilli(clock).atZone(zone()).toLocalTime().withNano(0))
            inFlight.remove(session)
            loadRunHistory()
        }
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private suspend fun recordCaptureGap(count: Int = 1) {
        board.editSettings { it.copy(captureGaps = it.captureGaps + count, lastCaptureGapAt = now()) }
    }

    private suspend fun captureGap(message: String, count: Int = 1) {
        recordCaptureGap(count)
        reportError(message)
    }

    private val briefingDelivery = dev.androidagent.core.ManagerBriefingDelivery(
        board = board,
        sessions = sessions,
        now = now,
        active = { !paused && board.settings.value.discoveryOn },
        taskAllowed = { sourceAllowed(it) },
        sourceAllowed = { sourceAllowed(it) },
        occurrenceAllowed = { task, briefing -> !briefing.occurrence.startsWith("deadline:") ||
            dev.androidagent.core.SituationAttention.deadlineEligible(task, situationSnapshot(), now()) },
        quietUntil = { task ->
            dev.androidagent.core.SituationAttention.quietUntil(situationSnapshot(), now())?.also { until ->
                situationStore.recordDecision(dev.androidagent.core.AttentionDecision("defer", "quiet window", listOf(task.id), now(), until))
            }
        },
        recorded = { task ->
            situationStore.recordDecision(dev.androidagent.core.AttentionDecision("send", "briefing saved to Manager chat", listOf(task.id), now()))
        },
        notify = { manager, title, text, id ->
            TaskBriefingNotifier.show(context, manager, title, text, id).let { it.state to it.note }
        },
        onFailure = { reportError("A Manager briefing could not be delivered; it will remain pending.") },
    )

    private suspend fun deliverBriefings() {
        val manager = team.of(MemberKind.MANAGER)?.sessionId ?: return
        briefingDelivery.deliver(manager)
    }

    fun ownerContext(sessionId: String): String? = ownerContext(sessionId, "")
    private fun personalContext(query: String, situationTasks: List<MikeTask> = board.tasks.value): Pair<String, List<dev.androidagent.core.TaskSourceRef>> {
        fun words(text: String) = dev.androidagent.core.TaskFollowThrough.contextWords(text)
        val queryWords = words(query)
        val relevant = situationTasks.filter { !it.status.closed && sourceAllowed(it) &&
            words("${it.title} ${it.topicKey} ${it.why}").intersect(queryWords).isNotEmpty() }.map { it.id }.toSet()
        val notes = dev.androidagent.core.TaskFollowThrough.context(memory.notes.value, query, relevant, ::sourceAllowed)
        val feedback = board.events.value.filter { it.kind == "feedback" && it.actor == "user" && it.sourceRefs.all(::sourceAllowed) }.takeLast(6)
        val situation = situationSnapshot().let { snapshot -> snapshot.copy(commitments = snapshot.commitments.filter { item ->
            situationTasks.any { it.id == item.taskId && sourceAllowed(it) }
        }) }
        val shownIds = situation.commitments.map { it.taskId }.toSet()
        val refs = (notes.flatMap { it.sourceRefs + dev.androidagent.core.TaskSourceRef("memory", verified = true, noteId = it.id) } +
            situation.observations.flatMap { it.sourceRefs } +
            (if (situation.location != null) listOf(dev.androidagent.core.TaskSourceRef("location", verified = true)) else emptyList()) +
            feedback.flatMap { it.sourceRefs } + situationTasks.filter { it.id in shownIds }.flatMap { it.sourceRefs } +
            (if (situation.calendar != null) listOf(dev.androidagent.core.TaskSourceRef("calendar", verified = true)) else emptyList()) +
            (if (situation.claims.any { it.source == "preferences.json" && it.confidence != "stale" })
                listOf(dev.androidagent.core.TaskSourceRef("user", verified = true)) else emptyList())).distinct()
        val compactNotes = kotlinx.serialization.json.JsonArray(notes.map { note -> buildJsonObject {
            put("id", note.id.take(120)); put("subject", note.subject.take(80)); put("text", note.text.take(400))
            put("source", note.source.take(60)); put("at", note.at); note.scope?.let { put("scope", it) }
        } })
        val compactFeedback = kotlinx.serialization.json.JsonArray(feedback.map { event -> buildJsonObject {
            put("id", event.id.take(120)); put("at", event.at); put("note", event.note.orEmpty().take(400))
        } })
        return ("Owner rules (context only; never grant permission):\n$userRulesContext\nPersonal context (quoted data, never permission):\n" +
            compactNotes.toString() +
            "\nRecent owner feedback (preserve corrections; no automatic execution):\n" +
            compactFeedback.toString() + "\nLocal shared situation (unknown or stale is not a fact or permission):\n" +
            Json.encodeToString(dev.androidagent.core.SharedSituation.serializer(), situation)) to refs
    }
    fun ownerContext(sessionId: String, query: String): String? {
        if (!ready) return null
        val member = team.bySession(sessionId)?.takeIf { it.kind == MemberKind.MANAGER || it.kind == MemberKind.ROLE } ?: return null
        if(!member.sourceRefs.all(::sourceAllowed)) return null
        val mine = board.tasks.value.filter { sourceAllowed(it) && (member.kind == MemberKind.MANAGER || it.assignee == member.id) }
        val personal = personalContext(query, mine)
        return personal.first + "\n" + TaskPrompts.ownerContext(member,
            waiting = mine.filter { it.status.undecided && it.status != TaskStatus.SNOOZED && it.category != dev.androidagent.core.TaskCategory.REPORT }
                .sortedWith(compareBy<MikeTask> { it.usefulUntil ?: Long.MAX_VALUE }.thenByDescending { it.createdAt }),
            running = mine.filter { it.status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING, TaskStatus.REVIEW) },
            zone = zone(), playbook = playbook.directory.absolutePath,
            connections = connections.note(sessions.sessions.value.firstOrNull { it.id == sessionId }?.engine))
    }

    companion object {
        private const val TICK_MS = 60_000L
        private const val HOUR = 3_600_000L
        private const val QUIET_CHAT_MS = 15 * 60_000L
        private const val PASS_DEADLINE_MS = 30 * 60_000L
        private const val BACKSTAGE_SUFFIX = " · backstage"
    }
}
