package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * Where rules live: one JSON file per rule under `<homeDirectory>/automations/`.
 *
 * Beside the workflow definitions and for the same reasons — a rule is a
 * document a person can read, diff, delete and hand to someone else, and it has
 * to survive [WorkspaceSeeder], which rewrites the session workspace on every
 * access and force-replaces the bundled skills on every start.
 *
 * A rule that exists is not a rule that runs. Nothing here schedules anything;
 * the host reads this directory, registers whatever the triggers need, and is
 * free to refuse. Keeping the store ignorant of that is what lets the same
 * files be listed, tested and explained on a phone where the notification
 * listener was never granted.
 */
class AutomationLibrary(private val root: File) {

    /** A file that is present but unusable, so a listing can say so instead of hiding it. */
    data class Broken(val file: String, val reason: String)

    fun directory(): File = root

    /**
     * Find a rule by id, or by a name close enough to be unambiguous.
     *
     * A near miss is resolved the way [WorkflowLibrary] resolves one, and an
     * ambiguous name is refused rather than guessed: enabling or deleting the
     * wrong standing rule is not a mistake the user finds out about quickly.
     */
    fun find(name: String): Lookup {
        val wanted = normalize(name)
        if (wanted.isEmpty()) return Lookup.NotFound(name, emptyList())
        val all = all()
        all.firstOrNull { normalize(it.id) == wanted }?.let { return Lookup.Found(it) }
        val near = all.filter { normalize(it.id).contains(wanted) || wanted.contains(normalize(it.id)) }
        return when (near.size) {
            1 -> Lookup.Found(near.first())
            0 -> Lookup.NotFound(name, all.map { it.id })
            else -> Lookup.Ambiguous(name, near.map { it.id })
        }
    }

    /** Every rule that parses, newest file first. Broken files are reported by [broken]. */
    fun all(): List<AutomationRule> = files().mapNotNull { read(it).getOrNull() }

    fun enabled(): List<AutomationRule> = all().filter { it.enabled }

    /** Rules a given trigger kind could wake, so a host registers only what is used. */
    fun watching(kind: AutomationTriggerKind): List<AutomationRule> =
        enabled().filter { it.trigger.kind == kind }

    fun broken(): List<Broken> = files().mapNotNull { file ->
        read(file).exceptionOrNull()?.let { Broken(file.name, it.message ?: "unreadable") }
    }

    /** Write a rule, replacing any file with the same id. Temp file and rename, as elsewhere. */
    fun save(rule: AutomationRule): File {
        require(AutomationRule.ID_RE.matches(rule.id)) { "\"${rule.id}\" is not a usable rule id" }
        require(all().size < MAX_RULES || all().any { it.id == rule.id }) {
            "This phone already holds $MAX_RULES rules; delete one before adding another."
        }
        root.mkdirs()
        val file = File(root, rule.id + SUFFIX)
        val temp = File(root, "." + rule.id + SUFFIX + ".tmp")
        temp.writeText(rule.toJson().toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(rule.toJson().toString(), Charsets.UTF_8)
            temp.delete()
        }
        return file
    }

    fun delete(id: String): Boolean = File(root, id.lowercase(Locale.ROOT) + SUFFIX).delete()

    /** Turn a rule on or off without rewriting it. Returns null when there is no such rule. */
    fun setEnabled(id: String, enabled: Boolean): AutomationRule? {
        val rule = (find(id) as? Lookup.Found)?.definition ?: return null
        if (rule.enabled == enabled) return rule
        val updated = rule.copy(enabled = enabled)
        save(updated)
        return updated
    }

    private fun files(): List<File> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.startsWith(".") && it.name != AutomationJournal.FILE_NAME }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun read(file: File): Result<AutomationRule> = runCatching {
        if (file.length() > MAX_FILE_BYTES) {
            throw AutomationFormatException("automation_invalid", "${file.name} is larger than $MAX_FILE_BYTES bytes.")
        }
        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw AutomationFormatException("automation_invalid", "${file.name} is not a JSON object.")
        AutomationRule.parse(json, source = file.name)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    sealed interface Lookup {
        data class Found(val definition: AutomationRule) : Lookup
        data class NotFound(val name: String, val known: List<String>) : Lookup
        data class Ambiguous(val name: String, val candidates: List<String>) : Lookup
    }

    companion object {
        const val DIRECTORY = "automations"
        const val MAX_RULES = 64
        private const val SUFFIX = ".json"
        private const val MAX_FILE_BYTES = 128L * 1024L

        fun directoryIn(homeDirectory: File): File = File(homeDirectory, DIRECTORY)
    }
}
