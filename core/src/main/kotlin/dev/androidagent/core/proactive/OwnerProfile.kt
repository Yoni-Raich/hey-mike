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

package dev.androidagent.core.proactive

import dev.androidagent.core.TaskBoard
import dev.androidagent.core.TaskBoardSettings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ProfileKind {
    PERSON, CONVERSATION, SOURCE, TOPIC;
    val wire: String get() = name.lowercase()
    companion object { fun parse(text: String?): ProfileKind? = entries.firstOrNull { it.wire == text?.trim()?.lowercase() } }
}

/** Who wrote an entry. The owner's entries outrank learned ones and nothing learned can change them. */
@Serializable
enum class ProfileOrigin { OWNER, LEARNED }

/**
 * One thing about what matters to the user. [weight] runs from -2 (mute)
 * through 0 (normal) to +2 (star). Learned entries stay within -1..+1: only
 * the owner can mute or star.
 */
@Serializable
data class ProfileEntry(
    val id: String,
    val kind: ProfileKind,
    val key: String,
    val label: String,
    val weight: Int,
    val alwaysNotify: Boolean = false,
    val why: String? = null,
    val origin: ProfileOrigin,
    /** Event or feedback ids that support a learned entry; the owner message event for an owner entry said in chat. */
    val evidence: List<String> = emptyList(),
    val updatedAt: Long,
    /** How many alerts or signals this entry has matched. */
    val hits: Int = 0,
    /** The background run that wrote a learned entry, for undo. */
    val runId: String? = null,
    val previousWeight: Int? = null,
    val previousWhy: String? = null,
    val previousRunId: String? = null,
)

/** How and when Mike may reach the user. */
@Serializable
data class AlertPrefs(
    val enabled: Boolean = true,
    /** Minutes after local midnight. Start after end means the quiet time crosses midnight. */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 7 * 60,
    val digestMinutes: List<Int> = listOf(8 * 60, 18 * 60),
    val urgentPerDay: Int = 3,
    val noticePerDay: Int = 10,
    /** Off by default: an urgent alert vibrates and shows, but does not ring unless the user asks for it. */
    val urgentSound: Boolean = false,
    val conversationCooldownMs: Long = 30 * 60_000L,
) {
    fun quietAt(at: Long, zone: ZoneId): Boolean {
        if (quietStartMinute == quietEndMinute) return false
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return if (quietStartMinute < quietEndMinute) minute in quietStartMinute until quietEndMinute
        else minute >= quietStartMinute || minute < quietEndMinute
    }

    /** The next digest time strictly after [after]. Null when the digest has no times. */
    fun nextDigestAt(after: Long, zone: ZoneId): Long? {
        val minutes = digestMinutes.filter { it in 0 until 24 * 60 }.distinct().sorted()
        if (minutes.isEmpty()) return null
        val local = Instant.ofEpochMilli(after).atZone(zone)
        for (dayOffset in 0..1) {
            val day = local.toLocalDate().plusDays(dayOffset.toLong())
            minutes.forEach { minute ->
                val at = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
                if (at > after) return at
            }
        }
        return null
    }

    fun normalized(): AlertPrefs = copy(
        quietStartMinute = quietStartMinute.coerceIn(0, 24 * 60 - 1),
        quietEndMinute = quietEndMinute.coerceIn(0, 24 * 60 - 1),
        digestMinutes = digestMinutes.filter { it in 0 until 24 * 60 }.distinct().sorted().take(4),
        urgentPerDay = urgentPerDay.coerceIn(0, 20),
        noticePerDay = noticePerDay.coerceIn(0, 50),
        conversationCooldownMs = conversationCooldownMs.coerceIn(0, 6 * 3_600_000L),
    )
}

@Serializable
data class OwnerProfile(
    val version: Int = 1,
    val revision: Long = 0,
    val entries: List<ProfileEntry> = emptyList(),
    val alerts: AlertPrefs = AlertPrefs(),
)

/** What the profile says about one message, alert or conversation. */
data class ProfileMatch(val entries: List<ProfileEntry> = emptyList()) {
    private val owner get() = entries.filter { it.origin == ProfileOrigin.OWNER }
    private val learned get() = entries.filter { it.origin == ProfileOrigin.LEARNED }
    val muted: ProfileEntry? get() = owner.firstOrNull { it.weight <= -2 }
    val starred: Boolean get() = owner.any { it.weight >= 2 }
    val alwaysNotify: Boolean get() = owner.any { it.alwaysNotify }
    /** The lowest learned weight about something the owner did not decide themselves. */
    val learnedLow: ProfileEntry? get() = learned.filter { l -> owner.none { it.kind == l.kind && it.key == l.key } }
        .filter { it.weight <= -1 }.minByOrNull { it.weight }
}

object ProfileKeys {
    fun person(name: String): String = name.lowercase().replace(Regex("[\\s\\u200e\\u200f\\u202a-\\u202e]+"), " ").trim().take(80)
    fun conversation(app: String, conversation: String): String = "${app.trim()}|${conversation.trim()}".take(200)
    fun source(app: String): String = app.trim().take(120)
    fun topic(text: String): String = TaskBoard.topicKey(text)
    const val ANY_APP = "*"

    fun of(kind: ProfileKind, raw: String): String = when (kind) {
        ProfileKind.PERSON -> person(raw)
        // A chat name without an app matches that chat in every app.
        ProfileKind.CONVERSATION -> raw.trim().let { if ('|' in it) it else "$ANY_APP|$it" }.take(200)
        ProfileKind.SOURCE -> source(raw)
        ProfileKind.TOPIC -> topic(raw)
    }
}

/**
 * The owner profile on disk: a small versioned JSON file with one writer.
 * A failed write leaves the previous file whole.
 */
class OwnerProfileStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var state: OwnerProfile = load()

    private fun load(): OwnerProfile = try {
        if (file.exists()) json.decodeFromString(OwnerProfile.serializer(), file.readText()).takeIf { it.version == 1 } ?: OwnerProfile()
        else OwnerProfile()
    } catch (_: Exception) { OwnerProfile() }

    val profile: OwnerProfile get() = state

    @Synchronized
    private fun save(next: OwnerProfile): OwnerProfile {
        val written = next.copy(revision = state.revision + 1, entries = next.entries.sortedWith(compareBy({ it.kind.ordinal }, { -it.weight }, { it.label })).take(MAX_ENTRIES))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(OwnerProfile.serializer(), written))
        if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) { "Could not save the profile." } }
        state = written
        return written
    }

    fun match(app: String?, conversation: String?, sender: String?, topic: String?, group: Boolean = false): ProfileMatch {
        val keys = buildSet {
            sender?.takeIf { it.isNotBlank() }?.let { add(ProfileKind.PERSON to ProfileKeys.person(it)) }
            if (!group) conversation?.takeIf { it.isNotBlank() }?.let { add(ProfileKind.PERSON to ProfileKeys.person(it)) }
            if (app != null && !conversation.isNullOrBlank()) add(ProfileKind.CONVERSATION to ProfileKeys.conversation(app, conversation))
            if (!conversation.isNullOrBlank()) add(ProfileKind.CONVERSATION to ProfileKeys.conversation(ProfileKeys.ANY_APP, conversation))
            app?.takeIf { it.isNotBlank() }?.let { add(ProfileKind.SOURCE to ProfileKeys.source(it)) }
            topic?.takeIf { it.isNotBlank() }?.let { add(ProfileKind.TOPIC to ProfileKeys.topic(it)) }
        }
        return ProfileMatch(state.entries.filter { (it.kind to it.key) in keys })
    }

    /** The owner's own entry. Weight 0 without always-notify removes the owner's say and leaves learned entries alone. */
    @Synchronized
    fun setOwner(kind: ProfileKind, raw: String, label: String?, weight: Int, alwaysNotify: Boolean = false,
        why: String? = null, evidence: List<String> = emptyList()): OwnerProfile {
        val key = ProfileKeys.of(kind, raw)
        require(key.isNotEmpty()) { "Name the person, conversation, app or topic." }
        val clean = weight.coerceIn(-2, 2)
        val rest = state.entries.filterNot { it.kind == kind && it.key == key && it.origin == ProfileOrigin.OWNER }
        if (clean == 0 && !alwaysNotify) return save(state.copy(entries = rest))
        // The owner's word replaces a learned guess about the same thing.
        val withoutLearned = rest.filterNot { it.kind == kind && it.key == key && it.origin == ProfileOrigin.LEARNED }
        val entry = ProfileEntry(UUID.randomUUID().toString(), kind, key, (label ?: raw).trim().take(80), clean,
            alwaysNotify = alwaysNotify && clean >= 0, why = why?.trim()?.take(200), origin = ProfileOrigin.OWNER,
            evidence = evidence.take(8), updatedAt = now())
        return save(state.copy(entries = withoutLearned + entry))
    }

    /** A learned entry. Refused when the owner already decided this key. */
    @Synchronized
    fun learn(kind: ProfileKind, raw: String, label: String?, weight: Int, why: String, evidence: List<String>, runId: String?): OwnerProfile {
        val key = ProfileKeys.of(kind, raw)
        require(key.isNotEmpty()) { "Name the person, conversation, app or topic." }
        require(evidence.isNotEmpty()) { "A learned entry needs the feedback or decision ids that support it." }
        require(why.isNotBlank()) { "Say why in one line." }
        require(state.entries.none { it.kind == kind && it.key == key && it.origin == ProfileOrigin.OWNER }) {
            "The owner already set this ${kind.wire}; learned entries cannot change it."
        }
        val previous = state.entries.firstOrNull { it.kind == kind && it.key == key && it.origin == ProfileOrigin.LEARNED }
        val clean = weight.coerceIn(-1, 1)
        val rest = state.entries.filterNot { it === previous }
        if (clean == 0) return save(state.copy(entries = rest))
        val entry = ProfileEntry(previous?.id ?: UUID.randomUUID().toString(), kind, key, (label ?: previous?.label ?: raw).trim().take(80), clean,
            why = why.trim().take(200), origin = ProfileOrigin.LEARNED, evidence = (evidence + previous?.evidence.orEmpty()).distinct().take(12),
            updatedAt = now(), hits = previous?.hits ?: 0, runId = runId, previousWeight = previous?.weight, previousWhy = previous?.why, previousRunId = previous?.runId)
        return save(state.copy(entries = rest + entry))
    }

    /** Undo what one background run learned: back to the value it replaced, or gone. */
    @Synchronized
    fun undoRun(runId: String): OwnerProfile {
        val touched = state.entries.filter { it.origin == ProfileOrigin.LEARNED && it.runId == runId }
        if (touched.isEmpty()) return state
        val restored = touched.mapNotNull { e -> e.previousWeight?.let { e.copy(weight = it, why = e.previousWhy, runId = e.previousRunId, previousWeight = null, previousWhy = null, previousRunId = null) } }
        return save(state.copy(entries = state.entries - touched.toSet() + restored))
    }

    /** "Wrong": the owner rejects a learned entry. It becomes an owner entry at normal weight, which also blocks relearning it. */
    @Synchronized
    fun reject(id: String): OwnerProfile {
        val entry = state.entries.firstOrNull { it.id == id } ?: return state
        if (entry.origin == ProfileOrigin.OWNER) return save(state.copy(entries = state.entries - entry))
        val owner = entry.copy(id = UUID.randomUUID().toString(), weight = 0, origin = ProfileOrigin.OWNER, why = "Owner rejected: ${entry.why.orEmpty()}".take(200),
            runId = null, previousWeight = null, previousWhy = null, previousRunId = null, updatedAt = now())
        return save(state.copy(entries = state.entries - entry + owner))
    }

    @Synchronized
    fun remove(id: String): OwnerProfile = save(state.copy(entries = state.entries.filterNot { it.id == id }))

    @Synchronized
    fun setAlerts(change: (AlertPrefs) -> AlertPrefs): OwnerProfile = save(state.copy(alerts = change(state.alerts).normalized()))

    @Synchronized
    fun touch(ids: Collection<String>) {
        if (ids.isEmpty()) return
        save(state.copy(entries = state.entries.map { if (it.id in ids) it.copy(hits = it.hits + 1) else it }))
    }

    /** Excluded sources take their learned entries with them. Owner entries stay. */
    @Synchronized
    fun pruneExcluded(settings: TaskBoardSettings): OwnerProfile {
        val gone = state.entries.filter { e ->
            e.origin == ProfileOrigin.LEARNED && when (e.kind) {
                ProfileKind.SOURCE -> e.key in settings.excludedApps
                ProfileKind.CONVERSATION -> e.key in settings.excludedConversations || e.key.substringBefore('|') in settings.excludedApps
                else -> false
            }
        }
        return if (gone.isEmpty()) state else save(state.copy(entries = state.entries - gone.toSet()))
    }

    /** "Delete collected input" also clears what was learned from it. */
    @Synchronized
    fun forgetLearned(): OwnerProfile = save(state.copy(entries = state.entries.filter { it.origin == ProfileOrigin.OWNER }))

    /** A short block for Scout and Manager prompts. Data from the owner's settings, not instructions from anyone else. */
    fun summary(now: Long, zone: ZoneId, limit: Int = 1_800): String = buildString {
        val p = state
        val owner = p.entries.filter { it.origin == ProfileOrigin.OWNER }
        val learned = p.entries.filter { it.origin == ProfileOrigin.LEARNED }
        fun line(e: ProfileEntry) = "${e.kind.wire}:${e.label}" + (if (e.alwaysNotify) " (always tell)" else "") + (e.why?.let { " — $it" } ?: "")
        append("Owner profile (what matters to the user; owner entries outrank learned ones). ")
        append(if (p.alerts.quietAt(now, zone)) "Quiet hours now. " else "Not quiet hours. ")
        owner.filter { it.weight >= 1 || it.alwaysNotify }.takeIf { it.isNotEmpty() }?.let { append("Important to the owner: ${it.take(15).joinToString("; ", transform = ::line)}. ") }
        owner.filter { it.weight <= -1 }.takeIf { it.isNotEmpty() }?.let { append("Owner said not important: ${it.take(15).joinToString("; ", transform = ::line)}. ") }
        learned.filter { it.weight > 0 }.takeIf { it.isNotEmpty() }?.let { append("Learned important: ${it.take(10).joinToString("; ", transform = ::line)}. ") }
        learned.filter { it.weight < 0 }.takeIf { it.isNotEmpty() }?.let { append("Learned less important: ${it.take(10).joinToString("; ", transform = ::line)}.") }
    }.take(limit)

    companion object { const val MAX_ENTRIES = 300 }
}
