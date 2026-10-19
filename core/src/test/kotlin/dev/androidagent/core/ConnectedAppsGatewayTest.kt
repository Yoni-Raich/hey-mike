package dev.androidagent.core

import dev.heymike.connect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConnectedAppsGatewayTest {
    private val schema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("color", buildJsonObject { put("type", "string") }) }); put("additionalProperties", false) }
    private val cap = Capability("set_theme", "Change theme", "Set a clock color", schema, access = Access.WRITE)
    private class Store : ConnectionGrantStore {
        override val grants = MutableStateFlow<List<ConnectionGrant>>(emptyList())
        override fun save(grant: ConnectionGrant) { grants.value = grants.value.filterNot { it.packageName == grant.packageName } + grant }
    }
    private inner class Transport : ConnectTransport {
        var app = ConnectProvider("clock.app", "ClockService", "Clock", "Clock settings", "signer", 1)
        var caps = listOf(cap)
        var calls = 0
        var lastId = ""
        var approval = false
        var error: Exception? = null
        var handler: (suspend (JsonObject) -> JsonObject)? = null
        override fun discover() = listOf(app)
        override suspend fun capabilities(provider: ConnectProvider) = caps
        override suspend fun call(provider: ConnectProvider, capability: Capability, arguments: JsonObject, requestId: String, origin: String, approved: Boolean, inputResponses: JsonObject, requestState: String?): JsonObject {
            calls++; lastId = requestId; approval = approved
            error?.let { throw it }
            return handler?.invoke(inputResponses) ?: ConnectProtocol.complete(buildJsonObject { put("color", "blue") })
        }
        override suspend fun openUi(provider: ConnectProvider, token: String) {}
    }
    private fun enable(s: Store, t: Transport) = s.save(ConnectionGrant(t.app.packageName, t.app.signer, capabilities = t.caps.map { CapabilityGrant(it.name, it.access, it.policyHash()) }))
    private fun args(operation: String = "call") = buildJsonObject { put("operation", operation); put("app", "clock.app"); put("capability", "set_theme"); put("requestId", "stable-1"); put("arguments", buildJsonObject { put("color", "blue") }) }
    private fun armed(t: Transport, s: Store, confirm: suspend (ConnectProvider, Capability, JsonObject) -> Boolean = {_,_,_->false}) =
        ConnectedAppsGateway(t, s, confirm).apply { beginRun("run", File(".")) }

    @Test fun grantsAndSchemaRefuseBeforeDispatch() = runTest {
        val s = Store(); val t = Transport(); val g = armed(t,s)
        assertFalse(g.invoke("connected_apps", args()).success); assertEquals(0,t.calls)
        enable(s,t)
        val invalid = JsonObject(args() + ("arguments" to buildJsonObject { put("color", 7) }))
        assertFalse(g.invoke("connected_apps", invalid).success); assertEquals(0,t.calls)
        assertTrue(g.invoke("connected_apps", args()).success); assertEquals("stable-1", t.lastId)
    }
    @Test fun changedPublisherOrSchemaRequiresFreshConsent() = runTest {
        val s = Store(); val t = Transport(); enable(s,t); val g = armed(t,s)
        t.caps = listOf(cap.copy(ui = Ui.ALWAYS))
        assertFalse(g.invoke("connected_apps", args()).success); assertEquals(0,t.calls)
        t.caps = listOf(cap); t.app = t.app.copy(signer = "other")
        assertFalse(g.invoke("connected_apps", args()).success); assertEquals(0,t.calls)
    }
    @Test fun sensitiveActionAlwaysNeedsApprovalAndNoModelFlagCanSupplyIt() = runTest {
        val s = Store(); val t = Transport(); t.caps = listOf(cap.copy(access = Access.SENSITIVE)); enable(s,t)
        assertFalse(armed(t,s).invoke("connected_apps", args()).success); assertEquals(0,t.calls)
        assertTrue(armed(t,s) {_,_,_->true}.invoke("connected_apps", args()).success); assertTrue(t.approval)
    }
    @Test fun unknownWriteIsNotRetriedAndKeepsStableId() = runTest {
        val s = Store(); val t = Transport(); enable(s,t)
        t.error = ConnectTransportException("outcome_unknown", true, "Lost response")
        val reply = armed(t,s).invoke("connected_apps", args()).text
        assertEquals(1,t.calls); assertTrue(reply.contains("\"mayHaveRun\":true")); assertTrue(reply.contains("stable-1"))
    }
    @Test fun readMayRetryOnceAndNewCapabilitiesStayOff() = runTest {
        val s=Store(); val t=Transport(); t.caps=listOf(cap.copy(access=Access.READ)); enable(s,t)
        t.error=ConnectTransportException("unavailable", false, "Offline")
        armed(t,s).invoke("connected_apps",args()); assertEquals(2,t.calls)
        t.error=null; t.caps=listOf(cap.copy(access=Access.SENSITIVE))
        assertFalse(armed(t,s).invoke("connected_apps",args()).success); assertEquals(2,t.calls)
    }
    @Test fun revokePreventsFurtherCallsAndNoScreenLeaseIsNeeded() = runTest {
        val s=Store(); val t=Transport(); enable(s,t); val g=armed(t,s)
        assertFalse(g.requiresDeviceLease("connected_apps")); assertFalse(g.deviceBackendLive())
        g.revoke(); assertFalse(g.invoke("connected_apps",args()).success); assertEquals(0,t.calls)
    }
    @Test fun textOnlyOversizeResultsAreBoundedAndPreserveUnknownWriteId() = runTest {
        val s=Store(); val t=Transport(); enable(s,t)
        t.handler = { buildJsonObject {
            put("resultType", "complete")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x".repeat(21_000)) }) })
        } }
        val reply=armed(t,s).invoke("connected_apps",args())
        assertFalse(reply.success); assertTrue(reply.text.length < 1_000)
        val body=Json.parseToJsonElement(reply.text).jsonObject
        assertEquals("result_too_large",body.string("code")); assertEquals("stable-1",body.string("requestId"))
        assertEquals(true,body["mayHaveRun"]?.jsonPrimitive?.boolean)
    }
    @Test fun revokingAnActiveGrantCancelsTheProviderCall() = runTest {
        val s=Store(); val t=Transport(); enable(s,t); val entered=CompletableDeferred<Unit>()
        t.handler={ entered.complete(Unit); awaitCancellation() }
        val call=async { armed(t,s).invoke("connected_apps",args()) }
        entered.await(); s.save(s.grants.value.single().copy(enabled=false))
        try { call.await(); fail("Revoked call completed") } catch (_: CancellationException) {}
        assertTrue(call.isCancelled)
    }
    private fun question() = buildJsonObject {
        put("resultType", "input_required"); put("requestState", "continuation-1")
        put("inputRequests", buildJsonObject { put("profile", buildJsonObject {
            put("method", "elicitation/create"); put("params", buildJsonObject {
                put("mode", "form"); put("message", "Which clock profile?")
                put("requestedSchema", buildJsonObject {
                    put("type", "object"); put("properties", buildJsonObject { put("value", buildJsonObject {
                        put("type", "string"); put("enum", JsonArray(listOf("Home", "Work").map(::JsonPrimitive)))
                    }) }); put("required", JsonArray(listOf(JsonPrimitive("value"))))
                })
            })
        }) })
    }
    @Test fun elicitationUsesTheRealAnswerAndResumesWithTheSameWriteId() = runTest {
        val s=Store(); val t=Transport(); enable(s,t); var questions=0
        t.handler={ responses -> if (responses.isEmpty()) question() else {
            assertEquals("Work",responses.getValue("profile").jsonObject.getValue("content").jsonObject.string("value"))
            ConnectProtocol.complete(buildJsonObject { put("saved",true) })
        } }
        val gateway=ConnectedAppsGateway(t,s,ask={ message,options ->
            questions++; assertEquals("Which clock profile?",message); assertEquals(listOf("Home","Work"),options); "Work"
        }).apply { beginRun("run",File(".")) }
        assertTrue(gateway.invoke("connected_apps",args()).success)
        assertEquals(1,questions); assertEquals(2,t.calls); assertEquals("stable-1",t.lastId)
    }
    @Test fun decliningAProviderQuestionDoesNotDispatchTheContinuation() = runTest {
        val s=Store(); val t=Transport(); enable(s,t); t.handler={ question() }
        val gateway=ConnectedAppsGateway(t,s,ask={ _,_ -> null }).apply { beginRun("run",File(".")) }
        assertFalse(gateway.invoke("connected_apps",args()).success)
        assertEquals(1,t.calls)
    }
    @Test fun connectionOffAndOnPreservesDeniedCapabilitiesAndAskBeforeChange() {
        val t=Transport()
        val initial=reviewedConnectionGrant(t.app,listOf(cap),null,true)
        val denied=initial.copy(capabilities=initial.capabilities.map { it.copy(enabled=false,askFirst=true) })
        val disabled=reviewedConnectionGrant(t.app,listOf(cap),denied,false)
        val restored=reviewedConnectionGrant(t.app,listOf(cap),disabled,true)
        assertTrue(restored.enabled); assertFalse(restored.capabilities.single().enabled)
        assertTrue(restored.capabilities.single().askFirst)
    }
    @Test fun reenablingAnAppDoesNotGrantNewOrExpandedFunctions() {
        val t=Transport(); val initial=reviewedConnectionGrant(t.app,listOf(cap),null,true)
        val changed=cap.copy(ui=Ui.ALWAYS)
        val added=cap.copy(name="delete_theme",access=Access.SENSITIVE)
        val reviewed=reviewedConnectionGrant(t.app,listOf(changed,added),initial,true)
        assertTrue(reviewed.capabilities.none { it.enabled })
    }
}
