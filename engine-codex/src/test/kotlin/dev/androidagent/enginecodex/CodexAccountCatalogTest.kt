/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.enginecodex

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAccountCatalogTest {
    @Test fun switchedOffToolsNeverStartTheRuntime() = kotlinx.coroutines.runBlocking {
        val runtime = object : dev.androidagent.core.RuntimeHost {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(dev.androidagent.core.RuntimeStatus())
            override val homeDirectory = java.io.File(".")
            override suspend fun prepare() { error("Must not connect") }
            override suspend fun startAppServer(): Process = error("Must not start")
            override suspend fun stop() {}
        }
        val engine = CodexEngine(runtime, accountReadBridge = true)
        assertFalse(engine.readAccountTool("gmail.send_email", buildJsonObject {}) { true }.success)
        assertFalse(engine.readAccountTool("gmail.read_email", buildJsonObject {}) { true }.success)
        engine.allowAccountTools(listOf("gmail.read_email"))
        assertFalse(engine.readAccountTool("gmail.read_email", buildJsonObject {}) { false }.success)
    }
    @Test fun onlyExplicitSafeReadsFromTheAccountServerQualify() {
        val server = Json.parseToJsonElement("""{"name":"codex_apps","tools":{
          "gmail.read":{"_meta":{"connector_name":"Gmail"},"annotations":{"readOnlyHint":true}},
          "gmail.send":{"_meta":{"connector_name":"Gmail"},"annotations":{"readOnlyHint":false}},
          "gmail.unknown":{"_meta":{"connector_name":"Gmail"}},
          "gmail.conflict":{"_meta":{"connector_name":"Gmail"},"annotations":{"readOnlyHint":true,"destructiveHint":true}},
          "gmail.external":{"_meta":{"connector_name":"Gmail"},"annotations":{"readOnlyHint":true,"openWorldHint":true}}
        }}""").jsonObject
        val c = CodexAccountCatalog.connectors(listOf(server)).single()
        assertEquals(listOf("gmail.read"), c.readTools)
        assertEquals(4, c.writeTools)
        assertEquals("Gmail", c.name)
        assertTrue(CodexAccountCatalog.connectors(listOf(JsonObject(server + ("name" to JsonPrimitive("other"))))).isEmpty())
    }
    @Test fun bridgeDisablesNativeAppsForUserChats() {
        val config = CodexEngine.sessionConfig(EngineProfile("test", disableAccountApps = true))
        assertEquals(JsonPrimitive(false), config["features.apps"])
    }
}
