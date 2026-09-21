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

/**
 * Network error categories surfaced from Codex JSON-RPC errors and stderr.
 *
 * The category is derived from the error text only. Hostnames are preserved
 * (they carry no credentials); everything secret is removed by [redact].
 */
enum class NetErrorCategory { DNS, TLS, TIMEOUT, AUTH, HTTP, CONNECTION, UNKNOWN }

/**
 * Redacts auth material from diagnostics and classifies network failures.
 *
 * Never logged or returned in clear: bearer tokens, API keys, JWTs, device
 * codes, user codes, cookies, auth.json contents, request bodies and URL
 * query strings. Hostnames (e.g. auth.openai.com) are preserved because they
 * are needed to tell DNS/TLS/allowlist problems apart.
 */
object SecretRedactor {

    // Remove terminal color/control sequences before redaction and UI display.
    private val ansiCsiPattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val colorRemainderPattern = Regex("""\[(?:\d{1,3};)*\d{1,3}m""")

    private val bearerPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/=]{8,}""")
    private val apiKeyPattern = Regex("""\bsk-[A-Za-z0-9\-_]{8,}""")
    private val jwtPattern = Regex("""\beyJ[A-Za-z0-9\-_]{10,}\.[A-Za-z0-9\-_]{10,}[A-Za-z0-9\-_.]*""")
    private val keyValuePattern = Regex(
        """(?i)("?(?:access_token|refresh_token|id_token|api_key|apikey|authorization|cookie|set-cookie|user_code|userCode|device_code|deviceCode|client_secret|session_key|token)"?\s*[:=]\s*"?)([^",\s}]{3,})"""
    )
    private val deviceCodePattern =
        Regex("""(?i)(user-?code|device-?code)[^A-Za-z0-9]{0,24}[A-Za-z0-9]{4,8}[-\s]?[A-Za-z0-9]{4,8}""")
    private val cookieHeaderPattern = Regex("""(?i)\bCookie\s*:[^\r\n]*""")
    private val queryPattern = Regex("""(\?)[^ \t\r\n"']+""")
    private val bodyHintPattern =
        Regex("""(?i)("(?:body|request_body|post_data|payload)"\s*:\s*")[^"]+""")

    /** Removes secrets from [raw] while keeping the useful classification text. */
    fun redact(raw: String): String {
        var out = colorRemainderPattern.replace(ansiCsiPattern.replace(raw, ""), "")
        out = cookieHeaderPattern.replace(out, "Cookie: [REDACTED]")
        out = bearerPattern.replace(out, "Bearer [REDACTED]")
        out = jwtPattern.replace(out, "[REDACTED_JWT]")
        out = apiKeyPattern.replace(out, "[REDACTED_API_KEY]")
        out = keyValuePattern.replace(out, "\$1[REDACTED]")
        out = deviceCodePattern.replace(out, "\$1 [REDACTED]")
        out = bodyHintPattern.replace(out, "\$1[REDACTED]")
        out = queryPattern.replace(out, "?[REDACTED]")
        return out
    }

    /**
     * Removes credentials from text read off the user's screen.
     *
     * Deliberately narrower than [redact]: it applies the credential patterns
     * but not the URL-query or request-body rules, which exist for diagnostics
     * and would mangle ordinary UI content — [redact] rewrites everything after
     * any "?" character, and screens are full of question marks.
     */
    fun redactUiText(raw: String): String {
        var out = colorRemainderPattern.replace(ansiCsiPattern.replace(raw, ""), "")
        out = cookieHeaderPattern.replace(out, "Cookie: [REDACTED]")
        out = bearerPattern.replace(out, "Bearer [REDACTED]")
        out = jwtPattern.replace(out, "[REDACTED_JWT]")
        out = apiKeyPattern.replace(out, "[REDACTED_API_KEY]")
        out = keyValuePattern.replace(out, "$1[REDACTED]")
        out = deviceCodePattern.replace(out, "$1 [REDACTED]")
        return out
    }

    /** Classifies a (possibly unredacted) error message. */
    fun classify(raw: String): NetErrorCategory {
        val text = raw.lowercase()
        if (containsAny(
                text,
                "failed to resolve", "name resolution", "unknown host", "nodename nor servname",
                "getaddrinfo", "eai_", "no address associated", "dns"
            )
        ) return NetErrorCategory.DNS
        if (containsAny(
                text,
                "tls", "ssl", "certificate", "pkix", "x509", "handshake",
                "unable to get local issuer", "ca_cert", "cert verify", "certificate verify"
            )
        ) return NetErrorCategory.TLS
        if (containsAny(text, "timed out", "timeout", "deadline exceeded", "elapsed")) {
            return NetErrorCategory.TIMEOUT
        }
        if (containsAny(
                text,
                "unauthorized", "invalid_grant", "invalid credentials", "not signed in",
                " 401", "http 401", "status 401"
            )
        ) return NetErrorCategory.AUTH
        if (containsAny(
                text,
                "http 4", "http 5", "status 4", "status 5", "status code",
                " 403", " 429", " 500", " 502", " 503", " 504"
            )
        ) return NetErrorCategory.HTTP
        if (containsAny(
                text,
                "error sending request", "failed to request", "connection refused",
                "connection reset", "connection closed", "network is unreachable",
                "network unreachable", "broken pipe", "econn", "transport error",
                "failed to connect", "no route to host"
            )
        ) return NetErrorCategory.CONNECTION
        return NetErrorCategory.UNKNOWN
    }

    /**
     * Classified, redacted one-line summary, e.g.
     * `[CONNECTION] failed to request device code: error sending request ... (code=-32603)`.
     * Safe to show in UI and logcat.
     */
    fun describe(raw: String, code: Long? = null): String {
        val clean = redact(raw).replace(Regex("\\s+"), " ").trim().take(500)
        val suffix = if (code != null) " (code=$code)" else ""
        return "[${classify(raw)}] $clean$suffix"
    }

    fun redactStderrLine(line: String, maxChars: Int = 500): String {
        val clean = redact(line.trim())
        return if (clean.length > maxChars) clean.take(maxChars) + "..." else clean
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { it in text }
}
