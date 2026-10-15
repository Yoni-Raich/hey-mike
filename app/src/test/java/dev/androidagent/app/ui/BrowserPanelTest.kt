package dev.androidagent.app.ui

import dev.androidagent.web.BrowserPageState
import org.junit.Assert.assertEquals
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
}
