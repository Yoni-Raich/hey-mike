package dev.androidagent.remote

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChildSessionTargetsTest {
    @get:Rule val temp = TemporaryFolder()
    private val store by lazy { RemoteStore(File(temp.root, "remote"), object : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }).also {
        it.save(RemoteComputer("pc", "Pc", "test.invalid", user = "test", access = RemoteAccess.ASK), null)
        it.save(RemoteComputer("server", "Server", "server.invalid", user = "test"), null)
        it.addProject("pc", "C:\\Work\\X"); it.addProject("pc", "C:\\Work\\Y")
        it.addProject("server", "/work/Y")
        it.bind("root", RemoteBinding("pc", "C:\\Work\\X", "source-thread", true))
    } }

    @Test fun inheritanceDoesNotCopyThreadAndExplicitTargetsKeepSavedPolicy() {
        val targets = ChildSessionTargets(store)
        assertEquals(RemoteBinding("pc", "C:\\Work\\X"), targets.resolve("root", null, null))
        assertEquals(RemoteBinding("pc", "C:\\Work\\Y"), targets.resolve("root", null, "y"))
        assertEquals(RemoteBinding("server", "/work/Y"), targets.resolve("root", "Server", "Y"))
        assertNull(targets.resolve("root", "phone", null))
        assertEquals(RemoteAccess.ASK, store.computer("pc")!!.access)
        assertEquals("source-thread", store.binding("root")!!.threadId)
    }

    @Test fun unknownOrAmbiguousComputerAndProjectNeverPickTheFirstMatch() {
        val targets = ChildSessionTargets(store)
        assertTrue(runCatching { targets.resolve("root", "missing", "X") }.isFailure)
        assertTrue(runCatching { targets.resolve("root", "Pc", "missing") }.isFailure)
        assertTrue(runCatching { targets.resolve("root", "Server", null) }.isFailure)
        assertTrue(runCatching { targets.resolve("root", "phone", "X") }.isFailure)
        store.addProject("pc", "D:\\Other\\X")
        assertTrue(runCatching { targets.resolve("root", "Pc", "X") }.isFailure)
        assertEquals("D:\\Other\\X", targets.resolve("root", "Pc", "D:/Other/X")!!.cwd)
        store.save(RemoteComputer("other", "Pc", "other.invalid", user = "test"), null)
        assertTrue(runCatching { targets.resolve("root", "Pc", "Y") }.isFailure)
        assertEquals("pc", targets.resolve("root", "pc", "Y")!!.computerId)
    }

    @Test fun phoneSourceUsesSavedDefaultAndLinuxFullPathsKeepCase() {
        val targets = ChildSessionTargets(store) { mapOf("server" to listOf("/recent/Project")) }
        assertEquals("pc", targets.resolve("phone-source", null, "X")!!.computerId)
        assertEquals("/recent/Project", targets.resolve("root", "Server", "Project")!!.cwd)
        assertEquals("/recent/project", targets.resolve("root", "Server", "/recent/project")!!.cwd)
        assertEquals("C:\\New\\Exact", targets.resolve("root", "Pc", "C:\\New\\Exact")!!.cwd)
        assertTrue(runCatching { targets.resolve("root", "Pc", "relative/path") }.isFailure)
    }
}
