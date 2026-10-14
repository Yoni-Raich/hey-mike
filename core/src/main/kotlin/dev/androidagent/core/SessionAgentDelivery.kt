package dev.androidagent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Batches public text without discarding any segment. No restored event is automatically replayed. */
class SessionAgentDelivery(
    scope: CoroutineScope,
    updates: Flow<SessionAgentUpdate>,
    changes: Flow<*>,
    private val active: (String) -> Boolean,
    /** False means not dispatched yet (startup, an approval or a response already speaking). */
    private val deliver: suspend (String, List<SessionAgentUpdate>, () -> Boolean) -> Boolean,
    private val failed: suspend (String, Throwable) -> Unit = { _, _ -> },
    private val batchMs: Long = 500,
) {
    private val lock = Mutex()
    private val pending = linkedMapOf<String, MutableList<SessionAgentUpdate>>()
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            updates.collect { update ->
                lock.withLock {
                    update.parents.filter { update.allowed(it) }.forEach { parent ->
                        pending.getOrPut(parent) { mutableListOf() }.add(update)
                    }
                }
                signals.trySend(Unit)
            }
        }
        scope.launch { changes.collect { signals.trySend(Unit) } }
        scope.launch {
            for (signal in signals) {
                delay(batchMs)
                val parents = lock.withLock { pending.keys.toList() }
                var waiting = false
                parents.forEach { parent ->
                    val batch = lock.withLock {
                        val list = pending[parent] ?: return@withLock emptyList()
                        list.removeAll { !it.allowed(parent) }
                        if (list.isEmpty()) { pending.remove(parent); emptyList() }
                        else if (active(parent) || list.any { it.wake }) list.toList()
                        else emptyList()
                    }
                    if (batch.isEmpty()) return@forEach
                    fun current() = batch.all { it.allowed(parent) }
                    try {
                        if (!current()) {
                            lock.withLock { pending[parent]?.removeAll { !it.allowed(parent) } }
                            waiting = true
                        } else if (deliver(parent, batch, ::current)) {
                            lock.withLock { pending[parent]?.let { list ->
                                batch.forEach { list.remove(it) }
                                if (list.isEmpty()) pending.remove(parent)
                            } }
                        } else waiting = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        // A transport error has an unknown outcome. Never replay it silently.
                        lock.withLock { pending[parent]?.let { list ->
                            batch.forEach { list.remove(it) }
                            if (list.isEmpty()) pending.remove(parent)
                        } }
                        failed(parent, error)
                    }
                }
                if (waiting) signals.trySend(Unit)
            }
        }
    }
}
