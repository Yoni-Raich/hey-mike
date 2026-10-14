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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * A question the agent put to the user and is waiting on.
 *
 * [options] may be empty: then the answer is whatever the user types. With
 * options the user can still type something else.
 */
data class UserQuestion(val id: String, val question: String, val options: List<String> = emptyList()) {
    /**
     * What a typed reply means. A reply that is only an option's number, as
     * the notification lists them, is that option; anything else is the
     * user's own words. Null when there is nothing to send.
     */
    fun resolve(reply: String): String? {
        val text = reply.trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { number -> options.getOrNull(number - 1)?.let { return it } }
        return options.firstOrNull { it.equals(text, ignoreCase = true) } ?: text
    }
}

/**
 * The tools a chat serves itself: questions, media and its provisional title.
 *
 * They put something in the conversation and never touch the phone's screen,
 * so [AgentCoordinator] answers them without taking the phone from another
 * chat. A question can wait minutes for its answer, and a chat holding the
 * phone that long would block every other chat's tools.
 */
object ChatTools {
    const val ASK = "ask_user"
    const val SHOW = "show_media"
    const val TITLE = "set_chat_title"
    val NAMES = setOf(ASK, SHOW, TITLE, SessionAgentTools.NAME)

    const val MAX_OPTIONS = 6
    const val MAX_MEDIA = 10
    private const val MAX_QUESTION = 1_000
    private const val MAX_OPTION = 120

    /** How long a question waits. Below Claude's 10 minute tool limit, so the answer has a call to go back to. */
    const val ASK_TIMEOUT_MS = 8L * 60 * 1_000

    val DEFINITIONS: List<ToolDefinition> = SessionAgentTools.DEFINITIONS + listOf(
        ToolDefinition(
            name = TITLE,
            description = "Give a new chat a short topic name once you understand the user's request. Use 3 to 7 words " +
                "in the user's language, up to 80 characters. Name the task, not Mike, the runtime context or a folder. " +
                "This also saves the name in Codex on the computer. Already chosen or manually renamed titles are kept. " +
                "Do this during the normal task; do not start another conversation or ask the user for a name.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("title", buildJsonObject { put("type", "string"); put("maxLength", ChatTitles.MAX_LENGTH) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("title"))))
                put("additionalProperties", false)
            },
        ),
        ToolDefinition(
            name = ASK,
            description = "Ask the user one question and wait for the answer. Use it when you cannot go on without a " +
                "choice or a fact only the user has; do not use it to confirm what they already asked for. Give " +
                "options (2 to $MAX_OPTIONS short labels) when the answer is a choice: they become buttons, and the " +
                "user can still type something else. Leave options out for a free-text answer. If the user is not " +
                "in the app they get a notification and answer from it. Returns the answer, or says nobody answered.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("question", buildJsonObject { put("type", "string"); put("description", "The question, in the user's language.") })
                    put("options", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Answers to pick from, each a few words. Omit for free text.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("question"))))
            },
        ),
        ToolDefinition(
            name = SHOW,
            description = "Show pictures or videos to the user in the chat. files are addresses as copy_file takes " +
                "them: chat:<path>, phone:<path> or a content:// uri, <computer>:<path>, or a bare path where your " +
                "shell runs. A file that is not in the chat's folder is copied there first. Up to $MAX_MEDIA files.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("files", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "The pictures and videos to show, as addresses.")
                    })
                    put("caption", buildJsonObject { put("type", "string"); put("description", "A line shown with them.") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("files"))))
            },
        ),
    )

    private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val VIDEO_TYPES = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")

    fun isImage(path: String): Boolean = extension(path) in IMAGE_TYPES
    fun isVideo(path: String): Boolean = extension(path) in VIDEO_TYPES
    fun isMedia(path: String): Boolean = isImage(path) || isVideo(path)

    /** [address] ends in a file type, and it is not a picture's or a video's. A content uri names none. */
    fun namesOtherKind(address: String): Boolean =
        !address.startsWith("content://") && extension(address).let { it.isNotEmpty() && !isMedia(address) }

    /** The name to show for an attachment: a local file's, or the file a [RemoteMediaRef] names. */
    fun displayName(attachment: String): String =
        RemoteMediaRef.parse(attachment)?.name ?: attachment.substringAfterLast('/').substringAfterLast('\\')

    private fun extension(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    /** The question in [arguments], or the reason there is none. */
    fun question(id: String, arguments: JsonObject): Result<UserQuestion> = runCatching {
        val text = (arguments["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(text.isNotEmpty()) { "question is empty. Say what you are asking." }
        val options = (arguments["options"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        require(options.size <= MAX_OPTIONS) { "At most $MAX_OPTIONS options. Ask a narrower question, or leave options out." }
        UserQuestion(id, text.take(MAX_QUESTION), options.map { it.take(MAX_OPTION) })
    }

    /** What `show_media` was asked to show. */
    data class Show(val files: List<String>, val caption: String)

    fun show(arguments: JsonObject): Result<Show> = runCatching {
        val files = (arguments["files"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        require(files.isNotEmpty()) { "files is empty. Name at least one picture or video." }
        require(files.size <= MAX_MEDIA) { "At most $MAX_MEDIA files in one call." }
        Show(files, (arguments["caption"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
    }

    fun answered(answer: String): ToolResult = ToolResult(
        buildJsonObject { put("ok", true); put("answer", answer) }.toString(),
    )

    /** [failed] are the files left out, each with why. */
    fun shown(names: List<String>, failed: List<String> = emptyList(), remote: List<String> = emptyList()): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", true)
            put("shown", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
            if (remote.isNotEmpty()) {
                put("notCopied", buildJsonArray { remote.forEach { add(JsonPrimitive(it)) } })
                put(
                    "note",
                    "Those stay on their computer: the chat loads each when the user looks at it, and offers a button to save it on the phone.",
                )
            }
            if (failed.isNotEmpty()) put("notShown", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } })
        }.toString(),
    )

    fun refused(type: String, message: String): ToolResult = ToolResult(
        buildJsonObject { put("ok", false); put("errorType", type); put("message", message) }.toString(),
        success = false,
    )
}

/**
 * Puts [ChatTools] in the tool list every engine is given.
 *
 * The list is what the composite gateway advertises, so this is how the
 * names reach a thread. The calls themselves are answered by the chat's
 * [AgentCoordinator] before they get here; one that does arrive came from
 * outside a chat turn (a workflow, a rule), where there is no chat to show
 * anything in.
 */
class ChatToolGateway : DeviceToolGateway {
    override val definitions: List<ToolDefinition> = ChatTools.DEFINITIONS
    override fun beginRun(runId: String, workspace: File) = Unit
    override fun revoke() = Unit
    override fun needsControl(name: String): Boolean = false
    override fun deviceBackendLive(): Boolean = false
    override suspend fun cancel() = Unit
    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult =
        ChatTools.refused("no_chat", "$name works only as a direct call in a chat turn.")
}
