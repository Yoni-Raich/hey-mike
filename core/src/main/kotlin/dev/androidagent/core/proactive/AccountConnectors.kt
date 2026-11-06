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

import dev.androidagent.core.EngineKind
import dev.androidagent.core.TaskSignal

/**
 * A connector the user already connected in their AI account, such as Gmail
 * in claude.ai. The engine signed in with that account brings it; Hey Mike
 * holds no token for it. Only the tools the service itself marks read-only
 * are kept in [readTools].
 */
data class AccountConnector(
    val engine: EngineKind,
    /** The engine's server name, e.g. "claude.ai Gmail". */
    val server: String,
    /** connected | needs-auth | failed | pending */
    val state: String,
    /** Full tool names the engine accepts, read-only only. */
    val readTools: List<String>,
    /** Tools left out because the service does not mark them read-only. */
    val writeTools: Int,
) {
    val connected: Boolean get() = state == "connected"
    /** The product without the account prefix: "Gmail". */
    val name: String get() = server.substringAfter(' ', server).trim()
}

/** An engine that can list its account connectors and let background passes use some of them. */
interface AccountConnectorSource {
    val engine: EngineKind
    /** The account's connectors as the engine sees them now. Empty when signed out or unsupported. */
    suspend fun accountConnectors(): List<AccountConnector>
    /** The only account tools a background (read-only) pass may call. Empty turns them all off. */
    fun allowForBackground(tools: List<String>)
    fun accountReadDefinitions(): List<dev.androidagent.core.ToolDefinition> = emptyList()
    suspend fun readAccountTool(tool: String, arguments: kotlinx.serialization.json.JsonObject, permitted: () -> Boolean): dev.androidagent.core.ToolResult =
        dev.androidagent.core.ToolResult("Account reads are unavailable.", success = false)
}

/**
 * Picks where a notification's context comes from on one pass: the app's own
 * connector when it is ready, else a read-only account connector of the
 * engine that runs the pass, else only the notification text.
 */
object ContextRouter {
    fun key(c: AccountConnector): String = "account:${c.engine.name.lowercase()}:${c.server}"

    /** Connected, switched on by the user, with at least one read-only tool, on [engine]. */
    fun usable(accounts: List<AccountConnector>, engine: EngineKind, enabled: (String) -> Boolean): List<AccountConnector> =
        accounts.filter { it.engine == engine && it.connected && it.readTools.isNotEmpty() && enabled(key(it)) }

    /** What [AccountConnectorSource.allowForBackground] gets for each engine. */
    fun backgroundTools(accounts: List<AccountConnector>, engine: EngineKind, enabled: (String) -> Boolean): List<String> =
        usable(accounts, engine, enabled).flatMap { it.readTools }.distinct().sorted()

    /** The account connector that explains notifications from [app], if any. */
    fun accountFor(app: String?, usable: List<AccountConnector>): AccountConnector? {
        if (app.isNullOrBlank()) return null
        return usable.firstOrNull { c -> APPS[c.name.lowercase()]?.any { app == it || app.startsWith("$it.") } == true }
    }

    /**
     * Notes for rows the app's own connectors did not explain but an account
     * connector can: the pass reads them itself, read-only. [explained] are
     * signal ids that already have context.
     */
    fun accountNotes(
        rows: List<TaskSignal>,
        explained: Set<String>,
        usable: List<AccountConnector>,
        allowed: (app: String, conversation: String?) -> Boolean,
    ): List<RowContext> = rows.mapNotNull { row ->
        if (row.id in explained) return@mapNotNull null
        val app = row.app ?: return@mapNotNull null
        val account = accountFor(app, usable) ?: return@mapNotNull null
        if (!allowed(app, row.conversation)) return@mapNotNull null
        val tools = account.readTools.joinToString(", ") { it.substringAfterLast("__") }
        val route = if (account.engine == EngineKind.CODEX) "Use account_read(operation=list) for the schemas, then account_read(operation=read, tool=..., arguments=...). " else ""
        RowContext(row.id, null,
            "Nothing was prefetched. If the notification is not enough, read it yourself with the read-only ${account.name} tools of your account ($tools). ${route}Read only what explains this row.")
    }

    /** Which notification apps an account product explains. Products not listed are offered without a hint. */
    val APPS: Map<String, Set<String>> = mapOf(
        "gmail" to setOf("com.google.android.gm"),
        "google calendar" to setOf("com.google.android.calendar"),
    )
}
