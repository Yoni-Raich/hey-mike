package dev.androidagent.remote

import dev.androidagent.core.ChatMessage
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.SessionStore
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolNotServiceable
import dev.androidagent.core.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** What the `computers` tool asks the app's screen to show. */
sealed interface ComputerUiRequest {
    /** The add-computer form, filled in. The user types the password there. */
    data class AddComputer(val host: String, val vpnHost: String, val user: String, val label: String) : ComputerUiRequest

    /** Switch to [sessionId] with [draft] in the composer, not sent. */
    data class OpenChat(val sessionId: String, val draft: String) : ComputerUiRequest
}

/**
 * The user's computers as one tool, `computers`, for any chat.
 *
 * A tool and not a skill because it crosses the line the sealed store draws:
 * the agent's shell has no SSH and must never read a password or rewrite
 * which chat runs where. So every mode is carried out by the app, and two
 * of them only prepare something the user finishes by hand:
 *
 * - `add` fills in the app's own add-computer form. The password is typed
 *   there and never reaches the model; the form says Mike suggested the
 *   address, because an address from a web page is how a password would be
 *   sent to someone else.
 * - `open_chat` opens a chat in a project with the task written in the
 *   composer, unsent. A computer chat may have full access to the PC, so an
 *   instruction the model picked up somewhere cannot travel there on its own.
 */
class ComputerToolGateway(
    private val hub: RemoteHub,
    private val sessions: SessionStore,
    /** Read by the app's screen, which clears it once shown. */
    private val requests: MutableStateFlow<ComputerUiRequest?>,
    private val bringToForeground: () -> Unit,
) : DeviceToolGateway {

    @Volatile private var revoked = true
    private val store get() = hub.store

    override val definitions: List<ToolDefinition> = listOf(DEFINITION)

    override fun beginRun(runId: String, workspace: File) { revoked = false }
    override fun revoke() { revoked = true }
    override fun needsControl(name: String): Boolean = false
    override fun deviceBackendLive(): Boolean = false
    override suspend fun cancel() = Unit

    override fun statusLine(): String {
        val computers = store.state.value.computers
        if (computers.isEmpty()) return "Computers: none"
        val setup = hub.setup.value
        return "Computers: " + computers.joinToString(", ") { c ->
            c.label + if (setup[c.id] is RemoteSetup.Ready) " (connected)" else ""
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. Nothing was done.")
        if (name != NAME) throw ToolNotServiceable("computers_unsupported", "The computers tool does not implement \"$name\".")
        return try {
            when (val mode = arguments.text("mode") ?: "status") {
                "status" -> status()
                "browse" -> browse(arguments)
                "new_project" -> newProject(arguments)
                "open_chat" -> openChat(arguments)
                "add" -> add(arguments)
                else -> refusal("unknown_mode", "\"$mode\" is not a mode. Use ${MODES.joinToString(", ")}.")
            }
        } catch (refused: Refused) {
            refusal(refused.type, refused.message)
        }
    }

    private suspend fun status(): ToolResult {
        val state = store.state.value
        if (state.computers.isEmpty()) {
            return ok {
                put("computers", JsonArray(emptyList()))
                put("note", "No computer is added yet. Use mode add with its address and Windows user name; the user types the password in the app.")
            }
        }
        // A computer not tried in this app run is connected quietly first,
        // so the answer has its projects. Nothing is installed here.
        state.computers.forEach { c ->
            if (hub.setup.value[c.id] == null) hub.connectQuietly(c.id) else hub.refreshThreads(c.id)
        }
        return ok {
            put("computers", buildJsonArray {
                state.computers.forEach { c ->
                    val setup = hub.setup.value[c.id]
                    val threads = hub.threads.value[c.id].orEmpty()
                    add(buildJsonObject {
                        put("id", c.id)
                        put("name", c.label)
                        put("default", c.id == state.defaultComputerId)
                        put("status", describe(setup))
                        put("access", if (c.access == RemoteAccess.ASK) "asks before commands outside the project" else "full access")
                        put("projects", buildJsonArray { projects(c.id).forEach { add(it) } })
                        put("recentConversations", buildJsonArray {
                            threads.take(RECENT).forEach { t ->
                                add(buildJsonObject { put("title", t.title); put("folder", t.cwd) })
                            }
                        })
                    })
                }
            })
        }
    }

    private suspend fun browse(arguments: JsonObject): ToolResult {
        val computer = computer(arguments)
        val listing = runCatching { hub.listFolders(computer.id, arguments.text("path").orEmpty()) }
            .getOrElse { throw Refused("unreachable", "Could not list folders on ${computer.label}: ${it.message}") }
        return ok {
            put("computer", computer.label)
            put("path", listing.path)
            listing.parent?.let { put("parent", it) }
            put("gitRepository", listing.isGitRepo)
            put("folders", buildJsonArray { listing.folders.forEach { add(it) } })
        }
    }

    private suspend fun newProject(arguments: JsonObject): ToolResult {
        val computer = computer(arguments)
        val path = arguments.text("path") ?: throw Refused("missing_path", "Give the folder's path on the computer.")
        val create = (arguments["create"] as? JsonPrimitive)?.booleanOrNull == true
        val listing = runCatching { hub.listFolders(computer.id, path, create) }.getOrElse {
            throw Refused("no_folder", "No folder at $path on ${computer.label}: ${it.message}. Pass create: true to make it.")
        }
        store.addProject(computer.id, listing.path)
        return ok {
            put("computer", computer.label)
            put("project", listing.path)
            put("note", "It is in the side panel under ${computer.label}. Use open_chat to start working in it.")
        }
    }

    private suspend fun openChat(arguments: JsonObject): ToolResult {
        val computer = computer(arguments)
        val asked = arguments.text("project") ?: throw Refused("missing_project", "Name the project, or give its folder's path.")
        val message = arguments.text("message") ?: throw Refused("missing_message", "Write the task, with what the chat so far decided, as message.")
        val path = projects(computer.id).firstOrNull { folderName(it).equals(asked, ignoreCase = true) || samePath(it, asked) }
            ?: if (asked.matches(ABSOLUTE)) asked
            else throw Refused("unknown_project", "No project \"$asked\" on ${computer.label}. Known: ${projects(computer.id).joinToString(", ") { folderName(it) }}.")
        // The chat needs Codex there; without it, the side panel sets it up.
        val setup = hub.setup.value[computer.id].takeIf { it is RemoteSetup.Ready } ?: hub.setUp(computer.id, install = false)
        if (setup !is RemoteSetup.Ready) {
            throw Refused("not_ready", "${computer.label} is not ready: ${describe(setup)}. Ask the user to open it from the side panel.")
        }
        val session = sessions.createSession()
        store.bind(session.id, RemoteBinding(computer.id, path))
        store.addProject(computer.id, path)
        sessions.rename(session.id, folderName(path))
        sessions.append(
            ChatMessage(
                UUID.randomUUID().toString(), session.id, "note",
                "This chat runs on ${computer.label}, in $path. Mike works there with Codex (shell, files, git, skills) and can still use this phone.",
                System.currentTimeMillis(),
            ),
        )
        requests.value = ComputerUiRequest.OpenChat(session.id, message)
        bringToForeground()
        return ok {
            put("computer", computer.label)
            put("project", path)
            put("chat", folderName(path))
            put("note", "The app switched to the new chat with your message in the composer, not sent. The user sends it. Finish this turn with one short line.")
        }
    }

    private fun add(arguments: JsonObject): ToolResult {
        val host = arguments.text("host").orEmpty()
        val vpnHost = arguments.text("vpnHost").orEmpty()
        if (host.isBlank() && vpnHost.isBlank()) throw Refused("missing_address", "Give the computer's home network address, its VPN address, or both.")
        val user = arguments.text("user").orEmpty()
        requests.value = ComputerUiRequest.AddComputer(host, vpnHost, user, arguments.text("name").orEmpty())
        bringToForeground()
        return ok {
            put("note", "The app's add-computer form is open with these details. The user checks the address and types the Windows password there; you never see it. OpenSSH Server must be on at the PC, which only the user can do.")
        }
    }

    private fun computer(arguments: JsonObject): RemoteComputer {
        val state = store.state.value
        if (state.computers.isEmpty()) throw Refused("no_computer", "No computer is added yet. Use mode add.")
        val asked = arguments.text("computer") ?: return state.defaultComputer ?: state.computers.first()
        return state.computers.firstOrNull { it.id == asked || it.label.equals(asked, ignoreCase = true) }
            ?: throw Refused("unknown_computer", "No computer \"$asked\". Known: ${state.computers.joinToString(", ") { it.label }}.")
    }

    /** Folders the user picked, chats here run in, or Codex on the computer worked in. */
    private fun projects(computerId: String): List<String> {
        val state = store.state.value
        val all = state.projects.filter { it.computerId == computerId }.map { it.path } +
            state.bindings.values.filter { it.computerId == computerId }.map { it.cwd } +
            hub.threads.value[computerId].orEmpty().map { it.cwd }
        return all.distinctBy(::pathKey)
    }

    private fun describe(setup: RemoteSetup?): String = when (setup) {
        is RemoteSetup.Ready -> "connected"
        is RemoteSetup.Working -> setup.step
        is RemoteSetup.NeedsSignIn -> "Codex on the computer needs a sign-in (shown in the app's side panel)"
        is RemoteSetup.Failed -> "not connected: ${setup.message}"
        null -> "not connected"
    }

    private class Refused(val type: String, override val message: String) : Exception(message)

    private fun ok(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ToolResult(buildJsonObject { put("ok", true); body() }.toString())

    private fun refusal(type: String, message: String) = ToolResult(
        buildJsonObject { put("ok", false); put("errorType", type); put("message", message) }.toString(),
        success = false,
    )

    companion object {
        const val NAME = "computers"
        private const val RECENT = 8
        private val MODES = listOf("status", "browse", "new_project", "open_chat", "add")
        private val ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

        internal fun pathKey(path: String) = path.replace('/', '\\').trimEnd('\\').lowercase()
        private fun samePath(a: String, b: String) = pathKey(a) == pathKey(b)
        internal fun folderName(path: String) =
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

        private const val DESCRIPTION =
            "The user's Windows computers, which Mike reaches over SSH with Codex running there. " +
                "status: each computer, whether it is connected, its projects and recent conversations. " +
                "browse: the folders at a path on a computer. " +
                "new_project: make a folder on a computer a project (create: true makes the folder). " +
                "open_chat: start a chat in a project to work there; message is the task plus what this chat decided, " +
                "and the user sends it. add: fill in the app's add-computer form; the user types the password there. " +
                "computer is a name or id; the default computer when left out."

        val DEFINITION = ToolDefinition(
            name = NAME,
            description = DESCRIPTION,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(MODES.map { JsonPrimitive(it) }))
                        put("description", "Defaults to status.")
                    })
                    put("computer", prop("string", "Computer name or id. Leave out for the default computer."))
                    put("path", prop("string", "browse, new_project: a Windows path such as C:\\Users\\me\\src. browse: blank is the home folder."))
                    put("create", prop("boolean", "new_project: make the folder if it does not exist."))
                    put("project", prop("string", "open_chat: a project's name or its folder's full path."))
                    put("message", prop("string", "open_chat: the task for the new chat, written for the user to send."))
                    put("host", prop("string", "add: the computer's home network address."))
                    put("vpnHost", prop("string", "add: its VPN address, such as Tailscale."))
                    put("user", prop("string", "add: the Windows user name."))
                    put("name", prop("string", "add: a name for the computer."))
                })
            },
        )
    }
}
