/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkflowLibraryTest {

    @get:Rule val temp = TemporaryFolder()

    private fun library() = WorkflowLibrary(File(temp.root, "definitions"))

    private fun definition(id: String, pkg: String = "com.android.settings", step: String = "open") = """
        {"id":"$id","package":"$pkg","steps":[{"id":"$step","action":"open_app"}]}
    """.trimIndent().toByteArray()

    private fun write(id: String, bytes: ByteArray = definition(id)) {
        val dir = File(temp.root, "definitions").apply { mkdirs() }
        File(dir, "$id.json").writeBytes(bytes)
    }

    @Test fun aWorkflowIsFoundByItsExactId() {
        write("wireless-debugging")
        val found = library().find("wireless-debugging")
        assertTrue(found is WorkflowLibrary.Lookup.Found)
        assertEquals("wireless-debugging", (found as WorkflowLibrary.Lookup.Found).definition.id)
    }

    @Test fun theNameTheModelSaysOutLoudStillResolves() {
        // The model names a workflow from a skill or a listing, and "wireless
        // debugging" for `wireless-debugging` is the ordinary near miss.
        write("wireless-debugging")
        for (name in listOf("Wireless Debugging", "wireless debugging", "wirelessdebugging", "wireless_debugging")) {
            assertTrue(name, library().find(name) is WorkflowLibrary.Lookup.Found)
        }
    }

    @Test fun anAmbiguousNameIsRefusedRatherThanGuessed() {
        // Running the wrong workflow drives the phone through someone else's
        // steps, so a near miss that fits two files is an error.
        write("wifi-on")
        write("wifi-off")
        val found = library().find("wifi")
        assertTrue(found.toString(), found is WorkflowLibrary.Lookup.Ambiguous)
        assertEquals(setOf("wifi-on", "wifi-off"), (found as WorkflowLibrary.Lookup.Ambiguous).candidates.toSet())
    }

    @Test fun anUnknownNameComesBackWithTheOnesThatDoExist() {
        write("wireless-debugging")
        val found = library().find("bluetooth")
        assertTrue(found is WorkflowLibrary.Lookup.NotFound)
        assertEquals(listOf("wireless-debugging"), (found as WorkflowLibrary.Lookup.NotFound).known)
    }

    @Test fun thePackageNarrowsAnAmbiguousNameWithoutHidingTheRest() {
        write("settings-toggle")
        write("chrome-toggle", definition("chrome-toggle", pkg = "com.android.chrome"))
        assertTrue(library().find("toggle", "com.android.chrome") is WorkflowLibrary.Lookup.Found)
        assertTrue(library().find("toggle") is WorkflowLibrary.Lookup.Ambiguous)
        assertEquals(1, library().forPackage("com.android.chrome").size)
    }

    @Test fun aPackageWithNoDefinitionsNeverFallsBackToAnotherApp() {
        write("settings-toggle")
        val found = library().find("settings-toggle", "com.android.chrome")
        assertTrue(found is WorkflowLibrary.Lookup.NotFound)
        assertEquals(listOf("settings-toggle"), (found as WorkflowLibrary.Lookup.NotFound).known)
    }

    @Test fun aFileThatDoesNotParseIsNamedRatherThanHidden() {
        // A definition the user wrote that simply never appears looks ignored.
        write("good")
        write("broken", """{"id":"broken","steps":[]}""".toByteArray())
        assertEquals(listOf("good"), library().all().map { it.id })
        assertEquals(listOf("broken.json"), library().broken().map { it.file })
        assertTrue(library().broken().single().reason.isNotEmpty())
    }

    @Test fun savingReplacesTheSameWorkflowRatherThanAccumulatingCopies() {
        val library = library()
        val first = WorkflowDefinition.parse(
            Json.parseToJsonElement(String(definition("flow", step = "one"))).jsonObject,
        )
        library.save(first)
        library.save(first.copy(description = "second time"))
        assertEquals(1, library.all().size)
        assertEquals("second time", library.all().single().description)
    }

    @Test fun oversizedReplacementLeavesThePreviousDefinitionLoadable() {
        val library = library()
        val original = WorkflowDefinition.parse(
            Json.parseToJsonElement(String(definition("flow", step = "safe"))).jsonObject,
        )
        val file = library.save(original)
        val savedBytes = file.readBytes()

        val error = runCatching {
            library.save(original.copy(description = "x".repeat(300 * 1024)))
        }.exceptionOrNull()

        assertTrue("expected oversized replacement to be refused, got $error", error is IllegalArgumentException)
        assertArrayEquals(savedBytes, file.readBytes())
        val loaded = library.find("flow") as WorkflowLibrary.Lookup.Found
        assertEquals(original.steps, loaded.definition.steps)
        assertEquals(original.description, loaded.definition.description)
    }

    @Test fun theDefinitionsDirectorySitsUnderTheWorkflowsDirectory() {
        // Saved step lists and definitions age out together when an app is
        // redesigned, which is the reason they live in one place.
        val home = temp.newFolder("home")
        assertEquals(
            File(WorkflowStore.directoryIn(home), "definitions"),
            WorkflowLibrary.directoryIn(home),
        )
    }

    @Test fun anEmptyLibraryIsAnEmptyListRatherThanAFailure() {
        assertTrue(library().all().isEmpty())
        assertTrue(library().broken().isEmpty())
        assertTrue(library().find("anything") is WorkflowLibrary.Lookup.NotFound)
    }
}
