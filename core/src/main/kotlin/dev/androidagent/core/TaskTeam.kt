/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mike's team: the Manager, three backstage members, and task roles the
 * Manager creates when similar cards keep coming.
 *
 * Roles organise work and knowledge; they carry no authority. Each has its
 * own chat for direct talk, but every task still runs in a fresh child chat
 * of the Manager, labelled with the member, so no chat grows without end.
 */
class TeamRoster(
    private val store: TaskBoardStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<TeamMember>>(emptyList())
    val members: StateFlow<List<TeamMember>> = mutable.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        mutable.value = store.loadMembers().sortedBy(::order)
        loaded = true
    }

    fun member(id: String): TeamMember? = mutable.value.firstOrNull { it.id == id }
    fun bySession(sessionId: String): TeamMember? = mutable.value.firstOrNull { it.sessionId == sessionId }
    fun of(kind: MemberKind): TeamMember? = mutable.value.firstOrNull { it.kind == kind }
    val sessionIds: Set<String> get() = mutable.value.mapNotNull { it.sessionId }.toSet()

    /**
     * Make sure the four built-in members exist, each with a chat. [chat]
     * creates a chat or returns null when [existing] is still there.
     */
    suspend fun ensureBuiltIns(chat: suspend (member: TeamMember, existing: String?) -> String) = lock.withLock {
        BUILT_INS.forEach { template ->
            val current = mutable.value.firstOrNull { it.id == template.id }
            val member = current ?: template.copy(createdAt = now())
            val session = chat(member, member.sessionId)
            if (current == null || current.sessionId != session) save(member.copy(sessionId = session))
        }
    }

    /** A new task role. Only the Manager creates them; the cap makes it merge instead of multiply. */
    suspend fun createRole(name: String, role: String, kinds: List<String>, chat: suspend (TeamMember) -> String): TeamMember = lock.withLock {
        val cleanName = name.trim().take(24).ifEmpty { error("Give the role a short name.") }
        require(mutable.value.none { it.name.equals(cleanName, true) }) { "A member is already called $cleanName." }
        require(mutable.value.count { it.kind == MemberKind.ROLE } < MAX_ROLES) {
            "The team has $MAX_ROLES task roles. Merge or rename one instead of adding another."
        }
        val cleanKinds = kinds.map(TaskBoard::topicKey).filter { it.isNotEmpty() }.distinct().take(8)
        require(cleanKinds.isNotEmpty()) { "Name the card kinds this role takes." }
        val id = "role-" + TaskBoard.topicKey(cleanName).take(32).ifEmpty { SignalInbox.idOf(cleanName, now()) }
        require(member(id) == null) { "A role with this id exists." }
        val usedHues = mutable.value.map { it.hue }
        val hue = ROLE_HUES.firstOrNull { candidate -> usedHues.none { kotlin.math.abs(it - candidate) < 12f } } ?: ROLE_HUES.first()
        val member = TeamMember(id, cleanName, role.trim().take(120), MemberKind.ROLE, hue, taskKinds = cleanKinds,
            createdAt = now(), createdBy = "manager")
        val withChat = member.copy(sessionId = chat(member))
        save(withChat)
        withChat
    }

    suspend fun rename(id: String, name: String?, role: String?, kinds: List<String>?): TeamMember = lock.withLock {
        val current = member(id) ?: error("No member has id $id.")
        val next = current.copy(
            name = name?.trim()?.take(24)?.takeIf { it.isNotEmpty() } ?: current.name,
            role = role?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: current.role,
            taskKinds = kinds?.map(TaskBoard::topicKey)?.filter { it.isNotEmpty() }?.distinct()?.take(8)
                ?.takeIf { current.kind == MemberKind.ROLE } ?: current.taskKinds,
        )
        save(next); next
    }

    /** Remove a task role. Built-in members stay. */
    suspend fun remove(id: String) = lock.withLock {
        val current = member(id) ?: error("No member has id $id.")
        require(current.kind == MemberKind.ROLE) { "Built-in members stay on the team." }
        store.deleteMember(id)
        mutable.value = mutable.value.filterNot { it.id == id }
    }

    /** The role that takes cards of [kind], if one does. */
    fun roleFor(kind: String?): TeamMember? = kind?.let { k -> mutable.value.firstOrNull { it.kind == MemberKind.ROLE && k in it.taskKinds } }

    suspend fun appendJournal(id: String, line: String) = lock.withLock {
        val current = member(id) ?: return@withLock
        val journal = (current.journal.lines().filter { it.isNotBlank() } + line.trim().take(200)).takeLast(JOURNAL_LINES).joinToString("\n")
        save(current.copy(journal = journal))
    }

    private suspend fun save(member: TeamMember) {
        store.saveMember(member)
        mutable.value = (mutable.value.filterNot { it.id == member.id } + member).sortedBy(::order)
    }

    private fun order(member: TeamMember): Int = member.kind.ordinal * 1_000 + (member.createdAt % 1_000).toInt()

    companion object {
        const val MAX_ROLES = 3
        const val JOURNAL_LINES = 20
        const val MANAGER = "manager"
        const val SCOUT = "scout"
        const val COACH = "coach"
        const val LIBRARIAN = "librarian"
        private val ROLE_HUES = listOf(200f, 330f, 150f, 30f, 265f, 95f)

        val BUILT_INS = listOf(
            TeamMember(MANAGER, "Mike", "Runs the board and the team, and talks with you about all of it.", MemberKind.MANAGER, 168f, createdAt = 0),
            TeamMember(SCOUT, "Scout", "Reads new messages, mail, the calendar and quiet chats, and proposes tasks.", MemberKind.SCOUT, 42f, createdAt = 0),
            TeamMember(COACH, "Coach", "Reviews your decisions each night and sharpens the playbook.", MemberKind.COACH, 285f, createdAt = 0),
            TeamMember(LIBRARIAN, "Librarian", "Compacts memory each night and keeps what is still needed.", MemberKind.LIBRARIAN, 215f, createdAt = 0),
        )
    }
}
