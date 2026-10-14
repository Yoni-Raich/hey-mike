package dev.androidagent.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicTransferFileTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun noReplaceCannotOverwriteAnExistingTarget() {
        val target=temp.newFile("target").apply { writeText("old") }; val part=temp.newFile("part").apply { writeText("new") }
        try { AtomicTransferFile.commit(part,target,false); fail() } catch(_:FileAlreadyExistsException) { }
        assertEquals("old",target.readText()); assertEquals("new",part.readText())
    }
    @Test fun failedReplaceKeepsThePreviousTargetIntact() {
        val target=temp.newFolder("target"); File(target,"previous").writeText("old")
        val part=temp.newFile("part").apply { writeText("new") }
        try { AtomicTransferFile.commit(part,target,true); fail() } catch(_:java.io.IOException) { }
        assertEquals("old",File(target,"previous").readText()); assertTrue(part.exists())
    }
    @Test fun concurrentNoReplaceHasOnlyOneWinner() {
        val target=File(temp.root,"target"); val start=CountDownLatch(1); val workers=Executors.newFixedThreadPool(2)
        try {
            val futures=(1..2).map { n ->
                val part=temp.newFile("part$n").apply { writeText("$n") }
                workers.submit<Boolean> { start.await(); try { AtomicTransferFile.commit(part,target,false); true } catch(_:FileAlreadyExistsException) { false } }
            }
            start.countDown(); assertEquals(1,futures.count { it.get(5,TimeUnit.SECONDS) })
            assertTrue(target.readText() in listOf("1","2"))
        } finally { workers.shutdownNow() }
    }
}
