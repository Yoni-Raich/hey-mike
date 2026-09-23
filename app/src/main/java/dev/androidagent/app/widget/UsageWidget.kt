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

package dev.androidagent.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.MainActivity
import dev.androidagent.app.R
import dev.androidagent.app.ui.quotaColor
import dev.androidagent.core.AccountUsageOverview
import dev.androidagent.core.AccountUsageRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Every saved account's quota on the home screen, each as a still frame of
// Mike's orb: the sphere takes the quota colour and dims as the window fills,
// and the ring around it fills the way the top-bar meter does. Only the live
// account can be read; the others show their last reading and its age.

class UsageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = renderAsync(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        renderAsync(context, manager, intArrayOf(id))

    private fun renderAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        UsageWidget.scope.launch {
            try { UsageWidget.render(context.applicationContext, manager, ids) } finally { pending.finish() }
        }
    }
}

object UsageWidget {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Four orbs fit a phone-width widget; more would be too small to read.
    private const val MAX_ACCOUNTS = 4
    private const val ORB_DP = 64
    private const val ORB_POINTS = 180
    private const val ORB_TILT = 0.38f

    private val Unknown = 0xFF8F8F8F.toInt()
    private val Live = 0xFF83D9CA.toInt()

    /** Redraw every placed widget. Cheap when none is placed. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) return
        scope.launch { runCatching { render(context.applicationContext, manager, ids) } }
    }

    internal fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = (context as? AgentApplication)?.graph ?: return
        val rows = AccountUsageOverview.rows(graph.accounts.state(), graph.usageBook.all(), System.currentTimeMillis())
        val views = build(context, rows.take(MAX_ACCOUNTS))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun build(context: Context, rows: List<AccountUsageRow>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_usage_root, open)
        views.removeAllViews(R.id.widget_usage_accounts)
        views.setViewVisibility(R.id.widget_usage_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
        val size = (ORB_DP * context.resources.displayMetrics.density).roundToInt().coerceIn(96, 220)
        rows.forEach { row -> views.addView(R.id.widget_usage_accounts, account(context, row, size)) }
        return views
    }

    private fun account(context: Context, row: AccountUsageRow, size: Int): RemoteViews {
        val item = RemoteViews(context.packageName, R.layout.widget_usage_account)
        val fraction = row.window?.fraction
        item.setImageViewBitmap(R.id.widget_account_orb, orb(size, fraction, seed = row.accountId.hashCode()))
        // White over the orb: in the orb's own colour the number sinks into it.
        item.setTextViewText(R.id.widget_account_percent, fraction?.let { "${((1f - it) * 100).roundToInt()}%" } ?: "–")
        item.setTextViewText(R.id.widget_account_name, row.name)
        val detail = row.window?.let { window -> listOfNotNull(window.label, window.resetText?.lowercaseFirst()).joinToString(" · ") }
            ?: "Not read yet"
        item.setTextViewText(R.id.widget_account_detail, detail)
        item.setTextViewText(R.id.widget_account_status, if (row.live) "In use" else row.readText ?: "Switch to read")
        item.setTextColor(R.id.widget_account_status, if (row.live) Live else Unknown)
        item.setContentDescription(R.id.widget_account_root, spoken(row))
        return item
    }

    private fun spoken(row: AccountUsageRow): String {
        val window = row.window
        val quota = window?.fraction?.let { "${((1f - it) * 100).roundToInt()}% of ${window.label.lowercase()} quota left" }
            ?: "quota not read yet"
        return listOfNotNull(row.name, quota, window?.resetText, if (row.live) "in use" else row.readText).joinToString(", ")
    }

    private fun String.lowercaseFirst() = replaceFirstChar { it.lowercase() }

    /**
     * One still frame of the agent orb, drawn with the platform canvas because
     * a home screen widget cannot run Compose. [fraction] is how full the
     * window is; null draws the grey orb of an account never read.
     */
    internal fun orb(size: Int, fraction: Float?, seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val left = fraction?.let { 1f - it.coerceIn(0f, 1f) } ?: 0.3f
        val color = fraction?.let { quotaColor(it).toArgb() } ?: Unknown
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Glow: brightest when the window is empty, fading as it fills.
        paint.shader = RadialGradient(
            center, center, center,
            intArrayOf(withAlpha(color, 0.18f + 0.32f * left), 0),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(center, center, center, paint)
        paint.shader = null

        // The ring, filled from twelve o'clock like the top-bar meter.
        val stroke = size * 0.055f
        val ringRadius = center - stroke
        val ring = RectF(center - ringRadius, center - ringRadius, center + ringRadius, center + ringRadius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0x24FFFFFF
        canvas.drawArc(ring, 0f, 360f, false, paint)
        if (fraction != null && fraction > 0f) {
            paint.color = color
            canvas.drawArc(ring, -90f, 360f * fraction.coerceIn(0f, 1f), false, paint)
        }
        paint.style = Paint.Style.FILL

        // The dotted sphere. Each account is turned a little differently, so a
        // row of them reads as several orbs rather than one stamped out again.
        val radius = size * 0.3f
        val unit = size / 42f
        val spin = (seed and 0xFFFF) / 65535f * 6.283f
        val phase = ((seed ushr 16) and 0xFF) / 255f * 6.283f
        val cosY = cos(spin)
        val sinY = sin(spin)
        val cosX = cos(ORB_TILT)
        val sinX = sin(ORB_TILT)
        for (index in 0 until ORB_POINTS) {
            val y = 1f - index / (ORB_POINTS - 1f) * 2f
            val across = sqrt(1f - y * y)
            val angle = index * 2.399963f
            val x = cos(angle) * across
            val z = sin(angle) * across
            val wobble = sin(3.1f * x + phase) * sin(2.7f * y - phase * 0.7f) * sin(3.3f * z + phase * 1.3f)
            val band = (1f - abs(y)) * left
            val push = 1f + 0.07f * wobble + 0.16f * band * (0.55f + 0.45f * wobble)
            val px = x * push
            val py = y * push
            val pz = z * push
            val turnedX = px * cosY + pz * sinY
            val turnedZ = -px * sinY + pz * cosY
            val tiltedY = py * cosX - turnedZ * sinX
            val tiltedZ = py * sinX + turnedZ * cosX
            val perspective = 2.6f / (2.6f - tiltedZ)
            val depth = ((tiltedZ + 1f) / 2f).coerceIn(0f, 1f)
            paint.color = withAlpha(mixWhite(color, 0.45f * left * depth), (0.1f + 0.9f * depth * depth) * (0.55f + 0.45f * left))
            canvas.drawCircle(
                center + turnedX * radius * perspective,
                center + tiltedY * radius * perspective,
                (0.35f + 0.75f * depth) * unit * (1f + 0.25f * left),
                paint,
            )
        }
        return bitmap
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (color and 0x00FFFFFF)

    private fun mixWhite(color: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val value = (color shr shift) and 0xFF
            return (value + (255 - value) * t).roundToInt() shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }
}
