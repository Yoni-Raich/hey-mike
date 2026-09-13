package dev.androidagent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSeederTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun seedWritesTheBundledAgentsMdAndNothingElseOverIt() {
        // An embedded ADB-era copy used to be written after the asset, so the
        // current AGENTS.md never reached a single chat.
        val ws = tempFolder.newFolder("workspace")
        WorkspaceSeeder.seedFrom(ws, bundled)

        assertEquals(BUNDLED_AGENTS, File(ws, "AGENTS.md").readText())
        assertEquals(BUNDLED_PREFERENCES, File(ws, "preferences.json").readText())
        assertFalse("workspace must not duplicate user skills", File(ws, ".agents/skills/device-automation").exists())
        assertFalse("workspace must not use legacy .codex skills", File(ws, ".codex/skills/device-automation").exists())
        assertEquals(setOf("AGENTS.md", "preferences.json"), ws.list()!!.toSet())
    }

    @Test
    fun theShippedAgentsMdIsAccessibilityFirst() {
        val shipped = File(assetRoot(), "AGENTS.md").readText()
        assertTrue(shipped.contains("Observe → Evaluate → Plan → Act → Verify"))
        assertTrue(shipped.contains("Golden rules"))
        assertTrue(shipped.contains("ADB being disconnected is normal"))
        assertFalse("the ADB-era framing must not come back", shipped.contains("over local Wireless ADB"))
        for (skill in listOf("device-automation", "app-cards", "recovery-and-safety", "user-preferences")) {
            assertTrue("AGENTS.md must point at $skill", shipped.contains(skill))
            assertTrue("$skill must ship", File(assetRoot(), "skills/$skill/SKILL.md").isFile)
        }
    }

    @Test
    fun seedRemovesTheFilesOlderReleasesPlantedButKeepsTheUsersOwn() {
        val ws = tempFolder.newFolder("workspace_legacy")
        File(ws, "RECOVERY.md").writeText("old")
        File(ws, "cards").mkdirs()
        File(ws, "cards/whatsapp.md").writeText("old")
        val legacyOnly = tempFolder.newFolder("workspace_legacy_mixed")
        File(legacyOnly, "cards").mkdirs()
        File(legacyOnly, "cards/whatsapp.md").writeText("old")
        File(legacyOnly, "cards/mine.md").writeText("keep me")

        WorkspaceSeeder.seedFrom(ws, bundled)
        WorkspaceSeeder.seedFrom(legacyOnly, bundled)

        assertFalse(File(ws, "RECOVERY.md").exists())
        assertFalse(File(ws, "cards").exists())
        assertFalse(File(legacyOnly, "cards/whatsapp.md").exists())
        assertEquals("keep me", File(legacyOnly, "cards/mine.md").readText())
    }

    @Test
    fun seedWithoutAssetsStillGuaranteesPreferences() {
        val ws = tempFolder.newFolder("workspace_no_assets")
        WorkspaceSeeder.seed(ws, null)

        val prefs = File(ws, "preferences.json")
        assertTrue("preferences.json should exist", prefs.isFile)
        assertTrue("preferences.json should have default structure", prefs.readText().contains("\"messaging\": \"WhatsApp\""))
    }

    private val bundled: (String) -> ByteArray = { path ->
        when (path) {
            "AGENTS.md" -> BUNDLED_AGENTS.toByteArray()
            "preferences.json" -> BUNDLED_PREFERENCES.toByteArray()
            else -> error("unexpected asset $path")
        }
    }

    /** The real bundled assets, so the shipped text is checked rather than a copy of it. */
    private fun assetRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/agent_stack") }
            .first { it.isDirectory }

    private companion object {
        const val BUNDLED_AGENTS = "# Bundled harness\n"
        const val BUNDLED_PREFERENCES = "{\"apps\":{}}"
    }

    @Test
    fun installDefaultSkillsUsesStandardUserRootAndCleansLegacyCopies() {
        val home = tempFolder.newFolder("home")
        val legacy = File(home, ".codex/skills/device-automation/SKILL.md")
        legacy.parentFile!!.mkdirs()
        legacy.writeText("legacy")
        val contents = mapOf(
            "device-automation" to "device body",
            "recovery-and-safety" to "recovery body",
            "user-preferences" to "preferences body",
            "app-cards" to "cards body",
        )

        WorkspaceSeeder.installDefaultSkills(home) { relativePath ->
            val name = relativePath.substringBefore('/')
            "---\nname: $name\ndescription: $name description\n---\n\n${contents.getValue(name)}\n".toByteArray()
        }

        for ((name, body) in contents) {
            val installed = File(home, ".agents/skills/$name/SKILL.md")
            assertTrue("$name should be installed in the standard user root", installed.isFile)
            assertTrue(installed.readText().contains(body))
        }
        assertFalse("legacy CODEX_HOME skill copy should be removed", legacy.exists())
        assertFalse("deprecated CODEX_HOME skill root must not be populated", File(home, ".codex/skills/app-cards").exists())
    }

    @Test
    fun seedRemovesOnlyManagedWorkspaceSkillDuplicates() {
        val ws = tempFolder.newFolder("workspace_cleanup")
        val oldManaged = File(ws, ".agents/skills/device-automation/SKILL.md")
        oldManaged.parentFile!!.mkdirs()
        oldManaged.writeText("old managed copy")
        val unrelated = File(ws, ".agents/skills/custom-user-skill/SKILL.md")
        unrelated.parentFile!!.mkdirs()
        unrelated.writeText("keep me")

        WorkspaceSeeder.seed(ws, null)

        assertFalse(oldManaged.exists())
        assertTrue("unrelated skills must remain untouched", unrelated.isFile)
        assertEquals("keep me", unrelated.readText())
    }

    @Test
    fun seedPreservesExistingUserPreferences() {
        val ws = tempFolder.newFolder("workspace_prefs")
        val customPrefs = """{"apps":{"messaging":"Signal"},"customKey":"preserved"}"""
        val prefsFile = File(ws, "preferences.json")
        prefsFile.writeText(customPrefs)

        WorkspaceSeeder.seed(ws, null)

        assertEquals("Existing preferences.json must not be overwritten", customPrefs, prefsFile.readText())
    }
}
