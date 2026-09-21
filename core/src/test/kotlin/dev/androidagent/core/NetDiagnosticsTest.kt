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

import org.junit.Assert.*
import org.junit.Test

class NetDiagnosticsTest {
    @Test
    fun `ChatGPT model and response host is allowed without allowing lookalikes`() {
        assertTrue(NetDiagnostics.checkConnectRequest("CONNECT chatgpt.com:443 HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Allow)
        listOf("chatgpt.com.evil.example", "evilchatgpt.com", "chatgpt.com@evil.example").forEach { host ->
            assertTrue(NetDiagnostics.checkConnectRequest("CONNECT $host:443 HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Deny)
        }
        assertTrue(NetDiagnostics.checkConnectRequest("CONNECT chatgpt.com:80 HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Deny)
    }

    @Test
    fun `stderr colors are removed while secrets remain redacted`() {
        val safe = SecretRedactor.redactStderrLine("\u001B[31mERROR\u001B[0m [2mrequest failed[0m Bearer abcdefghijkl")
        assertEquals("ERROR request failed Bearer [REDACTED]", safe)
    }

    @Test
    fun `only allowlisted tls connect is accepted`() {
        val allowed = NetDiagnostics.checkConnectRequest(
            "CONNECT AUTH.OPENAI.COM:443 HTTP/1.1\r\nHost: AUTH.OPENAI.COM\r\n\r\n"
        )
        assertEquals(NetDiagnostics.ConnectTarget("auth.openai.com", 443), (allowed as NetDiagnostics.ConnectCheck.Allow).target)
        assertTrue(NetDiagnostics.checkConnectRequest("GET https://auth.openai.com/ HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Deny)
        assertTrue(NetDiagnostics.checkConnectRequest("CONNECT api.openai.com:80 HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Deny)
        assertTrue(NetDiagnostics.checkConnectRequest("CONNECT evil.example:443 HTTP/1.1\r\n\r\n") is NetDiagnostics.ConnectCheck.Deny)
    }

    @Test
    fun `environment injects proxy and removes sandbox`() {
        val result = NetDiagnostics.buildAppServerEnvironment(
            mapOf("HOME" to "/x", "CODEX_SANDBOX" to "seatbelt"),
            "http://127.0.0.1:1234",
            "/x/cacert.pem"
        )
        assertEquals("http://127.0.0.1:1234", result["HTTPS_PROXY"])
        assertEquals("http://127.0.0.1:1234", result["https_proxy"])
        assertEquals("/x/cacert.pem", result["SSL_CERT_FILE"])
        assertEquals("/x/cacert.pem", result["CODEX_CA_CERTIFICATE"])
        assertFalse(result.containsKey("CODEX_SANDBOX"))
    }

    @Test
    fun `redaction removes credentials and device codes`() {
        val safe = SecretRedactor.redact(
            "https://auth.openai.com/?token=abc Bearer abcdefghijkl sk-secret1234 user_code=ABCD-1234 Cookie: sid=private"
        )
        assertFalse(safe.contains("abcdefghijkl"))
        assertFalse(safe.contains("sk-secret1234"))
        assertFalse(safe.contains("ABCD-1234"))
        assertFalse(safe.contains("sid=private"))
        assertTrue(safe.contains("auth.openai.com"))
    }
}
