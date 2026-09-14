package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Locale

/**
 * Where workflow definitions live, and how a name in a request becomes a file.
 *
 * One JSON file per workflow under `<homeDirectory>/workflows/definitions/`,
 * beside [WorkflowStore]'s per-package step lists rather than inside them: a
 * definition is a document a person can read, diff and hand to someone else,
 * and burying several of them in one package file makes all three awkward.
 *
 * Nothing is shipped here. A definition names the ids and labels of one
 * phone's screens — Settings search on a Nothing phone and on a Xiaomi share
 * no field id — so the agent learns each sequence on the phone it runs on and
 * writes it here, and every definition in this directory is one that phone
 * produced.
 */
class WorkflowLibrary(private val root: File) {

    /** A file that is present but unusable, so a listing can say so instead of hiding it. */
    data class Broken(val file: String, val reason: String)

    fun definitionsDirectory(): File = root

    /**
     * Find a workflow by id, or by a name close enough to be unambiguous.
     *
     * The model names a workflow from a skill or from a listing, and "wireless
     * debugging" for `wireless-debugging` is the normal near miss. A name that
     * matches several is refused rather than guessed: running the wrong
     * workflow drives the phone through somebody else's steps.
     */
    fun find(name: String, packageName: String? = null): Lookup {
        val wanted = normalize(name)
        if (wanted.isEmpty()) return Lookup.NotFound(name, emptyList())
        val all = all()
        val scoped = if (packageName.isNullOrBlank()) all else {
            all.filter { it.packageName.equals(packageName.trim(), ignoreCase = true) }.ifEmpty { all }
        }
        scoped.firstOrNull { normalize(it.id) == wanted }?.let { return Lookup.Found(it) }
        val near = scoped.filter { normalize(it.id).contains(wanted) || wanted.contains(normalize(it.id)) }
        return when (near.size) {
            1 -> Lookup.Found(near.first())
            0 -> Lookup.NotFound(name, all.map { it.id })
            else -> Lookup.Ambiguous(name, near.map { it.id })
        }
    }

    /** Every definition that parses, newest file first. Broken files are reported by [broken]. */
    fun all(): List<WorkflowDefinition> =
        files().mapNotNull { file -> read(file).getOrNull() }

    fun forPackage(packageName: String): List<WorkflowDefinition> =
        all().filter { it.packageName.equals(packageName.trim(), ignoreCase = true) }

    /** Files that exist but do not parse, so a listing can name them instead of pretending they are absent. */
    fun broken(): List<Broken> = files().mapNotNull { file ->
        read(file).exceptionOrNull()?.let { Broken(file.name, it.message ?: "unreadable") }
    }

    /**
     * Write a definition, replacing any file with the same id.
     *
     * Written to a temporary file and renamed, so a run that reads the
     * directory while a save is in flight never sees half a workflow.
     */
    fun save(definition: WorkflowDefinition): File {
        require(WorkflowDefinition.ID_RE.matches(definition.id)) { "\"${definition.id}\" is not a usable workflow id" }
        root.mkdirs()
        val file = File(root, definition.id + SUFFIX)
        val temp = File(root, "." + definition.id + SUFFIX + ".tmp")
        temp.writeText(definition.toJson().toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(definition.toJson().toString(), Charsets.UTF_8)
            temp.delete()
        }
        return file
    }

    fun delete(id: String): Boolean = File(root, id.lowercase(Locale.ROOT) + SUFFIX).delete()

    private fun files(): List<File> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun read(file: File): Result<WorkflowDefinition> = runCatching {
        if (file.length() > MAX_FILE_BYTES) {
            throw WorkflowFormatException("workflow_invalid", "${file.name} is larger than $MAX_FILE_BYTES bytes.")
        }
        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw WorkflowFormatException("workflow_invalid", "${file.name} is not a JSON object.")
        WorkflowDefinition.parse(json, source = file.name)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    sealed interface Lookup {
        data class Found(val definition: WorkflowDefinition) : Lookup
        data class NotFound(val name: String, val known: List<String>) : Lookup
        data class Ambiguous(val name: String, val candidates: List<String>) : Lookup
    }

    companion object {
        const val DIRECTORY = "definitions"
        private const val SUFFIX = ".json"
        private const val MAX_FILE_BYTES = 256L * 1024L

        /** Under the existing workflows directory, so saved sequences and definitions age out together. */
        fun directoryIn(homeDirectory: File): File =
            File(WorkflowStore.directoryIn(homeDirectory), DIRECTORY)
    }
}
