package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerClaudeSignInTest {
    private fun link(id: String) = "https://claude.ai/oauth/authorize?state=$id"

    @Test fun eachComputerGetsItsOwnLoginAndCompletionRefreshesItsModels() = runTest {
        val submitted = mutableListOf<Pair<String, String>>()
        val refreshed = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val login = ComputerClaudeSignIn(
            this,
            begin = { AccountStatus(false, "Sign in", loginUrl = link(it)) },
            complete = { id, code -> submitted += id to code; AccountStatus(true, "me") },
            stop = {}, refresh = { refreshed += it },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { login.loginLinks.collect { opened += it } }
        login.start("pc")
        login.start("server")
        runCurrent()
        assertEquals(listOf(link("pc"), link("server")), opened)
        assertEquals(link("server"), login.state.value["server"]!!.loginUrl)
        login.submit("pc", "private-code#state")
        runCurrent()
        assertEquals(listOf("pc" to "private-code#state"), submitted)
        assertEquals(listOf("pc"), refreshed)
        assertNull(login.state.value["pc"]!!.loginUrl)
        assertFalse(login.state.value["pc"]!!.busy)
        assertEquals(link("server"), login.state.value["server"]!!.loginUrl)
        assertFalse(login.state.value.toString().contains("private-code"))
    }

    @Test fun duplicateTapsCannotStartTwoProcessesOnOneComputer() = runTest {
        val waiting = CompletableDeferred<AccountStatus>()
        var started = 0
        val login = ComputerClaudeSignIn(this, begin = { started++; waiting.await() }, complete = { _, _ -> error("unused") }, stop = {}, refresh = {})
        login.start("pc")
        login.start("pc")
        runCurrent()
        assertEquals(1, started)
        assertTrue(login.state.value["pc"]!!.busy)
        waiting.complete(AccountStatus(false, "Sign in", loginUrl = link("pc")))
        runCurrent()
        assertFalse(login.state.value["pc"]!!.busy)
    }

    @Test fun cancellingBeforeTheLinkArrivesStopsTheLoginAndAllowsRetry() = runTest {
        val waiting = CompletableDeferred<AccountStatus>()
        val stopped = mutableListOf<String>()
        var started = 0
        val login = ComputerClaudeSignIn(
            this, begin = { started++; if (started == 1) waiting.await() else AccountStatus(false, "Sign in", loginUrl = link(it)) },
            complete = { _, _ -> error("unused") }, stop = { stopped += it }, refresh = {},
        )
        login.start("pc")
        runCurrent()
        login.cancel("pc")
        assertEquals(listOf("pc"), stopped)
        assertFalse(login.state.value.containsKey("pc"))
        login.start("pc")
        runCurrent()
        assertEquals(link("pc"), login.state.value["pc"]!!.loginUrl)
    }

    @Test fun failedCompletionDropsTheExpiredLinkAndDoesNotRefreshAnotherAccount() = runTest {
        var refreshed = false
        val login = ComputerClaudeSignIn(
            this, begin = { AccountStatus(false, "Sign in", loginUrl = link(it)) },
            complete = { _, _ -> AccountStatus(false, "Not signed in") }, stop = {}, refresh = { refreshed = true },
        )
        login.start("pc")
        runCurrent()
        login.submit("pc", "expired-code")
        runCurrent()
        assertFalse(refreshed)
        assertNull(login.state.value["pc"]!!.loginUrl)
        assertTrue(login.state.value["pc"]!!.error!!.contains("expired"))
        assertFalse(login.state.value["pc"]!!.busy)
        login.start("pc")
        runCurrent()
        assertNull(login.state.value["pc"]!!.error)
    }

    @Test fun processErrorsAreNotShownAsRawLoginOutput() = runTest {
        val login = ComputerClaudeSignIn(this, begin = { error("secret-code#state") }, complete = { _, _ -> error("unused") }, stop = {}, refresh = {})
        login.start("pc")
        runCurrent()
        assertFalse(login.state.value.toString().contains("secret-code"))
        assertFalse(login.state.value["pc"]!!.busy)
        assertTrue(login.state.value["pc"]!!.error!!.contains("connection"))
    }

    @Test fun cancellingAWaitingCodeDoesNotSubmitOrRefreshIt() = runTest {
        val stopped = mutableListOf<String>()
        val login = ComputerClaudeSignIn(
            this, begin = { AccountStatus(false, "Sign in", loginUrl = link(it)) },
            complete = { _, _ -> error("must not submit") }, stop = { stopped += it }, refresh = { error("must not refresh") },
        )
        login.start("pc")
        login.start("server")
        runCurrent()
        login.cancel("pc")
        assertEquals(listOf("pc"), stopped)
        assertFalse(login.state.value.containsKey("pc"))
        assertEquals(link("server"), login.state.value["server"]!!.loginUrl)
    }
}
