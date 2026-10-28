package dev.androidagent.core

import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** App-owned, text-free ancestry; the model cannot declare its own source permission. */
@Serializable
data class TaskSourceRef(val source: String, val app: String? = null, val conversation: String? = null,
    val sessionId: String? = null, val group: Boolean = false, val verified: Boolean = false,
    val noteId: String? = null)

object TaskSourcePolicy {
    fun of(signal: TaskSignal) = TaskSourceRef(signal.source, signal.app, signal.conversation, signal.sessionId, signal.group, signal.sourceVerified)
    fun allows(ref: TaskSourceRef, settings: TaskBoardSettings): Boolean {
        if (ref.source in setOf("chat", "backfill") && !settings.readChats) return false
        if (ref.source == "calendar" && !settings.readCalendar) return false
        if (ref.group && !settings.includeGroups) return false
        if (ref.app in settings.excludedApps) return false
        if ("${ref.app}|${ref.conversation}" in settings.excludedConversations) return false
        // Legacy external content did not record profile provenance. Never infer that it is personal.
        return ref.verified || ref.source in setOf("user", "chat", "backfill", "calendar")
    }
    fun changed(a: TaskBoardSettings, b: TaskBoardSettings) = a.excludedApps != b.excludedApps ||
        a.excludedConversations != b.excludedConversations || a.includeGroups != b.includeGroups ||
        a.readCalendar != b.readCalendar || a.readChats != b.readChats || a.discoveryOn != b.discoveryOn ||
        a.memoryForgetRevision != b.memoryForgetRevision
}

/** Scoped by the app when dispatching a managed model tool; checked inside storage writes. */
class TaskWritePermit(val policyRevision: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskWritePermit>
}
