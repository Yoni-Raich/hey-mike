package dev.androidagent.enginecodex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Accept trailing commas on the wire, without changing tool argument parsing. */
internal object CodexTransportJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(line: String): JsonObject {
        try { return json.parseToJsonElement(line).jsonObject }
        catch (error: SerializationException) {
            // The pinned serialization library has no allowTrailingCommas option.
            // Only commas outside strings and immediately before ] or } may go.
            val repaired = StringBuilder(line.length)
            var quoted = false
            var escaped = false
            var changed = false
            for (index in line.indices) {
                val c = line[index]
                if (quoted) {
                    repaired.append(c)
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') quoted = false
                } else if (c == '"') { quoted = true; repaired.append(c) }
                else if (c == ',') {
                    var next = index + 1
                    while (next < line.length && line[next] in " \t\r\n") next++
                    if (next < line.length && line[next] in "]}") changed = true else repaired.append(c)
                } else repaired.append(c)
            }
            if (!changed) throw error
            // All other malformed syntax still fails closed in the strict parser.
            return json.parseToJsonElement(repaired.toString()).jsonObject
        }
    }
}
