package dev.androidagent.a11y

import dev.androidagent.core.ChatMessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppChatParserTest {
    private class Node(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        override val boundsInScreen: List<Int> = listOf(0, 0, 1080, 2400),
        override val packageName: String? = "com.whatsapp",
        val children: List<A11yNodeView> = emptyList(),
    ) : A11yNodeView {
        override val className = "android.view.View"
        override val isEnabled = true
        override val isClickable = false
        override val isScrollable = false
        override val isFocused = false
        override val isVisibleToUser = true
        override val isPassword = false
        override val isEditable = false
        override val childCount get() = children.size
        override fun child(index: Int) = children.getOrNull(index)
    }

    @Test fun parsesAnOrderedPersonalChat() {
        val root = Node(children = listOf(
            Node(text = "Dana", viewIdResourceName = "com.whatsapp:id/conversation_contact_name", boundsInScreen = listOf(100, 20, 600, 90)),
            Node(text = "hello", viewIdResourceName = "com.whatsapp:id/message_text", boundsInScreen = listOf(60, 300, 360, 390)),
            Node(text = "hi", viewIdResourceName = "com.whatsapp:id/message_text", boundsInScreen = listOf(720, 450, 1030, 540)),
        ))
        val result = WhatsAppChatParser.parse(A11yWindow(root, active = true, id = 4))
        assertTrue(result.textAvailable)
        assertEquals("Dana", result.chatTitle)
        assertEquals(listOf("hello", "hi"), result.messages.map { it.text })
        assertEquals(listOf(ChatMessageDirection.INCOMING, ChatMessageDirection.OUTGOING), result.messages.map { it.direction })
    }

    @Test fun groupSenderIsOptionalAndDirectionStillControlsTheUpdate() {
        val sender = Node(text = "Ari", viewIdResourceName = "com.whatsapp:id/sender_name")
        val incoming = Node(
            contentDescription = "incoming message",
            children = listOf(sender, Node(text = "same", viewIdResourceName = "com.whatsapp:id/message_text", boundsInScreen = listOf(400, 400, 700, 500))),
        )
        val root = Node(children = listOf(
            Node(text = "Family", viewIdResourceName = "com.whatsapp:id/conversation_contact_name", boundsInScreen = listOf(100, 20, 600, 90)),
            incoming,
        ))
        val result = WhatsAppChatParser.parse(A11yWindow(root, active = true))
        assertTrue(result.textAvailable)
        assertEquals(ChatMessageDirection.INCOMING, result.messages.single().direction)
        assertEquals("Ari", result.messages.single().sender)
    }

    @Test fun ambiguousCenteredMessageFailsClosed() {
        val root = Node(children = listOf(
            Node(text = "Dana", viewIdResourceName = "com.whatsapp:id/conversation_contact_name"),
            Node(text = "ambiguous", viewIdResourceName = "com.whatsapp:id/message_text", boundsInScreen = listOf(400, 300, 680, 390)),
        ))
        val result = WhatsAppChatParser.parse(A11yWindow(root, active = true))
        assertFalse(result.textAvailable)
        assertEquals(ChatMessageDirection.UNKNOWN, result.messages.single().direction)
    }
}
