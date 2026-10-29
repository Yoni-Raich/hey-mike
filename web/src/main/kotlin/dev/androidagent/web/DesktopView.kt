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

/**
 * A page asked for as a desktop site, as Chrome's "Desktop site" does: the
 * browser names itself as desktop Chrome and the page lays out at a desktop
 * width, shrunk to fit the phone.
 *
 * Both halves are needed. A site that reads only the user agent still sends a
 * responsive page, and a responsive page with `width=device-width` stays phone
 * sized whatever the user agent says, so [VIEWPORT_SCRIPT] rewrites the
 * viewport tag before the page lays out.
 */
object DesktopView {
    /** The layout width a desktop page gets, in CSS pixels. */
    const val WIDTH = 1024

    /**
     * Desktop Chrome's user agent, with the version of the WebView's own
     * Chrome ([mobile] is the WebView's default user agent). The Linux
     * platform is the one Chrome's own "Desktop site" uses on Android.
     */
    fun userAgent(mobile: String): String {
        val major = Regex("""Chrome/(\d+)""").find(mobile)?.groupValues?.get(1) ?: FALLBACK_CHROME
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    /**
     * Run at the start of every document: any viewport tag, now or added
     * later, asks for [WIDTH]. A page without one already gets a wide layout.
     */
    val VIEWPORT_SCRIPT = """
        (() => {
          const content = 'width=$WIDTH';
          const fix = () => document.querySelectorAll('meta[name="viewport" i]').forEach(meta => {
            if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
          });
          const touchesMeta = records => records.some(record => record.target.nodeName === 'META' ||
            Array.prototype.some.call(record.addedNodes, node => node.nodeName === 'META' || node.nodeName === 'HEAD'));
          new MutationObserver(records => { if (touchesMeta(records)) fix(); })
            .observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
          fix();
        })();
    """.trimIndent()

    /** Used only if the WebView's user agent names no Chrome version. */
    private const val FALLBACK_CHROME = "130"
}
