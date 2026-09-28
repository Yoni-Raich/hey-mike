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

package dev.androidagent.app.assist

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View

/**
 * The screen the user summoned Mike over, as text the voice model can read.
 *
 * Android hands the assistant this structure itself, and only while the user
 * leaves "Use text from screen" on, so it needs no screen-access permission
 * and never includes a password field's contents.
 */
object ScreenText {
    /** Enough for a chat, an article or a form; a long page is cut, not summarised. */
    const val MAX_CHARS = 4_000

    /** Walk every window of [structure] in reading order. */
    fun from(structure: AssistStructure): ScreenCapture {
        val lines = ArrayList<String>()
        for (index in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(index).rootViewNode?.let { collect(it, lines) }
        }
        return ScreenCapture(structure.activityComponent?.packageName, lines)
    }

    private fun collect(node: AssistStructure.ViewNode, into: MutableList<String>) {
        if (node.visibility != View.VISIBLE) return
        line(
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            hint = node.hint,
            editable = node.className?.contains("EditText") == true,
            password = isPassword(node.inputType),
        )?.let(into::add)
        for (child in 0 until node.childCount) collect(node.getChildAt(child), into)
    }

    /** One view as a line, or null when it shows nothing worth reading. */
    fun line(text: String?, description: String?, hint: String?, editable: Boolean, password: Boolean): String? {
        if (password) return null
        val shown = text?.trim().orEmpty().ifEmpty { description?.trim().orEmpty() }
        if (editable) {
            val label = hint?.trim().orEmpty()
            return when {
                shown.isNotEmpty() && label.isNotEmpty() && shown != label -> "[field: $label] $shown"
                shown.isNotEmpty() -> "[field] $shown"
                label.isNotEmpty() -> "[empty field: $label]"
                else -> null
            }
        }
        return shown.takeIf { it.isNotEmpty() }
    }

    /**
     * The developer message that hands the screen to the voice model. It says
     * what the text is for and asks for silence, so the model waits for the
     * user instead of reading the screen back to them.
     */
    fun prompt(capture: ScreenCapture?): String {
        val header = "The user opened you with the assistant button while looking at their phone screen. " +
            "Do not reply to this message and do not describe the screen unless asked; wait for the user to speak. " +
            "When they say \"this\", \"here\" or \"on the screen\", they mean what is below."
        val body = capture?.lines?.let(::joined)
        if (capture == null || body.isNullOrEmpty()) {
            return "$header\nThe screen text is not available (\"Use text from screen\" may be off). " +
                "If the user asks about the screen, delegate to Codex, which can read it with its device tools."
        }
        val app = capture.packageName?.let { "App: $it\n" }.orEmpty()
        return "$header\n$app--- screen text ---\n$body\n--- end of screen text ---"
    }

    /** Drops repeated lines (a label read from both a view and its parent) and cuts at [MAX_CHARS]. */
    fun joined(lines: List<String>): String {
        val out = StringBuilder()
        var previous: String? = null
        for (line in lines) {
            val clean = line.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty() || clean == previous) continue
            previous = clean
            if (out.length + clean.length + 1 > MAX_CHARS) {
                out.append("…")
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(clean)
        }
        return out.toString()
    }

    private fun isPassword(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}

/** What [ScreenText.from] read: the app in front and its visible text, top to bottom. */
data class ScreenCapture(val packageName: String?, val lines: List<String>)
