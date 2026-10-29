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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A turn stopped because the account it ran on is out of usage. */
data class LimitReached(
    /** Epoch seconds at which the engine said the limit resets; null when it did not say. */
    val resetsAt: Long? = null,
    /** The engine whose limit it is. The coordinator fills it in from the run. */
    val engine: EngineKind? = null,
)

/**
 * Recognizes a usage-limit stop in an engine's error text. The engines also
 * say it structurally (Codex's `usageLimitExceeded`, Claude's rejected
 * `rate_limit_event`); this is the fallback for the wordings that carry no
 * structure, so it matches whole phrases only and never a bare "limit".
 */
object UsageLimitText {
    private val PHRASE = Regex(
        """(?i)(hit your (usage )?limit|usage limit (has been )?(reached|exceeded)|reached your [\w\s-]{0,40}usage limit|""" +
            """usagelimitexceeded|usage_limit_(reached|exceeded)|(5-hour|five-hour|weekly|session|daily|opus|sonnet) limit reached)""",
    )
    private val EPOCH = Regex("""(?i)usage limit reached\|(\d{9,11})""")

    fun recognize(text: String?): LimitReached? {
        if (text.isNullOrBlank() || !PHRASE.containsMatchIn(text)) return null
        return LimitReached(resetsAt = EPOCH.find(text)?.groupValues?.get(1)?.toLongOrNull())
    }
}

/** Who a turn runs as: an engine, and for Codex one saved account (null: whichever is live). */
@Serializable
data class LimitTarget(val engine: EngineKind, val accountId: String? = null) {
    val key: String get() = engine.name + (accountId?.let { ":$it" }.orEmpty())
}

/** What a chat does when it reaches a usage limit. Set from inside the chat. */
@Serializable
data class LimitPlan(
    /** Wait for the usage to reset, then continue on its own. */
    val continueAtReset: Boolean = false,
    /** Tried in this order before waiting: another Codex account, or the other provider. */
    val switchTo: List<LimitTarget> = emptyList(),
) {
    val active: Boolean get() = continueAtReset || switchTo.isNotEmpty()
}

enum class LimitPhase {
    /** Stopped; nothing is planned. The chat offers what can be done. */
    STOPPED,
    /** Moving to another account or provider. */
    SWITCHING,
    /** Waiting for the usage to reset; [LimitStop.continueAt] says when. */
    WAITING,
    /** A turn that picks the work up again was sent. */
    CONTINUING,
}

/** A chat that stopped at a usage limit, and what is being done about it. */
@Serializable
data class LimitStop(
    val sessionId: String,
    /** What the chat ran as when it stopped. */
    val target: LimitTarget,
    /** Epoch millis at which [target] frees up, or null when nobody said. */
    val resetsAt: Long? = null,
    val stoppedAt: Long,
    val phase: LimitPhase = LimitPhase.STOPPED,
    /** Epoch millis of the planned continuation while [phase] is WAITING. */
    val continueAt: Long? = null,
    /** Targets already used in this chain of stops, so it never goes round in a circle. */
    val tried: List<String> = emptyList(),
    /** Automatic continuations since the chat last finished a turn on its own. */
    val attempts: Int = 0,
    /** Set when the plan ran out of options, for the card to say why. */
    val reason: String? = null,
)

/** What [UsageLimitRecovery] needs from the app: accounts, engines, the queue. */
interface LimitRecoveryHost {
    /** What this chat would run as now, or null when the chat is gone. */
    suspend fun current(sessionId: String): LimitTarget?
    /** [target] can run this chat at all: signed in, installed where the chat runs. */
    suspend fun canRun(sessionId: String, target: LimitTarget): Boolean
    /** The last usage reading of [target], to tell when it frees up. */
    fun readings(target: LimitTarget): List<UsageLimit>
    /** Make the chat run as [target]. Throws when it cannot. */
    suspend fun switchTo(sessionId: String, target: LimitTarget, allowed: () -> Boolean)
    /** Queue the turn that picks the work up again on [engine]. */
    suspend fun continueChat(sessionId: String, engine: EngineKind, allowed: () -> Boolean)
    /** A line in the chat that says what was done. */
    suspend fun note(sessionId: String, text: String)
    /** Wake the app at [atMillis] in case it is not running then; null cancels. */
    fun wakeAt(atMillis: Long?)
    fun label(target: LimitTarget): String
}

/**
 * Picks a chat up again after it stopped at a usage limit.
 *
 * A chat's [LimitPlan] says what to do: first move to another account or
 * provider, in the user's order, then (or instead) wait for the usage to reset
 * and continue. A stop with no plan is kept for the chat's card, where the
 * user can decide. Plans and stops are saved, so a wait survives the app
 * being closed; [dispatchDue] is what the wake-up alarm calls.
 *
 * Every automatic continuation is a turn of its own, sent through the queue
 * like any other: it never steers or replays a turn, and anything the stopped
 * turn did stays done.
 */
class UsageLimitRecovery(
    private val scope: CoroutineScope,
    private val book: LimitBook,
    private val host: LimitRecoveryHost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val plansState = MutableStateFlow<Map<String, LimitPlan>>(emptyMap())
    private val stopsState = MutableStateFlow<Map<String, LimitStop>>(emptyMap())
    /** When each target frees up, by [LimitTarget.key], in epoch millis. */
    private var exhausted: Map<String, Long> = emptyMap()
    private val timers = mutableMapOf<String, Job>()
    private val handledRuns = mutableSetOf<String>()
    private val stopEpoch = AtomicLong()
    private val enabledEpochs = ConcurrentHashMap<String, Long>()
    private val sessionEpochs = ConcurrentHashMap<String, Long>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    /** Revoke synchronously, even while a switch is waiting outside the app. */
    fun stop(sessionId: String? = null) {
        if (sessionId == null) stopEpoch.incrementAndGet()
        else { paused += sessionId; sessionEpochs.merge(sessionId, 1L, Long::plus) }
        scope.launch {
            loaded.join()
            lock.withLock {
                stopsState.value.values.toList().filter { sessionId == null || it.sessionId == sessionId }.forEach { stop ->
                    if (!permitted(stop.sessionId)) {
                        cancelTimer(stop.sessionId)
                        update(stop.sessionId, stop.copy(phase = LimitPhase.STOPPED, continueAt = null))
                    }
                }
                wake()
            }
        }
    }

    private fun permitted(id: String): Boolean = id !in paused && (enabledEpochs[id] ?: 0L) == stopEpoch.get()
    private fun enable(id: String) { enabledEpochs[id] = stopEpoch.get(); paused -= id }
    private fun permission(id: String): () -> Boolean {
        val global = stopEpoch.get()
        val local = sessionEpochs[id] ?: 0L
        return { permitted(id) && stopEpoch.get() == global && (sessionEpochs[id] ?: 0L) == local }
    }

    val plans: StateFlow<Map<String, LimitPlan>> = plansState.asStateFlow()
    val stops: StateFlow<Map<String, LimitStop>> = stopsState.asStateFlow()

    private val loaded = scope.launch {
        val saved = book.read()
        lock.withLock {
            plansState.value = saved.plans
            exhausted = saved.exhausted
            // An interrupted switch or continuation needs review, never an automatic replay.
            stopsState.value = saved.stops.mapValues { (_, stop) ->
                if (stop.phase in setOf(LimitPhase.SWITCHING, LimitPhase.CONTINUING)) stop.copy(phase = LimitPhase.STOPPED) else stop
            }
            stopsState.value.values.filter { it.phase == LimitPhase.WAITING }.forEach(::schedule)
            wake()
        }
    }

    /** Follow every chat's finished runs. */
    fun watch(outcomes: Flow<Map<String, RunState>>) = scope.launch {
        loaded.join()
        outcomes.collect { all -> all.forEach { (sessionId, state) -> finished(sessionId, state) } }
    }

    suspend fun finished(sessionId: String, state: RunState) {
        if (state.active) return
        val runId = state.runId ?: return
        loaded.join()
        lock.withLock {
            if (!handledRuns.add(runId)) return
            val limit = state.limit
            if (limit != null) stopped(sessionId, limit) else if (sessionId in stopsState.value) {
                // The chat went on without us: a reply, Stop, or another error.
                cancelTimer(sessionId)
                update(sessionId, null)
            }
        }
    }

    fun planOf(sessionId: String): LimitPlan = plansState.value[sessionId] ?: LimitPlan()

    /** Save the chat's plan; a chat stopped right now acts on it at once. */
    suspend fun setPlan(sessionId: String, plan: LimitPlan) {
        enable(sessionId)
        loaded.join()
        lock.withLock {
            plansState.value = if (plan.active) plansState.value + (sessionId to plan) else plansState.value - sessionId
            persist()
            val stop = stopsState.value[sessionId] ?: return
            when {
                stop.phase == LimitPhase.STOPPED && plan.active -> proceed(sessionId)
                stop.phase == LimitPhase.WAITING && !plan.continueAtReset -> {
                    cancelTimer(sessionId)
                    update(sessionId, stop.copy(phase = LimitPhase.STOPPED, continueAt = null))
                }
            }
        }
    }

    /** From the chat's card: continue this stop once the usage resets, without a standing plan. */
    suspend fun continueAtReset(sessionId: String) {
        enable(sessionId)
        loaded.join()
        lock.withLock { stopsState.value[sessionId]?.takeIf { it.phase == LimitPhase.STOPPED }?.let { arm(it) } }
    }

    /** Stop waiting. The chat stays stopped and says so. */
    suspend fun cancelWait(sessionId: String) {
        loaded.join()
        lock.withLock {
            val stop = stopsState.value[sessionId]?.takeIf { it.phase == LimitPhase.WAITING } ?: return
            cancelTimer(sessionId)
            update(sessionId, stop.copy(phase = LimitPhase.STOPPED, continueAt = null))
        }
    }

    /** Try again now on what the chat runs as, for when the user knows the usage is back. */
    suspend fun continueNow(sessionId: String) {
        enable(sessionId)
        loaded.join()
        lock.withLock {
            val stop = stopsState.value[sessionId] ?: return
            if (stop.phase == LimitPhase.SWITCHING || stop.phase == LimitPhase.CONTINUING) return
            cancelTimer(sessionId)
            exhausted = exhausted - stop.target.key
            // The user asked for it: the count of automatic tries starts over.
            resume(stop.copy(attempts = 0), host.current(sessionId) ?: stop.target)
        }
    }

    /** Move the stopped chat to [target] now and continue there. */
    suspend fun switchNow(sessionId: String, target: LimitTarget) {
        enable(sessionId)
        loaded.join()
        lock.withLock {
            val stop = stopsState.value[sessionId] ?: return
            if (stop.phase == LimitPhase.SWITCHING || stop.phase == LimitPhase.CONTINUING) return
            cancelTimer(sessionId)
            if (!switchAndContinue(stop, target)) update(sessionId, stopsState.value[sessionId]?.copy(phase = LimitPhase.STOPPED))
        }
    }

    suspend fun dismiss(sessionId: String) {
        loaded.join()
        lock.withLock { cancelTimer(sessionId); update(sessionId, null) }
    }

    /** A deleted chat leaves nothing behind. */
    suspend fun forget(sessionId: String) {
        loaded.join()
        lock.withLock {
            cancelTimer(sessionId)
            plansState.value = plansState.value - sessionId
            update(sessionId, null)
        }
    }

    /** Continue every wait whose time has come. The wake-up alarm calls this. */
    suspend fun dispatchDue() {
        loaded.join()
        val due = stopsState.value.values.filter { it.phase == LimitPhase.WAITING && (it.continueAt ?: 0L) <= clock() }
        due.forEach { lock.withLock { due(it.sessionId) } }
    }

    /** When [target] frees up in epoch millis, or null when it is free now. */
    fun freeAt(target: LimitTarget): Long? {
        val now = clock()
        val known = exhausted[target.key]?.takeIf { it > now }
        val read = fullUntil(host.readings(target), now)
        return listOfNotNull(known, read).maxOrNull()
    }

    private suspend fun stopped(sessionId: String, limit: LimitReached) {
        val now = clock()
        val current = host.current(sessionId) ?: return update(sessionId, null)
        val resetsAt = limit.resetsAt?.takeIf { it > 0 }?.times(1000)?.takeIf { it > now } ?: fullUntil(host.readings(current), now)
        exhausted = exhausted.filterValues { it > now } + (current.key to (resetsAt ?: (now + UNKNOWN_RESET_MS)))
        val prior = stopsState.value[sessionId]?.takeIf { it.phase == LimitPhase.CONTINUING }
        update(sessionId, LimitStop(
            sessionId = sessionId,
            target = current,
            resetsAt = resetsAt,
            stoppedAt = now,
            tried = (prior?.tried.orEmpty() + current.key).distinct(),
            attempts = prior?.attempts ?: 0,
        ))
        proceed(sessionId)
    }

    /** Act on the plan for a stopped chat: switch, then wait, else leave it to the user. */
    private suspend fun proceed(sessionId: String) {
        if (!permitted(sessionId)) return
        val stop = stopsState.value[sessionId] ?: return
        val plan = planOf(sessionId)
        if (!plan.active) return
        if (stop.attempts >= MAX_ATTEMPTS) {
            update(sessionId, stop.copy(phase = LimitPhase.STOPPED, reason = "Stopped after $MAX_ATTEMPTS automatic tries."))
            host.note(sessionId, "Mike stopped trying on his own after $MAX_ATTEMPTS automatic tries. Send a message to go on.")
            return
        }
        for (target in plan.switchTo) {
            val latest = stopsState.value[sessionId] ?: return
            if (target.key in latest.tried || freeAt(target) != null) continue
            if (!runCatching { host.canRun(sessionId, target) }.getOrDefault(false)) continue
            if (switchAndContinue(latest, target)) return
        }
        val latest = stopsState.value[sessionId] ?: return
        if (plan.continueAtReset) arm(latest)
        else if (plan.switchTo.isNotEmpty()) {
            update(sessionId, latest.copy(phase = LimitPhase.STOPPED, reason = "Every account in the plan is out of usage."))
        }
    }

    private suspend fun switchAndContinue(stop: LimitStop, target: LimitTarget): Boolean {
        val sessionId = stop.sessionId
        val allowed = permission(sessionId)
        if (!allowed()) return false
        update(sessionId, stop.copy(phase = LimitPhase.SWITCHING, continueAt = null))
        host.note(sessionId, "Usage limit reached on ${host.label(stop.target)}. Moving to ${host.label(target)} to continue.")
        return try {
            if (!allowed()) return false
            host.switchTo(sessionId, target, allowed)
            if (!allowed()) return false
            send(stop.copy(tried = (stop.tried + target.key).distinct()), target)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            host.note(sessionId, "Could not move to ${host.label(target)}: ${e.message ?: e.javaClass.simpleName}")
            // Not tried again for a while, so a wait does not retry it every minute.
            exhausted = exhausted + (target.key to (clock() + UNKNOWN_RESET_MS))
            update(sessionId, stop.copy(phase = LimitPhase.STOPPED, tried = (stop.tried + target.key).distinct()))
            false
        }
    }

    /** Wait for the first target in the plan, or the one that stopped, to free up. */
    private suspend fun arm(stop: LimitStop) {
        if (!permitted(stop.sessionId)) return
        val now = clock()
        val candidates = candidates(stop)
        val at = candidates.map { freeAt(it) ?: now }.minOrNull() ?: (now + UNKNOWN_RESET_MS)
        val continueAt = maxOf(at + RESET_GRACE_MS, now + MIN_WAIT_MS)
        val waiting = stop.copy(phase = LimitPhase.WAITING, continueAt = continueAt, reason = null)
        update(stop.sessionId, waiting)
        schedule(waiting)
        wake()
    }

    private suspend fun due(sessionId: String) {
        if (!permitted(sessionId)) return
        val stop = stopsState.value[sessionId]?.takeIf { it.phase == LimitPhase.WAITING } ?: return
        timers.remove(sessionId)
        val ready = candidates(stop).filter { freeAt(it) == null }
        val pick = ready.firstOrNull { it == stop.target || runCatching { host.canRun(sessionId, it) }.getOrDefault(false) }
        if (pick == null) {
            // Still out, or the reset time was a guess: look again later.
            arm(stop.copy(phase = LimitPhase.STOPPED))
            return
        }
        // A new chain: what was out before may be back by now.
        resume(stop.copy(tried = emptyList()), pick)
    }

    private suspend fun resume(stop: LimitStop, target: LimitTarget) {
        val current = host.current(stop.sessionId) ?: return update(stop.sessionId, null)
        if (target != current) {
            if (!switchAndContinue(stop, target)) arm(stop)
            return
        }
        send(stop.copy(tried = (stop.tried + target.key).distinct()), target)
    }

    private suspend fun send(stop: LimitStop, target: LimitTarget) {
        val allowed = permission(stop.sessionId)
        if (!allowed()) return
        update(stop.sessionId, stop.copy(phase = LimitPhase.CONTINUING, continueAt = null, attempts = stop.attempts + 1, reason = null))
        wake()
        try {
            if (allowed()) host.continueChat(stop.sessionId, target.engine, allowed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            host.note(stop.sessionId, "Could not continue the chat: ${e.message ?: e.javaClass.simpleName}")
            update(stop.sessionId, stopsState.value[stop.sessionId]?.copy(phase = LimitPhase.STOPPED))
        }
    }

    private fun candidates(stop: LimitStop): List<LimitTarget> =
        (listOf(stop.target) + planOf(stop.sessionId).switchTo).distinctBy { it.key }

    private fun schedule(stop: LimitStop) {
        cancelTimer(stop.sessionId)
        val at = stop.continueAt ?: return
        timers[stop.sessionId] = scope.launch {
            delay((at - clock()).coerceAtLeast(0L))
            lock.withLock { due(stop.sessionId) }
        }
    }

    private fun cancelTimer(sessionId: String) { timers.remove(sessionId)?.cancel() }

    /** The alarm is set for the earliest wait, so a closed app still wakes for it. */
    private fun wake() {
        host.wakeAt(stopsState.value.values.filter { it.phase == LimitPhase.WAITING }.mapNotNull { it.continueAt }.minOrNull())
    }

    private suspend fun update(sessionId: String, stop: LimitStop?) {
        stopsState.value = if (stop == null) stopsState.value - sessionId else stopsState.value + (sessionId to stop)
        persist()
        if (stop == null) wake()
    }

    private suspend fun persist() {
        runCatching { book.write(LimitBook.Saved(plansState.value, stopsState.value, exhausted.filterValues { it > clock() })) }
    }

    companion object {
        /** A guess when nobody said when the limit resets: look again after this long. */
        const val UNKNOWN_RESET_MS = 30 * 60_000L
        /** Continue a little after the reset, not on the second of it. */
        const val RESET_GRACE_MS = 60_000L
        const val MIN_WAIT_MS = 60_000L
        const val MAX_ATTEMPTS = 12

        /**
         * Until when [limits] say the account is out: the latest reset among the
         * windows that are full. Null when no window is full.
         */
        fun fullUntil(limits: List<UsageLimit>, nowMillis: Long): Long? = limits
            .filter { (it.usedPercent ?: 0.0) >= 100.0 }
            .mapNotNull { it.resetsAt?.times(1000) }
            .filter { it > nowMillis }
            .maxOrNull()

        /** The prompt of an automatic continuation; the chat shows [CONTINUE_SHOWN] instead. */
        const val CONTINUE_PROMPT =
            "Your previous turn in this chat stopped because the usage limit was reached. Usage is available again. " +
                "Continue the task from where it stopped. Do not repeat actions that already completed; " +
                "check the current state first when you are not sure."
        const val CONTINUE_SHOWN = "Continuing after the usage limit"
    }
}

/** Plans, stops and known reset times, in one owner-only file. */
class LimitBook(private val file: File) {
    @Serializable
    data class Saved(
        val plans: Map<String, LimitPlan> = emptyMap(),
        val stops: Map<String, LimitStop> = emptyMap(),
        val exhausted: Map<String, Long> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun read(): Saved = runCatching {
        if (file.isFile) json.decodeFromString(Saved.serializer(), file.readText()) else Saved()
    }.getOrDefault(Saved())

    @Synchronized
    fun write(saved: Saved) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return
        val temp = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            temp.writeText(json.encodeToString(Saved.serializer(), saved))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
