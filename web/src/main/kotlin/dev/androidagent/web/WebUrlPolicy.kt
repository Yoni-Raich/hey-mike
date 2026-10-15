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

package dev.androidagent.web

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Where the agent's browser may go.
 *
 * A page is untrusted text that can ask the agent to open another page, so the
 * destination is judged here and not by the model. Two things are refused:
 *
 * - **Anything that is not the web.** `file:`, `content:`, `javascript:`,
 *   `intent:` and app schemes read local data, run code or leave the browser.
 *   An app link is `open_intent`'s to judge, with its own approvals.
 * - **This phone and the network it sits on.** The phone runs a proxy and may
 *   run ADB on loopback, and a home network is full of routers and consoles
 *   that trust whoever can reach them. A page that sent the agent there would
 *   be reading through the user's own front door.
 *
 * [resolve] turns a name into addresses so a public-looking name that points
 * at loopback is caught too. It is a check at navigation, not a firewall: a
 * name that changes its answer afterwards is not seen.
 */
open class WebUrlPolicy(
    private val allowPrivateNetwork: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { host ->
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
    },
) {
    sealed interface Decision {
        data class Allowed(val url: String) : Decision
        data class Blocked(val errorType: String, val message: String, val remedy: String) : Decision
    }

    /**
     * Judge [raw]. [lookUp] false skips the name lookup, for a caller on a
     * thread that must not touch the network.
     */
    open fun check(raw: String, lookUp: Boolean = true): Decision {
        val text = raw.trim()
        if (text.equals(BLANK, ignoreCase = true)) return Decision.Allowed(BLANK)
        // Read off the raw text: an unparseable scheme is still a scheme.
        // "example.com:8080/x" has a port there, not a scheme.
        val scheme = SCHEME.find(text)?.takeUnless { HOST_AND_PORT.containsMatchIn(text) }?.groupValues?.get(1)?.lowercase()
        val candidate = if (scheme == null) "https://$text" else text
        if (scheme != null && scheme !in WEB_SCHEMES) {
            return Decision.Blocked(
                "scheme_blocked",
                "The browser opens http and https pages only, not \"$scheme:\".",
                "For an app link use open_intent. Nothing was opened.",
            )
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
        val host = uri?.host?.trim('[', ']')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return Decision.Blocked("url_malformed", "\"${text.take(120)}\" is not a web address.", "Pass a full https:// URL.")
        if (uri.userInfo != null) {
            return Decision.Blocked(
                "url_credentials",
                "A URL that carries a user name or password is not opened.",
                "Open the page without them; the user signs in on the page.",
            )
        }
        if (allowPrivateNetwork) return Decision.Allowed(candidate)
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return privateHost(host)
        }
        val literal = literalAddress(host)
        val addresses = when {
            literal != null -> listOf(literal)
            lookUp -> resolve(host)
            else -> emptyList()
        }
        if (addresses.any(::isPrivate)) return privateHost(host)
        if (lookUp && literal == null && addresses.isEmpty()) return Decision.Blocked(
            "dns_unresolved", "The browser could not resolve this address.", "Check the address or connection and try again.")
        return Decision.Allowed(candidate)
    }

    private fun privateHost(host: String) = Decision.Blocked(
        "private_network_blocked",
        "\"$host\" is this phone or its local network, which the browser does not open.",
        "Nothing was opened. Tell the user if the task really needs a device on their network.",
    )

    private companion object {
        const val BLANK = "about:blank"
        val WEB_SCHEMES = setOf("http", "https")
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        val HOST_AND_PORT = Regex("^[^/:?#]+:\\d+([/?#]|$)")
        val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** An address written out, parsed without asking the network. */
        fun literalAddress(host: String): InetAddress? =
            if (IPV4.matches(host) || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull() else null

        fun isPrivate(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            return when (address) {
                is Inet4Address -> {
                    val a = bytes[0].toInt() and 0xFF
                    val b = bytes[1].toInt() and 0xFF
                    // 0.0.0.0/8 "this network", which Linux connects to as loopback.
                    a == 0 ||
                        // 100.64.0.0/10: carrier-grade NAT, which is also where a VPN mesh lives.
                        (a == 100 && (b and 0xC0) == 0x40) ||
                        // 192.0.0.0/24: protocol assignments; 198.18.0.0/15: benchmarking.
                        (a == 192 && b == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
                        (a == 198 && (b and 0xFE) == 18) ||
                        // 240.0.0.0/4 reserved, with the broadcast address.
                        a >= 240
                }
                is Inet6Address ->
                    // fc00::/7: unique local addresses.
                    (bytes[0].toInt() and 0xFE) == 0xFC ||
                        // 64:ff9b::/96 NAT64 carries an IPv4 address in its last four bytes.
                        (isNat64(bytes) && isPrivate(InetAddress.getByAddress(bytes.copyOfRange(12, 16))))
                else -> false
            }
        }

        private val NAT64 = byteArrayOf(0, 0x64, 0xFF.toByte(), 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

        private fun isNat64(bytes: ByteArray): Boolean = bytes.size == 16 && (0 until 12).all { bytes[it] == NAT64[it] }
    }
}
