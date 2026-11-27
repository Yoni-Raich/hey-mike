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

package dev.androidagent.enginecodex

import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections

class CodexUserMemoryTest {

    @Test fun dynamicInstructionsAppendLiveMemoryAndOmitWhenEmpty() {
        var current: String? = null
        val profile = CodexEngine.PHONE_PROFILE.copy(dynamicInstructions = { current })
        assertTrue(profile.developerInstructions.contains("user_memory"))
        assertEquals(profile.developerInstructions, profile.effectiveInstructions())

        current = "[User memory]\n- Lives in Israel"
        val first = profile.effectiveInstructions()
        assertTrue(first.startsWith(profile.developerInstructions))
        assertTrue(first.endsWith("[User memory]\n- Lives in Israel"))

        current = "   "
        assertFalse(profile.effectiveInstructions().contains("[User memory]"))
    }

    @Test fun oneShotRunnerRunsEphemeralToollessTurnAndReturnsFinalReply() = runBlocking {
        val server = FakeServer(
            deltas = listOf("## About\n", "- Prefers Hebrew"),
            completedText = "## About\n- Prefers Hebrew",
            askTool = true,
        )
        val reply = CodexOneShotRunner(server.host, timeoutMs = 5_000).complete(
            instructions = "Maintain user memory.",
            prompt = "CURRENT MEMORY PROFILE:\n(empty)",
        )
        assertEquals("## About\n- Prefers Hebrew", reply)
        val thread = server.params("thread/start")
        assertEquals(true, thread["ephemeral"]?.jsonPrimitive?.boolean)
        assertEquals("read-only", thread["sandbox"]?.jsonPrimitive?.content)
        assertEquals("never", thread["approvalPolicy"]?.jsonPrimitive?.content)
        assertEquals("Maintain user memory.", thread["developerInstructions"]?.jsonPrimitive?.content)
        assertNull("no tools in one-shot run", thread["dynamicTools"])
        val turn = server.params("turn/start")
        assertEquals("CURRENT MEMORY PROFILE:\n(empty)", turn["input"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertNotNull("tool requests are refused", server.toolAnswer?.get("error"))
        assertTrue("process stopped after completion", server.stopped)
    }

    @Test fun oneShotRunnerFallsBackToStreamedDeltasWhenCompletedItemIsAbsent() = runBlocking {
        val server = FakeServer(deltas = listOf("NO_", "CHANGES"), completedText = null)
        val reply = CodexOneShotRunner(server.host, timeoutMs = 5_000).complete("inst", "prompt")
        assertEquals("NO_CHANGES", reply)
        assertTrue(server.stopped)
    }

    private class FakeServer(
        private val deltas: List<String>,
        private val completedText: String?,
        private val askTool: Boolean = false,
    ) {
        private val serverIn = PipedInputStream()
        private val clientOut = PipedOutputStream(serverIn)
        private val clientIn = PipedInputStream()
        private val serverOut = PipedOutputStream(clientIn)
        private val writer = serverOut.bufferedWriter()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        fun params(method: String) = requests.first { it["method"]?.jsonPrimitive?.content == method }["params"]!!.jsonObject
        @Volatile var stopped = false
        @Volatile var toolAnswer: JsonObject? = null

        private val process = object : Process() {
            override fun isAlive() = !stopped
            override fun getOutputStream() = clientOut
            override fun getInputStream() = clientIn
            override fun getErrorStream() = ByteArray(0).inputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { stopped = true; runCatching { serverOut.close() } }
        }
        val host = object : RuntimeHost {
            override val status = MutableStateFlow(RuntimeStatus())
            override val homeDirectory = File("home")
            override suspend fun prepare() = Unit
            override suspend fun startAppServer() = process
            override suspend fun stop() { process.destroy() }
        }

        init {
            scope.launch {
                val reader = serverIn.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val message = Json.parseToJsonElement(line).jsonObject
                    val method = message["method"]?.jsonPrimitive?.content
                    if (method == null) {
                        if (message["id"]?.jsonPrimitive?.content == "901") toolAnswer = message
                        continue
                    }
                    requests += message
                    val id = message["id"] ?: continue
                    when (method) {
                        "model/list" -> write("""{"id":$id,"result":{"data":[{"model":"gpt-default","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low"}]}]}}""")
                        "thread/start" -> write("""{"id":$id,"result":{"thread":{"id":"t1"}}}""")
                        "turn/start" -> {
                            write("""{"id":$id,"result":{"turn":{"id":"u1","status":"inProgress"}}}""")
                            if (askTool) write("""{"id":901,"method":"item/tool/call","params":{"threadId":"t1","turnId":"u1"}}""")
                            for (chunk in deltas) {
                                val escaped = chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                                write("""{"method":"item/agentMessage/delta","params":{"threadId":"t1","turnId":"u1","delta":"$escaped"}}""")
                            }
                            if (completedText != null) {
                                val escaped = completedText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                                write("""{"method":"item/completed","params":{"threadId":"t1","turnId":"u1","item":{"type":"agentMessage","text":"$escaped"}}}""")
                            }
                            delay(50)
                            write("""{"method":"turn/completed","params":{"threadId":"t1","turn":{"id":"u1","status":"completed"}}}""")
                        }
                        else -> write("""{"id":$id,"result":{}}""")
                    }
                }
            }
        }

        private fun write(message: String) = synchronized(writer) { writer.write(message); writer.newLine(); writer.flush() }
    }
}
