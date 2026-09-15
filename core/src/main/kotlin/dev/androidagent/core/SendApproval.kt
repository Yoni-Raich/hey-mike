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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The agent is about to press Send in another app.
 *
 * This is the moment a message actually leaves the phone, so this is what the
 * user approves. Opening a chat with the text typed in sends nothing and needs
 * no approval.
 *
 * @param recipient the chat or conversation title as shown on screen, or null
 *   when the screen does not show one. Without it only an app-wide grant can
 *   cover the send.
 * @param message the text in the compose field, when it could be read.
 */
data class SendRequest(
    val packageName: String,
    val appLabel: String,
    val recipient: String?,
    val message: String?,
)

/** What a "remember this" answer covers. */
enum class ApprovalScope {
    /** This send only. */
    ONCE,

    /** Every later send to this recipient in this app. */
    CONTACT,

    /** Every later send in this app, to anyone. */
    APP,
}

/**
 * A standing permission to send without asking.
 *
 * @param recipient null for an app-wide grant.
 */
data class SendGrant(
    val packageName: String,
    val appLabel: String,
    val recipient: String?,
) {
    fun covers(request: SendRequest): Boolean =
        packageName == request.packageName &&
            (recipient == null || (request.recipient != null && sameRecipient(recipient, request.recipient)))

    companion object {
        /** Case, spacing and direction marks differ between screens that show the same chat. */
        fun sameRecipient(a: String, b: String): Boolean = normalize(a) == normalize(b)

        private fun normalize(value: String): String =
            value.filterNot { it in "‎‏‪‫‬‭‮⁦⁧⁨⁩" }
                .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }
}

/**
 * Where standing send permissions live.
 *
 * The production store must not be writable by the agent: its shell runs as
 * the app's own user, so anything it can edit is not a user decision.
 */
interface SendGrantStore {
    val grants: StateFlow<List<SendGrant>>
    fun add(grant: SendGrant)
    fun remove(grant: SendGrant)

    fun covers(request: SendRequest): Boolean = grants.value.any { it.covers(request) }
}

/** For tests and hosts that keep nothing across restarts. */
class InMemorySendGrantStore : SendGrantStore {
    private val state = MutableStateFlow<List<SendGrant>>(emptyList())
    override val grants: StateFlow<List<SendGrant>> = state.asStateFlow()
    override fun add(grant: SendGrant) {
        state.value = (state.value.filterNot { it == grant } + grant)
    }
    override fun remove(grant: SendGrant) {
        state.value = state.value.filterNot { it == grant }
    }
}
