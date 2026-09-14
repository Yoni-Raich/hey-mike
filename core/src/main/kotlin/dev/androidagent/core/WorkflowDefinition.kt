package dev.androidagent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A workflow the runner executes, declared by intent rather than by keystroke.
 *
 * `run_workflow` takes a literal list of tool calls, which is why it only ever
 * worked for the screen it was recorded on: a saved `tap(x=504,y=200)` misses
 * as soon as a row moves, and there is no way to say "the Search field",
 * "wait until Settings is in front" or "stop and ask before flipping this".
 *
 * A definition says what each step *means*. The runner reads the screen, finds
 * the node the step describes, acts on it and checks the result, so the same
 * file keeps working across a layout change, a different screen size and a
 * localized label as long as one of the selectors still matches.
 *
 * Nothing positional is ever stored. `nodeId` and `observationId` are minted by
 * `read_ui` during the run and are meaningless a second later, so a definition
 * that carried them would be stale before it was saved.
 */
data class WorkflowDefinition(
    val id: String,
    val version: Int,
    val packageName: String,
    val description: String,
    val steps: List<WorkflowStep>,
    /** Set when the definition was read from a file, for the failure report. */
    val source: String? = null,
) {
    fun stepIndex(stepId: String): Int? = steps.indexOfFirst { it.id == stepId }.takeIf { it >= 0 }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("package", packageName)
        if (description.isNotEmpty()) put("description", description)
        put("steps", JsonArray(steps.map { it.toJson() }))
    }

    /** The step list as a person reads it, for `mode:"describe"` and for the skill. */
    fun outline(): JsonArray = JsonArray(
        steps.map { step ->
            buildJsonObject {
                put("id", step.id)
                put("action", step.action.wire)
                step.target?.describe()?.let { put("target", it) }
                step.text?.let { put("text", it) }
                if (step.requiresConfirmation) put("requiresConfirmation", true)
                if (step.optional) put("optional", true)
                if (step.skipIfVerified) put("skipIfVerified", true)
            }
        },
    )

    companion object {
        const val MAX_STEPS = WorkflowEngine.MAX_STEPS
        const val MAX_ID_CHARS = 64
        const val MAX_DESCRIPTION_CHARS = 400

        /** Ids name files and are echoed in failures, so they stay boring on purpose. */
        val ID_RE = Regex("[a-z0-9][a-z0-9_-]{0,${MAX_ID_CHARS - 1}}")

        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        /**
         * Parse one definition, or throw [WorkflowFormatException] naming the
         * step that is wrong.
         *
         * A workflow that half-parses is worse than one that does not load: the
         * runner would drive the phone through the steps it understood and stop
         * somewhere nobody planned for.
         */
        fun parse(json: JsonObject, source: String? = null): WorkflowDefinition {
            val id = json.str("id")?.lowercase()
                ?: throw WorkflowFormatException("workflow_invalid", "\"id\" is required.")
            if (!ID_RE.matches(id)) {
                throw WorkflowFormatException(
                    "workflow_invalid",
                    "\"$id\" is not a usable workflow id: use lowercase letters, digits, \"-\" and \"_\".",
                )
            }
            val pkg = json.str("package")
                ?: throw WorkflowFormatException("workflow_invalid", "Workflow \"$id\" has no \"package\".")
            if (!PACKAGE_RE.matches(pkg)) {
                throw WorkflowFormatException(
                    "workflow_invalid",
                    "Workflow \"$id\" names \"$pkg\", which is not a valid Android package name.",
                )
            }
            val rawSteps = (json["steps"] as? JsonArray)
                ?: throw WorkflowFormatException("workflow_invalid", "Workflow \"$id\" has no \"steps\" array.")
            if (rawSteps.isEmpty()) {
                throw WorkflowFormatException("workflow_invalid", "Workflow \"$id\" has no steps.")
            }
            if (rawSteps.size > MAX_STEPS) {
                throw WorkflowFormatException(
                    "workflow_invalid",
                    "Workflow \"$id\" has ${rawSteps.size} steps; the limit is $MAX_STEPS.",
                )
            }
            val steps = rawSteps.mapIndexed { index, element ->
                val step = (element as? JsonObject)
                    ?: throw WorkflowFormatException("workflow_invalid", "Step $index of \"$id\" is not an object.")
                WorkflowStep.parse(step, index, id)
            }
            val duplicate = steps.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
            if (duplicate != null) {
                // Resuming names a step, so two steps with one name is an
                // ambiguity the runner cannot resolve at the point it matters.
                throw WorkflowFormatException(
                    "workflow_invalid",
                    "Workflow \"$id\" uses the step id \"$duplicate\" more than once.",
                )
            }
            return WorkflowDefinition(
                id = id,
                version = json["version"]?.jsonPrimitive?.intOrNull ?: 1,
                packageName = pkg,
                description = json.str("description")?.take(MAX_DESCRIPTION_CHARS).orEmpty(),
                steps = steps,
                source = source,
            )
        }
    }
}

// Readers shared by every parser here. A value of the wrong type reads as
// absent rather than throwing: a definition is refused for what it says, never
// for how it was quoted.

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.millis(key: String, min: Long, max: Long): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull?.coerceIn(min, max)

/** A definition file that cannot be trusted. Typed so the tool reply says which file and why. */
class WorkflowFormatException(val errorType: String, override val message: String) : Exception(message)

/** What the runner may do. Closed, for the same reason [WorkflowEngine]'s tool set is closed. */
enum class WorkflowAction(val wire: String, val commits: Boolean, val needsTarget: Boolean) {
    /** Bring an app to the front and wait for it to be there. */
    OPEN_APP("open_app", commits = true, needsTarget = false),

    /** Click the node the target names, by node handle where the backend has one. */
    TAP("tap", commits = true, needsTarget = true),

    /** Replace a field's contents. With no target, types into whatever holds focus. */
    TYPE_TEXT("type_text", commits = true, needsTarget = false),

    /** Scroll a list. With no target, the first scrollable node on screen. */
    SCROLL("scroll", commits = true, needsTarget = false),

    /** A global key: BACK, HOME, APP_SWITCH and the rest the backend allows. */
    KEY("key", commits = true, needsTarget = false),

    /** Wait for the screen to change and settle. Commits nothing. */
    WAIT("wait", commits = false, needsTarget = false),

    /** Read the screen. A checkpoint step whose whole job is its `verify`. */
    OBSERVE("observe", commits = false, needsTarget = false);

    companion object {
        fun from(wire: String): WorkflowAction? =
            entries.firstOrNull { it.wire == wire } ?: ALIASES[wire]?.let { alias -> entries.first { it.wire == alias } }

        /** Names the same action goes by in `run_workflow` and in the device tool list. */
        private val ALIASES = mapOf(
            "tap_node" to "tap",
            "set_text" to "type_text",
            "scroll_node" to "scroll",
            "wait_for_change" to "wait",
            "read_ui" to "observe",
            "back" to "key",
        )

        val WIRE_NAMES: List<String> = entries.map { it.wire }
    }
}

/**
 * One step.
 *
 * @param optional a step that may legitimately have nothing to do — a consent
 *   dialog that does not always appear. Its target not being found is recorded
 *   and skipped rather than failing the run.
 * @param skipIfVerified check `verify` *before* acting and skip the step when it
 *   already holds. This is what makes a resumed run safe: re-running "enable
 *   the switch" on a switch that is already on would turn it back off.
 * @param requiresConfirmation stop and ask the user before this step runs.
 */
data class WorkflowStep(
    val id: String,
    val action: WorkflowAction,
    val target: WorkflowSelector? = null,
    val text: String? = null,
    val submit: Boolean = false,
    /** Extra arguments handed straight to the device tool, e.g. `keycode`, `direction`. */
    val arguments: JsonObject = JsonObject(emptyMap()),
    val verify: WorkflowVerification? = null,
    val requiresConfirmation: Boolean = false,
    val optional: Boolean = false,
    val skipIfVerified: Boolean = false,
    val timeoutMs: Long? = null,
    /** Wait for the screen to settle after acting. Off for steps that change nothing on screen. */
    val waitForChange: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("action", action.wire)
        target?.let { put("target", it.toJson()) }
        text?.let { put("text", it) }
        if (submit) put("submit", true)
        if (arguments.isNotEmpty()) put("arguments", arguments)
        verify?.let { put("verify", it.toJson()) }
        if (requiresConfirmation) put("requiresConfirmation", true)
        if (optional) put("optional", true)
        if (skipIfVerified) put("skipIfVerified", true)
        timeoutMs?.let { put("timeoutMs", it) }
        if (!waitForChange) put("waitForChange", false)
    }

    companion object {
        const val MAX_TEXT_CHARS = 4_000

        fun parse(json: JsonObject, index: Int, workflowId: String): WorkflowStep {
            fun bad(reason: String): Nothing =
                throw WorkflowFormatException("workflow_invalid", "Step $index of \"$workflowId\": $reason")

            val id = json.str("id") ?: "step$index"
            if (id.length > WorkflowDefinition.MAX_ID_CHARS) bad("the step id is too long.")
            val actionName = json.str("action")
                ?: bad("\"action\" is required.")
            val action = WorkflowAction.from(actionName)
                ?: bad(
                    "\"$actionName\" is not a workflow action. Use one of " +
                        WorkflowAction.WIRE_NAMES.joinToString(", ") + ".",
                )
            val arguments = when (val raw = json["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> bad("\"arguments\" must be an object.")
            }
            val target = when (val raw = json["target"]) {
                null -> null
                is JsonObject -> WorkflowSelector.parse(raw) ?: bad("\"target\" names no selector field.")
                else -> bad("\"target\" must be an object.")
            }
            val text = json.str("text")
                ?: arguments.str("text")
            if (text != null && text.length > MAX_TEXT_CHARS) bad("\"text\" is longer than $MAX_TEXT_CHARS characters.")
            if (action.needsTarget && target == null) {
                bad("\"${action.wire}\" needs a \"target\" saying which element to act on.")
            }
            if (action == WorkflowAction.TYPE_TEXT && text == null) {
                bad("\"type_text\" needs \"text\".")
            }
            // `back` is sugar for the one key every recovery path presses, and
            // the only alias that implies an argument of its own.
            val stepArguments = if (actionName == "back" && arguments.str("keycode") == null) {
                JsonObject(arguments + ("keycode" to JsonPrimitive("BACK")))
            } else {
                arguments
            }
            if (action == WorkflowAction.KEY && stepArguments.str("keycode") == null) {
                bad("\"key\" needs arguments.keycode, for example {\"keycode\":\"BACK\"}.")
            }
            return WorkflowStep(
                id = id,
                action = action,
                target = target,
                text = text,
                submit = json.bool("submit") ?: arguments.bool("submit") ?: false,
                arguments = stepArguments,
                verify = parseVerify(json, ::bad),
                requiresConfirmation = json.bool("requiresConfirmation") ?: false,
                optional = json.bool("optional") ?: false,
                skipIfVerified = json.bool("skipIfVerified") ?: false,
                timeoutMs = json.millis("timeoutMs", MIN_STEP_MS, MAX_STEP_MS),
                waitForChange = json.bool("waitForChange") ?: action.commits,
            )
        }

        private fun parseVerify(json: JsonObject, bad: (String) -> Nothing): WorkflowVerification? =
            when (val raw = json["verify"]) {
                null -> null
                is JsonObject -> WorkflowVerification.parse(raw) ?: bad("\"verify\" names no condition.")
                else -> bad("\"verify\" must be an object.")
            }

        const val MIN_STEP_MS = 500L
        const val MAX_STEP_MS = 30_000L
    }
}

/**
 * How the runner finds an element on the screen in front of it.
 *
 * Every field is optional and they are scored, not ANDed: a definition that
 * names both the `resourceId` and the visible label still resolves when the app
 * renames the id in an update, because the label alone is enough to identify
 * the node. That is the whole reason a workflow survives a redesign.
 */
data class WorkflowSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    /** Require the whole field to equal the value instead of containing it. */
    val exact: Boolean = false,
    /** Which match to take when several are equally good. Defaults to the first. */
    val index: Int = 0,
    /** Only consider a node that can be clicked, or has a clickable ancestor. */
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    /** Scroll the screen looking for the node before giving up. */
    val scrollIntoView: Boolean = false,
) {
    /** One line naming what was looked for, for a failure the user can read. */
    fun describe(): String = buildList {
        resourceId?.let { add("resourceId=$it") }
        text?.let { add("text=\"$it\"") }
        contentDescription?.let { add("contentDescription=\"$it\"") }
        className?.let { add("class=$it") }
        packageName?.let { add("package=$it") }
        if (exact) add("exact")
        if (index > 0) add("index=$index")
        if (clickable) add("clickable")
        if (scrollable) add("scrollable")
    }.joinToString(", ")

    fun toJson(): JsonObject = buildJsonObject {
        resourceId?.let { put("resourceId", it) }
        text?.let { put("text", it) }
        contentDescription?.let { put("contentDescription", it) }
        className?.let { put("class", it) }
        packageName?.let { put("package", it) }
        if (exact) put("exact", true)
        if (index > 0) put("index", index)
        if (clickable) put("clickable", true)
        if (scrollable) put("scrollable", true)
        if (scrollIntoView) put("scrollIntoView", true)
    }

    companion object {
        const val MAX_FIELD_CHARS = 256

        /** Null when the object names nothing to match on; an empty selector would match the screen. */
        fun parse(json: JsonObject): WorkflowSelector? {
            fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                json.str(key)?.take(MAX_FIELD_CHARS)
            }
            val selector = WorkflowSelector(
                resourceId = field("resourceId", "resource-id", "id"),
                text = field("text", "label"),
                contentDescription = field("contentDescription", "content-desc", "description"),
                className = field("className", "class"),
                packageName = field("package", "packageName"),
                exact = json.bool("exact") ?: false,
                index = (json["index"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, MAX_INDEX) ?: 0,
                clickable = json.bool("clickable") ?: false,
                scrollable = json.bool("scrollable") ?: false,
                scrollIntoView = json.bool("scrollIntoView") ?: false,
            )
            val namesSomething = selector.resourceId != null || selector.text != null ||
                selector.contentDescription != null || selector.className != null || selector.scrollable
            return selector.takeIf { namesSomething }
        }

        const val MAX_INDEX = 50
    }
}

/**
 * What has to be true for a step to count as done.
 *
 * Without this the runner is a macro player: it would report success for a tap
 * that landed on a disabled control, and every later step would then act on the
 * wrong screen. The acceptance rule is that no step is reported complete until
 * its own condition holds.
 */
data class WorkflowVerification(
    /** The app in front. A substring, so "settings" matches `com.android.settings`. */
    val packageName: String? = null,
    /** A node matching this must be on screen. */
    val present: WorkflowSelector? = null,
    /** No node matching this may be on screen. Dismissed dialogs and closed sheets. */
    val absent: WorkflowSelector? = null,
    /** The target node's on/off state, for a switch, checkbox or radio. */
    val checked: Boolean? = null,
    /** Applies [checked] to this node instead of the step's own target. */
    val checkedOf: WorkflowSelector? = null,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun describe(): String = buildList {
        packageName?.let { add("package contains \"$it\"") }
        present?.let { add("${it.describe()} on screen") }
        absent?.let { add("${it.describe()} gone") }
        checked?.let { add(if (it) "the switch is on" else "the switch is off") }
    }.joinToString(" and ")

    fun toJson(): JsonObject = buildJsonObject {
        packageName?.let { put("package", it) }
        present?.let { put("present", it.toJson()) }
        absent?.let { put("absent", it.toJson()) }
        checked?.let { put("checked", it) }
        checkedOf?.let { put("checkedOf", it.toJson()) }
        if (timeoutMs != DEFAULT_TIMEOUT_MS) put("timeoutMs", timeoutMs)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MIN_TIMEOUT_MS = 500L
        const val MAX_TIMEOUT_MS = 20_000L

        /**
         * Null when nothing is asserted.
         *
         * `{"package": "..."}` is the spec's shorthand, and a bare selector
         * body — `{"text":"Wireless debugging"}` — reads as "this is on
         * screen", which is how anyone writing one by hand expects it to work.
         */
        fun parse(json: JsonObject): WorkflowVerification? {
            val timeout = json.millis("timeoutMs", MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
                ?: DEFAULT_TIMEOUT_MS
            val explicitPresent = (json["present"] as? JsonObject)?.let(WorkflowSelector::parse)
            val absent = (json["absent"] as? JsonObject)?.let(WorkflowSelector::parse)
            val checked = json.bool("checked")
            val checkedOf = (json["checkedOf"] as? JsonObject)?.let(WorkflowSelector::parse)
            val pkg = json.str("package") ?: json.str("packageName")
            // A shorthand body is only a selector when it is not already one of
            // the named conditions, so `{"package":"x"}` stays a package check.
            val shorthand = if (explicitPresent == null && absent == null) {
                WorkflowSelector.parse(json)
            } else {
                null
            }
            val present = explicitPresent ?: shorthand
            if (pkg == null && present == null && absent == null && checked == null) return null
            return WorkflowVerification(
                packageName = pkg,
                present = present,
                absent = absent,
                checked = checked,
                checkedOf = checkedOf,
                timeoutMs = timeout,
            )
        }
    }
}
