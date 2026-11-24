package dev.androidagent.enginecodex

import dev.androidagent.core.ToolDefinition
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CodexToolCatalogTest {
    @Test fun onlyTheOriginalThreadAndItsCompleteCatalogMatch() {
        val workspace = Files.createTempDirectory("mike-tool-catalog").toFile()
        try {
            val catalog = CodexToolCatalog(workspace)
            val first = ToolDefinition("device_status", "Status", buildJsonObject { put("type", "object") })
            val second = ToolDefinition("account_read", "Account reads", buildJsonObject { put("type", "object") })
            assertFalse(catalog.matches("old", listOf(first)))
            catalog.record("thread", listOf(first, second))
            assertTrue(catalog.matches("thread", listOf(second, first)))
            assertFalse(catalog.matches("old", listOf(first, second)))
            assertFalse(catalog.matches("thread", listOf(first)))
            assertFalse(catalog.matches("thread", listOf(first, second.copy(description = "New contract"))))
        } finally { workspace.deleteRecursively() }
    }
}
