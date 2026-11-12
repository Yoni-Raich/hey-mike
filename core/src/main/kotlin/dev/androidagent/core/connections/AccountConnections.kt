/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core.connections

import dev.androidagent.core.EngineKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class AccountConnectionsState(
    val accounts: List<AccountConnector> = emptyList(),
    val enabled: Set<String> = emptySet(),
    val loading: Set<EngineKind> = emptySet(),
    val checked: Set<EngineKind> = emptySet(),
    val errors: Set<EngineKind> = emptySet(),
) {
    val active: List<AccountConnector> get() = accounts.filter { it.connected && it.readTools.isNotEmpty() && it.id in enabled }
}

/** Ordinary chat abilities. No inbox, notification reader, team, scheduler or Manager dependency. */
class AccountConnections(
    private val sources: List<AccountConnectorSource>,
    initialEnabled: Set<String> = emptySet(),
    private val saveEnabled: (Set<String>) -> Unit = {},
    private val timeoutMs: Long = 45_000,
) {
    private val saved = sources.flatMap { source ->
        val prefix = "account:${source.engine.name.lowercase()}:"
        initialEnabled.filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { AccountConnector(source.engine, it.removePrefix(prefix), "unavailable", emptyList(), 0) }
    }
    private val mutable = MutableStateFlow(AccountConnectionsState(accounts = saved, enabled = saved.map { it.id }.toSet()))
    val state: StateFlow<AccountConnectionsState> = mutable.asStateFlow()
    private val refreshLock = Mutex()
    private val lock = Any()

    init {
        require(sources.map { it.engine }.distinct().size == sources.size)
        sources.forEach { it.allowAccountTools(emptyList()) }
    }

    /** Turning off also works when the provider is unavailable. A new unknown grant is refused. */
    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val before = mutable.value
        if (enabled && before.accounts.none { it.id == id && it.connected && it.readTools.isNotEmpty() }) return@synchronized false
        val grants = if (enabled) before.enabled + id else before.enabled - id
        saveEnabled(grants)
        mutable.value = before.copy(enabled = grants)
        pushPermissions()
        true
    }

    /** Metadata only. The engines are asked together; one unavailable account cannot hide the other. */
    suspend fun refresh() = refreshLock.withLock {
        coroutineScope {
            sources.map { source -> async {
                synchronized(lock) { mutable.value = mutable.value.copy(loading = mutable.value.loading + source.engine) }
                var failed = false
                val accounts = try {
                    withTimeoutOrNull(timeoutMs) { source.accountConnectors() } ?: emptyList<AccountConnector>().also { failed = true }
                } catch (stopped: CancellationException) {
                    synchronized(lock) { mutable.value = mutable.value.copy(loading = mutable.value.loading - source.engine) }
                    throw stopped
                } catch (_: Exception) {
                    failed = true
                    emptyList()
                }
                synchronized(lock) {
                    val before = mutable.value
                    val discovered = accounts.filter { it.engine == source.engine }.distinctBy { it.id }
                    val missing = before.accounts.filter { old -> old.engine == source.engine && discovered.none { it.id == old.id } }
                        .map { it.copy(state = if (failed) "failed" else "unavailable") }
                    mutable.value = before.copy(
                        accounts = (before.accounts.filterNot { it.engine == source.engine } + discovered + missing)
                            .distinctBy { it.id }.sortedWith(compareBy({ it.engine.name }, { it.name })),
                        loading = before.loading - source.engine,
                        checked = before.checked + source.engine,
                        errors = if (failed) before.errors + source.engine else before.errors - source.engine,
                    )
                    pushPermissions()
                }
            } }.awaitAll()
        }
    }

    private fun pushPermissions() {
        val active = mutable.value.active
        sources.forEach { source -> source.allowAccountTools(active.filter { it.engine == source.engine }.flatMap { it.readTools }.distinct().sorted()) }
    }
}
