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

package dev.androidagent.automations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.androidagent.core.AutomationEvent
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The single clock wake-up.
 *
 * One alarm, re-armed for the earliest rule that is next due, rather than one
 * alarm per rule: Android caps how many exact alarms an app may hold and
 * charges a wake-up for each, and the landing alarm re-checks every rule
 * anyway. It is a wake-up, not a decision.
 *
 * Exactness is asked for, never assumed. `canScheduleExactAlarms` is false when
 * the user has not granted it on Android 12+, and the fallback is an inexact
 * alarm that Doze may land an hour late — which the host reports rather than
 * hiding, because "19:00" landing at 20:10 is a different action, not a slow
 * one.
 */
internal class AutomationAlarms(private val context: Context) {

    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true else manager?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    fun armFor(next: ZonedDateTime) {
        val manager = manager ?: return
        val at = next.toInstant().toEpochMilli()
        val pending = pendingIntent() ?: return
        runCatching {
            if (canScheduleExact()) {
                // allowWhileIdle so Doze does not swallow the one wake-up the
                // whole feature depends on.
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { failure ->
            // A SecurityException here means the exact-alarm permission was
            // revoked between the check and the call. Falling back is better
            // than losing the alarm entirely.
            Log.w(AutomationHost.TAG, "Exact alarm refused, falling back to inexact", failure)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        }
    }

    fun cancel() {
        val pending = pendingIntent(create = false) ?: return
        runCatching { manager?.cancel(pending) }
        pending.cancel()
    }

    private fun pendingIntent(create: Boolean = true): PendingIntent? {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).setAction(ACTION_FIRE)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private companion object {
        const val ACTION_FIRE = "dev.androidagent.automations.FIRE"
        const val REQUEST_CODE = 0x4155
    }
}

/**
 * The alarm landed.
 *
 * It says only "some scheduled rule may be due", never which one. The host
 * re-checks each rule's own schedule against the real clock, so an alarm
 * Android coalesced, delivered early, or held over from a rule that has since
 * been deleted fires nothing.
 */
class AutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val host = context?.automationHost() ?: return
        host.onEvent(AutomationEvent.Clock(ZonedDateTime.now(ZoneId.systemDefault())))
    }
}

/**
 * Re-arm after a restart or an app update.
 *
 * An alarm does not survive either, so without this every scheduled rule stops
 * silently at the first reboot — the failure mode that makes people distrust
 * the whole feature.
 */
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Catch up rather than only re-arm: a slot that passed while the phone
        // was off is still owed if it is inside its window.
        context?.automationHost()?.catchUp()
    }
}
