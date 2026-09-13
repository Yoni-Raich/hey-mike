package dev.androidagent.workspace

import android.content.Context
import java.io.File

/**
 * Seeds the agent's guidance into each session workspace and Codex's user
 * skill root.
 *
 * The workspace gets exactly two files:
 * - `AGENTS.md` — the one harness entrypoint, rewritten from the bundled
 *   asset on every seed so an app update always reaches existing chats.
 * - `preferences.json` — durable user preferences, created once and preserved.
 *
 * Everything detailed (tool mechanics, app cards, recovery, preferences) lives
 * in the app-managed skills under `$HOME/.agents/skills`, loaded on demand.
 *
 * The bundled asset is the only source. An embedded copy used to be written
 * after it, and silently replaced the current `AGENTS.md` with a Wireless-ADB
 * era version on every seed.
 */
object WorkspaceSeeder {

    private const val ASSET_PREFIX = "agent_stack"
    private const val AGENTS_MD = "AGENTS.md"
    private const val PREFERENCES_JSON = "preferences.json"

    private val DEFAULT_SKILL_NAMES = listOf(
        "device-automation",
        "recovery-and-safety",
        "user-preferences",
        "app-cards",
    )

    /** Files older releases seeded into the workspace. Their content now lives in skills. */
    private val LEGACY_WORKSPACE_FILES = listOf(
        "RECOVERY.md",
        "cards/whatsapp.md",
        "cards/chrome.md",
        "cards/maps.md",
        "cards/settings.md",
        "cards/youtube.md",
    )

    fun seed(workspace: File, context: Context? = null) {
        val readAsset: ((String) -> ByteArray)? = context?.let { ctx ->
            { relativePath -> ctx.assets.open("$ASSET_PREFIX/$relativePath").use { it.readBytes() } }
        }
        seedFrom(workspace, readAsset)
    }

    /**
     * Seeds [workspace] from [readAsset], a reader over `agent_stack/`. Without
     * one only the preferences file is guaranteed.
     */
    internal fun seedFrom(workspace: File, readAsset: ((String) -> ByteArray)?) {
        workspace.mkdirs()

        readAsset?.let { read ->
            runCatching { read(AGENTS_MD) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { File(workspace, AGENTS_MD).writeBytes(it) }
        }

        removeLegacyWorkspaceFiles(workspace)
        removeLegacyWorkspaceSkillCopies(workspace)

        val prefsFile = File(workspace, PREFERENCES_JSON)
        if (!prefsFile.exists() || prefsFile.length() == 0L) {
            val bundled = readAsset?.let { read -> runCatching { read(PREFERENCES_JSON) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
            if (bundled != null) prefsFile.writeBytes(bundled)
            else prefsFile.writeText(DEFAULT_PREFERENCES, Charsets.UTF_8)
        }
    }

    /** Install the app-managed defaults in Codex's standard user-skill root. */
    fun installDefaultSkills(homeDir: File, context: Context) {
        installDefaultSkills(homeDir) { relativePath ->
            context.assets.open("$ASSET_PREFIX/skills/$relativePath").use { it.readBytes() }
        }
    }

    internal fun installDefaultSkills(homeDir: File, readAsset: (String) -> ByteArray) {
        val skillsDir = File(homeDir, ".agents/skills").apply { mkdirs() }
        for (name in DEFAULT_SKILL_NAMES) {
            val bytes = readAsset("$name/SKILL.md")
            require(bytes.isNotEmpty()) { "Bundled skill $name is empty" }
            val staging = File(skillsDir, ".$name.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            File(staging, "SKILL.md").writeBytes(bytes)

            val target = File(skillsDir, name)
            target.deleteRecursively()
            require(staging.renameTo(target)) { "Could not install bundled skill $name" }
        }

        // Remove only paths created by older releases. Keep all
        // unrelated user and repository skills untouched.
        removeManagedSkills(File(homeDir, ".codex/skills"))
    }

    private fun removeLegacyWorkspaceFiles(workspace: File) {
        for (path in LEGACY_WORKSPACE_FILES) File(workspace, path).delete()
        // Only when nothing the user or agent added is left inside.
        File(workspace, "cards").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }

    private fun removeLegacyWorkspaceSkillCopies(workspace: File) {
        removeManagedSkills(File(workspace, ".agents/skills"))
        removeManagedSkills(File(workspace, ".codex/skills"))
        removeManagedSkills(File(workspace, "skills"))
    }

    private fun removeManagedSkills(root: File) {
        for (name in DEFAULT_SKILL_NAMES) File(root, name).deleteRecursively()
    }

    const val DEFAULT_PREFERENCES = """{
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
