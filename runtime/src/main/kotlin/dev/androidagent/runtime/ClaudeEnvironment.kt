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

/**
 * The environment of every `claude` process the app starts.
 *
 * Order: the inherited env, then the engine's extra env, then the values the
 * host owns (they always win), then the scrub. So neither the app process nor
 * an engine can pass an API key, a token, a base URL, a cloud provider switch,
 * custom headers or an entrypoint to `claude`: sign-in is only ever the user's
 * own `claude auth login`.
 */
object ClaudeEnvironment {
    /** Exact keys removed from the child env (compliance rule 5, plus header and entrypoint spoofing). */
    val SCRUBBED_KEYS: Set<String> = setOf(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_CUSTOM_HEADERS",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_ENTRYPOINT",
    )

    /** Every `ANTHROPIC_*` and `CLAUDE_CODE_USE_*` (Bedrock, Vertex, ...) key is removed too. */
    val SCRUBBED_PREFIXES: List<String> = listOf("ANTHROPIC_", "CLAUDE_CODE_USE_")

    const val NO_PROXY_VALUE = "127.0.0.1,localhost"

    fun isScrubbed(key: String): Boolean {
        val upper = key.uppercase()
        return upper in SCRUBBED_KEYS || SCRUBBED_PREFIXES.any { upper.startsWith(it) }
    }

    fun configDirectory(homeDirectory: File): File = File(homeDirectory, ".claude")

    fun build(
        inherited: Map<String, String>,
        homeDirectory: File,
        tmpDirectory: File,
        proxyUrl: String,
        caFileAbsolutePath: String?,
        extraEnv: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val env = LinkedHashMap(inherited)
        env.putAll(extraEnv)
        env["HOME"] = homeDirectory.absolutePath
        env["CLAUDE_CONFIG_DIR"] = configDirectory(homeDirectory).absolutePath
        env["TMPDIR"] = tmpDirectory.absolutePath
        env["HTTPS_PROXY"] = proxyUrl
        env["HTTP_PROXY"] = proxyUrl
        env["https_proxy"] = proxyUrl
        env["http_proxy"] = proxyUrl
        env["NO_PROXY"] = NO_PROXY_VALUE
        env["no_proxy"] = NO_PROXY_VALUE
        if (caFileAbsolutePath.isNullOrBlank()) env.remove("SSL_CERT_FILE") else env["SSL_CERT_FILE"] = caFileAbsolutePath
        env["DISABLE_AUTOUPDATER"] = "1"
        env["DISABLE_UPDATES"] = "1"
        env["DISABLE_TELEMETRY"] = "1"
        env["DISABLE_ERROR_REPORTING"] = "1"
        env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        // The default is 10 retries with backoff: a failed request would stall for a minute.
        env["CLAUDE_CODE_MAX_RETRIES"] = "2"
        // Without this, a process that does not pass --strict-mcp-config retries the
        // account's claude.ai connectors at mcp-proxy.anthropic.com, which the proxy denies.
        env["ENABLE_CLAUDEAI_MCP_SERVERS"] = "false"
        env["NO_COLOR"] = "1"
        // `claude auth login` must not try to open a browser; the app shows the URL.
        env["BROWSER"] = "true"
        env.keys.removeAll { isScrubbed(it) }
        return env
    }
}
