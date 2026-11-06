// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect.testing

import dev.heymike.connect.*
import kotlinx.serialization.json.*

/** Supply a service created by Robolectric or an instrumentation context. No grants or IPC are bypassed in the app. */
class ConnectTestHost(private val service: MikeConnectService) {
    suspend fun call(name: String, arguments: JsonObject): JsonObject =
        service.testCall(name, arguments)["result"]?.jsonObject ?: error("Provider returned a protocol error")
}
object ConnectConformance {
    fun verify(capabilities: List<Capability>) {
        require(capabilities.size in 1..64)
        require(capabilities.map { it.name }.distinct().size == capabilities.size)
        capabilities.forEach { it.validate(); require(Capability.fromWire(it.wire()) == it) { "Capability wire round trip changed policy" } }
    }
}
