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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGuardTest {

    private class Node(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        override val boundsInScreen: List<Int> = listOf(0, 0, 1080, 2400),
        override val isEditable: Boolean = false,
        override val isFocused: Boolean = false,
        override val isClickable: Boolean = false,
        val children: List<A11yNodeView> = emptyList(),
    ) : A11yNodeView {
        override val className: String? = "android.view.View"
        override val packageName: String? = "com.whatsapp"
        override val isEnabled = true
        override val isScrollable = false
        override val isVisibleToUser = true
        override val isPassword = false
        override val isCheckable = false
        override val isChecked = false
        override val childCount get() = children.size
        override fun child(index: Int) = children.getOrNull(index)
    }

    /** The WhatsApp chat the phone test used: title, compose field, send button. */
    private val sendIcon = Node(viewIdResourceName = "com.whatsapp:id/send", contentDescription = "Send", boundsInScreen = listOf(940, 2200, 1060, 2320), isClickable = true)
    private val chat = Node(
        children = listOf(
            Node(viewIdResourceName = "com.whatsapp:id/conversation_contact_name", text = "My Wife אישתי היפה ❤", boundsInScreen = listOf(150, 100, 700, 180)),
            Node(viewIdResourceName = "com.whatsapp:id/message_text", text = "טוב", boundsInScreen = listOf(700, 1700, 1030, 1800)),
            Node(viewIdResourceName = "com.whatsapp:id/entry", text = "hi", isEditable = true, isFocused = true, boundsInScreen = listOf(150, 2200, 920, 2320)),
            sendIcon,
        ),
    )

    @Test fun theSendButtonIsRecognisedByIdLabelOrHebrewLabel() {
        assertTrue(SendGuard.looksLikeSend(sendIcon))
        assertTrue(SendGuard.looksLikeSend(Node(contentDescription = "שלח")))
        assertTrue(SendGuard.looksLikeSend(Node(viewIdResourceName = "com.google.android.apps.messaging:id/send_message_button_icon".replace("_icon", ""))))
        assertTrue(SendGuard.looksLikeSend(Node(viewIdResourceName = "org.telegram:id/send_button")))
        assertFalse(SendGuard.looksLikeSend(Node(text = "Sender name")))
        assertFalse(SendGuard.looksLikeSend(Node(viewIdResourceName = "com.whatsapp:id/sender_name")))
        assertFalse(SendGuard.looksLikeSend(Node(text = "טוב")))
    }

    @Test fun aCoordinateTapOnSendIsASendAndOnAMessageIsNot() {
        val (onSend, sendAncestors) = SendGuard.nodeAt(chat, 1000, 2260)!!
        assertTrue(SendGuard.isSendTap(onSend, sendAncestors))
        val (onMessage, messageAncestors) = SendGuard.nodeAt(chat, 800, 1750)!!
        assertFalse(SendGuard.isSendTap(onMessage, messageAncestors))
    }

    @Test fun anIconInsideAClickableSendFrameCountsBothWays() {
        val icon = Node(contentDescription = "Send")
        val frame = Node(isClickable = true, children = listOf(icon))
        assertTrue("tapping the frame presses the icon inside it", SendGuard.isSendTap(frame, emptyList()))
        assertTrue("tapping the icon presses its frame", SendGuard.isSendTap(Node(), listOf(Node(contentDescription = "Send"))))
    }

    @Test fun theRecipientAndDraftAreReadForTheApprovalCard() {
        assertEquals("My Wife אישתי היפה ❤", SendGuard.recipient(chat))
        assertEquals("hi", SendGuard.draft(chat))
        assertNull(SendGuard.recipient(Node(children = listOf(Node(text = "Chats")))))
    }

    @Test fun theSendControlIsFoundAgainAfterComingBack() {
        assertEquals(sendIcon, SendGuard.findSend(chat))
    }
}
