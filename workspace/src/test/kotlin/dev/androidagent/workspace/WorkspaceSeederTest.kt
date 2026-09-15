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
    fun seedWritesOnlyTheBundledAgentsMd() {
        // An embedded ADB-era copy used to be written after the asset, so the
        // current AGENTS.md never reached a single chat.
        val ws = tempFolder.newFolder("workspace")
        WorkspaceSeeder.seedFrom(ws, bundled)

        assertEquals(BUNDLED_AGENTS, File(ws, "AGENTS.md").readText())
        assertEquals(setOf("AGENTS.md"), ws.list()!!.toSet())
    }

    @Test
    fun theShippedGuidanceIsAccessibilityFirstAndPointsAtEveryShippedSkill() {
        val shipped = File(assetRoot(), "AGENTS.md").readText()
        assertTrue(shipped.contains("Observe → Evaluate → Plan → Act → Verify"))
        assertTrue(shipped.contains("ADB being disconnected is normal"))
        // Identity lives in the system instructions only; a second copy is how they drifted apart.
        assertFalse(shipped.contains("Your name is"))
        assertFalse("the ADB-era framing must not come back", shipped.contains("over local Wireless ADB"))

        val shippedSkills = File(assetRoot(), "skills").list()!!.toSet()
        assertEquals(
            setOf("device-automation", "app-cards", "user-preferences", "quick-actions", "workflows", "automations"),
            shippedSkills,
        )
        for (skill in shippedSkills) assertTrue("AGENTS.md must point at $skill", shipped.contains("`$skill`"))
        assertFalse(shipped.contains("recovery-and-safety"))
    }

    @Test
    fun theShippedPreferencesSkillCarriesThePathPlaceholder() {
        val skill = File(assetRoot(), "skills/user-preferences/SKILL.md").readText()
        assertTrue(skill.contains(WorkspaceSeeder.PREFERENCES_PATH_PLACEHOLDER))
    }

    @Test
    fun theShippedWorkflowsSkillNamesTheDirectoryDefinitionsAreLoadedFrom() {
        // Without the real path, a definition the model writes lands somewhere
        // workflow_runner never looks.
        val skill = File(assetRoot(), "skills/workflows/SKILL.md").readText()
        assertTrue(skill.contains(WorkspaceSeeder.WORKFLOW_DEFINITIONS_DIR_PLACEHOLDER))
    }

    @Test
    fun seedRemovesTheFilesOlderReleasesPlantedButKeepsTheUsersOwn() {
        val ws = tempFolder.newFolder("workspace_legacy")
        File(ws, "RECOVERY.md").writeText("old")
        File(ws, "cards").mkdirs()
        File(ws, "cards/whatsapp.md").writeText("old")
        val mixed = tempFolder.newFolder("workspace_legacy_mixed")
        File(mixed, "cards").mkdirs()
        File(mixed, "cards/whatsapp.md").writeText("old")
        File(mixed, "cards/mine.md").writeText("keep me")

        WorkspaceSeeder.seedFrom(ws, bundled)
        WorkspaceSeeder.seedFrom(mixed, bundled)

        assertFalse(File(ws, "RECOVERY.md").exists())
        assertFalse(File(ws, "cards").exists())
        assertFalse(File(mixed, "cards/whatsapp.md").exists())
        assertEquals("keep me", File(mixed, "cards/mine.md").readText())
    }

    @Test
    fun anUntouchedPerChatPreferencesCopyIsDeletedAndACustomizedOneKept() {
        val untouched = tempFolder.newFolder("ws_untouched")
        File(untouched, "preferences.json").writeText(LEGACY_DEFAULT)
        val customized = tempFolder.newFolder("ws_customized")
        File(customized, "preferences.json").writeText(SIGNAL)

        WorkspaceSeeder.seed(untouched, null)
        WorkspaceSeeder.seed(customized, null)

        assertFalse(File(untouched, "preferences.json").exists())
        assertEquals(SIGNAL, File(customized, "preferences.json").readText())
    }

    @Test
    fun globalPreferencesStartFromTheBundledDefaults() {
        val home = tempFolder.newFolder("home_fresh")
        WorkspaceSeeder.ensureGlobalPreferences(home, tempFolder.newFolder("sessions_empty"), bundled)
        assertEquals(BUNDLED_PREFERENCES, WorkspaceSeeder.preferencesFile(home).readText())
    }

    @Test
    fun globalPreferencesInheritTheNewestCustomizedChatCopy() {
        // A user who taught an older release "I use Signal" must not lose it.
        val sessions = tempFolder.newFolder("sessions")
        chatPreferences(sessions, "a", SIGNAL, modified = 2_000)
        chatPreferences(sessions, "b", WAZE, modified = 1_000)
        chatPreferences(sessions, "c", LEGACY_DEFAULT, modified = 9_000)
        val home = tempFolder.newFolder("home_migrate")

        WorkspaceSeeder.ensureGlobalPreferences(home, sessions, bundled)

        assertEquals(SIGNAL, WorkspaceSeeder.preferencesFile(home).readText())
    }

    @Test
    fun globalPreferencesAreNeverOverwritten() {
        val home = tempFolder.newFolder("home_existing")
        WorkspaceSeeder.preferencesFile(home).writeText(WAZE)
        val sessions = tempFolder.newFolder("sessions_other")
        chatPreferences(sessions, "a", SIGNAL, modified = 5_000)

        WorkspaceSeeder.ensureGlobalPreferences(home, sessions, bundled)

        assertEquals(WAZE, WorkspaceSeeder.preferencesFile(home).readText())
    }

    @Test
    fun installDefaultSkillsWritesThePreferencesPathAndRetiresOldSkills() {
        val home = tempFolder.newFolder("home")
        val legacy = File(home, ".codex/skills/device-automation/SKILL.md")
        legacy.parentFile!!.mkdirs()
        legacy.writeText("legacy")
        val retired = File(home, ".agents/skills/recovery-and-safety/SKILL.md")
        retired.parentFile!!.mkdirs()
        retired.writeText("retired")
        val userOwn = File(home, ".agents/skills/my-skill/SKILL.md")
        userOwn.parentFile!!.mkdirs()
        userOwn.writeText("mine")

        WorkspaceSeeder.installDefaultSkills(home) { relativePath ->
            val name = relativePath.substringBefore('/')
            when {
                relativePath.endsWith(".sh") -> "#!/system/bin/sh\r\nDATA=\"${WorkspaceSeeder.QUICK_ACTIONS_DIR_PLACEHOLDER}\"\r\n"
                relativePath.endsWith(".tsv") -> "a\tb\r\n"
                else -> "---\nname: $name\ndescription: d\n---\n\n$name at ${WorkspaceSeeder.PREFERENCES_PATH_PLACEHOLDER} " +
                    "${WorkspaceSeeder.SKILLS_DIR_PLACEHOLDER}\n"
            }.toByteArray()
        }

        for (name in listOf("device-automation", "app-cards", "user-preferences", "quick-actions")) {
            assertTrue("$name should be installed", File(home, ".agents/skills/$name/SKILL.md").isFile)
        }
        val script = File(home, ".agents/skills/quick-actions/scripts/act.sh").readText()
        assertFalse("scripts must reach the phone with LF endings", script.contains('\r'))
        assertTrue(script.contains(WorkspaceSeeder.quickActionsDir(home).absolutePath))
        assertTrue(File(home, ".agents/skills/quick-actions/scripts/intents.tsv").isFile)
        assertTrue(
            File(home, ".agents/skills/quick-actions/SKILL.md").readText()
                .contains(File(home, ".agents/skills").absolutePath),
        )
        val preferencesSkill = File(home, ".agents/skills/user-preferences/SKILL.md").readText()
        assertTrue(preferencesSkill.contains(WorkspaceSeeder.preferencesFile(home).absolutePath))
        assertFalse(preferencesSkill.contains(WorkspaceSeeder.PREFERENCES_PATH_PLACEHOLDER))
        assertFalse("retired skill must be removed", retired.exists())
        assertEquals("the user's own skills stay", "mine", userOwn.readText())
        assertFalse("legacy CODEX_HOME skill copy should be removed", legacy.exists())
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

    private fun chatPreferences(sessions: File, id: String, content: String, modified: Long) {
        val file = File(sessions, "$id/workspace/preferences.json")
        file.parentFile!!.mkdirs()
        file.writeText(content)
        file.setLastModified(modified)
    }

    private val bundled: (String) -> ByteArray = { path ->
        when (path) {
            "AGENTS.md" -> BUNDLED_AGENTS.toByteArray()
            "preferences.json" -> BUNDLED_PREFERENCES.toByteArray()
            else -> error("unexpected asset $path")
        }
    }

    @Test
    fun noWorkflowDefinitionShipsWithTheApp() {
        // A definition names one phone's screen ids; Settings search on a
        // Nothing phone and on a Xiaomi share none. The agent learns each
        // sequence on the phone it runs on instead.
        assertFalse(File(assetRoot(), "workflows").exists())
    }

    private fun assetRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/agent_stack") }
            .first { it.isDirectory }

    private companion object {
        const val BUNDLED_AGENTS = "# Bundled harness\n"
        const val BUNDLED_PREFERENCES = "{\"apps\":{}}"
        const val SIGNAL = """{"apps":{"messaging":"Signal"}}"""
        const val WAZE = """{"apps":{"maps":"Waze"}}"""
        const val LEGACY_DEFAULT = """{
  "apps": {
    "messaging": "WhatsApp",
    "browser": "Chrome",
    "maps": "Google Maps",
    "music": "YouTube"
  },
  "addresses": {},
  "contacts": {},
  "defaults": {
    "confirm_destructive": true
  }
}"""
    }
}
