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

package dev.androidagent.app.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.androidagent.app.MainActivity
import dev.androidagent.core.Onboarding
import dev.androidagent.core.AutomationVoiceRequest

/** How an assistant press reaches the app, and whether the app holds the role. */
object AssistLaunch {
    /** Set on the intent that opens `MainActivity` straight into voice mode. */
    const val EXTRA_START_VOICE = "dev.androidagent.app.extra.START_VOICE"
    const val EXTRA_AUTOMATION_VOICE = "dev.androidagent.app.extra.AUTOMATION_VOICE"

    /**
     * Reuses the chat screen if it is already open: CLEAR_TOP with SINGLE_TOP
     * delivers to `onNewIntent` instead of creating a second one.
     */
    fun voiceIntent(context: Context, automation: AutomationVoiceRequest? = null): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_START_VOICE, true)
        .apply { automation?.let { putExtra(EXTRA_AUTOMATION_VOICE, it.toJson()) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * True when an assistant press can open the live panel over the current
     * app. Otherwise it opens the app, which asks for what is missing: the
     * panel has no activity to show a permission dialog or the consent screen.
     */
    fun panelReady(context: Context): Boolean {
        val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val consent = context.getSharedPreferences(UI_PREFERENCES, 0).getInt(KEY_CONSENT_VERSION, 0)
        return microphone && consent >= Onboarding.CONSENT_VERSION
    }

    // Written by AgentViewModel; the panel only reads them.
    const val UI_PREFERENCES = "ui"
    const val KEY_CONSENT_VERSION = "consentVersion"
    const val KEY_SESSION = "session"
    const val KEY_MODEL = "model"

    /**
     * True when this app answers the assist gesture.
     *
     * The secure setting is asked first because the system dispatches on it,
     * and on hardware it has disagreed with `RoleManager`: a component set
     * outside the Default apps screen drove the gesture while `isRoleHeld`
     * still answered false. Compared against the runtime package name, since
     * the dev flavor carries a `.dev` suffix.
     */
    fun isDefaultAssistant(context: Context): Boolean {
        val expected = ComponentName(context.packageName, AgentVoiceInteractionService::class.java.name)
        val selected = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()
        if (!selected.isNullOrBlank()) return ComponentName.unflattenFromString(selected) == expected
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    /**
     * The screens that host the assistant picker, most specific first. OEM
     * builds drop some of them, so the caller walks the list until one opens.
     * An ordinary app cannot request `ROLE_ASSISTANT` through `RoleManager`, so
     * sending the user there is the only way in.
     */
    fun settingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}
