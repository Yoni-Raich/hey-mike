package dev.androidagent.core

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/** Local checks for exact writes. An accepted dispatch is not a verified value. */
internal class JevMutationEvidence {
    private data class Write(val identity: String, val field: JsonObject, val expected: String, var verified: Boolean,
        val relocatable: Boolean,
        var consumed: Boolean = false)
    private val textWrites = linkedMapOf<String, Write>()
    private val blockedSubmissions = mutableSetOf<String>()
    var revision = 0
        private set

    fun recordText(before: JsonObject, nodeId: String, expected: String, dispatch: ToolDispatch) {
        if (dispatch == ToolDispatch.NOT_DISPATCHED) return
        val semantics = JevUiSemantics(before)
        val field = semantics.nodes.singleOrNull { it["nodeId"]?.jsonPrimitive?.contentOrNull == nodeId }
        val identity = field?.let(semantics::identity) ?: "unresolved-write-${revision + 1}"
        val relocatable = semantics.find(identity) != null
        val key = if (relocatable) identity else "$identity:ambiguous-${revision + 1}"
        textWrites[key] = Write(identity, field?.let(semantics::fieldState) ?: buildJsonObject { put("label", "Unknown field") },
            expected, dispatch == ToolDispatch.VERIFIED, relocatable)
        changed()
    }

    suspend fun reconcile(observation: JevObservation, invoke: suspend (String, JsonObject) -> ToolResult) {
        for (write in textWrites.values) {
            if (write.consumed || !write.relocatable) continue
            val field = observation.semantics.find(write.identity) ?: continue
            val verified = try {
                val result = invoke("verify_text", buildJsonObject {
                    put("nodeId", field.getValue("nodeId")); put("observationId", observation.observationId)
                    put("text", write.expected)
                })
                result.success && Json.parseToJsonElement(result.text).jsonObject["matches"]?.jsonPrimitive?.booleanOrNull == true
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (disabled: JevDisabledException) { throw disabled }
              catch (_: Exception) { false }
            if (verified != write.verified) { write.verified = verified; changed() }
        }
    }

    private fun changed() { revision++; blockedSubmissions.clear() }
    val resolved: Boolean get() = textWrites.values.all { it.verified }
    fun blockSubmission(key: String) { blockedSubmissions += key }
    fun allows(key: String) = key !in blockedSubmissions
    fun submitted(packageName: String?) {
        textWrites.values.filter { it.verified && samePackage(it.field, packageName) }.forEach { it.consumed = true }
        changed()
    }

    fun state(): JsonArray = describe(onlyPending = true)
    fun pendingIn(packageName: String?): JsonArray = JsonArray(state().filter { entry ->
        samePackage(entry.jsonObject.getValue("field").jsonObject, packageName)
    })
    fun activeIn(packageName: String?): JsonArray = JsonArray(proofState().filter { entry ->
        entry.jsonObject["consumed"]?.jsonPrimitive?.booleanOrNull != true &&
            samePackage(entry.jsonObject.getValue("field").jsonObject, packageName)
    })
    private fun samePackage(field: JsonObject, packageName: String?): Boolean {
        val pkg = field["package"]?.jsonPrimitive?.contentOrNull
        return pkg == null || packageName == null || pkg == packageName
    }
    fun proofState(): JsonArray = describe(onlyPending = false)
    private fun describe(onlyPending: Boolean): JsonArray = buildJsonArray {
        textWrites.values.filter { !onlyPending || !it.verified }.forEach { write -> add(buildJsonObject {
            put("field", JsonObject(write.field - "currentValuePreview"))
            put("expectedLength", write.expected.length)
            put("expectedPreview", UiObservationSerializer.safeField(write.expected.take(200)))
            put("previewIsNotProof", true)
            put("expectedDigest", JevUiSemantics.digest(write.expected))
            put("consumed", write.consumed)
            put("verified", write.verified)
            if (!write.relocatable) put("identityAmbiguous", true)
            put("status", if (write.verified) "exact requested value verified locally" else "exact requested value not verified; repair before dependent submission")
        }) }
    }
}
