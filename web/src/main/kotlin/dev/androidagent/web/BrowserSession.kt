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

package dev.androidagent.web

import dev.androidagent.core.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A browser operation that did not happen, or did not finish, and why.
 *
 * [errorType] is the stable name the model acts on; [remedy] says what to do
 * instead, so a refusal is a next step rather than a dead end.
 */
class WebFailure(
    val errorType: String,
    override val message: String,
    val remedy: String? = null,
    val mayHaveActed: Boolean = false,
    /** The message already says exactly what happened on the page; the gateway must not reword it. */
    val known: Boolean = false,
    val completedAction: String? = null,
) : Exception(message)

/** Which element an action is about. Exactly one of [ref], [selector] and [text] is the address. */
data class WebTarget(
    /** A ref from a snapshot: `e12`, or `f1e12` inside a frame. */
    val ref: String? = null,
    val selector: String? = null,
    /** The control's visible label, matched best-first. */
    val text: String? = null,
    val role: String? = null,
    val exact: Boolean = false,
) {
    val isEmpty: Boolean get() = ref == null && selector == null && text == null

    override fun toString(): String = ref ?: selector?.let { "selector $it" } ?: "\"${text.orEmpty()}\""
}

/** What an action did, in the words the reply uses. */
data class WebAction(val did: String, val notes: List<String> = emptyList())

/** How much of a snapshot to render, and whether to resend one the model already holds. */
data class SnapshotQuery(
    val filter: String? = null,
    val interactiveOnly: Boolean = false,
    val offset: Int = 0,
    val maxChars: Int = DEFAULT_CHARS,
    val force: Boolean = false,
) {
    companion object {
        const val DEFAULT_CHARS = 12_000
        const val MAX_CHARS = 20_000
    }
}

/**
 * One page, driven through the Chrome DevTools Protocol.
 *
 * Everything here is protocol and policy, with no Android in it: the same
 * class drives the phone's WebView and, in tests, a desktop Chrome.
 *
 * The page is read as a list of what a person could act on, each with a ref,
 * and acted on with the input a person would give it: a real pointer press at
 * the element's place and real typed text, which a page cannot tell apart from
 * a hand. The helper script lives in an isolated world the page cannot see.
 */
class BrowserSession(
    private val cdp: CdpConnection,
    private val policy: WebUrlPolicy,
    private val pointer: Pointer = Pointer.TOUCH,
    private val nativeTap: (suspend (Double, Double) -> Unit)? = null,
    private val confirm: suspend (String) -> Boolean = { false },
) : Closeable {
    enum class Pointer { TOUCH, MOUSE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val notes = ConcurrentLinkedQueue<String>()
    private val script: String = checkNotNull(BrowserSession::class.java.getResourceAsStream("page_agent.js")) {
        "page_agent.js is missing from the build"
    }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Index is the `f<n>` of a ref; 0 is the page itself. Append-only, so a ref keeps its meaning. */
    private val frames = mutableListOf<String>()
    /** The address of each frame, as the last frame tree reported it. */
    private val frameUrls = mutableMapOf<String, String>()
    private val frameParents = mutableMapOf<String, String>()
    private var revision = 0L
    private var lastDigest: String? = null
    private var lastRevision = 0L
    private var events: Closeable? = null
    @Volatile var userControlled = false

    /**
     * How many inputs this page has been sent: a press, typed text, a value
     * set, a navigation, a script. Counted just before each is sent, so a call
     * that fails with this unchanged provably did nothing to the page, and one
     * that fails after it moved may have done something.
     */
    @Volatile var inputs: Long = 0
        private set

    /** True while an operation runs, so the host never closes a page in use. */
    val busy: Boolean get() = lock.isLocked

    val isOpen: Boolean get() = cdp.isOpen

    private fun sent() { inputs += 1 }

    /** Something that happened to the page outside an action: the host adds a blocked link, a refused download. */
    fun note(text: String) {
        if (notes.size < MAX_NOTES) notes += text.take(200)
    }

    suspend fun start() = lock.withLock {
        cdp.call("Page.enable")
        // The page lives in a window that never has input focus. Without this
        // it would believe it is in the background and refuse typed text.
        runCatching { cdp.call("Emulation.setFocusEmulationEnabled", buildJsonObject { put("enabled", true) }) }
        events = cdp.onEvent { method, params ->
            when (method) {
                "Page.javascriptDialogOpening" -> answerDialog(params)
                "Page.downloadWillBegin" ->
                    note("A download was refused: ${params["url"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)}")
            }
        }
        runCatching { cdp.call("Page.setDownloadBehavior", buildJsonObject { put("behavior", "deny") }) }
        frames.clear()
        frames += mainFrameId()
        Unit
    }

    // --- navigation ---------------------------------------------------------

    suspend fun open(url: String): WebAction = lock.withLock {
        val allowed = when (val decision = withContext(Dispatchers.IO) { policy.check(url) }) {
            is WebUrlPolicy.Decision.Allowed -> decision.url
            is WebUrlPolicy.Decision.Blocked -> throw WebFailure(decision.errorType, decision.message, decision.remedy)
        }
        sent()
        val result = cdp.call("Page.navigate", buildJsonObject { put("url", allowed) }, NAVIGATE_TIMEOUT_MS)
        result["errorText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { error ->
            // An aborted load is a download or a link another app took over.
            if (!error.contains("ERR_ABORTED")) {
                throw WebFailure("navigation_failed", "$allowed did not load: $error", "Navigation was attempted. Check the page before retrying.", mayHaveActed = true)
            }
        }
        val loaded = awaitLoad(LOAD_TIMEOUT_MS)
        settle(SETTLE_MS)
        enforcePolicy()
        WebAction("open $allowed", listOfNotNull("The page was still loading after ${LOAD_TIMEOUT_MS / 1000}s.".takeIf { !loaded }))
    }

    suspend fun back(): WebAction = lock.withLock {
        val history = cdp.call("Page.getNavigationHistory")
        val index = history["currentIndex"]?.jsonPrimitive?.intOrNull ?: 0
        val entries = history["entries"] as? JsonArray ?: JsonArray(emptyList())
        if (index <= 0 || index > entries.lastIndex) {
            throw WebFailure("no_history", "There is no earlier page to go back to.")
        }
        val id = (entries[index - 1] as JsonObject)["id"]!!.jsonPrimitive.intOrNull ?: 0
        sent()
        cdp.call("Page.navigateToHistoryEntry", buildJsonObject { put("entryId", id) })
        awaitLoad(LOAD_TIMEOUT_MS)
        settle(SETTLE_MS)
        enforcePolicy()
        WebAction("back")
    }

    suspend fun reload(): WebAction = lock.withLock {
        sent()
        cdp.call("Page.reload")
        awaitLoad(LOAD_TIMEOUT_MS)
        settle(SETTLE_MS)
        enforcePolicy()
        WebAction("reload")
    }

    /** The address and title, from the browser rather than the page, so a page mid-load still answers. */
    suspend fun location(): Pair<String, String> {
        val history = cdp.call("Page.getNavigationHistory")
        val index = history["currentIndex"]?.jsonPrimitive?.intOrNull ?: 0
        val entry = (history["entries"] as? JsonArray)?.getOrNull(index) as? JsonObject
        return (entry?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()) to
            (entry?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    // --- actions ------------------------------------------------------------

    suspend fun click(target: WebTarget): WebAction = lock.withLock {
        val found = resolve(target)
        val info = info(found)
        if (info.disabled) throw WebFailure("disabled", "${info.label(found)} is disabled.", "Something else on the page has to be done first.")
        if (info.tag == "select") {
            throw WebFailure("use_select", "${info.label(found)} is a dropdown.", "Set it with operation \"select\" and value.")
        }
        // Checked before asking the user, so nobody approves a press that would
        // land on something else, and again after, because an approval takes
        // long enough for the page to change under it.
        reachable(found, info)
        authorize(info)
        if (info(found).approvalToken != info.approvalToken) throw WebFailure(
            "target_changed", "The control changed while approval was pending. Nothing was clicked.",
            "Read the page and request approval for the new action.")
        reachable(found, info)
        val point = centre(element(found))
            ?: throw WebFailure("not_visible", "${info.label(found)} has no clickable box. Nothing was clicked.")
        sent()
        tap(point.first, point.second)
        settle(SETTLE_MS)
        enforcePolicy()
        WebAction(
            "click ${info.label(found)}",
            emptyList(),
        )
    }

    suspend fun fill(target: WebTarget, value: String, submit: Boolean): WebAction = lock.withLock {
        val found = resolve(target)
        val info = info(found)
        if (info.password || info.card) {
            throw WebFailure(
                "secret_field",
                "${info.label(found)} takes a password or a card number, which the agent does not type.",
                "Call browser with operation \"show\" and ask the user to type it on the page, then go on.",
            )
        }
        if (info.disabled) throw WebFailure("disabled", "${info.label(found)} is disabled.")
        if (!info.editable && !info.valueOnly) {
            throw WebFailure("not_editable", "${info.label(found)} does not take text.", "Use click, or select for a dropdown.")
        }
        var verified: Boolean
        if (info.valueOnly) {
            sent()
            agentValue(found.frameId, "__mike.setValue(${found.ref}, ${JsonPrimitive(value)})")
            verified = sameText(currentValue(found), value)
        } else {
            // Typed text goes to whatever has the focus. A field that passes its
            // focus on (to a sibling, a hidden proxy, a dialog) would have the
            // text typed somewhere else, so the focus is checked first.
            sent() // Focus and selection can run page handlers too.
            val focused = (agentValue(found.frameId, "__mike.focusForTyping(${found.ref})") as? JsonPrimitive)?.booleanOrNull == true && hasFocus(found)
            if (!focused && info.contentEditable) {
                throw WebFailure(
                    "not_focused",
                    "${info.label(found)} did not keep the focus, so nothing was typed.",
                    "Click it first, take a snapshot, then fill it again.",
                )
            }
            sent()
            if (focused) {
                if (value.isEmpty()) key("Backspace") else cdp.call("Input.insertText", buildJsonObject { put("text", value) })
                verified = sameText(currentValue(found), value)
            } else {
                verified = false
            }
            if (!verified && !info.contentEditable) {
                // A field that swallowed the typed text, or would not keep the
                // focus, still takes its value set directly.
                agentValue(found.frameId, "__mike.setValue(${found.ref}, ${JsonPrimitive(value)})")
                verified = sameText(currentValue(found), value)
            }
        }
        if (!verified) throw WebFailure("fill_not_verified", "The field did not retain the requested text.",
            "The field may have changed. Read it before repeating the fill. No submit was sent.", mayHaveActed = true)
        if (submit) {
            if (!hasFocus(found)) throw WebFailure(
                "focus_changed", "The field now holds the text, but another control has focus. No Enter was sent.",
                "Read the page before submitting. Do not repeat the completed fill.",
                known = true, completedAction = "fill")
            if (!submitAllowed(found.frameId)) {
                throw WebFailure(
                    "approval_denied",
                    "${info.label(found)} now holds the text, but the user did not approve submitting it. Nothing was submitted.",
                    "Do not submit it another way. Ask the user what they want.",
                    known = true, completedAction = "fill",
                )
            }
            if (!hasFocus(found)) throw WebFailure(
                "focus_changed", "The field lost focus during approval. No Enter was sent.",
                "Read the page. Do not repeat the completed fill.", known = true, completedAction = "fill")
            sent()
            key("Enter")
        }
        settle(if (submit) SETTLE_MS else QUICK_SETTLE_MS)
        if (submit) enforcePolicy()
        WebAction(
            "fill ${info.label(found)}" + if (submit) " and submit" else "",
            listOfNotNull("The field does not show the text that was typed; read it before going on.".takeIf { !verified }),
        )
    }

    suspend fun select(target: WebTarget, value: String): WebAction = lock.withLock {
        val found = resolve(target)
        val info = info(found)
        if (info.disabled) throw WebFailure("disabled", "${info.label(found)} is disabled.")
        sent()
        val result = agentValue(found.frameId, "__mike.choose(${found.ref}, ${JsonPrimitive(value)})") as? JsonObject
        when (result?.get("error")?.jsonPrimitive?.contentOrNull) {
            "not_a_select" -> throw WebFailure(
                "not_a_select",
                "${info.label(found)} is not a native dropdown.",
                "Click it to open its list, then click the option.",
            )
            "no_option" -> throw WebFailure(
                "no_option",
                "${info.label(found)} has no option \"$value\". It has: " +
                    (result["options"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty(),
            )
        }
        settle(QUICK_SETTLE_MS)
        WebAction("select \"${result?.get("chosen")?.jsonPrimitive?.contentOrNull ?: value}\" in ${info.label(found)}")
    }

    suspend fun press(name: String): WebAction = lock.withLock {
        val known = KEYS.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
            ?: throw WebFailure("unknown_key", "\"$name\" is not a key the browser sends.", "Use one of: ${KEYS.keys.joinToString(", ")}.")
        if ((known == "Enter" || known == "Space") && !submitAllowed(mainFrame())) {
            throw WebFailure("approval_denied", "The user did not approve submitting this page. Nothing was submitted.")
        }
        sent()
        key(known)
        settle(SETTLE_MS)
        enforcePolicy()
        WebAction("press $known")
    }

    suspend fun scroll(direction: String?, target: WebTarget?): WebAction = lock.withLock {
        val result = if (target != null && !target.isEmpty) {
            val found = resolve(target)
            sent()
            agentValue(found.frameId, "__mike.scroll(null, ${found.ref})")
        } else {
            val way = direction?.trim()?.lowercase() ?: "down"
            if (way !in SCROLL_DIRECTIONS) {
                throw WebFailure("bad_direction", "\"$direction\" is not a direction.", "Use up, down, top or bottom, or name an element.")
            }
            val frame = mainFrame()
            sent()
            agentValue(frame, "__mike.scroll(${JsonPrimitive(way)}, null)")
        } as? JsonObject
        settle(QUICK_SETTLE_MS)
        WebAction("scroll to ${result?.get("scrollY")?.jsonPrimitive?.intOrNull ?: 0} of ${result?.get("scrollMax")?.jsonPrimitive?.intOrNull ?: 0}")
    }

    /** Wait for text, a selector or an address to appear (or, with [gone], to go). */
    suspend fun waitFor(text: String?, selector: String?, url: String?, gone: Boolean, timeoutMs: Long): WebAction = lock.withLock {
        val what = when {
            url != null -> "the address to contain \"$url\""
            selector != null -> "\"$selector\""
            text != null -> "the text \"$text\""
            else -> {
                delay(timeoutMs.coerceIn(0, MAX_PLAIN_WAIT_MS))
                return@withLock WebAction("wait ${timeoutMs.coerceIn(0, MAX_PLAIN_WAIT_MS)}ms")
            }
        }
        val budget = timeoutMs.coerceIn(200, MAX_WAIT_MS)
        val deadline = System.currentTimeMillis() + budget
        val condition = buildJsonObject {
            text?.let { put("text", it) }
            selector?.let { put("selector", it) }
        }
        while (true) {
            val present = if (url != null) {
                location().first.contains(url, ignoreCase = true)
            } else {
                readableFrames().any { frame ->
                    runCatching { agentValue(frame, "__mike.present($condition)") }.getOrNull()
                        ?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
                }
            }
            if (present != gone) {
                // What the wait saw must come from a page the browser may read.
                if (url == null) guardRead()
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                throw WebFailure(
                    "wait_timeout",
                    "Waited ${budget}ms for $what to ${if (gone) "go" else "appear"}, and it did not.",
                    "Take a snapshot to see what the page shows instead.",
                )
            }
            delay(POLL_MS)
        }
        WebAction("wait: $what ${if (gone) "is gone" else "is there"}")
    }

    /** Run [source] in the page itself and return its value as JSON text. */
    suspend fun evaluate(source: String): String = lock.withLock {
        // Arbitrary JS can send requests or read secrets, so it needs a user approval.
        if (!confirm("Run page JavaScript at ${location().first}: ${source.take(400)}"))
            throw WebFailure("approval_denied", "The user did not approve this page script. Nothing ran.")
        sent()
        val result = cdp.call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", source)
                put("returnByValue", true)
                put("awaitPromise", true)
                put("timeout", EVAL_TIMEOUT_MS)
            },
            EVAL_TIMEOUT_MS + 2_000,
        )
        (result["exceptionDetails"] as? JsonObject)?.let { details ->
            val thrown = (details["exception"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull
                ?: details["text"]?.jsonPrimitive?.contentOrNull
                ?: "The script threw."
            throw WebFailure("eval_error", thrown.take(500), "The script may have changed the page before it threw. Inspect before retrying.", mayHaveActed = true)
        }
        val remote = result["result"] as? JsonObject
        val value = remote?.get("value")
        settle(QUICK_SETTLE_MS)
        enforcePolicy()
        when {
            value != null && value !is JsonNull -> SecretRedactor.redactUiText(value.toString())
            remote?.get("type")?.jsonPrimitive?.contentOrNull == "undefined" -> "undefined"
            else -> remote?.get("description")?.jsonPrimitive?.contentOrNull ?: "null"
        }
    }

    /** A PNG of what the page shows now, as base64, or null when the browser cannot capture it. */
    suspend fun screenshot(): String? = lock.withLock {
        guardRead()
        if (frameIds().any { !frameAllowed(it) }) throw WebFailure(
            "frame_blocked", "The screenshot includes a frame at a blocked address.", "Use the filtered snapshot instead.")
        runCatching {
            cdp.call("Page.captureScreenshot", buildJsonObject { put("format", "png") }, NAVIGATE_TIMEOUT_MS)["data"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // --- reading ------------------------------------------------------------

    /** The page as one line per thing on it, with a header that says where it is and what was left out. */
    suspend fun snapshot(query: SnapshotQuery = SnapshotQuery()): String = lock.withLock {
        guardRead()
        renderSnapshot(query)
    }

    /** The page as reading text, paged by character [offset]. */
    suspend fun read(offset: Int, maxChars: Int): String = lock.withLock {
        guardRead()
        val page = agentValue(mainFrame(), "__mike.read()") as? JsonObject
        val text = page?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            .lineSequence().joinToString("\n") { SecretRedactor.redactUiText(it) }
        val from = offset.coerceIn(0, text.length)
        val budget = maxChars.coerceIn(1_000, SnapshotQuery.MAX_CHARS)
        var to = (from + budget).coerceAtMost(text.length)
        // Stop on a line, so a page boundary never cuts a sentence in two.
        if (to < text.length) text.lastIndexOf('\n', to).takeIf { it > from }?.let { to = it }
        buildString {
            appendHeader(location())
            append("text: chars ").append(from).append('-').append(to).append(" of ").append(text.length)
            if (to < text.length) append("; next offset=").append(to)
            append('\n').append('\n').append(text, from, to)
        }
    }

    private suspend fun renderSnapshot(query: SnapshotQuery): String {
        val lines = mutableListOf<String>()
        var scrollLine: String? = null
        frameIds().take(MAX_FRAMES).forEach { frameId ->
            val index = frameIndex(frameId)
            if (index > 0 && !frameAllowed(frameId)) {
                // A frame at an address the browser does not open is never read.
                lines += "frame f$index (blocked address, not read)"
                return@forEach
            }
            val page = try {
                agentValue(frameId, "__mike.snapshot(${query.interactiveOnly})") as? JsonObject
                    ?: throw WebFailure("snapshot_failed", "The page helper returned no snapshot.")
            } catch (failure: Exception) {
                if (index == 0 || failure is kotlinx.coroutines.CancellationException) throw failure
                null
            }
            val frameLines = (page?.get("lines") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            if (index == 0) {
                scrollLine = "scroll: ${page?.get("scrollY")?.jsonPrimitive?.intOrNull ?: 0} of " +
                    "${page?.get("scrollMax")?.jsonPrimitive?.intOrNull ?: 0}"
                lines += frameLines
            } else if (page == null) {
                lines += "frame f$index (not readable)"
            } else if (frameLines.isNotEmpty()) {
                lines += "frame f$index ${page["url"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)}"
                // Inside a frame a ref carries the frame, so it resolves there.
                lines += frameLines.map { line -> REF_IN_LINE.replace(line) { "${it.groupValues[1]} [f${index}e${it.groupValues[2]}]" } }
            }
        }
        val safe = lines.map { SecretRedactor.redactUiText(it) }
        val matched = query.filter?.let { needle -> safe.filter { it.contains(needle, ignoreCase = true) } } ?: safe
        val where = location()
        val digest = digest(where.first, matched, query)
        revision += 1
        val body = StringBuilder()
        body.appendHeader(where)
        scrollLine?.let { body.append(it).append('\n') }
        if (!query.force && digest == lastDigest) {
            body.append("snapshot: unchanged since rev ").append(lastRevision)
                .append(" (").append(matched.size).append(" lines). Reuse those refs, or pass force=true to resend them.")
            return body.toString()
        }
        val from = query.offset.coerceIn(0, matched.size)
        val budget = query.maxChars.coerceIn(1_000, SnapshotQuery.MAX_CHARS)
        var to = from
        var used = 0
        while (to < matched.size && (to == from || used + matched[to].length + 1 <= budget)) {
            used += matched[to].length + 1
            to += 1
        }
        body.append("snapshot: lines ").append(if (matched.isEmpty()) 0 else from + 1).append('-').append(to)
            .append(" of ").append(matched.size).append(" (rev ").append(revision).append(')')
        if (query.filter != null) body.append(", filter \"").append(query.filter).append("\" of ").append(safe.size)
        if (to < matched.size) body.append("; next offset=").append(to)
        body.append('\n')
        for (i in from until to) body.append(matched[i]).append('\n')
        lastDigest = digest
        lastRevision = revision
        return body.toString().trimEnd()
    }

    // --- the page side -------------------------------------------------------

    private data class Found(val frameId: String, val frameIndex: Int, val ref: Long)

    private data class Info(
        val tag: String,
        val role: String,
        val name: String,
        val password: Boolean,
        val card: Boolean,
        val disabled: Boolean,
        val editable: Boolean,
        val valueOnly: Boolean,
        val contentEditable: Boolean,
        val confirmation: Boolean,
        val approvalToken: String,
    ) {
        fun label(found: Found): String =
            "$role \"${SecretRedactor.redactUiText(name).take(60)}\" [${if (found.frameIndex > 0) "f${found.frameIndex}" else ""}e${found.ref}]"
    }

    private suspend fun resolve(target: WebTarget): Found {
        if (target.isEmpty) {
            throw WebFailure("no_target", "This operation needs an element.", "Name it by ref, by text (its visible label) or by selector.")
        }
        target.ref?.let { raw ->
            frameIds()
            val match = REF.matchEntire(raw.trim())
                ?: throw WebFailure("bad_ref", "\"$raw\" is not a ref.", "A ref looks like e12; take it from a snapshot.")
            val index = match.groupValues[1].toIntOrNull() ?: 0
            val frameId = frames.getOrNull(index) ?: throw stale(raw)
            if (index > 0 && !frameAllowed(frameId)) throw blockedFrame(raw)
            val found = runCatching { agentValue(frameId, "__mike.find({ref: ${match.groupValues[2]}})") as? JsonObject }.getOrNull()
            val ref = found?.get("ref")?.jsonPrimitive?.longOrNull ?: throw stale(raw)
            return Found(frameId, index, ref)
        }
        val wanted = buildJsonObject {
            target.selector?.let { put("selector", it) }
            target.text?.let { put("text", it) }
            target.role?.let { put("role", it) }
            if (target.exact) put("exact", true)
        }
        val matches = mutableListOf<Found>()
        for (frameId in readableFrames()) {
            val found = agentValue(frameId, "__mike.find($wanted)") as? JsonObject ?: continue
            if (found["error"]?.jsonPrimitive?.contentOrNull == "bad_selector") {
                throw WebFailure("bad_selector", "\"${target.selector}\" is not a valid CSS selector.")
            }
            val ref = found["ref"]?.jsonPrimitive?.longOrNull ?: continue
            if ((found["count"]?.jsonPrimitive?.intOrNull ?: 0) > 1)
                throw WebFailure("ambiguous_target", "More than one element matches $target.", "Use a snapshot ref or a more specific selector.")
            matches += Found(frameId, frameIndex(frameId), ref)
        }
        if (matches.size > 1) throw WebFailure("ambiguous_target", "More than one frame matches $target.", "Use a snapshot ref.")
        matches.singleOrNull()?.let { return it }
        throw WebFailure(
            "not_found",
            "Nothing on the page matches $target.",
            "Take a snapshot and use a ref from it, or wait for the page to finish changing.",
        )
    }

    private fun stale(ref: String) = WebFailure(
        "stale_ref",
        "$ref is no longer on the page.",
        "The page changed. Take a snapshot and use a ref from it.",
    )

    private suspend fun info(found: Found): Info {
        val info = agentValue(found.frameId, "__mike.info(${found.ref})") as? JsonObject ?: throw stale("e${found.ref}")
        fun flag(key: String) = info[key]?.jsonPrimitive?.booleanOrNull == true
        fun text(key: String) = info[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Info(
            tag = text("tag"),
            role = text("role"),
            name = text("name"),
            password = flag("password"),
            card = flag("card"),
            disabled = flag("disabled"),
            editable = flag("editable"),
            valueOnly = flag("valueOnly"),
            contentEditable = text("tag") != "input" && text("tag") != "textarea",
            confirmation = flag("confirmation"),
            approvalToken = text("approvalToken"),
        )
    }

    private fun blockedFrame(ref: String) = WebFailure(
        "frame_blocked",
        "$ref is inside a frame at an address the browser does not open. Nothing was done.",
        "Work with the rest of the page, or tell the user this part needs their own browser.",
    )

    private suspend fun authorize(info: Info) {
        if (info.confirmation && !confirm("At ${location().first}: activate ${info.role} ${info.name}"))
            throw WebFailure("approval_denied", "The user did not approve this action. Nothing was clicked.")
    }

    /**
     * Whether Enter or Space on what has the focus in [frameId] may go ahead:
     * true when it needs no approval, or the user approved it.
     */
    private suspend fun submitAllowed(frameId: String): Boolean {
        val focus = focusedControl()
        val token = agentValue(focus.frameId, "__mike.focusToken()")
        val required = agentValue(focus.frameId, "__mike.submitNeedsApproval()") as? JsonPrimitive
        val allowed = required?.booleanOrNull == false || confirm("Submit or activate the focused control at ${location().first}")
        if (!allowed) return false
        if (focusedControl() != focus || agentValue(focus.frameId, "__mike.focusToken()") != token)
            throw WebFailure("focus_changed", "The focused action changed during approval. No key was sent.")
        return true
    }

    /**
     * Fail, having pressed nothing, unless a press at the element's middle
     * would reach it. Something covering it is scrolled past once (a sticky
     * header), then named, so the caller can dismiss it.
     */
    private suspend fun reachable(found: Found, info: Info) {
        fun check(value: JsonElement?): JsonObject? = value as? JsonObject
        var hit = check(agentValue(found.frameId, "__mike.hitCheck(${found.ref})"))
        val error = hit?.get("error")?.jsonPrimitive?.contentOrNull
        if (error == "offscreen") {
            sent()
            agentValue(found.frameId, "__mike.scroll(null, ${found.ref})")
            hit = check(agentValue(found.frameId, "__mike.hitCheck(${found.ref})"))
        }
        when (hit?.get("error")?.jsonPrimitive?.contentOrNull) {
            "stale_ref" -> throw stale(info.label(found))
            "no_box", "offscreen" -> throw WebFailure(
                "not_visible",
                "${info.label(found)} is not in the page's visible area. Nothing was clicked.",
                "Scroll to it, or take a snapshot to see what the page shows.",
            )
        }
        if (hit?.get("ok")?.jsonPrimitive?.booleanOrNull == true && globallyReachable(found)) return
        val blocker = hit?.get("blockedBy")?.jsonPrimitive?.contentOrNull ?: "another frame or page element"
        val ref = hit?.get("ref")?.jsonPrimitive?.longOrNull
        val frame = if (found.frameIndex > 0) "f${found.frameIndex}" else ""
        throw WebFailure(
            "obscured",
            "${info.label(found)} is covered by ${SecretRedactor.redactUiText(blocker)}" +
                (ref?.let { " [${frame}e$it]" } ?: "") + ", which would take the press. Nothing was clicked.",
            "Dismiss what covers it (a cookie banner, a dialog or a menu), then click again.",
        )
    }

    /** Child frames the browser may read and act in; a frame at a blocked address is left alone. */
    private suspend fun readableFrames(): List<String> =
        frameIds().take(MAX_FRAMES).filterIndexed { index, frameId -> index == 0 || frameAllowed(frameId) }

    /**
     * A child frame's address passes the URL policy. Only web addresses are
     * judged: about:blank, about:srcdoc and their like belong to the page that
     * made them. A failed name lookup is not a block here; the WebView did
     * load the frame, and blanking the page on a DNS blip helps no one.
     */
    private suspend fun frameAllowed(frameId: String): Boolean {
        val url = frameUrls[frameId] ?: return true
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return true
        val decision = withContext(Dispatchers.IO) { policy.check(url) }
        return decision !is WebUrlPolicy.Decision.Blocked || decision.errorType == DNS_UNRESOLVED
    }

    /**
     * Before anything is read off the page: a page that reached a blocked
     * address by itself (a server redirect, a script) is closed, not read.
     */
    private suspend fun guardRead() = enforcePolicy(afterAction = false)

    private suspend fun currentValue(found: Found): String? =
        (agentValue(found.frameId, "__mike.valueOf(${found.ref})") as? JsonPrimitive)?.contentOrNull

    /** Check every embedding frame, not just its remembered local activeElement. */
    private suspend fun hasFocus(found: Found): Boolean {
        frameIds()
        if (agentValue(found.frameId, "__mike.isFocused(${found.ref})")?.jsonPrimitive?.booleanOrNull != true) return false
        var child = found.frameId
        while (true) {
            val parent = frameParents[child] ?: return child == frames.firstOrNull()
            val owner = cdp.call("DOM.getFrameOwner", buildJsonObject { put("frameId", child) })
            val backend = owner["backendNodeId"]?.jsonPrimitive?.longOrNull ?: return false
            if (!callOnNode(parent, backend, "function() { return __mike.focusedElement() === this; }")) return false
            child = parent
        }
    }

    private suspend fun focusedControl(): Found {
        for (frame in readableFrames().asReversed()) {
            val ref = agentValue(frame, "__mike.focusedRef()")?.jsonPrimitive?.longOrNull ?: continue
            val found = Found(frame, frameIndex(frame), ref)
            if (hasFocus(found)) return found
        }
        throw WebFailure("focus_unknown", "The focused control could not be identified. No key was sent.")
    }

    /** CDP's hit test starts at the top viewport, so a parent overlay cannot be missed. */
    private suspend fun globallyReachable(found: Found): Boolean {
        val point = centre(element(found)) ?: return false
        val hit = cdp.call("DOM.getNodeForLocation", buildJsonObject {
            put("x", kotlin.math.round(point.first).toInt())
            put("y", kotlin.math.round(point.second).toInt())
            put("includeUserAgentShadowDOM", true)
        })
        if (hit["frameId"]?.jsonPrimitive?.contentOrNull != found.frameId) return false
        val backend = hit["backendNodeId"]?.jsonPrimitive?.longOrNull ?: return false
        return callOnNode(found.frameId, backend, "function() { return __mike.acceptsHit(${found.ref}, this); }")
    }

    private suspend fun callOnNode(frame: String, backend: Long, function: String): Boolean {
        agentValue(frame, "true") // Ensure the helper exists in this document.
        val context = cdp.call("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frame); put("worldName", WORLD)
        })["executionContextId"] ?: return false
        val node = cdp.call("DOM.resolveNode", buildJsonObject {
            put("backendNodeId", backend); put("executionContextId", context)
        })["object"] as? JsonObject ?: return false
        val objectId = node["objectId"]?.jsonPrimitive?.contentOrNull ?: return false
        return try {
            val result = cdp.call("Runtime.callFunctionOn", buildJsonObject {
                put("objectId", objectId); put("functionDeclaration", function); put("returnByValue", true)
            })["result"] as? JsonObject
            result?.get("value")?.jsonPrimitive?.booleanOrNull == true
        } finally {
            runCatching { cdp.call("Runtime.releaseObject", buildJsonObject { put("objectId", objectId) }) }
        }
    }

    private suspend fun element(found: Found): String =
        agent(found.frameId, "__mike.element(${found.ref})", byValue = false)["objectId"]?.jsonPrimitive?.contentOrNull
            ?: throw stale("e${found.ref}")

    /** The middle of the element's first drawn box, in the page's own viewport. */
    private suspend fun centre(objectId: String): Pair<Double, Double>? {
        val quads = runCatching {
            cdp.call("DOM.getContentQuads", buildJsonObject { put("objectId", objectId) })["quads"] as? JsonArray
        }.getOrNull() ?: return null
        for (quad in quads) {
            val points = (quad as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: continue
            if (points.size != 8) continue
            val xs = listOf(points[0], points[2], points[4], points[6])
            val ys = listOf(points[1], points[3], points[5], points[7])
            if (xs.max() - xs.min() < 1 || ys.max() - ys.min() < 1) continue
            return xs.average() to ys.average()
        }
        return null
    }

    private suspend fun tap(x: Double, y: Double) {
        nativeTap?.let { it(x, y); return }
        when (pointer) {
            Pointer.TOUCH -> {
                cdp.call(
                    "Input.dispatchTouchEvent",
                    buildJsonObject {
                        put("type", "touchStart")
                        put("touchPoints", buildJsonArray { add(buildJsonObject { put("x", x); put("y", y) }) })
                    },
                )
                cdp.call(
                    "Input.dispatchTouchEvent",
                    buildJsonObject {
                        put("type", "touchEnd")
                        put("touchPoints", buildJsonArray { })
                    },
                )
            }
            Pointer.MOUSE -> for (type in listOf("mouseMoved", "mousePressed", "mouseReleased")) {
                cdp.call(
                    "Input.dispatchMouseEvent",
                    buildJsonObject {
                        put("type", type)
                        put("x", x)
                        put("y", y)
                        if (type != "mouseMoved") {
                            put("button", "left")
                            put("clickCount", 1)
                        }
                    },
                )
            }
        }
    }

    private suspend fun key(name: String) {
        val key = KEYS.getValue(name)
        for (type in listOf(if (key.text != null) "keyDown" else "rawKeyDown", "keyUp")) {
            cdp.call(
                "Input.dispatchKeyEvent",
                buildJsonObject {
                    put("type", type)
                    put("key", key.key)
                    put("code", key.code)
                    put("windowsVirtualKeyCode", key.virtualKey)
                    put("nativeVirtualKeyCode", key.virtualKey)
                    if (type == "keyDown") key.text?.let { put("text", it) }
                },
            )
        }
    }

    /**
     * Evaluate [expression] in the helper's isolated world of [frameId].
     *
     * The world is asked for by name on every call, which returns the one that
     * exists or makes it; a navigation throws the old one away, and the helper
     * is put back the first time it is found missing.
     */
    private suspend fun agent(frameId: String, expression: String, byValue: Boolean = true, timeoutMs: Long = CdpClient.DEFAULT_TIMEOUT_MS): JsonObject {
        repeat(3) {
            try {
                val context = cdp.call(
                    "Page.createIsolatedWorld",
                    buildJsonObject {
                        put("frameId", frameId)
                        put("worldName", WORLD)
                    },
                )["executionContextId"]?.jsonPrimitive?.intOrNull ?: throw WebFailure("frame_gone", "That frame is gone.")
                val result = cdp.call(
                    "Runtime.evaluate",
                    buildJsonObject {
                        put("expression", "(typeof __mike === 'undefined') ? '$MISSING' : ($expression)")
                        put("contextId", context)
                        put("returnByValue", byValue)
                        put("awaitPromise", true)
                    },
                    timeoutMs,
                )
                (result["exceptionDetails"] as? JsonObject)?.let { details ->
                    throw WebFailure(
                        "page_script_failed",
                        (details["exception"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull?.take(300)
                            ?: "The page helper failed.",
                    )
                }
                val remote = result["result"] as? JsonObject ?: JsonObject(emptyMap())
                if (remote["value"]?.let { it is JsonPrimitive && it.isString && it.content == MISSING } == true) {
                    // Each injection gets a ref range no other world, document,
                    // frame or connection in this process has used, so an old
                    // ref can only ever be stale, never name another element.
                    val base = NEXT_REF_BASE.addAndGet(REF_RANGE)
                    cdp.call(
                        "Runtime.evaluate",
                        buildJsonObject {
                            put("expression", "globalThis.__mikeRefStart = $base;\n$script")
                            put("contextId", context)
                        },
                    )
                    return@repeat
                }
                return remote
            } catch (failure: CdpException) {
                throw failure
            }
        }
        throw WebFailure(
            "page_busy",
            "The page changed while it was being read.",
            "Wait for it to finish loading, then try again.",
        )
    }

    private suspend fun agentValue(frameId: String, expression: String, timeoutMs: Long = CdpClient.DEFAULT_TIMEOUT_MS): JsonElement? =
        agent(frameId, expression, timeoutMs = timeoutMs)["value"]

    private suspend fun mainFrameId(): String =
        ((cdp.call("Page.getFrameTree")["frameTree"] as JsonObject)["frame"] as JsonObject)["id"]!!.jsonPrimitive.content

    private suspend fun mainFrame(): String = frameIds().first()

    /** Every frame of the page, the page itself first, each given its `f<n>` on first sight. */
    private suspend fun frameIds(): List<String> {
        val found = mutableListOf<String>()
        val urls = mutableMapOf<String, String>()
        frameParents.clear()
        fun collect(node: JsonObject) {
            val frame = node["frame"] as? JsonObject
            frame?.get("id")?.jsonPrimitive?.contentOrNull?.let { id ->
                found += id
                frame["parentId"]?.jsonPrimitive?.contentOrNull?.let { frameParents[id] = it }
                frame["url"]?.jsonPrimitive?.contentOrNull?.let { urls[id] = it }
            }
            (node["childFrames"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let(::collect) }
        }
        (cdp.call("Page.getFrameTree")["frameTree"] as? JsonObject)?.let(::collect)
        if (found.isNotEmpty() && found.first() != frames.firstOrNull()) {
            // The page itself was replaced: every earlier ref died with it.
            frames.clear()
        }
        for (id in found) if (id !in frames) frames += id
        frameUrls.clear()
        frameUrls.putAll(urls)
        return found
    }

    private fun frameIndex(frameId: String): Int = frames.indexOf(frameId).coerceAtLeast(0)

    // --- settling ------------------------------------------------------------

    private suspend fun readyState(): String? = runCatching {
        (cdp.call("Runtime.evaluate", buildJsonObject { put("expression", "document.readyState"); put("returnByValue", true) }, 2_000)
            ["result"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private suspend fun awaitLoad(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (readyState() == "complete") return true
            delay(POLL_MS)
        }
        return false
    }

    /**
     * Give the page a moment to finish reacting: a navigation the action
     * started, then the DOM going quiet. Bounded, because some pages never do.
     */
    private suspend fun settle(maxMs: Long) {
        val deadline = System.currentTimeMillis() + maxMs
        delay(50)
        while (true) {
            while (readyState() != "complete" && System.currentTimeMillis() < deadline) delay(POLL_MS)
            val left = deadline - System.currentTimeMillis()
            if (left < 100) return
            val quiet = runCatching {
                agentValue(mainFrame(), "__mike.settle($QUIET_MS, $left)", timeoutMs = left + 2_000)
            }.isSuccess
            // A failure here is the page navigating under the wait; go round again.
            if (quiet) return
        }
    }

    /**
     * A page that took itself somewhere the browser does not go is taken back
     * out. [afterAction] says whether the agent's own action may have caused
     * it; before a read it did not.
     *
     * A failed name lookup does not count here: the WebView already loaded the
     * page, and blanking it because this check could not resolve the name a
     * moment later (offline, a DNS blip) would throw away the user's page.
     */
    private suspend fun enforcePolicy(afterAction: Boolean = true) {
        val url = location().first
        if (url.isEmpty() || url == "about:blank") return
        val decision = withContext(Dispatchers.IO) { policy.check(url) }
        if (decision is WebUrlPolicy.Decision.Blocked && decision.errorType != DNS_UNRESOLVED) {
            runCatching { cdp.call("Page.navigate", buildJsonObject { put("url", "about:blank") }) }
            throw WebFailure(
                decision.errorType,
                "The page went to an address the browser does not open, so it was closed without being read. ${decision.message}",
                decision.remedy,
                mayHaveActed = afterAction,
            )
        }
    }

    private fun answerDialog(params: JsonObject) {
        val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "alert"
        val message = params["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // An alert only informs and leaving a page is the agent's own doing;
        // a question is the user's to answer, so it is declined.
        val accept = type == "alert" || type == "beforeunload"
        note("The page showed a $type dialog \"${message.take(120)}\", which was ${if (accept) "accepted" else "declined"}.")
        scope.launch {
            runCatching { cdp.call("Page.handleJavaScriptDialog", buildJsonObject { put("accept", accept) }) }
        }
    }

    /** Notes gathered since the last reply, each said once. */
    fun drainNotes(): List<String> = generateSequence { notes.poll() }.toList()

    private fun StringBuilder.appendHeader(where: Pair<String, String>) {
        append("url: ").append(where.first).append('\n')
        append("title: ").append(SecretRedactor.redactUiText(where.second).take(160)).append('\n')
    }

    private fun digest(url: String, lines: List<String>, query: SnapshotQuery): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$url\n${query.filter}\n${query.interactiveOnly}\n${query.offset}\n${query.maxChars}\n".toByteArray())
        lines.forEach { md.update(it.toByteArray()); md.update(10) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sameText(actual: String?, wanted: String): Boolean =
        actual != null && actual.replace(WHITESPACE, " ").trim() == wanted.replace(WHITESPACE, " ").trim()

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    override fun close() {
        runCatching { events?.close() }
        scope.cancel()
        cdp.close()
    }

    private data class Key(val key: String, val code: String, val virtualKey: Int, val text: String? = null)

    companion object {
        const val MAX_WAIT_MS = 30_000L
        private const val DNS_UNRESOLVED = "dns_unresolved"

        /** Refs one helper injection may hand out; far above any page's element count. */
        private const val REF_RANGE = 10_000_000L

        /** Process-wide, so two sessions on one page (a reconnect) never share a ref range. */
        private val NEXT_REF_BASE = java.util.concurrent.atomic.AtomicLong(0)
        private const val MAX_PLAIN_WAIT_MS = 10_000L
        private const val WORLD = "hey_mike"
        private const val MISSING = "__mike_missing__"
        private const val NAVIGATE_TIMEOUT_MS = 30_000L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val SETTLE_MS = 3_000L
        private const val QUICK_SETTLE_MS = 800L
        private const val QUIET_MS = 300L
        private const val POLL_MS = 150L
        private const val EVAL_TIMEOUT_MS = 10_000L
        private const val MAX_FRAMES = 12
        private const val MAX_NOTES = 20

        private val REF = Regex("^(?:f(\\d+))?e?(\\d+)$")
        private val REF_IN_LINE = Regex("^(\\w+ \"(?:[^\"\\\\]|\\\\.)*\") \\[e(\\d+)]")
        private val WHITESPACE = Regex("\\s+")
        private val SCROLL_DIRECTIONS = setOf("up", "down", "top", "bottom")

        private val KEYS = mapOf(
            "Enter" to Key("Enter", "Enter", 13, "\r"),
            "Tab" to Key("Tab", "Tab", 9),
            "Escape" to Key("Escape", "Escape", 27),
            "Backspace" to Key("Backspace", "Backspace", 8),
            "Delete" to Key("Delete", "Delete", 46),
            "Space" to Key(" ", "Space", 32, " "),
            "ArrowUp" to Key("ArrowUp", "ArrowUp", 38),
            "ArrowDown" to Key("ArrowDown", "ArrowDown", 40),
            "ArrowLeft" to Key("ArrowLeft", "ArrowLeft", 37),
            "ArrowRight" to Key("ArrowRight", "ArrowRight", 39),
            "PageUp" to Key("PageUp", "PageUp", 33),
            "PageDown" to Key("PageDown", "PageDown", 34),
            "Home" to Key("Home", "Home", 36),
            "End" to Key("End", "End", 35),
        )
    }
}
