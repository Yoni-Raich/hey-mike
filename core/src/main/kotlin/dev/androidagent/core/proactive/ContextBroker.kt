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

package dev.androidagent.core.proactive

import dev.androidagent.core.SignalFilter
import dev.androidagent.core.TaskSignal
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ConnectorState {
    /** The user turned it off. */
    OFF,
    NOT_LINKED,
    LINKING,
    READY,
    /** It was linked and now fails: logged out, layout changed, grant revoked. */
    NEEDS_ATTENTION;
    val wire: String get() = name.lowercase()
}

data class ConnectorStatus(val state: ConnectorState, val detail: String? = null, val account: String? = null)

data class ConversationQuery(
    val conversation: String?,
    val sender: String?,
    /** The notification's newest line, to find the exact chat. */
    val hintText: String?,
    val group: Boolean,
    /** Messages before this time, for "load earlier". */
    val before: Long? = null,
    val limit: Int = ContextBroker.MESSAGES,
)

data class ContextMessage(
    /** in | out */
    val direction: String,
    val sender: String?,
    val text: String,
    val at: Long?,
    val kind: String = "text",
)

data class ConversationContext(
    val connector: String,
    val title: String?,
    val messages: List<ContextMessage>,
    /** text | title | sender | subject */
    val matchedBy: String,
    val hasEarlier: Boolean,
    val note: String? = null,
)

/**
 * A read-only source of conversation context. Implementations may not send,
 * delete, mark read or change anything at the source.
 */
interface ContextConnector {
    val id: String
    /** Notification packages this connector can explain. */
    val packages: Set<String>
    suspend fun status(): ConnectorStatus
    suspend fun conversation(query: ConversationQuery): ConversationContext
}

/** Why a connector could not help; shown in the prompt in place of context. */
class ConnectorUnavailable(val code: String, message: String) : Exception(message)

/** What the broker attached to one row. Exactly one of [context] and [note] is set. */
data class RowContext(val signalId: String, val context: ConversationContext?, val note: String?)

/**
 * Pulls conversation context for signal rows at pass time. Pure apart from the
 * connectors it is given; nothing it returns is written to disk.
 */
class ContextBroker(
    private val connectors: () -> List<ContextConnector>,
    /** The user's on/off switch per connector id. */
    private val enabled: (String) -> Boolean,
    /** The Sources page decision for an app and conversation. Checked before any fetch. */
    private val allowed: (app: String, conversation: String?) -> Boolean,
    private val ownPackage: String,
    private val timeoutMs: Long = FETCH_TIMEOUT_MS,
) {
    fun connectorFor(app: String?): ContextConnector? {
        if (app.isNullOrBlank()) return null
        return connectors().firstOrNull { c -> enabled(c.id) && c.packages.any { app == it || app.startsWith("$it.") } }
    }

    /** Context for up to [ROWS_PER_PASS] rows that have a connector. Rows without one are left out. */
    suspend fun enrich(rows: List<TaskSignal>): List<RowContext> {
        val out = mutableListOf<RowContext>()
        for (row in rows) {
            if (out.size >= ROWS_PER_PASS) break
            val app = row.app ?: continue
            val connector = connectorFor(app) ?: continue
            if (!allowed(app, row.conversation)) continue
            val hint = row.items.lastOrNull()?.text ?: row.text.lineSequence().lastOrNull { it.isNotBlank() }
            out += fetch(connector, app, ConversationQuery(row.conversation, row.sender, hint, row.group)).let {
                RowContext(row.id, it.first, it.second)
            }
        }
        return out
    }

    /** "Pull more": a model asks for earlier messages of a conversation it already saw. */
    suspend fun more(app: String, conversation: String?, sender: String?, group: Boolean, before: Long?, limit: Int): Pair<ConversationContext?, String?> {
        val connector = connectorFor(app) ?: return null to "No connection is turned on for this app."
        if (!allowed(app, conversation)) return null to "The user excluded this source."
        return fetch(connector, app, ConversationQuery(conversation, sender, null, group, before, limit.coerceIn(1, MAX_MESSAGES)))
    }

    private suspend fun fetch(connector: ContextConnector, app: String, query: ConversationQuery): Pair<ConversationContext?, String?> = try {
        val status = withTimeout(timeoutMs) { connector.status() }
        if (status.state != ConnectorState.READY) null to notReady(connector.id, status)
        else {
            val raw = withTimeout(timeoutMs) { connector.conversation(query) }
            clean(raw, app, query.limit) to null
        }
    } catch (e: TimeoutCancellationException) {
        null to "${connector.id} did not answer in time; using the notification text."
    } catch (e: ConnectorUnavailable) {
        null to "${connector.id}: ${e.message}"
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null to "${connector.id} failed (${e.javaClass.simpleName}); using the notification text."
    }

    private fun notReady(id: String, status: ConnectorStatus) = when (status.state) {
        ConnectorState.OFF -> "$id is turned off."
        ConnectorState.NOT_LINKED -> "$id is not linked."
        ConnectorState.LINKING -> "$id is still linking."
        ConnectorState.NEEDS_ATTENTION -> "$id needs attention${status.detail?.let { ": $it" } ?: ""}."
        ConnectorState.READY -> "$id is ready."
    }

    /** Codes and payments are dropped, lines and the total are capped. */
    private fun clean(raw: ConversationContext, app: String, limit: Int): ConversationContext {
        var budget = CHARS_PER_ROW
        val kept = ArrayDeque<ContextMessage>()
        var dropped = 0
        for (m in raw.messages.takeLast(limit.coerceIn(1, MAX_MESSAGES)).asReversed()) {
            if (SignalFilter.drop(app, m.text, ownPackage)) { dropped++; continue }
            val text = m.text.replace(Regex("\\s+"), " ").trim().take(MESSAGE_CHARS)
            if (text.isEmpty()) continue
            if (text.length > budget) break
            budget -= text.length
            kept.addFirst(m.copy(text = text, sender = m.sender?.take(80)))
        }
        val hidden = raw.messages.size - kept.size - dropped
        val note = listOfNotNull(raw.note, if (dropped > 0) "$dropped sensitive message(s) left out." else null).joinToString(" ").ifBlank { null }
        return raw.copy(title = raw.title?.take(120), messages = kept.toList(), hasEarlier = raw.hasEarlier || hidden > 0, note = note)
    }

    companion object {
        const val MESSAGES = 20
        const val MAX_MESSAGES = 50
        const val CHARS_PER_ROW = 6_000
        const val MESSAGE_CHARS = 1_200
        const val ROWS_PER_PASS = 4
        const val FETCH_TIMEOUT_MS = 25_000L

        fun toJson(row: RowContext): JsonObject = buildJsonObject {
            put("signalId", row.signalId)
            row.note?.let { put("note", it) }
            row.context?.let { put("conversation", toJson(it)) }
        }

        fun toJson(c: ConversationContext): JsonObject = buildJsonObject {
            put("connector", c.connector)
            c.title?.let { put("title", it) }
            put("matchedBy", c.matchedBy)
            put("hasEarlier", c.hasEarlier)
            c.note?.let { put("note", it) }
            c.messages.firstOrNull()?.at?.let { put("oldestAt", it) }
            put("messages", buildJsonArray {
                c.messages.forEach { m ->
                    add(buildJsonObject {
                        put("dir", m.direction)
                        m.sender?.let { put("from", it) }
                        m.at?.let { put("at", it) }
                        if (m.kind != "text") put("kind", m.kind)
                        put("text", m.text)
                    })
                }
            })
        }
    }
}
