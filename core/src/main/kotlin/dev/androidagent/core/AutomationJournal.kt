package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When each rule last fired, and how often today.
 *
 * Separate from the rules themselves because it is the one part that must
 * survive a process death: a phone that forgets a rule already ran is a phone
 * that posts the same thing twice, and the alarm that woke it is long gone by
 * the time anyone notices.
 *
 * Reading is deliberately split from recording. [AutomationEvaluator] only
 * reads, so a dry run cannot consume a rule's daily quota — which is what
 * makes `mode:"test"` safe to call as often as the model likes.
 */
interface AutomationHistory {
    fun lastFiredAt(ruleId: String): Long?
    fun firedOn(ruleId: String, date: LocalDate): Int

    /** Called by the host once the rule has actually fired, never by the evaluator. */
    fun record(ruleId: String, at: ZonedDateTime)
}

/** An [AutomationHistory] that forgets at process exit. For tests and dry runs. */
class InMemoryAutomationHistory(private val zone: ZoneId = ZoneId.systemDefault()) : AutomationHistory {
    private val fires = mutableMapOf<String, MutableList<Long>>()

    override fun lastFiredAt(ruleId: String): Long? = fires[ruleId]?.maxOrNull()

    override fun firedOn(ruleId: String, date: LocalDate): Int =
        fires[ruleId].orEmpty().count { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date }

    override fun record(ruleId: String, at: ZonedDateTime) {
        fires.getOrPut(ruleId) { mutableListOf() } += at.toInstant().toEpochMilli()
    }
}

/**
 * The durable journal, one file beside the rules.
 *
 * Only timestamps are kept. What the notification said, where the phone was and
 * what the agent replied are not this file's business, and a log of them would
 * quietly become the most sensitive file on the device.
 */
class AutomationJournal(
    private val file: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AutomationHistory {

    private val fires: MutableMap<String, MutableList<Long>> by lazy { load() }

    override fun lastFiredAt(ruleId: String): Long? = synchronized(this) { fires[ruleId]?.maxOrNull() }

    override fun firedOn(ruleId: String, date: LocalDate): Int = synchronized(this) {
        fires[ruleId].orEmpty().count { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date }
    }

    override fun record(ruleId: String, at: ZonedDateTime) = synchronized(this) {
        val stamps = fires.getOrPut(ruleId) { mutableListOf() }
        stamps += at.toInstant().toEpochMilli()
        // A day's worth is all any guard asks about. Trimming here keeps the
        // file bounded without a separate sweep nobody would remember to run.
        val cutoff = at.toInstant().toEpochMilli() - RETAINED_MS
        stamps.retainAll { it >= cutoff }
        if (stamps.isEmpty()) fires.remove(ruleId)
        save()
    }

    /** Every rule the journal knows about, for a status line. */
    fun tracked(): Set<String> = synchronized(this) { fires.keys.toSet() }

    private fun load(): MutableMap<String, MutableList<Long>> {
        if (!file.isFile) return mutableMapOf()
        val text = runCatching { file.readText() }.getOrNull() ?: return mutableMapOf()
        if (text.length > MAX_FILE_CHARS) return mutableMapOf()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return mutableMapOf()
        val entries = runCatching { json["fires"]!!.jsonObject }.getOrNull() ?: return mutableMapOf()
        val loaded = mutableMapOf<String, MutableList<Long>>()
        for ((ruleId, element) in entries) {
            val stamps = runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.longOrNull } }.getOrNull()
                ?: continue
            if (stamps.isNotEmpty()) loaded[ruleId] = stamps.takeLast(MAX_PER_RULE).toMutableList()
        }
        return loaded
    }

    private fun save() {
        val payload = buildJsonObject {
            put("version", FORMAT_VERSION)
            put(
                "fires",
                JsonObject(
                    fires.mapValues { (_, stamps) ->
                        JsonArray(stamps.takeLast(MAX_PER_RULE).map { kotlinx.serialization.json.JsonPrimitive(it) })
                    },
                ),
            )
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "." + file.name + ".tmp")
        runCatching {
            temp.writeText(payload.toString())
            if (!temp.renameTo(file)) {
                file.writeText(payload.toString())
                temp.delete()
            }
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "journal.json"
        private const val MAX_PER_RULE = 200
        private const val MAX_FILE_CHARS = 512 * 1024
        private const val RETAINED_MS = 48L * 60 * 60 * 1_000

        fun fileIn(homeDirectory: File): File =
            File(AutomationLibrary.directoryIn(homeDirectory), FILE_NAME)
    }
}
