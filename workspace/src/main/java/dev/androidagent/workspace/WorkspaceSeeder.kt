package dev.androidagent.workspace

import android.content.Context
import java.io.File

/**
 * Seeds the agent's guidance: `AGENTS.md` into each session workspace, the
 * app-managed skills into Codex's user skill root, and the user's preferences
 * into the global home directory.
 *
 * - `AGENTS.md` — the one harness entrypoint, rewritten from the bundled asset
 *   on every seed so an app update always reaches existing chats.
 * - `$HOME/preferences.json` — the user's defaults, shared by every chat and
 *   created once. It used to live in each chat's workspace, so a preference
 *   saved in one chat was gone in the next.
 * - skills under `$HOME/.agents/skills` — everything detailed, loaded on demand.
 *
 * The bundled asset is the only source of `AGENTS.md`. An embedded copy used to
 * be written after it, and silently replaced the current `AGENTS.md` with a
 * Wireless-ADB era version on every seed.
 */
object WorkspaceSeeder {

    private const val ASSET_PREFIX = "agent_stack"
    private const val AGENTS_MD = "AGENTS.md"
    private const val PREFERENCES_JSON = "preferences.json"

    /** Replaced with the absolute preferences path when a skill is installed. */
    internal const val PREFERENCES_PATH_PLACEHOLDER = "{{PREFERENCES_PATH}}"

    private val DEFAULT_SKILL_NAMES = listOf(
        "device-automation",
        "user-preferences",
        "app-cards",
    )

    /** Skills older releases installed whose content now lives in another skill. */
    private val RETIRED_SKILL_NAMES = listOf("recovery-and-safety")

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
        seedFrom(workspace, context?.let(::assetReader))
    }

    /** Seeds [workspace] from [readAsset], a reader over `agent_stack/`. */
    internal fun seedFrom(workspace: File, readAsset: ((String) -> ByteArray)?) {
        workspace.mkdirs()

        readAsset?.let { read ->
            runCatching { read(AGENTS_MD) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { File(workspace, AGENTS_MD).writeBytes(it) }
        }

        removeLegacyWorkspaceFiles(workspace, readAsset)
        removeLegacyWorkspaceSkillCopies(workspace)
    }

    /** The one preferences file every chat reads and writes. */
    fun preferencesFile(homeDir: File): File = File(homeDir, PREFERENCES_JSON)

    fun ensureGlobalPreferences(homeDir: File, sessionsDir: File, context: Context) {
        ensureGlobalPreferences(homeDir, sessionsDir, assetReader(context))
    }

    /**
     * Creates `$HOME/preferences.json` once.
     *
     * A user who taught an older release a preference has it in some chat's
     * workspace copy, so the most recently changed copy that differs from the
     * defaults seeds the global file. Nothing is ever overwritten.
     */
    internal fun ensureGlobalPreferences(homeDir: File, sessionsDir: File, readAsset: ((String) -> ByteArray)?) {
        val global = preferencesFile(homeDir)
        if (global.isFile && global.length() > 0L) return
        homeDir.mkdirs()

        val defaults = defaultPreferences(readAsset)
        val customized = sessionsDir.listFiles().orEmpty()
            .map { File(it, "workspace/$PREFERENCES_JSON") }
            .filter { it.isFile && it.length() > 0L && !isDefaultPreferences(it.readBytes(), readAsset) }
            .maxByOrNull { it.lastModified() }

        global.writeBytes(customized?.readBytes() ?: defaults)
    }

    /** Install the app-managed defaults in Codex's standard user-skill root. */
    fun installDefaultSkills(homeDir: File, context: Context) {
        installDefaultSkills(homeDir) { relativePath ->
            context.assets.open("$ASSET_PREFIX/skills/$relativePath").use { it.readBytes() }
        }
    }

    internal fun installDefaultSkills(homeDir: File, readAsset: (String) -> ByteArray) {
        val skillsDir = File(homeDir, ".agents/skills").apply { mkdirs() }
        val preferencesPath = preferencesFile(homeDir).absolutePath
        for (name in DEFAULT_SKILL_NAMES) {
            val bytes = readAsset("$name/SKILL.md")
            require(bytes.isNotEmpty()) { "Bundled skill $name is empty" }
            val text = bytes.toString(Charsets.UTF_8).replace(PREFERENCES_PATH_PLACEHOLDER, preferencesPath)
            val staging = File(skillsDir, ".$name.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            File(staging, "SKILL.md").writeText(text, Charsets.UTF_8)

            val target = File(skillsDir, name)
            target.deleteRecursively()
            require(staging.renameTo(target)) { "Could not install bundled skill $name" }
        }
        for (name in RETIRED_SKILL_NAMES) File(skillsDir, name).deleteRecursively()

        // Remove only paths created by older releases. Keep all
        // unrelated user and repository skills untouched.
        removeManagedSkills(File(homeDir, ".codex/skills"))
    }

    private fun assetReader(context: Context): (String) -> ByteArray = { relativePath ->
        context.assets.open("$ASSET_PREFIX/$relativePath").use { it.readBytes() }
    }

    private fun defaultPreferences(readAsset: ((String) -> ByteArray)?): ByteArray =
        readAsset?.let { read -> runCatching { read(PREFERENCES_JSON) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PREFERENCES.toByteArray(Charsets.UTF_8)

    private fun isDefaultPreferences(bytes: ByteArray, readAsset: ((String) -> ByteArray)?): Boolean {
        val content = bytes.toString(Charsets.UTF_8).withoutWhitespace()
        return content == DEFAULT_PREFERENCES.withoutWhitespace() ||
            content == LEGACY_DEFAULT_PREFERENCES.withoutWhitespace() ||
            content == defaultPreferences(readAsset).toString(Charsets.UTF_8).withoutWhitespace()
    }

    private fun String.withoutWhitespace(): String = filterNot { it.isWhitespace() }

    private fun removeLegacyWorkspaceFiles(workspace: File, readAsset: ((String) -> ByteArray)?) {
        for (path in LEGACY_WORKSPACE_FILES) File(workspace, path).delete()
        // Only when nothing the user or agent added is left inside.
        File(workspace, "cards").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        // An untouched per-chat copy is noise now that preferences are global.
        // A customized one is left alone: it may be what seeded the global file.
        File(workspace, PREFERENCES_JSON)
            .takeIf { it.isFile && isDefaultPreferences(it.readBytes(), readAsset) }
            ?.delete()
    }

    private fun removeLegacyWorkspaceSkillCopies(workspace: File) {
        removeManagedSkills(File(workspace, ".agents/skills"))
        removeManagedSkills(File(workspace, ".codex/skills"))
        removeManagedSkills(File(workspace, "skills"))
    }

    private fun removeManagedSkills(root: File) {
        for (name in DEFAULT_SKILL_NAMES + RETIRED_SKILL_NAMES) File(root, name).deleteRecursively()
    }

    const val DEFAULT_PREFERENCES = """{
  "apps": {
    "messaging": "WhatsApp",
    "browser": "Chrome",
    "maps": "Google Maps",
    "music": "YouTube"
  },
  "addresses": {},
  "contacts": {}
}"""

    /** What older releases seeded into every chat, so an untouched copy is not mistaken for a choice. */
    private const val LEGACY_DEFAULT_PREFERENCES = """{
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
