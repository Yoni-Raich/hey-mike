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

class ProxyDiagnosticsTest {

    @Test fun aHealthyBufferExplainsNothing() {
        assertNull(ProxyDiagnostics.explain(emptyList()))
        assertNull(ProxyDiagnostics.explain(listOf("listening:41234", "CONNECT chatgpt.com:443", "stopped")))
    }

    @Test fun eachCategoryNamesItsOwnRemedy() {
        val dns = ProxyDiagnostics.explain(listOf("proxy-error:dns"))!!
        assertTrue(dns.contains("look up"))
        assertTrue(dns.contains("DNS"))

        val connection = ProxyDiagnostics.explain(listOf("proxy-error:connection"))!!
        assertTrue(connection.contains("refused"))

        val timeout = ProxyDiagnostics.explain(listOf("proxy-error:timeout"))!!
        assertTrue(timeout.contains("did not answer"))

        // The three must be distinguishable — telling them apart is the whole
        // reason this exists.
        assertEquals(3, setOf(dns, connection, timeout).size)
    }

    @Test fun theMostRecentFailureWins() {
        // The buffer spans the process lifetime, so an error from twenty
        // minutes ago says nothing about the turn that just failed.
        val explained = ProxyDiagnostics.explain(
            listOf(
                "proxy-error:dns",
                "CONNECT chatgpt.com:443",
                "proxy-error:connection",
            ),
        )!!
        assertTrue(explained.contains("refused"))
    }

    @Test fun anUnknownCategoryIsStillReported() {
        val explained = ProxyDiagnostics.explain(listOf("proxy-error:something-new"))!!
        assertTrue(explained.contains("something-new"))
    }

    @Test fun anEmptyCategoryDoesNotProduceARaggedSentence() {
        val explained = ProxyDiagnostics.explain(listOf("proxy-error:"))!!
        assertTrue(explained.contains("unknown"))
    }

    @Test fun deniedAndAllowedEntriesAreNotFailures() {
        // A denied host surfaces as 403 and is a different problem; it must not
        // be reported as a connection failure.
        assertNull(
            ProxyDiagnostics.explain(
                listOf("denied:evil.example:443:host-not-allowed", "CONNECT chatgpt.com:443"),
            ),
        )
    }

    @Test fun theStatusLineIsPrefixedAndAbsentWhenHealthy() {
        assertNull(ProxyDiagnostics.statusLine(emptyList()))
        assertTrue(ProxyDiagnostics.statusLine(listOf("proxy-error:dns"))!!.startsWith("Network: "))
    }
}
