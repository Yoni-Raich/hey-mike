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

package dev.androidagent.app.panels

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.androidagent.a11y.A11yServiceHandle
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.R
import dev.androidagent.core.PanelFace
import dev.androidagent.core.PanelHost
import dev.androidagent.core.PanelLive
import dev.androidagent.core.PanelSurface
import dev.androidagent.core.RunState
import dev.androidagent.core.SavedPanel
import dev.androidagent.overlay.FloatingPanelOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draws saved panels where they live: floating cards, ongoing notifications
 * and home-screen widgets. Every surface reads the same [PanelFace], and a
 * panel that leaves [PanelHost.panels] (removed, expired, blocked) leaves its
 * surface on the next draw. Taps go to [PanelHost], which checks each call
 * against the allowlist again and logs it.
 */
class SavedPanelSurfaces(
    private val app: Context,
    private val scope: CoroutineScope,
    private val host: PanelHost,
    private val runStates: StateFlow<Map<String, RunState>>,
    private val appInFront: StateFlow<Boolean>,
) {
    private val floating = FloatingPanelOverlay(
        app,
        accessibilityService = { A11yServiceHandle.service.value },
        onAction = { panel, action -> host.runAction(panel, action) },
        onSlider = { panel, slider, value -> host.runSlider(panel, slider, value) },
        onClose = ::close,
    )
    private val notifications = SavedPanelNotifications(app)
    private var floatingShown = emptySet<String>()
    private val listOpen = MutableStateFlow(false)

    /** The app context, for the Saved panels screen. */
    val context: Context get() = app

    /** The Saved panels screen is in front: floating cards step aside so they never cover its buttons. */
    fun setListOpen(open: Boolean) { listOpen.value = open }

    /** True when a floating card can be drawn: the accessibility service or Display over other apps. */
    fun canFloat(): Boolean = floating.canShow()

    fun start() {
        // Floating cards step aside while Mike controls the screen, so his
        // screenshots and taps never meet them, and while the app is in front.
        val controlling = runStates.map { states -> states.values.any { it.controlling } }.distinctUntilChanged()
        val inApp = combine(appInFront, listOpen) { front, list -> front || list }.distinctUntilChanged()
        scope.launch {
            combine(host.panels, host.live, controlling, inApp, A11yServiceHandle.service) { panels, live, busy, front, _ ->
                Draw(panels, live, busy || front)
            }.collect { draw ->
                floating.refreshWindowHost()
                val faces = if (draw.away) emptyList() else PanelFace.on(PanelSurface.FLOATING, draw.panels, draw.live)
                val shown = runCatching { floating.render(faces) }.getOrElse { floating.hide(); emptySet() }
                (floatingShown - shown).forEach { host.setShown(it, false) }
                shown.forEach { host.setShown(it, true) }
                floatingShown = shown
            }
        }
        scope.launch {
            combine(host.panels, host.live) { panels, live -> panels to live }.collect { (panels, live) ->
                notifications.render(panels, live).let { shown ->
                    panels.filter { it.placement?.surface == PanelSurface.NOTIFICATION }.forEach { saved ->
                        host.setShown(saved.id, saved.id in shown, NOTIFICATION_FLOOR_MS)
                    }
                }
                notifications.approvals(panels)
                SavedPanelWidget.render(app, host, panels, live).let { shown ->
                    panels.filter { it.placement?.surface == PanelSurface.WIDGET }.forEach { saved ->
                        host.setShown(saved.id, saved.id in shown, WIDGET_FLOOR_MS)
                    }
                }
            }
        }
        scope.launch {
            host.approvals.collect { id -> host.get(id)?.let(notifications::askApproval) }
        }
    }

    /** Close on a floating card: a short card goes, a kept one hides until shown again from Saved panels. */
    private fun close(panelId: String) {
        val saved = host.get(panelId) ?: return
        scope.launch {
            if (saved.placement?.needsApproval == true) host.setHidden(panelId, true) else host.remove(panelId)
        }
    }

    private data class Draw(val panels: List<SavedPanel>, val live: Map<String, PanelLive>, val away: Boolean)

    companion object {
        /** A notification's live values are read at most this often, whatever the panel asks. */
        const val NOTIFICATION_FLOOR_MS = 15_000L
        /** A widget's, likewise: launchers redraw widgets slowly and every redraw costs. */
        const val WIDGET_FLOOR_MS = 30_000L
    }
}

/** The ongoing notification for each notification panel, and the approval prompt. */
internal class SavedPanelNotifications(private val context: Context) {
    private val posted = mutableMapOf<String, String>()
    private val asking = mutableSetOf<String>()

    /** Posts or updates every shown notification panel and cancels the rest. Returns the ids posted. */
    fun render(panels: List<SavedPanel>, live: Map<String, PanelLive>): Set<String> {
        val manager = NotificationManagerCompat.from(context)
        val faces = PanelFace.on(PanelSurface.NOTIFICATION, panels, live).associateBy { it.id }
        (posted.keys - faces.keys).toList().forEach { gone ->
            runCatching { manager.cancel(gone, PANEL_ID) }
            posted.remove(gone)
        }
        if (!manager.areNotificationsEnabled()) return emptySet()
        faces.values.forEach { face ->
            // Only a change re-posts: a tick that read the same values stays quiet.
            val signature = face.toString()
            if (posted[face.id] == signature) return@forEach
            val ok = try {
                ensureChannels()
                manager.notify(face.id, PANEL_ID, panel(face))
                true
            } catch (denied: SecurityException) {
                false
            }
            if (ok) posted[face.id] = signature else posted.remove(face.id)
        }
        return posted.keys.toSet()
    }

    /** Asks for approval of [saved]: the notification only opens the app's screen; approving happens there. */
    fun askApproval(saved: SavedPanel) {
        if (saved.status != SavedPanel.Status.PENDING) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Mike wants to keep a panel")
            .setContentText("\"${saved.title}\" waits for your approval in Saved panels.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openList(saved.id))
            .build()
        try {
            ensureChannels()
            manager.notify(saved.id, APPROVAL_ID, notification)
            asking += saved.id
        } catch (_: SecurityException) {
        }
    }

    /** Cancels the approval prompt of every panel that no longer waits. */
    fun approvals(panels: List<SavedPanel>) {
        val waiting = panels.filter { it.status == SavedPanel.Status.PENDING }.map { it.id }.toSet()
        val manager = NotificationManagerCompat.from(context)
        (asking - waiting).toList().forEach { done ->
            runCatching { manager.cancel(done, APPROVAL_ID) }
            asking.remove(done)
        }
    }

    private fun panel(face: PanelFace): Notification {
        val text = face.summary.ifBlank { face.rows.filterIsInstance<PanelFace.Row.Note>().firstOrNull()?.text.orEmpty() }
        val problem = face.tiles.firstNotNullOfOrNull { it.problem }
        fun base(withValues: Boolean) = NotificationCompat.Builder(context, PANELS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(face.title.ifBlank { "Mike" })
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openList(face.id))
            .setDeleteIntent(SavedPanelActionReceiver.dismiss(context, face.id))
            .also { builder ->
                if (withValues) {
                    builder.setContentText(problem ?: text)
                    if (text.isNotBlank()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, problem).joinToString("\n")))
                } else {
                    builder.setContentText("Saved panel")
                }
                face.tiles.take(MAX_BUTTONS).forEach { tile ->
                    val label = when {
                        !withValues -> tile.label
                        tile.running -> "${tile.label} …"
                        tile.on == true -> "${tile.label}: on"
                        tile.on == false -> "${tile.label}: off"
                        else -> tile.label
                    }
                    builder.addAction(
                        NotificationCompat.Action.Builder(0, label, SavedPanelActionReceiver.tap(context, face.id, tile.actionId))
                            // Allowlisted calls only (a setting or a media key), so the buttons
                            // work on the lock screen too, as the system torch tile does.
                            .setAuthenticationRequired(false)
                            .build(),
                    )
                }
            }
        // On the lock screen: the title and the buttons, never the values (a song or a reading).
        return base(withValues = true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base(withValues = false).build())
            .build()
    }

    private fun openList(panelId: String): PendingIntent = PendingIntent.getActivity(
        context, ("open:$panelId").hashCode(),
        SavedPanelsActivity.intent(context, panelId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(PANELS, "Saved panels", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Panels you kept from Mike, with their buttons."
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(APPROVALS, "Panels to approve", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A panel Mike made that waits for your approval."
                },
            )
        }
    }

    companion object {
        const val PANELS = "saved_panels"
        const val APPROVALS = "saved_panel_approvals"
        /** One per panel; the panel's id is the tag. */
        const val PANEL_ID = 7_401
        const val APPROVAL_ID = 7_402
        const val MAX_BUTTONS = 3
    }
}

/**
 * A tap on a saved panel's notification or widget button, a notification the
 * user swiped away, or a widget the launcher just placed. Not exported: only
 * this app's own pending intents reach it.
 */
class SavedPanelActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val host = graph.panels
        val panelId = intent.getStringExtra(EXTRA_PANEL) ?: return
        when (intent.action) {
            ACTION_TAP -> {
                val actionId = intent.getStringExtra(EXTRA_ACTION) ?: return
                if (!host.runAction(panelId, actionId)) return
                // Keep the process up until the call has finished, well inside a receiver's time.
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        withTimeoutOrNull(TAP_WAIT_MS) {
                            while (host.state(panelId, actionId).running) delay(100)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DISMISS -> {
                val pending = goAsync()
                graph.scope.launch {
                    try { host.setHidden(panelId, true) } finally { pending.finish() }
                }
            }
            ACTION_PINNED -> {
                val widgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (widgetId >= 0) SavedPanelWidget.bind(context.applicationContext, host, widgetId, panelId)
            }
        }
    }

    companion object {
        const val ACTION_TAP = "dev.androidagent.app.SAVED_PANEL_TAP"
        const val ACTION_DISMISS = "dev.androidagent.app.SAVED_PANEL_DISMISS"
        const val ACTION_PINNED = "dev.androidagent.app.SAVED_PANEL_PINNED"
        const val EXTRA_PANEL = "panel"
        const val EXTRA_ACTION = "action"
        private const val TAP_WAIT_MS = 8_000L

        fun tap(context: Context, panelId: String, actionId: String): PendingIntent = PendingIntent.getBroadcast(
            context, "tap:$panelId:$actionId".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_TAP)
                // A distinct address per button, so two buttons never share one pending intent.
                .setData(Uri.parse("heymike-panel://tap/${Uri.encode(panelId)}/${Uri.encode(actionId)}"))
                .putExtra(EXTRA_PANEL, panelId)
                .putExtra(EXTRA_ACTION, actionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun dismiss(context: Context, panelId: String): PendingIntent = PendingIntent.getBroadcast(
            context, "dismiss:$panelId".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(Uri.parse("heymike-panel://dismiss/${Uri.encode(panelId)}"))
                .putExtra(EXTRA_PANEL, panelId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * The launcher's answer to a pin request. Mutable, because the launcher
         * writes the new widget's id into it; the panel id is already set and
         * a fill-in cannot replace it.
         */
        fun pinned(context: Context, panelId: String): PendingIntent = PendingIntent.getBroadcast(
            context, "pinned:$panelId".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_PINNED)
                .setData(Uri.parse("heymike-panel://pinned/${Uri.encode(panelId)}"))
                .putExtra(EXTRA_PANEL, panelId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
