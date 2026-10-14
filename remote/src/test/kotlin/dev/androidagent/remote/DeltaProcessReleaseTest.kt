package dev.androidagent.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DeltaProcessReleaseTest {
    private class Helper(private val finishes: Boolean) : Process() {
        var inputClosed = false
        var destroyed = false
        var waited = false
        val input = object : ByteArrayOutputStream() { override fun close() { inputClosed = true } }
        override fun getOutputStream() = input
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waited = true; return finishes }
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
    }

    @Test fun successReleasesTheLeaseWithoutDisconnectingTheExecChannel() {
        val helper = Helper(true)
        finishDeltaProcess(helper, false)
        assertEquals("release\n", helper.input.toString("UTF-8"))
        assertTrue(helper.inputClosed)
        assertTrue(helper.waited)
        assertFalse(helper.destroyed)
    }

    @Test fun delayedNormalExitIsNotForceKilled() {
        val helper = Helper(false)
        finishDeltaProcess(helper, false)
        assertTrue(helper.waited)
        assertFalse(helper.destroyed)
    }

    @Test fun stopClosesStdinAndDestroysOnlyItsHelper() {
        val helper = Helper(false)
        finishDeltaProcess(helper, true)
        assertEquals(0, helper.input.size())
        assertTrue(helper.inputClosed)
        assertTrue(helper.destroyed)
        assertFalse(helper.waited)
    }
}
