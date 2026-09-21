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

/**
 * Turns a proxy failure category into something a person can act on.
 *
 * When the tunnel to OpenAI fails, the app-server reports only
 * `HTTP CONNECT failed with status 502`, which reads as if some external proxy
 * were at fault. It is not: the proxy is ours, injected deliberately because
 * the musl app-server build cannot resolve DNS under the app UID on Android.
 *
 * The 502 covers two different faults with two different remedies — the name
 * did not resolve, or it resolved and the connection was refused. The runtime
 * already records which one happened; this is what makes that distinction
 * visible instead of leaving the user to guess from a retry counter.
 *
 * Note that a rejected allowlist entry would surface as **403**, not 502, so a
 * 502 is never a sign of a misconfigured host list.
 */
object ProxyDiagnostics {

    private const val PREFIX = "proxy-error:"

    /**
     * The last failure in [events], explained.
     *
     * Last rather than first: the buffer spans the whole process lifetime, and
     * an error from twenty minutes ago says nothing about the turn that just
     * failed.
     *
     * @param events entries from `AndroidRuntimeHost.recentProxyEvents()`.
     *   Metadata only — host, port and category. The buffer never holds tunnel
     *   bytes, headers, or credentials, and nothing here inspects a value.
     * @return one sentence, or null when nothing has failed.
     */
    fun explain(events: List<String>): String? {
        val category = events.lastOrNull { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.trim()
            ?: return null
        return when (category) {
            "dns" ->
                "Your network could not look up chatgpt.com. Check DNS, VPN, or private-DNS settings."
            "connection" ->
                "chatgpt.com refused the connection. The network may be blocking it."
            "timeout" ->
                "chatgpt.com did not answer in time."
            else ->
                // An unrecognised category is still worth showing: knowing the
                // proxy failed and how it was labelled beats a bare 502.
                "The connection to chatgpt.com failed (${category.ifBlank { "unknown" }})."
        }
    }

    /** A one-line status suitable for the diagnostics rows, or null when healthy. */
    fun statusLine(events: List<String>): String? =
        explain(events)?.let { "Network: $it" }
}
