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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * What the agent has worked out about an app, kept across chats.
 *
 * Nothing the agent learned used to survive. The session workspace is rewritten
 * by the seeder on every access, the bundled skills are force-replaced on every
 * app start, and both are per-session anyway — so a selector discovered in one
 * chat was invisible to the next and rediscovered from scratch.
 *
 * This lives under `homeDirectory`, which is global across chats and is the one
 * place the seeder does not touch.
 *
 * The stable key is `(package, resourceId | contentDescription)` and never a
 * coordinate. A bounds centre is invalidated by any re-render; a resource id
 * survives one. That distinction is what makes remembering worth doing at all,
 * and it is why this could not have been built before the accessibility tree
 * existed.
 *
 * Pure JVM: no Android types, so the format and the staleness rules are
 * covered by fast unit tests rather than only by running the app.
 */
class KnowledgeStore(
    private val root: File,
    /** Injectable so tests do not depend on the wall clock. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * One thing the agent knows how to do in one app.
     *
     * @param selector the durable address — a resource id, or a content
     *   description when the app exposes no id. Never a bare coordinate pair;
     *   a screen that exposes nothing addressable puts that in [hint] instead.
     * @param lastVerified when this was last seen to work. Selectors age out
     *   with app updates, and stale confidence is worse than no confidence, so
     *   this is mandatory rather than optional.
     */
    data class Record(
        val packageName: String,
        val screen: String,
        val selector: String,
        val does: String,
        val intent: String? = null,
        val fallbacks: List<String> = emptyList(),
        /**
         * Free-form context that is useful but not addressable — including
         * coordinates, which are refused as a [selector] and allowed here.
         *
         * Some screens expose no id and no description at all: a canvas, a
         * game, an unexposed WebView. Refusing to record anything about those
         * loses the partial knowledge too, so they get a place to put it, on
         * the condition that it is never mistaken for a durable address.
         */
        val hint: String? = null,
        val lastVerified: Long = 0L,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("screen", screen)
            put("selector", selector)
            put("does", does)
            intent?.let { put("intent", it) }
            hint?.let { put("hint", it) }
            if (fallbacks.isNotEmpty()) {
                put("fallbacks", JsonArray(fallbacks.map { JsonPrimitive(it) }))
            }
            put("lastVerified", lastVerified)
        }
    }

    /** Everything known about one package, newest verification first. */
    fun read(packageName: String): List<Record> {
        val file = fileFor(packageName) ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching { readExisting(file, packageName) }.getOrDefault(emptyList())
    }

    /**
     * Add or replace one record.
     *
     * Replacement is by selector, not by insertion: re-learning the same
     * selector should refresh what we believe about it rather than pile a
     * second, possibly contradictory, entry behind the first.
     *
     * @return the stored record, with `lastVerified` stamped.
     */
    fun upsert(record: Record): Record {
        val file = requireNotNull(fileFor(record.packageName)) {
            "\"${record.packageName}\" is not a valid Android package name"
        }
        validate(record)
        val stamped = record.copy(lastVerified = now())
        val existing = if (file.isFile) readExisting(file, record.packageName) else emptyList()
        val merged = (existing.filterNot { it.selector == stamped.selector } + stamped)
            .sortedByDescending { it.lastVerified }
            .take(MAX_RECORDS_PER_PACKAGE)
        file.parentFile?.mkdirs()
        val payload = buildJsonObject {
            put("package", record.packageName)
            put("version", FORMAT_VERSION)
            put("records", JsonArray(merged.map { it.toJson() }))
        }
        // Written whole and replaced, so a crash mid-write cannot leave a
        // half-parsed file that read() would silently discard.
        writeAtomically(file, payload.toString())
        return stamped
    }

    /** Packages this store holds anything for. */
    fun packages(): List<String> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.sorted()
            .orEmpty()

    /**
     * True when a record is old enough that it should be checked rather than
     * trusted. Not an expiry: a stale selector is still the best guess
     * available, it just is not evidence any more.
     */
    fun isStale(record: Record): Boolean = now() - record.lastVerified > STALE_AFTER_MS

    /**
     * A compact, bounded view for one package.
     *
     * Deliberately per-package and capped rather than a whole-store dump: the
     * store grows without limit and the prompt does not.
     */
    fun summary(packageName: String, limit: Int = DEFAULT_SUMMARY_LIMIT): JsonObject {
        val records = read(packageName).take(limit.coerceIn(1, MAX_RECORDS_PER_PACKAGE))
        return buildJsonObject {
            put("package", packageName)
            put("known", records.size)
            put(
                "records",
                JsonArray(
                    records.map { record ->
                        buildJsonObject {
                            put("screen", record.screen)
                            put("selector", record.selector)
                            put("does", record.does)
                            record.intent?.let { put("intent", it) }
                            record.hint?.let { put("hint", it) }
                            if (record.fallbacks.isNotEmpty()) {
                                put("fallbacks", JsonArray(record.fallbacks.map { JsonPrimitive(it) }))
                            }
                            // The model needs to know how much to trust this,
                            // not when it was written.
                            put("stale", isStale(record))
                        }
                    },
                ),
            )
            if (records.isEmpty()) {
                put(
                    "guidance",
                    "Nothing is known about $packageName yet. Work it out from read_ui, then " +
                        "record what worked with remember_capability so the next chat does not " +
                        "have to rediscover it.",
                )
            } else {
                put(
                    "guidance",
                    "A record marked stale:true is a hint to check, not a fact. Verify it against " +
                        "the current screen before relying on it, and re-record it once confirmed.",
                )
            }
        }
    }

    private fun recordFrom(packageName: String, json: JsonObject): Record = Record(
        packageName = packageName,
        screen = json.text("screen") ?: "",
        selector = json.text("selector") ?: error("record has no selector"),
        does = json.text("does") ?: "",
        intent = json.text("intent"),
        fallbacks = runCatching {
            json["fallbacks"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList()),
        hint = json.text("hint"),
        lastVerified = json["lastVerified"]?.jsonPrimitive?.longOrNull ?: 0L,
    )

    private fun validate(record: Record) {
        require(record.selector.isNotBlank()) { "selector is required" }
        require(record.selector.length <= MAX_FIELD_CHARS) { "selector is too long" }
        require(record.screen.length <= MAX_FIELD_CHARS) { "screen is too long" }
        require(record.does.length <= MAX_FIELD_CHARS) { "does is too long" }
        require((record.intent?.length ?: 0) <= MAX_FIELD_CHARS) { "intent is too long" }
        require((record.hint?.length ?: 0) <= MAX_FIELD_CHARS) { "hint is too long" }
        require(record.fallbacks.size <= MAX_FALLBACKS) { "too many fallbacks" }
        require(record.fallbacks.all { it.length <= MAX_FIELD_CHARS }) { "a fallback is too long" }
        // A bare coordinate pair is not a durable address: it stops being true
        // on the next render. It is refused as the key and allowed in `hint`,
        // so a screen that genuinely exposes nothing addressable can still be
        // recorded rather than lost entirely.
        //
        // The match is anchored on the whole string on purpose. An earlier
        // version accepted a coordinate pair anywhere in the selector and so
        // rejected ordinary labels: "1,234 messages", "3,000 photos" and
        // "12,5 km to destination" are contentDescriptions, not coordinates.
        require(!COORDINATE_RE.matches(record.selector.trim())) {
            "selector must be a resourceId or a contentDescription, never a bare " +
                "coordinate pair, which stops being true on the next render. Put the " +
                "coordinate in \"hint\" and use whatever label or id the screen does " +
                "expose as the selector."
        }
    }

    /**
     * Package names map to filenames directly, so anything that could escape
     * the directory is refused rather than sanitised — a sanitised path is a
     * path someone will later assume is the real package name.
     */
    private fun fileFor(packageName: String): File? {
        val trimmed = packageName.trim()
        if (!PACKAGE_RE.matches(trimmed)) return null
        return File(root, trimmed.lowercase(Locale.ROOT) + SUFFIX)
    }

    /** Strict reads protect an existing cache from being replaced after parse failure. */
    private fun readExisting(file: File, packageName: String): List<Record> {
        val text = file.readText()
        require(text.length <= MAX_FILE_CHARS) { "knowledge file is too large" }
        val parsed = Json.parseToJsonElement(text).jsonObject
        val records = parsed["records"]?.jsonArray ?: error("knowledge file has no records array")
        return records.map { element -> recordFrom(packageName, element.jsonObject) }
            .sortedByDescending { it.lastVerified }
    }

    private fun writeAtomically(file: File, text: String) {
        require(text.length <= MAX_FILE_CHARS) { "knowledge file is too large" }
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val FORMAT_VERSION = 1

        /** Directory name under `homeDirectory`. */
        const val DIRECTORY = "knowledge"

        private const val SUFFIX = ".json"

        /** Two months. Long enough to survive a quiet app, short enough to catch a redesign. */
        const val STALE_AFTER_MS = 60L * 24 * 60 * 60 * 1_000

        const val MAX_RECORDS_PER_PACKAGE = 60
        const val DEFAULT_SUMMARY_LIMIT = 12
        private const val MAX_FIELD_CHARS = 400
        /** Also the schema's `maxItems`, so the tool advertises the limit it enforces. */
        internal const val MAX_FALLBACKS = 8
        private const val MAX_FILE_CHARS = 512 * 1024

        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        /**
         * A selector that is nothing but a coordinate pair, optionally
         * bracketed. Anchored to the whole string, because a label that merely
         * contains digits and a comma is an ordinary label.
         */
        private val COORDINATE_RE =
            Regex("[\\[(]?\\s*\\d{1,5}\\s*[,;]\\s*\\d{1,5}\\s*[\\])]?")

        /** Where the store lives for a given runtime home. */
        fun directoryIn(homeDirectory: File): File = File(homeDirectory, DIRECTORY)
    }
}
