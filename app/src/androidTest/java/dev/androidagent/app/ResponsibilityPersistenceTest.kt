/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.*
import dev.androidagent.workspace.FileResponsibilityStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponsibilityPersistenceTest {
    @Test fun keystoreSignedLifecycleSurvivesReopeningAndRejectsForgedActivation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "responsibility-test-$id").apply { mkdirs() }
        val alias = "hey_mike_test_responsibility_$id"
        try {
            val file = File(root, "state.json")
            val library = AutomationLibrary(File(root, "rules"))
            library.save(AutomationRule.parse(Json.parseToJsonElement(
                """{"id":"parcel","when":{"type":"manual"},"then":[{"type":"notify","text":"update"}]}"""
            ).jsonObject))
            val seal = KeystoreResponsibilitySeal(alias)
            val service = ResponsibilityService(FileResponsibilityStore(file, seal), library)
            assertNull(service.loadError)
            service.create("deliveries", "Deliveries", "Watch parcel", listOf("parcel"))
            service.activate("deliveries")
            service.pause("deliveries")
            val reopened = ResponsibilityService(FileResponsibilityStore(file, seal), library)
            assertNull(reopened.loadError)
            assertEquals(ResponsibilityState.PAUSED, reopened.get("deliveries")?.state)
            file.writeText(file.readText().replace("PAUSED", "ACTIVE"))
            val forged = ResponsibilityService(FileResponsibilityStore(file, seal), library)
            assertNotNull(forged.loadError)
            assertTrue(forged.runnable(library.all()).isEmpty())
        } finally {
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
