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

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.androidagent.core.PanelGrants

/**
 * Which of the panels' special access is off on this phone, read the same
 * way AndroidSystemPlatform reads it, and Android's screen to turn it on.
 * Only opens that screen: the user flips the switch, nothing is granted here.
 */
object PanelGrantAccess {
    fun missing(context: Context): Set<String> = buildSet {
        if (!Settings.System.canWrite(context)) add(PanelGrants.WRITE_SETTINGS)
        val policy = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        if (!policy) add(PanelGrants.NOTIFICATION_POLICY)
    }

    /** Opens Android's switch for [grant]; false when this phone has no such screen. */
    fun open(context: Context, grant: String): Boolean {
        val app = Uri.fromParts("package", context.packageName, null)
        val intents = when (grant) {
            PanelGrants.WRITE_SETTINGS -> listOf(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, app),
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
            )
            PanelGrants.NOTIFICATION_POLICY -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            else -> emptyList()
        }
        return intents.any { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
