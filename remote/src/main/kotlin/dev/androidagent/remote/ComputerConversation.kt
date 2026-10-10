package dev.androidagent.remote

import dev.androidagent.core.EngineKind
import dev.androidagent.enginecodex.CodexThread

/** A native conversation on a saved computer; IDs belong to an engine there. */
data class ComputerConversation(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long,
    val name: String? = null,
    val engine: EngineKind = EngineKind.CODEX,
    val busy: Boolean = false,
    val model: String? = null,
) {
    val key: String get() = "${engine.name}:$id"

    companion object {
        fun codex(thread: CodexThread) = ComputerConversation(thread.id, thread.title, thread.cwd, thread.updatedAt, thread.name)
    }
}

/** Text projected from a native transcript. Source IDs make refresh idempotent. */
data class ComputerMessage(val id: String, val role: String, val text: String, val createdAt: Long)
