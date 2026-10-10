package dev.androidagent.remote

import dev.androidagent.core.ChatMessage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Native IDs we already displayed through the live engine, not a second copy of their text. */
class ClaudeMessageLedger(private val root: File) {
    private fun file(computer: String, thread: String): File {
        ClaudeSessionFiles.requireId(thread)
        val owner = UUID.nameUUIDFromBytes(computer.toByteArray(Charsets.UTF_8))
        return File(root, "$owner/$thread.ids")
    }

    @Synchronized fun ids(computer: String, thread: String): Set<String> =
        file(computer, thread).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.onEach(ClaudeSessionFiles::requireId)?.toSet().orEmpty()

    @Synchronized fun record(computer: String, thread: String, id: String) {
        ClaudeSessionFiles.requireId(id)
        if (id in ids(computer, thread)) return
        val path = file(computer, thread)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs()) { "Claude sync folder could not be created" }
        FileOutputStream(path, true).use { out -> out.write((id + "\n").toByteArray(Charsets.UTF_8)); out.fd.sync() }
    }

    @Synchronized fun adoptLocalHistory(computer: String, thread: String, history: List<ComputerMessage>, local: List<ChatMessage>) {
        val marker = File(file(computer, thread).path + ".baseline")
        if (marker.isFile) return
        val matched = ClaudeHistorySync.localMatches(computer, thread, history, local)
        matched.forEach { record(computer, thread, it) }
        check(marker.parentFile!!.isDirectory || marker.parentFile!!.mkdirs())
        FileOutputStream(marker).use { it.write(1); it.fd.sync() }
    }
}

object ClaudeHistorySync {
    fun messageId(computer: String, thread: String, source: String): String =
        UUID.nameUUIDFromBytes("claude:$computer:$thread:$source".toByteArray(Charsets.UTF_8)).toString()

    /** Append only. Local notes, drafts, attachments and live messages are never rewritten. */
    fun additions(session: String, computer: String, thread: String, history: List<ComputerMessage>,
                  local: List<ChatMessage>, owned: Set<String>): List<ChatMessage> {
        val known = local.map { it.id }.toHashSet()
        val displayed = localMatches(computer, thread, history.filter { it.id in owned }, local)
        return history.filter { source ->
            // A process can die after an ID was recorded but before the UI saved
            // its text. In that case recover the native message instead of hiding it.
            source.id !in displayed
        }.map { source ->
            ChatMessage(messageId(computer, thread, source.id), session, source.role, source.text, source.createdAt)
        }.filter { known.add(it.id) }
    }

    /** Match each saved live message once, including older Mike chats without native ID records. */
    internal fun localMatches(computer: String, thread: String, history: List<ComputerMessage>, local: List<ChatMessage>): Set<String> {
        val importedIds = history.map { messageId(computer, thread, it.id) }.toSet()
        val available = local.filter { it.id !in importedIds && it.state == "complete" && it.text.isNotBlank() && it.role in setOf("user", "assistant") }.toMutableList()
        val matched = mutableSetOf<String>()
        history.distinctBy { it.id }.forEach { source ->
            val candidates = available.filter { it.role == source.role && source.createdAt > 0 && kotlin.math.abs(it.createdAt - source.createdAt) <= 300_000 }
            val single = candidates.firstOrNull { it.text == source.text || source.role == "user" && source.text.endsWith(it.text) }
            if (single != null) { available.remove(single); matched += source.id }
            else if (source.role == "assistant") {
                // The live engine displays text blocks separately; the transcript groups them by UUID.
                for (start in candidates.indices) {
                    val blocks = mutableListOf<ChatMessage>()
                    for (end in start until minOf(candidates.size, start + 32)) {
                        blocks += candidates[end]
                        val joined = blocks.joinToString("\n") { it.text }
                        if (joined == source.text) { available.removeAll(blocks.toSet()); matched += source.id; break }
                        if (joined.length >= source.text.length) break
                    }
                    if (source.id in matched) break
                }
            }
        }
        return matched
    }
}
