package dev.androidagent.app.ui

import dev.androidagent.core.ChatSession

/** A flat chat list. Location and project are filters, never nested lists. */
data class LibraryChat(val entry: PcChatEntry, val computerId: String? = null, val project: PcProject? = null) {
    val key: String get() = "${computerId ?: ChatLibrary.PHONE}:${entry.key}"
}

object ChatLibrary {
    const val ALL = "all-locations"
    const val PHONE = "this-phone"

    fun chats(
        sessions: List<ChatSession>,
        bindings: Map<String, dev.androidagent.remote.RemoteBinding>,
        sections: List<PcSection>,
        location: String = ALL,
        projectKey: String? = null,
        query: String = "",
    ): List<LibraryChat> {
        val needle = query.trim()
        val phone = if (projectKey == null && location in setOf(ALL, PHONE)) {
            PcChats.phoneSessions(sessions, bindings).map { LibraryChat(PcChatEntry.Local(it)) }
        } else emptyList()
        val computers = sections.filter { location == ALL || it.computer.id == location }.flatMap { section ->
            section.projects.filter { projectKey == null || it.key == projectKey }.flatMap { project ->
                project.chats.map { LibraryChat(it, section.computer.id, project) }
            }
        }
        return (phone + computers).filter { row ->
            needle.isEmpty() || row.entry.title.contains(needle, true) ||
                row.project?.path?.contains(needle, true) == true ||
                sections.firstOrNull { it.computer.id == row.computerId }?.computer?.label?.contains(needle, true) == true
        }.sortedByDescending { it.entry.updatedAt }
    }

    fun projects(sections: List<PcSection>, location: String, query: String): List<PcProject> {
        val needle = query.trim()
        return sections.filter { location == ALL || it.computer.id == location }.flatMap { it.projects }
            .filter { needle.isEmpty() || it.path.contains(needle, true) || it.chats.any { chat -> chat.title.contains(needle, true) } }
            .sortedByDescending { it.updatedAt }
    }
}
