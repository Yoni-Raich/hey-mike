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


package dev.androidagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailConnectorTest {
    @Test fun quotedHistoryIsCut() {
        val body = "Can we move it to 5?\nThanks\n\nOn Mon, 3 Nov 2026 at 10:00, Dana <d@x.com> wrote:\n> old text"
        assertEquals("Can we move it to 5?\nThanks", GmailConnector.withoutQuote(body))
        assertEquals("שלום\nמה נשמע", GmailConnector.withoutQuote("שלום\nמה נשמע\n> ציטוט"))
    }

    @Test fun htmlBecomesText() {
        val text = GmailConnector.stripHtml("<style>p{}</style><p>Hi&nbsp;there</p><div>Line&amp;2</div>")
        assertEquals("Hi there\nLine&2", text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
    }

    @Test fun displayNameDropsTheAddress() {
        assertEquals("Dana Levi", GmailConnector.displayName("\"Dana Levi\" <dana@example.com>"))
        assertEquals("dana@example.com", GmailConnector.displayName("dana@example.com"))
    }
}
