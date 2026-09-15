package dev.androidagent.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Something that happened, as the host reports it.
 *
 * One type per trigger kind rather than a bag of strings, so a source cannot
 * deliver half an event: a notification without a package, or a place change
 * with no direction, does not typecheck.
 *
 * Every event exposes its own fields as flat dotted names, which is what a
 * rule's `{{notification.text}}` resolves against. Nothing else is exposed:
 * whatever else the host knew when it built the event stays with the host.
 */
sealed interface AutomationEvent {
    val at: ZonedDateTime
    val kind: AutomationTriggerKind

    /** The fields this event offers a rule, keyed exactly as a placeholder writes them. */
    fun fields(): Map<String, String>

    /** A clock tick the host woke for. Re-checked against the schedule before anything fires. */
    data class Clock(override val at: ZonedDateTime) : AutomationEvent {
        override val kind = AutomationTriggerKind.SCHEDULE
        override fun fields(): Map<String, String> = emptyMap()
    }

    data class Place(
        val place: String,
        val transition: AutomationTrigger.PlaceTransition,
        override val at: ZonedDateTime,
    ) : AutomationEvent {
        override val kind = AutomationTriggerKind.PLACE
        override fun fields(): Map<String, String> = mapOf(
            "place.id" to place,
            "place.transition" to transition.wire,
        )
    }

    /**
     * A posted notification.
     *
     * The title and text are carried because a rule cannot match "from Dad"
     * without them. They travel no further than [AutomationEvaluator] unless
     * the rule's own actions name them — see [AutomationRule.exportedFields].
     */
    data class Notification(
        val packageName: String,
        val title: String,
        val text: String,
        override val at: ZonedDateTime,
    ) : AutomationEvent {
        override val kind = AutomationTriggerKind.NOTIFICATION
        override fun fields(): Map<String, String> = mapOf(
            "notification.package" to packageName,
            "notification.title" to title,
            "notification.text" to text,
        )
    }

    data class DeviceState(
        val name: String,
        val value: String,
        override val at: ZonedDateTime,
    ) : AutomationEvent {
        override val kind = AutomationTriggerKind.DEVICE_STATE
        override fun fields(): Map<String, String> = mapOf(
            "state.name" to name,
            "state.value" to value,
        )
    }

    /** Run this rule now, by name, because a person or another rule said so. */
    data class Manual(val ruleId: String, override val at: ZonedDateTime) : AutomationEvent {
        override val kind = AutomationTriggerKind.MANUAL
        override fun fields(): Map<String, String> = emptyMap()
    }
}

/**
 * What is true right now, independent of what just happened.
 *
 * A trigger says a message arrived; the context says it is 21:40, the phone is
 * at home, and the screen is locked. Conditions read this, so the same rule can
 * be tested against any moment the caller cares to describe — which is what
 * makes `mode:"test"` a real dry run rather than a re-parse.
 *
 * [userReachable] is the one field the host must be honest about: it gates
 * every [AutomationAttention.USER] action, and a host that reports `true` from
 * a pocket gets a phone that starts talking in a meeting.
 */
data class AutomationContext(
    val now: ZonedDateTime,
    /** Places the phone is currently inside, by the same ids a rule names. */
    val places: Set<String> = emptySet(),
    /** Charging, wifi, screen and anything else the host publishes, by name. */
    val deviceState: Map<String, String> = emptyMap(),
    /** True when a person could answer right now: unlocked, not in Do Not Disturb. */
    val userReachable: Boolean = false,
    /** True when a turn could run right now: signed in, runtime up, no other run. */
    val agentAvailable: Boolean = true,
) {
    /** Always-available fields, on top of whatever the event carries. */
    fun fields(): Map<String, String> = mapOf(
        "now.time" to now.format(TIME),
        "now.day" to now.dayOfWeek.wire(),
        "now.date" to now.format(DATE),
    )

    companion object {
        val AMBIENT_FIELDS: Set<String> = setOf("now.time", "now.day", "now.date")

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

/**
 * Builds an event from the same JSON a trigger is written in.
 *
 * This exists so a rule can be dry-run against a described moment — "pretend
 * Dad messaged at 21:40" — without a notification listener, a geofence or a
 * wait until 19:00. The host's live sources build these types directly and do
 * not come through here.
 */
object AutomationEventFormat {

    fun parse(json: kotlinx.serialization.json.JsonObject, at: ZonedDateTime): AutomationEvent {
        fun bad(reason: String): Nothing = throw AutomationFormatException("automation_event_invalid", reason)

        val wire = json.str("type") ?: bad("\"event\" needs a \"type\".")
        return when (AutomationTriggerKind.from(wire)) {
            AutomationTriggerKind.SCHEDULE -> AutomationEvent.Clock(at)

            AutomationTriggerKind.PLACE -> AutomationEvent.Place(
                place = json.str("place") ?: bad("A \"place\" event needs \"place\"."),
                transition = AutomationTrigger.PlaceTransition.from(json.str("transition")?.lowercase() ?: "enter")
                    ?: bad("A \"place\" event's \"transition\" is enter or exit."),
                at = at,
            )

            AutomationTriggerKind.NOTIFICATION -> AutomationEvent.Notification(
                packageName = json.str("package") ?: bad("A \"notification\" event needs \"package\"."),
                title = json.str("title").orEmpty(),
                text = json.str("text").orEmpty(),
                at = at,
            )

            AutomationTriggerKind.DEVICE_STATE -> AutomationEvent.DeviceState(
                name = json.str("state") ?: bad("A \"device_state\" event needs \"state\"."),
                value = json.str("is") ?: json.str("value") ?: bad("A \"device_state\" event needs \"is\"."),
                at = at,
            )

            AutomationTriggerKind.MANUAL -> AutomationEvent.Manual(
                ruleId = json.str("rule") ?: bad("A \"manual\" event needs the \"rule\" it runs."),
                at = at,
            )

            null -> bad(
                "\"$wire\" is not an event type. Use one of " +
                    AutomationTriggerKind.WIRE_NAMES.joinToString(", ") + ".",
            )
        }
    }
}
