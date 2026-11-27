package dev.androidagent.remote

import com.jcraft.jsch.JSchException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SshConnectGateTest {
    @Test fun transientFailuresBackOffAndSuccessResetsTheBudget() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val gate = SshConnectGate(clock = { now }, sleep = { waits += it; now += it }, jitter = { it })
        fun failure() {
            try { gate.connect("pc") { throw SshUnreachable("offline", Exception()) }; fail() }
            catch (_: SshUnreachable) { }
        }
        failure()
        failure()
        gate.connect("pc") { Unit }
        failure()
        gate.connect("pc") { Unit }
        assertEquals(listOf(1_000L, 2_000L, 1_000L), waits)
    }

    @Test fun authenticationAttemptsAreSerializedPerComputerAndWaitingIsInterruptible() {
        val gate = SshConnectGate()
        val pool = Executors.newFixedThreadPool(3)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        try {
            val first = pool.submit { gate.connect("pc") { inside.countDown(); release.await() } }
            assertTrue(inside.await(2, TimeUnit.SECONDS))
            val second = pool.submit { gate.connect("pc") { secondEntered.countDown() } }
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
            val other = pool.submit<Boolean> { gate.connect("server") { true } }
            assertTrue(other.get(2, TimeUnit.SECONDS))
            assertTrue(second.cancel(true))
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertEquals(1L, secondEntered.count)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun resetIsTransientButAuthenticationFailureIsNot() {
        assertTrue(SshLink.unreachable(JSchException("Session.connect", SocketException("Connection reset"))))
        assertTrue(SshLink.unreachable(JSchException("Session.connect: java.net.SocketException: Connection reset")))
        assertFalse(SshLink.unreachable(JSchException("Auth fail")))
    }

    @Test fun securityErrorsDoNotCreateBackoff() {
        val waits = mutableListOf<Long>()
        val gate = SshConnectGate(clock = { 0 }, sleep = { waits += it }, jitter = { it })
        try {
            gate.connect("pc") { throw HostKeyChanged(HostKeySeen("key", "value", "fingerprint")) }
            fail()
        } catch (_: HostKeyChanged) { }
        gate.connect("pc") { Unit }
        assertTrue(waits.isEmpty())
    }
}
