package dev.androidagent.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Quiet delivery never takes the screen or offers an action that executes a task. */
internal object TaskBriefingNotifier {
    fun show(context: Context, chat: String, title: String, text: String): String {
        val notices = NotificationManagerCompat.from(context)
        if(!notices.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
            return "Saved in Manager chat. Notifications are disabled."
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("manager_briefings", "Mike · Manager", NotificationManager.IMPORTANCE_LOW))
        if(manager.getNotificationChannel("manager_briefings")?.importance == NotificationManager.IMPORTANCE_NONE)
            return "Saved in Manager chat. Manager notifications are disabled."
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(QuestionNotifier.EXTRA_OPEN_CHAT, chat)
        return try {
            notices.notify(7_305, NotificationCompat.Builder(context, "manager_briefings")
                .setSmallIcon(R.drawable.ic_agent).setContentTitle(title.take(120)).setContentText(text.take(160))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOnlyAlertOnce(true).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(PendingIntent.getActivity(context, 7_305, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .build())
            "Saved in Manager chat; quiet notification posted."
        } catch (_: SecurityException) { "Saved in Manager chat. Android blocked the notification." }
    }
}
