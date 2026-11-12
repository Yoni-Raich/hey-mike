/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.core.connections

import dev.androidagent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AccountReadGatewayTest {
    @Test fun revokedAndMalformedCallsNeverReachTheAccount() = runBlocking {
        var calls = 0
        val source = object : AccountConnectorSource {
            override val engine = EngineKind.CODEX
            override suspend fun accountConnectors() = emptyList<AccountConnector>()
            override fun allowAccountTools(tools: List<String>) {}
            override suspend fun readAccountTool(tool: String, arguments: JsonObject, permitted: () -> Boolean): ToolResult {
                check(permitted()); calls++; return ToolResult("ok")
            }
        }
        val gateway = AccountReadGateway(source)
        assertTrue(ToolSchemaAudit.complaints(gateway.definitions).isEmpty())
        val read = Json.parseToJsonElement("""{"operation":"read","tool":"gmail.read"}""").jsonObject
        assertFalse(gateway.invoke("account_read", read).success)
        gateway.beginRun("run", File("."))
        assertFalse(gateway.invoke("account_read", buildJsonObject { put("operation", "read") }).success)
        assertTrue(gateway.invoke("account_read", read).success)
        gateway.revokeIndependentTools()
        assertFalse(gateway.invoke("account_read", read).success)
        assertEquals(1, calls)
    }
}
