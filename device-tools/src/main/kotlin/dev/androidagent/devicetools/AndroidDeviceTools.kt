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

package dev.androidagent.devicetools

import dev.androidagent.adb.AdbFileTransport
import dev.androidagent.core.AdbTransport
import dev.androidagent.core.CommandResult
import dev.androidagent.core.ConnectionPhase
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.ACT_AND_OBSERVE_ACTIONS
import dev.androidagent.core.ACT_AND_OBSERVE_DEFINITION
import dev.androidagent.core.READ_UI_DESCRIPTION
import dev.androidagent.core.ToolNotServiceable
import dev.androidagent.core.ObservationFingerprint
import dev.androidagent.core.ObservationState
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.core.UiNode
import dev.androidagent.core.UiObservation
import dev.androidagent.core.UiObservationSerializer
import dev.androidagent.core.UiQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.StringReader
import java.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Sole agent-facing device gateway. Every device operation goes through [adb].
 *
 * Run scoping: [beginRun] arms the gateway for one run id plus workspace.
 * [revoke] is synchronous and flips a volatile flag; any [invoke] dispatched
 * afterwards fails without touching the device. In-flight calls are stopped
 * via [cancel], which the coordinator calls after [revoke].
 *
 * Mutating tools (taps, text, keys, app launch, shell, push, install) report
 * [needsControl] true so the overlay glow is shown. Read-only tools
 * (device_status, read_ui, screenshot, pull_file) return false.
 */
class AndroidDeviceTools(
    private val adb: AdbTransport,
    /** Full IME component, for example `com.example/.AgentInputMethodService`. */
    private val inputMethodComponent: String? = null,
    /** Shared with every other gateway so revisions never move backwards. */
    private val observations: ObservationState = ObservationState(),
    /** Moves the floating card out of the way before a gesture lands on it. */
    private val avoidTouch: (Int, Int) -> Unit = { _, _ -> },
    private val observationVisibility: suspend (Boolean) -> Unit = {},
) : DeviceToolGateway {

    private val lock = Any()
    @Volatile private var revoked = true
    @Volatile private var workspace: File? = null
    @Volatile private var runId: String? = null
    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        synchronized(lock) {
            this.runId = runId
            this.workspace = workspace.absoluteFile
            workspace.absoluteFile.mkdirs()
            observations.reset()
            revoked = false
        }
    }

    override fun revoke() {
        synchronized(lock) { revoked = true }
    }

    override fun needsControl(name: String): Boolean =
        when (name) {
            "device_status", "read_ui", "screenshot", "pull_file" -> false
            "tap", "swipe", "type_text", "key", "open_app", "shell",
            "push_file", "install_apk" -> true
            else -> true
        }

    override fun hidesOverlayDuringCapture(name: String): Boolean =
        // Both capture the composited screen, so the overlay must step aside.
        name == "read_ui" || name == "screenshot"

    override fun statusLine(): String? {
        // Worded as an optional extra: the model reads this line every turn,
        // and a bare "ADB: DISCONNECTED" reads as "the phone is unreachable".
        val s = adb.status.value
        return "Wireless ADB (optional): ${s.phase.name.lowercase()} - ${s.message}"
    }

    /**
     * Nothing here works without the transport, so a disconnected ADB serves
     * no tool at all. The advertised list is untouched: a call still fails
     * with its own typed error, this only tells the turn snapshot the truth.
     */
    override fun readyTools(): Set<String> =
        if (adb.status.value.phase == ConnectionPhase.CONNECTED) {
            definitions.map { it.name }.toSet()
        } else {
            emptySet()
        }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        val ws = workspace
        if (revoked || ws == null) {
            throw IllegalStateException("Run stopped. No device action was performed.")
        }
        checkActive()
        if (name != "device_status" && adb.status.value.phase != ConnectionPhase.CONNECTED) {
            // Typed, and before any device call, so the composite reports the
            // accessibility backend's own reason instead of a bare transport
            // error that reads as "this task needs ADB".
            throw ToolNotServiceable(
                "adb_not_connected",
                "Wireless ADB is an optional advanced backend and is not connected.",
            )
        }
        val result = when (name) {
            "device_status" -> deviceStatus()
            "read_ui" -> readUi(arguments)
            "screenshot" -> screenshot(arguments)
            "act_and_observe" -> actAndObserve(arguments)
            "tap" -> tap(arguments)
            "swipe" -> swipe(arguments)
            "type_text" -> typeText(arguments)
            "key" -> pressKey(arguments)
            "open_app" -> openApp(arguments)
            "shell" -> shell(arguments)
            "pull_file" -> pullFile(arguments, ws)
            "push_file" -> pushFile(arguments, ws)
            "install_apk" -> installApk(arguments, ws)
            else -> ToolResult("Unknown tool: $name", success = false)
        }
        checkActive()
        return result
    }

    override suspend fun cancel() {
        runCatching { adb.cancelActive() }
    }

    // ---- tools ----

    private suspend fun actAndObserve(arguments: JsonObject): ToolResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull
        require(action in ACT_AND_OBSERVE_ACTIONS) { "Choose one supported action." }
        val args = arguments["arguments"] as? JsonObject ?: error("Action arguments are required.")
        val result = invoke(action!!, args)
        if (!result.success) return result // Never retry a side effect or observe after a failed commit.
        checkActive()
        val observation = try {
            observationVisibility(true)
            readUi(buildJsonObject {})
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolResult("Observation failed: ${failure.message}. Do not repeat the completed action.", success = false)
        } finally { withContext(NonCancellable) { observationVisibility(false) } }
        return ToolResult(buildJsonObject {
            put("actionCompleted", true)
            put("actionResult", result.text)
            put("observationSucceeded", observation.success)
            put("observation", runCatching { Json.parseToJsonElement(observation.text) }.getOrElse { JsonPrimitive(observation.text) })
        }.toString(), success = observation.success)
    }

    private fun deviceStatus(): ToolResult {
        val s = adb.status.value
        val port = s.port?.toString() ?: "-"
        return ToolResult("phase=${s.phase} port=$port ${s.message}".take(MAX_OUTPUT_CHARS))
    }

    private suspend fun readUi(arguments: JsonObject): ToolResult {
        val timeout = arguments.timeoutMsOrDefault(READ_UI_DEFAULT_TIMEOUT_MS)
        val raw = arguments["raw"]?.jsonPrimitive?.booleanOrNull ?: false
        val force = arguments["force"]?.jsonPrimitive?.booleanOrNull ?: false
        val query = UiQuery.from(arguments)
        val revision = observations.nextRevision()
        val observationId = "ui-$revision"
        val startedAt = System.nanoTime()

        return try {
            val result = withTimeout(timeout) {
                readUiWithinBudget(raw, force, query, revision, observationId, startedAt, timeout)
            }
            // A failed observation means the screen is unknown, so the next
            // successful one must carry a full payload rather than a diff.
            if (!result.success) observations.reset()
            result
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            observations.reset()
            uiFailure(
                observationId = observationId,
                revision = revision,
                startedAt = startedAt,
                errorType = "ui_timeout",
                message = "UI hierarchy timed out before a stable dump completed; try screenshot or retry",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            observations.reset()
            uiFailure(
                observationId = observationId,
                revision = revision,
                startedAt = startedAt,
                errorType = "ui_dump_failure",
                message = error.message ?: "UI hierarchy dump failed",
            )
        }
    }

    private suspend fun readUiWithinBudget(
        raw: Boolean,
        force: Boolean,
        query: UiQuery,
        revision: Long,
        observationId: String,
        startedAt: Long,
        totalBudgetMs: Long,
    ): ToolResult {
        // Staging the dump to a file and reading it back is the only path that
        // returns a hierarchy across shell transports. Writing the dump to
        // /dev/tty (or /proc/self/fd/1) makes uiautomator report success and
        // exit 0 while emitting no hierarchy unless the shell service happens to
        // forward raw stdout, so that shortcut is never attempted: on the
        // supported device it cost ~2.2s per call and always returned nothing.
        val attempt = runUiDumpCommand(
            uiDumpCommand(UI_DUMP_PATH),
            remainingBudget(startedAt, totalBudgetMs),
        )
        val completed = when (attempt) {
            is UiDumpAttempt.TimedOut -> return uiFailure(
                observationId, revision, startedAt, "ui_timeout",
                "UI hierarchy dump timed out within the total budget",
            )
            is UiDumpAttempt.Completed -> attempt
        }

        val dump = completed.result
        if (isIdleFailure(dump.output)) {
            return uiFailure(
                observationId, revision, startedAt, "ui_idle_failure",
                "UI Automator could not get idle state; no additional dump was attempted",
            )
        }
        val xml = extractHierarchyXml(dump.output)
            ?: return uiFailure(
                observationId, revision, startedAt, "ui_dump_failure",
                "UI hierarchy dump returned no hierarchy XML",
            )
        if (dump.exitCode != 0) {
            return uiFailure(
                observationId, revision, startedAt, "ui_dump_failure",
                "UI hierarchy dump failed with exit ${dump.exitCode}",
            )
        }
        return completeUiObservation(
            xml, "file", raw, force, query, observationId, revision, startedAt,
        )
    }

    private suspend fun runUiDumpCommand(command: String, timeoutMs: Long): UiDumpAttempt {
        if (timeoutMs <= 0L) return UiDumpAttempt.TimedOut(0L)
        val startedAt = System.nanoTime()
        return try {
            UiDumpAttempt.Completed(
                userExecute(command, timeoutMs),
                elapsedMs(startedAt),
            )
        } catch (error: TimeoutCancellationException) {
            UiDumpAttempt.TimedOut(elapsedMs(startedAt))
        }
    }

    private fun completeUiObservation(
        xml: String,
        source: String,
        raw: Boolean,
        force: Boolean,
        query: UiQuery,
        observationId: String,
        revision: Long,
        startedAt: Long,
    ): ToolResult {
        // raw=true is an explicit compatibility/debug path. It does not claim
        // that semantic parsing succeeded, and it never participates in
        // unchanged-screen suppression.
        if (raw) return ToolResult(bound(xml))
        return try {
            val parsed = parseUiHierarchy(xml)
            val rendered = UiObservationSerializer.render(
                observation = parsed,
                source = source,
                backend = BACKEND,
                observationId = observationId,
                revision = revision,
                elapsedMs = elapsedMs(startedAt),
                previous = observations.last(),
                force = force,
                // uiautomator only answers once the window is already idle.
                stable = true,
                query = query,
            )
            rendered.fingerprint?.let { observations.record(it) }
            ToolResult(bound(rendered.text), success = rendered.ok)
        } catch (error: Exception) {
            uiFailure(
                observationId, revision, startedAt, "ui_parse_failure",
                "UI hierarchy XML could not be parsed safely: ${error.message ?: "invalid XML"}",
            )
        }
    }

    private fun parseUiHierarchy(xml: String): UiObservation {
        require(xml.length <= MAX_UI_XML_CHARS) { "hierarchy XML is too large" }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) { "ENTITY declarations are not allowed" }

        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            setInput(StringReader(xml))
        }
        val ancestors = ArrayDeque<UiNode>()
        val meaningful = mutableListOf<UiNode>()
        val packages = mutableMapOf<String, Int>()
        var nodeCount = 0
        var sawHierarchy = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "hierarchy" -> {
                        require(!sawHierarchy) { "multiple hierarchy roots" }
                        sawHierarchy = true
                    }
                    "node" -> {
                        require(sawHierarchy) { "node appears before hierarchy root" }
                        require(parser.depth <= MAX_UI_XML_DEPTH) { "hierarchy is too deep" }
                        require(nodeCount < MAX_UI_NODES) { "hierarchy has too many nodes" }
                        val node = UiNode(
                            nodeId = "n${nodeCount++}",
                            text = parser.attribute("text").compactUiText(),
                            contentDescription = parser.attribute("content-desc").compactUiText(),
                            resourceId = parser.attribute("resource-id").compactUiText(),
                            className = parser.attribute("class").compactUiText(),
                            bounds = parseBounds(parser.attribute("bounds")),
                            enabled = parser.attribute("enabled")?.toBooleanStrictOrNull() ?: true,
                            clickable = parser.attribute("clickable")?.toBooleanStrictOrNull() ?: false,
                            scrollable = parser.attribute("scrollable")?.toBooleanStrictOrNull() ?: false,
                            focused = parser.attribute("focused")?.toBooleanStrictOrNull() ?: false,
                            packageName = parser.attribute("package").compactUiText(),
                            password = parser.attribute("password")?.toBooleanStrictOrNull() ?: false,
                            checkable = parser.attribute("checkable")?.toBooleanStrictOrNull() ?: false,
                            checked = parser.attribute("checked")?.toBooleanStrictOrNull() ?: false,
                            clickableAncestor = ancestors.lastOrNull { it.clickable }?.asClickTarget(),
                            // The nearest ancestor that will itself be emitted:
                            // a subtree query has to resolve from the flat list.
                            parentId = ancestors.lastOrNull { it.isMeaningful() }?.nodeId,
                        )
                        node.packageName?.let { packages[it] = (packages[it] ?: 0) + 1 }
                        if (node.isMeaningful()) meaningful += node
                        ancestors.addLast(node)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "node" && ancestors.isNotEmpty()) {
                    ancestors.removeLast()
                }
            }
            event = parser.next()
        }
        require(sawHierarchy) { "missing hierarchy root" }
        require(ancestors.isEmpty()) { "unclosed node elements" }
        return UiObservation(
            activePackage = packages.maxByOrNull { it.value }?.key,
            nodes = meaningful,
        )
    }

    private fun uiFailure(
        observationId: String,
        revision: Long,
        startedAt: Long,
        errorType: String,
        message: String,
    ): ToolResult = ToolResult(
        UiObservationSerializer.failureJson(
            observationId = observationId,
            revision = revision,
            elapsedMs = elapsedMs(startedAt),
            errorType = errorType,
            message = message,
        ),
        success = false,
    )

    private fun extractHierarchyXml(output: String): String? {
        val start = output.indexOf("<hierarchy")
        if (start < 0) return null
        val end = output.indexOf("</hierarchy>", start)
        if (end < 0) return null
        return output.substring(start, end + "</hierarchy>".length)
    }

    private fun isIdleFailure(output: String): Boolean =
        output.contains("could not get idle state", ignoreCase = true)

    private fun remainingBudget(startedAt: Long, totalBudgetMs: Long): Long =
        (totalBudgetMs - elapsedMs(startedAt)).coerceAtLeast(0L)

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private sealed interface UiDumpAttempt {
        data class Completed(val result: CommandResult, val elapsedMs: Long) : UiDumpAttempt
        data class TimedOut(val elapsedMs: Long) : UiDumpAttempt
    }

    private fun XmlPullParser.attribute(name: String): String? = getAttributeValue(null, name)

    private fun String?.compactUiText(): String? = UiObservationSerializer.compactField(this)

    private fun parseBounds(value: String?): List<Int>? {
        val match = BOUNDS_RE.matchEntire(value?.trim().orEmpty()) ?: return null
        return listOf(
            match.groupValues[1].toIntOrNull() ?: return null,
            match.groupValues[2].toIntOrNull() ?: return null,
            match.groupValues[3].toIntOrNull() ?: return null,
            match.groupValues[4].toIntOrNull() ?: return null,
        )
    }

    private suspend fun screenshot(arguments: JsonObject): ToolResult {
        val timeout = arguments.timeoutMsOrDefault().coerceIn(1L, MAX_TIMEOUT_MS)
        // screencap photographs every window, ours included. The coordinator
        // only steps the card aside when this backend is asked first, not after
        // a fall-through from accessibility, so step it aside here as well.
        observationVisibility(true)
        val png = try {
            screencapPng(timeout)
        } finally {
            withContext(NonCancellable) { observationVisibility(false) }
        }
        val encoded = Base64.getEncoder().encodeToString(png)
        checkActive()
        val image = File(checkNotNull(workspace), "screenshots/${java.util.UUID.randomUUID()}.png")
        image.parentFile!!.mkdirs()
        image.writeBytes(png)
        return ToolResult("Screenshot captured (${png.size} bytes, PNG)", imageBase64 = encoded, attachmentPaths = listOf(image.absolutePath))
    }

    private suspend fun screencapPng(timeout: Long): ByteArray {
        val bytes = runCatching { userExecuteBytes("screencap -p", timeout) }.getOrNull()
        val png = if (bytes != null && isPng(bytes)) {
            bytes
        } else {
            // Text-only transports: recover binary through a base64 round-trip.
            val textResult = userExecute("screencap -p | base64 | tr -d '\\r\\n'", timeout)
            check(textResult.exitCode == 0) { "Screenshot command failed" }
            val out = textResult.output
            val clean = out.filterNot { it.isWhitespace() }
            if (clean.isEmpty()) throw java.io.IOException("Screenshot returned no data")
            try {
                Base64.getMimeDecoder().decode(clean)
            } catch (e: IllegalArgumentException) {
                throw java.io.IOException("Screenshot decode failed", e)
            }.also {
                check(isPng(it)) { "Screenshot did not return PNG data" }
            }
        }
        check(png.size <= MAX_SCREENSHOT_BYTES) { "Screenshot exceeds size limit" }
        return png
    }

    private suspend fun tap(arguments: JsonObject): ToolResult {
        val x = arguments.requireCoordinate("x")
        val y = arguments.requireCoordinate("y")
        // A coordinate tap hits whatever is topmost, including our own card.
        avoidTouch(x, y)
        val timeout = arguments.timeoutMsOrDefault()
        val out = userExecute("input tap $x $y", timeout)
        return ToolResult(
            text = bound("Tapped $x,$y${out.output.ifBlank { "" }.prefix(" :: ")}"),
            success = out.exitCode == 0,
        )
    }

    private suspend fun swipe(arguments: JsonObject): ToolResult {
        val x1 = arguments.requireCoordinate("x1")
        val y1 = arguments.requireCoordinate("y1")
        val x2 = arguments.requireCoordinate("x2")
        val y2 = arguments.requireCoordinate("y2")
        val duration = arguments.get("durationMs")?.jsonPrimitive?.intOrNull ?: 300
        require(duration in 0..5_000) { "durationMs must be between 0 and 5000" }
        avoidTouch(x1, y1)
        val timeout = arguments.timeoutMsOrDefault()
        val out = userExecute("input swipe $x1 $y1 $x2 $y2 $duration", timeout)
        return ToolResult(
            text = bound("Swiped ($x1,$y1)->($x2,$y2) ${duration}ms${out.output.ifBlank { "" }.prefix(" :: ")}"),
            success = out.exitCode == 0,
        )
    }

    private suspend fun typeText(arguments: JsonObject): ToolResult {
        val text = arguments.get("text")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("text is required")
        val submit = arguments.get("submit")?.jsonPrimitive?.booleanOrNull ?: false
        val timeout = arguments.timeoutMsOrDefault()
        val component = inputMethodComponent
        if (component != null) {
            return typeTextThroughIme(text, submit, timeout, component)
        }

        requireAdbInputText(text)
        // adb input text interprets %s as a space. Keep the whole token quoted
        // and reject literal percent signs rather than sending altered text.
        require(!text.contains('%')) {
            "Literal percent input requires the configured Unicode IME bridge; text was not sent."
        }
        val encoded = text.replace(" ", "%s")
        checkActive()
        val result = userExecute("input text ${shellQuote(encoded)}", timeout)
        check(result.exitCode == 0) { "Text input command failed; text was not confirmed" }
        if (submit) {
            checkActive()
            val submitResult = userExecute("input keyevent 66", timeout)
            check(submitResult.exitCode == 0) { "Enter key command failed after text input" }
        }
        return ToolResult(bound("Typed ${text.length} chars" + if (submit) " + Enter" else ""))
    }

    /**
     * Commits UTF-8 text through the app IME. Every command up to the commit is
     * a new user action and checks revocation. Restoring the previous IME is a
     * cleanup action and is allowed from NonCancellable finally code after a
     * stop request.
     */
    private suspend fun typeTextThroughIme(
        text: String,
        submit: Boolean,
        timeout: Long,
        component: String,
    ): ToolResult {
        requireValidImeComponent(component)
        validateImeText(text)
        val previous = queryDefaultIme(timeout)
        var switched = false
        try {
            checkActive()
            val enabled = userExecute("ime enable ${shellQuote(component)}", timeout)
            check(enabled.exitCode == 0) {
                "Unicode input IME could not be enabled; text was not sent"
            }

            checkActive()
            val selected = userExecute("ime set ${shellQuote(component)}", timeout)
            check(selected.exitCode == 0) {
                "Unicode input IME could not be selected; text was not sent"
            }
            switched = true

            checkActive()
            awaitImeReady(component, timeout)
            val payload = encodeImePayload(text)
            var committed = false
            var lastCode: Int? = null
            for (attempt in 0 until IME_COMMIT_ATTEMPTS) {
                checkActive()
                val broadcast = userExecute(buildImeBroadcastCommand(component, payload), timeout)
                lastCode = imeBroadcastResult(broadcast.output)
                if (broadcast.exitCode == 0 && lastCode == IME_RESULT_SUCCESS) {
                    committed = true
                    break
                }
                // Only explicit no-delivery/no-connection responses are safe to retry.
                // A missing acknowledgement can mean that text was already inserted.
                if (broadcast.exitCode != 0 || lastCode !in listOf(0, 4)) break
                if (attempt + 1 < IME_COMMIT_ATTEMPTS) delay(IME_COMMIT_RETRY_MS)
            }
            check(committed) {
                "Unicode input failed: ${imeFailureReason(lastCode)}. " +
                    "No Enter key was sent. Check the text field before retrying."
            }

            if (submit) {
                checkActive()
                val submitResult = userExecute("input keyevent 66", timeout)
                check(submitResult.exitCode == 0) { "Enter key command failed after text input" }
            }
            return ToolResult(bound("Typed ${text.length} chars" + if (submit) " + Enter" else ""))
        } finally {
            val restore = previous?.takeIf { switched && it != component }
            if (restore != null) {
                withContext(NonCancellable) {
                    // This is cleanup of the temporary IME selection, not a new
                    // user-requested device action. Do not mask the input result.
                    runCatching {
                        adb.execute("ime set ${shellQuote(restore)}", timeout)
                    }
                }
            }
        }
    }

    /** Ask the selected IME itself; dumpsys formats differ between Android versions. */
    private suspend fun awaitImeReady(component: String, timeout: Long) {
        var lastCode: Int? = null
        try {
            withTimeout(timeout.coerceAtMost(IME_READY_WAIT_MS).coerceAtLeast(IME_READY_POLL_MS)) {
                while (true) {
                    checkActive()
                    if (imeSelectionMatches(queryDefaultIme(IME_STATUS_TIMEOUT_MS), component)) {
                        val probe = userExecute(buildImeProbeCommand(component), IME_STATUS_TIMEOUT_MS)
                        lastCode = imeBroadcastResult(probe.output)
                        if (probe.exitCode == 0 && lastCode == IME_RESULT_SUCCESS) return@withTimeout
                        check(lastCode !in listOf(2, 3)) { "Unicode IME probe rejected: ${imeFailureReason(lastCode)}" }
                    }
                    delay(IME_READY_POLL_MS)
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(
                "Unicode IME not ready: ${imeFailureReason(lastCode)}. Focus the target text field and retry. No text was inserted.",
                error,
            )
        }
    }

    private suspend fun queryDefaultIme(timeout: Long): String? {
        checkActive()
        val result = userExecute("settings get secure default_input_method", timeout)
        check(result.exitCode == 0) {
            "Could not read the current input method; text was not sent"
        }
        return result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" && it != "none" }
            ?.also { requireValidImeComponent(it) }
    }

    private suspend fun pressKey(arguments: JsonObject): ToolResult {
        val raw = arguments.get("keycode")?.jsonPrimitive?.content
            ?: arguments.get("key")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("keycode is required")
        val code = resolveKeycode(raw)
        val timeout = arguments.timeoutMsOrDefault()
        val out = userExecute("input keyevent $code", timeout)
        return ToolResult(
            text = bound("Key $code sent${out.output.ifBlank { "" }.prefix(" :: ")}"),
            success = out.exitCode == 0,
        )
    }

    private suspend fun openApp(arguments: JsonObject): ToolResult {
        val pkg = arguments.get("package")?.jsonPrimitive?.content?.trim()
            ?: throw IllegalArgumentException("package is required")
        requireValidPackage(pkg)
        val activity = arguments.get("activity")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val timeout = arguments.timeoutMsOrDefault()
        val result = if (activity == null) {
            // Monkey is a fuzzing harness and changes device state on cleanup.
            // Resolve a normal launcher intent in the owner's profile instead.
            userExecute("am start --user 0 -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${shellQuote(pkg)}", timeout)
        } else {
            val component = if ('/' in activity) activity else "$pkg/$activity"
            requireValidComponent(component)
            require(component.substringBefore('/') == pkg) { "Activity must belong to the requested package." }
            userExecute("am start --user 0 -W -n ${shellQuote(component)}", timeout)
        }
        val opened = result.exitCode == 0 && !Regex("(?im)^(Error|Exception|SecurityException)").containsMatchIn(result.output)
        return ToolResult(
            text = bound("${if (opened) "Opened" else "Could not open"} $pkg${result.output.ifBlank { "" }.prefix(" :: ")}"),
            success = opened,
        )
    }

    private suspend fun shell(arguments: JsonObject): ToolResult {
        val command = arguments.get("command")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("command is required")
        require(command.isNotBlank()) { "command cannot be empty" }
        require(!command.contains('\u0000')) { "command contains NUL" }
        require(command.length <= MAX_SHELL_CHARS) { "command exceeds length limit" }
        // The direct command changes the phone's rotation setting on teardown.
        // read_ui uses a guarded dump and can use Accessibility when available.
        if (Regex("\\buiautomator\\s+dump\\b", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            return ToolResult("Use read_ui instead of shell uiautomator dump to preserve screen rotation.", success = false)
        }
        val timeout = arguments.timeoutMsOrDefault()
        val out = userExecute(command, timeout)
        val text = bound(out.output)
        return ToolResult(if (out.exitCode == 0) text.ifBlank { "OK" } else "exit ${out.exitCode}: $text", success = out.exitCode == 0)
    }

    private suspend fun pullFile(arguments: JsonObject, ws: File): ToolResult {
        val remote = arguments.get("remotePath")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("remotePath is required")
        requireValidRemotePath(remote)
        val localName = arguments.get("localName")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("localName is required")
        val target = ws.resolveSafe(localName)
        val timeout = arguments.timeoutMsOrDefault()
        val native = adb as? AdbFileTransport
        if (native != null) {
            withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
            val res = native.pullFile(target, remote, timeout)
            return ToolResult(
                text = bound("Pulled $remote -> ${ws.relativize(target)} (${target.length()} bytes) ${res.output}".trim()),
                success = res.exitCode == 0,
            )
        }
        // Fallback for text-only transports: base64 round-trip, bounded.
        val out = userExecute("base64 ${shellQuote(remote)} | tr -d '\\r\\n'", timeout).output
        val clean = out.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) throw java.io.IOException("Remote file is empty or unreadable: $remote")
        val bytes = try {
            Base64.getMimeDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            throw java.io.IOException("Pull decode failed", e)
        }
        check(bytes.size <= MAX_PULL_BYTES) { "Remote file exceeds size limit" }
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        return ToolResult("Pulled $remote -> ${ws.relativize(target)} (${bytes.size} bytes)")
    }

    private suspend fun pushFile(arguments: JsonObject, ws: File): ToolResult {
        val localName = arguments.get("localName")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("localName is required")
        val source = ws.resolveSafe(localName)
        check(source.isFile) { "Local file does not exist: $localName" }
        check(source.length() <= MAX_PUSH_BYTES) { "Local file exceeds size limit" }
        val remote = arguments.get("remotePath")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("remotePath is required")
        requireValidRemotePath(remote)
        val timeout = arguments.timeoutMsOrDefault()
        val native = adb as? AdbFileTransport
            ?: throw java.io.IOException("Push requires a native file transport")
        val res = native.pushFile(source, remote, timeout)
        return ToolResult(
            text = bound("Pushed ${ws.relativize(source)} -> $remote ${res.output}".trim()),
            success = res.exitCode == 0,
        )
    }

    private suspend fun installApk(arguments: JsonObject, ws: File): ToolResult {
        val localName = arguments.get("localName")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("localName is required")
        require(localName.endsWith(".apk", ignoreCase = true)) { "Not an APK: $localName" }
        val source = ws.resolveSafe(localName)
        check(source.isFile) { "APK does not exist: $localName" }
        check(source.length() <= MAX_APK_BYTES) { "APK exceeds size limit" }
        val replace = arguments.get("replace")?.jsonPrimitive?.booleanOrNull ?: true
        val timeout = arguments.timeoutMsOrDefault().coerceAtLeast(60_000L)
        val native = adb as? AdbFileTransport
            ?: throw java.io.IOException("Install requires a native file transport")
        val res = native.installApk(source, replace, timeout)
        return ToolResult(
            text = bound("Installed ${source.name} ${res.output}".trim()),
            success = res.exitCode == 0,
        )
    }

    // ---- helpers ----

    /** Dispatch a new agent action only while the current run is armed. */
    private suspend fun userExecute(command: String, timeoutMs: Long): CommandResult {
        checkActive()
        return adb.execute(command, timeoutMs)
    }

    /** Binary equivalent of [userExecute], preserving transport bytes. */
    private suspend fun userExecuteBytes(command: String, timeoutMs: Long): ByteArray {
        checkActive()
        return adb.executeBytes(command, timeoutMs)
    }

    private fun checkActive() {
        if (revoked) throw IllegalStateException("Run stopped. No device action was performed.")
    }

    private fun bound(text: String): String =
        if (text.length <= MAX_OUTPUT_CHARS) text
        else text.take(MAX_OUTPUT_CHARS) + "\n[output truncated]"

    private fun String.prefix(p: String): String = if (isEmpty()) "" else "$p$this"

    private fun JsonObject.timeoutMsOrDefault(default: Long = DEFAULT_TIMEOUT_MS): Long {
        val raw = get("timeoutMs")?.jsonPrimitive?.longOrNull ?: return default
        return raw.coerceIn(1L, MAX_TIMEOUT_MS)
    }

    private fun JsonObject.requireCoordinate(name: String): Int {
        val v = get(name)?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("$name is required (integer 0..$MAX_COORDINATE)")
        require(v in 0..MAX_COORDINATE) { "$name must be between 0 and $MAX_COORDINATE" }
        return v
    }

    private fun File.resolveSafe(relative: String): File {
        require(relative.isNotBlank()) { "Path cannot be blank" }
        require(!relative.contains('\u0000')) { "Path contains NUL" }
        // Reject absolute paths (posix and windows) and parent traversal.
        require(!relative.startsWith("/")) { "Absolute paths are not allowed: $relative" }
        require(!relative.matches(Regex("^[A-Za-z]:.*"))) { "Absolute paths are not allowed: $relative" }
        require(!relative.contains("\\")) { "Backslashes are not allowed: $relative" }
        val base = canonicalFile
        val target = File(base, relative).canonicalFile
        val prefix = base.path + File.separator
        check(target.path == base.path || target.path.startsWith(prefix)) {
            "Path traversal denied: $relative"
        }
        check(target.path != base.path) { "Path must name a file inside the workspace" }
        return target
    }

    private fun File.relativize(child: File): String =
        runCatching { relativeTo(canonicalFile).path }.getOrDefault(child.name)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_OUTPUT_CHARS = UiObservationSerializer.MAX_OUTPUT_CHARS
        const val READ_UI_DEFAULT_TIMEOUT_MS = 6_000L
        const val MAX_SHELL_CHARS = 8_000
        const val MAX_COORDINATE = 10_000
        const val MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024
        const val MAX_PULL_BYTES = 32 * 1024 * 1024
        const val MAX_PUSH_BYTES = 64 * 1024 * 1024
        const val MAX_APK_BYTES = 256 * 1024 * 1024
        const val UI_DUMP_PATH = "/sdcard/window_dump.xml"
        private const val MAX_UI_FIELD_CHARS = UiObservationSerializer.MAX_UI_FIELD_CHARS
        private const val MAX_UI_XML_CHARS = 512 * 1024
        private const val MAX_UI_XML_DEPTH = 128
        private const val MAX_UI_NODES = UiObservationSerializer.MAX_UI_NODES
        private val BOUNDS_RE = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

        /** Scopes unchanged-suppression to this backend. */
        private const val BACKEND = "adb"

        private const val IME_ACTION_SUFFIX = ".INPUT_TEXT"
        private const val IME_EXTRA_PAYLOAD = "payload_base64"
        private const val IME_RESULT_SUCCESS = 1
        private const val MAX_IME_TEXT_BYTES = 16 * 1024
        private const val IME_READY_WAIT_MS = 2_500L
        private const val IME_READY_POLL_MS = 100L
        private const val IME_STATUS_TIMEOUT_MS = 2_000L
        private const val IME_COMMIT_ATTEMPTS = 4
        private const val IME_COMMIT_RETRY_MS = 150L

        /** POSIX single-quote escaping. Public for unit tests. */
        fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

        fun quotedRemote(path: String): String = shellQuote(path)

        /**
         * Dump the hierarchy without leaking `uiautomator`'s rotation side effect.
         *
         * `uiautomator dump` runs inside a UiAutomation session, and AOSP's
         * `UiAutomationConnection.shutdown()` restores rotation state on teardown:
         * when the session never froze a rotation itself it calls
         * `WindowManagerService.thawRotation()`, which writes
         * `Settings.System.accelerometer_rotation = 1`. So every plain dump turns
         * system Auto-Rotate on and discards the user's manual orientation lock,
         * which is why it appears to flip on by itself during agent runs (#12).
         *
         * The guard snapshots the user's rotation settings, runs the dump, and puts
         * them back in the same shell round trip, so the dump's own exit code still
         * reaches the caller and the fix costs no extra transport latency. The
         * post-dump read is short-circuited away when Auto-Rotate was already on,
         * which is the common case. Public for unit tests.
         */
        fun uiDumpCommand(path: String): String {
            val quoted = quotedRemote(path)
            return "__ar=\$(settings get system accelerometer_rotation); " +
                "__ur=\$(settings get system user_rotation); " +
                "uiautomator dump --compressed $quoted && cat $quoted; __rc=\$?; " +
                "if [ \"\$__ar\" = 0 ] && " +
                "[ \"\$(settings get system accelerometer_rotation)\" != 0 ]; then " +
                "case \"\$__ur\" in 0|1|2|3) settings put system user_rotation \"\$__ur\";; esac; " +
                "settings put system accelerometer_rotation 0; " +
                "fi; exit \$__rc"
        }

        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val COMPONENT_RE = Regex("^[A-Za-z][A-Za-z0-9_.]*(/[A-Za-z0-9_.\$]+)+$")

        fun requireValidPackage(pkg: String) {
            require(pkg.length in 3..255) { "Invalid package name" }
            require(pkg.matches(PACKAGE_RE)) { "Invalid package name: $pkg" }
        }

        fun requireValidComponent(component: String) {
            require(component.length in 3..512) { "Invalid component name" }
            require(component.matches(COMPONENT_RE)) { "Invalid component name: $component" }
        }

        fun requireValidRemotePath(path: String) {
            require(path.startsWith("/")) { "remotePath must be absolute: $path" }
            require(!path.contains('\u0000')) { "remotePath contains NUL" }
            require(path.length <= 1024) { "remotePath too long" }
        }

        /**
         * `adb shell input text` only supports ASCII. Fail honestly instead of
         * corrupting text when the app was not given its Unicode IME component.
         */
        fun requireAdbInputText(text: String) {
            require(text.isNotEmpty()) { "text cannot be empty" }
            require(text.length <= 4096) { "text exceeds 4096 chars" }
            require(!text.contains('\u0000')) { "text contains NUL" }
            require(!text.contains('\n') && !text.contains('\r')) { "text cannot contain line breaks; use submit for Enter" }
            val bad = text.firstOrNull { it.code !in 32..126 }
            require(bad == null) {
                "Unicode input is not supported by `adb input text` (found U+%04X). ".format(bad!!.code) +
                "Root follow-up: add an IME bridge for Unicode input; text was not sent."
            }
        }

        /** Validate text before making any IME selection or broadcast call. */
        fun validateImeText(text: String) {
            require(text.isNotEmpty()) { "text cannot be empty" }
            require(!text.contains('\u0000')) { "text contains NUL" }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_IME_TEXT_BYTES) {
                "text exceeds the Unicode input size limit"
            }
        }

        /** Base64 UTF-8 payload shared with AgentInputMethodService. */
        fun encodeImePayload(text: String): String {
            validateImeText(text)
            return Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        }

        /** The secure setting should exactly match the component we selected. */
        internal fun imeSelectionMatches(current: String?, requested: String): Boolean =
            current?.trim()?.let { normalizedComponent(it) } == normalizedComponent(requested)

        internal fun imeBroadcastResult(output: String): Int? =
            Regex("\\bresult=(-?\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()

        internal fun imeBroadcastCommitted(output: String): Boolean = imeBroadcastResult(output) == IME_RESULT_SUCCESS

        internal fun imeFailureReason(code: Int?): String = when (code) {
            0 -> "IME receiver did not respond"
            2 -> "IME rejected the sender identity"
            3 -> "IME rejected the text payload"
            4 -> "IME has no active target editor"
            5 -> "editor did not confirm the text commit"
            else -> "commit acknowledgement unavailable"
        }

        fun buildImeProbeCommand(component: String): String {
            requireValidImeComponent(component)
            val packageName = component.substringBefore('/')
            return "am broadcast --user current --receiver-foreground -p ${shellQuote(packageName)} " +
                "-a ${shellQuote(packageName + ".INPUT_PROBE") }"
        }

        private fun normalizedComponent(value: String): String {
            val clean = value.trim().trimEnd(',', ';')
            val slash = clean.indexOf('/')
            if (slash <= 0 || slash == clean.lastIndex) return clean
            val pkg = clean.substring(0, slash)
            val cls = clean.substring(slash + 1)
            return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
        }

        /**
         * Build the only broadcast accepted by the input service. The action is
         * package-scoped and the payload is one shell-safe Base64 token.
         */
        fun buildImeBroadcastCommand(component: String, payload: String): String {
            requireValidImeComponent(component)
            require(payload.isNotEmpty() && payload.length <= ((MAX_IME_TEXT_BYTES + 2) / 3) * 4 + 4) {
                "IME payload is invalid"
            }
            val packageName = component.substringBefore('/')
            val action = packageName + IME_ACTION_SUFFIX
            return "am broadcast --user current --receiver-foreground " +
                "-p ${shellQuote(packageName)} " +
                "-a ${shellQuote(action)} " +
                "--es ${shellQuote(IME_EXTRA_PAYLOAD)} ${shellQuote(payload)}"
        }

        fun requireValidImeComponent(component: String) {
            require(component.length in 3..512) { "Invalid input method component" }
            val slash = component.indexOf('/')
            require(slash > 0 && slash == component.lastIndexOf('/')) {
                "Invalid input method component"
            }
            val pkg = component.substring(0, slash)
            val cls = component.substring(slash + 1)
            requireValidPackage(pkg)
            require(cls.matches(Regex("^\\.?[A-Za-z][A-Za-z0-9_.\$]*$"))) {
                "Invalid input method component"
            }
        }

        private val KEYCODES = mapOf(
            "BACK" to 4, "HOME" to 3, "MENU" to 82, "APP_SWITCH" to 187,
            "ENTER" to 66, "TAB" to 61, "ESCAPE" to 111, "DELETE" to 67,
            "FORWARD_DEL" to 112, "DPAD_UP" to 19, "DPAD_DOWN" to 20,
            "DPAD_LEFT" to 21, "DPAD_RIGHT" to 22, "DPAD_CENTER" to 23,
            "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "POWER" to 26,
            "CAMERA" to 27, "WAKEUP" to 224, "SLEEP" to 223,
        )

        fun resolveKeycode(raw: String): Int {
            val trimmed = raw.trim()
            trimmed.toIntOrNull()?.let {
                require(it in 0..260) { "keycode must be between 0 and 260" }
                return it
            }
            val normalized = trimmed.uppercase().removePrefix("KEYCODE_")
            return KEYCODES[normalized]
                ?: throw IllegalArgumentException("Unknown key: $raw")
        }

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size > 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()

        val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            ACT_AND_OBSERVE_DEFINITION,
            tool("device_status", "Report which device backends are live: the accessibility service, and the optional Wireless ADB. Read-only.", emptyMap(), emptyList()),
            tool(
                "read_ui",
                READ_UI_DESCRIPTION,
                mapOf(
                    "timeoutMs" to "integer", "raw" to "boolean", "force" to "boolean",
                    "text" to "string", "resourceId" to "string", "class" to "string",
                    "package" to "string", "rootNodeId" to "string",
                    "clickableOnly" to "boolean", "scrollableOnly" to "boolean",
                    "offset" to "integer", "maxNodes" to "integer", "maxChars" to "integer",
                ),
                emptyList(),
            ),
            tool("screenshot", "Capture a PNG screenshot. Returns imageBase64. Read-only.", emptyMap(), emptyList()),
            tool("tap", "Tap the screen at pixel coordinates.", mapOf("x" to "integer", "y" to "integer"), listOf("x", "y")),
            tool("swipe", "Swipe from one point to another.", mapOf("x1" to "integer", "y1" to "integer", "x2" to "integer", "y2" to "integer", "durationMs" to "integer"), listOf("x1", "y1", "x2", "y2")),
            tool("type_text", "Type text. Uses the configured IME for full Unicode; ASCII falls back to adb input when no IME is configured.", mapOf("text" to "string", "submit" to "boolean"), listOf("text")),
            tool("key", "Send a keyevent by name or numeric code.", mapOf("keycode" to "string"), listOf("keycode")),
            tool("open_app", "Launch an app by package, optionally with activity.", mapOf("package" to "string", "activity" to "string"), listOf("package")),
            tool("shell", "Run an arbitrary shell command. Visible device control.", mapOf("command" to "string", "timeoutMs" to "integer"), listOf("command")),
            tool("pull_file", "Copy a file from the device into the run workspace. Read-only.", mapOf("remotePath" to "string", "localName" to "string"), listOf("remotePath", "localName")),
            tool("push_file", "Push a workspace file to the device.", mapOf("localName" to "string", "remotePath" to "string"), listOf("localName", "remotePath")),
            tool("install_apk", "Install a workspace APK on the device.", mapOf("localName" to "string", "replace" to "boolean"), listOf("localName")),
        )

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
        ): ToolDefinition {
            val props = buildJsonObject {
                for ((k, t) in properties) putJsonType(k, t)
            }
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("description", description)
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
            return ToolDefinition(name, description, schema)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonType(key: String, type: String) {
            put(key, buildJsonObject { put("type", type) })
        }
    }
}
