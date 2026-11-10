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

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app itself knows about one conversation, with no model involved:
 * when its notification last appeared, when it went away, and when the owner
 * last wrote in it. Times only; no message text is kept here.
 */
@Serializable
data class ConversationMark(
    val key: String,
    /** The last time a notification for this conversation was posted or updated. */
    val postedAt: Long = 0,
    /** The last time its notification was opened or cleared. */
    val seenAt: Long? = null,
    /** The last time a notification showed the owner's own reply. */
    val repliedAt: Long? = null,
    val notificationKey: String? = null,
) {
    /** The owner opened, cleared or answered after the newest notification. */
    val handled: Boolean get() = maxOf(seenAt ?: 0, repliedAt ?: 0) >= postedAt && postedAt > 0

    fun handledAfter(since: Long): Boolean = maxOf(seenAt ?: 0, repliedAt ?: 0) > since
}

/**
 * The ledger of [ConversationMark]s. A removed notification is the only sign
 * the phone gives that the owner dealt with a message, so it is recorded here
 * and used twice: a light message the owner already saw never reaches a model,
 * and a card that only waits for a reply closes when the conversation is opened.
 */
class ConversationLedger(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private var marks: Map<String, ConversationMark> = load()

    private fun load(): Map<String, ConversationMark> = try {
        file?.takeIf { it.isFile }?.let { json.decodeFromString<List<ConversationMark>>(it.readText()) }.orEmpty().associateBy { it.key }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun save(next: Map<String, ConversationMark>) {
        val clock = now()
        val kept = next.values.filter { clock - maxOf(it.postedAt, it.seenAt ?: 0, it.repliedAt ?: 0) < MAX_AGE_MS }
            .sortedByDescending { it.postedAt }.take(MAX_MARKS)
        marks = kept.associateBy { it.key }
        file?.let { target ->
            runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ConversationMark.serializer()), kept))
                if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
            }
        }
    }

    @Synchronized fun mark(key: String?): ConversationMark? = key?.let(marks::get)

    /** A notification for this conversation appeared or changed. */
    @Synchronized fun posted(message: CapturedMessage, clock: Long = now()) {
        if (!message.fromPerson) return
        val key = key(message.app, message.conversation ?: message.sender) ?: return
        val old = marks[key] ?: ConversationMark(key)
        val next = if (message.sender == OWNER) old.copy(repliedAt = maxOf(old.repliedAt ?: 0, clock), notificationKey = message.notificationKey ?: old.notificationKey)
            else old.copy(postedAt = clock, notificationKey = message.notificationKey ?: old.notificationKey)
        save(marks + (key to next))
    }

    /** The notification with [notificationKey] left the shade because the owner or its app took it away. */
    @Synchronized fun removed(app: String, notificationKey: String, clock: Long = now()) {
        val hit = marks.values.filter { it.notificationKey == notificationKey && it.key.startsWith("$app|") }
        if (hit.isEmpty()) return
        save(marks + hit.map { it.key to it.copy(seenAt = clock) })
    }

    @Synchronized fun clear() = save(emptyMap())

    companion object {
        /** How the notification reader names the owner's own line. */
        const val OWNER = "You"
        const val MAX_MARKS = 300
        const val MAX_AGE_MS = 7 * 24 * 3_600_000L

        fun key(app: String?, conversation: String?): String? =
            if (app.isNullOrBlank() || conversation.isNullOrBlank()) null else "${app.trim()}|${conversation.trim().take(120)}"

        fun key(row: TaskSignal): String? = key(row.app, row.conversation ?: row.sender)

        /** The conversation a card waits on, from the sources the app recorded for it. */
        fun key(task: MikeTask): String? = task.sourceRefs.firstNotNullOfOrNull { ref ->
            if (ref.source in CHAT_SOURCES) key(ref.app, ref.conversation) else null
        }

        val CHAT_SOURCES = setOf("whatsapp", "message")
    }
}

/**
 * The gate in front of the model. Most chat messages are small talk the owner
 * reads and answers alone ("are you coming?"), and a card for each of them is
 * noise. So the app decides first, from the words and from the ledger:
 *
 * - a message with something to hold on to (a time, an amount, a link, an ask,
 *   or real length) goes to the model at once;
 * - a light message the owner already saw or answered is dropped, unread by any model;
 * - a light message nobody opened waits, and goes to the model only once it
 *   has sat unanswered for [UNANSWERED_MS] outside quiet hours.
 *
 * The word lists are a deliberate, plain heuristic. A miss costs one unmade
 * card for something the owner already read; a false pass costs one model look.
 */
object SignalTriage {
    enum class Verdict { PASS, HOLD, DROP }

    const val UNANSWERED_MS = 3 * 3_600_000L
    const val GROUP_SETTLE_MS = 30 * 60_000L
    const val LONG_TEXT = 140

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val TIME = Regex(
        "(?i)$B(מחר|מחרתיים|הערב|הלילה|בבוקר|בצהריים|בערב|השבוע|שבוע הבא|חודש הבא|ביום (ראשון|שני|שלישי|רביעי|חמישי|שישי)|בשבת|[הבלמ]?פגישה|[הבלמ]?תור|[הבלמ]?מועד|" +
            "tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week|deadline|appointment|meeting|due)$E",
    )
    private val ASK = Regex(
        "(?i)$B(תקנ[הי]|תביא[י]?|תזכור|תזכרי|אל תשכח[י]?|לא לשכוח|תשלח[י]?|תתקשר[י]?|תזמין|תזמיני|תשלם|תשלמי|תאסוף|תאספי|תבדוק|תבדקי|תכין|תכיני|" +
            "צריך ל\\p{L}+|צריכה ל\\p{L}+|חייב(ים)? ל\\p{L}+|תוכלי?|הבטחת|להזכיר|" +
            "can you|could you|don't forget|do not forget|remember to|need you to|pick up|please (send|call|buy|pay|bring|check))$E",
    )
    private val MONEY = Regex("₪|\\$|€|ש\"ח|ש״ח|${B}שקל(ים)?$E")
    private val LINK = Regex("(?i)https?://|www\\.")
    private val DIGIT = Regex("\\d")

    /** True when [text] holds something a card could be built on. */
    fun substantive(text: String): Boolean = text.length >= LONG_TEXT || DIGIT.containsMatchIn(text) || LINK.containsMatchIn(text) ||
        MONEY.containsMatchIn(text) || TIME.containsMatchIn(text) || ASK.containsMatchIn(text)

    /** Only a person's chat conversation is judged here. Mail, calendar, Mike chats and app notices pass as before. */
    fun judged(row: TaskSignal): Boolean = row.reason == SignalRows.PERSON_MESSAGE && row.source in ConversationLedger.CHAT_SOURCES

    fun verdict(row: TaskSignal, mark: ConversationMark?, now: Long, quiet: Boolean): Verdict {
        if (!judged(row)) return Verdict.PASS
        val theirs = row.items.filter { it.sender != ConversationLedger.OWNER }
        if (row.items.isNotEmpty() && theirs.isEmpty()) return Verdict.DROP
        if (theirs.any { substantive(it.text) } || substantive(row.text)) return Verdict.PASS
        if (mark?.handled == true) return Verdict.DROP
        if (row.group) return if (now - row.touchedAt >= GROUP_SETTLE_MS) Verdict.DROP else Verdict.HOLD
        val last = theirs.maxOfOrNull { it.at } ?: row.at
        return if (now - last >= UNANSWERED_MS && !quiet) Verdict.PASS else Verdict.HOLD
    }

    /** What the app tells the model about a row it lets through. Null when there is nothing to add. */
    fun note(row: TaskSignal, mark: ConversationMark?, now: Long): String? {
        if (!judged(row)) return null
        if (mark?.handled == true) return "owner_saw"
        val last = row.items.filter { it.sender != ConversationLedger.OWNER }.maxOfOrNull { it.at } ?: return null
        return if (now - last >= UNANSWERED_MS) "unopened_${(now - last) / 3_600_000L}h" else null
    }
}

/**
 * When a card leaves the board on its own. A board that only grows stops being
 * read, so every open card has an end the app enforces:
 *
 * - an undecided card that only waits for the owner's reply closes when the
 *   owner opens that conversation, or after [REPLY_LIFETIME_MS];
 * - a suggestion nobody decided on expires [PROPOSAL_LIFETIME_MS] after its
 *   last change, unless it names its own `usefulUntil` or a later deadline;
 * - a card the owner took ("mine") is reminded at its check time, and expires
 *   [MINE_GRACE_MS] after the later of that check and its deadline. Setting a
 *   new reminder keeps it.
 *
 * Closed cards stay in history and can be restored. Work Mike is carrying out
 * is never closed here.
 */
object CardLifecycle {
    const val REPLY_LIFETIME_MS = 6 * 3_600_000L
    const val PROPOSAL_LIFETIME_MS = 48 * 3_600_000L
    const val MINE_GRACE_MS = 24 * 3_600_000L
    const val SEEN = "The conversation was opened after this card was made."
    const val REPLY_EXPIRED = "Nothing was decided while a reply still mattered."
    const val PROPOSAL_EXPIRED = "Nobody decided on this suggestion in time."
    const val MINE_EXPIRED = "The reminder passed and no new one was set."

    /** [task] as it should now be, or null when nothing changes. */
    fun close(task: MikeTask, now: Long, handledAfter: (conversation: String, since: Long) -> Boolean): MikeTask? {
        if (task.category != TaskCategory.TASK || task.status.closed || task.status.executing) return null
        fun closed(status: TaskStatus, why: String) = task.copy(status = status, resolvedEvidence = task.resolvedEvidence ?: why,
            closedAt = now, updatedAt = now)
        // Once the owner takes a reply card it follows the "mine" rule, so a reminder they asked for survives.
        if (task.awaitsReply && task.status.undecided) {
            val conversation = ConversationLedger.key(task)
            if (conversation != null && handledAfter(conversation, task.createdAt)) return closed(TaskStatus.LOOKS_RESOLVED, SEEN)
            if (task.status == TaskStatus.PROPOSED && now - task.createdAt >= REPLY_LIFETIME_MS && (task.commitment?.nextCheckAt ?: 0) <= now)
                return closed(TaskStatus.EXPIRED, REPLY_EXPIRED)
        }
        return when (task.status) {
            TaskStatus.PROPOSED -> {
                val end = task.usefulUntil ?: maxOf(task.updatedAt + PROPOSAL_LIFETIME_MS, task.deadlineAt ?: 0, task.commitment?.nextCheckAt ?: 0)
                if (end <= now) closed(TaskStatus.EXPIRED, PROPOSAL_EXPIRED) else null
            }
            TaskStatus.MINE -> {
                val anchor = maxOf(task.commitment?.nextCheckAt ?: ((task.decision?.at ?: task.updatedAt) + 24 * 3_600_000L),
                    task.deadlineAt ?: 0, task.commitment?.deadlineAt ?: 0)
                if (now - anchor >= MINE_GRACE_MS) closed(TaskStatus.EXPIRED, MINE_EXPIRED) else null
            }
            else -> null
        }
    }
}
