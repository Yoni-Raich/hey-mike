/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Launch and stream-json compatibility check; no sign-in, user message, tools or persisted session. */
object ClaudeRuntimeProbe {
    val INITIALIZE_ARGS = listOf(
        "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        "--tools", "", "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence",
    )

    suspend fun verify(version: String, start: suspend (List<String>) -> Process): Boolean {
        val reported = command(start(listOf("--version"))) ?: return false
        if (reported.trim() != "$version (Claude Code)") return false
        return withContext(Dispatchers.IO) {
            val process = start(INITIALIZE_ARGS)
            try {
                launch { drain(process.errorStream) }
                val reply = async {
                    process.inputStream.bufferedReader().use { reader ->
                        var total = 0
                        val line = StringBuilder()
                        while (total++ < MAX_OUTPUT) {
                            val char = reader.read()
                            if (char < 0) break
                            if (char == 10) {
                                if (acceptsInitialize(line.toString())) return@async true
                                line.setLength(0)
                            } else line.append(char.toChar())
                        }
                        false
                    }
                }
                process.outputStream.write(REQUEST.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
                withTimeoutOrNull(TIMEOUT_MS) { reply.await() } == true
            } finally {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }

    private suspend fun command(process: Process): String? = withContext(Dispatchers.IO) {
        try {
            process.outputStream.close()
            launch { drain(process.errorStream) }
            val output = async {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (bytes.size() <= 4096) {
                    val count = process.inputStream.read(buffer, 0, minOf(buffer.size, 4097 - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
            val bytes = withTimeoutOrNull(TIMEOUT_MS) { output.await() } ?: return@withContext null
            if (bytes.size > 4096 || !process.waitFor(1000, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else bytes.toString(Charsets.UTF_8)
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    internal fun acceptsInitialize(line: String): Boolean = runCatching {
        val frame = Json.parseToJsonElement(line) as JsonObject
        if (frame["type"]?.jsonPrimitive?.content != "control_response") return@runCatching false
        val response = frame["response"] as JsonObject
        if (response["request_id"]?.jsonPrimitive?.content != "mike-update-check" ||
            response["subtype"]?.jsonPrimitive?.content != "success") return@runCatching false
        val models = (response["response"] as JsonObject)["models"] as? JsonArray ?: return@runCatching false
        models.isNotEmpty() && models.all { (it as? JsonObject)?.get("value")?.jsonPrimitive?.content?.isNotBlank() == true }
    }.getOrDefault(false)

    private fun drain(stream: java.io.InputStream) {
        // Killing or closing a probe can break a concurrent pipe read.
        runCatching { stream.use { input ->
            val buffer = ByteArray(8192)
            while (input.read(buffer) >= 0) Unit
        } }
    }

    private const val TIMEOUT_MS = 30_000L
    private const val MAX_OUTPUT = 1024 * 1024
    private const val REQUEST = "{\"type\":\"control_request\",\"request_id\":\"mike-update-check\",\"request\":{\"subtype\":\"initialize\"}}\n"
}
