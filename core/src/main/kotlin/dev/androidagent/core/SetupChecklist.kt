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

package dev.androidagent.core

/** One thing the user has to enable, connect or grant before the agent works. */
enum class SetupItem { RUNTIME, ACCOUNT, SCREEN_CONTROL, FLOATING_CONTROL, WIRELESS_ADB, NOTIFICATIONS, INSTALL_UPDATES, MICROPHONE }

/**
 * How far along one item is. BLOCKED means the user already acted but the
 * system did not follow through, so telling them to "turn it on" would be
 * wrong advice.
 */
enum class SetupState { DONE, WORKING, PENDING, BLOCKED }

/** REQUIRED items gate a run; the rest only cost the user a feature. */
enum class SetupImportance { REQUIRED, RECOMMENDED, OPTIONAL }

data class SetupRow(
    val item: SetupItem,
    val state: SetupState,
    val importance: SetupImportance,
    val summary: String,
)

/**
 * Everything the checklist needs, as plain values. Keeping it primitive is
 * what lets this live in core: the accessibility status comes from the a11y
 * module and the permissions from the Android framework, and neither is a
 * dependency here.
 */
data class SetupSignals(
    val runtimePhase: RuntimePhase = RuntimePhase.MISSING,
    /** Null until the engine has reported an account at all. */
    val signedIn: Boolean? = null,
    /** A login is in flight and waiting for the user to finish it in a browser. */
    val loginPending: Boolean = false,
    val a11yConnected: Boolean = false,
    val a11yDeclared: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val installUpdatesGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val adbPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val adbPort: Int? = null,
)

object SetupChecklist {

    fun rows(signals: SetupSignals): List<SetupRow> = listOf(
        runtimeRow(signals),
        accountRow(signals),
        screenControlRow(signals),
        floatingControlRow(signals),
        wirelessAdbRow(signals),
        permissionRow(
            item = SetupItem.NOTIFICATIONS,
            granted = signals.notificationsGranted,
            importance = SetupImportance.RECOMMENDED,
            done = "Allowed",
            pending = "Not allowed · run progress stays hidden",
        ),
        permissionRow(
            item = SetupItem.INSTALL_UPDATES,
            granted = signals.installUpdatesGranted,
            importance = SetupImportance.OPTIONAL,
            done = "Allowed",
            pending = "Not allowed · updates cannot install",
        ),
        permissionRow(
            item = SetupItem.MICROPHONE,
            granted = signals.microphoneGranted,
            importance = SetupImportance.OPTIONAL,
            done = "Allowed",
            pending = "Not allowed · voice is unavailable",
        ),
    )

    /** True when nothing required is outstanding. Recommended and optional items never block a run. */
    fun readyForRuns(rows: List<SetupRow>): Boolean = outstanding(rows) == 0

    fun outstanding(rows: List<SetupRow>): Int =
        rows.count { it.importance == SetupImportance.REQUIRED && it.state != SetupState.DONE }

    fun headline(rows: List<SetupRow>): String = when (val left = outstanding(rows)) {
        0 -> "Ready"
        1 -> "1 thing to finish"
        else -> "$left things to finish"
    }

    private fun runtimeRow(signals: SetupSignals) = SetupRow(
        item = SetupItem.RUNTIME,
        state = when (signals.runtimePhase) {
            RuntimePhase.READY, RuntimePhase.RUNNING -> SetupState.DONE
            RuntimePhase.PREPARING -> SetupState.WORKING
            RuntimePhase.ERROR -> SetupState.BLOCKED
            RuntimePhase.MISSING -> SetupState.PENDING
        },
        importance = SetupImportance.REQUIRED,
        summary = when (signals.runtimePhase) {
            RuntimePhase.READY -> "Ready"
            RuntimePhase.RUNNING -> "Running"
            RuntimePhase.PREPARING -> "Preparing…"
            RuntimePhase.ERROR -> "Preparation failed"
            RuntimePhase.MISSING -> "Not prepared yet"
        },
    )

    private fun accountRow(signals: SetupSignals) = SetupRow(
        item = SetupItem.ACCOUNT,
        state = when {
            signals.signedIn == true -> SetupState.DONE
            signals.loginPending -> SetupState.WORKING
            else -> SetupState.PENDING
        },
        importance = SetupImportance.REQUIRED,
        summary = when {
            signals.signedIn == true -> "Signed in"
            signals.loginPending -> "Finish signing in"
            signals.signedIn == false -> "Signed out"
            else -> "Waiting for the engine"
        },
    )

    private fun screenControlRow(signals: SetupSignals) = SetupRow(
        item = SetupItem.SCREEN_CONTROL,
        state = when {
            signals.a11yConnected -> SetupState.DONE
            // Switched on in Settings but never started: on Android 13+ this is
            // the restricted-settings block, not something the user can fix by
            // toggling the same switch again.
            signals.a11yDeclared -> SetupState.BLOCKED
            else -> SetupState.PENDING
        },
        importance = SetupImportance.REQUIRED,
        summary = when {
            signals.a11yConnected -> "On"
            signals.a11yDeclared -> "Allow restricted settings to finish"
            else -> "Off"
        },
    )

    private fun floatingControlRow(signals: SetupSignals) = SetupRow(
        item = SetupItem.FLOATING_CONTROL,
        state = if (signals.overlayGranted) SetupState.DONE else SetupState.PENDING,
        importance = SetupImportance.REQUIRED,
        summary = if (signals.overlayGranted) "Allowed" else "Not allowed · device control cannot start",
    )

    private fun wirelessAdbRow(signals: SetupSignals) = SetupRow(
        item = SetupItem.WIRELESS_ADB,
        state = when (signals.adbPhase) {
            ConnectionPhase.CONNECTED -> SetupState.DONE
            ConnectionPhase.DISCOVERING, ConnectionPhase.PAIRING, ConnectionPhase.CONNECTING -> SetupState.WORKING
            ConnectionPhase.ERROR -> SetupState.BLOCKED
            ConnectionPhase.DISCONNECTED -> SetupState.PENDING
        },
        // Optional: the accessibility service serves screen control, and ADB
        // adds only shell, file transfer and installs. Counting it as required
        // told every user without Wireless Debugging the agent could not run.
        importance = SetupImportance.OPTIONAL,
        summary = when (signals.adbPhase) {
            ConnectionPhase.CONNECTED -> signals.adbPort?.let { "Connected · port $it" } ?: "Connected"
            ConnectionPhase.DISCOVERING -> "Looking for this phone…"
            ConnectionPhase.PAIRING -> "Pairing…"
            ConnectionPhase.CONNECTING -> "Connecting…"
            ConnectionPhase.ERROR -> "Connection failed"
            ConnectionPhase.DISCONNECTED -> "Not paired · only for shell, files and installs"
        },
    )

    private fun permissionRow(
        item: SetupItem,
        granted: Boolean,
        importance: SetupImportance,
        done: String,
        pending: String,
    ) = SetupRow(
        item = item,
        state = if (granted) SetupState.DONE else SetupState.PENDING,
        importance = importance,
        summary = if (granted) done else pending,
    )
}
