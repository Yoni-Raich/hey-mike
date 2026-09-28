package dev.androidagent.enginecodex

/**
 * A conversation Codex keeps on the machine it runs on. [cwd] is that
 * machine's path, so it stays a string.
 */
data class CodexThread(val id: String, val title: String, val cwd: String, val updatedAt: Long)

/** One message of a conversation: [role] is `user` or `assistant`. */
data class CodexThreadMessage(val role: String, val text: String)
