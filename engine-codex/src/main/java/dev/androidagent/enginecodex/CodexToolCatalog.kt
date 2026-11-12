/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.enginecodex

import dev.androidagent.core.ToolDefinition
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-owned evidence of the tools used at thread/start, not at thread/resume. */
internal class CodexToolCatalog(workspace: File) {
    private val file = File(workspace, ".mike-codex-tools-v1.json")

    fun matches(threadId: String, tools: List<ToolDefinition>): Boolean {
        val saved = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return false
        return (saved["threadId"] as? JsonPrimitive)?.contentOrNull == threadId &&
            (saved["fingerprint"] as? JsonPrimitive)?.contentOrNull == fingerprint(tools)
    }

    fun record(threadId: String, tools: List<ToolDefinition>) {
        val text = buildJsonObject {
            put("threadId", threadId)
            put("fingerprint", fingerprint(tools))
        }.toString()
        val temporary = File.createTempFile(".mike-codex-tools-", ".tmp", file.parentFile)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun fingerprint(tools: List<ToolDefinition>): String {
        val payload = JsonArray(tools.sortedBy { it.name }.map { tool -> buildJsonObject {
            put("name", tool.name); put("description", tool.description); put("schema", tool.inputSchema)
        } }).toString()
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
