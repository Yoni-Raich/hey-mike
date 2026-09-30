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

package dev.androidagent.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeEnvironmentTest {
    private val home = File("/data/user/0/dev.androidagent.app.dev/files/runtime/claude-home")
    private val tmp = File("/data/user/0/dev.androidagent.app.dev/files/runtime/claude-tmp")
    private val proxy = "http://127.0.0.1:40123"
    private val ca = "/data/user/0/dev.androidagent.app.dev/files/runtime/cacert.pem"

    private val tokenKeys = listOf(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_BASE_URL",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_USE_FOUNDRY",
    )

    private fun build(
        inherited: Map<String, String> = mapOf("PATH" to "/system/bin", "ANDROID_DATA" to "/data"),
        extra: Map<String, String> = emptyMap(),
        caFile: String? = ca,
    ) = ClaudeEnvironment.build(inherited, home, tmp, proxy, caFile, extra)

    @Test
    fun `sets the private home proxy and quiet flags`() {
        val env = build()
        assertEquals(home.absolutePath, env["HOME"])
        assertEquals(File(home, ".claude").absolutePath, env["CLAUDE_CONFIG_DIR"])
        assertEquals(tmp.absolutePath, env["TMPDIR"])
        listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy").forEach { assertEquals(it, proxy, env[it]) }
        assertEquals("127.0.0.1,localhost", env["NO_PROXY"])
        assertEquals("127.0.0.1,localhost", env["no_proxy"])
        assertEquals(ca, env["SSL_CERT_FILE"])
        mapOf(
            "DISABLE_AUTOUPDATER" to "1",
            "DISABLE_UPDATES" to "1",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
            "CLAUDE_CODE_MAX_RETRIES" to "2",
            "NO_COLOR" to "1",
            "BROWSER" to "true",
        ).forEach { (key, value) -> assertEquals(key, value, env[key]) }
        assertEquals("/system/bin", env["PATH"])
    }

    @Test
    fun `no ca file leaves SSL_CERT_FILE unset`() {
        assertNull(build(caFile = null)["SSL_CERT_FILE"])
    }

    @Test
    fun `token and provider variables are scrubbed from the inherited env`() {
        val inherited = tokenKeys.associateWith { "secret" } + ("PATH" to "/system/bin")
        val env = build(inherited = inherited)
        tokenKeys.forEach { assertFalse(it, env.containsKey(it)) }
        assertTrue(env.values.none { it == "secret" })
    }

    @Test
    fun `extra env cannot add token variables`() {
        val env = build(extra = tokenKeys.associateWith { "secret" } + ("ANTHROPIC_CUSTOM_HEADERS" to "x-spoof: 1"))
        tokenKeys.forEach { assertFalse(it, env.containsKey(it)) }
        assertFalse(env.containsKey("ANTHROPIC_CUSTOM_HEADERS"))
        assertTrue(env.values.none { it == "secret" })
    }

    @Test
    fun `extra env cannot replace host owned values`() {
        val env = build(
            extra = mapOf(
                "HOME" to "/sdcard",
                "CLAUDE_CONFIG_DIR" to "/sdcard/.claude",
                "HTTPS_PROXY" to "http://evil:1",
                "DISABLE_TELEMETRY" to "0",
                "CLAUDE_CODE_ENTRYPOINT" to "cli",
                "MCP_TOOL_TIMEOUT" to "600000",
            )
        )
        assertEquals(home.absolutePath, env["HOME"])
        assertEquals(File(home, ".claude").absolutePath, env["CLAUDE_CONFIG_DIR"])
        assertEquals(proxy, env["HTTPS_PROXY"])
        assertEquals("1", env["DISABLE_TELEMETRY"])
        assertFalse(env.containsKey("CLAUDE_CODE_ENTRYPOINT"))
        assertEquals("600000", env["MCP_TOOL_TIMEOUT"])
    }

    @Test
    fun `scrub list matches the compliance rule`() {
        tokenKeys.forEach { assertTrue(it, ClaudeEnvironment.isScrubbed(it)) }
        assertTrue(ClaudeEnvironment.isScrubbed("anthropic_api_key"))
        listOf("PATH", "HOME", "MCP_TIMEOUT", "ENABLE_TOOL_SEARCH", "CLAUDE_CONFIG_DIR").forEach {
            assertFalse(it, ClaudeEnvironment.isScrubbed(it))
        }
    }
}
