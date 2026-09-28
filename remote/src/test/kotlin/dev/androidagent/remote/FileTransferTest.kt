package dev.androidagent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferTest {
    @Test fun rateAndTimeLeftComeFromWhatHasMovedSoFar() {
        val move = FileTransfer(1, "a.apk", "Pc", toPhone = true, bytes = 10_000_000, total = 40_000_000, startedAt = 0)
        assertEquals(0.25f, move.fraction!!, 0.0001f)
        assertEquals(5_000_000L, move.rate(2_000))
        assertEquals(6L, move.secondsLeft(2_000))
        // The first half second says nothing about speed.
        assertNull(move.rate(300))
        assertNull(move.copy(total = 0).fraction)
        assertNull(move.copy(total = 0).secondsLeft(2_000))
    }
}
