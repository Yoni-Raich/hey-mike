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

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What Mike keeps after the raw text is gone: short, attributed notes.
 *
 * There is no fixed lifetime. Memory is compacted the way a long chat is:
 * the Librarian folds detail notes into summaries and profiles, and retires
 * what nothing uses. A size budget sets how hard it compacts. Retiring is
 * soft for [TRASH_MS], so a night's upkeep can be undone.
 *
 * Usage is the evidence for what is needed: every recall and every card that
 * cites a note counts, so upkeep keeps what is used and not what a model
 * guesses is important.
 */
class MemoryBook(
    private val store: TaskBoardStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<MemoryNote>>(emptyList())
    /** Live notes and the trash. */
    val notes: StateFlow<List<MemoryNote>> = mutable.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load() = lock.withLock {
        if (loaded) return@withLock
        mutable.value = store.loadNotes()
        loaded = true
    }
    suspend fun refresh() = lock.withLock { mutable.value = store.loadNotes() }

    val live: List<MemoryNote> get() = mutable.value.filter { it.retiredAt == null && it.supersededBy == null }

    /** Characters held by live notes against [BUDGET_CHARS]: above 1 upkeep must compact before anything else. */
    fun pressure(): Double = live.sumOf { it.text.length + (it.cue?.length ?: 0) } / BUDGET_CHARS.toDouble()

    suspend fun remember(
        subject: String,
        kind: String,
        text: String,
        source: String,
        sender: String? = null,
        cue: String? = null,
        at: Long? = null,
        linkedTaskIds: List<String> = emptyList(),
        level: String = "detail",
        recorderSessionId: String? = null,
        recorderRole: String? = null,
        signalIds: List<String> = emptyList(),
        sourceRefs: List<TaskSourceRef> = emptyList(),
    ): MemoryNote = lock.withLock {
        val cleanText = text.trim().take(MAX_TEXT)
        require(cleanText.length >= 3) { "A note needs a line of text." }
        val cleanSubject = subject.trim().take(80).ifEmpty { error("Name who or what the note is about.") }
        require(kind in KINDS) { "kind is one of ${KINDS.joinToString()}." }
        require(level in LEVELS) { "level is one of ${LEVELS.joinToString()}." }
        val provenance = MemoryProvenance(source.trim().take(60).ifEmpty { "chat" }, sender?.trim()?.take(80),
            at ?: now(), recorderSessionId, recorderRole, signalIds.distinct().take(32).map { it.take(120) })
        live.firstOrNull { it.subject.equals(cleanSubject, true) && it.text.equals(cleanText, true) }?.let { existing ->
            val merged = existing.copy(linkedTaskIds = (existing.linkedTaskIds + linkedTaskIds).distinct(),
                provenance = (origins(existing) + provenance).distinct().takeLast(MAX_PROVENANCE),
                sourceRefs = (existing.sourceRefs + sourceRefs).distinct())
            if (merged != existing) save(listOf(merged))
            return@withLock merged
        }
        require(live.size < MAX_NOTES) { "Memory is full. The Librarian compacts it tonight; keep only what matters." }
        val note = MemoryNote(
            id = newId(), subject = cleanSubject, kind = kind, text = cleanText,
            cue = cue?.trim()?.take(MAX_CUE)?.takeIf { it.isNotEmpty() },
            source = source.trim().take(60).ifEmpty { "chat" }, sender = sender?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            at = at ?: now(), level = level, linkedTaskIds = linkedTaskIds.distinct(),
            provenance = listOf(provenance),
            sourceRefs = sourceRefs.distinct(),
        )
        save(listOf(note))
        note
    }

    /**
     * Notes about [subject] or matching [query], best first. Each returned
     * note is marked used: that is what keeps it through upkeep.
     */
    suspend fun recall(query: String?, subject: String? = null, limit: Int = 12, markUsed: Boolean = true,
        allowed: (MemoryNote) -> Boolean = { true }): List<MemoryNote> = lock.withLock {
        val words = words(query.orEmpty())
        val scored = live.filter(allowed).mapNotNull { note ->
            val subjectHit = subject != null && note.subject.equals(subject.trim(), true)
            val haystack = words("${note.subject} ${note.text} ${note.cue.orEmpty()} ${note.sender.orEmpty()}")
            val overlap = words.count { it in haystack }
            if (!subjectHit && overlap == 0 && !(words.isEmpty() && subject == null)) null
            else note to (if (subjectHit) 10 else 0) + overlap * 3 + (if (note.level == "profile") 2 else 0) + if (note.pinned) 1 else 0
        }.sortedWith(compareByDescending<Pair<MemoryNote, Int>> { it.second }.thenByDescending { it.first.at })
            .take(limit.coerceIn(1, 40)).map { it.first }
        if (markUsed && scored.isNotEmpty()) {
            val clock = now()
            save(scored.map { it.copy(useCount = it.useCount + 1, lastUsedAt = clock) })
        }
        scored.map { found -> mutable.value.first { it.id == found.id } }
    }

    /** Context actually injected into an owner turn counts as retrieval, even without a recall call. */
    suspend fun used(ids: Collection<String>) = lock.withLock {
        save(live.filter { it.id in ids }.map { it.copy(useCount = it.useCount + 1, lastUsedAt = now()) })
    }

    /** Note that cards cite these notes. Linked notes are never compacted away while the card is open. */
    suspend fun link(ids: Collection<String>, taskId: String) = lock.withLock {
        val clock = now()
        val changed = mutable.value.filter { it.id in ids }.map {
            it.copy(linkedTaskIds = (it.linkedTaskIds + taskId).distinct(), useCount = it.useCount + 1, lastUsedAt = clock)
        }
        if (changed.isNotEmpty()) save(changed)
    }

    /**
     * Fold [ids] into one note at [level]. The originals are superseded, not
     * deleted, until the trash empties.
     */
    suspend fun merge(ids: List<String>, subject: String, text: String, level: String, openTaskIds: Set<String>): MemoryNote = lock.withLock {
        require(ids.distinct().size >= 2) { "Merge at least two different notes." }
        require(level in setOf("summary", "profile")) { "Merge into a summary or a profile." }
        val originals = ids.distinct().map { id -> live.firstOrNull { it.id == id } ?: error("No live note has id $id.") }
        require(originals.none { it.pinned }) { "Pinned notes cannot be compacted." }
        require(originals.none { note -> note.linkedTaskIds.any { it in openTaskIds } }) { "Notes linked to an open task cannot be compacted." }
        val rank = listOf("detail", "summary", "profile")
        require(originals.none { rank.indexOf(it.level) > rank.indexOf(level) }) { "Compaction cannot turn a profile back into a summary." }
        val clock = now()
        val merged = MemoryNote(
            id = newId(), subject = subject.trim().take(80).ifEmpty { originals.first().subject },
            kind = if (level == "profile") "profile" else "summary",
            text = text.trim().take(MAX_TEXT).also { require(it.length >= 3) { "Give the merged text." } },
            source = "memory", at = originals.maxOf { it.at }, level = level, compactedFrom = originals.map { it.id },
            linkedTaskIds = originals.flatMap { it.linkedTaskIds }.distinct(),
            provenance = originals.flatMap(::origins).distinct().takeLast(MAX_PROVENANCE),
            pinned = originals.any { it.pinned },
            sourceRefs = originals.flatMap { it.sourceRefs }.distinct(),
            useCount = originals.sumOf { it.useCount }, lastUsedAt = originals.mapNotNull { it.lastUsedAt }.maxOrNull(),
        )
        save(originals.map { it.copy(supersededBy = merged.id, retiredAt = clock, retiredReason = "compacted") } + merged)
        merged
    }

    /** Retire notes nothing needs. Pinned notes and notes an open card cites stay. */
    suspend fun retire(ids: List<String>, reason: String, openTaskIds: Set<String>): List<String> = lock.withLock {
        val clock = now()
        val refusals = mutableListOf<String>()
        val retired = ids.distinct().mapNotNull { id ->
            val note = live.firstOrNull { it.id == id } ?: return@mapNotNull null.also { refusals += "$id: not live" }
            when {
                note.pinned -> null.also { refusals += "$id: pinned by the user" }
                note.linkedTaskIds.any { it in openTaskIds } -> null.also { refusals += "$id: an open card cites it" }
                else -> note.copy(retiredAt = clock, retiredReason = reason.trim().take(120).ifEmpty { "not needed" })
            }
        }
        save(retired)
        refusals
    }

    suspend fun setPinned(id: String, pinned: Boolean) = edit(id) { it.copy(pinned = pinned) }

    suspend fun editText(id: String, text: String) = edit(id) { it.copy(text = text.trim().take(MAX_TEXT)) }

    /** The user's own delete: immediate, no trash. */
    suspend fun forget(ids: Collection<String>) = lock.withLock {
        forgetLocked(ids)
    }

    private suspend fun forgetLocked(ids: Collection<String>): Int {
        val forgotten = ids.toMutableSet()
        do {
            val before = forgotten.size
            mutable.value.filter { it.id in forgotten || it.compactedFrom.any(forgotten::contains) || it.supersededBy in forgotten || it.sourceRefs.any { ref -> ref.noteId in forgotten } }
                .forEach { forgotten += it.id; forgotten += it.compactedFrom; it.supersededBy?.let(forgotten::add) }
        } while (forgotten.size != before)
        store.forgetNotes(forgotten)
        mutable.value = mutable.value.filterNot { it.id in forgotten }
        return forgotten.size
    }

    /** "Forget everything about X": every note with that subject, now. */
    suspend fun forgetSubject(subject: String): Int = lock.withLock {
        val ids = mutable.value.filter { it.subject.equals(subject.trim(), true) }.map { it.id }
        forgetLocked(ids)
    }

    /** Empty the trash. Retired notes older than [TRASH_MS] are deleted for good. */
    suspend fun emptyTrash(): Int = lock.withLock {
        val clock = now()
        val gone = mutable.value.filter { it.retiredAt != null && clock - it.retiredAt > TRASH_MS }.map { it.id }
        if (gone.isNotEmpty()) {
            store.deleteNotes(gone)
            mutable.value = mutable.value.filterNot { it.id in gone }
        }
        gone.size
    }

    /** Put back every note as it was, for undoing a night's upkeep. */
    suspend fun restore(snapshot: List<MemoryNote>) = lock.withLock {
        store.replaceNotes(snapshot)
        mutable.value = snapshot
    }

    private suspend fun edit(id: String, change: (MemoryNote) -> MemoryNote) = lock.withLock {
        val note = mutable.value.firstOrNull { it.id == id } ?: error("No note has id $id.")
        save(listOf(change(note)))
    }

    private suspend fun save(changed: List<MemoryNote>) {
        if (changed.isEmpty()) return
        store.saveNotes(changed)
        val ids = changed.map { it.id }.toSet()
        mutable.value = mutable.value.filterNot { it.id in ids } + changed
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    private fun origins(note: MemoryNote): List<MemoryProvenance> = note.provenance.ifEmpty {
        listOf(MemoryProvenance(note.source, note.sender, note.at))
    }

    companion object {
        val KINDS = setOf("request", "promise", "date", "decision", "preference", "fact", "summary", "profile")
        val LEVELS = setOf("detail", "summary", "profile")
        const val MAX_TEXT = 400
        const val MAX_CUE = 200
        const val MAX_PROVENANCE = 24
        const val MAX_NOTES = 3_000
        const val BUDGET_CHARS = 120_000
        const val TRASH_MS = 7 * 24 * 3_600_000L
    }
}
