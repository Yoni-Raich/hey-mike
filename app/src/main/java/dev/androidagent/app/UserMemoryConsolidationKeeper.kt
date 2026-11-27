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
import dev.androidagent.core.CodexAccountVault
import dev.androidagent.core.ConsolidationOutput
import dev.androidagent.core.EngineKind
import dev.androidagent.core.SessionStore
import dev.androidagent.core.UserMemoryConsolidator
import dev.androidagent.core.UserMemoryStore
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.enginecodex.CodexOneShotRunner
import dev.androidagent.enginecodex.ExternalChatgptTokens
import dev.androidagent.runtime.AndroidClaudeHost
import dev.androidagent.runtime.AndroidRuntimeHost
import dev.androidagent.runtime.ClaudeInstallPhase
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
import java.time.ZoneId

/**
 * Runs nightly (or on-demand) cross-engine user-memory consolidation directly
 * on the phone, without creating a visible chat or depending on any computer.
 *
 * Mirrors [UsageWindowKeeper]: one alarm wakes [MemoryConsolidationService],
 * Kotlin collects only new messages from [sessions] (skipping automations and
 * delegated subagents), and one ephemeral, tool-less model call updates
 * `$HOME/memory/profile.md` with automatic rollback backups.
 */
class UserMemoryConsolidationKeeper(
    private val app: Context,
    private val store: UserMemoryStore,
    private val sessions: SessionStore,
    private val vault: CodexAccountVault,
    private val claudeHost: AndroidClaudeHost,
    private val claude: ClaudeCodeEngine,
    private val liveTokens: suspend (refresh: Boolean, previousAccountId: String?) -> ExternalChatgptTokens,
    private val accountFiles: Mutex,
    private val preferredEngine: () -> EngineKind = { EngineKind.CODEX },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val running = Mutex()
    private val sideHome = File(app.filesDir, "runtime/memory-consolidation/.codex")
    private val sideRuntime by lazy { AndroidRuntimeHost(app, sideHome) }

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    fun nextRunAtMillis(): Long? = UserMemoryConsolidator.nextRunAtMillis(store.config(), clock(), zone())

    fun rearm() {
        val next = nextRunAtMillis()
        if (next == null) {
            MemoryConsolidationAlarm.cancel(app)
            return
        }
        val at = maxOf(next, clock() + SOON_MS)
        MemoryConsolidationAlarm.arm(app, at)
    }

    /** Start an immediate consolidation run under [MemoryConsolidationService]. */
    fun startNow() {
        val intent = Intent(app, MemoryConsolidationService::class.java).putExtra(EXTRA_FORCE, true)
        val started = runCatching { ContextCompat.startForegroundService(app, intent) }
        if (started.isFailure) {
            Log.w(TAG, "Foreground start failed for manual memory sync: ${started.exceptionOrNull()?.message}")
        }
    }

    /** Run consolidation now if due, or unconditionally when [force] is true. */
    suspend fun run(force: Boolean = false) = running.withLock {
        val cfg = store.config()
        if (!force) {
            if (!cfg.enabled || !cfg.autoLearn) return@withLock
            val dueAt = UserMemoryConsolidator.nextRunAtMillis(cfg, clock(), zone()) ?: return@withLock
            if (dueAt > clock() + DUE_SLACK_MS) return@withLock
        }
        mutableBusy.value = true
        try {
            val excluded = automationChatIds()
            val digest = withContext(Dispatchers.IO) {
                UserMemoryConsolidator.buildDigest(sessions, cfg.lastConsolidatedAt, excluded, zone())
            }
            if (digest.isEmpty) {
                withContext(Dispatchers.IO) {
                    store.recordConsolidation(
                        ok = true,
                        summary = "Up to date · no new chats",
                        processedChats = 0,
                    )
                }
                return@withLock
            }

            val currentProfile = withContext(Dispatchers.IO) { store.read() }
            val prompt = UserMemoryConsolidator.buildPrompt(currentProfile, digest)
            val reply = runOneShot(UserMemoryConsolidator.CONSOLIDATION_INSTRUCTIONS, prompt)
            when (val parsed = UserMemoryConsolidator.parseOutput(reply, currentProfile)) {
                ConsolidationOutput.NoChanges -> withContext(Dispatchers.IO) {
                    store.recordConsolidation(
                        ok = true,
                        summary = "Reviewed ${digest.chatCount} chat(s) · no durable changes",
                        processedChats = digest.chatCount,
                        newWatermark = digest.latestMessageAt,
                    )
                }
                is ConsolidationOutput.Updated -> withContext(Dispatchers.IO) {
                    store.recordConsolidation(
                        ok = true,
                        summary = "Updated from ${digest.chatCount} chat(s)",
                        processedChats = digest.chatCount,
                        newWatermark = digest.latestMessageAt,
                        updatedProfile = parsed.profile,
                    )
                }
                is ConsolidationOutput.Invalid -> withContext(Dispatchers.IO) {
                    store.recordConsolidation(
                        ok = false,
                        summary = parsed.reason,
                        processedChats = digest.chatCount,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) {
                runCatching { store.recordConsolidation(ok = false, summary = "Stopped before finishing", processedChats = 0) }
            }
            throw cancelled
        } catch (failure: Exception) {
            val reason = shortReason(failure.message ?: failure::class.java.simpleName)
            Log.w(TAG, "Memory consolidation failed: $reason")
            withContext(Dispatchers.IO) {
                store.recordConsolidation(ok = false, summary = reason, processedChats = 0)
            }
        } finally {
            mutableBusy.value = false
            rearm()
        }
    }

    private suspend fun runOneShot(instructions: String, prompt: String): String {
        val codexReady = withContext(Dispatchers.IO) { vault.state().activeId != null }
        val claudeReady = claudeHost.installState.value.phase == ClaudeInstallPhase.INSTALLED &&
            runCatching { claude.account().signedIn }.getOrDefault(false)

        val order = when (preferredEngine()) {
            EngineKind.CLAUDE -> listOf(EngineKind.CLAUDE, EngineKind.CODEX)
            else -> listOf(EngineKind.CODEX, EngineKind.CLAUDE)
        }
        var lastError: Exception? = null
        for (engine in order) {
            if (engine == EngineKind.CODEX && !codexReady) continue
            if (engine == EngineKind.CLAUDE && !claudeReady) continue
            try {
                return when (engine) {
                    EngineKind.CODEX -> runCodex(instructions, prompt)
                    EngineKind.CLAUDE -> claude.runOneShot(instructions, prompt)
                    EngineKind.ANTIGRAVITY -> continue
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastError = failure
            }
        }
        throw lastError ?: IllegalStateException("Sign in to Codex or Claude in Accounts to update memory.")
    }

    private suspend fun runCodex(instructions: String, prompt: String): String = accountFiles.withLock {
        val auth = File(sideHome, CodexAccountVault.AUTH_FILE)
        val runner = CodexOneShotRunner(sideRuntime)
        try {
            withContext(Dispatchers.IO) { auth.delete() }
            runner.complete(
                instructions = instructions,
                prompt = prompt,
                tokens = liveTokens(false, null),
                refresh = { previous -> liveTokens(true, previous) },
            )
        } finally {
            withContext(Dispatchers.IO) { auth.delete() }
        }
    }

    private fun automationChatIds(): Set<String> = runCatching {
        app.getSharedPreferences(AUTOMATION_CHATS_PREFS, Context.MODE_PRIVATE)
            .all.values.filterIsInstance<String>().toSet()
    }.getOrDefault(emptySet())

    private fun shortReason(message: String): String =
        message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: message

    companion object {
        const val TAG = "UserMemorySync"
        const val EXTRA_FORCE = "force"
        private const val AUTOMATION_CHATS_PREFS = "automation_chats"
        private const val SOON_MS = 5_000L
        private const val DUE_SLACK_MS = 60_000L
    }
}

internal object MemoryConsolidationAlarm {
    private const val ACTION_FIRE = "dev.androidagent.app.MEMORY_CONSOLIDATION"
    private const val REQUEST_CODE = 0x4D45

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
        val intent = Intent(context, MemoryConsolidationReceiver::class.java).setAction(ACTION_FIRE)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

class MemoryConsolidationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        graph.scope.launch(Dispatchers.IO) { graph.memoryConsolidation.rearm() }
    }
}

class MemoryConsolidationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!MemoryConsolidationAlarm.isFire(intent)) return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val started = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MemoryConsolidationService::class.java))
        }
        if (started.isSuccess) return
        Log.w(UserMemoryConsolidationKeeper.TAG, "Foreground start refused: ${started.exceptionOrNull()?.message}")
        val pending = goAsync()
        graph.scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { graph.memoryConsolidation.run(force = false) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BROADCAST_BUDGET_MS = 50_000L
    }
}

class MemoryConsolidationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "User memory", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Updating Mike memory")
            .setContentText("Summarizing durable facts from recent chats")
            .setSilent(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(UserMemoryConsolidationKeeper.TAG, "Could not go foreground: ${it.message}")
        }
        val force = intent?.getBooleanExtra(UserMemoryConsolidationKeeper.EXTRA_FORCE, false) == true
        val graph = (application as AgentApplication).graph
        scope.launch {
            try {
                graph.memoryConsolidation.run(force = force)
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
        const val CHANNEL = "user_memory"
        const val NOTIFICATION_ID = 0x4D45
    }
}
