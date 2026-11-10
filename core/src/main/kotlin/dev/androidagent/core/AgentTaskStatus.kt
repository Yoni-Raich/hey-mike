package dev.androidagent.core

/**
 * Where a child agent's task stands. Phone children (`session_agents`) and
 * computer children (`computers`) share this one vocabulary, so the user, the
 * code and the parent agent all read the same words.
 *
 * [wire] is what receipts store and what the parent agent sees in tool results.
 * Those strings are a contract: add a value, never rename one.
 */
enum class AgentTaskStatus(val wire: String, val terminal: Boolean = false) {
    QUEUED("queued"),
    STARTING("starting"),
    RUNNING("running"),
    /** A live approval or `ask_user` question waits in the child. */
    WAITING_FOR_USER("waiting_for_user"),
    STOPPING("stopping"),
    COMPLETED("completed", terminal = true),
    FAILED("failed", terminal = true),
    /** Stopped by the user or the parent. Completed actions are not undone. */
    CANCELLED("cancelled", terminal = true),
    /** The work may have happened. Inspect the child; never start a replacement. */
    UNKNOWN("unknown", terminal = true);

    /** The parent agent or the user has to look: a gate, or an end that is not success. */
    val needsAttention: Boolean get() = this == WAITING_FOR_USER || this == FAILED || this == CANCELLED || this == UNKNOWN

    companion object {
        /**
         * Reads a stored or reported status. `cancelling` is the name computer
         * tasks used for [STOPPING]. A word this build does not know is work
         * in progress: it must not look finished.
         */
        fun of(wire: String): AgentTaskStatus =
            if (wire == "cancelling") STOPPING else entries.firstOrNull { it.wire == wire } ?: RUNNING

        /** The status of a child whose run has ended. A run with no recorded outcome finished normally. */
        fun ended(outcome: RunOutcome?): AgentTaskStatus = when (outcome) {
            RunOutcome.UNKNOWN -> UNKNOWN
            RunOutcome.FAILED -> FAILED
            RunOutcome.STOPPED -> CANCELLED
            RunOutcome.COMPLETED, null -> COMPLETED
        }

        /** The status of a child from its run alone. A caller that asked for a stop decides [STOPPING] itself. */
        fun of(run: RunState): AgentTaskStatus = when {
            run.waitingForUser -> WAITING_FOR_USER
            run.active && run.phase == RunPhase.STOPPING -> STOPPING
            run.active -> RUNNING
            else -> ended(run.outcome)
        }
    }
}
