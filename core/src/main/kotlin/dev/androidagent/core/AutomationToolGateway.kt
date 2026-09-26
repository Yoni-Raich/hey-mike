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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exposes standing rules as one tool, `automation_rule`.
 *
 * One tool with modes rather than six, for the same reason `workflow_runner`
 * is one: the model picks a name once and the failure replies can carry the
 * full list of what exists, which is the difference between a dead end and a
 * correction.
 *
 * It rides in the composite because that is where a tool name reaches the
 * model without new plumbing, not because writing a rule is a device action —
 * it touches no device, so [needsControl] is false for every mode and
 * [deviceBackendLive] is false.
 *
 * **This gateway never fires a rule.** It writes, reads, explains and dry-runs
 * them. Firing needs a clock, a geofence and a notification listener, all of
 * which live on the Android side; keeping them out of here is what lets every
 * decision in this file be unit-tested and what stops a tool call from turning
 * into an unattended run.
 */
class AutomationToolGateway(
    private val library: AutomationLibrary,
    private val history: AutomationHistory,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone()) },
    /**
     * What the host can actually serve. A rule that needs a notification
     * listener nobody granted is saved and reported as dormant, never silently
     * accepted as working: the point of failure is when it is written, not the
     * first night it does not fire.
     */
    private val supportedTriggers: () -> Set<AutomationTriggerKind> = { AutomationTriggerKind.entries.toSet() },
    /**
     * Fire one rule now, by id. Null leaves `mode:"run"` refusing rather than
     * silently doing nothing — a host with no firing path should say so.
     *
     * Deliberately fire-and-forget: the run takes the device, may stop to ask
     * the user, and can outlast the tool call that started it. Waiting for it
     * here would hold the coordinator's tool lock for the whole rule.
     */
    private val fireNow: ((String) -> Unit)? = null,
    /**
     * Called after every change to the saved rules: create, update, enable,
     * disable, delete. The host re-arms its one alarm here. Without it a rule
     * written by the agent was on disk and never scheduled — it waited for the
     * next unrelated firing or restart to be noticed at all.
     */
    private val onChanged: () -> Unit = {},
) : DeviceToolGateway {

    @Volatile private var revoked = true

    private val evaluator = AutomationEvaluator(history)

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        revoked = false
    }

    override fun revoke() {
        revoked = true
    }

    /** Nothing here touches the screen. */
    override fun needsControl(name: String): Boolean = false

    /** A local store: always ready, but it cannot operate the phone. */
    override fun deviceBackendLive(): Boolean = false

    override fun statusLine(): String {
        val all = library.all()
        if (all.isEmpty()) return "Automations: none"
        val on = all.count { it.enabled }
        val dormant = all.count { it.enabled && it.trigger.kind !in supportedTriggers() }
        return buildString {
            append("Automations: $on on")
            if (all.size > on) append(", ${all.size - on} off")
            if (dormant > 0) append(", $dormant dormant (permission missing)")
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
        if (name != "automation_rule") {
            throw ToolNotServiceable("automation_unsupported", "Automations do not implement \"$name\".")
        }
        val mode = arguments.str("mode")?.lowercase() ?: "list"
        if (mode !in MODES) {
            return refusal("unknown_mode", "\"$mode\" is not a mode. Use " + MODES.joinToString(", ") + ".")
        }
        return try {
            when (mode) {
                "create" -> create(arguments)
                "update" -> update(arguments)
                "list" -> list()
                "describe" -> describe(arguments)
                "enable" -> setEnabled(arguments, true)
                "disable" -> setEnabled(arguments, false)
                "delete" -> delete(arguments)
                "test" -> test(arguments)
                "run" -> run(arguments)
                else -> refusal("unknown_mode", "\"$mode\" is not a mode.")
            }
        } catch (invalid: AutomationFormatException) {
            refusal(invalid.errorType, invalid.message)
        }
    }

    override suspend fun cancel() {
        // Every mode is a bounded file read, a file write, or an in-memory decision.
    }

    /**
     * Write a rule.
     *
     * The reply is deliberately long: it says what the rule will do in words,
     * which fields of the event reach the model, whether the phone can serve
     * the trigger at all, and — the thing the model most needs to hear back —
     * what kind of attention it ends in. A rule accepted with a one-word "ok"
     * is a rule nobody checked.
     */
    private fun create(arguments: JsonObject): ToolResult {
        val json = arguments["rule"] as? JsonObject
            ?: return refusal("rule_required", "\"rule\" must be the rule object itself.")
        val rule = AutomationRule.parse(json)
        val existing = library.get(rule.id)
        // Silently replacing a standing rule because a new one happened to get
        // the same id is how a working rule disappears. Replacing is allowed,
        // but it has to be asked for.
        if (existing != null && arguments.bool("replace") != true) {
            return refusal(
                "rule_exists",
                "A rule called \"${rule.id}\" already exists (${existing.description.ifEmpty { "no description" }}). " +
                    "Use mode:\"update\" to change it, pass replace:true to overwrite it whole, " +
                    "or pick another id.",
            )
        }
        try {
            library.save(rule)
        } catch (full: IllegalArgumentException) {
            return refusal("rule_limit", full.message ?: "No room for another rule.")
        }
        runCatching { onChanged() }
        val supported = rule.trigger.kind in supportedTriggers()
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("rule", rule.id)
                put("replaced", existing != null)
                put("summary", rule.outline())
                put("dormant", !supported)
                if (!supported) {
                    put(
                        "dormantBecause",
                        "This phone cannot serve a \"${rule.trigger.kind.wire}\" trigger yet — the " +
                            "permission it needs is not granted. The rule is saved and will start " +
                            "working once it is; tell the user rather than reporting it as live.",
                    )
                }
                put(
                    "note",
                    "Saved. It runs unattended, so check it with mode:\"test\" before trusting it, " +
                        "and tell the user in one sentence what will happen and when.",
                )
            }.toString(),
        )
    }

    private fun list(): ToolResult {
        val rules = library.all()
        val broken = library.broken()
        return ToolResult(
            buildJsonObject {
                put("count", rules.size)
                put("rules", JsonArray(rules.map { it.outline() }))
                if (broken.isNotEmpty()) {
                    put(
                        "unreadable",
                        JsonArray(
                            broken.map { entry ->
                                buildJsonObject {
                                    put("file", entry.file)
                                    put("reason", entry.reason)
                                }
                            },
                        ),
                    )
                }
                val unsupported = rules.filter { it.enabled && it.trigger.kind !in supportedTriggers() }
                if (unsupported.isNotEmpty()) {
                    put("dormant", JsonArray(unsupported.map { JsonPrimitive(it.id) }))
                    put("dormantMeans", "Saved and turned on, but this phone cannot serve their trigger yet.")
                }
                if (rules.isEmpty()) {
                    put(
                        "hint",
                        "No rules yet. A rule is when/if/then: what wakes it, what has to be true, " +
                            "and what to do. Write the steps as a workflow first where there are " +
                            "steps — a rule names one, it does not contain one.",
                    )
                }
            }.toString(),
        )
    }

    private fun describe(arguments: JsonObject): ToolResult {
        val rule = lookup(arguments) ?: return notFound(arguments)
        val last = history.lastFiredAt(rule.id)
        return ToolResult(
            buildJsonObject {
                put("rule", rule.outline())
                put("definition", rule.toJson())
                put("firedToday", history.firedOn(rule.id, now().toLocalDate()))
                put("maxPerDay", rule.guard.maxPerDay)
                put("cooldownMinutes", rule.guard.cooldownMs / 60_000L)
                last?.let { put("lastFiredAt", it) }
                rule.trigger.schedule?.let { put("nextRunAt", it.nextRunAt(now(), last, rule.savedAt).toString()) }
            }.toString(),
        )
    }

    private fun setEnabled(arguments: JsonObject, enabled: Boolean): ToolResult {
        val rule = exact(arguments) ?: return notFound(arguments)
        val updated = library.setEnabled(rule.id, enabled) ?: return notFound(arguments)
        runCatching { onChanged() }
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("rule", updated.id)
                put("enabled", updated.enabled)
                put("summary", updated.outline())
            }.toString(),
        )
    }

    private fun delete(arguments: JsonObject): ToolResult {
        val rule = exact(arguments) ?: return notFound(arguments)
        val removed = library.delete(rule.id)
        if (removed) runCatching { onChanged() }
        return ToolResult(
            buildJsonObject {
                put("ok", removed)
                put("rule", rule.id)
                if (removed) {
                    put("deleted", rule.outline())
                    put("note", "Deleted. It will not run again. Tell the user which rule is gone.")
                } else {
                    put("message", "\"${rule.id}\" could not be deleted; it may already be gone.")
                }
            }.toString(),
            success = removed,
        )
    }

    /**
     * Change part of a saved rule.
     *
     * `changes` carries only what changes, keyed like the rule itself — `when`,
     * `if`, `then`, `guard`, `description`, `enabled`. Each key replaces the old
     * value whole (a new `then` is the whole new action list), and `null`
     * removes a key. The merged rule is validated exactly like a new one, and
     * nothing is written when it fails.
     *
     * The reply shows the rule before and after, so a change the model did not
     * mean is visible in the same turn rather than at 19:00.
     */
    private fun update(arguments: JsonObject): ToolResult {
        val rule = exact(arguments) ?: return notFound(arguments)
        val changes = arguments["changes"] as? JsonObject
            ?: return refusal(
                "changes_required",
                "\"changes\" is the part of the rule to change, e.g. " +
                    "{\"when\":{\"type\":\"schedule\",\"at\":\"20:00\"}}. Each key replaces the old value; " +
                    "null removes it.",
            )
        if (changes.isEmpty()) return refusal("changes_required", "\"changes\" is empty: there is nothing to change.")
        val updated = library.update(rule.id, changes)
        runCatching { onChanged() }
        val supported = updated.trigger.kind in supportedTriggers()
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("rule", updated.id)
                put("changed", JsonArray(changes.keys.sorted().map { JsonPrimitive(it) }))
                put("before", rule.outline())
                put("after", updated.outline())
                put("dormant", !supported)
                put(
                    "note",
                    "Saved. Check the change with mode:\"test\", and tell the user in one sentence what " +
                        "is different now.",
                )
            }.toString(),
        )
    }

    /**
     * Run a rule against a described moment without firing it.
     *
     * Nothing is recorded, so this can be called as often as the model likes,
     * and a rule that would not fire says which of its own clauses stopped it.
     * Every rule is reported when none is named, which is how "why did nothing
     * happen when Dad messaged" gets an answer.
     */
    private fun test(arguments: JsonObject): ToolResult {
        val eventJson = arguments["event"] as? JsonObject
            ?: return refusal(
                "event_required",
                "\"event\" describes what to pretend happened, e.g. " +
                    "{\"type\":\"notification\",\"package\":\"com.whatsapp\",\"title\":\"Dad\"}.",
            )
        val at = arguments.str("now")?.let { text ->
            runCatching { ZonedDateTime.parse(text) }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(text).atZone(zone()) }.getOrNull()
                ?: return refusal(
                    "now_invalid",
                    "\"now\" is \"$text\"; write an ISO time such as \"2026-09-15T21:40\".",
                )
        } ?: now()
        val event = AutomationEventFormat.parse(eventJson, at)
        val context = AutomationContext(
            now = at,
            places = (arguments["places"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf(String::isNotEmpty) }
                ?.toSet()
                .orEmpty(),
            deviceState = (arguments["deviceState"] as? JsonObject)
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                .orEmpty(),
            userReachable = arguments.bool("userReachable") ?: true,
            agentAvailable = arguments.bool("agentAvailable") ?: true,
        )
        val named = arguments.str("rule")
        val rules = (
            if (named == null) library.all() else listOf(lookup(arguments) ?: return notFound(arguments))
        ).map {
            // A dry run asks about a described moment, which may well be before
            // the rule was written. When it was saved is a live-path concern.
            it.copy(savedAt = null)
        }
        val outcomes = evaluator.evaluate(rules, event, context)
        val firing = outcomes.filterIsInstance<AutomationEvaluator.Outcome.Fired>()
        return ToolResult(
            buildJsonObject {
                put("at", at.toString())
                put("event", event.kind.wire)
                put("firing", firing.size)
                put("outcomes", JsonArray(outcomes.map { AutomationEvaluator.toJson(it) }))
                put(
                    "note",
                    "A dry run. Nothing ran, and no rule's daily count or cooldown was touched.",
                )
            }.toString(),
        )
    }

    /**
     * Fire a rule now, for real.
     *
     * Naming the rule is its trigger — the caller supplies what the clock or
     * the listener would have — but nothing else is waived: the conditions, the
     * cooldown, the daily limit and the attention gate all apply, and the run
     * counts against the rule's quota like any other. That is the difference
     * from `mode:"test"`, which decides the same way and does nothing.
     *
     * The reply says it started, not that it worked: the run takes the device
     * and may stop to ask the user, so its outcome arrives later.
     */
    private fun run(arguments: JsonObject): ToolResult {
        val fire = fireNow ?: return refusal(
            "run_unavailable",
            "This host cannot fire a rule on demand. Rules still run from their own triggers.",
        )
        val rule = exact(arguments) ?: return notFound(arguments)
        if (!rule.enabled) {
            return refusal(
                "rule_disabled",
                "\"${rule.id}\" is turned off. Enable it first with mode:\"enable\".",
            )
        }
        fire(rule.id)
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("rule", rule.id)
                put("started", true)
                put("attention", rule.attention.wire)
                put("willDo", JsonArray(rule.actions.map { JsonPrimitive(it.describe()) }))
                put(
                    "note",
                    "Started. Naming the rule supplied its trigger; its conditions, cooldown and " +
                        "daily limit still applied, so it may have decided not to run — check " +
                        "mode:\"describe\" for whether the count went up. This run counts against " +
                        "its quota.",
                )
            }.toString(),
        )
    }

    private fun lookup(arguments: JsonObject): AutomationRule? {
        val name = arguments.str("rule") ?: arguments.str("id") ?: arguments.str("name") ?: return null
        return (library.find(name) as? AutomationLibrary.Lookup.Found)?.definition
    }

    /**
     * The rule named by its exact id, for every mode that changes something.
     * A near miss is answered by [notFound] with the candidates, never acted on.
     */
    private fun exact(arguments: JsonObject): AutomationRule? {
        val name = arguments.str("rule") ?: arguments.str("id") ?: arguments.str("name") ?: return null
        return library.get(name)
    }

    private fun notFound(arguments: JsonObject): ToolResult {
        val name = arguments.str("rule") ?: arguments.str("id") ?: arguments.str("name")
        if (name == null) {
            return refusal("rule_required", "\"rule\" is required: name the rule by its id. " + known())
        }
        return when (val lookup = library.find(name)) {
            is AutomationLibrary.Lookup.Ambiguous -> refusal(
                "rule_ambiguous",
                "\"$name\" matches ${lookup.candidates.joinToString(", ")}. Name one of them exactly.",
            )
            is AutomationLibrary.Lookup.Found -> refusal(
                "rule_id_inexact",
                "\"$name\" is not an exact rule id. Did you mean \"${lookup.definition.id}\"? " +
                    "Changing a rule needs its exact id.",
            )
            else -> refusal("rule_not_found", "There is no rule called \"$name\". " + known())
        }
    }

    private fun known(): String {
        val ids = library.all().map { it.id }
        return if (ids.isEmpty()) "No rules are saved." else "Saved rules: " + ids.joinToString(", ") + "."
    }

    private fun refusal(errorType: String, message: String): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", false)
            put("errorType", errorType)
            put("message", message)
        }.toString(),
        success = false,
    )

    private companion object {
        val MODES = listOf("create", "update", "list", "describe", "enable", "disable", "delete", "test", "run")

        private fun enumOf(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

        /**
         * The rule `create` takes, or the id every other mode takes.
         *
         * Deliberately typeless: one argument carries either a whole rule or a
         * string naming one, and `properties` constrains only the object case,
         * so both still validate. What it buys is that the shape of a rule -
         * when/if/then, and which kinds exist - reaches the caller from the tool
         * itself rather than only from prose it may not have read. The nested
         * bodies stay open because each kind carries different fields.
         */
        private val RULE_SCHEMA: JsonObject = buildJsonObject {
            put(
                "description",
                "For create: the whole rule, {id, when, if?, then, description?, guard?}. " +
                    "For update, describe, enable, disable, delete, test and run: the rule's exact id as a string.",
            )
            put(
                "properties",
                buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Lowercase letters, digits, \"-\" and \"_\".")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "One line a person can read back.")
                    })
                    put("enabled", buildJsonObject { put("type", "boolean") })
                    put("when", buildJsonObject {
                        put("type", "object")
                        put("description", "The one thing that starts it. Its other fields depend on \"type\".")
                        put("properties", buildJsonObject {
                            put("type", buildJsonObject {
                                put("type", "string")
                                put("enum", enumOf(AutomationTriggerKind.WIRE_NAMES))
                            })
                        })
                        put("required", enumOf(listOf("type")))
                        put("additionalProperties", true)
                    })
                    put("if", buildJsonObject {
                        put("type", "array")
                        put("description", "Tests that must all hold. At most ${AutomationRule.MAX_CONDITIONS}.")
                        put("maxItems", AutomationRule.MAX_CONDITIONS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("type", buildJsonObject {
                                    put("type", "string")
                                    put("enum", enumOf(AutomationCondition.Kind.WIRE_NAMES))
                                })
                            })
                            put("required", enumOf(listOf("type")))
                            put("additionalProperties", true)
                        })
                    })
                    put("then", buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "What it does, at most ${AutomationRule.MAX_ACTIONS} actions. A longer " +
                                "sequence belongs in a workflow the rule names.",
                        )
                        put("maxItems", AutomationRule.MAX_ACTIONS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("type", buildJsonObject {
                                    put("type", "string")
                                    put("enum", enumOf(AutomationActionKind.WIRE_NAMES))
                                })
                            })
                            put("required", enumOf(listOf("type")))
                            put("additionalProperties", true)
                        })
                    })
                    put("guard", buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "How often it may fire: cooldownMinutes, maxPerDay, and validForMinutes " +
                                "(how late a run is still the right run).",
                        )
                        put("additionalProperties", true)
                    })
                },
            )
        }

        val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            ToolDefinition(
                name = "automation_rule",
                description = DESCRIPTION,
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("description", DESCRIPTION)
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "mode",
                                buildJsonObject {
                                    put("type", "string")
                                    put("enum", JsonArray(MODES.map { JsonPrimitive(it) }))
                                    put(
                                        "description",
                                        "create, update (change part of a rule), list, describe, enable, disable, " +
                                            "delete, test (decide without doing) or run (fire it now for real). " +
                                            "Defaults to list.",
                                    )
                                },
                            )
                            put("rule", RULE_SCHEMA)
                            put(
                                "changes",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "description",
                                        "update only: the keys of the rule to change (when, if, then, guard, " +
                                            "description, enabled). Each replaces the old value whole; null removes it.",
                                    )
                                    put("additionalProperties", true)
                                },
                            )
                            put(
                                "replace",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "create only: overwrite a rule with the same id. Without it, create refuses.",
                                    )
                                },
                            )
                            put(
                                "event",
                                buildJsonObject {
                                    put("type", "object")
                                    put("description", "test only: what to pretend happened, written like a trigger.")
                                    // The fields differ per trigger kind, so
                                    // the keys stay open rather than wrong.
                                    put("additionalProperties", true)
                                },
                            )
                            put(
                                "now",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "test only: the moment to pretend it is, ISO-8601. Defaults to now.")
                                },
                            )
                            put(
                                "places",
                                buildJsonObject {
                                    put("type", "array")
                                    put("description", "test only: places the phone is inside at that moment.")
                                    put("items", buildJsonObject { put("type", "string") })
                                },
                            )
                            put(
                                "deviceState",
                                buildJsonObject {
                                    put("type", "object")
                                    put("description", "test only: device signals by name, e.g. {\"charging\":\"true\"}.")
                                    put("additionalProperties", buildJsonObject { put("type", "string") })
                                },
                            )
                            put(
                                "userReachable",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "test only: whether the user could answer. Defaults to true.")
                                },
                            )
                            put(
                                "agentAvailable",
                                buildJsonObject {
                                    put("type", "boolean")
                                    put("description", "test only: whether a turn could run. Defaults to true.")
                                },
                            )
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("mode"))))
                },
            ),
        )

        const val DESCRIPTION: String =
            "Standing rules: when something happens, and the conditions hold, do this. " +
                "A rule is when/if/then. \"when\" is one of schedule (at:\"19:00\", days, or " +
                "everyMinutes), place (enter/exit a named place), notification (a named package, " +
                "optionally from someone), device_state, or manual. \"if\" is any number of " +
                "time_between (wraps past midnight), day_of_week, at_place, text (on a field such " +
                "as notification.text) and device_state tests, all of which must hold. \"then\" is " +
                "up to four actions from run_workflow, open_intent, notify, agent_turn, voice_call " +
                "and ask. Use {{notification.text}} and the other event fields in an action to pass " +
                "what happened into it — and only the fields you actually write are ever sent " +
                "anywhere, so do not interpolate a message body you do not need. " +
                "The action kinds carry the cost: run_workflow, open_intent and notify run on " +
                "their own; agent_turn spends a thinking turn; voice_call and ask need the user " +
                "present and are held when they are not. Prefer the cheapest kind that does the " +
                "job — a fixed sequence belongs in a workflow the rule names, not in an " +
                "agent_turn that re-derives it every night. Always dry-run a new rule with " +
                "mode:\"test\" before telling the user it works. To change a rule use mode:\"update\" " +
                "with only the keys that change; create refuses an id that exists unless replace:true. " +
                "Update, enable, disable, delete and run need the rule's exact id. Use mode:\"run\" to fire one " +
                "now for real — naming a rule supplies its trigger, so a scheduled rule can be " +
                "proved without waiting for its hour, while its conditions and limits still apply."
    }
}
