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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.androidagent.core.AccountUsageBook
import dev.androidagent.core.CodexAccountVault
import dev.androidagent.core.EngineKind
import dev.androidagent.core.LastUsageStore
import dev.androidagent.core.UsageWindowJournal
import dev.androidagent.core.UsageWindowPlanner
import dev.androidagent.core.WindowStatus
import dev.androidagent.core.WindowTarget
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.enginecodex.CodexWindowStarter
import dev.androidagent.enginecodex.ExternalChatgptTokens
import dev.androidagent.runtime.AndroidRuntimeHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the settings show: whether it is on, whether a start runs now, and each account. */
data class UsageWindowsState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val accounts: List<WindowStatus> = emptyList(),
)

/**
 * Starts each account's 5-hour usage window the minute the last one ends.
 *
 * A plan's window starts at the first message after a reset, not at the
 * reset. So when one ends Mike sends the account one short message, and the
 * next five hours start counting then instead of when the person comes back.
 *
 * Every saved Codex account is covered, not only the live one: a saved
 * account runs on a second, private app-server with its sign-in lent by the
 * vault, and the live one signs that app-server in with its current tokens,
 * so nothing switches and no chat is touched. Claude has one sign-in and runs
 * a throwaway `claude -p`. Each start is recorded in [journal], and the reading
 * it brings back goes where the widget reads it, which also tells the
 * planner when the new window ends.
 *
 * One alarm, re-armed after every start, reading and account change, wakes
 * [UsageWindowService], which keeps the process up while the starts run.
 */
class UsageWindowKeeper(
    private val app: Context,
    private val journal: UsageWindowJournal,
    private val vault: CodexAccountVault,
    private val usageBook: AccountUsageBook,
    private val claudeUsage: LastUsageStore,
    private val claude: ClaudeCodeEngine,
    /** The live account's tokens; with `true`, refreshed by the main app-server first. */
    private val liveTokens: suspend (refresh: Boolean, previousAccountId: String?) -> ExternalChatgptTokens,
    /** Held by anything that swaps or lends a sign-in, so a start never overlaps an account switch. */
    private val accountFiles: Mutex,
    /** A new reading is on disk: redraw the widget, tell the open screen. */
    private val onReading: suspend (engine: EngineKind, live: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()
    private val sideHome = File(app.filesDir, "runtime/usage-windows/.codex")
    private val sideRuntime by lazy { AndroidRuntimeHost(app, sideHome) }
    private val mutableState = MutableStateFlow(UsageWindowsState())
    val state: StateFlow<UsageWindowsState> = mutableState.asStateFlow()

    fun setEnabled(value: Boolean) {
        journal.setEnabled(value)
        rearm()
    }

    /** Every account Mike can start a window for: each saved Codex sign-in, then Claude when signed in. */
    fun targets(): List<WindowTarget> {
        val readings = usageBook.all()
        val codex = vault.state().accounts.map { account ->
            WindowTarget(account.id, EngineKind.CODEX, account.label, readings[account.id]?.limits.orEmpty())
        }
        val claudeLabel = claudeUsage.account() ?: return codex
        return codex + WindowTarget(CLAUDE_ID, EngineKind.CLAUDE, claudeLabel, claudeUsage.readSaved()?.limits.orEmpty())
    }

    /** Set the alarm for the next start, or cancel it when off. Cheap: reads three small files. */
    fun rearm() {
        val enabled = journal.enabled()
        if (!enabled) {
            UsageWindowAlarm.cancel(app)
            publish()
            return
        }
        val now = clock()
        val plan = UsageWindowPlanner.plan(targets(), journal.attempts(), now)
        // Something due now still goes through the alarm, so it runs under the service.
        val at = if (plan.due.isNotEmpty()) now + SOON_MS else plan.nextAtMillis
        if (at == null) UsageWindowAlarm.cancel(app) else UsageWindowAlarm.arm(app, at)
        publish()
    }

    /** Start every window that is due. A second call waits for the first and then finds nothing due. */
    suspend fun runDue() = running.withLock {
        try {
            if (!journal.enabled()) return@withLock
            publish(busy = true)
            val plan = UsageWindowPlanner.plan(targets(), journal.attempts(), clock())
            for (target in plan.due) {
                val failure = runCatching { start(target) }.exceptionOrNull()
                failure?.let { Log.w(TAG, "Could not start the ${target.engine} usage window: ${it.message}") }
                val reason = if (failure is CancellationException) "Stopped before it finished" else failure?.message?.let(::shortReason)
                journal.record(target.id, failure == null, reason, targets().map { it.id })
                // Cut short (the broadcast's time ran out): the back-off retries the rest.
                if (failure is CancellationException) throw failure
                publish(busy = true)
            }
        } finally {
            publish(busy = false)
            rearm()
        }
    }

    private suspend fun start(target: WindowTarget) = when (target.engine) {
        EngineKind.CODEX -> startCodex(target.id)
        EngineKind.CLAUDE -> {
            val limits = claude.startUsageWindow()
            if (limits.isNotEmpty()) withContext(Dispatchers.IO) { claudeUsage.save(limits) }
            onReading(EngineKind.CLAUDE, false)
        }
    }

    private suspend fun startCodex(id: String) {
        var live = false
        accountFiles.withLock {
            val accounts = withContext(Dispatchers.IO) { vault.state() }
            if (accounts.accounts.none { it.id == id }) return
            live = accounts.activeId == id
            val auth = File(sideHome, CodexAccountVault.AUTH_FILE)
            val starter = CodexWindowStarter(sideRuntime)
            val limits = try {
                if (live) {
                    withContext(Dispatchers.IO) { auth.delete() }
                    starter.start(liveTokens(false, null)) { previous -> liveTokens(true, previous) }
                } else {
                    withContext(Dispatchers.IO) { vault.lend(id, auth) }
                    try { starter.start() } finally { withContext(Dispatchers.IO) { vault.takeBack(id, auth) } }
                }
            } finally {
                // A copy of a sign-in never stays outside the vault.
                withContext(Dispatchers.IO) { auth.delete() }
            }
            if (limits.isNotEmpty()) withContext(Dispatchers.IO) { usageBook.record(id, limits, vault.state().accounts.map { it.id }) }
        }
        onReading(EngineKind.CODEX, live)
    }

    private fun publish(busy: Boolean = mutableState.value.busy) {
        val enabled = journal.enabled()
        val accounts = if (enabled) journal.status(targets(), clock()) else emptyList()
        mutableState.value = UsageWindowsState(enabled, busy, accounts)
    }

    /** The first line of an error, without the noise a stack of wrappers adds. */
    private fun shortReason(message: String): String = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: message

    companion object {
        const val TAG = "UsageWindows"
        /** The id of the Claude row, as in the widget. */
        const val CLAUDE_ID = dev.androidagent.core.AccountUsageOverview.CLAUDE_ID
        private const val SOON_MS = 2_000L
    }
}

/** The one alarm. Exact when Android allows it; otherwise Doze may land it late, which the settings say. */
internal object UsageWindowAlarm {
    private const val ACTION_FIRE = "dev.androidagent.app.USAGE_WINDOWS"
    private const val REQUEST_CODE = 0x5557

    fun canScheduleExact(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    fun arm(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, create = true) ?: return
        runCatching {
            if (canScheduleExact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }.onFailure {
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        }
    }

    fun cancel(context: Context) {
        val pending = pendingIntent(context, create = false) ?: return
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending) }
        pending.cancel()
    }

    fun isFire(intent: Intent?): Boolean = intent?.action == ACTION_FIRE

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, UsageWindowReceiver::class.java).setAction(ACTION_FIRE)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

/** An alarm does not survive a restart or an app update, so either only re-arms it. */
class UsageWindowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        graph.scope.launch(Dispatchers.IO) { graph.usageWindows.rearm() }
    }
}

/** The alarm landed: start what is due under [UsageWindowService]. */
class UsageWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!UsageWindowAlarm.isFire(intent)) return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val started = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, UsageWindowService::class.java))
        }
        if (started.isSuccess) return
        // Android refused a foreground service from the background (an
        // inexact alarm does not allow one). Run inside the broadcast's time
        // instead; a start cut short is retried by the back-off.
        Log.w(UsageWindowKeeper.TAG, "Foreground start refused: ${started.exceptionOrNull()?.message}")
        val pending = goAsync()
        graph.scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { graph.usageWindows.runDue() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BROADCAST_BUDGET_MS = 50_000L
    }
}

/** Keeps the process up for the half minute a start takes, with a quiet notification that says so. */
class UsageWindowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Usage windows", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Starting usage windows")
            .setContentText("Sending a short message so the next 5 hours start now")
            .setSilent(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(UsageWindowKeeper.TAG, "Could not go foreground: ${it.message}")
        }
        val graph = (application as AgentApplication).graph
        scope.launch {
            try {
                graph.usageWindows.runDue()
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "usage_windows"
        const val NOTIFICATION_ID = 0x5557
    }
}
