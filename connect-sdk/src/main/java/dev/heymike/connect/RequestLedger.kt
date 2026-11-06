// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect

import java.io.File
import kotlinx.serialization.json.*

/** Durable reservation before executing a write. A crash leaves an unknown outcome, never a replay. */
@android.annotation.TargetApi(26) // Only used by the Android 11+ bound service.
class RequestLedger(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(caller: String, requestId: String) = File(directory, ConnectProtocol.fingerprint(JsonPrimitive(caller + ":" + requestId)) + ".json")
    @Synchronized fun reserve(caller: String, requestId: String, fingerprint: String): JsonObject? {
        require(requestId.length in 1..120)
        directory.mkdirs()
        val target = file(caller, requestId)
        if (target.exists()) {
            val entry = runCatching { ConnectProtocol.parse(target.readText()) }.getOrElse {
                return ConnectProtocol.failure("outcome_unknown", "Saved result is unreadable. Verify state before another action.", true)
            }
            if (entry.string("fingerprint") != fingerprint) return ConnectProtocol.failure("request_id_conflict", "Request id already belongs to different arguments")
            return entry["result"] as? JsonObject ?: ConnectProtocol.failure("outcome_unknown", "An earlier write may have run. Read current state; do not repeat it.", true)
        }
        // Unresolved reservations are not evicted: prefer a busy result over replaying an unknown action.
        val entries = directory.listFiles { f -> f.extension == "json" }.orEmpty()
        entries.filter { f ->
            runCatching { val e = ConnectProtocol.parse(f.readText()); e["result"] != null && now() - (e["time"]?.jsonPrimitive?.longOrNull ?: now()) > 600_000 }.getOrDefault(false)
        }.forEach { it.delete() }
        if (directory.listFiles().orEmpty().size >= 256) return ConnectProtocol.failure("busy", "Write result ledger is full; resolve pending operations first")
        store(target, fingerprint, null)
        return null
    }
    @Synchronized fun finish(caller: String, requestId: String, fingerprint: String, result: JsonObject) = store(file(caller, requestId), fingerprint, result)
    private fun store(target: File, fingerprint: String, result: JsonObject?) {
        val body = buildJsonObject { put("fingerprint", fingerprint); put("time", now()); result?.let { put("result", it) } }.toString()
        val part = File(target.parentFile, target.name + ".part")
        java.io.FileOutputStream(part).use { it.write(body.toByteArray()); it.fd.sync() }
        java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
