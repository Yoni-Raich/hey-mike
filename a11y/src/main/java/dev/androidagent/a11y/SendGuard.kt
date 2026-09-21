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

package dev.androidagent.a11y

import java.util.Locale

/**
 * Recognises the moment a message would leave the phone, and reads who it goes
 * to and what it says.
 *
 * Opening a chat with the text typed in sends nothing; pressing Send does. So
 * this, not the intent that opened the chat, is what needs the user's answer.
 * Written against [A11yNodeView] so it runs off-device against fake trees.
 *
 * It errs toward asking: a false "this is a send" costs one extra approval,
 * a missed one sends a message nobody approved.
 */
object SendGuard {

    /** Apps whose Enter key or submit sends the typed text to someone. */
    val MESSAGING_PACKAGES: Map<String, String> = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Messages",
        "com.android.mms" to "Messages",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.viber.voip" to "Viber",
        "com.google.android.gm" to "Gmail",
        "com.microsoft.teams" to "Teams",
        "com.Slack" to "Slack",
        "com.discord" to "Discord",
    )

    private val SEND_LABELS = setOf(
        "send", "send message", "send now", "send sms", "send email", "send mms",
        "שלח", "שליחה", "שלח הודעה", "שליחת הודעה",
        "إرسال", "ارسال", "отправить", "enviar", "envoyer", "senden", "invia",
    )

    /** `…:id/send`, `send_button`, `sendBtn`, `compose_send`, `send_message_btn`. */
    private val SEND_ID = Regex("(^|[_.:/])send([_-]?(button|btn|message|msg|icon|fab|view))?(_btn|_button)?$", RegexOption.IGNORE_CASE)

    /** Where apps put the name of the chat. Checked in order. */
    private val TITLE_IDS = listOf(
        "conversation_contact_name", "conversation_title", "contact_name", "toolbar_title",
        "action_bar_title", "title_text", "chat_title", "header_title", "name",
    )

    fun appLabel(packageName: String): String = MESSAGING_PACKAGES[packageName] ?: packageName

    fun isMessagingApp(packageName: String?): Boolean = packageName != null && packageName in MESSAGING_PACKAGES

    /** True when [node] itself is labelled or identified as a Send control. */
    fun looksLikeSend(node: A11yNodeView): Boolean {
        val labels = listOfNotNull(node.text, node.contentDescription).map(::normalize)
        if (labels.any { it in SEND_LABELS }) return true
        val id = node.viewIdResourceName?.substringAfter(":id/", node.viewIdResourceName ?: "") ?: return false
        return SEND_ID.containsMatchIn(id)
    }

    /**
     * Whether tapping [target] would press Send: the target itself, a child
     * of it (a clickable frame around a send icon), or one of [ancestors]
     * (the icon inside a clickable send button).
     */
    fun isSendTap(target: A11yNodeView, ancestors: List<A11yNodeView>): Boolean {
        if (target.isEditable) return false
        if (looksLikeSend(target)) return true
        for (index in 0 until target.childCount) {
            val child = target.child(index) ?: continue
            if (!child.isEditable && looksLikeSend(child)) return true
        }
        return ancestors.take(3).any { looksLikeSend(it) }
    }

    /** The deepest visible node containing (x, y), with its ancestors nearest first. */
    fun nodeAt(root: A11yNodeView, x: Int, y: Int): Pair<A11yNodeView, List<A11yNodeView>>? {
        if (!root.contains(x, y)) return null
        var current = root
        val ancestors = ArrayDeque<A11yNodeView>()
        while (true) {
            // Later children are drawn on top, so they win a tie.
            val next = (current.childCount - 1 downTo 0)
                .mapNotNull { current.child(it) }
                .firstOrNull { it.isVisibleToUser && it.contains(x, y) }
                ?: break
            ancestors.addFirst(current)
            current = next
        }
        return current to ancestors.toList()
    }

    /** The first node on screen that is a Send control, for pressing it again after the approval. */
    fun findSend(root: A11yNodeView): A11yNodeView? =
        walk(root).firstOrNull { it.isVisibleToUser && it.isEnabled && !it.isEditable && looksLikeSend(it) }

    /** The chat's name as the app shows it, or null when the screen does not say. */
    fun recipient(root: A11yNodeView): String? {
        val nodes = walk(root).filter { it.isVisibleToUser }.toList()
        for (suffix in TITLE_IDS) {
            nodes.firstOrNull { node ->
                node.viewIdResourceName?.substringAfter(":id/")?.equals(suffix, ignoreCase = true) == true &&
                    !node.text.isNullOrBlank()
            }?.let { return it.text!!.trim() }
        }
        return null
    }

    /** The text waiting in the compose field, or null when it is empty or unreadable. */
    fun draft(root: A11yNodeView): String? =
        walk(root)
            .filter { it.isEditable && it.isVisibleToUser && !it.isPassword }
            .sortedByDescending { if (it.isFocused) 1 else 0 }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()

    private fun walk(root: A11yNodeView, limit: Int = 2_000): Sequence<A11yNodeView> = sequence {
        val stack = ArrayDeque<A11yNodeView>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < limit) {
            val node = stack.removeFirst()
            seen++
            yield(node)
            for (index in 0 until node.childCount) node.child(index)?.let { stack.add(it) }
        }
    }

    private fun A11yNodeView.contains(x: Int, y: Int): Boolean {
        val b = boundsInScreen
        return b.size == 4 && x >= b[0] && x < b[2] && y >= b[1] && y < b[3]
    }

    private fun normalize(label: String): String =
        label.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")
}
