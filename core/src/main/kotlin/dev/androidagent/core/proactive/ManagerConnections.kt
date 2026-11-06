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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Manager's read-only connections in one place: the app's own connectors
 * (WhatsApp Web) and the account connectors the engines bring (claude.ai or
 * ChatGPT Gmail, Calendar, ...). It knows their state, which ones the user
 * switched on, and tells each engine which account tools it may load.
 *
 * [enabled] reads the user's switch for a connector id or account key;
 * [allowed] is the source policy for an app and conversation.
 */
class ManagerConnections(
    private val enabled: (String) -> Boolean,
    private val allowed: (app: String, conversation: String?) -> Boolean,
    private val probeMs: Long = ACCOUNT_PROBE_MS,
    private val statusMs: Long = STATUS_MS,
) {
    /** The app's own read-only connectors, registered by the app graph. */
    @Volatile var connectors: List<ContextConnector> = emptyList()
    /** Engines whose account connectors chats and passes may read through, read-only. */
    @Volatile var accountSources: List<AccountConnectorSource> = emptyList()
    /** Connector id to its last known state, for the Connections cards. */
    @Volatile var states: Map<String, ConnectorStatus> = emptyMap()
        private set
    /** Null until the engines were asked once in this process. */
    @Volatile private var known: List<AccountConnector>? = null
    private val accountLock = Mutex()

    val accounts: List<AccountConnector> get() = known.orEmpty()
    val accountsChecked: Boolean get() = known != null

    /** The user flipped [id]; the engines learn at once, before the saved setting lands. */
    fun switched(id: String, on: Boolean) = pushAccountTools { key -> if (key == id) on else enabled(key) }

    /** Whether the pass engine can read mail through an account connector the user switched on, and mail is an allowed source. */
    fun mailReadable(engine: EngineKind): Boolean =
        ContextRouter.readsMail(ContextRouter.usable(accounts, engine, enabled)) && allowed(ContextRouter.GMAIL_APP, null)

    /** The connections note for a Manager or team chat running on [engine]. */
    fun note(engine: EngineKind?): String? {
        val apps = connectors.filter { c -> enabled(c.id) && states[c.id]?.state == ConnectorState.READY }
            .map { c -> if (c.id == ConnectorIds.WHATSAPP_WEB) "WhatsApp Web" else c.id.replaceFirstChar { it.uppercase() } }
        return ContextRouter.connectionsNote(apps, ContextRouter.usable(accounts, null, enabled), engine)
    }

    /**
     * Context for a pass on [engine]: what the app's connectors [pulled], and
     * for rows they did not explain, a note to read them through the engine's
     * own account connectors.
     */
    suspend fun passContext(batch: List<TaskSignal>, pulled: List<RowContext>, engine: EngineKind): List<RowContext> {
        if (accountSources.isNotEmpty() && known == null) loadAccounts()
        val viaAccount = ContextRouter.accountNotes(batch, pulled.filter { it.context != null }.map { it.signalId }.toSet(),
            ContextRouter.usable(accounts, engine, enabled), allowed)
        val accountRows = viaAccount.map { it.signalId }.toSet()
        return pulled.filter { it.signalId !in accountRows } + viaAccount
    }

    /** Ask every connector for its state, then the engines for their account connectors. [changed] runs after each step. */
    suspend fun refresh(changed: () -> Unit) {
        states = connectors.associate { c -> c.id to status(c) }
        changed()
        if (accountSources.isNotEmpty()) { loadAccounts(); changed() }
    }

    private suspend fun loadAccounts() = accountLock.withLock {
        known = accountSources.flatMap { source ->
            try { withTimeoutOrNull(probeMs) { source.accountConnectors() }.orEmpty() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        pushAccountTools(enabled)
    }

    private fun pushAccountTools(on: (String) -> Boolean) {
        val all = accounts
        accountSources.forEach { it.allowAccountTools(ContextRouter.allowedTools(all, it.engine, on)) }
    }

    private suspend fun status(c: ContextConnector): ConnectorStatus =
        try { withTimeoutOrNull(statusMs) { c.status() } ?: ConnectorStatus(ConnectorState.NEEDS_ATTENTION, "no answer") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ConnectorStatus(ConnectorState.NEEDS_ATTENTION, e.message) }

    companion object {
        /** Claude's account list can take a while to arrive after sign-in. */
        const val ACCOUNT_PROBE_MS = 45_000L
        const val STATUS_MS = 10_000L
    }
}
