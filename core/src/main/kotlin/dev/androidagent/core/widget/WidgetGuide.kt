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

package dev.androidagent.core.widget

/**
 * The widget format in a few lines, for the sessions that cannot load the
 * on-device `visual-widgets` skill (a computer's Claude or Codex session).
 * The skill is the full reference; keep the two in step.
 */
object WidgetGuide {
    val TEXT: String = """
Live visuals in the chat: write a fenced block with the language mike-widget and one JSON object. The Hey Mike app draws it as an interactive card (no browser). Use it only when the user wants to explore, tune, compare or see numbers; a plain answer stays plain. Label made-up numbers as sample data in the subtitle.

```mike-widget
{"title": "...", "subtitle": "Sample data", "dir": "rtl", "body": [
  {"type": "stats", "items": [{"label": "Per week", "value": "=perDay * 7", "format": "int", "icon": "check"}]},
  {"type": "slider", "id": "perDay", "label": "Calls per day", "min": 20, "max": 400, "step": 10, "value": 120},
  {"type": "chart", "kinds": ["bar", "line"], "points": 7, "xLabel": "D{i + 1}",
   "series": [{"name": "Calls", "values": "=perDay * (0.8 + 0.4 * noise(i))"}]},
  {"type": "button", "label": "Send my settings", "action": "ask"}
]}
```

Blocks: heading, text, stats (items with label, value, unit, format, icon, delta, color), slider, select, tabs, toggle, input (each with a unique id), chart (kinds bar, line, area, pie, donut, spark; x labels or points; series with values or an expression using i and n; pie slices), gauge, progress, table (columns, rows), dots (animated grid; expression uses x, y, i, j, t), card and row (children), divider, button (action copy or ask). Any block takes "when": "=expr".
Every input id is a variable in expressions. A number may be "=expr"; text may hold {expr}. Expressions: + - * / % ^, comparisons, && || !, cond ? a : b, 'text', pi, abs round(x,digits) floor ceil sqrt pow sin cos min max sum avg clamp mix mod dist noise rand. No loops or assignment.
Put one or two sentences before the block and say what to look at after it. Keep it to one screen. Set dir to rtl for Hebrew or Arabic. A button with action ask sends the user's current values back to you as their next message.
""".trim()
}
