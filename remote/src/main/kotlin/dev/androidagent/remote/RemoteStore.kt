package dev.androidagent.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * How much Codex on a computer may do without asking.
 *
 * The user picks this per computer. [ASK] is Codex's own default preset:
 * writes inside the project folder run, anything else waits for an answer on
 * the phone. [FULL] is what the phone's own Codex does.
 */
enum class RemoteAccess(val sandbox: String, val approvalPolicy: String) {
    ASK("workspace-write", "on-request"),
    FULL("danger-full-access", "never"),
}

/** A computer the user added. The password is kept apart, in [RemoteStore]. */
data class RemoteComputer(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val access: RemoteAccess = RemoteAccess.ASK,
    /** The SSH host key trusted on first connect, base64 of the key blob. */
    val hostKey: String? = null,
    /** `SHA256:...` of [hostKey], as `ssh-keygen -l` prints it. */
    val fingerprint: String? = null,
    /** The folder a chat was last opened in, where the folder picker starts. */
    val lastFolder: String? = null,
) {
    val address: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"
}

/** One chat that runs on a computer instead of on the phone. */
data class RemoteBinding(
    val computerId: String,
    /** The working folder, as the computer spells it (`C:\Users\...`). */
    val cwd: String,
    /** The Codex thread on that computer, once there is one. */
    val threadId: String? = null,
)

data class RemoteState(
    val computers: List<RemoteComputer> = emptyList(),
    val bindings: Map<String, RemoteBinding> = emptyMap(),
    /**
     * The saved state could not be opened. It is sealed with a Keystore key,
     * so this means it was changed by something other than this app, or the
     * key is gone. Nothing from it is trusted.
     */
    val unreadable: Boolean = false,
)

/**
 * Seals and opens bytes with a key that never leaves this app.
 *
 * The phone agent's shell runs as this app's own Linux user and can rewrite
 * any file the app owns. A computer's host, its password and which chat runs
 * where are therefore stored only through this box: authenticated encryption
 * means an edited file does not open, instead of pointing a saved password at
 * another host.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Computers, their passwords and chat bindings, in one sealed file.
 *
 * One file keeps the three consistent: a binding can never outlive its
 * computer, and a password cannot be moved to another host by editing a
 * separate list.
 */
class RemoteStore(private val file: File, private val box: SecretBox) {

    private data class Stored(val state: RemoteState, val passwords: Map<String, String>)

    private var stored: Stored = load()
    private val mutableState = MutableStateFlow(stored.state)
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    @Synchronized fun computer(id: String): RemoteComputer? = stored.state.computers.firstOrNull { it.id == id }

    @Synchronized fun password(id: String): String? = stored.passwords[id]

    @Synchronized fun binding(sessionId: String): RemoteBinding? = stored.state.bindings[sessionId]

    /** The chat and binding that own [threadId], if a computer runs it. */
    @Synchronized fun bindingForThread(threadId: String): Pair<String, RemoteBinding>? =
        stored.state.bindings.entries.firstOrNull { it.value.threadId == threadId }?.toPair()

    /** Add a computer, or change one. A null [password] keeps the saved one. */
    @Synchronized fun save(computer: RemoteComputer, password: String?): RemoteComputer {
        val existing = computer(computer.id)
        // A changed address is a different machine: its old key proves nothing.
        val moved = existing != null && (existing.host != computer.host || existing.port != computer.port)
        val next = if (moved) computer.copy(hostKey = null, fingerprint = null) else computer
        val computers = stored.state.computers.filterNot { it.id == computer.id } + next
        val passwords = if (password == null) stored.passwords else stored.passwords + (computer.id to password)
        write(Stored(stored.state.copy(computers = computers), passwords))
        return next
    }

    @Synchronized fun remove(id: String) {
        write(
            Stored(
                stored.state.copy(
                    computers = stored.state.computers.filterNot { it.id == id },
                    bindings = stored.state.bindings.filterValues { it.computerId != id },
                ),
                stored.passwords - id,
            ),
        )
    }

    @Synchronized fun update(id: String, change: (RemoteComputer) -> RemoteComputer) {
        val current = computer(id) ?: return
        write(Stored(stored.state.copy(computers = stored.state.computers.map { if (it.id == id) change(current) else it }), stored.passwords))
    }

    @Synchronized fun bind(sessionId: String, binding: RemoteBinding) {
        require(computer(binding.computerId) != null) { "Unknown computer" }
        write(Stored(stored.state.copy(bindings = stored.state.bindings + (sessionId to binding)), stored.passwords))
    }

    @Synchronized fun unbind(sessionId: String) {
        if (sessionId !in stored.state.bindings) return
        write(Stored(stored.state.copy(bindings = stored.state.bindings - sessionId), stored.passwords))
    }

    private fun write(next: Stored) {
        val body = encode(next).toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(box.seal(body))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not save computers" }
        }
        stored = next
        mutableState.value = next.state
    }

    private fun load(): Stored {
        if (!file.isFile) return Stored(RemoteState(), emptyMap())
        return runCatching { decode(String(box.open(file.readBytes()), Charsets.UTF_8)) }
            .getOrElse { Stored(RemoteState(unreadable = true), emptyMap()) }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        private fun encode(stored: Stored): String = buildJsonObject {
            put("version", 1)
            put("computers", buildJsonArray {
                stored.state.computers.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("label", c.label); put("host", c.host); put("port", c.port)
                        put("user", c.user); put("access", c.access.name)
                        c.hostKey?.let { put("hostKey", it) }
                        c.fingerprint?.let { put("fingerprint", it) }
                        c.lastFolder?.let { put("lastFolder", it) }
                        stored.passwords[c.id]?.let { put("password", it) }
                    })
                }
            })
            put("bindings", buildJsonObject {
                stored.state.bindings.forEach { (session, b) ->
                    put(session, buildJsonObject {
                        put("computer", b.computerId); put("cwd", b.cwd)
                        b.threadId?.let { put("thread", it) }
                    })
                }
            })
        }.toString()

        private fun decode(text: String): Stored {
            val root = Json.parseToJsonElement(text).jsonObject
            val passwords = mutableMapOf<String, String>()
            val computers = (root["computers"] as? JsonArray).orEmpty().map { element ->
                val c = element.jsonObject
                val id = c.text("id")!!
                c.text("password")?.let { passwords[id] = it }
                RemoteComputer(
                    id = id,
                    label = c.text("label") ?: c.text("host")!!,
                    host = c.text("host")!!,
                    port = (c["port"] as? JsonPrimitive)?.intOrNull ?: 22,
                    user = c.text("user")!!,
                    access = runCatching { RemoteAccess.valueOf(c.text("access")!!) }.getOrDefault(RemoteAccess.ASK),
                    hostKey = c.text("hostKey"),
                    fingerprint = c.text("fingerprint"),
                    lastFolder = c.text("lastFolder"),
                )
            }
            val ids = computers.map { it.id }.toSet()
            val bindings = (root["bindings"] as? JsonObject).orEmpty().mapNotNull { (session, value) ->
                val b = value.jsonObject
                val computer = b.text("computer") ?: return@mapNotNull null
                if (computer !in ids) return@mapNotNull null
                session to RemoteBinding(computer, b.text("cwd") ?: return@mapNotNull null, b.text("thread"))
            }.toMap()
            return Stored(RemoteState(computers, bindings), passwords)
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
