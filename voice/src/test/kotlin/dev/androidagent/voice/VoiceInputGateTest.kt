// Hey Mike. Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only
package dev.androidagent.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceInputGateTest {
    @Test fun screenDeliveryCompletesBeforeAudioCanOpen() = runBlocking {
        val sent = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Unit>()
        var audioOpened = false
        val start = launch {
            prepareVoiceInput { sent.complete(Unit); delivered.await() }
            audioOpened = true
        }
        sent.await()
        assertFalse(audioOpened)
        delivered.complete(Unit)
        start.join()
        assertTrue(audioOpened)
    }

    @Test fun firstKeystrokeCancelsContextStartupWithoutOpeningAudio() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var audioOpened = false
        val start = launch {
            prepareVoiceInput { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            audioOpened = true
        }
        entered.await()
        start.cancelAndJoin()
        assertFalse(audioOpened)
    }

    @Test fun contextDeliveryFailureKeepsAudioClosed() = runBlocking {
        var audioOpened = false
        try {
            prepareVoiceInput { throw IllegalStateException("context was not delivered") }
            audioOpened = true
            fail("Failed context must not start audio")
        } catch (_: IllegalStateException) { }
        assertFalse(audioOpened)
    }
}
