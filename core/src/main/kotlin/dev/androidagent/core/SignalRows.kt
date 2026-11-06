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

/**
 * The queue holds conversations with something new, not messages: at most one
 * open row per conversation, with why it entered and where to read from. A
 * person's conversation or an app keeps a short list of lines; a Mike chat
 * keeps only its place, and its words are read when a pass is about to run.
 */
object SignalRows {
    const val PERSON_MESSAGE = "person_message"
    const val OWNER_MESSAGE = "owner_message"
    const val TASK_RESULT = "task_result"
    const val CALENDAR_CHANGE = "calendar_change"
    const val MAIL_CHECK = "mail_check"
    const val CHAT_QUIET = "chat_quiet"
    const val APP_NOTIFICATION = "app_notification"
    const val HISTORY_SCAN = "history_scan"

    const val MAX_PERSON_ITEMS = 20
    const val MAX_APP_ITEMS = 8
    const val MAX_ROW_CHARS = 12_000
    const val MAX_REPLY_CHARS = 6_000

    private val PEOPLE_SOURCES = setOf("whatsapp", "email", "message")

    fun canOpen(rows: Collection<TaskSignal>, lane: SignalLane?) =
        rows.size < SignalInbox.MAX_PENDING && (lane != SignalLane.BACKGROUND ||
            rows.count { it.lane == SignalLane.BACKGROUND } < SignalInbox.MAX_PENDING - SignalInbox.RESERVED_PEOPLE_ROWS)

    /** A long text keeps its start and its end, where requests and sign-offs usually are. */
    fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val mark = "\n[...]\n"
        val head = (limit - mark.length) * 2 / 3
        return text.take(head) + mark + text.takeLast(limit - mark.length - head)
    }

    /** Which row a notification belongs to: its conversation, or the whole app. */
    fun keyOf(message: CapturedMessage): String =
        if (message.fromPerson) SignalInbox.idOf("person", message.app, message.conversation ?: message.sender) else SignalInbox.idOf("app", message.app)

    fun keyOf(row: TaskSignal): String = row.rowKey ?: when {
        row.sessionId != null -> "chat|${row.sessionId}"
        row.reason == APP_NOTIFICATION -> SignalInbox.idOf("app", row.app)
        row.app != null -> SignalInbox.idOf("person", row.app, row.conversation ?: row.sender)
        else -> row.id
    }

    /**
     * A person's line stays distinct by its time, because the same words can be
     * said twice. Any other notification is only its words, so a repost or a
     * repeated alert is the same line.
     */
    fun lineId(message: CapturedMessage, text: String): String =
        if (message.fromPerson) SignalInbox.idOf(message.app, message.conversation, message.sender, text, message.at)
        else SignalInbox.idOf("app-line", message.app, message.conversation, text)

    /** [row] with [message] added, or a new row when there is none open. */
    fun merge(row: TaskSignal?, message: CapturedMessage, text: String, clock: Long): TaskSignal {
        val person = message.fromPerson
        val base = row ?: TaskSignal(
            id = SignalInbox.idOf("row", keyOf(message), clock), source = SignalFilter.sourceOf(message.app), app = message.app,
            conversation = message.conversation?.take(120).takeIf { person },
            sender = message.sender?.take(80).takeIf { person && message.conversation == null },
            text = "", at = message.at, capturedAt = clock, sourceVerified = true,
            lane = if (person) SignalLane.PEOPLE else SignalLane.BACKGROUND,
            reason = if (person) PERSON_MESSAGE else APP_NOTIFICATION,
            rowKey = keyOf(message),
        )
        val item = SignalItem(clip(text, if (person) MAX_REPLY_CHARS else SignalInbox.MAX_TEXT), message.sender?.take(80).takeIf { person },
            message.conversation?.take(120).takeIf { !person }, message.at)
        return add(base, item).copy(at = maxOf(base.at, message.at), updatedAt = clock, group = base.group || message.group,
            revision = base.revision + 1, attempts = 0)
    }

    private fun add(row: TaskSignal, item: SignalItem): TaskSignal {
        val same = row.items.indexOfLast { it.text == item.text && it.sender == item.sender && it.title == item.title &&
            (row.lane != SignalLane.PEOPLE || it.at == item.at) }
        val items = if (same < 0) row.items + item
            else row.items.toMutableList().also { it[same] = it[same].copy(at = maxOf(it[same].at, item.at), count = it[same].count + item.count) }
        val kept = bounded(items.sortedBy { it.at }, if (row.lane == SignalLane.PEOPLE) MAX_PERSON_ITEMS else MAX_APP_ITEMS)
        return row.copy(items = kept, omitted = row.omitted + items.size - kept.size)
    }

    /** Overflow pushes out the oldest lines only. */
    private fun bounded(items: List<SignalItem>, limit: Int): List<SignalItem> {
        var kept = items.takeLast(limit)
        while (kept.size > 1 && kept.sumOf { it.text.length } > MAX_ROW_CHARS) kept = kept.drop(1)
        return kept
    }

    /** What one message in the chat feed does to the queue. */
    enum class ChatEntry { NONE, OWNER, RESULT, REQUEST, REPLY }
    data class ChatCapture(val change: SessionMessageChange, val title: String, val ordinary: Boolean,
        val ownerChat: Boolean, val taskResult: Boolean, val quietUntil: Long)

    fun chatRow(row: TaskSignal?, capture: ChatCapture, entry: ChatEntry, clock: Long): TaskSignal? {
        if (entry == ChatEntry.NONE || (entry == ChatEntry.REPLY && row == null)) return null
        val message = capture.change.message
        val reason = when (entry) {
            ChatEntry.OWNER -> OWNER_MESSAGE
            ChatEntry.RESULT -> TASK_RESULT
            else -> row?.reason ?: CHAT_QUIET
        }
        val base = row ?: TaskSignal(SignalInbox.idOf("row", message.sessionId, capture.change.sequence), "chat",
            conversation = capture.title, text = "", at = message.createdAt, capturedAt = clock,
            sessionId = message.sessionId, sourceVerified = true, sinceAt = message.createdAt)
        return base.copy(reason = reason, lane = if (reason in setOf(OWNER_MESSAGE, TASK_RESULT)) SignalLane.IMMEDIATE else SignalLane.BACKGROUND,
            at = maxOf(base.at, message.createdAt), updatedAt = clock, revision = base.revision + 1, attempts = 0,
            ownerFeedback = reason == OWNER_MESSAGE,
            notBefore = if (reason in setOf(OWNER_MESSAGE, TASK_RESULT)) clock else capture.quietUntil)
    }

    /**
     * Only the user's own words and a task's result open a row. Mike's reply in
     * an ordinary chat opens nothing: it only means the chat is not quiet yet.
     */
    fun chatEntry(message: ChatMessage, ordinary: Boolean, ownerChat: Boolean, taskResult: Boolean, startAt: Long, clock: Long, historyMs: Long,
        freshRevision: Boolean = false): ChatEntry = when {
        !freshRevision && (clock - message.createdAt > historyMs || message.createdAt < startAt) -> ChatEntry.NONE
        ownerChat && message.role == "user" -> ChatEntry.OWNER
        taskResult && message.role == "assistant" && message.phase == "final_answer" -> ChatEntry.RESULT
        !ordinary -> ChatEntry.NONE
        message.role == "user" -> ChatEntry.REQUEST
        message.role == "assistant" -> ChatEntry.REPLY
        else -> ChatEntry.NONE
    }

    /**
     * What a pass reads from a chat, with nothing stored in the queue. An owner
     * row is the user's words, a task result is its last reply, and an ordinary
     * chat is each request with the reply that finally answered it.
     */
    fun chatText(row: TaskSignal, messages: List<ChatMessage>): String {
        val history = row.reason == HISTORY_SCAN
        val fresh = messages.filter { it.createdAt >= row.sinceAt && it.state == "complete" &&
            (it.role == "user" || (it.role == "assistant" && (it.phase == "final_answer" || (history && it.phase == null)))) && it.text.isNotBlank() }
        val lines = when (row.reason) {
            OWNER_MESSAGE -> fresh.filter { it.role == "user" }.map { "User: ${clip(it.text, SignalInbox.MAX_TEXT)}" }
            TASK_RESULT -> fresh.lastOrNull { it.role == "assistant" }?.let { listOf("Mike: ${clip(it.text, MAX_REPLY_CHARS)}") }.orEmpty()
            else -> requests(fresh)
        }
        return clip(lines.joinToString("\n"), MAX_ROW_CHARS)
    }

    private fun requests(messages: List<ChatMessage>): List<String> {
        val start = messages.indexOfFirst { it.role == "user" }
        if (start < 0) return emptyList()
        val lines = mutableListOf<String>()
        var reply: ChatMessage? = null
        fun flush() { reply?.let {
            val label = if (it.phase == null) "Mike (historical reply, final status unknown)" else "Mike"
            lines += "$label: ${clip(it.text, MAX_REPLY_CHARS)}"
        }; reply = null }
        messages.drop(start).forEach { message ->
            if (message.role == "user") { flush(); lines += "User: ${clip(message.text, SignalInbox.MAX_TEXT)}" } else reply = message
        }
        flush()
        return lines
    }

    /** Waiting input from before rows existed, in rows, and the lines those rows now answer for. */
    data class Migration(val remove: List<String>, val rows: List<TaskSignal>, val receipts: List<String>)

    fun migrate(legacy: List<TaskSignal>, clock: Long, startAt: Long, isTaskChat: (String) -> Boolean): Migration {
        val old = legacy.filter { it.lane == null }
        val (chats, others) = old.partition { it.source in setOf("chat", "backfill") && it.sessionId != null }
        val chatRows = chats.groupBy { it.sessionId!! }.mapNotNull { (session, fragments) ->
            val owner = fragments.filter { it.ownerFeedback }
            val words = fragments.filter { it.sender == "User" && it.at >= startAt }
            val (reason, from) = when {
                owner.isNotEmpty() -> OWNER_MESSAGE to owner
                isTaskChat(session) -> TASK_RESULT to fragments
                // Mike's words alone never open a row.
                words.isEmpty() -> return@mapNotNull null
                else -> CHAT_QUIET to words
            }
            TaskSignal(id = SignalInbox.idOf("row", "chat", session, reason), source = "chat", conversation = fragments.last().conversation,
                text = "", at = fragments.maxOf { it.at }, capturedAt = fragments.minOf { it.capturedAt }, sessionId = session,
                notBefore = fragments.maxOf { it.notBefore }, sourceVerified = true, ownerFeedback = owner.isNotEmpty(),
                lane = if (reason == CHAT_QUIET) SignalLane.BACKGROUND else SignalLane.IMMEDIATE, reason = reason,
                sinceAt = from.minOf { it.at }, updatedAt = fragments.maxOf { it.capturedAt })
        }
        val receipts = mutableListOf<String>()
        val noticeRows = others.filter { it.app != null && it.source != "calendar" }.groupBy { signal ->
            if (signal.source in PEOPLE_SOURCES) "${signal.app}|${signal.conversation ?: signal.sender}" else signal.app!!
        }.values.map { lines ->
            val first = lines.first()
            val person = first.source in PEOPLE_SOURCES
            val base = TaskSignal(id = SignalInbox.idOf("row", keyOf(first.copy(reason = if (person) PERSON_MESSAGE else APP_NOTIFICATION)), clock),
                source = first.source, app = first.app, conversation = first.conversation.takeIf { person },
                sender = first.sender.takeIf { person && first.conversation == null }, text = "", group = lines.any { it.group },
                at = lines.maxOf { it.at }, capturedAt = lines.minOf { it.capturedAt }, notBefore = lines.maxOf { it.notBefore },
                sourceVerified = lines.all { it.sourceVerified }, lane = if (person) SignalLane.PEOPLE else SignalLane.BACKGROUND,
                reason = if (person) PERSON_MESSAGE else APP_NOTIFICATION, updatedAt = lines.maxOf { it.capturedAt })
            // A long message used to arrive as parts of one line.
            lines.groupBy { it.at to it.sender }.values.map { parts -> parts.joinToString("") { it.text } to parts.first() }
                .fold(base) { row, (text, line) ->
                    val captured = CapturedMessage(line.app!!, line.conversation, line.sender, text, line.at, line.group, fromPerson = person)
                    receipts += lineId(captured, text)
                    add(row, SignalItem(clip(text, SignalInbox.MAX_TEXT), line.sender.takeIf { person }, line.conversation.takeIf { !person }, line.at))
                }
        }
        val rest = others.filter { it.app == null || it.source == "calendar" }.map { signal ->
            if (signal.source == "calendar") signal.copy(lane = SignalLane.PEOPLE, reason = CALENDAR_CHANGE, updatedAt = signal.capturedAt)
            else signal.copy(lane = SignalLane.BACKGROUND, reason = HISTORY_SCAN, updatedAt = signal.capturedAt)
        }
        return Migration(old.map { it.id }, chatRows + noticeRows + rest, receipts)
    }
}
