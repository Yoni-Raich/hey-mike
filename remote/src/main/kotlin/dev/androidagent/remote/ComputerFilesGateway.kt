package dev.androidagent.remote

import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * phone should receive (`push_file`) is named by its path on
 * the computer and copied to the phone over the chat's SSH link first, and a
 * file taken from the phone (`pull_file`) is copied on into the project
 * folder. `install_apk` is not touched: it installs an APK already on the
 * phone, so a retry never copies again (the `computers` tool's
 * `copy_to_phone` stages one). Every other tool, and every phone chat, goes
 * straight through.
 */
class ComputerFilesGateway(
    private val inner: DeviceToolGateway,
    private val hub: RemoteHub,
    /**
     * Save a file into the phone's shared storage (Downloads, Pictures...)
     * as the app itself, at the place [remotePath] names, and return its
     * `content://media/...` address. No ADB is involved.
     */
    private val saveToPhone: suspend (file: File, remotePath: String) -> String,
) : DeviceToolGateway by inner {

    @Volatile private var workspace: File? = null
    @Volatile private var activeRunId: String? = null

    /** In a computer chat push_file needs no ADB, so it is ready whether or not ADB is. */
    override fun readyTools(): Set<String> {
        val ready = inner.readyTools()
        val ws = workspace ?: return ready
        val computerChat = RoutingAgentEngine.sessionIdOf(ws)?.let(hub.store::binding) != null
        return if (computerChat) ready + "push_file" else ready
    }

    override fun beginRun(runId: String, workspace: File) {
        this.workspace = workspace
        activeRunId = runId
        inner.beginRun(runId, workspace)
    }

    override fun revoke() {
        activeRunId = null
        inner.revoke()
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        val runId = activeRunId ?: return ToolResult("This run has stopped.", success = false)
        val ws = workspace ?: return inner.invoke(name, arguments)
        val binding = RoutingAgentEngine.sessionIdOf(ws)?.let(hub.store::binding) ?: return inner.invoke(name, arguments)
        val asked = (arguments["localName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return inner.invoke(name, arguments)
        return when (name) {
            "push_file" -> {
                val onComputer = onComputer(binding.cwd, asked)
                val local = File(ws, "$FROM_COMPUTER/${fileName(onComputer)}")
                try {
                    hub.download(binding.computerId, onComputer, local, Long.MAX_VALUE)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return ToolResult("Could not copy $onComputer from the computer to the phone: ${error.message}", success = false)
                }
                val remotePath = (arguments["remotePath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (activeRunId != runId) return ToolResult("Stopped before saving the file on the phone.", success = false)
                // The app writes it into shared storage itself: nothing here needs ADB.
                val uri = try {
                    saveToPhone(local, remotePath)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return ToolResult("Copied $onComputer from the computer, but could not save it on the phone: ${error.message}", success = false)
                }
                ToolResult(
                    "Copied $onComputer from the computer to the phone ($uri). To send it, call " +
                        "files_media with operation share and this URI, then complete the phone's share flow.",
                )
            }
            "pull_file" -> {
                val result = inner.invoke(name, arguments)
                if (!result.success) return result
                currentCoroutineContext().ensureActive()
                if (activeRunId != runId) return ToolResult("Stopped before copying the file to the computer.", success = false)
                val onComputer = onComputer(binding.cwd, asked)
                runCatching { hub.upload(binding.computerId, File(ws, asked), onComputer) }.fold(
                    { result.copy(text = "${result.text}\nSaved on the computer as $onComputer") },
                    {
                        if (it is CancellationException) throw it
                        result.copy(text = "${result.text}\nThe file stayed on the phone; copying it to the computer failed: ${it.message}", success = false)
                    },
                )
            }
            else -> inner.invoke(name, arguments)
        }
    }

    companion object {
        private const val FROM_COMPUTER = "from-computer"

        /** An absolute path as given, or one relative to the chat's project folder, in the computer's own style. */
        internal fun onComputer(cwd: String, path: String): String {
            val absolute = path.matches(Regex("^[A-Za-z]:[\\\\/].*")) || path.startsWith("\\\\") || path.startsWith("/")
            if (absolute) return path
            if (cwd.startsWith("/")) return cwd.trimEnd('/') + "/" + path.trimStart('/')
            return cwd.trimEnd('\\', '/') + "\\" + path.replace('/', '\\').trimStart('\\')
        }

        internal fun fileName(path: String): String =
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { "file" }
    }
}
