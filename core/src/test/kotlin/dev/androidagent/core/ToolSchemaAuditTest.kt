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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every tool this module advertises describes what it takes.
 *
 * `act_plan` shipped with `steps` as a bare `{"type":"array"}` and the agent
 * sent quoted JSON, because that is what such a schema renders as. The fix is
 * only worth as much as this test: without it the next tool reintroduces it,
 * and nobody finds out until a phone run.
 */
class ToolSchemaAuditTest {

    @get:Rule val temp = TemporaryFolder()

    private val router = object : DeviceToolGateway {
        override val definitions: List<ToolDefinition> = emptyList()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = false
        override suspend fun cancel() = Unit
        override suspend fun invoke(name: String, arguments: JsonObject) = ToolResult("$name ok")
    }

    private fun library(): WorkflowLibrary {
        val dir = File(temp.root, "definitions").apply { mkdirs() }
        File(dir, "wifi.json").writeText(
            """{"id":"wifi","package":"com.android.settings","steps":[{"id":"open","action":"open_app"}]}""",
        )
        return WorkflowLibrary(dir)
    }

    /** Everything a turn can be offered from this module. */
    private fun everyDefinition(): List<ToolDefinition> =
        WorkflowToolGateway(WorkflowStore(temp.newFolder()), { router }, library()).definitions +
            KnowledgeToolGateway(KnowledgeStore(temp.newFolder())).definitions +
            AutomationToolGateway(
                library = AutomationLibrary(File(temp.root, "automations")),
                history = InMemoryAutomationHistory(),
            ).definitions +
            listOf(ACT_AND_OBSERVE_DEFINITION)

    @Test fun everyAdvertisedToolSaysWhatItTakes() {
        val complaints = ToolSchemaAudit.complaints(everyDefinition())
        assertEquals(complaints.joinToString("\n"), emptyList<String>(), complaints)
    }

    @Test fun theAuditCatchesTheDefectThatShippedOnce() {
        // The exact shape act_plan had: an array a client renders as strings.
        val bare = ToolDefinition(
            "bad_tool",
            "takes a list of something",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("steps", buildJsonObject { put("type", "array") }) })
                put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("steps")) })
            },
        )
        val complaints = ToolSchemaAudit.complaints(bare)
        assertEquals(1, complaints.size)
        assertTrue(complaints.single(), complaints.single().contains("bad_tool.steps"))
        assertTrue(complaints.single(), complaints.single().contains("array of strings"))
    }

    @Test fun itAlsoCatchesTheQuieterVersionsOfTheSameMistake() {
        fun complain(property: JsonObject): List<String> = ToolSchemaAudit.complaints(
            ToolDefinition(
                "t",
                "d",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("p", property) })
                },
            ),
        )
        // An object that names no keys and does not say its keys are open.
        assertEquals(1, complain(buildJsonObject { put("type", "object") }).size)
        // Nothing at all: no type, no enum, no shape.
        assertEquals(1, complain(buildJsonObject { put("description", "something") }).size)
        // An array of objects that are themselves undescribed.
        assertEquals(
            1,
            complain(
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "object") })
                },
            ).size,
        )
        // A required name that is not a property at all.
        val missing = ToolSchemaAudit.complaints(
            ToolDefinition(
                "t",
                "d",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("a", buildJsonObject { put("type", "string") }) })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("b")) })
                },
            ),
        )
        assertEquals(1, missing.size)
        assertTrue(missing.single(), missing.single().contains("\"b\" is required"))
    }

    @Test fun anHonestSchemaIsNotComplainedAbout() {
        // Free-form on purpose (an intent's extras) is a description, not a gap.
        val open = ToolDefinition(
            "t",
            "d",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("extras", buildJsonObject { put("type", "object"); put("additionalProperties", true) })
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
            },
        )
        assertEquals(emptyList<String>(), ToolSchemaAudit.complaints(open))
    }
}
