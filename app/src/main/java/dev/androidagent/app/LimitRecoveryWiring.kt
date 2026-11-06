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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.EngineKind
import dev.androidagent.core.LimitRecoveryHost
import dev.androidagent.core.LimitTarget
import dev.androidagent.core.QueuedTurn
import dev.androidagent.core.UsageLimit
import dev.androidagent.core.UsageLimitRecovery
import dev.androidagent.core.label
import dev.androidagent.runtime.ClaudeInstallPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * What [UsageLimitRecovery] does through the app: which account a chat is on,
 * the Codex account switch, the queue, and the wake-up alarm.
 */
internal class AppLimitRecoveryHost(private val app: Context, private val graph: AgentGraph) : LimitRecoveryHost {
    private val alarms = LimitAlarm(app)

    override suspend fun current(sessionId: String): LimitTarget? {
        val session = graph.sessions.getSession(sessionId) ?: return null
        return when (session.engine) {
            EngineKind.CODEX -> LimitTarget(EngineKind.CODEX, liveAccount())
            EngineKind.CLAUDE -> LimitTarget(EngineKind.CLAUDE)
        }
    }

    override suspend fun canRun(sessionId: String, target: LimitTarget): Boolean = when (target.engine) {
        EngineKind.CODEX -> target.accountId == null ||
            withContext(Dispatchers.IO) { graph.accounts.state().accounts.any { it.id == target.accountId } }
        // A computer's Claude is its own sign-in there; the plan only offers it when that computer has one.
        EngineKind.CLAUDE -> graph.computers.binding(sessionId) != null ||
            (graph.claudeHost.installState.value.phase == ClaudeInstallPhase.INSTALLED && graph.claudeUsage.account() != null)
    }

    override fun readings(target: LimitTarget): List<UsageLimit> = runCatching {
        when (target.engine) {
            EngineKind.CODEX -> target.accountId?.let { graph.usageBook.all()[it]?.limits }.orEmpty()
            EngineKind.CLAUDE -> graph.claudeUsage.readSaved()?.limits.orEmpty()
        }
    }.getOrDefault(emptyList())

    override suspend fun switchTo(sessionId: String, target: LimitTarget, allowed: () -> Boolean) {
        val account = target.accountId
        if (target.engine == EngineKind.CODEX && account != null && account != liveAccount()) {
            graph.switchCodexAccount(account, allowed = allowed)
        }
        // A change of engine travels with the continuation turn itself: the
        // coordinator moves the chat and tells the new engine what was said.
    }

    override suspend fun continueChat(sessionId: String, engine: EngineKind, allowed: () -> Boolean) {
        val session = graph.sessions.getSession(sessionId) ?: error("Chat no longer exists.")
        if (!allowed()) return
        // The chat's own model stays when the engine does; a new engine starts on its default.
        val same = session.engine == engine
        // The app may have been woken by the alarm alone: the run needs the service to stay alive.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, AgentService::class.java)) }
        if (!allowed()) return
        graph.queue.submit(QueuedTurn(
            sessionId = sessionId,
            prompt = UsageLimitRecovery.CONTINUE_PROMPT,
            model = session.model?.takeIf { same },
            effort = session.reasoningEffort?.takeIf { same },
            engine = engine,
            displayPrompt = UsageLimitRecovery.CONTINUE_SHOWN,
            agentContext = true,
            requiresLiveGuard = true,
            dispatchGuard = allowed,
        ))
    }

    override suspend fun note(sessionId: String, text: String) {
        if (graph.sessions.getSession(sessionId) == null) return
        graph.sessions.append(ChatMessage(UUID.randomUUID().toString(), sessionId, "note", text, System.currentTimeMillis()))
    }

    override fun wakeAt(atMillis: Long?) = if (atMillis == null) alarms.cancel() else alarms.arm(atMillis)

    override fun label(target: LimitTarget): String = when (target.engine) {
        EngineKind.CLAUDE -> "Claude"
        EngineKind.CODEX -> target.accountId
            ?.let { id -> runCatching { graph.accounts.state().accounts.firstOrNull { it.id == id }?.label }.getOrNull() }
            ?.let { "Codex ($it)" } ?: EngineKind.CODEX.label
    }

    private suspend fun liveAccount(): String? = withContext(Dispatchers.IO) { runCatching { graph.accounts.state().activeId }.getOrNull() }
}

/**
 * One alarm for the earliest chat waiting for its usage to reset, so the wait
 * holds when the app is closed. Exact when the user allowed exact alarms,
 * otherwise as close as Doze lets it; the chat continues either way.
 */
private class LimitAlarm(private val context: Context) {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun arm(atMillis: Long) {
        val manager = manager ?: return
        val pending = pending(create = true) ?: return
        val exact = runCatching { Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }.onFailure { runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) } }
    }

    fun cancel() {
        val pending = pending(create = false) ?: return
        runCatching { manager?.cancel(pending) }
        pending.cancel()
    }

    private fun pending(create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, LimitWakeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE),
    )

    companion object {
        const val ACTION = "dev.androidagent.app.LIMIT_RESET"
        const val REQUEST_CODE = 0x4C52
    }
}

/** A chat's usage resets now. It only wakes the app: what is due is decided from the saved waits. */
class LimitWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        // The service keeps the process alive for the turn; an exact alarm may start it from the background.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
        val done = goAsync()
        val work = graph.scope.launch { graph.limitRecovery.dispatchDue() }
        // A broadcast has seconds, a switch of account may take longer: the work goes on in the app's scope.
        graph.scope.launch {
            withTimeoutOrNull(8_000) { work.join() }
            done.finish()
        }
    }
}
