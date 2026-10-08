package dev.androidagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.androidagent.a11y.A11yServiceHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Observes only. Does not send, stop, select, change permissions or dispatch tools. */
class QaEvidenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = (context.applicationContext as AgentApplication).graph
                val session = intent.getStringExtra("session_id")?.also { require(UUID.fromString(it).toString() == it) }
                val draft = session?.let { withTimeout(3_000) { graph.sessions.composerDraft(it) } }
                pending.resultData = buildJsonObject {
                    put("ok", true)
                    graph.openChat.value?.let { put("activeSessionId", it) }
                    put("appInFront", graph.appInFront.value)
                    put("a11yBound", A11yServiceHandle.service.value != null)
                    put("runs", buildJsonObject {
                        graph.coordinator.sessionStates.value.forEach { (id, state) ->
                            if (state.active) put(id, state.phase.name)
                        }
                    })
                    session?.let { put("sessionId", it); put("draft", draft.orEmpty()) }
                }.toString()
                pending.resultCode = 1
            } catch (_: Exception) {
                pending.resultData = "{\"ok\":false,\"errorType\":\"qa_state_unavailable\"}"
                pending.resultCode = 0
            } finally { pending.finish() }
        }
    }
}
