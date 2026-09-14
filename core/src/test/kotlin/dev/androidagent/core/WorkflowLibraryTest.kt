package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    @Test fun aBundledDefinitionIsInstalledOnceAndRefreshedByAnUpdate() {
        val library = library()
        library.installBundled(mapOf("shipped" to definition("shipped")))
        assertEquals(listOf("shipped"), library.all().map { it.id })

        val updated = """{"id":"shipped","package":"com.android.settings","description":"v2",
            "steps":[{"id":"open","action":"open_app"}]}""".toByteArray()
        library.installBundled(mapOf("shipped" to updated))
        assertEquals("v2", library.all().single().description)
    }

    @Test fun anEditedWorkflowIsNeverOverwrittenByAnAppUpdate() {
        // The user's own change to a shipped file has to survive, or editing
        // one is pointless.
        val library = library()
        library.installBundled(mapOf("shipped" to definition("shipped")))
        File(temp.root, "definitions/shipped.json").writeBytes(
            """{"id":"shipped","package":"com.android.settings","description":"mine",
               "steps":[{"id":"open","action":"open_app"}]}""".toByteArray(),
        )
        library.installBundled(mapOf("shipped" to definition("shipped")))
        assertEquals("mine", library.all().single().description)
    }

    @Test fun aShippedWorkflowTheUserDeletedStaysDeleted() {
        val library = library()
        library.installBundled(mapOf("shipped" to definition("shipped")))
        assertTrue(File(temp.root, "definitions/shipped.json").delete())
        library.installBundled(mapOf("shipped" to definition("shipped")))
        assertTrue(library.all().isEmpty())
    }

    @Test fun aBundledFileThatDoesNotParseIsNotInstalledAtAll() {
        val library = library()
        library.installBundled(mapOf("bad" to """{"id":"bad"}""".toByteArray()))
        assertTrue(library.all().isEmpty())
        assertTrue(library.broken().isEmpty())
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

/**
 * The definitions this release ships, read from the asset directory itself.
 *
 * A shipped file that does not parse installs as nothing, and the model is
 * then told a workflow it was pointed at does not exist. These are the only
 * definitions a user gets without writing one, so they are checked here rather
 * than discovered on a phone.
 */
class BundledWorkflowTest {

    private fun assetDirectory(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/agent_stack/workflows") }
            .first { it.isDirectory }

    private fun shipped(): List<Pair<String, WorkflowDefinition>> =
        assetDirectory().listFiles()!!.filter { it.name.endsWith(".json") }.sorted().map { file ->
            file.name to WorkflowDefinition.parse(
                Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject,
                source = file.name,
            )
        }

    @Test fun everyShippedDefinitionParsesAndIsNamedAfterItsFile() {
        val definitions = shipped()
        assertTrue("nothing is shipped", definitions.isNotEmpty())
        for ((fileName, definition) in definitions) {
            assertEquals(fileName, definition.id + ".json")
            assertTrue("${definition.id} has no steps", definition.steps.isNotEmpty())
            assertTrue("${definition.id} has no description", definition.description.isNotEmpty())
        }
    }

    @Test fun everyShippedStepThatChangesSomethingSaysHowToTellItWorked() {
        // A step with no condition reports success for a tap that landed on
        // nothing, and every later step then runs against the wrong screen.
        for ((_, definition) in shipped()) {
            for (step in definition.steps) {
                if (!step.action.commits) continue
                assertTrue(
                    "${definition.id}/${step.id} has no verify",
                    step.verify != null,
                )
            }
        }
    }

    @Test fun everyShippedToggleAsksFirstAndIsSafeToResume() {
        // Turning wireless debugging or airplane mode on is the user's call,
        // and re-running a toggle that is already on turns it off.
        for ((_, definition) in shipped()) {
            val toggles = definition.steps.filter { it.verify?.checked != null }
            assertTrue("${definition.id} declares no toggle", toggles.isNotEmpty())
            for (step in toggles) {
                assertTrue("${definition.id}/${step.id} does not ask first", step.requiresConfirmation)
                assertTrue("${definition.id}/${step.id} is not safe to resume", step.skipIfVerified)
            }
        }
    }

    @Test fun theInstalledLibraryLoadsExactlyWhatIsShipped() {
        val temp = File.createTempFile("definitions", "").let { file ->
            file.delete()
            file.apply { mkdirs() }
        }
        try {
            val library = WorkflowLibrary(temp)
            library.installBundled(
                assetDirectory().listFiles()!!
                    .filter { it.name.endsWith(".json") }
                    .associate { it.name.removeSuffix(".json") to it.readBytes() },
            )
            assertEquals(shipped().map { it.second.id }.toSet(), library.all().map { it.id }.toSet())
            assertTrue(library.broken().isEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }
}
