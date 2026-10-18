package dev.androidagent.workspace

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeAtomicPublisherTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var publisher: NativeAtomicPublisher

    @Before fun nativeFixture() {
        assumeNotNull(System.getProperty("mike.delta.fixtureDir"))
        publisher = NativeAtomicPublisher()
        assertTrue(publisher.available)
    }

    @Test fun publishesUnicodeFileWithoutHardLinks() {
        val part = temp.newFile("source").apply { writeText("complete") }
        val target = File(temp.root, "עדכון-😀.apk")
        publisher.commit(part, target, false)
        assertEquals("complete", target.readText())
        assertFalse(part.exists())
    }

    @Test fun existingFileIsPreserved() {
        val part = temp.newFile("source").apply { writeText("new") }
        val target = temp.newFile("target").apply { writeText("old") }
        try { publisher.commit(part, target, false); fail() } catch (_: FileAlreadyExistsException) { }
        assertEquals("old", target.readText())
        assertEquals("new", part.readText())
    }

    @Test fun failedPublicationDoesNotTouchPreviousTarget() {
        val target = temp.newFile("target").apply { writeText("old") }
        try { publisher.commit(File(temp.root,"missing"), target, false); fail() } catch (_: IOException) { }
        assertEquals("old", target.readText())
    }

    @Test fun concurrentNoReplaceHasOneWinner() {
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val target = File(temp.root,"target")
        try {
            val tasks = (1..2).map { n ->
                val part = temp.newFile("part$n").apply { writeText("$n") }
                workers.submit<Boolean> {
                    start.await()
                    try { publisher.commit(part, target, false); true } catch (_: FileAlreadyExistsException) { false }
                }
            }
            start.countDown()
            assertEquals(1, tasks.count { it.get(5,TimeUnit.SECONDS) })
            assertTrue(target.readText() in listOf("1","2"))
        } finally { workers.shutdownNow() }
    }
}
