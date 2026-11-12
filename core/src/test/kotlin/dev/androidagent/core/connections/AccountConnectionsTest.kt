package dev.androidagent.core.connections

import dev.androidagent.core.EngineKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountConnectionsTest {
    private val codexMail = AccountConnector(EngineKind.CODEX, "ChatGPT Gmail", "connected", listOf("gmail.read"), 2)
    private val claudeMail = AccountConnector(EngineKind.CLAUDE, "claude.ai Gmail", "connected", listOf("mcp__claude_ai_Gmail__read"), 1)

    private class Source(override val engine: EngineKind, var accounts: List<AccountConnector>) : AccountConnectorSource {
        var probes = 0
        var allowed = emptyList<String>()
        var probe: (suspend () -> List<AccountConnector>)? = null
        override suspend fun accountConnectors(): List<AccountConnector> { probes++; return probe?.invoke() ?: accounts }
        override fun allowAccountTools(tools: List<String>) { allowed = tools }
    }

    @Test fun discoveryDoesNotEnableAnythingAndGrantsStayWithTheirProvider() = runTest {
        val codex = Source(EngineKind.CODEX, listOf(codexMail))
        val claude = Source(EngineKind.CLAUDE, listOf(claudeMail))
        var saved = emptySet<String>()
        val connections = AccountConnections(listOf(codex, claude), saveEnabled = { saved = it })
        assertEquals(0, codex.probes + claude.probes)
        assertTrue(codex.allowed.isEmpty())
        connections.refresh()
        assertTrue(connections.state.value.active.isEmpty())
        assertTrue(connections.setEnabled(codexMail.id, true))
        assertEquals(codexMail.readTools, codex.allowed)
        assertTrue(claude.allowed.isEmpty())
        assertEquals(setOf(codexMail.id), saved)
        assertTrue(connections.setEnabled(codexMail.id, false))
        assertTrue(codex.allowed.isEmpty())
    }

    @Test fun savedGrantsRequireFreshMetadataBeforeToolsAreAllowed() = runTest {
        val source = Source(EngineKind.CODEX, listOf(codexMail))
        val connections = AccountConnections(listOf(source), setOf(codexMail.id))
        assertTrue(source.allowed.isEmpty())
        assertEquals("unavailable", connections.state.value.accounts.single().state)
        connections.refresh()
        assertEquals(codexMail.readTools, source.allowed)
    }

    @Test fun aFailedProviderIsRevokedWithoutHidingItsSwitchOrTheOtherProvider() = runTest {
        val codex = Source(EngineKind.CODEX, listOf(codexMail))
        val claude = Source(EngineKind.CLAUDE, listOf(claudeMail))
        val connections = AccountConnections(listOf(codex, claude), setOf(codexMail.id, claudeMail.id))
        connections.refresh()
        codex.probe = { error("Unavailable") }
        connections.refresh()
        assertTrue(codex.allowed.isEmpty())
        assertEquals(claudeMail.readTools, claude.allowed)
        assertEquals(setOf(EngineKind.CODEX), connections.state.value.errors)
        assertEquals("failed", connections.state.value.accounts.single { it.engine == EngineKind.CODEX }.state)
        assertTrue(connections.setEnabled(codexMail.id, false))
        assertFalse(codexMail.id in connections.state.value.enabled)
    }

    @Test fun aSwitchTurnedOffDuringRefreshCannotBeTurnedOnByItsLateAnswer() = runTest {
        val source = Source(EngineKind.CODEX, listOf(codexMail))
        val connections = AccountConnections(listOf(source), setOf(codexMail.id))
        connections.refresh()
        val answer = CompletableDeferred<List<AccountConnector>>()
        source.probe = { answer.await() }
        val refresh = launch { connections.refresh() }
        runCurrent()
        assertTrue(EngineKind.CODEX in connections.state.value.loading)
        connections.setEnabled(codexMail.id, false)
        answer.complete(listOf(codexMail))
        refresh.join()
        assertTrue(source.allowed.isEmpty())
        assertTrue(connections.state.value.active.isEmpty())
    }

    @Test fun missingOrWriteOnlyConnectionsCannotBeGrantedAndOldMissingOnesCanBeDisabled() = runTest {
        val writes = codexMail.copy(server = "ChatGPT Write only", readTools = emptyList())
        val source = Source(EngineKind.CODEX, listOf(writes))
        val connections = AccountConnections(listOf(source), setOf(codexMail.id))
        connections.refresh()
        assertFalse(connections.setEnabled("account:codex:Unknown", true))
        assertFalse(connections.setEnabled(writes.id, true))
        assertFalse(connections.setEnabled(codexMail.id, true))
        assertTrue(connections.setEnabled(codexMail.id, false))
        assertTrue(source.allowed.isEmpty())
    }

    @Test fun cancellationClearsTheLoadingState() = runTest {
        val source = Source(EngineKind.CODEX, emptyList())
        source.probe = { CompletableDeferred<List<AccountConnector>>().await() }
        val connections = AccountConnections(listOf(source))
        val refresh = launch { connections.refresh() }
        runCurrent()
        refresh.cancel()
        refresh.join()
        assertTrue(connections.state.value.loading.isEmpty())
        assertTrue(source.allowed.isEmpty())
    }
}
