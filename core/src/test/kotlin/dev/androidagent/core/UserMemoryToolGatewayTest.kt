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

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserMemoryToolGatewayTest {
    @get:Rule val temp = TemporaryFolder()

    private fun gateway(): Pair<UserMemoryStore, UserMemoryToolGateway> {
        val store = UserMemoryStore(temp.newFolder("memory"))
        val gw = UserMemoryToolGateway(store)
        gw.beginRun("run-1", temp.root)
        return store to gw
    }

    @Test fun schemaPassesAuditAndToolNeedsNoScreenOrLease() {
        val (_, gw) = gateway()
        assertEquals(emptyList<String>(), ToolSchemaAudit.complaints(gw.definitions))
        assertFalse(gw.needsControl(UserMemoryToolGateway.TOOL_NAME))
        assertFalse(gw.requiresDeviceLease(UserMemoryToolGateway.TOOL_NAME))
        assertFalse(gw.deviceBackendLive())
        assertEquals("User memory: empty", gw.statusLine())
    }

    @Test fun rememberReadForgetAndReplaceWorkEndToEnd() = runBlocking {
        val (store, gw) = gateway()

        val remembered = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject {
                put("operation", "remember")
                put("fact", "Prefers short Hebrew answers")
                put("section", "Preferences")
            },
        )
        assertTrue(remembered.success)
        val remJson = Json.parseToJsonElement(remembered.text).jsonObject
        assertTrue(remJson["saved"]!!.jsonPrimitive.boolean)
        assertTrue(gw.statusLine().startsWith("User memory: "))

        val read = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject { put("operation", "read") },
        )
        assertTrue(read.success)
        val readJson = Json.parseToJsonElement(read.text).jsonObject
        assertTrue(readJson["content"]!!.jsonPrimitive.content.contains("Prefers short Hebrew answers"))

        val forgot = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject {
                put("operation", "forget")
                put("query", "Hebrew")
            },
        )
        assertTrue(forgot.success)
        assertEquals(1, Json.parseToJsonElement(forgot.text).jsonObject["removedLines"]!!.jsonPrimitive.int)
        assertFalse(store.read().contains("Hebrew"))

        val replaced = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject {
                put("operation", "replace")
                put("content", "## About\n- Works on Hey Mike")
            },
        )
        assertTrue(replaced.success)
        assertEquals("## About\n- Works on Hey Mike", store.read())
    }

    @Test fun disabledMemoryRefusesWritesButAllowsReadAndForget() = runBlocking {
        val (store, gw) = gateway()
        store.write("## About\n- Drinks espresso")
        store.setEnabled(false)
        assertEquals("User memory: off", gw.statusLine())

        val rem = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject { put("operation", "remember"); put("fact", "Likes tea") },
        )
        assertFalse(rem.success)
        assertTrue(rem.text.contains("memory_disabled"))

        val rep = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject { put("operation", "replace"); put("content", "new") },
        )
        assertFalse(rep.success)
        assertTrue(rep.text.contains("memory_disabled"))

        val read = gw.invoke(UserMemoryToolGateway.TOOL_NAME, buildJsonObject { put("operation", "read") })
        assertTrue(read.success)
        assertFalse(Json.parseToJsonElement(read.text).jsonObject["enabled"]!!.jsonPrimitive.boolean)

        val forgot = gw.invoke(
            UserMemoryToolGateway.TOOL_NAME,
            buildJsonObject { put("operation", "forget"); put("query", "espresso") },
        )
        assertTrue(forgot.success)
    }

    @Test fun revokedGatewayThrowsImmediately() = runBlocking {
        val (_, gw) = gateway()
        gw.revoke()
        val err = runCatching {
            gw.invoke(UserMemoryToolGateway.TOOL_NAME, buildJsonObject { put("operation", "read") })
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
    }
}
