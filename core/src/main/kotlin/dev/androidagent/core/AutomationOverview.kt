package dev.androidagent.core

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * One rule as a person reads it.
 *
 * The rule format is written for the model: ids, packages, 24-hour clocks and
 * a closed vocabulary. None of that belongs on a panel someone glances at, and
 * translating it inside a composable would put the interesting decisions — what
 * counts as attention, which sentence matters most — somewhere no test can
 * reach. So the translation lives here and the UI renders strings.
 */
data class AutomationSummary(
    val id: String,
    /** The name in a list: what the rule does, in the author's own words. */
    val name: String,
    /** The short name a chip can carry. Derived from the id, so it is always short. */
    val chipName: String,
    /** "WhatsApp from Dad, after 19:00" — the trigger and its conditions in one line. */
    val trigger: String,
    val status: Status,
    val attention: AutomationAttention,
    /** The third line: when it last ran, when it runs next, or why it cannot. */
    val detail: String,
    /** Set only for [Status.BLOCKED]: the short half that fits in the strip's sentence. */
    val blockedReason: String? = null,
    /** The trigger alone, for the chain on the rule's own screen. */
    val whenLine: String = "",
    /** Each condition as its own line, so the chain reads as steps rather than a paragraph. */
    val conditions: List<String> = emptyList(),
    /** What it does, one line per action. */
    val actions: List<String> = emptyList(),
    /** "Costs a thinking turn" / "Needs you there", or null when it runs on its own. */
    val cost: String? = null,
    /** What leaves the phone when it fires, in the user's terms. */
    val sends: String = "Nothing",
    val sendsNote: String = "",
) {
    /**
     * Three states, because three is what a person needs to tell apart: it is
     * working, it is off because you said so, or it is on and cannot run —
     * which looks identical to working until the day you notice nothing
     * happened.
     */
    enum class Status { ON, BLOCKED, OFF }

    val needsAttention: Boolean get() = status == Status.BLOCKED
}

/**
 * Everything the side panel's rules strip shows, decided once.
 *
 * The strip is a glance, so what it may not do is grow: at most [MAX_CHIPS]
 * chips and exactly one sentence, however many rules exist. Which chips and
 * which sentence are the whole design, so they are decided here rather than in
 * a Composable — the sentence in particular has to be the most useful true
 * thing, not the first thing in the list.
 */
data class AutomationOverview(
    val summaries: List<AutomationSummary>,
    /** Sorted: what needs you first, then what is running, then what is off. */
    val chips: List<AutomationSummary>,
    /** How many did not fit, for the "+N" chip. */
    val hidden: Int,
    /** The one live sentence under the chips. */
    val line: String,
    /** True when [line] is a problem rather than a schedule, so it reads amber. */
    val lineIsWarning: Boolean,
    val enabled: Int,
    val off: Int,
    val blocked: Int,
) {
    /**
     * Whether the chat name in the top bar shows its dot.
     *
     * The panel is the only place a rule's state is visible, so without this a
     * rule that cannot run is invisible until the day someone notices it never
     * did anything.
     */
    val needsAttention: Boolean get() = blocked > 0

    val isEmpty: Boolean get() = summaries.isEmpty()

    companion object {
        const val MAX_CHIPS = 3

        /** What a host holds before it has read the rules. Never rendered as zero. */
        val EMPTY = AutomationOverview(
            summaries = emptyList(),
            chips = emptyList(),
            hidden = 0,
            line = "Ask Mike to do something on a schedule",
            lineIsWarning = false,
            enabled = 0,
            off = 0,
            blocked = 0,
        )

        fun of(
            rules: List<AutomationRule>,
            history: AutomationHistory,
            supported: Set<AutomationTriggerKind>,
            now: ZonedDateTime,
            appLabel: (String) -> String? = { null },
        ): AutomationOverview {
            val summaries = rules
                .map { rule -> AutomationSummaries.of(rule, history, supported, now, appLabel) }
                .sortedWith(compareBy({ it.status.ordinal.sortKey() }, { it.name.lowercase(Locale.ROOT) }))

            val blocked = summaries.filter { it.status == AutomationSummary.Status.BLOCKED }
            val on = summaries.filter { it.status == AutomationSummary.Status.ON }
            val off = summaries.filter { it.status == AutomationSummary.Status.OFF }

            val chips = summaries.take(MAX_CHIPS)
            val warning = blocked.firstOrNull()

            // The most useful true thing, in priority order: a rule that cannot
            // run, then the next thing that will happen, then the honest
            // nothing. A list of everything would not fit and would not help.
            val line: String
            val isWarning: Boolean
            when {
                warning != null -> {
                    line = warning.chipName + " can’t run — " + (warning.blockedReason ?: "a permission is missing")
                    isWarning = true
                }
                on.isNotEmpty() -> {
                    line = AutomationSummaries.nextLine(rules, now) ?: (on.size.toString() + " watching for their moment")
                    isWarning = false
                }
                summaries.isEmpty() -> {
                    line = "Ask Mike to do something on a schedule"
                    isWarning = false
                }
                else -> {
                    line = "Every rule is turned off"
                    isWarning = false
                }
            }

            return AutomationOverview(
                summaries = summaries,
                chips = chips,
                hidden = (summaries.size - chips.size).coerceAtLeast(0),
                line = line,
                lineIsWarning = isWarning,
                enabled = blocked.size + on.size,
                off = off.size,
                blocked = blocked.size,
            )
        }

        /** BLOCKED first, then ON, then OFF — attention before routine. */
        private fun Int.sortKey(): Int = when (this) {
            AutomationSummary.Status.BLOCKED.ordinal -> 0
            AutomationSummary.Status.ON.ordinal -> 1
            else -> 2
        }
    }
}

/** Turns one rule into the strings a person reads. Pure; every branch is testable. */
object AutomationSummaries {

    fun of(
        rule: AutomationRule,
        history: AutomationHistory,
        supported: Set<AutomationTriggerKind>,
        now: ZonedDateTime,
        appLabel: (String) -> String? = { null },
    ): AutomationSummary {
        val chipName = chipName(rule.id)
        val servable = rule.trigger.kind in supported
        val status = when {
            !rule.enabled -> AutomationSummary.Status.OFF
            !servable -> AutomationSummary.Status.BLOCKED
            else -> AutomationSummary.Status.ON
        }
        val blockedReason = if (status == AutomationSummary.Status.BLOCKED) blockedReason(rule.trigger.kind) else null
        return AutomationSummary(
            id = rule.id,
            name = rule.description.ifBlank { chipName },
            chipName = chipName,
            trigger = triggerLine(rule, appLabel),
            status = status,
            attention = rule.attention,
            detail = detailLine(rule, status, history, now, blockedReason),
            blockedReason = blockedReason,
            whenLine = whenLine(rule, appLabel),
            conditions = rule.conditions.map { conditionLine(it).replaceFirstChar { c -> c.titlecase(Locale.ROOT) } },
            actions = rule.actions.map { actionLine(it) },
            cost = cost(rule.attention),
            sends = sends(rule),
            sendsNote = sendsNote(rule),
        )
    }

    /**
     * "home-lights" becomes "Home lights".
     *
     * From the id rather than the description because a chip has room for two
     * or three words and a description is a sentence. Ids are already short,
     * lowercase and hyphenated, which is exactly a short label with the dashes
     * still in.
     */
    fun chipName(id: String): String {
        val words = id.replace('-', ' ').replace('_', ' ').trim()
        if (words.isEmpty()) return id
        return words.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** The trigger and every condition, as one readable line. */
    fun triggerLine(rule: AutomationRule, appLabel: (String) -> String? = { null }): String {
        val trigger = rule.trigger
        val head = when (trigger.kind) {
            AutomationTriggerKind.SCHEDULE -> trigger.schedule?.let { scheduleLine(it) } ?: "On a schedule"
            AutomationTriggerKind.PLACE -> {
                val place = trigger.place?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "a place"
                if (trigger.transition == AutomationTrigger.PlaceTransition.EXIT) "Leaving $place" else "Arriving at $place"
            }
            AutomationTriggerKind.NOTIFICATION -> {
                val app = trigger.packageName?.let { appLabel(it) ?: prettyPackage(it) } ?: "an app"
                trigger.from?.let { "$app from $it" } ?: app
            }
            AutomationTriggerKind.DEVICE_STATE -> {
                val name = trigger.stateName?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "A device signal"
                trigger.stateValue?.let { "$name becomes $it" } ?: "$name changes"
            }
            AutomationTriggerKind.MANUAL -> "Only when you run it"
        }
        val tail = rule.conditions.joinToString(", ") { conditionLine(it) }
        return if (tail.isEmpty()) head else "$head, $tail"
    }

    private fun scheduleLine(schedule: AutomationSchedule): String {
        schedule.everyMinutes?.let { minutes ->
            return if (minutes % 60 == 0) {
                val hours = minutes / 60
                if (hours == 1) "Every hour" else "Every $hours hours"
            } else {
                "Every $minutes minutes"
            }
        }
        val at = schedule.at ?: return "On a schedule"
        val clock = "%02d:%02d".format(at.hour, at.minute)
        if (schedule.days.isEmpty()) return "Every day at $clock"
        val named = java.time.DayOfWeek.entries
            .filter { it in schedule.days }
            .map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        val list = when (named.size) {
            1 -> named.first()
            2 -> named[0] + " and " + named[1]
            else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
        }
        return "$list at $clock"
    }

    /** The trigger on its own, without the conditions the chain lists separately. */
    fun whenLine(rule: AutomationRule, appLabel: (String) -> String? = { null }): String {
        val full = triggerLine(rule, appLabel)
        val tail = rule.conditions.joinToString(", ") { conditionLine(it) }
        return if (tail.isEmpty()) full else full.removeSuffix(", $tail")
    }

    /** One action, as the thing it does rather than the name of its kind. */
    fun actionLine(action: AutomationAction): String = when (action.kind) {
        AutomationActionKind.RUN_WORKFLOW ->
            "Runs the \"" + (action.raw.str("workflow") ?: "saved") + "\" sequence on your phone"
        AutomationActionKind.OPEN_INTENT -> "Opens a screen on your phone"
        AutomationActionKind.NOTIFY -> "Tells you: " + (action.raw.str("text") ?: "something happened")
        AutomationActionKind.AGENT_TURN -> "Mike works out what to do and does it"
        AutomationActionKind.VOICE_CALL -> "Mike starts talking to you"
        AutomationActionKind.ASK -> "Asks you: " + (action.raw.str("question") ?: "go ahead?")
    }

    /** What the rule costs to finish, or null when it costs nothing and needs nobody. */
    fun cost(attention: AutomationAttention): String? = when (attention) {
        AutomationAttention.NONE -> null
        AutomationAttention.MODEL -> "Costs a thinking turn"
        AutomationAttention.USER -> "Needs you there"
    }

    /**
     * What leaves the phone, named field by field.
     *
     * The rule format already knows this exactly — only the fields an action
     * interpolates are ever exported — so the screen states it rather than
     * asking the user to trust it.
     */
    fun sends(rule: AutomationRule): String {
        if (rule.exportedFields.isEmpty()) return "Nothing"
        val named = rule.exportedFields.sorted().map { field ->
            when (field) {
                "notification.title" -> "the sender’s name"
                "notification.text" -> "the message itself"
                "notification.package" -> "which app it came from"
                "place.id" -> "which place"
                "place.transition" -> "whether you arrived or left"
                "state.name", "state.value" -> "the phone signal"
                "now.time" -> "the time"
                "now.day" -> "the day"
                "now.date" -> "the date"
                else -> field
            }
        }
        val list = when (named.size) {
            1 -> named.first()
            2 -> named[0] + " and " + named[1]
            else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
        }
        return "Only " + list
    }

    fun sendsNote(rule: AutomationRule): String {
        val readsMore = rule.conditions.any { it.kind == AutomationCondition.Kind.TEXT } &&
            "notification.text" !in rule.exportedFields
        return when {
            rule.exportedFields.isEmpty() -> "This runs on the phone. No thinking turn, nothing transmitted."
            readsMore -> "The message itself is read on the phone to decide, and is never sent."
            else -> "Matching happens on the phone. Nothing else about the event is sent."
        }
    }

    private fun conditionLine(condition: AutomationCondition): String {
        val body = when (condition.kind) {
            AutomationCondition.Kind.TIME_BETWEEN -> {
                val after = condition.after?.let { "%02d:%02d".format(it.hour, it.minute) }
                val before = condition.before?.let { "%02d:%02d".format(it.hour, it.minute) }
                when {
                    after != null && before != null -> "after $after and before $before"
                    after != null -> "after $after"
                    else -> "before $before"
                }
            }
            AutomationCondition.Kind.DAY_OF_WEEK -> "on " + java.time.DayOfWeek.entries
                .filter { it in condition.days }
                .joinToString("/") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            AutomationCondition.Kind.AT_PLACE -> "at " + (condition.place ?: "a place")
            AutomationCondition.Kind.TEXT -> {
                val what = condition.equals ?: condition.contains
                if (what == null) "when it says something" else "mentioning \"$what\""
            }
            AutomationCondition.Kind.DEVICE_STATE -> {
                val what = condition.equals ?: condition.contains
                (condition.stateName ?: "the phone") + (what?.let { " is $it" } ?: " is set")
            }
        }
        return if (condition.negate) "not $body" else body
    }

    /**
     * Why a rule cannot run, short enough to sit inside the strip's sentence.
     *
     * Named per trigger because the remedy differs: one is a permission the
     * user can grant on the spot, the other is something the app cannot do at
     * all yet, and telling them apart is the difference between a fixable
     * problem and a wait.
     */
    fun blockedReason(kind: AutomationTriggerKind): String = when (kind) {
        AutomationTriggerKind.NOTIFICATION -> "Mike can’t read notifications yet"
        AutomationTriggerKind.PLACE -> "Mike can’t track location yet"
        AutomationTriggerKind.SCHEDULE -> "the alarm permission is missing"
        AutomationTriggerKind.DEVICE_STATE -> "Mike can’t watch the phone’s state yet"
        AutomationTriggerKind.MANUAL -> "this phone cannot run it"
    }

    private fun detailLine(
        rule: AutomationRule,
        status: AutomationSummary.Status,
        history: AutomationHistory,
        now: ZonedDateTime,
        blockedReason: String?,
    ): String {
        if (status == AutomationSummary.Status.OFF) return "Turned off"
        val last = history.lastFiredAt(rule.id)
        if (status == AutomationSummary.Status.BLOCKED) {
            val reason = blockedReason ?: "a permission is missing"
            return if (last == null) {
                reason.replaceFirstChar { it.titlecase(Locale.ROOT) } + ", so this has never run"
            } else {
                reason.replaceFirstChar { it.titlecase(Locale.ROOT) } + ", so it has stopped running"
            }
        }
        rule.trigger.schedule?.let { schedule ->
            return "Next run " + relativeFuture(schedule.nextRunAt(now), now)
        }
        if (last == null) return "Waiting for its moment"
        val today = history.firedOn(rule.id, now.toLocalDate())
        val ran = "Ran " + relativePast(java.time.Instant.ofEpochMilli(last).atZone(now.zone), now)
        return if (today > 1) "$ran · $today times today" else ran
    }

    /** The soonest scheduled run across every enabled rule, as a sentence. */
    fun nextLine(rules: List<AutomationRule>, now: ZonedDateTime): String? {
        val due = rules
            .filter { it.enabled && it.trigger.kind == AutomationTriggerKind.SCHEDULE }
            .mapNotNull { rule -> rule.trigger.schedule?.nextRunAt(now)?.let { rule to it } }
            .minByOrNull { it.second }
            ?: return null
        return "Next: " + chipName(due.first.id) + ", " + relativeFuture(due.second, now)
    }

    private fun relativeFuture(at: ZonedDateTime, now: ZonedDateTime): String {
        val minutes = Duration.between(now, at).toMinutes()
        return when {
            minutes <= 1L -> "in a moment"
            minutes < 60L -> "in $minutes minutes"
            minutes < 120L -> "in an hour"
            minutes < 60L * 24 -> "in " + (minutes / 60L) + " hours"
            at.toLocalDate() == now.toLocalDate().plusDays(1) -> "tomorrow at " + at.format(CLOCK)
            else -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " at " + at.format(CLOCK)
        }
    }

    private fun relativePast(at: ZonedDateTime, now: ZonedDateTime): String {
        val minutes = Duration.between(at, now).toMinutes()
        return when {
            minutes < 2L -> "just now"
            minutes < 60L -> "$minutes minutes ago"
            at.toLocalDate() == now.toLocalDate() -> "today at " + at.format(CLOCK)
            at.toLocalDate() == now.toLocalDate().minusDays(1) -> "yesterday at " + at.format(CLOCK)
            else -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " at " + at.format(CLOCK)
        }
    }

    /** "com.whatsapp" reads as "Whatsapp" when the phone cannot give a real label. */
    private fun prettyPackage(packageName: String): String =
        packageName.substringAfterLast('.').replaceFirstChar { it.titlecase(Locale.ROOT) }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
