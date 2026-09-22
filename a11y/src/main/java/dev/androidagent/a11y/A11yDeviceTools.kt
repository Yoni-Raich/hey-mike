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

package dev.androidagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.androidagent.core.ACT_AND_OBSERVE_ACTIONS
import dev.androidagent.core.ACT_AND_OBSERVE_DEFINITION
import dev.androidagent.core.DeviceToolGateway
import dev.androidagent.core.READ_UI_DESCRIPTION
import dev.androidagent.core.SendRequest
import dev.androidagent.core.ObservationState
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolNotServiceable
import dev.androidagent.core.ToolResult
import dev.androidagent.core.ToolDispatch
import dev.androidagent.core.UiObservation
import dev.androidagent.core.LocalIntentRequest
import dev.androidagent.core.UiObservationSerializer
import dev.androidagent.core.UiQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.math.round

/**
 * Device gateway backed by the accessibility service, so observation and
 * action work with no ADB connection at all.
 *
 * It implements the existing tool names rather than introducing new ones.
 * Codex binds the tool list at thread start and never re-sends it, so every
 * chat opened before this shipped still asks for `read_ui` and `tap` — naming
 * the accessibility versions differently would leave those chats dead the
 * moment Wireless Debugging is off.
 *
 * Anything this backend genuinely cannot do raises [ToolNotServiceable] before
 * touching the device, which lets the composite fall through to ADB.
 */
class A11yDeviceTools(
    private val context: Context,
    private val observations: ObservationState,
    /** Moves the floating card away from a coordinate before a gesture lands on it. */
    private val avoidTouch: (Int, Int) -> Unit = { _, _ -> },
    /** Steps the floating card aside (true) and back (false) around a whole-display capture. */
    private val observationVisibility: suspend (Boolean) -> Unit = {},
    private val authorizeIntent: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult = { _, _ ->
        ToolResult(
            "{\"ok\":false,\"errorType\":\"approval_unavailable\",\"message\":\"This intent needs approval in the app.\"}",
            success = false,
        )
    },
    /**
     * Gates a press of Send in another app. The default refuses, so a host
     * that wires no approval can never send a message on its own.
     */
    private val authorizeSend: suspend (SendRequest, suspend () -> ToolResult) -> ToolResult = { _, _ ->
        ToolResult(
            "{\"ok\":false,\"errorType\":\"approval_unavailable\",\"message\":\"Sending needs approval in the app. Nothing was sent.\"}",
            success = false,
        )
    },
    /** Move the approval screen out of the way so the app underneath is back in front. */
    private val leaveApprovalScreen: () -> Unit = {},
) : DeviceToolGateway {

    private val lock = Any()
    private val generation = AtomicLong()
    private class RunEpoch(val value: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RunEpoch>
    }

    /** Direct navigation, tried before walking the UI. */
    private val intents = IntentTools(context, authorizeIntent = authorizeIntent)

    @Volatile private var revoked = true
    @Volatile private var workspace: File? = null

    /**
     * Node handles from the most recent observation only. An action addressed
     * to an older observation is refused rather than guessed at, and the map
     * is dropped on revoke so a stopped run leaves nothing behind.
     */
    @Volatile private var handles: Map<String, A11yNodeView> = emptyMap()

    /**
     * Every observation id these handles answer to.
     *
     * An unchanged reply tells the model to reuse the nodes from an earlier
     * revision, so that earlier id has to keep working — otherwise following
     * our own advice would be refused as stale.
     */
    @Volatile private var handleObservationIds: Set<String> = emptySet()
    private data class Snapshot(val observation: UiObservation, val id: String, val revision: Long, val stable: Boolean)
    @Volatile private var snapshot: Snapshot? = null

    override val definitions: List<ToolDefinition> = TOOL_DEFINITIONS

    override fun beginRun(runId: String, workspace: File) {
        require(runId.isNotBlank()) { "runId cannot be blank" }
        synchronized(lock) {
            generation.incrementAndGet()
            this.workspace = workspace.absoluteFile
            workspace.absoluteFile.mkdirs()
            clearHandles()
            revoked = false
        }
        // Re-pushed on every run start, so an instance that reconnected after
        // a crash or a self-update is not left permanently idle-gated.
        A11yServiceHandle.service.value?.runActive = true
    }

    override fun revoke() {
        synchronized(lock) {
            generation.incrementAndGet()
            revoked = true
            clearHandles()
        }
        A11yServiceHandle.service.value?.runActive = false
    }

    override fun needsControl(name: String): Boolean =
        when (name) {
            "read_ui", "screenshot", "resolve_intent" -> false
            else -> true
        }

    override fun hidesOverlayDuringCapture(name: String): Boolean =
        // Neither tool needs the coordinator to hide the card: read_ui filters
        // our own windows out of the tree, and screenshot leaves our window out
        // of the capture, stepping the card aside itself only where it cannot.
        false

    override fun statusLine(): String {
        val connected = A11yServiceHandle.connected
        val declared = A11yAvailability.isDeclaredEnabled(context)
        return "Accessibility: " + when {
            connected -> "connected"
            declared -> "enabled but not connected (restricted setting?)"
            else -> "off"
        }
    }

    /**
     * Everything this backend does needs a bound service, and none of it needs
     * ADB. That asymmetry is the point of issue #44: with the service on and
     * Wireless Debugging off, observation, touch, text and intents are all
     * still live and must not be reported as unavailable.
     */
    override fun readyTools(): Set<String> =
        if (A11yServiceHandle.connected) definitions.map { it.name }.toSet()
        else SERVICE_FREE_TOOLS intersect definitions.map { it.name }.toSet()

    /**
     * Only the screen needs the service. Launching an app or an intent is a
     * plain startActivity from this app, and reporting those as blocked while
     * the service was off made the agent refuse a deep link it could open.
     */
    override fun deviceBackendLive(): Boolean = A11yServiceHandle.connected

    override suspend fun cancel() {
        // Nothing to tear down: every wait here is bounded by withTimeoutOrNull
        // and unwinds with the cancelled coroutine.
    }

    override suspend fun invoke(name: String, arguments: JsonObject): ToolResult {
        checkActive()
        val epoch = currentCoroutineContext()[RunEpoch] ?: RunEpoch(generation.get())
        return withContext(epoch) { invokeOwned(name, arguments) }
    }

    private suspend fun invokeOwned(name: String, arguments: JsonObject): ToolResult {
        if (revoked || workspace == null) {
            throw IllegalStateException("Run stopped. No device action was performed.")
        }
        checkActive()
        val result = when (name) {
            "act_and_observe" -> actAndObserve(arguments)
            "read_ui" -> readUi(arguments)
            "screenshot" -> screenshot()
            "tap" -> tap(arguments)
            "swipe" -> swipe(arguments)
            "drag" -> drag(arguments)
            "long_press_node" -> longPressNode(arguments)
            "type_text" -> typeText(arguments)
            "key" -> pressKey(arguments)
            "open_app" -> openApp(arguments)
            "tap_node" -> tapNode(arguments)
            "set_text" -> setText(arguments)
            "set_progress" -> setProgress(arguments)
            "scroll_node" -> scrollNode(arguments)
            "wait_for_change" -> waitForChange(arguments)
            "resolve_intent" -> intents.resolve(arguments)
            "open_intent" -> intents.open(arguments)
            else -> throw ToolNotServiceable(
                "a11y_unsupported",
                "The accessibility backend does not implement \"$name\".",
            )
        }
        checkActive()
        return result
    }

    /**
     * One action, then a fresh observation.
     *
     * Only ADB used to serve this, so the tool the system prompt recommends
     * failed as soon as Wireless Debugging was off. A [ToolNotServiceable] from
     * the action still propagates, since nothing has happened yet and ADB may
     * take the whole call; once the action has landed, nothing may.
     */
    private suspend fun actAndObserve(arguments: JsonObject): ToolResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull
        require(action in ACT_AND_OBSERVE_ACTIONS) { "Choose one supported action." }
        val args = arguments["arguments"] as? JsonObject
            ?: throw IllegalArgumentException("Action arguments are required.")
        val result = invoke(action!!, args)
        if (!result.success) return result // Never observe after a failed commit.
        checkActive()
        val observation = try {
            readUi(buildJsonObject {})
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolResult("Observation failed: ${failure.message}. Do not repeat the completed action.", success = false)
        }
        return ToolResult(buildJsonObject {
            put("actionCompleted", true)
            put("actionResult", result.text)
            put("observationSucceeded", observation.success)
            put("observation", runCatching { Json.parseToJsonElement(observation.text) }.getOrElse { JsonPrimitive(observation.text) })
        }.toString(), success = observation.success)
    }

    // ---- observation ----

    private suspend fun readUi(arguments: JsonObject): ToolResult {
        if (arguments["raw"]?.jsonPrimitive?.booleanOrNull == true) {
            // There is no XML behind this backend. Nothing was read, so ADB
            // may still serve the debug path.
            throw ToolNotServiceable(
                "ui_raw_unavailable",
                "raw=true returns the uiautomator XML dump, which only the ADB backend produces.",
            )
        }
        val service = requireService()
        val force = arguments["force"]?.jsonPrimitive?.booleanOrNull ?: false
        val query = UiQuery.from(arguments)
        arguments["snapshotId"]?.jsonPrimitive?.contentOrNull?.let { id ->
            val held = snapshot?.takeIf { it.id == id }
                ?: throw ToolNotServiceable("snapshot_expired", "Snapshot expired; start read_ui again without snapshotId.")
            val page = UiObservationSerializer.render(held.observation, SOURCE, BACKEND, held.id,
                held.revision, 0, null, true, held.stable, query)
            return ToolResult(JsonObject(Json.parseToJsonElement(page.text).jsonObject +
                ("snapshotPaging" to JsonPrimitive(true))).toString(), success = page.ok)
        }
        val revision = observations.nextRevision()
        val observationId = "ui-$revision"
        val startedAt = System.nanoTime()

        // Reading a screen that is still animating produces nodes that have
        // already moved. A timeout is not fatal, it just means "not settled".
        val stable = awaitQuiescence(service)
        checkActive()

        val visible = service.visibleWindows()
        val result = traverse(visible, context.packageName)
        val display = requireNotNull(service.getSystemService(android.view.WindowManager::class.java)).maximumWindowMetrics.bounds
        val observed = result.observation.copy(
            viewport = listOf(display.left, display.top, display.right, display.bottom),
            windows = visible.filter { it.root?.packageName != context.packageName }.map { window -> buildJsonObject {
                put("type", window.type); put("active", window.active)
                window.root?.packageName?.let { put("package", it) }
                window.root?.boundsInScreen?.let { put("bounds", buildJsonArray { it.forEach { value -> add(value) } }) }
            } },
        )
        val rendered = UiObservationSerializer.render(
            observation = observed,
            source = SOURCE,
            backend = BACKEND,
            observationId = observationId,
            revision = revision,
            elapsedMs = elapsedMs(startedAt),
            previous = observations.last(),
            force = force,
            stable = stable,
            query = query,
        )
        val epoch = currentCoroutineContext()[RunEpoch]?.value
        synchronized(lock) {
            // A rejected query never reached the model as a node list, so the
            // ids it already holds have to keep working.
            if (!revoked && epoch == generation.get() && rendered.ok) {
                // Handles come from the whole traversal, never from the page
                // that was emitted: a node the query filtered out is still on
                // screen, and an action that names it must still land.
                handles = result.handles
                snapshot = Snapshot(observed, observationId, revision, stable)
                handleObservationIds =
                    if (rendered.unchanged) handleObservationIds + observationId else setOf(observationId)
            }
        }
        rendered.fingerprint?.let { observations.record(it) }
        return ToolResult(JsonObject(Json.parseToJsonElement(rendered.text).jsonObject +
            ("snapshotPaging" to JsonPrimitive(true))).toString(), success = rendered.ok)
    }

    /** True when the screen stopped changing before the budget ran out. */
    private suspend fun awaitQuiescence(service: AgentAccessibilityService): Boolean =
        withTimeoutOrNull(QUIESCENCE_TIMEOUT_MS) {
            while (service.idleMs < QUIESCENCE_IDLE_MS) {
                delay(QUIESCENCE_POLL_MS)
            }
            true
        } ?: false

    // ---- actions ----

    /** Capture the screen without ADB, and without our floating card in it. */
    private suspend fun screenshot(): ToolResult {
        val service = requireService()
        val png = captureWithoutOverlay(service)
        if (png.size > MAX_SCREENSHOT_BYTES) {
            // Bigger than the model will accept. Say so rather than truncating
            // into a file that decodes to a corrupt image.
            return ToolResult(
                "Screenshot is ${png.size} bytes, above the ${MAX_SCREENSHOT_BYTES} byte limit.",
                success = false,
            )
        }
        checkActive()
        val image = File(checkNotNull(workspace), "screenshots/${java.util.UUID.randomUUID()}.png")
        image.parentFile!!.mkdirs()
        image.writeBytes(png)
        return ToolResult(
            text = "Screenshot captured (${png.size} bytes, PNG, accessibility)",
            imageBase64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP),
            attachmentPaths = listOf(image.absolutePath),
        )
    }

    /**
     * On Android 14+ each window is captured on its own and ours is left out,
     * so the floating card never has to leave the screen. On older Android, or
     * when that fails for any reason but a secure window, the display is
     * composited whole with the card stepped aside for the moment of capture.
     */
    private suspend fun captureWithoutOverlay(service: AgentAccessibilityService): ByteArray {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                return Screenshotter.capturePngWithout(service, context.packageName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A secure window refuses every capture; saying so beats a
                // whole-display attempt that the platform will refuse too.
                if (failure is ToolNotServiceable && failure.errorType == "screenshot_secure_window") throw failure
            }
        }
        observationVisibility(true)
        try {
            return Screenshotter.capturePng(service)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { observationVisibility(false) }
        }
    }

    private suspend fun tap(arguments: JsonObject): ToolResult {
        val x = arguments.requireCoordinate("x")
        val y = arguments.requireCoordinate("y")
        requireNotOurOwnUi(x, y)
        val service = requireService()
        val root = service.rootInActiveWindow?.let(::RealNodeView)
        val hit = root?.let { SendGuard.nodeAt(it, x, y) }
        if (root != null && hit != null && SendGuard.isSendTap(hit.first, hit.second)) {
            return gatedSend(service, root) { pressSend(it) }
        }
        val landed = service.dispatchTap(x, y)
        return ToolResult("Tapped $x,$y", success = landed)
    }

    private suspend fun swipe(arguments: JsonObject): ToolResult {
        val x1 = arguments.requireCoordinate("x1")
        val y1 = arguments.requireCoordinate("y1")
        val x2 = arguments.requireCoordinate("x2")
        val y2 = arguments.requireCoordinate("y2")
        val duration = arguments["durationMs"]?.jsonPrimitive?.intOrNull ?: 300
        require(duration in 0..5_000) { "durationMs must be between 0 and 5000" }
        requireNotOurOwnUi(x1, y1)
        val service = requireService()
        val landed = service.dispatchSwipe(x1, y1, x2, y2, duration.toLong())
        return ToolResult("Swiped ($x1,$y1)->($x2,$y2) ${duration}ms", success = landed)
    }

    private suspend fun typeText(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("text is required")
        require(text.length <= MAX_TEXT_CHARS) { "text must be at most $MAX_TEXT_CHARS characters" }
        val submit = arguments["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val service = requireService()
        val target = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: throw ToolNotServiceable(
                "no_text_focus",
                "No editable field has input focus. Tap the centre of the text field first, then retry.",
            )
        val arguments1 = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val committed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments1)
        if (!committed) {
            return ToolResult("Text was rejected by the field; nothing was typed.", success = false)
        }
        val pkg = target.packageName?.toString()
        if (submit && SendGuard.isMessagingApp(pkg)) {
            // In a chat, submit is Send. The text stays typed in; only the
            // press waits for the user.
            val root = service.rootInActiveWindow?.let(::RealNodeView) ?: RealNodeView(target)
            return gatedSend(service, root) { submitDraft(it) }
        }
        // ACTION_SET_TEXT replaces the whole field, and some Compose and chat
        // composers do not propagate it. Report what the field actually holds
        // rather than assuming the write took.
        val verified = runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString() }
            .getOrNull() == text
        var submitted = false
        if (submit) {
            submitted = target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
            )
        }
        return ToolResult(
            buildJsonObject {
                put("typed", text.length)
                put("verified", verified)
                if (submit) put("submitted", submitted)
            }.toString(),
        )
    }

    private suspend fun longPressNode(arguments: JsonObject): ToolResult {
        val (id, view) = resolveNode(arguments)
        checkActive()
        if (view.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return ToolResult("Long pressed $id", dispatch = ToolDispatch.ACKNOWLEDGED)
        }
        val b = view.boundsInScreen
        val x = (b[0] + b[2]) / 2
        val y = (b[1] + b[3]) / 2
        requireNotOurOwnUi(x, y)
        checkActive()
        val landed = requireService().dispatchSwipe(x, y, x, y, 650)
        return ToolResult("Long press $id acknowledged=$landed", success = landed,
            dispatch = if (landed) ToolDispatch.ACKNOWLEDGED else ToolDispatch.UNKNOWN)
    }

    private suspend fun drag(arguments: JsonObject): ToolResult {
        val x1 = arguments.requireCoordinate("x1")
        val y1 = arguments.requireCoordinate("y1")
        val x2 = arguments.requireCoordinate("x2")
        val y2 = arguments.requireCoordinate("y2")
        val duration = arguments["durationMs"]?.jsonPrimitive?.intOrNull ?: 700
        require(duration in 100..3000) { "Drag duration must be 100..3000 ms" }
        requireNotOurOwnUi(x1, y1)
        requireNotOurOwnUi(x2, y2)
        checkActive()
        val service = requireService()
        val holdPath = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
        val hold = GestureDescription.StrokeDescription(holdPath, 0, 600, true)
        var released = false
        try {
            val held = service.dispatchAndAwait(GestureDescription.Builder().addStroke(hold).build())
            if (!held) return ToolResult("Drag hold outcome unknown", success = false, dispatch = ToolDispatch.UNKNOWN)
            checkActive()
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            val stroke = hold.continueStroke(path, 0, duration.toLong(), false)
            val landed = service.dispatchAndAwait(GestureDescription.Builder().addStroke(stroke).build())
            released = true
            return ToolResult("Drag acknowledged=$landed", success = landed,
                dispatch = if (landed) ToolDispatch.ACKNOWLEDGED else ToolDispatch.UNKNOWN)
        } finally {
            if (!released) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // Release an in-progress contact when Stop interrupts the hold.
                runCatching {
                    val release = hold.continueStroke(holdPath, 0, 1, false)
                    withTimeoutOrNull(500) { service.dispatchAndAwait(GestureDescription.Builder().addStroke(release).build()) }
                }
            }
        }
    }

    private suspend fun pressKey(arguments: JsonObject): ToolResult {
        val raw = arguments["keycode"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("keycode is required")
        val normalized = raw.trim().uppercase().removePrefix("KEYCODE_")
        val action = GLOBAL_ACTIONS[normalized]
            ?: throw ToolNotServiceable(
                "key_unsupported",
                "Accessibility can only send ${GLOBAL_ACTIONS.keys.sorted().joinToString(", ")}. " +
                    "To submit a field use type_text with submit=true, or tap the on-screen button; " +
                    "only other raw key codes need the optional Wireless ADB.",
            )
        val service = requireService()
        val sent = service.performGlobalAction(action)
        return ToolResult("Sent $normalized", success = sent,
            dispatch = if (sent) ToolDispatch.ACKNOWLEDGED else ToolDispatch.NOT_DISPATCHED)
    }

    private suspend fun openApp(arguments: JsonObject): ToolResult {
        val pkg = arguments["package"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("package is required")
        require(PACKAGE_RE.matches(pkg)) { "package is not a valid Android package name" }
        if (arguments["activity"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            // An explicit component is often not exported, and startActivity
            // cannot reach those. `am start` can, so let ADB have it.
            throw ToolNotServiceable(
                "explicit_activity_unsupported",
                "Launching a named activity needs the ADB backend.",
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw ToolNotServiceable(
                "no_launch_intent",
                "No launcher activity resolved for $pkg. It may not be installed or not visible to this app.",
            )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            // startActivity returns before the app is drawn. Reporting at once
            // made act_and_observe read the previous app, and the agent decided
            // the app had not opened and went looking for another way in.
            val service = A11yServiceHandle.service.value
                ?: return ToolResult("Opened $pkg")
            val inFront = withTimeoutOrNull(APP_OPEN_TIMEOUT_MS) {
                while (service.rootInActiveWindow?.packageName?.toString() != pkg) delay(QUIESCENCE_POLL_MS)
                true
            } ?: false
            if (inFront) {
                awaitQuiescence(service)
                ToolResult("Opened $pkg; it is in front")
            } else {
                ToolResult("Launched $pkg, but it was not in front after ${APP_OPEN_TIMEOUT_MS / 1_000}s. Call read_ui to see what is showing.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult("Could not open $pkg: ${error.message}", success = false)
        }
    }

    // ---- node addressing ----

    private suspend fun tapNode(arguments: JsonObject): ToolResult {
        val (node, view) = resolveNode(arguments)
        val ancestors = generateSequence(runCatching { view.node.parent }.getOrNull()) { runCatching { it.parent }.getOrNull() }
            .take(3).map(::RealNodeView).toList()
        if (SendGuard.isSendTap(view, ancestors)) {
            val service = requireService()
            val root = service.rootInActiveWindow?.let(::RealNodeView) ?: view
            return gatedSend(service, root) { pressSend(it) }
        }
        if (view.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ToolResult("Tapped $node", dispatch = ToolDispatch.ACKNOWLEDGED)
        }
        // A labelled node is often not the clickable one. Fall back to its
        // centre rather than reporting a failure the model cannot act on.
        val bounds = view.boundsInScreen
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        requireNotOurOwnUi(x, y)
        val landed = requireService().dispatchTap(x, y)
        return ToolResult(
            "Node $node did not accept a click; tapped its centre $x,$y instead",
            success = landed,
            dispatch = if (landed) ToolDispatch.ACKNOWLEDGED else ToolDispatch.UNKNOWN,
        )
    }

    private suspend fun setText(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("text is required")
        require(text.length <= MAX_TEXT_CHARS) { "text must be at most $MAX_TEXT_CHARS characters" }
        val submit = arguments["submit"]?.jsonPrimitive?.booleanOrNull ?: false
        val (node, view) = resolveNode(arguments)
        if (!view.isEditable) {
            return ToolResult("Node $node is not an editable field; nothing was typed.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED)
        }
        val extras = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!view.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, extras)) {
            return ToolResult("Node $node rejected the text; nothing was typed.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED)
        }
        if (submit && SendGuard.isMessagingApp(view.packageName)) {
            val service = requireService()
            val root = service.rootInActiveWindow?.let(::RealNodeView) ?: view
            return gatedSend(service, root) { submitDraft(it) }
        }
        // ACTION_SET_TEXT replaces the whole field and some composers drop it,
        // so report what the field holds instead of assuming the write took.
        val verified = withTimeoutOrNull(PROGRESS_VERIFY_TIMEOUT_MS) {
            while (true) {
                checkActive()
                if (view.node.refresh() && view.node.text?.toString() == text) return@withTimeoutOrNull true
                delay(PROGRESS_VERIFY_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        var submitted = false
        if (submit) {
            submitted = view.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ToolResult(
            buildJsonObject {
                put("nodeId", node)
                put("typed", text.length)
                put("verified", verified)
                if (submit) put("submitted", submitted)
            }.toString(), dispatch = if (verified && !submit) ToolDispatch.VERIFIED else ToolDispatch.ACKNOWLEDGED,
        )
    }

    private suspend fun scrollNode(arguments: JsonObject): ToolResult {
        val direction = arguments["direction"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: throw IllegalArgumentException("direction is required")
        val action = SCROLL_ACTIONS[direction]
            ?: throw IllegalArgumentException(
                "direction must be one of " + SCROLL_ACTIONS.keys.sorted().joinToString(", "),
            )
        val (node, view) = resolveNode(arguments)
        if (!view.isScrollable) {
            return ToolResult("Node $node is not scrollable.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED)
        }
        val scrolled = view.node.performAction(action)
        return ToolResult(
            if (scrolled) "Scrolled $node $direction"
            else "Node $node would not scroll $direction; it may already be at the end.",
            success = scrolled,
            dispatch = if (scrolled) ToolDispatch.ACKNOWLEDGED else ToolDispatch.NOT_DISPATCHED,
        )
    }

    private suspend fun setProgress(arguments: JsonObject): ToolResult {
        val requested = arguments["value"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("value is required")
        require(requested.isFinite()) { "value must be finite" }
        val (node, view) = resolveNode(arguments)
        val min = view.rangeMin?.toDouble()
        val max = view.rangeMax?.toDouble()
        val previous = view.rangeCurrent?.toDouble()
        if (min == null || max == null || previous == null || !view.supportsSetProgress) {
            return ToolResult("Node $node does not expose semantic progress control.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED)
        }
        val rangeType = view.rangeType
        val target = if (rangeType == "int") round(requested) else requested
        require(target in min..max) { "value must be between $min and $max" }
        val extras = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target.toFloat())
        }
        val committed = view.node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            extras,
        )
        if (!committed) {
            return ToolResult("Node $node rejected progress $target.", success = false, dispatch = ToolDispatch.NOT_DISPATCHED)
        }
        var current: Double? = null
        val verificationStarted = System.nanoTime()
        var verified = false
        while (!verified && (System.nanoTime() - verificationStarted) / 1_000_000L < PROGRESS_VERIFY_TIMEOUT_MS) {
            currentCoroutineContext().ensureActive()
            current = runCatching {
                view.node.refresh()
                view.node.rangeInfo?.current?.toDouble()
            }.getOrNull()
            verified = current != null && progressMatches(current!!, target, rangeType)
            if (!verified) delay(PROGRESS_VERIFY_POLL_MS)
        }
        return ToolResult(
            buildJsonObject {
                put("nodeId", node)
                put("previous", previous)
                put("requested", requested)
                put("target", target)
                rangeType?.let { put("rangeType", it) }
                put("dispatched", true)
                current?.let { put("current", it) }
                put("verified", verified)
            }.toString(),
            success = verified,
            dispatch = if (verified) ToolDispatch.VERIFIED else ToolDispatch.ACKNOWLEDGED,
        )
    }

    private fun progressMatches(current: Double, target: Double, rangeType: String?): Boolean {
        val tolerance = when (rangeType) {
            "int" -> 0.0001
            "percent" -> 0.01
            else -> maxOf(0.0001, target.absoluteValue * 0.0001)
        }
        return (current - target).absoluteValue <= tolerance
    }

    private suspend fun waitForChange(arguments: JsonObject): ToolResult {
        val timeout = (arguments["timeoutMs"]?.jsonPrimitive?.intOrNull?.toLong() ?: QUIESCENCE_TIMEOUT_MS)
            .coerceIn(100L, MAX_WAIT_MS)
        val service = requireService()
        val before = service.idleMs
        val settled = withTimeoutOrNull(timeout) {
            // Wait for something to move, then for it to stop moving.
            while (service.idleMs >= before) delay(QUIESCENCE_POLL_MS)
            while (service.idleMs < QUIESCENCE_IDLE_MS) delay(QUIESCENCE_POLL_MS)
            true
        } ?: false
        return ToolResult(
            buildJsonObject {
                put("changed", settled)
                put(
                    "hint",
                    if (settled) "The screen changed and settled. Call read_ui."
                    else "Nothing changed in time. The previous action may not have landed.",
                )
            }.toString(),
        )
    }

    /**
     * Resolve a nodeId against the observation it came from.
     *
     * Both ids are required. A nodeId alone is meaningless once the screen has
     * been re-read, and quietly acting on a stale one is exactly how an agent
     * taps the wrong thing.
     */
    private fun resolveNode(arguments: JsonObject): Pair<String, RealNodeView> {
        val nodeId = arguments["nodeId"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("nodeId is required")
        val observationId = arguments["observationId"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("observationId is required")
        val accepted = handleObservationIds
        if (accepted.isEmpty()) {
            throw ToolNotServiceable("stale_node", "No observation is held. Call read_ui before addressing a node.")
        }
        if (observationId !in accepted) {
            throw ToolNotServiceable("stale_node",
                "$nodeId belongs to observation $observationId, which is no longer current " +
                    "(holding ${accepted.sorted().joinToString(", ")}). " +
                    "Call read_ui and use the node ids it returns.",
            )
        }
        val view = handles[nodeId] as? RealNodeView
            ?: throw ToolNotServiceable("stale_node",
                "$nodeId is not in the current observation. Call read_ui and pick a node it lists.",
            )
        if (!runCatching { view.node.refresh() }.getOrDefault(false)) {
            throw ToolNotServiceable("stale_node", "$nodeId is no longer on screen. Call read_ui and pick a current node.")
        }
        return nodeId to view
    }

    // ---- helpers ----

    private suspend fun requireService(): AgentAccessibilityService {
        val service = A11yServiceHandle.service.value
            ?: A11yServiceHandle.await(CONNECT_GRACE_MS)
            ?: throw ToolNotServiceable(
                "a11y_unavailable",
                "The Hey Mike accessibility service is not running. Ask the user to " +
                    "enable it in Settings > Accessibility > Hey Mike.",
            )
        checkActive()
        return service
    }

    // ---- sending ----

    /**
     * Ask before a message leaves the phone, then press Send.
     *
     * Asking raises Hey Mike over the app being driven, so [press] only runs
     * after the app is back in front, and finds the control again rather than
     * trusting a coordinate from before the switch.
     */
    private suspend fun gatedSend(
        service: AgentAccessibilityService,
        root: RealNodeView,
        press: suspend (AgentAccessibilityService) -> ToolResult,
    ): ToolResult {
        val pkg = root.packageName ?: service.rootInActiveWindow?.packageName?.toString()
            ?: return ToolResult(sendFailure("send_app_unknown", "Could not tell which app this send is in. Nothing was sent."), success = false)
        val request = SendRequest(
            packageName = pkg,
            appLabel = appLabel(pkg),
            recipient = SendGuard.recipient(root),
            message = SendGuard.draft(root),
        )
        return authorizeSend(request) {
            checkActive()
            val live = requireService()
            if (!returnTo(live, pkg)) {
                ToolResult(sendFailure("send_app_gone", "${request.appLabel} could not be brought back. Nothing was sent."), success = false)
            } else {
                press(live)
            }
        }
    }

    private suspend fun returnTo(service: AgentAccessibilityService, pkg: String): Boolean {
        fun inFront() = service.rootInActiveWindow?.packageName?.toString() == pkg
        suspend fun waitInFront(): Boolean = withTimeoutOrNull(RETURN_TIMEOUT_MS) {
            while (!inFront()) delay(QUIESCENCE_POLL_MS)
            true
        } ?: false
        if (inFront()) return true
        // Step the approval screen back first: that uncovers the chat exactly
        // as it was. A launcher intent was tried first on a phone and landed
        // WhatsApp on its chat list, where there is no Send button to press.
        runCatching { leaveApprovalScreen() }
        var back = waitInFront()
        if (!back) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }.getOrElse { return false }
            back = waitInFront()
        }
        if (back) awaitQuiescence(service)
        return back
    }

    private suspend fun pressSend(service: AgentAccessibilityService): ToolResult {
        val root = service.rootInActiveWindow?.let(::RealNodeView)
        val send = root?.let { SendGuard.findSend(it) } as? RealNodeView
            ?: return ToolResult(
                sendFailure("send_control_gone", "The Send button is not on screen any more. Nothing was sent; call read_ui."),
                success = false,
            )
        var target: android.view.accessibility.AccessibilityNodeInfo? = send.node
        repeat(3) {
            if (target?.isClickable == true && target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ToolResult("{\"ok\":true,\"sent\":true,\"note\":\"Pressed Send. Confirm with read_ui that the message appears in the chat.\"}")
            }
            target = runCatching { target?.parent }.getOrNull()
        }
        val bounds = send.boundsInScreen
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        requireNotOurOwnUi(x, y)
        val landed = service.dispatchTap(x, y)
        return ToolResult(
            "{\"ok\":$landed,\"sent\":$landed,\"note\":\"Tapped Send. Confirm with read_ui that the message appears in the chat.\"}",
            success = landed,
        )
    }

    private suspend fun submitDraft(service: AgentAccessibilityService): ToolResult {
        val field = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (field != null && field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            return ToolResult("{\"ok\":true,\"submitted\":true,\"note\":\"Submitted. Confirm with read_ui that the message was sent.\"}")
        }
        // Coming back from the approval can drop input focus; the Send button
        // does the same thing.
        return pressSend(service)
    }

    private fun appLabel(pkg: String): String =
        SendGuard.MESSAGING_PACKAGES[pkg] ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun sendFailure(errorType: String, message: String): String =
        buildJsonObject {
            put("ok", false)
            put("errorType", errorType)
            put("message", message)
        }.toString()

    /**
     * A coordinate gesture hits whatever is topmost, so filtering our windows
     * out of the tree does nothing for it. Move the card, then refuse if the
     * point is still ours.
     */
    private suspend fun requireNotOurOwnUi(x: Int, y: Int) {
        avoidTouch(x, y)
        val service = A11yServiceHandle.service.value ?: return
        // avoidTouch hops to the main thread and the window manager applies the
        // move a frame later, so checking immediately refuses points the card is
        // already leaving. Give the move a few beats before giving up on one.
        repeat(OWN_UI_SETTLE_ATTEMPTS) {
            if (!service.ownWindowContains(context.packageName, x, y)) return
            delay(OWN_UI_SETTLE_MS)
            avoidTouch(x, y)
        }
        if (service.ownWindowContains(context.packageName, x, y)) {
            throw IllegalStateException(
                "($x,$y) is inside Hey Mike's own window. Nothing was tapped; " +
                    "read_ui again and pick a target in the app you are driving.",
            )
        }
    }

    private fun clearHandles() {
        handles = emptyMap()
        handleObservationIds = emptySet()
        snapshot = null
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (revoked || currentCoroutineContext()[RunEpoch]?.value?.let { it != generation.get() } == true) {
            throw CancellationException("Device control was stopped or superseded")
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private fun JsonObject.requireCoordinate(key: String): Int {
        val value = this[key]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("$key is required and must be an integer")
        require(value in 0..MAX_COORDINATE) { "$key must be between 0 and $MAX_COORDINATE" }
        return value
    }

    companion object {
        private const val SOURCE = "accessibility"

        /** Scopes unchanged-suppression to this backend. */
        private const val BACKEND = "a11y"

        private const val CONNECT_GRACE_MS = 750L
        private const val QUIESCENCE_IDLE_MS = 350L
        private const val QUIESCENCE_TIMEOUT_MS = 3_000L
        private const val QUIESCENCE_POLL_MS = 50L
        private const val PROGRESS_VERIFY_TIMEOUT_MS = 750L
        private const val PROGRESS_VERIFY_POLL_MS = 25L
        private const val MAX_COORDINATE = 20_000
        private const val OWN_UI_SETTLE_ATTEMPTS = 4
        private const val OWN_UI_SETTLE_MS = 40L
        private const val RETURN_TIMEOUT_MS = 4_000L
        private const val APP_OPEN_TIMEOUT_MS = 5_000L

        /** Tools that work with the accessibility service off. */
        internal val SERVICE_FREE_TOOLS = setOf("open_app", "open_intent", "resolve_intent")
        private const val MAX_TEXT_CHARS = 4_000

        /** Same ceiling the ADB backend enforces, so the two agree. */
        private const val MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024

        private const val MAX_WAIT_MS = 30_000L

        // A getter avoids resolving AccessibilityAction singleton objects when
        // host-side schema tests load this class against the Android stub jar.
        private val SCROLL_ACTIONS: Map<String, Int>
            get() = mapOf(
                "forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                "backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                "up" to AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                "down" to AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                "left" to AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                "right" to AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            )

        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        /** Everything accessibility can send. Anything else belongs to ADB. */
        private val GLOBAL_ACTIONS = mapOf(
            "BACK" to AccessibilityService.GLOBAL_ACTION_BACK,
            "HOME" to AccessibilityService.GLOBAL_ACTION_HOME,
            "APP_SWITCH" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "RECENTS" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "NOTIFICATIONS" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "QUICK_SETTINGS" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "POWER" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            "LOCK" to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
        )

        /** Internal rather than private so this module's tests can audit it. */
        internal val TOOL_DEFINITIONS: List<ToolDefinition> = listOf(
            ACT_AND_OBSERVE_DEFINITION,
            tool(
                "read_ui",
                READ_UI_DESCRIPTION,
                mapOf(
                    "timeoutMs" to "integer", "raw" to "boolean", "force" to "boolean", "snapshotId" to "string",
                    "text" to "string", "resourceId" to "string", "class" to "string",
                    "package" to "string", "rootNodeId" to "string",
                    "clickableOnly" to "boolean", "scrollableOnly" to "boolean",
                    "offset" to "integer", "maxNodes" to "integer", "maxChars" to "integer",
                ),
                emptyList(),
            ),
            tool("screenshot", "Capture a PNG screenshot. Returns imageBase64. Read-only.", emptyMap(), emptyList()),
            tool("tap", "Tap the screen at pixel coordinates.", mapOf("x" to "integer", "y" to "integer"), listOf("x", "y")),
            tool(
                "swipe",
                "Swipe from one point to another.",
                mapOf("x1" to "integer", "y1" to "integer", "x2" to "integer", "y2" to "integer", "durationMs" to "integer"),
                listOf("x1", "y1", "x2", "y2"),
            ),
            tool("long_press_node", "Long press a node from read_ui.",
                mapOf("nodeId" to "string", "observationId" to "string"), listOf("nodeId", "observationId")),
            tool("drag", "Hold then drag between two observed screen points.",
                mapOf("x1" to "integer", "y1" to "integer", "x2" to "integer", "y2" to "integer", "durationMs" to "integer"),
                listOf("x1", "y1", "x2", "y2")),
            tool(
                "type_text",
                "Type text into the focused field. Tap the field first so it holds input focus.",
                mapOf("text" to "string", "submit" to "boolean"),
                listOf("text"),
            ),
            tool("key", "Send a keyevent by name or numeric code.", mapOf("keycode" to "string"), listOf("keycode")),
            tool("open_app", "Launch an app by package, optionally with activity.", mapOf("package" to "string", "activity" to "string"), listOf("package")),
            tool(
                "tap_node",
                "Tap a node from the most recent read_ui by its id instead of computing a coordinate. " +
                    "Pass the observationId the node came from; a node from an older observation is refused.",
                mapOf("nodeId" to "string", "observationId" to "string"),
                listOf("nodeId", "observationId"),
            ),
            tool(
                "set_text",
                "Replace the text of an editable node from the most recent read_ui. Reports verified=false " +
                    "when the field did not take the value, which some chat and Compose inputs do not.",
                mapOf("nodeId" to "string", "observationId" to "string", "text" to "string", "submit" to "boolean"),
                listOf("nodeId", "observationId", "text"),
            ),
            tool(
                "set_progress",
                "Set the absolute value of a ranged control from the most recent read_ui. " +
                    "Use the min, max and current range values exposed on that node.",
                mapOf("nodeId" to "string", "observationId" to "string", "value" to "number"),
                listOf("nodeId", "observationId", "value"),
            ),
            tool(
                "scroll_node",
                "Scroll a scrollable node from the most recent read_ui. More reliable than a swipe gesture " +
                    "inside a list. direction: forward, backward, up, down, left or right.",
                mapOf("nodeId" to "string", "observationId" to "string", "direction" to "string"),
                listOf("nodeId", "observationId", "direction"),
            ),
            tool(
                "wait_for_change",
                "Block until the screen changes and settles, or the timeout expires. Use it after an action " +
                    "that starts a transition instead of polling read_ui. changed=false means nothing moved.",
                mapOf("timeoutMs" to "integer"),
                emptyList(),
            ),
        ) + IntentTools.DEFINITIONS_SPEC.map { spec ->
            tool(spec.name, spec.description, spec.properties, spec.required)
        }

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
        ): ToolDefinition {
            val props = buildJsonObject {
                for ((key, type) in properties) {
                    put(key, buildJsonObject {
                        put("type", type)
                        // A free-form map, such as open_intent's extras.
                        if (type == "object") put("additionalProperties", true)
                    })
                }
            }
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("description", description)
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
            return ToolDefinition(name, description, schema)
        }
    }
}

/** Front-to-back windows, with the active one flagged. */
internal fun AgentAccessibilityService.visibleWindows(): List<A11yWindow> =
    runCatching {
        windows.map { window ->
            A11yWindow(
                root = runCatching { window.root }.getOrNull()?.let(::RealNodeView),
                active = window.isActive,
                type = when (window.type) {
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    else -> "application"
                },
            )
        }
    }.getOrElse {
        // Some devices refuse the window list; the focused window still works.
        listOfNotNull(rootInActiveWindow?.let { A11yWindow(RealNodeView(it), active = true) })
    }

/** True when (x, y) falls inside a window this app owns. */
internal fun AgentAccessibilityService.ownWindowContains(ownPackage: String, x: Int, y: Int): Boolean =
    runCatching {
        windows.any { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@any false
            if (root.packageName?.toString() != ownPackage) return@any false
            val rect = android.graphics.Rect()
            window.getBoundsInScreen(rect)
            rect.contains(x, y)
        }
    }.getOrDefault(false)

internal suspend fun AgentAccessibilityService.dispatchTap(x: Int, y: Int): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val stroke = GestureDescription.StrokeDescription(path, 0, 50)
    return dispatchAndAwait(GestureDescription.Builder().addStroke(stroke).build())
}

internal suspend fun AgentAccessibilityService.dispatchSwipe(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    durationMs: Long,
): Boolean {
    val path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
    }
    val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
    return dispatchAndAwait(GestureDescription.Builder().addStroke(stroke).build())
}

private suspend fun AccessibilityService.dispatchAndAwait(gesture: GestureDescription): Boolean {
    val outcome = kotlinx.coroutines.CompletableDeferred<Boolean>()
    val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(description: GestureDescription?) { outcome.complete(true) }
        override fun onCancelled(description: GestureDescription?) { outcome.complete(false) }
    }
    if (!dispatchGesture(gesture, callback, null)) return false
    return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { outcome.await() } ?: false
}

private const val GESTURE_TIMEOUT_MS = 5_000L
