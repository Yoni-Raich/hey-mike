/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.workspace

import dev.androidagent.core.ResponsibilityService
import dev.androidagent.core.ResponsibilitySnapshot
import dev.androidagent.core.ResponsibilityStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface ResponsibilitySeal {
    fun hasKey(): Boolean
    fun sign(payload: String): String
    fun verify(payload: String, signature: String): Boolean
}

/**
 * One bounded, atomic document outside sessions and the runtime home.
 * No partial-write fallback: a failed replacement must leave the old state intact.
 * Bad data is an error, never an empty store that releases a paused rule.
 */
class FileResponsibilityStore(private val file: File, private val seal: ResponsibilitySeal? = null) : ResponsibilityStore {
    @Synchronized override fun load(): ResponsibilitySnapshot {
        if (!file.exists()) {
            check(seal?.hasKey() != true) { "Signed responsibility state is missing." }
            return ResponsibilitySnapshot()
        }
        require(file.isFile && file.length() <= MAX_BYTES) { "Responsibility store is unreadable or too large." }
        val stored = file.readText(Charsets.UTF_8)
        val payload = if (seal == null) stored else {
            val envelope = Json.parseToJsonElement(stored) as? JsonObject ?: error("Unsigned responsibility state.")
            require(envelope.keys == setOf("payload", "signature")) { "Invalid signed responsibility state." }
            val body = envelope["payload"] as? JsonPrimitive
            val signature = envelope["signature"] as? JsonPrimitive
            require(body?.isString == true && signature?.isString == true) { "Unsigned responsibility state." }
            check(seal.verify(body.content, signature.content)) { "Responsibility signature does not match." }
            body.content
        }
        return Json.decodeFromString<ResponsibilitySnapshot>(payload).also(ResponsibilityService::validate)
    }

    @Synchronized override fun save(snapshot: ResponsibilitySnapshot) {
        ResponsibilityService.validate(snapshot)
        val payload = Json.encodeToString(snapshot)
        val stored = if (seal == null) payload else buildJsonObject {
            put("payload", payload); put("signature", seal.sign(payload))
        }.toString()
        val bytes = stored.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Responsibility store is too large." }
        val parent = file.absoluteFile.parentFile
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create the responsibility directory." }
        val temporary = File(parent, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
