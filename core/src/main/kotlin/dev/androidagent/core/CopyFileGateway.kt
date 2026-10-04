package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * A place files live that is not this chat's own folder: the phone's shared
 * storage, or a saved computer. Each moves bytes to and from a file on this
 * phone, since every copy passes through the phone anyway.
 */
interface FilePlace {
    /** What goes before the colon in an address: "phone", or a computer's name. */
    val name: String

    /** Other words that name this place, such as a computer's id. */
    val aliases: Set<String> get() = emptySet()

    /**
     * Copy [path] into [target], a file on this phone. A relative [path] is
     * taken against [base], the chat's folder there, or refused when there is
     * none. Reports (bytes, total) as it goes.
     */
    suspend fun download(path: String, base: String?, target: File, progress: (Long, Long) -> Unit): Fetched

    /**
     * Store [source] at [path] (a folder when it ends in a separator) and
     * return the address it now has, as `copy_file` would write it. A file
     * already there is refused unless [replace]. The file appears whole or
     * not at all.
     */
    suspend fun upload(source: File, path: String, base: String?, replace: Boolean, progress: (Long, Long) -> Unit): String

    /** What [download] copied: its size and the file's own name. */
    data class Fetched(val size: Long, val name: String)

    /**
     * Size and full path of the file [path] names, without copying it. Null
     * when this place cannot tell without a copy; throws when there is no
     * such file or the place cannot be reached.
     */
    suspend fun stat(path: String, base: String?): Stat? = null

    /** A file seen in place: its size, its absolute [path] here, and its name. */
    data class Stat(val size: Long, val path: String, val name: String)
}

/** Where bare paths point in a chat whose shell runs on a computer: that computer, in the project folder. */
data class FileHome(val place: String, val base: String)

/**
 * `copy_file`: one tool that copies between any two places.
 *
 * Addresses name their place: `chat:` (this chat's folder on the phone),
 * `phone:` (shared storage, or a `content://` uri), or a computer's name. A
 * bare path is where the chat's shell runs. What to do with a file after
 * (install it, share it) belongs to other tools; which copies make a use
 * case belongs to the `files-across-devices` skill, not to more tools.
 */
class CopyFileGateway(
    private val phone: FilePlace?,
    private val computers: () -> List<FilePlace>,
    private val home: (File) -> FileHome?,
    private val meter: TransferMeter?,
    /** Where a copy between two outside places waits on the phone. */
    private val scratch: File,
) : DeviceToolGateway {

    @Volatile private var workspace: File? = null
    @Volatile private var revoked = true

    override val definitions: List<ToolDefinition> = listOf(DEFINITION)

    override fun beginRun(runId: String, workspace: File) {
        this.workspace = workspace.absoluteFile
        revoked = false
    }

    override fun revoke() { revoked = true }

    /** Copying never touches the screen. */
    override fun needsControl(name: String): Boolean = false

    override fun deviceBackendLive(): Boolean = false

    override suspend fun cancel() = Unit

    /** A resolved address: a file in this chat's folder, or a path in another place. */
    sealed interface Spot {
        val label: String

        data class Chat(val folder: File, val relative: String) : Spot {
            val file: File get() = if (relative.isEmpty()) folder else File(folder, relative)
            val isFolder: Boolean get() = relative.isEmpty() || relative.endsWith("/")
            override val label: String get() = "this chat"
        }

        data class Away(val place: FilePlace, val path: String, val base: String?) : Spot {
            val isFolder: Boolean get() = path.isEmpty() || path.endsWith("/") || path.endsWith("\\")
            override val label: String get() = place.name
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. Nothing was copied.")
        if (name != NAME) throw ToolNotServiceable("copy_unsupported", "copy_file does not implement \"$name\".")
        val ws = workspace ?: throw IllegalStateException("Run stopped. Nothing was copied.")
        val from = arguments.text("from") ?: return refusal("missing_from", "Say which file to copy in from.")
        val to = arguments.text("to") ?: return refusal("missing_to", "Say where to copy it in to.")
        val replace = (arguments["replace"] as? JsonPrimitive)?.booleanOrNull == true
        return try {
            val source = resolve(from, ws)
            val target = resolve(to, ws)
            val copied = copy(source, target, replace)
            ToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("from", from)
                    put("to", copied.address)
                    copied.address.removePrefix("phone:").takeIf { it.startsWith("content://") }?.let { put("uri", it) }
                    put("bytes", copied.size)
                    put("complete", true)
                }.toString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: Refused) {
            refusal(refused.type, refused.message)
        } catch (error: Exception) {
            refusal("copy_failed", "Could not copy $from to $to: ${error.message}. Nothing was left half-copied.")
        }
    }

    /**
     * A file on this phone for [address], for a tool that acts on the phone
     * (installing an APK). A file elsewhere is refused with the copy that
     * brings it here: the action never starts a transfer of its own.
     */
    suspend fun phoneFile(address: String, workspace: File): File = when (val spot = resolve(address, workspace.absoluteFile)) {
        is Spot.Chat -> spot.file.also { check(it.isFile) { "No file at chat:${spot.relative}." } }
        is Spot.Away -> {
            if (spot.place !== phone) {
                throw IllegalArgumentException(
                    "$address is on ${spot.place.name}, not on the phone. Copy it first with copy_file (to chat:), then use the chat: address.",
                )
            }
            val dir = File(scratch, UUID.randomUUID().toString()).apply { mkdirs() }
            val staged = File(dir, "fetched")
            val fetched = spot.place.download(spot.path, spot.base, staged) { _, _ -> }
            File(dir, fetched.name).also { staged.renameTo(it) }
        }
    }

    /**
     * A file in [workspace] holding what [address] names, for the chat to
     * show. A file already in the chat's folder is used where it is; one from
     * the phone's storage or a computer is copied into `media/`, under a name
     * no earlier copy has. Does not depend on the run the gateway is armed
     * for, so a chat that does not hold the phone can call it.
     */
    suspend fun chatCopy(address: String, workspace: File): File {
        val ws = workspace.absoluteFile
        return try {
            when (val spot = resolve(address, ws)) {
                is Spot.Chat -> spot.file.also { require(it.isFile) { "No file at chat:${spot.relative}." } }
                is Spot.Away -> {
                    val dir = File(ws, MEDIA_FOLDER).apply { mkdirs() }
                    val part = File(dir, ".copy-${UUID.randomUUID()}.part")
                    val name = spot.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
                    try {
                        val fetch: suspend ((Long, Long) -> Unit) -> FilePlace.Fetched = { progress ->
                            spot.place.download(spot.path, spot.base, part, progress)
                        }
                        val fetched = meter?.track(name, spot.label, "this chat", fetch) ?: fetch { _, _ -> }
                        val final = freeName(dir, fetched.name.ifBlank { name })
                        check(part.renameTo(final)) { "Could not finish the copy at ${final.name}." }
                        final
                    } finally {
                        part.delete()
                    }
                }
            }
        } catch (refused: Refused) {
            throw IllegalArgumentException(refused.message)
        }
    }

    private fun freeName(dir: File, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_")
        val stem = safe.substringBeforeLast('.', safe)
        val dot = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        return generateSequence(0) { it + 1 }
            .map { n -> File(dir, if (n == 0) safe else "$stem-$n$dot") }
            .first { !it.exists() }
    }

    /**
     * What a chat message should hold to show [address]: a file in the chat's
     * folder for one that is there already or on the phone, and for a file on a
     * computer a [RemoteMediaRef] that is not copied at all. The computer is
     * asked only that the file exists and how big it is; the bytes move when
     * the user opens it, and [fetchRemote] brings them.
     */
    suspend fun chatMedia(address: String, workspace: File): String {
        val ws = workspace.absoluteFile
        val spot = try { resolve(address, ws) } catch (refused: Refused) { throw IllegalArgumentException(refused.message) }
        if (spot is Spot.Away && spot.place !== phone) {
            val seen = try {
                spot.place.stat(spot.path, spot.base)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("${spot.place.name}: ${error.message ?: "could not be read"}")
            }
            if (seen != null) return RemoteMediaRef(spot.place.name, seen.path, seen.size).encode()
        }
        return chatCopy(address, workspace).absolutePath
    }

    /** Copy the file [ref] names from its computer to [target], reporting (bytes, total). */
    suspend fun fetchRemote(ref: RemoteMediaRef, target: File, progress: (Long, Long) -> Unit) {
        val place = placeNamed(ref.place) ?: throw IllegalArgumentException("${ref.place} is no longer saved.")
        place.download(ref.path, null, target, progress)
    }

    /** Save [source] on the phone at the shared [path] (such as `Pictures/Hey Mike/a.png`); returns its `phone:` address. */
    suspend fun saveToPhone(source: File, path: String, progress: (Long, Long) -> Unit = { _, _ -> }): String =
        (phone ?: throw IllegalStateException(noPhone().message)).upload(source, path, null, true, progress)

    internal fun resolve(address: String, ws: File): Spot {
        val text = address.trim()
        if (text.isEmpty()) throw Refused("blank_address", "An address is empty.")
        if (text.startsWith("content://")) return Spot.Away(phone ?: throw noPhone(), text, null)
        val colon = text.indexOf(':')
        if (colon > 0) {
            val prefix = text.substring(0, colon)
            val rest = text.substring(colon + 1)
            when {
                prefix.equals("chat", ignoreCase = true) -> return chatSpot(ws, rest)
                prefix.equals("phone", ignoreCase = true) -> return Spot.Away(phone ?: throw noPhone(), rest.trim(), null)
                else -> placeNamed(prefix)?.let { place -> return Spot.Away(place, rest.trim(), home(ws)?.takeIf { it.place.equals(place.name, true) }?.base) }
            }
        }
        // No place named: where the chat's shell runs.
        val here = home(ws) ?: return chatSpot(ws, text)
        val place = placeNamed(here.place) ?: throw Refused("unknown_place", "This chat's computer, ${here.place}, is no longer saved.")
        return Spot.Away(place, text, here.base)
    }

    private fun placeNamed(word: String): FilePlace? =
        computers().firstOrNull { it.name.equals(word, ignoreCase = true) || it.aliases.any { alias -> alias.equals(word, ignoreCase = true) } }

    private fun chatSpot(ws: File, rest: String): Spot.Chat {
        val relative = rest.trim().replace('\\', '/').trimStart('/')
        if (relative.contains('\u0000')) throw Refused("bad_path", "The path contains NUL.")
        if (relative.matches(Regex("^[A-Za-z]:.*"))) {
            throw Refused("which_place", "\"$rest\" is not in this chat's folder. Name the place: chat:, phone:, or a computer (${placeNames()}).")
        }
        val base = ws.canonicalFile
        val target = File(base, relative).canonicalFile
        if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
            throw Refused("bad_path", "chat:$rest is outside this chat's folder.")
        }
        return Spot.Chat(ws, relative)
    }

    private fun placeNames(): String = (listOfNotNull(phone?.name) + computers().map { it.name + ":" }).joinToString(", ")

    private fun noPhone() = Refused("unsupported", "This build cannot reach the phone's storage.")

    private class Copied(val address: String, val size: Long)

    private suspend fun copy(source: Spot, target: Spot, replace: Boolean): Copied {
        if (source is Spot.Chat && source.isFolder) throw Refused("not_a_file", "Name a file to copy, not a folder.")
        if (source is Spot.Chat && !source.file.isFile) throw Refused("no_file", "No file at chat:${source.relative}.")
        val sourceName = when (source) {
            is Spot.Chat -> source.file.name
            is Spot.Away -> source.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        }
        val track: suspend (suspend ((Long, Long) -> Unit) -> Copied) -> Copied = { body ->
            meter?.track(sourceName.ifBlank { "file" }, source.label, target.label, body) ?: body { _, _ -> }
        }
        return track { progress ->
            when {
                source is Spot.Chat && target is Spot.Chat -> {
                    val final = chatTarget(target, source.file.name, replace)
                    withContext(Dispatchers.IO) { whole(final) { part -> source.file.copyTo(part, overwrite = true) } }
                    Copied(chatAddress(target.folder, final), final.length())
                }
                source is Spot.Chat && target is Spot.Away -> {
                    val size = source.file.length()
                    val address = target.place.upload(source.file, toFolder(target, source.file.name), target.base, replace, progress)
                    Copied(address, size)
                }
                source is Spot.Away && target is Spot.Chat -> {
                    // A named target already there is refused before any byte moves.
                    if (!target.isFolder) chatTarget(target, target.file.name, replace)
                    // Downloaded beside the target and renamed at the end, so a
                    // stopped copy never leaves a file that looks finished.
                    val dir = (if (target.isFolder) target.file else target.file.parentFile).apply { mkdirs() }
                    val part = File(dir, ".copy-${UUID.randomUUID()}.part")
                    try {
                        val fetched = source.place.download(source.path, source.base, part, progress)
                        checkActive()
                        val final = chatTarget(target, fetched.name, replace)
                        if (final.exists()) final.delete()
                        check(part.renameTo(final)) { "Could not finish the copy at ${final.name}." }
                        Copied(chatAddress(target.folder, final), fetched.size)
                    } finally {
                        part.delete()
                    }
                }
                source is Spot.Away && target is Spot.Away -> {
                    // Two outside places: the file passes through the phone.
                    val dir = File(scratch, UUID.randomUUID().toString()).apply { mkdirs() }
                    try {
                        var half = 0L
                        val fetched = source.place.download(source.path, source.base, File(dir, "fetched")) { bytes, total ->
                            half = total; progress(bytes, total * 2)
                        }
                        checkActive()
                        val staged = File(dir, fetched.name).also { File(dir, "fetched").renameTo(it) }
                        val address = target.place.upload(staged, toFolder(target, fetched.name), target.base, replace) { bytes, _ ->
                            progress(half + bytes, half * 2)
                        }
                        Copied(address, fetched.size)
                    } finally {
                        dir.deleteRecursively()
                    }
                }
                else -> error("unreachable")
            }
        }
    }

    private fun checkActive() {
        if (revoked) throw IllegalStateException("Run stopped. Nothing was left half-copied.")
    }

    private fun toFolder(target: Spot.Away, name: String): String = if (target.isFolder) target.path + name else target.path

    private fun chatTarget(target: Spot.Chat, name: String, replace: Boolean): File {
        val final = if (target.isFolder) File(target.file, name) else target.file
        if (final.exists() && !replace) {
            throw Refused("exists", "${chatAddress(target.folder, final)} already exists. Pass replace: true to overwrite it.")
        }
        final.parentFile?.mkdirs()
        return final
    }

    private fun chatAddress(folder: File, file: File): String =
        "chat:" + file.canonicalFile.relativeTo(folder.canonicalFile).invariantSeparatorsPath

    /** Written as a part file and renamed once whole. */
    private fun whole(final: File, write: (File) -> Unit) {
        val part = File(final.parentFile, ".${final.name}.part")
        try {
            write(part)
            if (final.exists()) final.delete()
            check(part.renameTo(final)) { "Could not finish the copy at ${final.name}." }
        } finally {
            part.delete()
        }
    }

    private class Refused(val type: String, override val message: String) : Exception(message)

    private fun refusal(type: String, message: String) = ToolResult(
        buildJsonObject { put("ok", false); put("errorType", type); put("message", message) }.toString(),
        success = false,
    )

    companion object {
        const val NAME = "copy_file"

        /** Where [chatCopy] keeps what a chat shows, inside the chat's folder. */
        const val MEDIA_FOLDER = "media"

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private const val DESCRIPTION =
            "Copy a file from one place to another. Addresses: chat:<path> is this chat's folder on the phone; " +
                "phone:<path> is the phone's shared storage (Download/..., Pictures/..., DCIM/...) or a content:// uri " +
                "from files_media; <computer>:<path> is a file on a saved computer, by its name (Pc:C:\\Users\\me\\a.pdf, " +
                "Server:/home/me/a.pdf). A path with no place is where your shell runs: this chat's folder in a phone " +
                "chat, the project folder in a computer chat. A destination ending in / keeps the file's name. Nothing " +
                "is overwritten unless replace is true. Returns the file's new address (for phone:, also a content:// " +
                "uri to share or open). The files-across-devices skill has recipes."

        val DEFINITION = ToolDefinition(
            name = NAME,
            description = DESCRIPTION,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("from", buildJsonObject { put("type", "string"); put("description", "The file to copy, as an address.") })
                    put("to", buildJsonObject { put("type", "string"); put("description", "Where it goes, as an address; ending in / keeps the name.") })
                    put("replace", buildJsonObject { put("type", "boolean"); put("description", "Overwrite a file already there.") })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"))))
            },
        )
    }
}
