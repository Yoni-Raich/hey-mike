package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutomationLibraryTest {

    @get:Rule val temp = TemporaryFolder()

    private fun library() = AutomationLibrary(File(temp.root, "automations"))

    private fun ruleJson(id: String, enabled: Boolean = true) = """
        {"id":"$id"${if (enabled) "" else ",\"enabled\":false"},
         "when":{"type":"schedule","at":"19:00"},
         "then":[{"type":"notify","text":"hi"}]}
    """.trimIndent()

    private fun write(id: String, body: String = ruleJson(id)) {
        val dir = File(temp.root, "automations").apply { mkdirs() }
        File(dir, "$id.json").writeText(body)
    }

    @Test fun aRuleIsFoundByItsExactId() {
        write("evening-post")
        assertTrue(library().find("evening-post") is AutomationLibrary.Lookup.Found)
    }

    @Test fun theNameSaidOutLoudStillResolves() {
        write("evening-post")
        for (name in listOf("Evening Post", "evening post", "eveningpost", "evening_post")) {
            assertTrue(name, library().find(name) is AutomationLibrary.Lookup.Found)
        }
    }

    @Test fun anAmbiguousNameIsRefusedRatherThanGuessed() {
        // Deleting or disabling the wrong standing rule is not a mistake the
        // user finds out about quickly.
        write("morning-post")
        write("morning-post-weekend")
        assertTrue(library().find("morning") is AutomationLibrary.Lookup.Ambiguous)
    }

    @Test fun anUnknownNameComesBackWithTheOnesThatExist() {
        write("evening-post")
        val lookup = library().find("nope")
        assertTrue(lookup is AutomationLibrary.Lookup.NotFound)
        assertEquals(listOf("evening-post"), (lookup as AutomationLibrary.Lookup.NotFound).known)
    }

    @Test fun savingAndReadingBackPreservesTheRule() {
        val rule = AutomationRule.parse(Json.parseToJsonElement(ruleJson("x")).jsonObject)
        library().save(rule)
        val read = (library().find("x") as AutomationLibrary.Lookup.Found).definition
        assertEquals(rule.toJson(), read.toJson())
        assertEquals("x.json", read.source)
    }

    @Test fun anUnreadableFileIsNamedRatherThanHidden() {
        // A rule that vanished from the listing is a rule the user thinks is
        // running.
        write("good")
        write("broken", "{ this is not json")
        val library = library()
        assertEquals(listOf("good"), library.all().map { it.id })
        assertEquals(listOf("broken.json"), library.broken().map { it.file })
    }

    @Test fun aFileThatParsesButBreaksTheRulesIsAlsoReportedBroken() {
        write("bad", """{"id":"bad","when":{"type":"notification"},"then":[{"type":"notify","text":"x"}]}""")
        val broken = library().broken().single()
        assertEquals("bad.json", broken.file)
        assertTrue(broken.reason, broken.reason.contains("package"))
    }

    @Test fun theJournalIsNotMistakenForARule() {
        write("real")
        File(temp.root, "automations/${AutomationJournal.FILE_NAME}").writeText("""{"version":1,"fires":{}}""")
        assertEquals(listOf("real"), library().all().map { it.id })
        assertTrue(library().broken().isEmpty())
    }

    @Test fun turningARuleOffRewritesOnlyThatFlag() {
        write("x")
        val off = library().setEnabled("x", false)
        assertFalse(off!!.enabled)
        assertTrue(library().all().single().let { !it.enabled })
        assertTrue(library().enabled().isEmpty())
    }

    @Test fun turningOnARuleThatIsAlreadyOnIsANoOp() {
        write("x")
        assertTrue(library().setEnabled("x", true)!!.enabled)
    }

    @Test fun enablingSomethingThatDoesNotExistReturnsNull() {
        assertNull(library().setEnabled("ghost", true))
    }

    @Test fun watchingListsOnlyTheEnabledRulesOfOneTriggerKind() {
        // A host registers a notification listener only when a rule uses one.
        write("scheduled")
        write("off", ruleJson("off", enabled = false))
        write(
            "notif",
            """{"id":"notif","when":{"type":"notification","package":"com.whatsapp"},"then":[{"type":"notify","text":"x"}]}""",
        )
        val library = library()
        assertEquals(listOf("scheduled"), library.watching(AutomationTriggerKind.SCHEDULE).map { it.id })
        assertEquals(listOf("notif"), library.watching(AutomationTriggerKind.NOTIFICATION).map { it.id })
        assertTrue(library.watching(AutomationTriggerKind.PLACE).isEmpty())
    }

    @Test fun deletingRemovesTheFile() {
        write("x")
        assertTrue(library().delete("x"))
        assertTrue(library().all().isEmpty())
        assertFalse(library().delete("x"))
    }

    @Test fun anOversizedFileIsRefusedRatherThanRead() {
        write("huge", "{\"id\":\"huge\",\"description\":\"" + "x".repeat(200_000) + "\"}")
        assertTrue(library().all().isEmpty())
        assertTrue(library().broken().single().reason.contains("larger"))
    }
}
