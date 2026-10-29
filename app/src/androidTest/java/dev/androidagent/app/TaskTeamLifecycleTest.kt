package dev.androidagent.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Run on the fixture emulator: checks the real ViewModel's empty-chat cleanup. */
class TaskTeamLifecycleTest {
    @Test(timeout = 15_000) fun missingCalendarPermissionDoesNotStartAnUnfinishablePass() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        val graph = (context.applicationContext as AgentApplication).graph
        withTimeout(10_000) { while (!graph.taskDesk.state.value.ready) delay(50) }
        val original = graph.taskDesk.board.settings.value
        try {
            graph.taskDesk.board.editSettings { it.copy(discoveryOn = true, readChats = false, readCalendar = true) }
            graph.taskDesk.pulse()
            assertFalse(graph.taskDesk.signals.pending.value.any { it.source == "calendar" })
            assertFalse(graph.taskDesk.hasBackgroundWork())
        } finally {
            graph.taskDesk.board.editSettings { original }
            graph.taskDesk.stopBackgroundTurns()
        }
    }

    @Test(timeout = 15_000) fun backstageVoiceIsRefusedBeforeAnAccountOrRuntimeIsOpened() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentApplication).graph
        withTimeout(10_000) { while (!graph.taskDesk.state.value.ready) delay(50) }
        for(member in graph.taskDesk.team.members.value.filter { it.kind.backstage }) {
            val id = member.sessionId!!
            val before = graph.sessions.getSession(id)!!.engineThreadId
            val error = runCatching { graph.voiceConversation.begin(id, null) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("read-only typed chat"))
            assertNull(graph.voiceConversation.sessionId.value)
            assertEquals(before, graph.sessions.getSession(id)!!.engineThreadId)
        }
    }

    @Test(timeout = 25_000) fun teamChatsSurviveOpeningLeavingAndActivityRecreationBeforeTheFirstUserMessage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as AgentApplication).graph
        withTimeout(10_000) { while (!graph.taskDesk.state.value.ready) delay(50) }
        val members = graph.taskDesk.team.members.value
        assertEquals(4, members.size)
        // The Manager eye animates forever. Wait for the Activity's lifecycle,
        // not main-loop idleness, which an animated real screen need not reach.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: MainActivity? = null
        suspend fun resumed(previous: MainActivity? = null): MainActivity = withTimeout(5_000) {
            var found: MainActivity? = null
            while (found == null) {
                instrumentation.runOnMainSync {
                    found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { it !== previous }
                }
                if (found == null) delay(25)
            }
            found!!
        }
        try {
            activity = resumed()
            lateinit var model: AgentViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity!!)[AgentViewModel::class.java] }
            for (member in members) {
                val id = member.sessionId!!
                instrumentation.runOnMainSync { model.select(id) }
                withTimeout(2_000) { while (model.ui.value.activeSessionId != id) delay(25) }
                instrumentation.runOnMainSync { model.newChat() }
                withTimeout(2_000) { while (model.ui.value.activeSessionId == id) delay(25) }
                val chat = graph.sessions.getSession(id)
                assertNotNull("${member.name} was removed by empty-chat cleanup", chat)
                assertTrue(chat!!.hasMessages)
                assertFalse(chat.titlePending)
            }
            val previous = activity!!
            instrumentation.runOnMainSync { previous.recreate() }
            activity = resumed(previous)
            for (member in members) assertNotNull(graph.sessions.getSession(member.sessionId!!))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
