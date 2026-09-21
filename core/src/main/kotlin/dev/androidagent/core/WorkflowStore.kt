/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * Saved step sequences, kept beside the knowledge store and for the same
 * reason: a sequence worked out in one chat is invisible to the next unless it
 * lives somewhere the seeder does not rewrite.
 *
 * One file per package, mirroring [KnowledgeStore], so a package's selectors
 * and its workflows age out together when the app is redesigned.
 */
class WorkflowStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Workflow(
        val name: String,
        val packageName: String,
        val description: String,
        /** Raw step objects, validated by [WorkflowEngine] when they run. */
        val steps: JsonArray,
        val lastSaved: Long = 0L,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("steps", steps)
            put("lastSaved", lastSaved)
        }
    }

    fun forPackage(packageName: String): List<Workflow> {
        val file = fileFor(packageName) ?: return emptyList()
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.length > MAX_FILE_CHARS) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val entries = runCatching { parsed["workflows"]!!.jsonArray }.getOrNull() ?: return emptyList()
        return entries.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                Workflow(
                    name = obj["name"]!!.jsonPrimitive.content,
                    packageName = packageName,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    steps = obj["steps"]!!.jsonArray,
                    lastSaved = obj["lastSaved"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        }.sortedByDescending { it.lastSaved }
    }

    fun all(): List<Workflow> = packages().flatMap { forPackage(it) }.sortedByDescending { it.lastSaved }

    fun packages(): List<String> =
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.sorted()
            .orEmpty()

    /** Replaces by name within the package, so re-saving refines rather than duplicates. */
    fun save(workflow: Workflow): Workflow {
        val file = requireNotNull(fileFor(workflow.packageName)) {
            "\"${workflow.packageName}\" is not a valid Android package name"
        }
        require(workflow.name.isNotBlank()) { "name is required" }
        require(workflow.name.length <= MAX_NAME_CHARS) { "name is too long" }
        require(workflow.description.length <= MAX_DESCRIPTION_CHARS) { "description is too long" }
        require(workflow.steps.isNotEmpty()) { "a workflow needs at least one step" }
        require(workflow.steps.size <= WorkflowEngine.MAX_STEPS) {
            "a workflow is limited to ${WorkflowEngine.MAX_STEPS} steps"
        }
        val stamped = workflow.copy(lastSaved = now())
        val merged = (forPackage(workflow.packageName).filterNot { it.name == stamped.name } + stamped)
            .sortedByDescending { it.lastSaved }
            .take(MAX_PER_PACKAGE)
        file.parentFile?.mkdirs()
        val payload = buildJsonObject {
            put("package", workflow.packageName)
            put("version", FORMAT_VERSION)
            put("workflows", JsonArray(merged.map { it.toJson() }))
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(payload.toString())
        if (!temp.renameTo(file)) {
            file.writeText(payload.toString())
            temp.delete()
        }
        return stamped
    }

    private fun fileFor(packageName: String): File? {
        val trimmed = packageName.trim()
        if (!PACKAGE_RE.matches(trimmed)) return null
        return File(root, trimmed.lowercase(Locale.ROOT) + SUFFIX)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val DIRECTORY = "workflows"
        const val MAX_PER_PACKAGE = 40

        private const val SUFFIX = ".json"
        private const val MAX_NAME_CHARS = 120
        private const val MAX_DESCRIPTION_CHARS = 400
        private const val MAX_FILE_CHARS = 512 * 1024

        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        fun directoryIn(homeDirectory: File): File = File(homeDirectory, DIRECTORY)
    }
}
