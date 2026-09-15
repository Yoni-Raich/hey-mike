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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Exposes the cross-chat knowledge store as two tools.
 *
 * It rides in the composite alongside the device backends because that is the
 * one place a tool name reaches the model without new plumbing, not because
 * remembering something is a device action — it touches no device at all,
 * which is why [needsControl] is false for both.
 *
 * Recall is a tool rather than a block injected into every prompt on purpose.
 * The store grows without limit and the prompt does not; the model asks about
 * the package it is actually driving, and pays for nothing else.
 */
class KnowledgeToolGateway(
    private val store: KnowledgeStore,
) : DeviceToolGateway {

    @Volatile private var revoked = true

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        revoked = false
    }

    override fun revoke() {
        revoked = true
    }

    /** Neither tool touches the screen, so neither needs the control banner. */
    override fun needsControl(name: String): Boolean = false

    /** A local store: always ready, but it cannot operate the phone. */
    override fun deviceBackendLive(): Boolean = false

    override fun statusLine(): String {
        val packages = store.packages()
        return "Knowledge: " + if (packages.isEmpty()) {
            "empty"
        } else {
            "${packages.size} app(s)"
        }
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
        return when (name) {
            "recall_capability" -> recall(arguments)
            "remember_capability" -> remember(arguments)
            else -> throw ToolNotServiceable(
                "knowledge_unsupported",
                "The knowledge store does not implement \"$name\".",
            )
        }
    }

    override suspend fun cancel() {
        // Both operations are a single bounded file read or write.
    }

    private fun recall(arguments: JsonObject): ToolResult {
        val pkg = arguments.string("package")
            ?: throw IllegalArgumentException("package is required")
        val limit = arguments["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: KnowledgeStore.DEFAULT_SUMMARY_LIMIT
        return ToolResult(store.summary(pkg, limit).toString())
    }

    private fun remember(arguments: JsonObject): ToolResult {
        val record = KnowledgeStore.Record(
            packageName = arguments.string("package")
                ?: throw IllegalArgumentException("package is required"),
            screen = arguments.string("screen") ?: "",
            selector = arguments.string("selector")
                ?: throw IllegalArgumentException("selector is required"),
            does = arguments.string("does") ?: "",
            intent = arguments.string("intent"),
            hint = arguments.string("hint"),
            fallbacks = runCatching {
                arguments["fallbacks"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrDefault(emptyList()),
        )
        val stored = store.upsert(record)
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("package", stored.packageName)
                put("selector", stored.selector)
                put(
                    "note",
                    "Recorded. A later chat can find this with recall_capability, and it is " +
                        "marked stale after two months so it is rechecked rather than trusted " +
                        "forever.",
                )
            }.toString(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            tool(
                "recall_capability",
                "Look up what previous chats worked out about an app before exploring its UI. " +
                    "Returns durable selectors and deep links keyed by package. A record marked " +
                    "stale:true is a hint to verify, not a fact. Read-only.",
                mapOf("package" to "string", "limit" to "integer"),
                listOf("package"),
            ),
            tool(
                "remember_capability",
                "Record one thing you worked out about an app, so the next chat does not " +
                    "rediscover it. The selector must be a resourceId or a contentDescription " +
                    "from read_ui, never a bare coordinate pair — that stops being true on the " +
                    "next render. If the screen exposes nothing addressable at all (a canvas, a " +
                    "game, an unexposed WebView), use whatever description fits as the selector " +
                    "and put the coordinate in hint.",
                mapOf(
                    "package" to "string",
                    "screen" to "string",
                    "selector" to "string",
                    "does" to "string",
                    "intent" to "string",
                    "hint" to "string",
                    "fallbacks" to "array",
                ),
                listOf("package", "selector"),
            ),
        )

        fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
        ): ToolDefinition {
            val props = buildJsonObject {
                for ((key, type) in properties) put(key, buildJsonObject { put("type", type) })
            }
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("description", description)
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
            return ToolDefinition(name, description, schema)
        }
    }
}
