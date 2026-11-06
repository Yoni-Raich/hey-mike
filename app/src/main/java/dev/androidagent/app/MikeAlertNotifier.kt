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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.androidagent.core.proactive.AlertGate
import dev.androidagent.core.proactive.AlertLevel
import dev.androidagent.core.proactive.AlertSink
import dev.androidagent.core.proactive.FeedbackAction
import dev.androidagent.core.proactive.MikeAlert

/**
 * Mike's own alerts on the phone. Urgent shows heads-up and vibrates, but
 * rings only when the user turned the sound on. Nothing here runs a task:
 * every button only records the user's answer or opens the Manager chat.
 */
class MikeAlertNotifier(
    private val context: Context,
    private val managerChat: () -> String?,
    private val urgentSound: () -> Boolean,
) : AlertSink {
    private val notices = NotificationManagerCompat.from(context)
    private val hebrew get() = java.util.Locale.getDefault().language in setOf("he", "iw")

    private fun allowed(): Boolean = notices.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(NotificationChannel(URGENT, text("Mike · Urgent", "מייק · דחוף"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = text("Something needs your attention now. Vibrates, no sound.", "משהו דורש תשומת לב עכשיו. רוטט, בלי צליל.")
                setSound(null, null); enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(URGENT_SOUND, text("Mike · Urgent with sound", "מייק · דחוף עם צליל"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = text("Used only when you turn on sound for urgent alerts.", "בשימוש רק כשהפעלת צליל להתראות דחופות.")
                enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(NOTICE, text("Mike · Worth a look", "מייק · כדאי להציץ"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = text("Something to see today. Silent.", "משהו לראות היום. שקט.")
                setSound(null, null); enableVibration(false)
            })
            manager.createNotificationChannel(NotificationChannel(DIGEST, text("Mike · Digest", "מייק · סיכום"), NotificationManager.IMPORTANCE_LOW).apply {
                description = text("A summary at your digest times.", "סיכום בשעות הסיכום שבחרת.")
            })
        }
    }

    override fun show(alert: MikeAlert) {
        if (!allowed()) return
        ensureChannels()
        val channel = when (alert.level) {
            AlertLevel.URGENT -> if (urgentSound()) URGENT_SOUND else URGENT
            AlertLevel.NOTICE -> NOTICE
            else -> DIGEST
        }
        val detail = listOfNotNull(alert.body, alert.whyNow?.let { text("Why now: ", "למה עכשיו: ") + it },
            alert.reasons.filter { it != "test" }.takeIf { it.isNotEmpty() }?.joinToString(" · ") { AlertGate.explain(it, hebrew) })
            .joinToString("\n")
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(if (alert.level == AlertLevel.URGENT) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(if (alert.level == AlertLevel.URGENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            // The lock screen shows that Mike has something, not what it is.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_agent)
                .setContentTitle(text("Mike has something for you", "למייק יש משהו בשבילך")).build())
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(alert.id))
            .setDeleteIntent(action(alert.id, FeedbackAction.DISMISS))
            .addAction(0, text("Open", "פתח"), openIntent(alert.id))
            .addAction(0, text("Not important", "לא חשוב"), action(alert.id, FeedbackAction.NOT_IMPORTANT))
            .addAction(0, text("Later", "אחר כך"), action(alert.id, FeedbackAction.LATER))
        try { notices.notify(alert.id, NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }

    override fun cancel(alertId: String) { runCatching { notices.cancel(alertId, NOTIFICATION_ID) } }

    override fun digest(alerts: List<MikeAlert>) {
        if (alerts.isEmpty() || !allowed()) return
        ensureChannels()
        val title = if (hebrew) "סיכום ממייק: ${alerts.size} דברים" else "Mike's digest: ${alerts.size} item${if (alerts.size == 1) "" else "s"}"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        alerts.take(7).forEach { style.addLine("${it.title} — ${it.body}".take(120)) }
        if (alerts.size > 7) style.setSummaryText(if (hebrew) "ועוד ${alerts.size - 7}" else "and ${alerts.size - 7} more")
        val builder = NotificationCompat.Builder(context, DIGEST)
            .setSmallIcon(R.drawable.ic_agent).setContentTitle(title)
            .setContentText(alerts.first().title).setStyle(style)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
            .setContentIntent(openIntent(null))
        try { notices.notify("digest", NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }

    private fun openIntent(alertId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        managerChat()?.let { intent.putExtra(QuestionNotifier.EXTRA_OPEN_CHAT, it) }
        alertId?.let { intent.putExtra(EXTRA_ALERT, it) }
        return PendingIntent.getActivity(context, ("open:" + alertId).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(alertId: String, action: FeedbackAction): PendingIntent {
        val intent = Intent(context, MikeAlertReceiver::class.java).setAction(ACTION_FEEDBACK)
            .putExtra(EXTRA_ALERT, alertId).putExtra(EXTRA_ACTION, action.name)
        return PendingIntent.getBroadcast(context, (alertId + action.name).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun text(english: String, hebrewText: String) = if (hebrew) hebrewText else english

    companion object {
        const val URGENT = "mike_urgent"
        const val URGENT_SOUND = "mike_urgent_sound"
        const val NOTICE = "mike_notice"
        const val DIGEST = "mike_digest"
        const val NOTIFICATION_ID = 7_310
        const val ACTION_FEEDBACK = "dev.androidagent.app.ALERT_FEEDBACK"
        const val EXTRA_ALERT = "dev.androidagent.app.extra.ALERT"
        const val EXTRA_ACTION = "dev.androidagent.app.extra.ALERT_ACTION"
    }
}

/** The user's answer on an alert: Not important, Later, or swiped away. Open goes through [MainActivity]. */
class MikeAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MikeAlertNotifier.ACTION_FEEDBACK) return
        val alert = intent.getStringExtra(MikeAlertNotifier.EXTRA_ALERT) ?: return
        val action = intent.getStringExtra(MikeAlertNotifier.EXTRA_ACTION)?.let { name -> FeedbackAction.entries.firstOrNull { it.name == name } } ?: return
        val graph = (context.applicationContext as? AgentApplication)?.graph ?: return
        val pending = goAsync()
        Thread {
            try { graph.taskDesk.alertFeedback(alert, action) } finally { pending.finish() }
        }.start()
    }
}
