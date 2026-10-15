package dev.androidagent.web

import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real host/window code with Android shadows; no WebView renderer or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidBrowserHostTest {
    @Test fun showingAPageDoesNotHideItOnTheNextMainLoopTick() = runBlocking {
        lateinit var host: AndroidBrowserHost
        host = AndroidBrowserHost(RuntimeEnvironment.getApplication(),
            takeScreen = { key -> host.screenInUse(key); true })
        val key = "fixture-chat"
        // Seed the same parked Android window as session() without opening a CDP socket.
        val create = host.javaClass.getDeclaredMethod("create", String::class.java).apply { isAccessible = true }
        val page = checkNotNull(create.invoke(host, key))
        val pagesField = host.javaClass.getDeclaredField("pages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pages = pagesField.get(host) as MutableMap<String, Any>
        pages[key] = page
        try {
            host.show(key)
            shadowOf(Looper.getMainLooper()).idle()
            val shown = host.javaClass.getDeclaredField("visibleKey").apply { isAccessible = true }
            assertEquals(key, shown.get(host))
            host.screenInUse("another-chat")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shown.get(host))
        } finally { host.closeAll() }
    }
}
