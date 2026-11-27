package dev.androidagent.remote

import dev.androidagent.core.ResetCredit
import dev.androidagent.core.ResetCredits
import dev.androidagent.core.UsageLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime

/** One `/usage` reply: the quota windows and the resets banked on that sign-in. */
data class AntigravityUsageReading(val limits: List<UsageLimit>, val resetCredits: ResetCredits)

/**
 * Parses quota limits returned by `agy -p /usage --output-format json`
 * (non-interactive print mode, zero turns / zero token spend).
 */
internal object AntigravityUsage {
    private val ANSI = Regex("\u001b\\[[0-9;]*[A-Za-z]")
    private val TRAILING_MODELS = Regex("""\s+models$""", RegexOption.IGNORE_CASE)
    private val WORD_AND = Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)
    private val DURATION_TOKEN = Regex("""^(\d+)\s*([mhdw])$""", RegexOption.IGNORE_CASE)
    private val PERCENT_TOKEN = Regex("""^(\d+(?:\.\d+)?)%$""")

    fun parse(output: String): List<UsageLimit> {
        val cleaned = output.replace(ANSI, "").trim()
        if (cleaned.isEmpty()) return emptyList()
        val payload = extractJson(cleaned)
        if (payload != null) {
            val status = payload.str("status")
            if (!status.isNullOrBlank() && !status.equals("SUCCESS", ignoreCase = true)) return emptyList()
            val groups = (payload["command"] as? JsonObject)
                ?.let { it["data"] as? JsonObject }
                ?.let { it["groups"] as? JsonArray }
            if (groups != null) {
                val parsed = parseGroups(groups)
                if (parsed.isNotEmpty()) return parsed
            }
            payload.str("response")?.let { response ->
                val fallback = parseTsv(response)
                if (fallback.isNotEmpty()) return fallback
            }
            return emptyList()
        }
        return parseTsv(cleaned)
    }

    /**
     * Banked quota resets from the same reply. agy marks `banked_resets` as
     * omitempty, so a successful reply without it means none are banked.
     * The entry shape (`reset_id`, `display_label`, `expire_time`, `claimable`,
     * `remaining_count`) comes from agy's own field tags; none was seen live yet.
     * Returns null only when the reply is not a successful JSON reply.
     */
    fun parseBankedResets(output: String): ResetCredits? {
        val payload = extractJson(output.replace(ANSI, "").trim()) ?: return null
        val status = payload.str("status")
        if (!status.isNullOrBlank() && !status.equals("SUCCESS", ignoreCase = true)) return null
        val entries = findBankedResets(payload["command"]) ?: findBankedResets(payload) ?: JsonArray(emptyList())
        val credits = entries.flatMapIndexed { index, element ->
            val reset = element as? JsonObject ?: return@flatMapIndexed emptyList()
            val count = reset.firstOf("remaining_count", "remainingCount")?.let(::intValue) ?: 1
            if (count <= 0) return@flatMapIndexed emptyList()
            val id = reset.firstOf("reset_id", "resetId")?.contentOrNull?.takeIf { it.isNotBlank() } ?: "banked-$index"
            val label = reset.firstOf("display_label", "displayLabel")?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            // A reset that cannot be claimed yet is still banked: say so rather than hide it.
            val claimable = reset.firstOf("claimable")?.booleanOrNull
            val title = if (claimable == false) "${label ?: "Banked reset"} · not claimable yet" else label
            val expiresAt = epochSeconds(reset["expire_time"] ?: reset["expireTime"])
            (1..count.coerceAtMost(MAX_RESETS_PER_ENTRY)).map { n ->
                ResetCredit(id = if (count == 1) id else "$id#$n", title = title, expiresAt = expiresAt)
            }
        }
        return ResetCredits(credits.size, credits.sortedWith(compareBy(nullsLast()) { it.expiresAt }))
    }

    private const val MAX_RESETS_PER_ENTRY = 50

    private fun findBankedResets(element: JsonElement?, depth: Int = 0): JsonArray? {
        val node = element as? JsonObject ?: return null
        (node["banked_resets"] ?: node["bankedResets"])?.let { return it as? JsonArray }
        if (depth >= 4) return null
        node.values.forEach { child -> findBankedResets(child, depth + 1)?.let { return it } }
        return null
    }

    private fun JsonObject.firstOf(vararg keys: String): JsonPrimitive? =
        keys.firstNotNullOfOrNull { this[it] as? JsonPrimitive }

    private fun intValue(value: JsonPrimitive): Int? = value.longOrNull?.toInt() ?: value.contentOrNull?.trim()?.toIntOrNull()

    private fun extractJson(text: String): JsonObject? {
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { return it }
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .toList()
            .asReversed()
            .forEach { line ->
                runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()?.let { return it }
            }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first >= 0 && last > first) {
            runCatching { Json.parseToJsonElement(text.substring(first, last + 1)) as? JsonObject }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseGroups(groups: JsonArray): List<UsageLimit> = groups.flatMap { element ->
        val group = element as? JsonObject ?: return@flatMap emptyList()
        val groupLabel = shortGroupName(group.str("name").orEmpty())
        val buckets = group["buckets"] as? JsonArray ?: return@flatMap emptyList()
        buckets.mapNotNull { item ->
            val bucket = item as? JsonObject ?: return@mapNotNull null
            val remaining = (bucket["remaining_fraction"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            val usedPercent = remaining?.let { (1.0 - it.coerceIn(0.0, 1.0)) * 100.0 }
            val resetsAt = epochSeconds(bucket["reset_time"])
            if (usedPercent == null && resetsAt == null) return@mapNotNull null
            val (windowLabel, windowMinutes) = parseWindow(
                window = bucket.str("window"),
                bucketName = bucket.str("name"),
                bucketId = bucket.str("id"),
            )
            val label = buildLabel(groupLabel, windowLabel, bucket.str("id"))
            UsageLimit(
                name = label,
                usedPercent = usedPercent,
                resetsAt = resetsAt,
                windowMinutes = windowMinutes,
            )
        }
    }

    /** Fallback parser for tab-separated lines: `Group\tWindow Name\t77%\t2026-11-30T21:25:44Z`. */
    private fun parseTsv(text: String): List<UsageLimit> = text.lineSequence().mapNotNull { raw ->
        val cols = raw.trimEnd('\r').split('\t').map { it.trim() }
        if (cols.size < 3) return@mapNotNull null
        val pctMatch = PERCENT_TOKEN.matchEntire(cols[2]) ?: return@mapNotNull null
        val remainingPct = pctMatch.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val usedPercent = (100.0 - remainingPct.coerceIn(0.0, 100.0))
        val resetsAt = cols.getOrNull(3)?.let(::parseIsoEpochSeconds)
        val groupLabel = shortGroupName(cols[0])
        val (windowLabel, windowMinutes) = parseWindow(window = null, bucketName = cols[1], bucketId = null)
        UsageLimit(
            name = buildLabel(groupLabel, windowLabel, fallback = cols[1]),
            usedPercent = usedPercent,
            resetsAt = resetsAt,
            windowMinutes = windowMinutes,
        )
    }.toList()

    private fun shortGroupName(raw: String): String = raw.trim()
        .replace(TRAILING_MODELS, "")
        .replace(WORD_AND, " & ")
        .trim()

    private fun buildLabel(group: String, window: String, fallback: String?): String =
        listOfNotNull(group.ifBlank { null }, window.ifBlank { null })
            .joinToString(" · ")
            .ifBlank { fallback?.trim().orEmpty().ifBlank { "Usage" } }

    private fun parseWindow(window: String?, bucketName: String?, bucketId: String?): Pair<String, Long?> {
        val token = window?.trim()?.lowercase().orEmpty()
        when (token) {
            "5h" -> return "5-hour" to 300L
            "weekly", "7d", "1w" -> return "Weekly" to 10_080L
            "daily", "24h", "1d" -> return "Daily" to 1_440L
            "hourly", "1h" -> return "Hourly" to 60L
        }
        DURATION_TOKEN.matchEntire(token)?.let { match ->
            val value = match.groupValues[1].toLongOrNull()
            val unit = match.groupValues[2].lowercase()
            if (value != null && value > 0L) {
                return when (unit) {
                    "m" -> "$value-minute" to value
                    "h" -> "$value-hour" to value * 60L
                    "d" -> if (value == 7L) "Weekly" to 10_080L else "$value-day" to value * 1_440L
                    "w" -> if (value == 1L) "Weekly" to 10_080L else "${value * 7}-day" to value * 10_080L
                    else -> token to null
                }
            }
        }
        val hint = "${bucketName.orEmpty()} ${bucketId.orEmpty()}".lowercase()
        return when {
            "five hour" in hint || "5-hour" in hint || "5h" in hint -> "5-hour" to 300L
            "weekly" in hint || "7-day" in hint || "7d" in hint -> "Weekly" to 10_080L
            "daily" in hint || "24h" in hint -> "Daily" to 1_440L
            "hourly" in hint || "1h" in hint -> "Hourly" to 60L
            else -> bucketName?.trim()
                ?.replace(Regex("""\s+limit(\s+remaining)?$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                .orEmpty() to null
        }
    }

    private fun epochSeconds(value: JsonElement?): Long? {
        (value as? JsonObject)?.let { return epochSeconds(it["seconds"]) }
        val primitive = value as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it.takeIf { seconds -> seconds > 0L } }
        val text = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && primitive.isString } ?: return null
        return parseIsoEpochSeconds(text)
    }

    private fun parseIsoEpochSeconds(text: String): Long? =
        runCatching { OffsetDateTime.parse(text.trim()).toEpochSecond() }
            .getOrNull()
            ?.takeIf { it > 0L }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
