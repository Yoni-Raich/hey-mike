package dev.androidagent.app

import dev.androidagent.app.ui.ChildAgentItem
import dev.androidagent.core.*
import dev.androidagent.remote.*
import org.junit.Assert.*
import org.junit.Test

class ChildAgentItemTest {
    @Test fun failedRemoteSetupStillNamesTheRequestedComputerAndProject() {
        val task = SessionAgentTask("task", "key", "root", "Read", "Read notes", EngineKind.CLAUDE,
            createdAt = 0, status = "failed", computer = "pc", project = "C:\\projects\\Project X")
        val remote = RemoteState(computers = listOf(RemoteComputer("pc", "Work PC", "", user = "test")))
        assertEquals("Work PC · Project X", ChildAgentItem.from(task, remote, emptyList()).place)
        assertEquals("This phone", ChildAgentItem.from(task.copy(computer = "phone"), remote, emptyList()).place)
    }
}
