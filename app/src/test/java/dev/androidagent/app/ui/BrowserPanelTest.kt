package dev.androidagent.app.ui

import dev.androidagent.web.BrowserPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPanelTest {
    @Test fun theBarNamesTheSiteAndWarnsOnPlainHttp() {
        assertEquals("Blank page", browserSiteLabel(BrowserPageState("chat")))
        assertEquals("bank.example", browserSiteLabel(BrowserPageState("chat", host = "bank.example")))
        assertEquals("Not secure · shop.example", browserSiteLabel(BrowserPageState("chat", host = "shop.example", secure = false)))
    }

    @Test fun theUserIsToldWhoHasThePage() {
        assertTrue(browserControlLabel(BrowserPageState("chat", userControlled = true)).startsWith("You have control"))
        assertTrue(browserControlLabel(BrowserPageState("chat")).startsWith("Mike is using this page"))
    }

    @Test fun aSwitchedChatNeverShowsThePreviousChatsPage() {
        val pageA = BrowserPageState("/chats/a/workspace", host = "a.example")
        // The chat switched to B before the page collector caught up: A's page is still in the state.
        val switching = AgentUiState(activeSessionId = "b", browserPage = pageA, browserPageChat = "a", browserOpenFor = "b")
        assertNull(switching.activeBrowserPage())
        assertFalse(switching.browserShown())
        // Once the collector writes B's (absent) page, B still shows nothing of A.
        assertNull(switching.copy(browserPage = null, browserPageChat = "b").activeBrowserPage())
        // A's own state shows A's page.
        val onA = AgentUiState(activeSessionId = "a", browserPage = pageA, browserPageChat = "a", browserOpenFor = "a")
        assertEquals(pageA, onA.activeBrowserPage())
        assertTrue(onA.browserShown())
    }
}
