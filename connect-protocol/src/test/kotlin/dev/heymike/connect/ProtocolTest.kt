package dev.heymike.connect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun protocolErrorsKeepTheRequestIdOrExplicitNullForAnUnknownRequest() {
        assertEquals(JsonNull, ConnectProtocol.rpcError(null, -32700, "Invalid JSON")["id"])
        assertEquals(JsonPrimitive("rpc-1"), ConnectProtocol.rpcError(JsonPrimitive("rpc-1"), -32601, "Unknown method")["id"])
        assertEquals(JsonPrimitive(7), ConnectProtocol.rpcError(JsonPrimitive(7), -32601, "Unknown method")["id"])
    }
    @Serializable data class Theme(@Describe("A palette") val color: String, val size: Int = 20, val label: String? = null)
    @Test fun generatedSchemasValidateRequiredOptionalAndNullableValues() {
        val schema = Schema.generate(Theme.serializer().descriptor)
        Schema.validate(schema, buildJsonObject { put("color", "blue") })
        Schema.validate(schema, buildJsonObject { put("color", "blue"); put("label", JsonNull) })
        assertTrue(runCatching { Schema.validate(schema, buildJsonObject { put("size", 20) }) }.isFailure)
        assertTrue(runCatching { Schema.validate(schema, buildJsonObject { put("color", 3) }) }.isFailure)
        assertTrue(runCatching { Schema.validate(schema, buildJsonObject { put("color", "blue"); put("unknown", true) }) }.isFailure)
    }
    @Test fun hostileSchemasAndMessagesAreBounded() {
        assertTrue(runCatching { Schema.check(buildJsonObject { put("\$ref", "https://evil.test/schema") }) }.isFailure)
        assertTrue(runCatching { Schema.check(buildJsonObject { put("pattern", "(a+)+") }) }.isFailure)
        assertTrue(runCatching { ConnectProtocol.parse("[".repeat(100) + "]".repeat(100)) }.isFailure)
        assertTrue(runCatching { ConnectProtocol.parse("{\"text\":\"" + "x".repeat(300_000) + "\"}") }.isFailure)
    }
    @Test fun conflictingAnnotationsUseStricterAccess() {
        val cap = Capability("search_titles", "Search", "Find movies", Schema.generate(Theme.serializer().descriptor))
        val wire = JsonObject(cap.wire() + ("annotations" to buildJsonObject { put("destructiveHint", true) }))
        assertEquals(Access.SENSITIVE, Capability.fromWire(wire).access)
    }
    @Test fun canonicalFingerprintDoesNotDependOnArgumentOrder() {
        assertEquals(ConnectProtocol.fingerprint(buildJsonObject { put("x", 1); put("y", 2) }),
            ConnectProtocol.fingerprint(buildJsonObject { put("y", 2); put("x", 1) }))
    }
    @Test fun compositionAndLocalRefsValidateWithoutNetwork() {
        val schema = ConnectProtocol.parse("""{"type":"object","properties":{"x":{"REF":"#/DEFS/value"}},"DEFS":{"value":{"type":"integer","minimum":1,"maximum":5}}}""".replace("REF", "\$ref").replace("DEFS", "\$defs"))
        Schema.validate(schema, buildJsonObject { put("x", 3) })
        assertTrue(runCatching { Schema.validate(schema, buildJsonObject { put("x", 7) }) }.isFailure)
        val cycle = ConnectProtocol.parse("""{"DEFS":{"loop":{"REF":"#/DEFS/loop"}},"REF":"#/DEFS/loop"}""".replace("REF", "\$ref").replace("DEFS", "\$defs"))
        assertTrue(runCatching { Schema.validate(cycle, JsonPrimitive(1)) }.isFailure)
    }
}
