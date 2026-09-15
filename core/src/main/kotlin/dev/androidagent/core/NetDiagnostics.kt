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

import java.io.File
import java.security.MessageDigest

/**
 * Network-compatibility diagnostics for the on-phone Codex app-server.
 *
 * Context: the musl build of the Codex app-server cannot resolve DNS on
 * Android (no /etc/resolv.conf under the app UID), so the runtime tunnels
 * TLS through a localhost HTTP CONNECT proxy that uses Android/Bionic
 * networking, and pins a CA PEM bundle for TLS verification.
 *
 * Everything here is pure JVM/Kotlin with no Android dependency so it is
 * unit-testable. No function in this file ever logs or returns auth tokens,
 * cookies, device codes, request bodies, or auth.json contents: [redact]
 * must be applied before any diagnostic text leaves the process.
 */
object NetDiagnostics {

    // ---- proxy allowlist ----

    /**
     * Minimal CONNECT allowlist. auth.openai.com is the verified device-auth
     * endpoint. api.openai.com serves API-key sessions; chatgpt.com serves
     * ChatGPT sessions, including models and responses. Verified against
     * rust-v0.153.4/codex-rs/model-provider-info/src/lib.rs
     * (CHATGPT_CODEX_BASE_URL and to_api_provider). Further hosts require
     * observed CONNECT metadata or a verified upstream route.
     */
    val defaultAllowedHosts: Set<String> = setOf(
        "auth.openai.com",
        "api.openai.com",
        "chatgpt.com"
    )

    /** Only TLS is tunnelled. Plain HTTP through the proxy is never allowed. */
    val defaultAllowedPorts: Set<Int> = setOf(443)

    /** Local bypasses that must never be proxied. */
    const val NO_PROXY_VALUE = "localhost,127.0.0.1"

    data class ConnectTarget(val host: String, val port: Int)

    sealed interface ConnectCheck {
        data class Allow(val target: ConnectTarget) : ConnectCheck
        data class Deny(val reason: String) : ConnectCheck
    }

    /**
     * Parse one HTTP request head and decide allow/deny.
     * Accepts the raw head bytes as text (headers only, never a body).
     * Never throws; malformed input is a [ConnectCheck.Deny].
     */
    fun checkConnectRequest(
        head: String,
        allowedHosts: Set<String> = defaultAllowedHosts,
        allowedPorts: Set<Int> = defaultAllowedPorts
    ): ConnectCheck {
        val requestLine = head.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) return ConnectCheck.Deny("malformed-request-line")
        if (!parts[0].equals("CONNECT", ignoreCase = false)) return ConnectCheck.Deny("method-not-allowed")
        val target = parseAuthority(parts[1]) ?: return ConnectCheck.Deny("malformed-authority")
        if (target.port !in allowedPorts) return ConnectCheck.Deny("port-not-allowed")
        if (!isHostAllowed(target.host, allowedHosts)) return ConnectCheck.Deny("host-not-allowed")
        return ConnectCheck.Allow(target)
    }

    /**
     * Parse a CONNECT authority ("host:port"). Rejects userinfo, empty host,
     * missing/invalid port, and anything that is not exactly host:port.
     * Returns the lower-cased host (trailing dot stripped) and port.
     */
    fun parseAuthority(authority: String): ConnectTarget? {
        if (authority.isEmpty() || "@" in authority || "/" in authority) return null
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            // Literal IPv6: [::1]:443. Never allowlisted, but parse cleanly.
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (!rest.startsWith(":")) return null
            portText = rest.drop(1)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon <= 0) return null
            host = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
        }
        if (host.isEmpty() || portText.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val normal = host.lowercase().trimEnd('.')
        if (normal.isEmpty()) return null
        return ConnectTarget(normal, port)
    }

    fun isHostAllowed(host: String, allowedHosts: Set<String> = defaultAllowedHosts): Boolean {
        val normal = host.lowercase().trimEnd('.')
        return allowedHosts.any { it.lowercase() == normal }
    }

    // ---- app-server environment ----

    const val KEY_SANDBOX = "CODEX_SANDBOX"
    const val UNSAFE_SANDBOX_SEATBELT = "seatbelt"

    /**
     * Build the exact environment for the app-server process.
     * - Starts from [base] (HOME, CODEX_HOME, TMPDIR, PATH).
     * - Injects HTTPS/HTTP proxy (upper and lowercase, reqwest honors both).
     * - Sets NO_PROXY/no_proxy so localhost traffic stays direct.
     * - Sets SSL_CERT_FILE and CODEX_CA_CERTIFICATE to the absolute
     *   app-private CA bundle path when one is staged.
     * - Removes CODEX_SANDBOX entirely (seatbelt is a macOS sandbox and must
     *   never be set for the Android app-server).
     * TLS verification is never disabled: no *_VERIFY=0 / INSECURE flag here.
     */
    fun buildAppServerEnvironment(
        base: Map<String, String>,
        proxyUrl: String,
        caFileAbsolutePath: String?
    ): Map<String, String> {
        val env = LinkedHashMap(base)
        env.remove(KEY_SANDBOX)
        env["HTTPS_PROXY"] = proxyUrl
        env["HTTP_PROXY"] = proxyUrl
        env["https_proxy"] = proxyUrl
        env["http_proxy"] = proxyUrl
        env["NO_PROXY"] = NO_PROXY_VALUE
        env["no_proxy"] = NO_PROXY_VALUE
        env["NO_COLOR"] = "1"
        if (!caFileAbsolutePath.isNullOrBlank()) {
            env["SSL_CERT_FILE"] = caFileAbsolutePath
            env["CODEX_CA_CERTIFICATE"] = caFileAbsolutePath
        }
        return env
    }

    /**
     * CODEX_SANDBOX must be absent for the Android app-server. In particular
     * CODEX_SANDBOX=seatbelt (a macOS sandbox) must never be set; any other
     * present value is also treated as unsafe because no sandbox mode has
     * been validated on Android.
     */
    fun isSandboxEnvSafe(env: Map<String, String>): Boolean =
        !env.containsKey(KEY_SANDBOX)

    // ---- CA bundle ----

    data class CaValidation(val certificates: Int, val bytes: Long)

    /**
     * Validate PEM bundle bytes (header markers + at least one certificate).
     * Returns null when invalid. Never logs the bundle contents.
     */
    fun validateCaPem(bytes: ByteArray): CaValidation? {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        val text = runCatching { bytes.toString(Charsets.US_ASCII) }.getOrNull() ?: return null
        if (!text.contains("-----BEGIN CERTIFICATE-----")) return null
        val count = text.split("-----BEGIN CERTIFICATE-----").size - 1
        if (count < 1) return null
        if (!text.contains("-----END CERTIFICATE-----")) return null
        return CaValidation(certificates = count, bytes = bytes.size.toLong())
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = sha256Hex(file.readBytes())

    // ---- error classification + redaction ----

    enum class ErrorCategory { DNS, TLS, TIMEOUT, HTTP, CONNECTION, AUTH, UNKNOWN }

    data class ClassifiedError(val category: ErrorCategory, val redactedMessage: String)

    /**
     * Classify a raw Codex/network error message without keeping secrets.
     * The returned message is already redacted and safe to surface/log.
     */
    fun describe(raw: String): ClassifiedError {
        val message = redact(raw)
        return ClassifiedError(classify(message), message)
    }

    fun classify(redactedLowerHint: String): ErrorCategory {
        val m = redactedLowerHint.lowercase()
        if (m.contains("failed to resolve") || m.contains("could not resolve") ||
            m.contains("name resolution") || m.contains("getaddrinfo") ||
            m.contains("no address associated") || m.contains("eai_") ||
            m.contains("name or service not known") || m.contains("nodename nor servname")
        ) return ErrorCategory.DNS
        if (m.contains("certificate") || m.contains("x509") || m.contains("pkix") ||
            m.contains("tls") || m.contains(" ssl") || m.contains("ssl ") ||
            m.contains("handshake") || m.contains("cert ")
        ) return ErrorCategory.TLS
        if (m.contains("timed out") || m.contains("timeout") ||
            m.contains("deadline exceeded") || m.contains("504")
        ) return ErrorCategory.TIMEOUT
        if (m.contains("unauthorized") || m.contains(" 401") || m.contains("(401") ||
            m.contains("invalid_token") || m.contains("invalid grant")
        ) return ErrorCategory.AUTH
        if (m.contains("error sending request") || m.contains("failed to request device code") ||
            m.contains("connection refused") || m.contains("connection reset") ||
            m.contains("network is unreachable") || m.contains("broken pipe") ||
            m.contains("econnrefused") || m.contains("enetunreach") ||
            m.contains("connection closed") || m.contains("connection failed")
        ) return ErrorCategory.CONNECTION
        val httpStatus = Regex("""\bhttp[/ ](\d{3})\b""").find(m)
            ?: Regex("""\bstatus[ :]+(\d{3})\b""").find(m)
        if (httpStatus != null || m.contains("status code")) return ErrorCategory.HTTP
        return ErrorCategory.UNKNOWN
    }

    private val redactPatterns: List<Pair<Regex, String>> by lazy {
        listOf(
            // JWT-shaped tokens.
            Regex("""\beyJ[A-Za-z0-9\-_]{8,}\.[A-Za-z0-9\-_]{8,}\.[A-Za-z0-9\-_]{8,}""") to "[REDACTED-JWT]",
            // OpenAI-style secret keys.
            Regex("""\bsk-[A-Za-z0-9\-_]{8,}""") to "[REDACTED-KEY]",
            // Bearer credentials.
            Regex("""(?i)(bearer\s+)[^\s;,\"']{4,}""") to "$1[REDACTED]",
            // Named secret fields (JSON or log style): keep the key, drop value.
            Regex("""(?i)("(?:access_token|refresh_token|id_token|api_key|apikey|authorization|cookie|set-cookie|user_code|device_code|client_secret|secret|token|password)"\s*:\s*")[^"]*("?)""") to "$1[REDACTED]$2",
            Regex("""(?i)\b((?:access_token|refresh_token|id_token|api_key|authorization|cookie|set-cookie|user_code|device_code|client_secret)\s*[:=]\s*)[^\s;,\"'}]+""") to "$1[REDACTED]",
            // CamelCase variants used by the login flow.
            Regex("""\b((?:userCode|deviceCode|verificationCode)\s*["'\s:=]+)[A-Za-z0-9\-]{3,}""") to "$1[REDACTED]",
            // ChatGPT device codes look like ABCD-1234: redact the pattern.
            Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4}\b""") to "[REDACTED-CODE]",
            // Cookie header remnants.
            Regex("""(?i)\bcookie\b\s*[:=]\s*[^\s;]{4,}""") to "cookie=[REDACTED]"
        )
    }

    /** Strip auth tokens, cookies, device codes, bodies, auth.json fragments. */
    fun redact(raw: String): String {
        var out = raw
        for ((pattern, replacement) in redactPatterns) {
            out = pattern.replace(out, replacement)
        }
        return out
    }

    /** Redact one stderr line and bound its length for the rolling buffer. */
    fun redactStderrLine(line: String, maxChars: Int = 500): String {
        val redacted = redact(line.trim())
        return if (redacted.length > maxChars) redacted.take(maxChars) + "..." else redacted
    }
}
