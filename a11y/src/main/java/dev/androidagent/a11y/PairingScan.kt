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

package dev.androidagent.a11y

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** What the system's pairing dialog is showing right now. */
data class PairingDetails(val code: String, val port: Int)

/**
 * Reads the Wireless Debugging pairing dialog so the user does not have to copy
 * a six-digit code that disappears the moment they leave the dialog.
 *
 * This is the one place the app looks at another app's screen outside an agent
 * run, so it is deliberately narrow: it only runs while the user has just asked
 * to pair, only reads windows belonging to the system settings app, keeps
 * nothing, and hands what it finds straight to the ADB transport. The code
 * never reaches the model, a log, or disk.
 */
object PairingScan {

    /** Settings lives under different package names across OEM builds. */
    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.android.tv.settings",
        "com.miui.securitycenter",
    )

    private val ENDPOINT = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    private val SIX_DIGITS = Regex("""(?<!\d)(\d{6})(?!\d)""")

    /**
     * Pull the pairing code and port out of whatever the dialog is showing.
     * Returns null until both are on screen, which is the normal state while
     * the dialog is still opening.
     */
    fun parse(texts: List<String>): PairingDetails? {
        val joined = texts.joinToString(separator = "\n")
        val endpoint = ENDPOINT.find(joined) ?: return null
        val port = endpoint.groupValues[2].toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        // The endpoint is removed before looking for the code so an address
        // like 10.0.0.1:123456 can never be mistaken for one.
        val withoutEndpoints = ENDPOINT.replace(joined, " ")
        val code = SIX_DIGITS.find(withoutEndpoints)?.groupValues?.get(1) ?: return null
        return PairingDetails(code = code, port = port)
    }

    /** Text worth parsing, from settings windows only, bounded so a deep tree cannot stall the scan. */
    fun collectText(windows: List<A11yWindow>, limit: Int = 400): List<String> {
        val out = ArrayList<String>()
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName !in SETTINGS_PACKAGES) continue
            collect(root, out, limit)
            if (out.size >= limit) break
        }
        return out
    }

    private fun collect(node: A11yNodeView, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        node.text?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.takeIf { it.isNotBlank() }?.let(out::add)
        for (index in 0 until node.childCount) {
            if (out.size >= limit) return
            node.child(index)?.let { collect(it, out, limit) }
        }
    }
}

/**
 * One look at the current screen for pairing details. Null while the dialog is
 * not up, which is the normal answer for most of the polling window.
 */
internal fun AgentAccessibilityService.pairingDetails(): PairingDetails? =
    runCatching { PairingScan.parse(PairingScan.collectText(visibleWindows())) }.getOrNull()

/**
 * Waits for the pairing dialog to show a code, then reports it once. Armed only
 * by an explicit user action and always bounded by [timeoutMs], so nothing is
 * watching the screen a minute after the user gave up.
 */
object PairingWatcher {

    /** False when the user has not enabled screen control; the caller must fall back to typing. */
    val available: Boolean get() = A11yServiceHandle.connected

    suspend fun await(timeoutMs: Long = 120_000L, pollMs: Long = 600L): PairingDetails? =
        withTimeoutOrNull(timeoutMs) {
            while (coroutineContext.isActive) {
                A11yServiceHandle.service.value?.pairingDetails()?.let { return@withTimeoutOrNull it }
                delay(pollMs)
            }
            null
        }
}
