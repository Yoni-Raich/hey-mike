package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * What wakes a rule.
 *
 * Closed, and each kind declares the fields it provides, so a rule that
 * interpolates `{{notification.text}}` under a `schedule` trigger is refused
 * when it is written rather than firing with an empty prompt at 19:00.
 */
enum class AutomationTriggerKind(val wire: String, val fields: Set<String>) {
    /** The clock. The host sets an alarm for [AutomationSchedule.nextRunAt]. */
    SCHEDULE("schedule", emptySet()),

    /** Entering or leaving a named place. The place is resolved by the host, not here. */
    PLACE("place", setOf("place.id", "place.transition")),

    /** A notification was posted. The one trigger that reads content, and the one that is fenced. */
    NOTIFICATION("notification", setOf("notification.package", "notification.title", "notification.text")),

    /** Charging, unplugged, connected to a Wi-Fi network, screen on. */
    DEVICE_STATE("device_state", setOf("state.name", "state.value")),

    /** Nothing wakes it: the rule exists so a person or another rule can run it by name. */
    MANUAL("manual", emptySet());

    companion object {
        fun from(wire: String): AutomationTriggerKind? = entries.firstOrNull { it.wire == wire }
        val WIRE_NAMES: List<String> = entries.map { it.wire }
    }
}

/**
 * The trigger half of a rule: which events may wake it, and which of those are
 * actually about it.
 *
 * Matching is re-checked here even for events the host only delivers because it
 * thinks the rule wants them. An alarm that fires a minute early, a geofence
 * that reports the wrong place, a notification listener that widens its filter
 * in a later version — none of them can fire a rule whose own trigger does not
 * agree, which is why [matches] takes the whole event rather than a promise.
 */
data class AutomationTrigger(
    val kind: AutomationTriggerKind,
    val schedule: AutomationSchedule? = null,
    /** PLACE: which place, and whether entering or leaving. */
    val place: String? = null,
    val transition: PlaceTransition = PlaceTransition.ENTER,
    /** NOTIFICATION: which app, and optionally which sender. */
    val packageName: String? = null,
    val from: String? = null,
    /** DEVICE_STATE: which signal, and the value that wakes the rule. */
    val stateName: String? = null,
    val stateValue: String? = null,
) {

    enum class PlaceTransition(val wire: String) {
        ENTER("enter"), EXIT("exit");

        companion object {
            fun from(wire: String): PlaceTransition? = entries.firstOrNull { it.wire == wire }
        }
    }

    fun matches(event: AutomationEvent): Boolean = when (kind) {
        AutomationTriggerKind.SCHEDULE ->
            event is AutomationEvent.Clock && schedule?.isDue(event.at) == true

        AutomationTriggerKind.PLACE ->
            event is AutomationEvent.Place &&
                event.place.equals(place, ignoreCase = true) &&
                event.transition == transition

        AutomationTriggerKind.NOTIFICATION ->
            event is AutomationEvent.Notification &&
                (packageName == null || event.packageName.equals(packageName, ignoreCase = true)) &&
                (from == null || event.title.contains(from, ignoreCase = true))

        AutomationTriggerKind.DEVICE_STATE ->
            event is AutomationEvent.DeviceState &&
                event.name.equals(stateName, ignoreCase = true) &&
                (stateValue == null || event.value.equals(stateValue, ignoreCase = true))

        AutomationTriggerKind.MANUAL ->
            event is AutomationEvent.Manual
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("type", kind.wire)
        schedule?.let { schedule -> schedule.toJson().forEach { (key, value) -> put(key, value) } }
        place?.let { put("place", it) }
        if (kind == AutomationTriggerKind.PLACE) put("transition", transition.wire)
        packageName?.let { put("package", it) }
        from?.let { put("from", it) }
        stateName?.let { put("state", it) }
        stateValue?.let { put("is", it) }
    }

    fun describe(): JsonPrimitive = JsonPrimitive(
        when (kind) {
            AutomationTriggerKind.SCHEDULE -> schedule?.describe() ?: "on a schedule"
            AutomationTriggerKind.PLACE -> "${transition.wire}ing \"$place\""
            AutomationTriggerKind.NOTIFICATION ->
                "a notification from " + (packageName ?: "any app") + (from?.let { " by \"$it\"" } ?: "")
            AutomationTriggerKind.DEVICE_STATE -> "$stateName becomes ${stateValue ?: "anything"}"
            AutomationTriggerKind.MANUAL -> "only when run by name"
        },
    )

    companion object {
        fun parse(json: JsonObject, ruleId: String): AutomationTrigger {
            fun bad(reason: String): Nothing =
                throw AutomationFormatException("automation_invalid", "Rule \"$ruleId\": $reason")

            val wire = json.str("type") ?: bad("the trigger needs a \"type\".")
            val kind = AutomationTriggerKind.from(wire)
                ?: bad(
                    "\"$wire\" is not a trigger. Use one of " +
                        AutomationTriggerKind.WIRE_NAMES.joinToString(", ") + ".",
                )
            return when (kind) {
                AutomationTriggerKind.SCHEDULE ->
                    AutomationTrigger(kind, schedule = AutomationSchedule.parse(json, ruleId))

                AutomationTriggerKind.PLACE -> {
                    val place = json.str("place") ?: bad("a \"place\" trigger needs \"place\".")
                    val transitionWire = json.str("transition") ?: "enter"
                    val transition = PlaceTransition.from(transitionWire.lowercase())
                        ?: bad("\"$transitionWire\" is not a transition. Use enter or exit.")
                    AutomationTrigger(kind, place = place, transition = transition)
                }

                AutomationTriggerKind.NOTIFICATION -> {
                    // No package means every app on the phone, which is almost
                    // never what was meant and always the widest possible read
                    // of the user's notifications. Named explicitly or refused.
                    val pkg = json.str("package")
                        ?: bad("a \"notification\" trigger needs \"package\": name the app it listens to.")
                    AutomationTrigger(kind, packageName = pkg, from = json.str("from"))
                }

                AutomationTriggerKind.DEVICE_STATE -> {
                    val name = json.str("state") ?: bad("a \"device_state\" trigger needs \"state\".")
                    AutomationTrigger(kind, stateName = name, stateValue = json.str("is"))
                }

                AutomationTriggerKind.MANUAL -> AutomationTrigger(kind)
            }
        }
    }
}

/**
 * When a scheduled rule is due.
 *
 * Two shapes, because two things are meant by "every day at seven" and "every
 * half hour", and collapsing them into a cron string would make both harder to
 * read and neither easier to write.
 *
 * The due check is re-run against the event's own clock rather than trusting
 * that an alarm fired for the right reason. Android coalesces, delays and
 * batches alarms, and a rule that posts to Facebook must not post because the
 * OS woke the process early for something else.
 */
data class AutomationSchedule(
    val at: LocalTime? = null,
    val days: Set<DayOfWeek> = emptySet(),
    val everyMinutes: Int? = null,
) {

    /** True when [now] is the minute this schedule names. */
    fun isDue(now: ZonedDateTime): Boolean {
        if (everyMinutes != null) return true
        val at = at ?: return false
        if (days.isNotEmpty() && now.dayOfWeek !in days) return false
        return now.hour == at.hour && now.minute == at.minute
    }

    /**
     * The next instant the host should set an alarm for, strictly after [after].
     *
     * Seconds and nanos are dropped: a rule says 19:00, not 19:00:37, and an
     * alarm that keeps its seconds drifts a little further from the stated time
     * every time it is rescheduled from its own firing.
     */
    fun nextRunAt(after: ZonedDateTime): ZonedDateTime {
        everyMinutes?.let { return after.plusMinutes(it.toLong()).withSecond(0).withNano(0) }
        val at = at ?: return after.plusDays(1)
        var candidate = after.withHour(at.hour).withMinute(at.minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(after)) candidate = candidate.plusDays(1)
        if (days.isEmpty()) return candidate
        // At most seven hops: some day of the week is always in the set.
        repeat(7) {
            if (candidate.dayOfWeek in days) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    fun toJson(): JsonObject = buildJsonObject {
        at?.let { put("at", "%02d:%02d".format(it.hour, it.minute)) }
        everyMinutes?.let { put("everyMinutes", it) }
        if (days.isNotEmpty()) {
            put("days", JsonArray(DayOfWeek.entries.filter { it in days }.map { JsonPrimitive(it.wire()) }))
        }
    }

    fun describe(): String = when {
        everyMinutes != null -> "every $everyMinutes minutes"
        at == null -> "on a schedule"
        days.isEmpty() -> "every day at %02d:%02d".format(at.hour, at.minute)
        else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.wire() } +
            " at %02d:%02d".format(at.hour, at.minute)
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 24 * 60

        fun parse(json: JsonObject, ruleId: String): AutomationSchedule {
            fun bad(reason: String): Nothing =
                throw AutomationFormatException("automation_invalid", "Rule \"$ruleId\": $reason")

            val everyMinutes = (json["everyMinutes"] as? JsonPrimitive)?.intOrNull
            val atText = json.str("at")
            if (everyMinutes == null && atText == null) {
                bad("a \"schedule\" trigger needs \"at\" (\"19:00\") or \"everyMinutes\".")
            }
            if (everyMinutes != null && atText != null) {
                bad("a \"schedule\" trigger takes \"at\" or \"everyMinutes\", not both.")
            }
            if (everyMinutes != null && everyMinutes < MIN_INTERVAL_MINUTES) {
                // Below this Android's own alarm batching makes the stated
                // interval a fiction, and the battery cost stops being invisible.
                bad("\"everyMinutes\" is $everyMinutes; the shortest interval is $MIN_INTERVAL_MINUTES minutes.")
            }
            if (everyMinutes != null && everyMinutes > MAX_INTERVAL_MINUTES) {
                bad("\"everyMinutes\" is $everyMinutes; use \"at\" for anything longer than a day.")
            }
            return AutomationSchedule(
                at = atText?.let { parseClock(it, ruleId, "at") },
                days = parseDays(json["days"], ruleId),
                everyMinutes = everyMinutes,
            )
        }
    }
}

/**
 * When the host should next wake for the clock.
 *
 * One alarm serves every scheduled rule: the earliest next run across all of
 * them. Android caps how many exact alarms an app may hold and charges for
 * each wake-up, and a phone with twelve daily rules does not want twelve
 * alarms when one plus a re-check does the same job — the re-check being
 * [AutomationSchedule.isDue], which runs against every rule when the alarm
 * lands and is the reason an early or coalesced wake fires nothing.
 */
object AutomationWakeups {

    /** The earliest moment any enabled scheduled rule is next due, or null if none is. */
    fun nextRunAt(rules: List<AutomationRule>, after: ZonedDateTime): ZonedDateTime? =
        rules.asSequence()
            .filter { it.enabled && it.trigger.kind == AutomationTriggerKind.SCHEDULE }
            .mapNotNull { it.trigger.schedule?.nextRunAt(after) }
            .minOrNull()

    /**
     * Packages the notification listener may look at: the union over enabled
     * notification rules.
     *
     * The listener checks this before it reads a title or a body, so a
     * notification from an app no rule names is dropped without being looked
     * at. An empty set means the listener has nothing to do at all.
     */
    fun watchedPackages(rules: List<AutomationRule>): Set<String> =
        rules.asSequence()
            .filter { it.enabled && it.trigger.kind == AutomationTriggerKind.NOTIFICATION }
            .mapNotNull { it.trigger.packageName?.lowercase() }
            .toSet()
}
