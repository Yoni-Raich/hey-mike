/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.workspace

import dev.androidagent.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileResponsibilityStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "persistent/state.json")
    private fun value(state: ResponsibilityState) = ResponsibilitySnapshot(responsibilities = listOf(
        Responsibility("parcel", "Delivery", "Watch my package", listOf("delivery-rule"), state,
            createdAt = 1, updatedAt = 2, notes = "Use my home address")
    ))

    @Test fun newInstancesRetainLifecycleAndExplicitNotes() {
        FileResponsibilityStore(file).save(value(ResponsibilityState.PAUSED))
        assertEquals(value(ResponsibilityState.PAUSED), FileResponsibilityStore(file).load())
        FileResponsibilityStore(file).save(value(ResponsibilityState.COMPLETED))
        assertEquals(ResponsibilityState.COMPLETED, FileResponsibilityStore(file).load().responsibilities.single().state)
    }

    @Test fun corruptDataIsAnErrorInsteadOfReleasingRules() {
        FileResponsibilityStore(file).save(value(ResponsibilityState.PAUSED))
        file.writeText("{broken")
        assertTrue(runCatching { FileResponsibilityStore(file).load() }.isFailure)
    }

    @Test fun interruptedTemporaryWriteDoesNotReplaceCommittedState() {
        FileResponsibilityStore(file).save(value(ResponsibilityState.PAUSED))
        File(file.parentFile, ".state.json.tmp").writeText("{half-written")
        assertEquals(value(ResponsibilityState.PAUSED), FileResponsibilityStore(file).load())
    }

    @Test fun invalidReplacementLeavesTheOldStateIntact() {
        val store = FileResponsibilityStore(file)
        store.save(value(ResponsibilityState.PAUSED))
        assertTrue(runCatching { store.save(value(ResponsibilityState.ACTIVE).copy(schemaVersion = 99)) }.isFailure)
        assertEquals(value(ResponsibilityState.PAUSED), store.load())
    }

    @Test fun signedStateRejectsTamperingAndMissingState() {
        val seal = TestSeal()
        val store = FileResponsibilityStore(file, seal)
        store.save(value(ResponsibilityState.PAUSED))
        assertEquals(value(ResponsibilityState.PAUSED), FileResponsibilityStore(file, seal).load())
        file.writeText(file.readText().replace("PAUSED", "ACTIVE"))
        assertTrue(runCatching { store.load() }.isFailure)
        file.delete()
        assertTrue(runCatching { store.load() }.isFailure)
    }

    private class TestSeal : ResponsibilitySeal {
        private var exists = false
        override fun hasKey() = exists
        override fun sign(payload: String): String {
            exists = true
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec("unit-test-key".toByteArray(), "HmacSHA256"))
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray()))
        }
        override fun verify(payload: String, signature: String) = hasKey() && sign(payload) == signature
    }
}
