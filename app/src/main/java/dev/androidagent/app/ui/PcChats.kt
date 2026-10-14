package dev.androidagent.app.ui

import dev.androidagent.core.ChatSession
import dev.androidagent.core.EngineKind
import dev.androidagent.remote.ComputerConversation
import dev.androidagent.remote.RemoteBinding
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteProject

/** One row under a project: a chat on this phone, or a conversation only the computer has. */
sealed interface PcChatEntry {
    val key: String
    val title: String
    val updatedAt: Long

    data class Local(val session: ChatSession) : PcChatEntry {
        override val key get() = "chat-${session.id}"
        override val title get() = session.title
        override val updatedAt get() = session.updatedAt
    }

    data class OnComputer(val thread: ComputerConversation) : PcChatEntry {
        override val key get() = "thread-${thread.key}"
        override val title get() = thread.title
        override val updatedAt get() = thread.updatedAt
    }
}

data class PcProject(val computerId: String, val path: String, val chats: List<PcChatEntry>) {
    val name: String get() = folderName(path)
    val key: String get() = "project-$computerId-${PcChats.pathKey(path)}"
    val updatedAt: Long get() = chats.maxOfOrNull { it.updatedAt } ?: 0L
}

data class PcSection(val computer: RemoteComputer, val projects: List<PcProject>)

/**
 * The side panel's computer part: per computer, its projects, and under each
 * the chats. A project is a folder the user picked, a folder a chat here
 * runs in, or a folder Codex on the computer worked in.
 */
object PcChats {

    /** A saved PC name is shared across clients; an unnamed preview must not replace a local name. */
    fun title(session: ChatSession, binding: RemoteBinding?, threads: List<ComputerConversation>): String =
        session.engineThreadId?.takeIf { binding != null && binding.thread(session.engine) == it }
            ?.let { id -> threads.firstOrNull { it.engine == session.engine && it.id == id }?.name?.takeIf { it.isNotBlank() } }
            ?: session.title

    fun threadKey(engine: EngineKind, threadId: String): String = "${engine.name}:$threadId"

    /**
     * The computer conversations a chat bound there already follows, by
     * [threadKey]: the binding's thread, the one parked for the other engine,
     * and the chat's own threads. A Claude chat Mike started before bindings
     * named their engine is found through the chat alone.
     */
    fun followed(binding: RemoteBinding, session: ChatSession?): Set<String> = buildSet {
        binding.threadId?.let { add(threadKey(binding.engine, it)) }
        binding.parked?.let { add(threadKey(it.engine, it.threadId)) }
        session?.engineThreadId?.let { add(threadKey(session.engine, it)) }
        session?.parked?.forEach { (engine, parked) -> add(threadKey(engine, parked.threadId)) }
    }

    /** Windows paths ignore case and may end in a separator; Linux paths keep their case. */
    fun pathKey(path: String): String =
        if (path.startsWith("/")) path.trimEnd('/') else path.replace('/', '\\').trimEnd('\\').lowercase()

    fun sections(
        computers: List<RemoteComputer>,
        defaultComputerId: String?,
        projects: List<RemoteProject>,
        bindings: Map<String, RemoteBinding>,
        sessions: List<ChatSession>,
        threads: Map<String, List<ComputerConversation>>,
        query: String = "",
    ): List<PcSection> {
        val needle = query.trim()
        val byId = sessions.associateBy { it.id }
        return computers.sortedByDescending { it.id == defaultComputerId }.map { computer ->
            val bound = bindings.filterValues { it.computerId == computer.id }
            val followed = bound.flatMap { (chat, binding) -> followed(binding, byId[chat]) }.toSet()
            val entries = mutableMapOf<String, MutableList<PcChatEntry>>()
            val spelled = mutableMapOf<String, String>()
            fun folder(path: String): MutableList<PcChatEntry> {
                val key = pathKey(path)
                spelled.putIfAbsent(key, path)
                return entries.getOrPut(key) { mutableListOf() }
            }
            projects.filter { it.computerId == computer.id }.forEach { folder(it.path) }
            bound.forEach { (chat, binding) -> byId[chat]?.let {
                folder(binding.cwd) += PcChatEntry.Local(it.copy(title = title(it, binding, threads[computer.id].orEmpty())))
            } }
            threads[computer.id].orEmpty()
                .filter { it.key !in followed }
                .forEach { folder(it.cwd) += PcChatEntry.OnComputer(it) }
            val shown = entries.map { (key, chats) ->
                val kept = if (needle.isEmpty()) chats else chats.filter { it.title.contains(needle, ignoreCase = true) }
                PcProject(computer.id, spelled.getValue(key), kept.sortedByDescending { it.updatedAt })
            }.filter {
                needle.isEmpty() || it.chats.isNotEmpty() || it.name.contains(needle, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }
            PcSection(computer, shown)
        }
    }

    /** Chats that run on the phone. */
    fun phoneSessions(sessions: List<ChatSession>, bindings: Map<String, RemoteBinding>): List<ChatSession> =
        sessions.filter { it.id !in bindings }

    /** The most recently used projects across computers, for the new-chat picker. */
    fun recentProjects(sections: List<PcSection>, limit: Int = 4): List<PcProject> =
        sections.flatMap { it.projects }.sortedByDescending { it.updatedAt }.take(limit)
}
