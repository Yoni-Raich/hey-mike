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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * One semantic UI observation model shared by every device backend.
 *
 * The uiautomator XML dump and the accessibility node tree both reduce to
 * [UiNode], and both render through [UiObservationSerializer], so the model
 * sees one schema and one failure taxonomy no matter which backend answered.
 * The node *lists* still differ between backends — different traversal roots
 * and different inclusion rules — which is why the envelope carries `source`.
 */
data class UiNode(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: List<Int>?,
    val enabled: Boolean,
    val clickable: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val packageName: String?,
    /** True when ACTION_SET_TEXT can target this node. */
    val editable: Boolean = false,
    /** Selection state for tabs, chips and list choices. */
    val selected: Boolean = false,
    /** Semantic range exposed by Accessibility, for example a SeekBar. */
    val range: UiRange? = null,
    /** True when Accessibility exposes ACTION_SET_PROGRESS for [range]. */
    val supportsSetProgress: Boolean = false,
    /** True for password fields. Their text is never emitted, whatever the backend reported. */
    val password: Boolean = false,
    /**
     * True for a switch, checkbox or radio — anything with an on/off state.
     *
     * [checked] is meaningless without it: a plain button reports `checked`
     * false, and "the switch is off" and "this is not a switch" are different
     * answers to "did the toggle take effect".
     */
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val clickableAncestor: UiNode? = null,
    /**
     * Nearest ancestor that was itself emitted, or null for a root.
     *
     * Deliberately never serialized: it exists so [UiQuery.rootNodeId] can cut
     * a subtree out of the flat list, and emitting it on every node would
     * spend the character budget the subtree query is there to save.
     */
    val parentId: String? = null,
) {
    fun isMeaningful(): Boolean =
        text != null || contentDescription != null || resourceId != null ||
            clickable || scrollable || focused || editable || selected || range != null ||
            supportsSetProgress || !enabled || checkable

    fun toJson(): JsonObject = buildJsonObject {
        put("nodeId", nodeId)
        if (password) {
            // Never emit the contents of a password field, even when the
            // platform handed us the characters rather than a mask.
            put("password", true)
        } else {
            text?.let { put("text", UiObservationSerializer.safeField(it)) }
        }
        contentDescription?.let { put("contentDescription", UiObservationSerializer.safeField(it)) }
        resourceId?.let { put("resourceId", it) }
        className?.let { put("class", it) }
        bounds?.let { values ->
            put("bounds", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
        put("enabled", enabled)
        put("clickable", clickable)
        put("scrollable", scrollable)
        put("focused", focused)
        if (editable) put("editable", true)
        if (selected) put("selected", true)
        range?.let { value ->
            put("range", buildJsonObject {
                put("min", value.min)
                put("max", value.max)
                put("current", value.current)
            })
        }
        if (supportsSetProgress) {
            put("actions", buildJsonArray { add("SET_PROGRESS") })
        }
        // Only for a node that has a state to report. Emitting "checked":false
        // on every label would cost the character budget for no information.
        if (checkable) {
            put("checkable", true)
            put("checked", checked)
        }
        clickableAncestor?.let { ancestor ->
            put("clickableAncestor", buildJsonObject {
                put("nodeId", ancestor.nodeId)
                ancestor.bounds?.let { values ->
                    put("bounds", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                }
                ancestor.className?.let { put("class", it) }
            })
        }
    }

    /** A parent reference carries position only; its own labels belong to the child. */
    fun asClickTarget(): UiNode = copy(
        text = null,
        contentDescription = null,
        resourceId = null,
        packageName = null,
        editable = false,
        selected = false,
        range = null,
        supportsSetProgress = false,
        password = false,
        checkable = false,
        checked = false,
        clickableAncestor = null,
        parentId = null,
    )
}

data class UiRange(val min: Double, val max: Double, val current: Double)

/** A parsed screen, before it is rendered for the model. */
data class UiObservation(val activePackage: String?, val nodes: List<UiNode>)

/**
 * The one `read_ui` description, so both backends advertise the same tool.
 *
 * Codex binds the tool list once per thread, so this text is the only chance
 * to teach the model that a truncated screen is recoverable.
 */
const val READ_UI_DESCRIPTION: String =
    "Read a bounded compact semantic UI observation. Returns labeled/actionable nodes by " +
        "default; use raw=true only for debug XML. A large screen does not fit in one reply: " +
        "the reply then carries \"truncated\":true with \"nextOffset\", and calling read_ui " +
        "again with that offset returns the next page. Narrow it instead with text, " +
        "resourceId, class or package (case-insensitive substrings), rootNodeId (that node " +
        "and its descendants), clickableOnly or scrollableOnly; maxNodes and maxChars lower " +
        "the caps. A filter changes only what is listed — every node is still on screen and " +
        "its id stays valid for tap_node, set_text, set_progress and scroll_node. When both the screen and " +
        "the query are identical to the previous observation the reply is \"unchanged\":true " +
        "with \"unchangedSinceRevision\" instead of the node list — reuse the nodes from that " +
        "revision, or pass force=true to resend them. Timeout or idle failures are typed and " +
        "do not trigger a second dump."

/** The actions `act_and_observe` may wrap. */
val ACT_AND_OBSERVE_ACTIONS: Set<String> = setOf("tap", "swipe", "key", "open_app", "type_text")

/**
 * The one `act_and_observe` definition, so every backend advertises the same
 * tool. The accessibility backend serving it is what keeps the tool the
 * system prompt recommends working with Wireless ADB off.
 */
val ACT_AND_OBSERVE_DEFINITION: ToolDefinition = ToolDefinition(
    "act_and_observe",
    "Perform ONE known action and return a fresh UI observation in one call. Saves a model round trip. " +
        "Never batch speculative actions. If actionCompleted=true but observation failed, do not repeat the action.",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { ACT_AND_OBSERVE_ACTIONS.forEach { add(it) } })
            })
            put("arguments", buildJsonObject { put("type", "object"); put("additionalProperties", true) })
        })
        put("required", buildJsonArray { add("action"); add("arguments") })
        put("additionalProperties", false)
    },
)

/**
 * A focused request for part of one observation.
 *
 * A busy screen does not fit in one reply, and a reply that silently dropped
 * the tail left the model with no way to ask for the rest. Every field here
 * narrows only what is *emitted*: the backend still reads the whole screen, so
 * node ids stay stable across a filtered call and a node an action refers to
 * keeps working even when a later query does not list it.
 *
 * String filters are case-insensitive substring matches, so `package="whatsapp"`
 * finds `com.whatsapp`. Several filters are combined with AND.
 */
data class UiQuery(
    /** Substring of `text` or `contentDescription`. */
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    /** Emit only this node and its descendants. */
    val rootNodeId: String? = null,
    val clickableOnly: Boolean = false,
    val scrollableOnly: Boolean = false,
    /** Matching nodes to skip. The cursor a truncated reply hands back. */
    val offset: Int = 0,
    val maxNodes: Int? = null,
    val maxChars: Int? = null,
) {
    /** True when this asks for the whole screen, the way `read_ui` always did. */
    val isEmpty: Boolean
        get() = text == null && resourceId == null && className == null &&
            packageName == null && rootNodeId == null && !clickableOnly &&
            !scrollableOnly && offset == 0 && maxNodes == null && maxChars == null

    /** The reply ceiling. A caller may lower it, never raise it. */
    fun charBudget(): Int =
        (maxChars ?: UiObservationSerializer.MAX_OUTPUT_CHARS)
            .coerceIn(MIN_OUTPUT_CHARS, UiObservationSerializer.MAX_OUTPUT_CHARS)

    fun matches(node: UiNode): Boolean {
        if (clickableOnly && !node.clickable && node.clickableAncestor == null) return false
        if (scrollableOnly && !node.scrollable) return false
        if (packageName != null && node.packageName?.contains(packageName, true) != true) return false
        if (className != null && node.className?.contains(className, true) != true) return false
        if (resourceId != null && node.resourceId?.contains(resourceId, true) != true) return false
        if (text != null) {
            // A password node never emits its text, so it can only be found by
            // its description. Matching the hidden value would leak it one
            // probe at a time.
            val label = node.contentDescription
            val body = if (node.password) null else node.text
            if (label?.contains(text, true) != true && body?.contains(text, true) != true) return false
        }
        return true
    }

    /** Echoed back so the model sees which filters produced this reply. */
    fun toJson(): JsonObject = buildJsonObject {
        text?.let { put("text", it) }
        resourceId?.let { put("resourceId", it) }
        className?.let { put("class", it) }
        packageName?.let { put("package", it) }
        rootNodeId?.let { put("rootNodeId", it) }
        if (clickableOnly) put("clickableOnly", true)
        if (scrollableOnly) put("scrollableOnly", true)
        if (offset > 0) put("offset", offset)
        maxNodes?.let { put("maxNodes", it) }
        maxChars?.let { put("maxChars", it) }
    }

    companion object {
        val ALL = UiQuery()

        /** Below this a reply could not carry a useful node, so it is the floor. */
        const val MIN_OUTPUT_CHARS = 1_000

        /**
         * Read the query out of the tool arguments.
         *
         * Out-of-range numbers are clamped rather than rejected: a bad
         * `offset` should still return the screen, not an error the model has
         * to recover from.
         */
        fun from(arguments: JsonObject): UiQuery = UiQuery(
            text = arguments.queryString("text"),
            resourceId = arguments.queryString("resourceId"),
            className = arguments.queryString("class"),
            packageName = arguments.queryString("package"),
            rootNodeId = arguments.queryString("rootNodeId"),
            clickableOnly = arguments.queryBoolean("clickableOnly"),
            scrollableOnly = arguments.queryBoolean("scrollableOnly"),
            offset = arguments.queryInt("offset")?.coerceAtLeast(0) ?: 0,
            maxNodes = arguments.queryInt("maxNodes")
                ?.coerceIn(1, UiObservationSerializer.MAX_UI_NODES),
            maxChars = arguments.queryInt("maxChars"),
        )

        private fun JsonObject.queryString(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?.take(UiObservationSerializer.MAX_UI_FIELD_CHARS)

        private fun JsonObject.queryBoolean(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        private fun JsonObject.queryInt(key: String): Int? =
            (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    }
}

/**
 * What a filtered or truncated reply has to say about the nodes it left out.
 *
 * This is the half the bug was missing: knowing that something was dropped is
 * useless without the cursor that retrieves it.
 */
data class UiPage(
    /** Nodes the backend read, before any filter. */
    val totalNodes: Int,
    /** Nodes the query selected, before the character budget. */
    val matchedNodes: Int,
    val offset: Int,
    val returnedNodes: Int,
    val query: UiQuery,
) {
    /** Where to continue, or null when this reply carries the last match. */
    val nextOffset: Int? = (offset + returnedNodes).takeIf { it < matchedNodes }

    fun hint(): String? = when {
        nextOffset != null ->
            "Nodes ${offset + 1}-${offset + returnedNodes} of $matchedNodes matching. Call " +
                "read_ui again with offset=$nextOffset for the next page, or narrow it with " +
                "text, resourceId, class, package, rootNodeId, clickableOnly or scrollableOnly."
        returnedNodes == 0 && matchedNodes > 0 ->
            "offset $offset is past the last of $matchedNodes matching nodes. Call read_ui " +
                "with a smaller offset."
        matchedNodes == 0 && !query.isEmpty ->
            "No node matched this query; $totalNodes nodes are on screen. Call read_ui " +
                "without filters to see what is there."
        else -> null
    }
}

/**
 * Identity of the last observation handed to the model. [backend] scopes
 * unchanged-suppression: a fall-through from one gateway to another produces a
 * different node set, so an unchanged reply across backends would be a lie.
 */
data class ObservationFingerprint(val digest: String, val revision: Long, val backend: String)

/** The outcome of rendering one observation for the model. */
data class RenderedObservation(
    val text: String,
    val fingerprint: ObservationFingerprint?,
    val unchanged: Boolean,
    /** False when the query itself was rejected; the caller reports a failure. */
    val ok: Boolean = true,
)

/**
 * Shared revision counter and last-observation memory.
 *
 * One instance per process, handed to every gateway. Per-gateway counters
 * would make `revision` jump backwards when a call falls through to another
 * backend, and `unchangedSinceRevision` could then name a revision produced by
 * a different backend with a different node set.
 */
class ObservationState {
    private val revision = AtomicLong(0L)

    @Volatile
    private var last: ObservationFingerprint? = null

    fun nextRevision(): Long = revision.incrementAndGet()

    fun last(): ObservationFingerprint? = last

    fun record(fingerprint: ObservationFingerprint) {
        last = fingerprint
    }

    /**
     * Forget the last observation, so the next successful one carries a full
     * payload. Called when a run begins and after any failed observation: the
     * screen is then unknown, and a diff across a gap in knowledge is wrong.
     */
    fun reset() {
        last = null
    }
}

object UiObservationSerializer {
    const val MAX_OUTPUT_CHARS = 20_000
    const val MAX_UI_FIELD_CHARS = 256
    const val MAX_UI_NODES = 5_000

    /** Trim, cap and drop an empty attribute the way every backend must. */
    fun compactField(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_UI_FIELD_CHARS)

    /**
     * On-screen text reaches the model, so anything that looks like a token
     * leaves the device redacted. Applied at emit time so both backends and
     * every future one inherit it.
     */
    fun safeField(value: String): String =
        SecretRedactor.redactUiText(value).take(MAX_UI_FIELD_CHARS)

    fun semanticJson(
        observation: UiObservation,
        source: String,
        observationId: String,
        revision: Long,
        elapsedMs: Long,
        truncated: Boolean,
        stable: Boolean,
        /** Omitted only by callers that render a node list they never narrowed. */
        page: UiPage? = null,
    ): String = buildJsonObject {
        put("ok", true)
        put("observationId", observationId)
        put("revision", revision)
        put("elapsedMs", elapsedMs)
        put("source", source)
        put("stable", stable)
        observation.activePackage?.let { put("activePackage", it) }
        put("truncated", truncated)
        // Before the nodes, so a model that stops reading early still learns
        // that there is more and how to ask for it.
        page?.let { window ->
            put("totalNodes", window.totalNodes)
            put("returnedNodes", window.returnedNodes)
            if (!window.query.isEmpty) {
                put("matchedNodes", window.matchedNodes)
                put("query", window.query.toJson())
            }
            if (window.offset > 0) put("offset", window.offset)
            window.nextOffset?.let { put("nextOffset", it) }
            window.hint()?.let { put("hint", it) }
        }
        put("nodes", buildJsonArray { observation.nodes.forEach { add(it.toJson()) } })
    }.toString()

    fun unchangedJson(
        activePackage: String?,
        nodeCount: Int,
        source: String,
        observationId: String,
        revision: Long,
        elapsedMs: Long,
        unchangedSinceRevision: Long,
    ): String = buildJsonObject {
        put("ok", true)
        put("observationId", observationId)
        put("revision", revision)
        put("elapsedMs", elapsedMs)
        put("source", source)
        put("stable", true)
        activePackage?.let { put("activePackage", it) }
        put("unchanged", true)
        put("unchangedSinceRevision", unchangedSinceRevision)
        put("nodeCount", nodeCount)
        put(
            "hint",
            "Screen is identical to revision $unchangedSinceRevision. Reuse those nodes; " +
                "if the previous action was meant to change the screen it did not take effect. " +
                "Call read_ui with force=true to resend the full node list.",
        )
    }.toString()

    /**
     * The one failure envelope. [remedy] and [alternatives] tell the model what
     * to do instead, so an unavailable backend is actionable rather than a
     * dead end.
     */
    fun failureJson(
        observationId: String,
        revision: Long,
        elapsedMs: Long,
        errorType: String,
        message: String,
        remedy: String? = null,
        alternatives: List<String> = emptyList(),
        reasons: List<String> = emptyList(),
    ): String = buildJsonObject {
        put("ok", false)
        put("observationId", observationId)
        put("revision", revision)
        put("elapsedMs", elapsedMs)
        put("source", "none")
        put("stable", false)
        put("errorType", errorType)
        put("message", message.take(MAX_UI_FIELD_CHARS))
        remedy?.let { put("remedy", it.take(MAX_UI_FIELD_CHARS)) }
        if (alternatives.isNotEmpty()) {
            put("alternatives", buildJsonArray { alternatives.forEach { add(JsonPrimitive(it)) } })
        }
        if (reasons.isNotEmpty()) {
            put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it.take(MAX_UI_FIELD_CHARS))) } })
        }
    }.toString()

    /**
     * Digest of exactly what the model would receive, so a screen that merely
     * re-renders identically is recognised. Bounds are part of the node JSON,
     * so any real movement changes the digest.
     */
    fun digest(
        activePackage: String?,
        nodes: List<UiNode>,
        query: UiQuery = UiQuery.ALL,
    ): String {
        val payload = buildJsonObject {
            activePackage?.let { put("activePackage", it) }
            put("nodes", buildJsonArray { nodes.forEach { add(it.toJson()) } })
            // The same screen answers two different queries differently, so a
            // query is part of the identity of a reply. An empty one adds
            // nothing, which keeps every unfiltered digest what it always was.
            if (!query.isEmpty) put("query", query.toJson())
        }.toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Unchanged-suppression plus the truncation loop, once, for every backend.
     * The caller records [RenderedObservation.fingerprint] only on success.
     */
    /**
     * The nodes a [query] selects, in traversal order.
     *
     * `rootNodeId` leans on pre-order: an emitted node always follows its
     * emitted ancestors, so one forward pass resolves a whole subtree without
     * a parent index.
     */
    fun select(nodes: List<UiNode>, query: UiQuery): List<UiNode> {
        val scoped = query.rootNodeId?.let { root ->
            val subtree = mutableSetOf(root)
            nodes.filter { node ->
                val inside = node.nodeId == root || (node.parentId != null && node.parentId in subtree)
                if (inside) subtree += node.nodeId
                inside
            }
        } ?: nodes
        return scoped.filter(query::matches)
    }

    /**
     * Unchanged-suppression, query selection and paging, once, for every
     * backend. The caller records [RenderedObservation.fingerprint] only on
     * success, and reports [RenderedObservation.ok] as the tool outcome.
     */
    fun render(
        observation: UiObservation,
        source: String,
        backend: String,
        observationId: String,
        revision: Long,
        elapsedMs: Long,
        previous: ObservationFingerprint?,
        force: Boolean,
        stable: Boolean,
        query: UiQuery = UiQuery.ALL,
    ): RenderedObservation {
        if (query.rootNodeId != null && observation.nodes.none { it.nodeId == query.rootNodeId }) {
            // An empty node list would read as "that part of the screen is
            // empty", which is a different and wrong answer.
            return RenderedObservation(
                text = failureJson(
                    observationId = observationId,
                    revision = revision,
                    elapsedMs = elapsedMs,
                    errorType = "ui_unknown_node",
                    message = "rootNodeId \"${query.rootNodeId}\" is not on the current screen.",
                    remedy = "Call read_ui without rootNodeId, then use an id from that reply.",
                ),
                fingerprint = null,
                unchanged = false,
                ok = false,
            )
        }
        val matched = select(observation.nodes, query)
        val fingerprint = ObservationFingerprint(
            digest = digest(observation.activePackage, observation.nodes, query),
            revision = revision,
            backend = backend,
        )
        if (!force && previous != null && previous.backend == backend && previous.digest == fingerprint.digest) {
            // The screen is byte-identical to what the model already holds, and
            // so is the question it asked of it. Acknowledge instead of
            // resending the whole node list.
            return RenderedObservation(
                text = unchangedJson(
                    observation.activePackage, matched.size, source,
                    observationId, revision, elapsedMs, previous.revision,
                ),
                fingerprint = null,
                unchanged = true,
            )
        }
        val window = matched.drop(query.offset).let { rest -> query.maxNodes?.let(rest::take) ?: rest }
        val render = { count: Int ->
            semanticJson(
                observation = observation.copy(nodes = window.take(count)),
                source = source,
                observationId = observationId,
                revision = revision,
                elapsedMs = elapsedMs,
                truncated = query.offset + count < matched.size,
                stable = stable,
                page = UiPage(
                    totalNodes = observation.nodes.size,
                    matchedNodes = matched.size,
                    offset = query.offset,
                    returnedNodes = count,
                    query = query,
                ),
            )
        }
        val returned = fitCount(window.size, query.charBudget(), render)
        return RenderedObservation(text = render(returned), fingerprint = fingerprint, unchanged = false)
    }

    /**
     * The largest prefix that fits [budget], and never fewer than one node.
     *
     * A binary search rather than the shrink-by-an-eighth loop this replaces:
     * that one re-serialized a 5000-node screen dozens of times to converge,
     * and paging makes an oversized screen the normal case rather than the
     * exceptional one.
     */
    private fun fitCount(size: Int, budget: Int, render: (Int) -> String): Int {
        if (size == 0) return 0
        if (render(size).length <= budget) return size
        var low = 1
        var high = size
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (render(mid).length <= budget) low = mid else high = mid - 1
        }
        return low
    }
}
