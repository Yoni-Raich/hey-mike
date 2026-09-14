package dev.androidagent.a11y

import dev.androidagent.core.ChatMessageDirection
import dev.androidagent.core.ForegroundChatObserver
import dev.androidagent.core.ForegroundChatSnapshot
import dev.androidagent.core.VisibleChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/** Reads only the active WhatsApp conversation after the app has explicit consent. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WhatsAppChatObserver : ForegroundChatObserver {
    override val changes: Flow<Unit> = A11yServiceHandle.service.flatMapLatest { service ->
        if (service == null) flowOf(Unit) else merge(flowOf(Unit), service.screenChanges)
    }

    override suspend fun snapshot(): ForegroundChatSnapshot {
        val service = A11yServiceHandle.service.value
            ?: return ForegroundChatSnapshot(null, null, null, emptyList(), textAvailable = false)
        val active = service.visibleWindows().firstOrNull { it.active }
            ?: return ForegroundChatSnapshot(null, null, null, emptyList(), textAvailable = false)
        return WhatsAppChatParser.parse(active)
    }
}

/** Pure parser so real WhatsApp trees can be captured once and covered by JVM tests. */
object WhatsAppChatParser {
    private val packages = setOf("com.whatsapp", "com.whatsapp.w4b")
    // quoted_text belongs to a reply preview and is not a new message itself.
    private val messageIds = setOf("message_text", "caption")
    private val outgoingHints = Regex("(^|[^a-z])(outgoing|from[_ -]?me|message[_ -]?out|you)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val incomingHints = Regex("(^|[^a-z])(incoming|message[_ -]?in|from[_ -]?them)([^a-z]|$)", RegexOption.IGNORE_CASE)

    fun parse(window: A11yWindow): ForegroundChatSnapshot {
        val root = window.root
        val pkg = root?.packageName
        if (root == null || pkg !in packages || !window.active) {
            return ForegroundChatSnapshot(pkg, window.id, null, emptyList(), textAvailable = false)
        }
        val title = SendGuard.recipient(root)
        val rootBounds = root.boundsInScreen
        val horizontalBounds = rootBounds.takeIf { it.size == 4 && it[2] > it[0] }
        val candidates = mutableListOf<Candidate>()
        walk(root).forEach { (node, ancestors) ->
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            val text = node.text?.trim().orEmpty()
            if (node.isVisibleToUser && !node.isPassword && !node.isEditable && id in messageIds && text.isNotEmpty()) {
                candidates += Candidate(
                    message = VisibleChatMessage(
                        text = text,
                        direction = direction(node, ancestors, horizontalBounds),
                        sender = sender(ancestors),
                    ),
                    bounds = node.boundsInScreen,
                )
            }
        }
        val messages = candidates
            .sortedWith(compareBy<Candidate> { it.bounds.getOrNull(1) ?: Int.MAX_VALUE }.thenBy { it.bounds.getOrNull(0) ?: 0 })
            .map { it.message }
        return ForegroundChatSnapshot(
            packageName = pkg,
            windowId = window.id,
            chatTitle = title,
            messages = messages,
            textAvailable = title != null && messages.isNotEmpty() && messages.none { it.direction == ChatMessageDirection.UNKNOWN },
        )
    }

    private fun direction(
        node: A11yNodeView,
        ancestors: List<A11yNodeView>,
        windowBounds: List<Int>?,
    ): ChatMessageDirection {
        val hints = (listOf(node) + ancestors.take(4)).joinToString(" ") {
            listOfNotNull(it.viewIdResourceName, it.contentDescription).joinToString(" ")
        }
        if (outgoingHints.containsMatchIn(hints)) return ChatMessageDirection.OUTGOING
        if (incomingHints.containsMatchIn(hints)) return ChatMessageDirection.INCOMING
        val bounds = node.boundsInScreen
        if (windowBounds == null || bounds.size != 4) return ChatMessageDirection.UNKNOWN
        val windowWidth = windowBounds[2] - windowBounds[0]
        val center = (bounds[0] + bounds[2]) / 2f
        return when {
            center >= windowBounds[0] + windowWidth * 0.62f -> ChatMessageDirection.OUTGOING
            center <= windowBounds[0] + windowWidth * 0.38f -> ChatMessageDirection.INCOMING
            else -> ChatMessageDirection.UNKNOWN
        }
    }

    private fun sender(ancestors: List<A11yNodeView>): String? = ancestors.asSequence().take(2)
        .flatMap { parent -> (0 until parent.childCount).asSequence().mapNotNull(parent::child) }
        .firstOrNull { it.viewIdResourceName?.substringAfter(":id/")?.contains("sender_name", ignoreCase = true) == true }
        ?.text?.trim()?.takeIf(String::isNotEmpty)

    private fun walk(root: A11yNodeView, limit: Int = 2_000): Sequence<Pair<A11yNodeView, List<A11yNodeView>>> = sequence {
        val queue = ArrayDeque<Pair<A11yNodeView, List<A11yNodeView>>>()
        queue.add(root to emptyList())
        var seen = 0
        while (queue.isNotEmpty() && seen++ < limit) {
            val (node, ancestors) = queue.removeFirst()
            yield(node to ancestors)
            val next = listOf(node) + ancestors.take(7)
            for (index in 0 until node.childCount) node.child(index)?.let { queue.add(it to next) }
        }
    }

    private data class Candidate(val message: VisibleChatMessage, val bounds: List<Int>)
}
