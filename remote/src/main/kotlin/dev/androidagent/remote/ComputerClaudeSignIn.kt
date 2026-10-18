package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In-memory sign-in progress only. Pasted codes and credentials never enter this state. */
data class ComputerClaudeSignInState(
    val busy: Boolean = false,
    val loginUrl: String? = null,
    val error: String? = null,
)

/** Relays the computer's official Claude login through the phone, independently for each computer. */
class ComputerClaudeSignIn(
    private val scope: CoroutineScope,
    private val begin: suspend (String) -> AccountStatus,
    private val complete: suspend (String, String) -> AccountStatus,
    private val stop: suspend (String) -> Unit,
    private val refresh: suspend (String) -> Unit,
) {
    private val monitor = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val cancelling = mutableSetOf<String>()
    private val mutable = MutableStateFlow<Map<String, ComputerClaudeSignInState>>(emptyMap())
    val state = mutable.asStateFlow()
    private val links = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Open newly requested login links once; an existing link can also be reopened from the card. */
    val loginLinks = links.asSharedFlow()

    fun start(computerId: String) = run(computerId, "Could not open Claude sign-in. Check the computer connection and try again.") {
        update(computerId) { it.copy(loginUrl = null) }
        val status = begin(computerId)
        if (status.signedIn) {
            refresh(computerId)
        } else {
            val url = checkNotNull(status.loginUrl) { "No sign-in link" }
            update(computerId) { it.copy(loginUrl = url) }
            links.emit(url)
        }
    }

    fun submit(computerId: String, code: String) = run(computerId, "Sign-in did not finish or has expired. Start again.") {
        check(mutable.value[computerId]?.loginUrl != null) { "No waiting sign-in" }
        check(complete(computerId, code).signedIn) { "Sign-in did not finish" }
        update(computerId) { it.copy(loginUrl = null) }
        refresh(computerId)
    }

    fun check(computerId: String) = run(computerId, "Could not check Claude. Check the computer connection and try again.") {
        refresh(computerId)
    }

    /** Cancel only the waiting login. Existing computer chats and the saved account stay intact. */
    suspend fun cancel(computerId: String) {
        val job = synchronized(monitor) {
            if (!cancelling.add(computerId)) return
            jobs.remove(computerId).also { update(computerId) { it.copy(busy = true, loginUrl = null) } }
        }
        try {
            job?.cancelAndJoin()
            stop(computerId)
        } finally {
            synchronized(monitor) {
                mutable.update { it - computerId }
                cancelling.remove(computerId)
            }
        }
    }

    private fun run(computerId: String, message: String, block: suspend () -> Unit) {
        val job = synchronized(monitor) {
            if (computerId in jobs || computerId in cancelling) return
            update(computerId) { it.copy(busy = true, error = null) }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Never surface raw process output: it can contain login codes.
                    update(computerId) { it.copy(loginUrl = null, error = message) }
                } finally {
                    synchronized(monitor) {
                        if (computerId !in cancelling) {
                            update(computerId) { it.copy(busy = false) }
                            jobs.remove(computerId)
                        }
                    }
                }
            }.also { jobs[computerId] = it }
        }
        job.start()
    }

    private fun update(computerId: String, change: (ComputerClaudeSignInState) -> ComputerClaudeSignInState) {
        mutable.update { states -> states + (computerId to change(states[computerId] ?: ComputerClaudeSignInState())) }
    }
}
