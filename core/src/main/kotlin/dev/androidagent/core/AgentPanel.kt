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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Agent UI: a small panel Mike builds in the chat when he needs the user.
 *
 * It is made of a closed set of blocks, parsed here so the app never draws
 * the model's raw JSON. Inputs come back to Mike as values; he then acts with
 * his ordinary, gated tools. [PanelControl] blocks are the exception: they are
 * the app's own live settings controls, drawn and run by the app, so a panel
 * can put a real switch in front of the user without Mike touching it.
 * [PanelBlock.Action] blocks go one step further: each is bound to a real
 * device tool call, and a tap runs that call at once, through the same
 * gateway and approvals as Mike's own calls, with no model turn in between.
 */
data class AgentPanel(
    val id: String,
    val title: String,
    val blocks: List<PanelBlock>,
    val buttons: List<PanelButton>,
) {
    val inputs: List<PanelBlock.Input> get() = blocks.filterIsInstance<PanelBlock.Input>()
    val actions: List<PanelBlock.Action> get() = blocks.filterIsInstance<PanelBlock.Action>()

    /** Values the user may submit: known ids only, each trimmed to its cap. */
    fun accept(button: String?, values: Map<String, String>): PanelReply? {
        if (button != null && buttons.none { it.id == button }) return null
        val clean = inputs.associate { input -> input.id to (values[input.id] ?: input.initial).take(AgentPanels.MAX_VALUE) }
        val chosen = buttons.firstOrNull { it.id == button }
        if (chosen != null && chosen.style != ButtonStyle.CANCEL) {
            if (inputs.any { it.required && clean[it.id].isNullOrBlank() }) return null
            inputs.filterIsInstance<PanelBlock.Choice>().forEach { choice ->
                val value = clean[choice.id].orEmpty()
                if (value.isNotEmpty() && value !in choice.options) return null
            }
        }
        return PanelReply(button, clean)
    }

    /** Ids of inputs whose values must never reach history or traces. */
    val secretIds: Set<String> get() = blocks.filterIsInstance<PanelBlock.Field>().filter { it.kind == FieldKind.SECRET }.map { it.id }.toSet()
}

enum class Tone { NEUTRAL, GOOD, WARN, BAD }
enum class FieldKind { TEXT, EMAIL, SECRET, NUMBER, URL, PHONE, MULTILINE }
enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, CANCEL }

data class PanelButton(val id: String, val label: String, val style: ButtonStyle = ButtonStyle.PRIMARY)

sealed interface PanelBlock {
    data class Text(val text: String, val tone: Tone = Tone.NEUTRAL) : PanelBlock
    data class Status(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL) : PanelBlock

    /** A block whose value comes back to Mike. */
    sealed interface Input : PanelBlock {
        val id: String
        val label: String
        val required: Boolean
        val initial: String
    }

    data class Field(
        override val id: String, override val label: String, val kind: FieldKind = FieldKind.TEXT,
        val placeholder: String = "", override val initial: String = "", override val required: Boolean = false,
    ) : Input

    data class Toggle(override val id: String, override val label: String, val on: Boolean = false, val detail: String = "") : Input {
        override val required: Boolean get() = false
        override val initial: String get() = on.toString()
    }

    data class Choice(
        override val id: String, override val label: String, val options: List<String>,
        override val initial: String = "", override val required: Boolean = false,
    ) : Input

    /** One of the app's own settings controls, live. */
    data class Control(val control: PanelControl) : PanelBlock

    /**
     * A tile bound to a real tool call. With [offCall] it is a switch: [call]
     * turns it on, [offCall] turns it off, [on] is where it starts. Without,
     * it is a button that runs [call] on each tap. The app runs the call itself.
     */
    data class Action(
        val id: String, val label: String, val detail: String, val icon: String, val accent: PanelAccent,
        val call: PanelCall, val offCall: PanelCall?, val on: Boolean,
    ) : PanelBlock {
        val switch: Boolean get() = offCall != null
        fun callFor(turnOn: Boolean): PanelCall = if (!turnOn) offCall ?: call else call
    }
}

/** A tool call a panel action makes: a device tool's name and its arguments, as Mike would send them. */
data class PanelCall(val tool: String, val arguments: JsonObject) {
    /** What the tile shows under its label, so a label cannot hide what a tap does. */
    val caption: String
        get() = listOfNotNull(
            tool,
            ((arguments["operation"] ?: arguments["action"] ?: arguments["mode"]) as? JsonPrimitive)?.contentOrNull,
        ).joinToString(" · ")
}

/** A tile's colour. A closed set, so every panel stays in the app's look. */
enum class PanelAccent { TEAL, AMBER, VIOLET, ROSE, SKY, LIME }

/** Where one action tile stands. [on] is the switch's state after its last call that worked. */
data class PanelActionState(val running: Boolean = false, val on: Boolean? = null, val ok: Boolean? = null, val message: String = "", val runs: Int = 0)

/** One tap's outcome, for Mike's tool result. */
data class PanelActionLog(val id: String, val label: String, val tool: String, val on: Boolean?, val ok: Boolean, val text: String)

/**
 * Mike's own settings that a panel can embed. Each is the same control the
 * Settings screen shows, bound to the app's real state. The phone's settings
 * are not here: those Mike changes with his tools after the user answers.
 */
enum class PanelControl(val key: String, val about: String) {
    WIRELESS_ADB("wireless_adb", "pair, connect or turn on wireless ADB (shell, install_apk)"),
    SCREEN_CONTROL("screen_control", "the accessibility service that sees and taps the screen"),
    FLOATING_STOP("floating_stop", "the floating Stop button (draw over apps)"),
    NOTIFICATIONS("notifications", "progress and question notifications"),
    INSTALL_UPDATES("install_updates", "permission to install app updates"),
    MICROPHONE("microphone", "microphone for voice mode"),
    MODEL("model", "model, reasoning effort and context"),
    SEND_APPROVALS("send_approvals", "what Mike may send without asking"),
    ASSISTANT("assistant", "Mike as the power-button assistant"),
    AUTOMATIONS("automations", "notification access and exact alarms for standing rules"),
    ACCOUNT("account", "sign-in and usage windows"),
    ADD_COMPUTER("add_computer", "connect a new computer over SSH; the password stays on the phone"),
    ;

    companion object {
        fun of(key: String): PanelControl? = entries.firstOrNull { it.key == key }
    }
}

/** What the user did with a panel. A null [button] with [typed] means they wrote in the composer instead. */
data class PanelReply(val button: String?, val values: Map<String, String>, val typed: String? = null)

object AgentPanels {
    const val NAME = "show_ui"
    const val MAX_BLOCKS = 20
    const val MAX_BUTTONS = 4
    const val MAX_TEXT = 1_000
    const val MAX_LABEL = 120
    const val MAX_VALUE = 2_000
    const val MAX_CHOICES = 12
    const val MAX_ACTIONS = 12
    const val MAX_ARGUMENTS = 4_000
    /** Icon names the app draws on an action tile; anything else gets a bolt. */
    val ICONS = listOf(
        "flashlight", "bolt", "wifi", "bluetooth", "volume", "mute", "brightness", "moon", "music", "play", "pause",
        "next", "alarm", "timer", "phone", "message", "location", "camera", "home", "lock", "power", "star", "heart",
        "light", "fan", "tv", "car", "rotate", "airplane", "battery", "bell", "sparkle",
    )
    const val MASK = "••••"
    private val ID = Regex("[A-Za-z][A-Za-z0-9_.-]{0,39}")

    val DEFINITION = ToolDefinition(
        name = NAME,
        description = "Show the user a small interactive panel in the chat and wait for them. Use it when you need " +
            "several facts only the user has (an email and a name for a website, a code, an address), a choice with " +
            "settings, or to put one of Mike's own settings in front of them (block type control). Blocks: text, " +
            "status (a readout: label, value, tone), field (id, label, kind text|email|secret|number|url|phone|multiline, " +
            "placeholder, value, required), toggle (id, label, value), choice (id, label, options, value), control " +
            "(control: ${PanelControl.entries.joinToString(", ") { "${it.key} = ${it.about}" }}), action (id, label, tool, " +
            "arguments, optional off_arguments, value, detail, icon, accent). A control is live: the " +
            "user changes the real setting there; check the result afterwards. An action is a live tile bound to one of " +
            "your device tools: a tap runs tool with arguments at once, without you, under the same approvals as your own " +
            "calls. With off_arguments it is a switch: arguments turn it on, off_arguments turn it off, value " +
            "\"true\"/\"false\" is where it starts (read the real state first). Use actions for remote controls: torch, " +
            "volume, brightness, do-not-disturb, media keys. Not for tools that read or touch the screen. icon: ${ICONS.joinToString("|")}; " +
            "accent: ${PanelAccent.entries.joinToString("|") { it.name.lowercase() }}. The panel stays open while the user " +
            "taps; the result lists every action they ran in actions: do not run those again. Buttons (up to $MAX_BUTTONS: id, label, " +
            "style primary|secondary|danger|cancel) end the panel; without buttons there is one Done. Returns the button " +
            "pressed and every input's value; you then act with your normal tools. A secret field's value reaches you " +
            "but is masked in the chat history. Not for yes/no confirmations: approvals and ask_user do that. One " +
            "panel or question at a time.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "What this is for, in the user's language.") })
                put("blocks", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("type", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(listOf("text", "status", "field", "toggle", "choice", "control", "action").map(::JsonPrimitive)))
                            })
                            listOf("id", "label", "text", "placeholder", "detail").forEach { put(it, buildJsonObject { put("type", "string") }) }
                            put("tool", buildJsonObject { put("type", "string"); put("description", "An action's device tool, e.g. system_controls.") })
                            put("arguments", buildJsonObject {
                                put("type", "object"); put("additionalProperties", true)
                                put("description", "An action's tool arguments, exactly as you would call it. For a switch, the ones that turn it on.")
                            })
                            put("off_arguments", buildJsonObject {
                                put("type", "object"); put("additionalProperties", true)
                                put("description", "Makes the action a switch: the arguments that turn it off.")
                            })
                            put("icon", buildJsonObject { put("type", "string"); put("enum", JsonArray(ICONS.map(::JsonPrimitive))) })
                            put("accent", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(PanelAccent.entries.map { JsonPrimitive(it.name.lowercase()) }))
                            })
                            put("value", buildJsonObject { put("type", "string"); put("description", "Starting value: text, \"true\"/\"false\" for a toggle, or one of a choice's options.") })
                            put("kind", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(FieldKind.entries.map { JsonPrimitive(it.name.lowercase()) }))
                            })
                            put("tone", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(Tone.entries.map { JsonPrimitive(it.name.lowercase()) }))
                            })
                            put("required", buildJsonObject { put("type", "boolean") })
                            put("options", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("control", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(PanelControl.entries.map { JsonPrimitive(it.key) }))
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("type"))))
                    })
                })
                put("buttons", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject { put("type", "string") })
                            put("label", buildJsonObject { put("type", "string") })
                            put("style", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(ButtonStyle.entries.map { JsonPrimitive(it.name.lowercase()) }))
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("label"))))
                    })
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("blocks"))))
        },
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun <T : Enum<T>> pick(raw: String?, values: Array<T>, default: T, what: String): T {
        if (raw.isNullOrBlank()) return default
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("$what \"$raw\" is not one of ${values.joinToString("|") { it.name.lowercase() }}.")
    }

    /**
     * The panel in [arguments], or a reason the model can fix. [checkCall]
     * says why an action's tool call may not be bound, or null when it may.
     */
    fun parse(
        id: String, arguments: JsonObject,
        checkCall: (String, JsonObject) -> String? = { _, _ -> null },
    ): Result<AgentPanel> = runCatching {
        val title = arguments.str("title").orEmpty()
        require(title.isNotEmpty()) { "title is empty. Say what the panel is for." }
        val rawBlocks = (arguments["blocks"] as? JsonArray).orEmpty()
        require(rawBlocks.isNotEmpty()) { "blocks is empty. Add at least one block." }
        require(rawBlocks.size <= MAX_BLOCKS) { "At most $MAX_BLOCKS blocks. Split it, or ask for less." }
        val ids = mutableSetOf<String>()
        fun inputId(o: JsonObject): String {
            val value = o.str("id").orEmpty()
            require(ID.matches(value)) { "Each input needs an id: a letter, then letters, digits, _ . or -, up to 40." }
            require(ids.add(value)) { "id \"$value\" is used twice." }
            return value
        }
        fun label(o: JsonObject): String {
            val value = o.str("label").orEmpty()
            require(value.isNotEmpty()) { "Each input needs a label." }
            return value.take(MAX_LABEL)
        }
        val controls = mutableSetOf<PanelControl>()
        fun call(o: JsonObject, aid: String, key: String): PanelCall? {
            val raw = o[key] ?: return null
            val args = raw as? JsonObject ?: throw IllegalArgumentException("Action \"$aid\": $key must be an object.")
            require(args.toString().length <= MAX_ARGUMENTS) { "Action \"$aid\": $key is too long." }
            val tool = o.str("tool").orEmpty()
            require(tool.isNotEmpty()) { "Action \"$aid\" needs a tool." }
            checkCall(tool, args)?.let { throw IllegalArgumentException("Action \"$aid\": $it") }
            return PanelCall(tool, args)
        }
        val blocks = rawBlocks.mapIndexed { index, element ->
            val o = element as? JsonObject ?: throw IllegalArgumentException("Block ${index + 1} is not an object.")
            when (val type = o.str("type")?.lowercase()) {
                "text" -> PanelBlock.Text(
                    o.str("text")?.takeIf(String::isNotEmpty)?.take(MAX_TEXT) ?: throw IllegalArgumentException("A text block needs text."),
                    pick(o.str("tone"), Tone.entries.toTypedArray(), Tone.NEUTRAL, "tone"),
                )
                "status" -> PanelBlock.Status(
                    label(o), o.str("value").orEmpty().take(MAX_LABEL), pick(o.str("tone"), Tone.entries.toTypedArray(), Tone.NEUTRAL, "tone"),
                )
                "field" -> PanelBlock.Field(
                    inputId(o), label(o), pick(o.str("kind"), FieldKind.entries.toTypedArray(), FieldKind.TEXT, "kind"),
                    o.str("placeholder").orEmpty().take(MAX_LABEL), o.str("value").orEmpty().take(MAX_VALUE),
                    (o["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
                "toggle" -> PanelBlock.Toggle(
                    inputId(o), label(o),
                    (o["value"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull.toBoolean() } ?: false,
                    o.str("detail").orEmpty().take(MAX_TEXT),
                )
                "choice" -> {
                    val options = (o["options"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_LABEL) }
                        .distinct()
                    require(options.size in 2..MAX_CHOICES) { "A choice needs 2 to $MAX_CHOICES options." }
                    val start = o.str("value").orEmpty()
                    require(start.isEmpty() || start in options) { "A choice's value must be one of its options." }
                    PanelBlock.Choice(inputId(o), label(o), options, start, (o["required"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                "control" -> {
                    val key = o.str("control").orEmpty()
                    val control = PanelControl.of(key)
                        ?: throw IllegalArgumentException("control \"$key\" is not one of ${PanelControl.entries.joinToString("|") { it.key }}.")
                    require(controls.add(control)) { "control \"$key\" is shown twice." }
                    PanelBlock.Control(control)
                }
                "action" -> {
                    val aid = inputId(o)
                    val on = call(o, aid, "arguments") ?: throw IllegalArgumentException("Action \"$aid\" needs arguments.")
                    PanelBlock.Action(
                        aid, label(o), o.str("detail").orEmpty().take(MAX_LABEL),
                        o.str("icon")?.lowercase()?.takeIf { it in ICONS } ?: "bolt",
                        PanelAccent.entries.firstOrNull { it.name.equals(o.str("accent"), ignoreCase = true) } ?: PanelAccent.TEAL,
                        on, call(o, aid, "off_arguments"),
                        (o["value"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull.toBoolean() } ?: false,
                    )
                }
                else -> throw IllegalArgumentException("Block ${index + 1} has type \"$type\"; use text, status, field, toggle, choice, control or action.")
            }
        }
        require(blocks.count { it is PanelBlock.Action } <= MAX_ACTIONS) { "At most $MAX_ACTIONS actions." }
        val rawButtons = (arguments["buttons"] as? JsonArray).orEmpty()
        require(rawButtons.size <= MAX_BUTTONS) { "At most $MAX_BUTTONS buttons." }
        val buttonIds = mutableSetOf<String>()
        val buttons = rawButtons.mapIndexed { index, element ->
            val o = element as? JsonObject ?: throw IllegalArgumentException("Button ${index + 1} is not an object.")
            val bid = o.str("id").orEmpty()
            require(ID.matches(bid)) { "Each button needs an id." }
            require(buttonIds.add(bid)) { "Button id \"$bid\" is used twice." }
            val text = o.str("label").orEmpty()
            require(text.isNotEmpty()) { "Each button needs a label." }
            PanelButton(bid, text.take(MAX_LABEL), pick(o.str("style"), ButtonStyle.entries.toTypedArray(), ButtonStyle.PRIMARY, "style"))
        }.ifEmpty { listOf(PanelButton("done", "Done")) }
        AgentPanel(id, title.take(MAX_LABEL), blocks, buttons)
    }

    /** Values as history and traces may show them: secrets masked. */
    fun masked(panel: AgentPanel, values: Map<String, String>): Map<String, String> =
        values.mapValues { (key, value) -> if (key in panel.secretIds && value.isNotEmpty()) MASK else value }

    fun result(panel: AgentPanel, reply: PanelReply, mask: Boolean, log: List<PanelActionLog> = emptyList()): String = buildJsonObject {
        val values = if (mask) masked(panel, reply.values) else reply.values
        if (reply.typed != null) {
            put("ok", false)
            put("errorType", "dismissed")
            put("typed", reply.typed)
            put("message", "The user wrote in the chat instead of using the panel. Their words are in typed; act on them.")
        } else if (reply.button == null) {
            put("ok", false)
            put("errorType", "closed")
            put("message", "The user closed the panel without choosing. Do not act on its values; ask in plain words if you still need something." +
                if (log.isNotEmpty()) " The actions they ran did happen: see actions." else "")
        } else {
            put("ok", true)
            put("button", reply.button)
            reply.button?.let { b -> panel.buttons.firstOrNull { it.id == b }?.let { put("buttonLabel", it.label) } }
        }
        put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
        putActions(log)
        val shown = panel.blocks.filterIsInstance<PanelBlock.Control>().map { it.control.key }
        if (shown.isNotEmpty()) {
            put("controls", buildJsonArray { shown.forEach { add(JsonPrimitive(it)) } })
            put("note", "Controls change real settings directly. Check their state now (device_status, the runtime snapshot or a tool) before you say what changed.")
        }
    }.toString()

    private fun JsonObjectBuilder.putActions(log: List<PanelActionLog>) {
        if (log.isEmpty()) return
        put("actions", buildJsonArray {
            log.forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id); put("label", entry.label); put("tool", entry.tool)
                    entry.on?.let { put("on", it) }
                    put("ok", entry.ok); put("result", entry.text.take(600))
                })
            }
        })
    }

    /** Nobody answered in time; what the user's taps did still happened. */
    fun timedOut(log: List<PanelActionLog>, minutes: Long): String = buildJsonObject {
        put("ok", false)
        put("errorType", "no_answer")
        put("message", "Nobody used the panel within $minutes minutes and it is closed. Do not show it again now: say what " +
            "you need and end your turn." + if (log.isNotEmpty()) " The actions the user ran did happen: see actions." else "")
        putActions(log)
    }.toString()

    /** One line for the chat history, as the user's side of the exchange. */
    fun summary(panel: AgentPanel, reply: PanelReply): String {
        if (reply.button == null) return "Closed \"${panel.title}\""
        val label = panel.buttons.firstOrNull { it.id == reply.button }?.label ?: reply.button.orEmpty()
        val lines = masked(panel, reply.values).mapNotNull { (key, value) ->
            val input = panel.inputs.firstOrNull { it.id == key } ?: return@mapNotNull null
            if (value.isBlank()) null else "${input.label}: $value"
        }
        return (listOf(label) + lines).filter(String::isNotBlank).joinToString("\n")
    }
}
