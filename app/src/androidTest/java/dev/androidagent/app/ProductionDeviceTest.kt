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

package dev.androidagent.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidagent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in physical tests. Runs only with -e productionDevice true; never contacts anyone. */
class ProductionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as AgentApplication).graph

    private fun optIn() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("productionDevice") == "true")
        assertEquals("A059", android.os.Build.MODEL)
    }
    private fun evidence(name: String, content: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "production-review").apply { mkdirs() }
        File(dir, "$name.txt").writeText(content)
    }

    @Test fun selfAdbPreservesRotationAndCombinedActionReturnsFreshObservation() = runBlocking {
        optIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(30_000) { graph.adb.status.first { it.phase == ConnectionPhase.CONNECTED } }
            assertTrue(graph.coordinator.available.value)
            val session = graph.sessions.createSession()
            graph.sessions.rename(session.id, "QA · device gateway")
            val before = graph.adb.execute("settings get system accelerometer_rotation").output.trim()
            val orientation = graph.adb.execute("settings get system user_rotation").output.trim()
            graph.tools.beginRun("qa-gateway", graph.sessions.workspace(session.id))
            try {
                val started = SystemClock.elapsedRealtime()
                val observation = graph.tools.invoke("read_ui", buildJsonObject { put("force", true) })
                val readMs = SystemClock.elapsedRealtime() - started
                assertTrue(observation.text, observation.success)
                assertEquals(before, graph.adb.execute("settings get system accelerometer_rotation").output.trim())
                assertEquals(orientation, graph.adb.execute("settings get system user_rotation").output.trim())
                graph.overlay.showState(OverlayState(OverlayPhase.CONTROLLING, "QA home"))
                val actionStarted = SystemClock.elapsedRealtime()
                val combined = graph.tools.invoke("act_and_observe", buildJsonObject {
                    put("action", "key"); put("arguments", buildJsonObject { put("keycode", "HOME") })
                })
                val actionMs = SystemClock.elapsedRealtime() - actionStarted
                assertTrue(combined.text, combined.success)
                assertTrue(Json.parseToJsonElement(combined.text).jsonObject["actionCompleted"]!!.jsonPrimitive.boolean)
                assertEquals(before, graph.adb.execute("settings get system accelerometer_rotation").output.trim())
                val screenshot = graph.tools.invoke("screenshot", buildJsonObject {})
                assertTrue(screenshot.success)
                assertTrue(File(screenshot.attachmentPaths.single()).length() > 100)
                evidence("gateway", "PASS read_ui=${readMs}ms act_and_observe=${actionMs}ms rotation=$before user_rotation=$orientation screenshot_saved=true")
            } finally { graph.tools.revoke(); graph.overlay.hide() }
        }
    }

    @Test fun authenticatedChatControlsSettingsAndProducesFinalAnswer() = runBlocking {
        optIn()
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(30_000) { graph.adb.status.first { it.phase == ConnectionPhase.CONNECTED } }
            graph.engine.connect()
            assertTrue("Codex account must be signed in", graph.engine.account().signedIn)
            val session = graph.sessions.createSession()
            graph.sessions.rename(session.id, "QA · live device task")
            val preferences = instrumentation.targetContext.getSharedPreferences("ui", 0)
            val model = preferences.getString("model", null)
            val effort = preferences.getString("reasoningEffort", null)
            withContext(Dispatchers.Main) {
                graph.coordinator.send(session.id, "Open Android Settings and tell me which main settings sections you can see. Do not change any settings. Use the device tools, verify the result, then give a short final answer.", model = model, reasoningEffort = effort)
            }
            try {
                withTimeout(180_000) { graph.coordinator.available.first { ready -> ready } }
                val messages = graph.sessions.messages(session.id).first()
                assertTrue(messages.any { it.role == "tool" })
                val final = messages.last()
                assertEquals("assistant", final.role)
                assertEquals("complete", final.state)
                assertTrue(final.text.isNotBlank())
                assertFalse(final.text.contains("without a final reply"))
                val metrics = graph.coordinator.metrics.value[session.id]!!
                evidence("live-agent", "PASS model=$model effort=$effort first_response_ms=${metrics.firstResponseMs} total_ms=${metrics.totalMs} tool_calls=${metrics.toolCalls} tool_ms=${metrics.toolMs} final_reply=true")
            } finally { withContext(Dispatchers.Main) { graph.queue.pause(); graph.coordinator.stop() } }
        }
    }
}
