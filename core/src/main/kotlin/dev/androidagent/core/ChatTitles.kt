package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Names come from the user's request, never from the runtime input before it. */
object ChatTitles {
    const val MAX_LENGTH = 80
    private val whitespace = Regex("\\s+")
    private const val RUNTIME_PREFIX = "[Trusted Android Agent runtime context]"

    fun isRuntimeTitle(title: String): Boolean = title.trimStart().startsWith(RUNTIME_PREFIX)

    fun normalize(title: String): String = title.trim().replace(whitespace, " ")

    fun fallback(prompt: String): String {
        val text = normalize(prompt)
        if (text.isBlank() || isRuntimeTitle(text)) return "Image chat"
        if (text.length <= MAX_LENGTH) return text
        val short = text.take(MAX_LENGTH - 1)
        val boundary = short.lastIndexOf(' ').takeIf { it >= MAX_LENGTH / 2 } ?: short.length
        return short.take(boundary).trimEnd() + "…"
    }
}

/** Shared by all runs and the rename UI, so a late automatic name cannot win over a manual one. */
class ChatTitleManager(private val sessions: SessionStore, private val engine: AgentEngine) {
    private val lock = Mutex()

    suspend fun seed(sessionId: String, prompt: String) = lock.withLock {
        if (sessions.getSession(sessionId)?.titlePending != true) return@withLock
        val firstRequest = sessions.messages(sessionId).first().firstOrNull { it.role == "user" }?.text ?: prompt
        sessions.setAutomaticTitle(sessionId, ChatTitles.fallback(firstRequest), complete = false)
        Unit
    }

    /** Only a newly created/replaced thread gets the phone's saved name. Existing PC names stay theirs. */
    suspend fun nameNewThread(sessionId: String, threadId: String) = lock.withLock {
        val session = sessions.getSession(sessionId) ?: return@withLock
        val title = session.title
        if (title == "New chat" || ChatTitles.isRuntimeTitle(title)) return@withLock
        try {
            withTimeoutOrNull(5_000) {
                val saved = engine.threadName(threadId)
                if (session.titlePending && saved != null && saved != title && !ChatTitles.isRuntimeTitle(saved)) {
                    sessions.setAutomaticTitle(sessionId, saved, complete = true)
                } else if (saved != title) engine.renameThread(threadId, title)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Naming is optional; a server without this method must still run the user's task.
            // The title tool can retry while the provisional name is pending.
        }
        Unit
    }

    suspend fun refine(sessionId: String, threadId: String, title: String): ToolResult = lock.withLock {
        val name = ChatTitles.normalize(title)
        if (name.isBlank() || name.length > ChatTitles.MAX_LENGTH || ChatTitles.isRuntimeTitle(name)) {
            return@withLock ChatTools.refused("bad_title", "Use a short topic name, up to 80 characters, in the user's language.")
        }
        val session = sessions.getSession(sessionId) ?: return@withLock ChatTools.refused("no_chat", "This chat was removed.")
        if (!session.titlePending) return@withLock ToolResult(buildJsonObject {
            put("ok", true); put("changed", false); put("title", session.title)
            put("message", "This chat already has a chosen name. Keep it.")
        }.toString())
        val chosen = withTimeoutOrNull(5_000) {
            val saved = engine.threadName(threadId)
            if (saved != null && saved != session.title && !ChatTitles.isRuntimeTitle(saved)) saved
            else { engine.renameThread(threadId, name); name }
        }
        check(chosen != null) {
            "The computer did not save the title in time. The provisional name is kept."
        }
        val changed = sessions.setAutomaticTitle(sessionId, chosen, complete = true)
        ToolResult(buildJsonObject {
            put("ok", true); put("changed", changed); put("title", chosen)
            if (chosen != name) put("message", "The name already chosen in Codex is kept.")
        }.toString())
    }

    suspend fun rename(sessionId: String, title: String) = lock.withLock {
        sessions.rename(sessionId, title)
        val session = sessions.getSession(sessionId) ?: return@withLock
        session.engineThreadId?.let { threadId ->
            check(withTimeoutOrNull(5_000) { engine.renameThread(threadId, session.title); true } == true) {
                "The name was saved on this phone, but the computer did not save it in time."
            }
        }
        Unit
    }
}
