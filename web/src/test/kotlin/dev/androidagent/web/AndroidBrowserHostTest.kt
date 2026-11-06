package dev.androidagent.web

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Real host and view code with Android shadows: where a page lives, who
 * controls it, and how it moves onto Hey Mike's screen. No renderer, no CDP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidBrowserHostTest {
    private val key = "fixture-chat"
    private val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
    private val frame = BrowserFrame(activity)
    private var presented = mutableListOf<String>()
    private lateinit var made: AndroidBrowserHost

    private fun host(appComesForward: Boolean = true): AndroidBrowserHost {
        made = AndroidBrowserHost(RuntimeEnvironment.getApplication(), takeScreen = { true }, present = { asked ->
            presented += asked
            if (appComesForward) made.attach(asked, frame)
        })
        made.open(key)
        return made
    }

    @After fun closePages() { if (::made.isInitialized) runBlocking { made.closeAll() } }

    @Test fun showPutsThePageOnHeyMikesScreenWithTheUserInControl() = runBlocking {
        val host = host()
        assertTrue(host.show(key))
        assertEquals(listOf(key), presented)
        assertEquals(1, frame.childCount)
        assertTrue(host.pageStates.value.getValue(key).userControlled)
        assertFalse("the user can touch a page they control", frame.locked)
    }

    @Test fun anotherChatsScreenWorkDoesNotTakeAShownPageAway() = runBlocking {
        // Regression: show used to schedule its own hiding through screen hand-over.
        val host = host()
        assertTrue(host.show(key))
        val failure = runCatching { host.screenInUse("another-chat") }.exceptionOrNull()
        assertTrue("phone work must not silently take a user-owned page", failure is WebFailure)
        assertEquals("user_control", (failure as WebFailure).errorType)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, frame.childCount)
        assertTrue(host.pageStates.value.getValue(key).userControlled)
    }

    @Test fun showSaysSoWhenTheAppCouldNotComeForward() = runBlocking {
        val host = host(appComesForward = false)
        assertFalse(host.show(key))
        assertEquals(0, frame.childCount)
        // The user still has it: the agent must not act while they are asked to open it.
        assertTrue(host.pageStates.value.getValue(key).userControlled)
    }

    @Test fun screenWorkWaitsForThePanelToLeaveAndKeepsItsPage() = runBlocking {
        val host = host()
        assertTrue(host.show(key))
        host.giveBack(key)
        val view = frame.getChildAt(0)
        val screenWork = async(start = CoroutineStart.UNDISPATCHED) { host.screenInUse(key) }
        try {
            assertFalse("screen work must wait for the UI to remove the panel", screenWork.isCompleted)
            assertEquals(1, frame.childCount)
            // The Compose panel's disposal acknowledges that it has left the screen.
            host.detach(key, frame)
            screenWork.await()
            assertEquals(0, frame.childCount)
            assertNotNull("the page is parked, not closed", view.parent)
            assertNotSame(frame, view.parent)
            assertTrue(key in host.pageStates.value)
            assertFalse(host.pageStates.value.getValue(key).userControlled)
        } finally { screenWork.cancel() }
    }

    @Test fun anotherChatsScreenWorkAlsoWaitsForAnAgentOwnedPanel() = runBlocking {
        val host = host()
        assertTrue(host.show(key))
        host.giveBack(key)
        val screenWork = async(start = CoroutineStart.UNDISPATCHED) { host.screenInUse("another-chat") }
        try {
            assertFalse(screenWork.isCompleted)
            assertEquals(1L, host.pageStates.value.getValue(key).panelDismissals)
            host.detach(key, frame)
            screenWork.await()
            assertTrue(key in host.pageStates.value)
        } finally { screenWork.cancel() }
    }

    @Test fun screenWorkIsRefusedWhenTheUiDoesNotRemoveThePanel() = runBlocking {
        val host = host()
        assertTrue(host.show(key))
        host.giveBack(key)
        val failure = runCatching { host.screenInUse(key) }.exceptionOrNull()
        assertTrue(failure is WebFailure)
        assertEquals("browser_panel_busy", (failure as WebFailure).errorType)
        assertEquals(1, frame.childCount)
        assertTrue(key in host.pageStates.value)
    }

    @Test fun leavingTheScreenMovesThePageBackToItsHiddenWindow() = runBlocking {
        val host = host()
        assertTrue(host.attach(key, frame))
        val view = frame.getChildAt(0)
        host.detach(key, frame)
        assertEquals(0, frame.childCount)
        assertNotNull("the WebView is kept, in its hidden window", view.parent)
        assertNotSame(frame, view.parent)
        // A second frame cannot be detached by the first one's disposal.
        val other = BrowserFrame(activity)
        host.attach(key, other)
        host.detach(key, frame)
        assertEquals(1, other.childCount)
    }

    @Test fun theAgentsPageHoldsBackTheUsersTouches() = runBlocking {
        val host = host()
        host.attach(key, frame)
        assertTrue(frame.locked)
        host.takeControl(key)
        assertFalse(frame.locked)
        host.giveBack(key)
        assertTrue(frame.locked)
        assertFalse(host.pageStates.value.getValue(key).userControlled)

        val touched = mutableListOf<Int>()
        val probe = BrowserFrame(activity).apply {
            addView(object : View(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean { touched += event.actionMasked; return true }
            })
            layout(0, 0, 100, 100)
            getChildAt(0).layout(0, 0, 100, 100)
        }
        val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        probe.dispatchTouchEvent(down)
        assertTrue("a locked frame keeps the touch from the page", touched.isEmpty())
        probe.locked = false
        probe.dispatchTouchEvent(down)
        assertEquals(listOf(MotionEvent.ACTION_DOWN), touched)
        down.recycle()
    }

    @Test fun theUserClosingThePageEndsIt() = runBlocking {
        val host = host()
        host.attach(key, frame)
        host.closeByUser(key)
        assertEquals(0, frame.childCount)
        assertFalse(key in host.pageStates.value)
        assertFalse(host.attach(key, frame))
    }

    @Test fun aLateDetachFromTheOldChatsFrameLeavesTheNewChatsPage() = runBlocking {
        val host = host()
        val other = "other-chat"
        host.open(other)
        val frameA = BrowserFrame(activity)
        val frameB = BrowserFrame(activity)
        assertTrue(host.attach(key, frameA))
        // The user switched chats: B's panel attaches before A's panel is disposed.
        assertTrue(host.attach(other, frameB))
        host.detach(key, frameA)
        assertEquals("B's page stays on screen", 1, frameB.childCount)
        assertEquals("A's page left its old frame", 0, frameA.childCount)
        // A detach naming the wrong frame or page takes nothing away.
        host.detach(other, frameA)
        host.detach(key, frameB)
        assertEquals(1, frameB.childCount)
    }

    @Test fun aDesktopSiteStaysWithTheChatForItsNextPage() = runBlocking {
        val host = host()
        assertFalse(host.pageStates.value.getValue(key).desktop)
        host.layout(key, desktop = true)
        assertTrue(host.pageStates.value.getValue(key).desktop)
        host.closeByUser(key)
        host.open(key)
        assertTrue("the chat asked for desktop sites", host.pageStates.value.getValue(key).desktop)
        host.layout(key, desktop = false)
        assertFalse(host.pageStates.value.getValue(key).desktop)
        host.open("other-chat")
        assertFalse("another chat keeps its own layout", host.pageStates.value.getValue("other-chat").desktop)
    }

    @Test fun layoutNeedsAPage() = runBlocking {
        val host = host()
        val failure = runCatching { host.layout("no-such-chat", desktop = true) }.exceptionOrNull()
        assertEquals("no_page", (failure as WebFailure).errorType)
    }
}
