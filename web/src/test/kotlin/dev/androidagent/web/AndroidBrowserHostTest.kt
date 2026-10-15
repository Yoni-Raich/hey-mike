package dev.androidagent.web

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
        host.screenInUse("another-chat")
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
}
