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

import android.app.AlarmManager
import android.app.KeyguardManager
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
import androidx.core.app.RemoteInput
import dev.androidagent.a11y.A11yServiceHandle
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.R
import dev.androidagent.core.PanelEffect
import dev.androidagent.core.PanelFace
import dev.androidagent.core.PanelHost
import dev.androidagent.core.PanelLive
import dev.androidagent.core.PanelOp
import dev.androidagent.core.PanelState
import dev.androidagent.core.PanelStates
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
import kotlinx.coroutines.Dispatchers
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
        onAction = { panel, action -> tap(panel, action) },
        onSlider = { panel, slider, value -> host.runSlider(panel, slider, value) },
        onClose = ::close,
        onOp = { panel, op -> host.edit(panel, op) },
        onOpenList = { panel -> runCatching { app.startActivity(SavedPanelsActivity.intent(app, panel)) } },
    )
    private val notifications = SavedPanelNotifications(app)
    private var floatingShown = emptySet<String>()
    private val listOpen = MutableStateFlow(false)

    /** The app context, for the Saved panels screen. */
    val context: Context get() = app

    /** The Saved panels screen is in front: floating cards step aside so they never cover its buttons. */
    fun setListOpen(open: Boolean) { listOpen.value = open }

    /** Posts [panelId]'s notification again on the next draw (after a reply). */
    fun repost(panelId: String) = notifications.repost(panelId)

    /** True when a floating card can be drawn: the accessibility service or Display over other apps. */
    fun canFloat(): Boolean = floating.canShow()

    fun start() {
        // Floating cards step aside while Mike controls the screen, so his
        // screenshots and taps never meet them, and while the app is in front.
        val controlling = runStates.map { states -> states.values.any { it.controlling } }.distinctUntilChanged()
        val inApp = combine(appInFront, listOpen) { front, list -> front || list }.distinctUntilChanged()
        val faces = combine(host.panels, host.live, host.states) { panels, live, states -> Triple(panels, live, states) }
        scope.launch {
            combine(faces, controlling, inApp, A11yServiceHandle.service) { (panels, live, states), busy, front, _ ->
                Draw(panels, live, states, busy || front)
            }.collect { draw ->
                floating.refreshWindowHost()
                val faces = if (draw.away) emptyList() else PanelFace.on(PanelSurface.FLOATING, draw.panels, draw.live, draw.states)
                val shown = runCatching { floating.render(faces) }.getOrElse { floating.hide(); emptySet() }
                (floatingShown - shown).forEach { host.setShown(it, false) }
                shown.forEach { host.setShown(it, true) }
                floatingShown = shown
            }
        }
        // Notifications and widgets are built and posted off the main thread:
        // each change to a panel, a live value or a tile's state used to
        // build them there, beside the chat's frames.
        scope.launch(Dispatchers.Default) {
            combine(faces, notifications.nudges) { draw, _ -> draw }.collect { (panels, live, states) ->
                notifications.render(panels, live, states).let { shown ->
                    panels.filter { it.placement?.surface == PanelSurface.NOTIFICATION }.forEach { saved ->
                        host.setShown(saved.id, saved.id in shown, NOTIFICATION_FLOOR_MS)
                    }
                }
                notifications.approvals(panels)
                SavedPanelWidget.render(app, host, panels, live, states).let { shown ->
                    panels.filter { it.placement?.surface == PanelSurface.WIDGET }.forEach { saved ->
                        host.setShown(saved.id, saved.id in shown, WIDGET_FLOOR_MS)
                    }
                }
            }
        }
        scope.launch {
            host.approvals.collect { id -> host.get(id)?.let(notifications::askApproval) }
        }
        scope.launch { host.alerts.collect(notifications::alert) }
        // One alarm for the next timer end or reset, so it happens with the app asleep or gone.
        scope.launch {
            combine(host.panels, host.states) { _, _ -> host.nextDueAtMs() }.distinctUntilChanged().collect { at ->
                SavedPanelAlarm.schedule(app, at)
            }
        }
    }

    /** A tap on a floating card's button. An `open` button starts its target from the app's own activity. */
    private fun tap(panelId: String, actionId: String): Boolean {
        if (SavedPanelNotifications.opens(host, panelId, actionId)) {
            return runCatching { app.startActivity(PanelOpenActivity.intent(app, panelId, actionId)); true }.getOrDefault(false)
        }
        return host.runAction(panelId, actionId)
    }

    /** Close on a floating card: a short card goes, a kept one hides until shown again from Saved panels. */
    private fun close(panelId: String) {
        val saved = host.get(panelId) ?: return
        scope.launch {
            if (saved.placement?.needsApproval == true) host.setHidden(panelId, true) else host.remove(panelId)
        }
    }

    private data class Draw(
        val panels: List<SavedPanel>, val live: Map<String, PanelLive>, val states: Map<String, PanelState>, val away: Boolean,
    )

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
    private val _nudges = MutableStateFlow(0)
    /** Bumped when a notification must be posted again although its face is the same (a reply that changed nothing). */
    val nudges: StateFlow<Int> = _nudges

    /** Posts [panelId] again on the next draw, so a reply's spinner clears even when the reply was refused. */
    fun repost(panelId: String) {
        synchronized(posted) { posted.remove(panelId) }
        _nudges.value += 1
    }

    /** Posts or updates every shown notification panel and cancels the rest. Returns the ids posted. */
    fun render(panels: List<SavedPanel>, live: Map<String, PanelLive>, states: Map<String, PanelState> = emptyMap()): Set<String> = synchronized(posted) {
        val manager = NotificationManagerCompat.from(context)
        val faces = PanelFace.on(PanelSurface.NOTIFICATION, panels, live, states).associateBy { it.id }
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
        posted.keys.toSet()
    }

    /** A countdown on a panel ended: one notification that pops up, silent unless the user gave its channel a sound. */
    fun alert(alert: dev.androidagent.core.PanelAlert) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(listOf(alert.label, "time is up").filter(String::isNotBlank).joinToString(": "))
            .setContentText(alert.title.ifBlank { "Saved panel" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Which timer ended is private enough to keep off the lock screen; the alert itself shows.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, ALERTS).setSmallIcon(R.drawable.ic_agent)
                    .setContentTitle("A panel's timer ended").build(),
            )
            .setAutoCancel(true)
            .setContentIntent(openList(alert.panelId))
            .build()
        try {
            ensureChannels()
            manager.notify(alert.panelId, ALERT_ID, notification)
        } catch (_: SecurityException) {
        }
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
            synchronized(asking) { asking += saved.id }
        } catch (_: SecurityException) {
        }
    }

    /** Cancels the approval prompt of every panel that no longer waits. */
    fun approvals(panels: List<SavedPanel>) {
        val waiting = panels.filter { it.status == SavedPanel.Status.PENDING }.map { it.id }.toSet()
        val manager = NotificationManagerCompat.from(context)
        synchronized(asking) { (asking - waiting).toList() }.forEach { done ->
            runCatching { manager.cancel(done, APPROVAL_ID) }
            synchronized(asking) { asking.remove(done) }
        }
    }

    private fun panel(face: PanelFace): Notification {
        val text = face.summary.ifBlank { face.rows.filterIsInstance<PanelFace.Row.Note>().firstOrNull()?.text.orEmpty() }
        val problem = face.tiles.firstNotNullOfOrNull { it.problem }
        val lines = listOfNotNull(text.takeIf(String::isNotBlank), problem) + listLines(face)
        val clock = face.rows.filterIsInstance<PanelFace.Row.Clock>().firstOrNull { it.running }
        fun base() = NotificationCompat.Builder(context, PANELS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(face.title.ifBlank { "Mike" })
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openList(face.id))
            .setDeleteIntent(SavedPanelActionReceiver.dismiss(context, face.id))
        val full = base()
            .setContentText(problem ?: text)
            .also { builder ->
                if (lines.isNotEmpty()) builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                // A running timer counts down in the notification itself; nothing is posted each second.
                if (clock?.endsAtMs != null) {
                    builder.setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).setWhen(clock.endsAtMs!!)
                } else {
                    builder.setShowWhen(false)
                }
                buttons(face).forEach(builder::addAction)
            }
        // On the lock screen: the title only. No values, no list, no buttons.
        return full
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base().setShowWhen(false).setContentText("Unlock to use this panel").build())
            .build()
    }

    /** A list's items as lines, open ones first, for the expanded notification. */
    private fun listLines(face: PanelFace): List<String> {
        val checks = face.checks ?: return emptyList()
        val open = checks.items.filter { !it.checked }
        val shown = open.take(MAX_LINES).map { item -> "☐ " + (item.qty?.let { "$it × " } ?: "") + item.text }
        val more = open.size - shown.size
        return shown + listOfNotNull(
            "and $more more".takeIf { more > 0 },
            "${checks.items.size - open.size} done".takeIf { checks.items.size > open.size },
        )
    }

    /**
     * Up to [MAX_BUTTONS]: the panel's own buttons first, then what its list,
     * counter or timer offers (Add by reply, +1, Start or Pause). Every button
     * needs the phone unlocked.
     */
    private fun buttons(face: PanelFace): List<NotificationCompat.Action> {
        val out = mutableListOf<NotificationCompat.Action>()
        fun add(label: String, intent: PendingIntent, reply: RemoteInput? = null) {
            if (out.size >= MAX_BUTTONS) return
            out += NotificationCompat.Action.Builder(0, label, intent)
                .setAuthenticationRequired(true)
                .also { action -> reply?.let { action.addRemoteInput(it).setAllowGeneratedReplies(false) } }
                .build()
        }
        val host = (context.applicationContext as? AgentApplication)?.graph?.panels
        face.tiles.forEach { tile ->
            val label = when {
                tile.running -> "${tile.label} …"
                tile.on == true -> "${tile.label}: on"
                tile.on == false -> "${tile.label}: off"
                else -> tile.label
            }
            val intent = if (host != null && opens(host, face.id, tile.actionId)) PanelOpenActivity.pending(context, face.id, tile.actionId)
            else SavedPanelActionReceiver.tap(context, face.id, tile.actionId)
            add(label, intent)
        }
        face.rows.forEach { row ->
            when (row) {
                is PanelFace.Row.Checks -> if (row.canAdd) add(
                    "Add",
                    SavedPanelActionReceiver.reply(context, face.id, row.blockId),
                    RemoteInput.Builder(SavedPanelActionReceiver.EXTRA_TEXT).setLabel("Add to ${row.label.ifBlank { "the list" }}").build(),
                )
                is PanelFace.Row.Count -> if (row.count < row.max) add("+1 ${row.label}".trim(), SavedPanelActionReceiver.op(context, face.id, PanelOp("inc", row.blockId)))
                is PanelFace.Row.Clock -> add(
                    if (row.running) "Pause" else if (row.done) "Again" else "Start",
                    SavedPanelActionReceiver.op(context, face.id, PanelOp(if (row.running) "pause" else if (row.done) "reset" else "start", row.blockId)),
                )
                else -> Unit
            }
        }
        return out
    }

    private fun openList(panelId: String): PendingIntent = PendingIntent.getActivity(
        context, ("open:$panelId").hashCode(),
        SavedPanelsActivity.intent(context, panelId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            // Android keeps a channel's first sound and importance, so a change needs a new id.
            retire(manager, RETIRED_CHANNELS)
            CHANNELS.forEach { spec -> manager.createNotificationChannel(spec.channel()) }
        }
    }

    /** One notification channel as it is created. [sound] false means silent; true keeps the phone's default. */
    internal data class ChannelSpec(
        val id: String,
        val name: String,
        val importance: Int,
        val description: String,
        val sound: Boolean,
        val vibration: Boolean,
        val badge: Boolean = true,
    ) {
        fun channel(): NotificationChannel = NotificationChannel(id, name, importance).also { channel ->
            channel.description = description
            if (!sound) channel.setSound(null, null)
            if (!vibration) channel.enableVibration(false)
            if (!badge) channel.setShowBadge(false)
        }
    }

    companion object {
        const val PANELS = "saved_panels_v2"
        const val OLD_PANELS = "saved_panels"
        /** v2: silent. The first approval channel used the phone's default sound and buzz. */
        const val APPROVALS = "saved_panel_approvals_v2"
        const val OLD_APPROVALS = "saved_panel_approvals"
        /** v2: silent. The first timer channel used the phone's default sound. */
        const val ALERTS = "saved_panel_alerts_v2"
        const val OLD_ALERTS = "saved_panel_alerts"

        /** Channels from earlier builds, deleted on every start by RetiredChannels. */
        internal val RETIRED_CHANNELS = listOf(OLD_PANELS, OLD_ALERTS, OLD_APPROVALS)

        /**
         * Deletes [ids] from earlier builds. Android keeps a channel's first sound and
         * importance, and the user's own changes to it, so a silent channel needs a new
         * id and those changes do not carry over.
         */
        internal fun retire(manager: NotificationManager, ids: List<String>) {
            ids.forEach { id -> runCatching { manager.deleteNotificationChannel(id) } }
        }

        internal val CHANNELS = listOf(
            // Default importance, so the title shows on the lock screen; no sound, no
            // vibration, and no heads-up (that needs high importance).
            ChannelSpec(
                PANELS, "Saved panels", NotificationManager.IMPORTANCE_DEFAULT,
                "Panels you kept from Mike. The lock screen shows only the title; buttons work after unlock.",
                sound = false, vibration = false, badge = false,
            ),
            // High importance so it pops up, but never a sound or a buzz unless the
            // user turns one on for this channel in Android's settings.
            ChannelSpec(
                ALERTS, "Panel timers", NotificationManager.IMPORTANCE_HIGH,
                "A timer on a saved panel ended. Silent; you can turn on a sound here.",
                sound = false, vibration = false,
            ),
            ChannelSpec(
                APPROVALS, "Panels to approve", NotificationManager.IMPORTANCE_DEFAULT,
                "A panel Mike made that waits for your approval. Silent; you can turn on a sound here.",
                sound = false, vibration = false,
            ),
        )
        /** One per panel; the panel's id is the tag. */
        const val PANEL_ID = 7_401
        const val APPROVAL_ID = 7_402
        const val ALERT_ID = 7_403
        const val MAX_BUTTONS = 3
        const val MAX_LINES = 8

        /** True when [actionId] on [panelId] is an `open` button, which must start from an activity. */
        fun opens(host: PanelHost, panelId: String, actionId: String): Boolean =
            host.active(panelId)?.panel?.actions?.firstOrNull { it.id == actionId }?.effect is PanelEffect.Open
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
        val panelId = intent.getStringExtra(EXTRA_PANEL) ?: intent.data?.pathSegments?.firstOrNull() ?: return
        // No button works while the phone is locked: a list, a counter or a Mike
        // chat must not change for whoever holds the phone. Android 12+ already asks
        // to unlock; this covers Android 11 and a lock screen set to show all content.
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && intent.action in setOf(ACTION_TAP, ACTION_OP, ACTION_REPLY, ACTION_TOGGLE)) return
        when (intent.action) {
            ACTION_TOGGLE -> {
                // The panel and the list come from the template's address, which a fill-in
                // cannot change; the fill-in only names the item, and only a toggle is done.
                val parts = intent.data?.pathSegments.orEmpty()
                val block = parts.getOrNull(1) ?: return
                val item = intent.getStringExtra(EXTRA_ITEM) ?: return
                host.edit(parts.getOrNull(0) ?: return, PanelOp("toggle", block, item = item))
            }
            ACTION_OP -> {
                val op = runCatching {
                    PanelOp.parse(kotlinx.serialization.json.Json.parseToJsonElement(intent.getStringExtra(EXTRA_OP).orEmpty()) as kotlinx.serialization.json.JsonObject, "tap")
                }.getOrNull() ?: return
                host.edit(panelId, op)
            }
            ACTION_REPLY -> {
                val block = intent.getStringExtra(EXTRA_BLOCK) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) host.edit(panelId, PanelOp("add", block, text = text.take(PanelStates.MAX_ITEM_TEXT)))
                // Post again even when nothing changed, so the reply's spinner stops.
                runCatching { graph.panelSurfaces.repost(panelId) }
            }
            ACTION_ALARM -> {
                val pending = goAsync()
                graph.scope.launch {
                    try { withTimeoutOrNull(TAP_WAIT_MS) { host.tick(); host.flushStates() } } finally { pending.finish() }
                }
            }
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
        const val ACTION_OP = "dev.androidagent.app.SAVED_PANEL_OP"
        const val ACTION_REPLY = "dev.androidagent.app.SAVED_PANEL_REPLY"
        const val ACTION_ALARM = "dev.androidagent.app.SAVED_PANEL_ALARM"
        const val ACTION_TOGGLE = "dev.androidagent.app.SAVED_PANEL_TOGGLE"
        const val EXTRA_ITEM = "item"
        const val EXTRA_PANEL = "panel"
        const val EXTRA_ACTION = "action"
        const val EXTRA_OP = "op"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_TEXT = "text"
        private const val TAP_WAIT_MS = 8_000L

        /** A tap that changes [panelId]'s own state: a tick, +1, start or pause. Checked as a surface tap. */
        fun op(context: Context, panelId: String, op: PanelOp): PendingIntent = PendingIntent.getBroadcast(
            context, "op:$panelId:${op.toJson()}".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_OP)
                .setData(Uri.parse("heymike-panel://op/${Uri.encode(panelId)}/${Uri.encode(op.toJson().toString())}"))
                .putExtra(EXTRA_PANEL, panelId)
                .putExtra(EXTRA_OP, op.toJson().toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * A notification reply that adds an item to list [blockId]. Mutable,
         * because the system writes the reply's text into it; the panel and the
         * list are already set, the receiver is not exported, and the text only
         * ever becomes an `add` to that one list, with its bounds.
         */
        fun reply(context: Context, panelId: String, blockId: String): PendingIntent = PendingIntent.getBroadcast(
            context, "reply:$panelId:$blockId".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .setData(Uri.parse("heymike-panel://reply/${Uri.encode(panelId)}/${Uri.encode(blockId)}"))
                .putExtra(EXTRA_PANEL, panelId)
                .putExtra(EXTRA_BLOCK, blockId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * The template a widget's list rows fill in (Android 12+). Mutable, because
         * each row adds which item it is; the panel and the list are in its address,
         * which a fill-in cannot replace, and the receiver only toggles that item.
         */
        fun toggleTemplate(context: Context, panelId: String, blockId: String): PendingIntent = PendingIntent.getBroadcast(
            context, "toggle:$panelId:$blockId".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(ACTION_TOGGLE)
                .setData(Uri.parse("heymike-panel://toggle/${Uri.encode(panelId)}/${Uri.encode(blockId)}")),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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

/**
 * One alarm for the next moment a panel's timer ends or its list resets, so
 * that happens on time while the app sleeps or was closed. Inexact but allowed
 * in idle: a few minutes late in deep sleep, which is fine for a reset; a
 * countdown on screen is drawn from its end time, not from this alarm.
 */
internal object SavedPanelAlarm {
    fun schedule(context: Context, atMs: Long?) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, "saved-panel-alarm".hashCode(),
            Intent(context, SavedPanelActionReceiver::class.java)
                .setAction(SavedPanelActionReceiver.ACTION_ALARM)
                .putExtra(SavedPanelActionReceiver.EXTRA_PANEL, "*"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            if (atMs == null) manager.cancel(pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
    }
}
