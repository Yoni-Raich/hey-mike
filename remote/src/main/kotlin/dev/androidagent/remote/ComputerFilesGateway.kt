package dev.androidagent.remote

import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The phone's device tools, as a chat on a computer uses them.
 *
 * Those tools read and write the chat's workspace on the phone, but a
 * computer chat's files are on the computer. So in such a chat a file the
 * phone should receive (`push_file`, `install_apk`) is named by its path on
 * the computer and copied to the phone over the chat's SSH link first, and a
 * file taken from the phone (`pull_file`) is copied on into the project
 * folder. Every other tool, and every phone chat, goes straight through.
 */
class ComputerFilesGateway(
    private val inner: DeviceToolGateway,
    private val hub: RemoteHub,
) : DeviceToolGateway by inner {

    @Volatile private var workspace: File? = null

    override fun beginRun(runId: String, workspace: File) {
        this.workspace = workspace
        inner.beginRun(runId, workspace)
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        val ws = workspace ?: return inner.invoke(name, arguments)
        val binding = RoutingAgentEngine.sessionIdOf(ws)?.let(hub.store::binding) ?: return inner.invoke(name, arguments)
        val asked = (arguments["localName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return inner.invoke(name, arguments)
        return when (name) {
            "push_file", "install_apk" -> {
                val onComputer = onComputer(binding.cwd, asked)
                val local = File(ws, "$FROM_COMPUTER/${fileName(onComputer)}")
                try {
                    hub.download(binding.computerId, onComputer, local, MAX_COPY_BYTES)
                } catch (error: Exception) {
                    return ToolResult("Could not copy $onComputer from the computer to the phone: ${error.message}", success = false)
                }
                inner.invoke(name, JsonObject(arguments + ("localName" to JsonPrimitive("$FROM_COMPUTER/${local.name}"))))
            }
            "pull_file" -> {
                val result = inner.invoke(name, arguments)
                if (!result.success) return result
                val onComputer = onComputer(binding.cwd, asked)
                runCatching { hub.upload(binding.computerId, File(ws, asked), onComputer) }.fold(
                    { result.copy(text = "${result.text}\nSaved on the computer as $onComputer") },
                    { result.copy(text = "${result.text}\nThe file stayed on the phone; copying it to the computer failed: ${it.message}") },
                )
            }
            else -> inner.invoke(name, arguments)
        }
    }

    companion object {
        private const val FROM_COMPUTER = "from-computer"
        private const val MAX_COPY_BYTES = 512L * 1024 * 1024

        /** A Windows path as given, or one relative to the chat's project folder. */
        internal fun onComputer(cwd: String, path: String): String {
            val absolute = path.matches(Regex("^[A-Za-z]:[\\\\/].*")) || path.startsWith("\\\\")
            return if (absolute) path else cwd.trimEnd('\\', '/') + "\\" + path.replace('/', '\\').trimStart('\\')
        }

        internal fun fileName(path: String): String =
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { "file" }
    }
}
