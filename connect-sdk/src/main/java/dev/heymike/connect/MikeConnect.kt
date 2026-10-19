// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import android.content.Intent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*

class MikeConnect private constructor(val capabilities: List<RegisteredCapability>, val trusted: List<TrustedAgent>) {
    class Builder {
        private val entries = mutableListOf<RegisteredCapability>()
        private val agents = mutableListOf(TrustedAgents.HEY_MIKE)
        fun trust(agent: TrustedAgent) { agents += agent }
        fun addCapability(name: String, configure: java.util.function.Consumer<CapabilityBuilder>) {
            capability(name) { configure.accept(this) }
        }
        fun capability(name: String, configure: CapabilityBuilder.() -> Unit) {
            val entry = CapabilityBuilder(name).apply(configure).build()
            require(entries.none { it.definition.name == name }) { "Duplicate capability" }
            require(entries.size < 64) { "Too many capabilities" }
            entries += entry
        }
        fun build() = MikeConnect(entries.toList(), agents.distinct())
    }
    class CapabilityBuilder(private val name: String) {
        var title: String = name
        var description: String = ""
        var access: Access = Access.READ
        var ui: Ui = Ui.NONE
        var summary: String = ""
        var timeoutMs: Long = 15_000
        var inputSchema: JsonObject = buildJsonObject { put("type", "object"); put("additionalProperties", false) }
        var outputSchema: JsonObject? = null
        private var handler: (suspend CallContext.(JsonObject) -> JsonElement)? = null
        fun handleJson(block: suspend CallContext.(JsonObject) -> JsonElement) { handler = block }
        fun handle(handler: CapabilityHandler) { this.handler = { args -> handler.execute(this, args) } }
        inline fun <reified I, reified O> handle(noinline block: suspend CallContext.(I) -> O) = handle(serializer<I>(), serializer<O>(), block)
        fun <I, O> handle(input: KSerializer<I>, output: KSerializer<O>, block: suspend CallContext.(I) -> O) {
            inputSchema = Schema.generate(input.descriptor)
            outputSchema = Schema.generate(output.descriptor)
            handler = { args -> ConnectProtocol.json.encodeToJsonElement(output, block(ConnectProtocol.json.decodeFromJsonElement(input, args))) }
        }
        fun build(): RegisteredCapability = RegisteredCapability(
            Capability(name, title, description, inputSchema, outputSchema, access, ui, summary.ifBlank { title }, timeoutMs).also { it.validate() },
            requireNotNull(handler) { "A capability needs a handler" })
    }
}
data class RegisteredCapability(val definition: Capability, val handler: suspend CallContext.(JsonObject) -> JsonElement)
fun interface CapabilityHandler { fun execute(context: CallContext, arguments: JsonObject): JsonElement }

class CallContext internal constructor(
    val requestId: String, val origin: String, val userApproved: Boolean,
    private val responses: JsonObject, private val notify: (JsonObject) -> Unit,
    private val uiToken: (Intent) -> String,
) {
    internal var openedUi: String? = null
    fun requireChoice(key: String, question: String, options: List<String>): String {
        require(options.size in 1..6 && options.all { it.length <= 120 })
        val answer = answer(key)
        if (answer != null) {
            if (answer !in options) throw ConnectException("invalid_arguments", "Choose one of the offered values")
            return answer
        }
        throw InputRequired(key, question, options)
    }
    fun requireText(key: String, question: String): String = answer(key) ?: throw InputRequired(key, question, emptyList())
    private fun answer(key: String): String? {
        val response = responses[key] as? JsonObject ?: return null
        if (response.string("action") != "accept") throw ConnectException("cancelled", "User declined the question")
        return (response["content"] as? JsonObject)?.string("value")?.takeIf { it.isNotBlank() }
    }
    fun openUi(intent: Intent) { openedUi = uiToken(intent) }
    fun progress(fraction: Double, message: String = "") {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        notify(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/progress")
            put("params", buildJsonObject { put("progressToken", requestId); put("progress", fraction); put("total", 1); put("message", message.take(300)) })
        })
    }
}
internal class InputRequired(val key: String, val question: String, val options: List<String>) : RuntimeException()
