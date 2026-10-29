package dev.androidagent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopViewTest {
    @Test fun theDesktopUserAgentKeepsTheWebViewsChromeVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 15; A059 Build/AP3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        val desktop = DesktopView.userAgent(mobile)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36", desktop)
        for (phone in listOf("Android", "Mobile", "; wv", "Version/4.0")) assertFalse(phone, desktop.contains(phone))
    }

    @Test fun aUserAgentWithoutChromeStillGivesDesktopChrome() {
        assertTrue(DesktopView.userAgent("user").contains("Chrome/"))
    }

    @Test fun theViewportScriptAsksForTheDesktopWidth() {
        assertTrue(DesktopView.VIEWPORT_SCRIPT.contains("width=${DesktopView.WIDTH}"))
    }
}
