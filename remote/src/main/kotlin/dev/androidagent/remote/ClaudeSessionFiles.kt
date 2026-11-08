package dev.androidagent.remote

import dev.androidagent.core.EngineKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * A failure the reader on the computer reported itself, as opposed to the
 * computer not answering. [code] says which kind, where it matters to a caller.
 */
class ClaudeReaderException(message: String, val code: String?) : IllegalStateException(message) {
    /** No transcript carries this ID: a chat whose first turn never reached Claude Code has none yet. */
    val missing: Boolean get() = code == "not_found"
    /** The session is there, but its text could not be projected. Claude itself can still continue it. */
    val history: Boolean get() = code == "history"
}

/** Read-only projections from the computer's native Claude Code transcript store. */
object ClaudeSessionFiles {
    private val idPattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun requireId(id: String) { require(idPattern.matches(id)) { "Invalid Claude session ID" } }

    // The version is part of the name: two phones on different app versions
    // share this folder and must not run each other's reader.
    fun scriptName(os: HostOs): String = "session-reader-v6." + if (os == HostOs.WINDOWS) "ps1" else "py"

    /** Stable across engine/app restarts, distinct for each phone's saved computer. */
    fun clientId(computerId: String): String = UUID.nameUUIDFromBytes("claude-client:$computerId".toByteArray(Charsets.UTF_8)).toString()

    fun script(os: HostOs): String {
        val suffix = if (os == HostOs.WINDOWS) "ps1" else "py"
        return checkNotNull(javaClass.getResourceAsStream("/dev/androidagent/remote/claude_sessions.$suffix")) { "Claude session reader is missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** The script that appends a chat's earlier turns to a transcript (`claude_transcript.*`), by the same versioned naming. */
    fun writerName(os: HostOs): String = "transcript-writer-v1." + if (os == HostOs.WINDOWS) "ps1" else "py"

    fun writer(os: HostOs): String {
        val suffix = if (os == HostOs.WINDOWS) "ps1" else "py"
        return checkNotNull(javaClass.getResourceAsStream("/dev/androidagent/remote/claude_transcript.$suffix")) { "Claude transcript writer is missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** What the writer reads: the folder `claude` runs in, the session and the JSON lines to add. */
    fun writerPayload(cwd: String, sessionId: String, records: List<String>): String {
        requireId(sessionId)
        return buildJsonObject {
            put("cwd", cwd)
            put("sessionId", sessionId)
            put("records", buildJsonArray { records.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    fun writerCommand(os: HostOs, script: String, payload: String): String =
        if (os == HostOs.WINDOWS) WindowsHost.powershell("& ${quote(os, script)} -Payload ${quote(os, payload)}")
        else LinuxHost.wrap("python3 ${quote(os, script)} ${quote(os, payload)}")

    fun command(os: HostOs, path: String, mode: String, id: String? = null, clientId: String? = null): String {
        require(mode in setOf("list", "read", "busy"))
        id?.let(::requireId)
        clientId?.let(::requireId)
        fun quote(value: String): String = quote(os, value)
        return if (os == HostOs.WINDOWS) {
            WindowsHost.powershell("& ${quote(path)} -Mode ${quote(mode)}" + (id?.let { " -SessionId ${quote(it)}" } ?: "") +
                (clientId?.let { " -ClientId ${quote(it)}" } ?: ""))
        } else LinuxHost.wrap("python3 ${quote(path)} ${quote(mode)}" + (id?.let { " ${quote(it)}" } ?: "") +
            (clientId?.let { " --client ${quote(it)}" } ?: ""))
    }

    /** One argument quoted for the computer's shell. */
    private fun quote(os: HostOs, value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid session reader path" }
        return "'" + value.replace("'", if (os == HostOs.WINDOWS) "''" else "'\\''") + "'"
    }

    fun payload(result: ExecResult): JsonObject = WindowsHost.payload(result).also { row ->
        row.text("error")?.let { throw ClaudeReaderException(it, row.text("code")) }
    }

    /** Another Claude client holds the session open. */
    fun heldByOther(row: JsonObject): Boolean =
        (row["busy"] as? JsonPrimitive)?.booleanOrNull ?: error("Claude writer status is unavailable")

    /** Only a `claude` that some Mike launched holds it: this phone's warm one, or another phone's. */
    fun heldByMike(row: JsonObject): Boolean = (row["mike"] as? JsonPrimitive)?.booleanOrNull == true

    /** A retained process from this saved computer can be reclaimed after this app lost its in-memory engine. */
    fun heldElsewhere(row: JsonObject, holdsSession: Boolean): Boolean = heldByOther(row) ||
        (heldByMike(row) && (row["owned"] as? JsonPrimitive)?.booleanOrNull != true && !holdsSession)

    fun threads(row: JsonObject): List<ComputerConversation> =
        (row["threads"] as? JsonArray ?: error("Claude session inventory is incomplete")).map { thread(it as? JsonObject ?: error("Invalid Claude session")) }

    fun thread(row: JsonObject): ComputerConversation {
        val id = row.text("id") ?: error("Claude session ID is missing")
        requireId(id)
        val cwd = row.text("cwd")?.takeIf { it.isNotBlank() } ?: error("Claude working folder is missing")
        return ComputerConversation(id, row.text("title").orEmpty().ifBlank { "Claude conversation" }, cwd,
            (row["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, row.text("name"), EngineKind.CLAUDE,
            (row["busy"] as? JsonPrimitive)?.booleanOrNull == true, row.text("model"))
    }

    fun messages(row: JsonObject): List<ComputerMessage> =
        (row["messages"] as? JsonArray ?: error("Claude history is incomplete")).map { value ->
            val message = value as? JsonObject ?: error("Invalid Claude message")
            val id = message.text("id") ?: error("Claude message ID is missing")
            requireId(id)
            val role = message.text("role")
            require(role in setOf("user", "assistant")) { "Invalid Claude message role" }
            ComputerMessage(id, role!!, message.text("text") ?: error("Claude message text is missing"),
                (message["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
