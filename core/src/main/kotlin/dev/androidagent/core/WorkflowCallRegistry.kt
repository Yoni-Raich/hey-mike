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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One device tool a workflow `call` step may invoke.
 *
 * Approval lives in the called tool itself, for direct and workflow calls
 * alike: the registry carries no confirmation flag, so there is exactly one
 * approval card and one foregrounding path per operation. A UI sequence that
 * must stop and ask still marks its step `requiresConfirmation`.
 *
 * @param commits whether invoking it may change something. A failure after a
 *   committing call is reported as possibly committed, never retried blindly.
 * @param readOnlyOperations operation names that never change anything, for
 *   tools that multiplex read-only and writing operations behind one name.
 *   The call's resolved `operation` argument picks which applies; anything
 *   else stays conservative (see [mayCommit]).
 */
data class WorkflowCallMetadata(
    val name: String,
    val commits: Boolean = true,
    val readOnlyOperations: Set<String> = emptySet(),
    val description: String = "",
) {
    /**
     * Whether a failure after invoking with these exact arguments counts as
     * possibly committed.
     *
     * `commits=false` always means false. Otherwise a valid string
     * `operation` naming a read-only operation means false; a missing,
     * non-string or unknown operation stays conservative true, since the tool
     * may have done anything.
     */
    fun mayCommit(arguments: JsonObject): Boolean {
        if (!commits) return false
        val operation = (arguments["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return operation == null || operation !in readOnlyOperations
    }
}

/**
 * The explicit allowlist for workflow `call` steps.
 *
 * A `call` names a target device tool and carries rich JSON args, but it is
 * not open dispatch: a name that is not registered here fails before anything
 * is invoked. This is deliberately plain data rather than a gateway reference,
 * so app wiring can build it from tool names without a construction cycle back
 * through the [CompositeDeviceToolGateway] that will route the calls.
 */
class WorkflowCallRegistry(tools: Map<String, WorkflowCallMetadata> = emptyMap()) {
    init {
        // A key that does not match its metadata routes one name while
        // describing another. Refuse it here rather than dispatching it.
        tools.entries.firstOrNull { (key, metadata) -> key != metadata.name }?.let { (key, metadata) ->
            throw IllegalArgumentException(
                "WorkflowCallRegistry key \"$key\" does not match metadata name \"${metadata.name}\".",
            )
        }
    }

    // Blocked names stay out even when registered by mistake: a workflow must
    // never become a way to run shell, install packages, or recurse into
    // itself.
    private val tools: Map<String, WorkflowCallMetadata> =
        tools.filterKeys { it !in BLOCKED_CALL_TOOLS }

    fun resolve(name: String): WorkflowCallMetadata? = tools[name]

    /** Whether a failure after calling [name] counts as possibly committed. Unknown names fail first. */
    fun commits(name: String): Boolean = tools[name]?.commits ?: true

    val names: Set<String> get() = tools.keys

    companion object {
        val EMPTY = WorkflowCallRegistry()

        /** Never callable from a workflow, even if registered. */
        val BLOCKED_CALL_TOOLS = setOf(
            "shell",
            "install_apk",
            "run_workflow",
            "workflow_runner",
            // A plan runs on the same runner, so a call step reaching it would
            // nest one run inside another and lose the budget and the ledger
            // that make either resumable.
            "act_plan",
            "save_workflow",
            "list_workflows",
        )

        /** A captured result larger than this fails instead of being stored. */
        const val MAX_CAPTURED_OUTPUT_CHARS = 8_000

        /** How many outputs one run may hold, bounding the resume state. */
        const val MAX_OUTPUT_BINDINGS = 16

        /**
         * The whole output map, serialized, may not exceed this. Together with
         * the per-binding and count limits this bounds the resume state, and
         * the gateway validates resume payloads against the same total, so
         * every resume the runner emits is accepted back.
         */
        const val MAX_TOTAL_OUTPUT_CHARS = 16_000
    }
}

/** A `{{outputs...}}` reference that names nothing, thrown before anything is dispatched. */
class WorkflowOutputException(val errorType: String, override val message: String) : Exception(message)

/**
 * Prior-output substitution for values the definition could not know when it
 * was written.
 *
 * `{{name}}` is a workflow parameter, bound before anything runs and unchanged
 * here. `{{outputs.<binding>}}` is a result an earlier `call` step captured
 * with `output`, optionally followed by `.key` segments into objects and
 * numeric segments into arrays, e.g. `{{outputs.contact.phones.0}}`.
 *
 * Like parameter binding, a string that is exactly one reference keeps the
 * value's JSON type, while a reference inside larger text is spliced in as
 * text. Anything unresolvable fails loudly rather than reaching the device as
 * a literal placeholder.
 */
object WorkflowOutputRefs {
    internal val REF_RE =
        Regex("\\{\\{\\s*outputs\\.([A-Za-z][A-Za-z0-9_]{0,31})((?:\\.[A-Za-z][A-Za-z0-9_]{0,31}|\\.\\d+)*)\\s*\\}\\}")

    /** Resolve every output reference in [element], recursively through objects and arrays. */
    fun resolve(element: JsonElement, outputs: Map<String, JsonElement>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolve(value, outputs) })
        is JsonArray -> JsonArray(element.map { resolve(it, outputs) })
        is JsonPrimitive -> if (!element.isString) element else resolveString(element.content, outputs)
        else -> element
    }

    /** Resolve references in one text template, always producing text. */
    fun resolveText(template: String, outputs: Map<String, JsonElement>): String {
        val whole = REF_RE.matchEntire(template.trim())
        if (whole != null) return asText(lookup(whole, outputs, template))
        return REF_RE.replace(template) { match -> asText(lookup(match, outputs, template)) }
    }

    private fun resolveString(text: String, outputs: Map<String, JsonElement>): JsonElement {
        val whole = REF_RE.matchEntire(text.trim())
        if (whole != null) return lookup(whole, outputs, text)
        if (!REF_RE.containsMatchIn(text)) return JsonPrimitive(text)
        return JsonPrimitive(REF_RE.replace(text) { match -> asText(lookup(match, outputs, text)) })
    }

    private fun lookup(
        match: MatchResult,
        outputs: Map<String, JsonElement>,
        template: String,
    ): JsonElement {
        val binding = match.groupValues[1]
        var current = outputs[binding]
            ?: throw WorkflowOutputException(
                "unknown_output",
                "There is no captured output called \"$binding\" in \"${template.take(120)}\". " +
                    if (outputs.isEmpty()) "No earlier step captured one."
                    else "Captured so far: " + outputs.keys.sorted().joinToString(", ") + ".",
            )
        val path = match.groupValues[2].split(".").filter { it.isNotEmpty() }
        for (segment in path) {
            current = when (current) {
                is JsonObject -> current[segment]
                    ?: throw WorkflowOutputException(
                        "unknown_output",
                        "\"$binding\" has no field \"$segment\".",
                    )
                is JsonArray -> {
                    val index = segment.toIntOrNull()
                        ?: throw WorkflowOutputException(
                            "unknown_output",
                            "\"$binding\" is a list, so \"$segment\" does not address into it; use a number.",
                        )
                    current.getOrNull(index)
                        ?: throw WorkflowOutputException(
                            "unknown_output",
                            "\"$binding\" has ${current.size} items, so index $index is out of range.",
                        )
                }
                else -> throw WorkflowOutputException(
                    "unknown_output",
                    "\"$binding\" has no path past its ${describe(current)} value.",
                )
            }
        }
        if (current is JsonNull) {
            throw WorkflowOutputException("unknown_output", "\"$binding\" captured null, which cannot be used here.")
        }
        return current
    }

    private fun asText(element: JsonElement): String =
        (element as? JsonPrimitive)?.content ?: element.toString()

    private fun describe(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "list"
        is JsonPrimitive -> if (element.isString) "text" else "number"
        else -> "empty"
    }
}
