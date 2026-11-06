/* Copyright (C) 2025-2026 Yoni Raich. SPDX-License-Identifier: AGPL-3.0-only */
package dev.androidagent.enginecodex

import dev.androidagent.core.ToolDefinition
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexToolCatalogTest {
    @get:Rule val temp = TemporaryFolder()
    private fun tool(name: String) = ToolDefinition(name, "test", buildJsonObject { put("type", "object") })

    @Test fun legacyAndChangedCatalogsRequireFreshThreadButKnownThreadResumes() {
        val catalog = CodexToolCatalog(temp.root)
        val old = listOf(tool("tasks"))
        val current = old + tool("account_read")
        assertFalse(catalog.matches("old", current))
        catalog.record("old", old)
        assertTrue(catalog.matches("old", old))
        assertFalse(catalog.matches("old", current))
        catalog.record("new", current)
        assertTrue(CodexToolCatalog(temp.root).matches("new", current.reversed()))
        assertFalse(catalog.matches("old", current))
        val changedSchema = current.map { it.copy(inputSchema = buildJsonObject { put("type", "object"); put("additionalProperties", true) }) }
        assertFalse(catalog.matches("new", changedSchema))
    }
}
