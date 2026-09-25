package dev.androidagent.core

/** One task's recovery memory, retained across budget continuations. */
internal class JevProgressTracker {
    private val refused = mutableSetOf<String>()
    private val exhausted = mutableSetOf<String>()
    private val transitions = mutableMapOf<String, Int>()
    private val waits = mutableMapOf<String, Int>()
    private val rejectedCompletion = mutableSetOf<String>()
    private var milestoneRevision = 0

    fun checkpoint(revision: Int) {
        if (revision == milestoneRevision) return
        milestoneRevision = revision
        // A newly proved requirement can need a route used by an earlier one.
        // Definite capability refusals survive; navigation loop counts do not.
        exhausted.clear()
        transitions.clear()
        waits.clear()
        rejectedCompletion.clear()
    }

    fun allows(screen: String, action: String) = action !in refused && "$screen:$action" !in exhausted
    fun refuse(action: String) { refused += action }
    fun decline(screen: String, action: String) { exhausted += "$screen:$action" }
    fun record(before: String, action: String, after: String) {
        val edge = "$before:$action:$after"
        val visits = (transitions[edge] ?: 0) + 1
        transitions[edge] = visits
        if (before == after || visits >= 2) decline(before, action)
    }
    fun waited(screen: String, after: String) {
        if (screen == after) waits[screen] = (waits[screen] ?: 0) + 1
    }
    fun canWait(screen: String) = (waits[screen] ?: 0) < 2
    fun rejectDone(screen: String, evidenceRevision: Int) { rejectedCompletion += "$screen:$evidenceRevision" }
    fun canFinish(screen: String, evidenceRevision: Int) = "$screen:$evidenceRevision" !in rejectedCompletion
}
