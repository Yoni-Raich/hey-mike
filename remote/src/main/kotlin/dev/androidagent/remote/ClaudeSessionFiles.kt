package dev.androidagent.remote

import dev.androidagent.core.EngineKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Read-only projections from the computer's native Claude Code transcript store. */
object ClaudeSessionFiles {
    private val idPattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun requireId(id: String) { require(idPattern.matches(id)) { "Invalid Claude session ID" } }

    fun scriptName(os: HostOs): String = "session-reader-v1." + if (os == HostOs.WINDOWS) "ps1" else "py"

    fun script(os: HostOs): String {
        val suffix = if (os == HostOs.WINDOWS) "ps1" else "py"
        return checkNotNull(javaClass.getResourceAsStream("/dev/androidagent/remote/claude_sessions.$suffix")) { "Claude session reader is missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun command(os: HostOs, path: String, mode: String, id: String? = null): String {
        require(mode in setOf("list", "read", "busy"))
        id?.let(::requireId)
        fun quote(value: String): String {
            require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid session reader path" }
            return "'" + value.replace("'", if (os == HostOs.WINDOWS) "''" else "'\\''") + "'"
        }
        return if (os == HostOs.WINDOWS) {
            WindowsHost.powershell("& ${quote(path)} -Mode ${quote(mode)}" + (id?.let { " -SessionId ${quote(it)}" } ?: ""))
        } else LinuxHost.wrap("python3 ${quote(path)} ${quote(mode)}" + (id?.let { " ${quote(it)}" } ?: ""))
    }

    fun payload(result: ExecResult): JsonObject = WindowsHost.payload(result).also { row ->
        row.text("error")?.let { throw IllegalStateException(it) }
    }

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
