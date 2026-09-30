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

package dev.androidagent.engineclaude

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AgentEngine
import dev.androidagent.core.AgentModel
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.DeviceCapabilities
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.SecretRedactor
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.core.UsageLimit
import dev.androidagent.engineclaude.ClaudeProtocol.string
import dev.androidagent.engineclaude.ClaudeStreamMapper.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Mike on the official `claude` CLI, driven in headless stream-json mode.
 *
 * One `claude -p` process per open chat, started lazily by the first turn and
 * restarted with `--resume` when the chat's model, effort or tool list
 * changes. The phone tools reach the model over a loopback MCP server per
 * process; a `tools/call` becomes [EngineEvent.ToolCall] and waits for
 * [answerTool], so the coordinator's approvals, overlay and stop flow are the
 * same as for Codex.
 *
 * Compliance: the engine only passes `claude` arguments. The host owns the
 * binary, its environment and the proxy. Sign-in is `claude auth login` with
 * the pasted code relayed to that process only; sign-in state comes only from
 * `claude auth status`. Nothing under `CLAUDE_CONFIG_DIR` is read except the
 * app-installed `skills/<name>/SKILL.md` files, and login output is never
 * logged.
 */
class ClaudeCodeEngine(
    private val host: ClaudeProcessHost,
    private val toolServers: McpToolServerFactory,
    private val interruptGraceMs: Long = 3_000,
    private val maxLiveChats: Int = 2,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : AgentEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: Flow<EngineEvent> = stream.asSharedFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val chats = ConcurrentHashMap<String, Chat>()
    private val chatsLock = Mutex()
    private val pendingTools = ConcurrentHashMap<String, PendingTool>()
    private val modelLock = Mutex()
    @Volatile private var models: List<AgentModel>? = null
    @Volatile private var limits: List<UsageLimit>? = null
    private val loginLock = Mutex()
    @Volatile private var loginProcess: Process? = null
    @Volatile private var accountCache: Pair<Long, AccountStatus>? = null

    private class PendingTool(val threadId: String, val turnId: String, val result: CompletableDeferred<ToolResult>)

    private fun installed(): Boolean = host.status.value.phase.let { it == RuntimePhase.READY || it == RuntimePhase.RUNNING }

    /** Checks only. The binary is never downloaded from here: that needs the user's consent in Settings. */
    override suspend fun connect() {
        check(installed()) { DOWNLOAD_FIRST }
    }

    // ---- account ----------------------------------------------------------------

    override suspend fun account(): AccountStatus {
        if (!installed()) return AccountStatus(false, DOWNLOAD_FIRST)
        accountCache?.let { (at, status) -> if (status.signedIn && System.nanoTime() - at < ACCOUNT_CACHE_NS) return status }
        val status = ClaudeProtocol.parseAuthStatus(runCommand(ClaudeProtocol.AUTH_STATUS_ARGS, COMMAND_TIMEOUT_MS))
        accountCache = System.nanoTime() to status
        return status
    }

    override suspend fun refreshUsage() {
        limits?.let { stream.emit(EngineEvent.UsageChanged(null, limits = it)) }
    }

    /**
     * Start `claude auth login` and return its sign-in link. The process
     * stays alive, waiting for the code the user pastes back through
     * [completeLogin].
     */
    override suspend fun login(): AccountStatus {
        connect()
        return loginLock.withLock {
            loginProcess?.destroyForcibly()
            loginProcess = null
            accountCache = null
            val process = host.start(ClaudeProtocol.AUTH_LOGIN_ARGS, home(), emptyMap())
            val url = CompletableDeferred<String>()
            scope.launch { readLoginOutput(process.inputStream, url) }
            scope.launch { drain(process.errorStream) }
            val found = try {
                withTimeoutOrNull(LOGIN_URL_TIMEOUT_MS) { url.await() }
            } catch (error: IllegalStateException) {
                null
            }
            if (found == null) {
                process.destroyForcibly()
                error("Claude did not give a sign-in link. Try again.")
            }
            loginProcess = process
            AccountStatus(false, ClaudeProtocol.SIGN_IN_LABEL, loginUrl = found)
        }
    }

    /** Write the pasted code to the waiting login process, then read the result from `claude auth status`. */
    override suspend fun completeLogin(code: String): AccountStatus {
        val clean = code.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_CODE_CHARS && clean.none { it.isISOControl() }) {
            "Paste the code from the Claude sign-in page."
        }
        loginLock.withLock {
            val process = loginProcess?.takeIf { it.isAlive } ?: error("The sign-in expired. Start it again.")
            withContext(Dispatchers.IO) {
                try {
                    process.outputStream.write((clean + "\n").toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                } catch (error: IOException) {
                    throw IllegalStateException("The sign-in expired. Start it again.")
                }
                if (!process.waitFor(LOGIN_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            loginProcess = null
        }
        accountCache = null
        val status = account().let { if (it.signedIn) it else AccountStatus(false, "Sign-in did not finish. Try again.") }
        stream.emit(EngineEvent.AccountChanged(status))
        return status
    }

    override suspend fun logout() {
        connect()
        runCommand(ClaudeProtocol.AUTH_LOGOUT_ARGS, COMMAND_TIMEOUT_MS)
        accountCache = null
        chatsLock.withLock { chats.values.forEach { it.shutdown() } }
        stream.emit(EngineEvent.AccountChanged(AccountStatus(false, ClaudeProtocol.SIGN_IN_LABEL)))
    }

    // ---- catalogs ---------------------------------------------------------------

    override suspend fun models(): List<String> = modelCatalog().map { it.id }

    /** The CLI's own list from `initialize`, read once; the fixed aliases until then. */
    override suspend fun modelCatalog(): List<AgentModel> {
        models?.let { return it }
        if (!installed()) return ClaudeProtocol.FALLBACK_MODELS
        return modelLock.withLock {
            models ?: runCatching { probeModels() }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { models = it }
                ?: ClaudeProtocol.FALLBACK_MODELS
        }
    }

    /** The skills the app installed under `<claudeHome>/.claude/skills`. Only `SKILL.md` files are read. */
    override suspend fun skillCatalog(workspace: File, forceReload: Boolean): List<AgentSkill> = withContext(Dispatchers.IO) {
        val root = File(host.homeDirectory, ".claude/skills")
        val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return@withContext emptyList()
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val file = File(dir, "SKILL.md")
            val inside = runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            if (!inside || !file.isFile || file.length() > MAX_SKILL_BYTES) return@mapNotNull null
            runCatching { ClaudeProtocol.parseSkill(file, file.readText()) }.getOrNull()
        }.distinctBy { it.name }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    // ---- chats ------------------------------------------------------------------

    /**
     * Register the chat and return its Claude session id. A new chat gets a
     * fresh UUID. The process starts with the first turn, which knows the
     * reasoning effort, so a chat is not started twice.
     */
    override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
        connect()
        val known = ClaudeProtocol.isSessionId(threadId)
        val id = if (known) threadId!! else UUID.randomUUID().toString()
        val dir = workspace.absoluteFile
        val wantedModel = ClaudeProtocol.normalizeModel(model, models.orEmpty().map { it.id })
        return chatsLock.withLock {
            val existing = chats[id]
            if (existing != null && existing.workspace != dir) {
                existing.shutdown()
                chats.remove(id)
            }
            val chat = chats.getOrPut(id) { Chat(id, dir, resumable = known) }
            chat.lock.withLock {
                chat.model = wantedModel
                chat.tools = tools
                chat.touch()
            }
            id
        }
    }

    override suspend fun startTurn(threadId: String, prompt: String, images: List<File>): String =
        startTurn(threadId, prompt, images, null)

    override suspend fun startTurn(threadId: String, prompt: String, images: List<File>, reasoningEffort: String?): String =
        startTurn(threadId, prompt, images, reasoningEffort, null)

    override suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File>,
        reasoningEffort: String?,
        skill: AgentSkill?,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill, AdbStatus())

    override suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File>,
        reasoningEffort: String?,
        skill: AgentSkill?,
        adbStatus: AdbStatus,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill, DeviceCapabilities(adbStatus = adbStatus))

    override suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File>,
        reasoningEffort: String?,
        skill: AgentSkill?,
        capabilities: DeviceCapabilities,
    ): String = startTurn(threadId, prompt, images, reasoningEffort, skill, capabilities, planModel = null)

    override suspend fun startTurn(
        threadId: String,
        prompt: String,
        images: List<File>,
        reasoningEffort: String?,
        skill: AgentSkill?,
        capabilities: DeviceCapabilities,
        planModel: String?,
    ): String {
        val chat = chats[threadId] ?: error("This chat is not open. Send the message again.")
        return chat.lock.withLock {
            chat.effort = ClaudeProtocol.normalizeEffort(reasoningEffort)
            chat.ensureRunning()
            chat.touch()
            val content = withContext(Dispatchers.IO) {
                ClaudeProtocol.turnContent(prompt, images, skill?.name, capabilities, !planModel.isNullOrBlank(), clock())
            }
            val turnId = UUID.randomUUID().toString()
            val frame = UUID.randomUUID().toString()
            val turn = synchronized(chat.state) { chat.mapper.begin(turnId, frame) }
            stream.emit(EngineEvent.TurnStarted(threadId, turnId))
            try {
                chat.write(ClaudeProtocol.userFrame(frame, content))
            } catch (error: Exception) {
                synchronized(chat.state) { chat.mapper.abandon(turn) }
                throw error
            }
            turnId
        }
    }

    /** Write `/compact` and wait for its `result`. */
    override suspend fun compact(threadId: String) {
        val chat = chats[threadId] ?: error("Send a message in this chat before compacting it.")
        chat.lock.withLock {
            chat.ensureRunning()
            val frame = UUID.randomUUID().toString()
            val turn = synchronized(chat.state) { chat.mapper.begin("compact-$frame", frame, internal = true) }
            try {
                chat.write(ClaudeProtocol.userFrame(frame, JsonArray(listOf(ClaudeProtocol.textBlock("/compact")))))
            } catch (error: Exception) {
                synchronized(chat.state) { chat.mapper.abandon(turn) }
                throw error
            }
            val status = withTimeoutOrNull(COMPACT_TIMEOUT_MS) { turn.done.await() }
            if (status == null) {
                chat.kill()
                chat.dispatch(synchronized(chat.state) { chat.mapper.end(turn, "failed", "Compacting took too long.") })
                error("Compacting took too long.")
            }
            check(status == "completed") { turn.error ?: "Claude could not compact this chat." }
        }
    }

    /** A new user message inside the running turn. Fails when the turn has already ended. */
    override suspend fun steer(threadId: String, turnId: String, prompt: String) {
        val chat = chats[threadId] ?: error(TURN_OVER)
        val frame = UUID.randomUUID().toString()
        synchronized(chat.state) {
            val turn = chat.mapper.active
            check(turn != null && turn.turnId == turnId && !turn.ended && !turn.interruptRequested && !turn.internal) { TURN_OVER }
            chat.mapper.addFrame(turn, frame)
        }
        chat.write(
            ClaudeProtocol.userFrame(frame, JsonArray(listOf(ClaudeProtocol.textBlock(prompt))), ClaudeProtocol.STEER_PRIORITY),
        )
    }

    /**
     * Revoke the turn's tool calls, then ask the CLI to stop. Returns at once:
     * if no `result` comes within the grace period, the process is killed
     * (SIGKILL, never SIGTERM first) and the turn ends as interrupted.
     */
    override suspend fun interrupt(threadId: String, turnId: String) {
        val chat = chats[threadId] ?: return
        val turn = synchronized(chat.state) {
            chat.mapper.active?.takeIf { it.turnId == turnId && !it.ended }?.also { it.interruptRequested = true }
        } ?: return
        failPendingTools(threadId, turnId)
        val sent = runCatching { chat.write(ClaudeProtocol.interruptRequest("interrupt-${UUID.randomUUID()}")) }.isSuccess
        scope.launch {
            val ended = sent && withTimeoutOrNull(interruptGraceMs) { turn.done.await() } != null
            if (!ended) {
                chat.kill()
                chat.dispatch(synchronized(chat.state) { chat.mapper.end(turn, "interrupted", null) })
            }
        }
    }

    override suspend fun answerTool(requestId: String, result: ToolResult) {
        pendingTools[requestId]?.result?.complete(result)
    }

    /** `dontAsk` and the allow rules never prompt, so there is nothing to answer. */
    override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit

    override suspend fun close() {
        val all = chatsLock.withLock { chats.values.toList().also { chats.clear() } }
        all.forEach { runCatching { it.shutdown() } }
        pendingTools.values.forEach { it.result.complete(STOPPED) }
        pendingTools.clear()
        loginProcess?.destroyForcibly()
        loginProcess = null
        runCatching { host.stopAll() }
    }

    private fun failPendingTools(threadId: String, turnId: String) {
        pendingTools.values.filter { it.threadId == threadId && it.turnId == turnId }.forEach { it.result.complete(STOPPED) }
    }

    private fun home(): File = host.homeDirectory.apply { mkdirs() }

    /** Stop idle processes of other chats so at most [maxLiveChats] run. Each one is a full CLI in memory. */
    private suspend fun trimLive(keep: Chat) {
        val live = chats.values.filter { it !== keep && it.alive }.sortedByDescending { it.lastUsed }
        live.drop((maxLiveChats - 1).coerceAtLeast(0)).forEach { other ->
            if (other.lock.tryLock()) {
                try {
                    if (other.idle()) other.stopProcess()
                } finally {
                    other.lock.unlock()
                }
            }
        }
    }

    // ---- one chat process ---------------------------------------------------------

    private data class RunningConfig(val model: String, val effort: String?, val toolNames: List<String>)

    private class ChatStartException(message: String, val notFound: Boolean) : IllegalStateException(message)

    private inner class Chat(val threadId: String, val workspace: File, @Volatile var resumable: Boolean) {
        val lock = Mutex()
        val state = Any()
        val mapper = ClaudeStreamMapper(threadId, workspace)
        @Volatile var model: String = ClaudeProtocol.DEFAULT_MODEL
        @Volatile var effort: String? = null
        @Volatile var tools: List<ToolDefinition> = emptyList()
        @Volatile var lastUsed: Long = System.nanoTime()
        @Volatile private var process: Process? = null
        @Volatile private var writer: BufferedWriter? = null
        @Volatile private var server: McpToolServer? = null
        @Volatile private var stderrJob: Job? = null
        @Volatile private var running: RunningConfig? = null
        @Volatile private var readerEnded = CompletableDeferred<Unit>().apply { complete(Unit) }
        private val writeLock = Mutex()
        private val controls = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val stderrLock = Any()
        private val stderrTail = ArrayDeque<String>()

        val alive: Boolean get() = process?.isAlive == true

        fun touch() { lastUsed = System.nanoTime() }

        fun idle(): Boolean = synchronized(state) { mapper.active == null }

        private fun dir(): File = File(host.homeDirectory, "mike-chats/$threadId")

        /** Start or restart the process when it is gone or its model, effort or tools changed. Caller holds [lock]. */
        suspend fun ensureRunning() {
            val wanted = RunningConfig(model, effort, tools.map { it.name })
            if (alive && running == wanted) return
            check(idle()) { "A turn is already running in this chat." }
            stopProcess()
            trimLive(this)
            if (resumable) {
                try {
                    start(resume = true)
                } catch (error: ChatStartException) {
                    // A session that never got a message has no file to resume.
                    // Starting it under the same id keeps the chat's id stable.
                    if (!error.notFound) throw error
                    start(resume = false)
                }
            } else {
                start(resume = false)
            }
            resumable = true
            running = wanted
        }

        private suspend fun start(resume: Boolean) = withContext(Dispatchers.IO) {
            val dir = dir().apply { mkdirs() }
            val promptFile = File(dir, "system-prompt.md")
            promptFile.writeText(ClaudeInstructions.systemPrompt(readAgentsMd()))
            val toolServer = toolServers.create(ClaudeProtocol.MCP_SERVER_NAME, { tools }) { name, arguments ->
                onToolCall(name, arguments)
            }
            server = toolServer
            try {
                val mcpFile = File(dir, MCP_CONFIG_FILE)
                writePrivate(mcpFile, toolServer.start())
                val args = ClaudeProtocol.chatArgs(threadId, resume, model, effort, mcpFile.absolutePath, promptFile.absolutePath)
                attach(host.start(args, workspace.apply { mkdirs() }, ClaudeProtocol.CHAT_ENV))
                val reply = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { control("initialize") }
                    ?: error("Claude did not start in time")
                ClaudeProtocol.parseModels(reply).takeIf { it.isNotEmpty() }?.let { models = it }
            } catch (error: CancellationException) {
                stopProcess()
                throw error
            } catch (error: Exception) {
                stderrJob?.let { job -> withTimeoutOrNull(1_000) { job.join() } }
                val tail = stderrSnapshot(5)
                stopProcess()
                val detail = listOfNotNull(error.message, tail.takeIf { it.isNotBlank() }).joinToString(" | ")
                throw ChatStartException(
                    SecretRedactor.redact("Claude could not open this chat: $detail"),
                    notFound = tail.contains(NOT_FOUND, ignoreCase = true),
                )
            }
        }

        private fun readAgentsMd(): String? =
            File(workspace, "AGENTS.md").takeIf { it.isFile && it.length() <= MAX_AGENTS_MD_BYTES }?.let { runCatching { it.readText() }.getOrNull() }

        private fun attach(started: Process) {
            synchronized(stderrLock) { stderrTail.clear() }
            readerEnded = CompletableDeferred()
            process = started
            writer = started.outputStream.bufferedWriter(Charsets.UTF_8)
            stderrJob = scope.launch {
                try {
                    started.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(::recordStderr) }
                } catch (_: IOException) {
                }
            }
            val ended = readerEnded
            scope.launch { readLoop(started, ended) }
        }

        private suspend fun readLoop(started: Process, ended: CompletableDeferred<Unit>) {
            try {
                started.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // Stdout is never logged: a bad line is dropped, not printed.
                        val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                        dispatch(synchronized(state) { mapper.map(message) })
                    }
                }
            } catch (_: IOException) {
            } finally {
                withContext(NonCancellable) {
                    ended.complete(Unit)
                    stderrJob?.let { job -> withTimeoutOrNull(500) { job.join() } }
                    if (process === started) {
                        failControls()
                        dispatch(synchronized(state) { mapper.processEnded(stderrSnapshot(3)) })
                    }
                }
            }
        }

        suspend fun dispatch(signals: List<Signal>) {
            for (signal in signals) {
                when (signal) {
                    is Signal.Emit -> {
                        val event = signal.event
                        if (event is EngineEvent.UsageChanged && event.limits != null) limits = event.limits
                        stream.emit(event)
                    }
                    is Signal.ControlResponse -> controls.remove(signal.requestId)?.let { waiter ->
                        if (signal.error != null) waiter.completeExceptionally(IllegalStateException(signal.error))
                        else waiter.complete(signal.response ?: JsonObject(emptyMap()))
                    }
                    is Signal.ControlRequest -> runCatching { write(ClaudeProtocol.answerControlRequest(signal.requestId, signal.request)) }
                    is Signal.Init -> checkInit(signal.message)
                    is Signal.Ended -> failPendingTools(threadId, signal.turn.turnId)
                }
            }
        }

        private fun checkInit(message: JsonObject) {
            val version = message.string("claude_code_version")
            if (version.isNotBlank() && version != ClaudeProtocol.PINNED_VERSION) {
                System.err.println("ClaudeCodeEngine: claude $version is running, expected ${ClaudeProtocol.PINNED_VERSION}")
            }
            val mike = (message["mcp_servers"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("name") == ClaudeProtocol.MCP_SERVER_NAME }
            if (mike?.string("status") != "connected") {
                System.err.println("ClaudeCodeEngine: phone tools are not connected (${mike?.string("status") ?: "missing"})")
            }
        }

        private suspend fun control(subtype: String): JsonObject {
            val id = "req-${UUID.randomUUID()}"
            val waiter = CompletableDeferred<JsonObject>()
            controls[id] = waiter
            try {
                // The reader fails every registered waiter when it ends; one
                // registered after that must not wait for an answer that
                // cannot come.
                check(!readerEnded.isCompleted) { "Claude stopped" }
                write(ClaudeProtocol.controlRequest(id, subtype))
                return waiter.await()
            } finally {
                controls.remove(id)
            }
        }

        private fun failControls() {
            val waiting = controls.values.toList()
            controls.clear()
            waiting.forEach { it.completeExceptionally(IllegalStateException("Claude stopped")) }
        }

        suspend fun write(message: JsonObject) = withContext(Dispatchers.IO) {
            writeLock.withLock {
                val out = writer ?: error("Claude is not running for this chat.")
                try {
                    out.write(message.toString())
                    out.newLine()
                    out.flush()
                } catch (error: IOException) {
                    throw IllegalStateException("Claude is not running for this chat.", error)
                }
            }
        }

        private suspend fun onToolCall(name: String, arguments: JsonObject): ToolResult {
            val turn = synchronized(state) { mapper.toolTurn() }
                ?: return ToolResult("No task is running in this chat, so the tool was not run.", success = false)
            val requestId = "claude-${UUID.randomUUID()}"
            val pending = PendingTool(threadId, turn.turnId, CompletableDeferred())
            pendingTools[requestId] = pending
            try {
                // A stop between the check above and here still refuses the call.
                if (turn.ended || turn.interruptRequested) return STOPPED
                stream.emit(EngineEvent.ToolCall(requestId, name.removePrefix(ClaudeProtocol.MCP_TOOL_PREFIX), arguments, threadId, turn.turnId))
                return pending.result.await()
            } finally {
                pendingTools.remove(requestId)
            }
        }

        /**
         * SIGKILL, for a stop the CLI did not honour. The reader then ends the turn.
         * The process can still read as alive for a moment after the kill, so
         * it is marked unusable: the next turn starts a new one.
         */
        fun kill() {
            running = null
            process?.destroyForcibly()
            server?.stop()
            server = null
        }

        /** Stop an idle process: close stdin so the CLI exits by itself, then force it after 2 s. */
        suspend fun stopProcess() {
            val started = process
            process = null
            running = null
            val out = writer
            writer = null
            withContext(Dispatchers.IO) {
                runCatching { out?.close() }
                if (started != null && !started.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) started.destroyForcibly()
            }
            server?.stop()
            server = null
            File(dir(), MCP_CONFIG_FILE).delete()
            failControls()
            synchronized(state) { mapper.processEnded("") }
        }

        /** End any running turn as interrupted and stop the process. */
        suspend fun shutdown() {
            val signals = synchronized(state) {
                mapper.active?.let { turn ->
                    turn.interruptRequested = true
                    mapper.end(turn, "interrupted", null)
                }.orEmpty()
            }
            dispatch(signals)
            stopProcess()
        }

        private fun recordStderr(line: String) {
            val safe = SecretRedactor.redactStderrLine(line)
            if (safe.isBlank()) return
            synchronized(stderrLock) {
                if (stderrTail.size >= MAX_STDERR_LINES) stderrTail.removeFirst()
                stderrTail.addLast(safe)
            }
        }

        fun stderrSnapshot(maxLines: Int): String = synchronized(stderrLock) {
            stderrTail.toList().takeLast(maxLines).joinToString("; ")
        }
    }

    // ---- short-lived commands ----------------------------------------------------

    /** Run a `claude` subcommand to its end and return stdout. Output is returned, never logged. */
    private suspend fun runCommand(args: List<String>, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val process = host.start(args, home(), emptyMap())
        runCatching { process.outputStream.close() }
        val output = async { readBounded(process.inputStream) }
        launch { drain(process.errorStream) }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Claude did not answer in time.")
        }
        output.await()
    }

    /** Ask a throwaway process for the model list. It sends no message, so no usage is spent. */
    private suspend fun probeModels(): List<AgentModel> = withContext(Dispatchers.IO) {
        val process = host.start(ClaudeProtocol.probeArgs(), home(), emptyMap())
        try {
            launch { drain(process.errorStream) }
            val reply = CompletableDeferred<JsonObject>()
            launch {
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                            val response = message["response"] as? JsonObject ?: continue
                            if (message.string("type") == "control_response" && response.string("request_id") == PROBE_ID) {
                                (response["response"] as? JsonObject)?.let(reply::complete)
                                break
                            }
                        }
                    }
                }
                reply.completeExceptionally(IllegalStateException("No model list"))
            }
            process.outputStream.bufferedWriter(Charsets.UTF_8).apply {
                write(ClaudeProtocol.controlRequest(PROBE_ID, "initialize").toString())
                newLine()
                flush()
            }
            val answer = withTimeoutOrNull(PROBE_TIMEOUT_MS) { runCatching { reply.await() }.getOrNull() }
            answer?.let(ClaudeProtocol::parseModels).orEmpty()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun readLoginOutput(input: InputStream, url: CompletableDeferred<String>) {
        val buffer = StringBuilder()
        try {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(1024)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    if (url.isCompleted || buffer.length > MAX_LOGIN_OUTPUT) continue
                    buffer.append(chunk, 0, count)
                    ClaudeProtocol.parseLoginUrl(buffer.toString())?.let { url.complete(it) }
                }
            }
        } catch (_: IOException) {
        } finally {
            if (!url.isCompleted) {
                ClaudeProtocol.parseLoginUrl("$buffer\n")?.let { url.complete(it) }
                    ?: url.completeExceptionally(IllegalStateException("No sign-in link"))
            }
        }
    }

    private fun readBounded(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        try {
            input.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (out.size() < MAX_COMMAND_OUTPUT) out.write(chunk, 0, count)
                }
            }
        } catch (_: IOException) {
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun drain(input: InputStream) {
        try {
            input.use { stream -> val chunk = ByteArray(8192); while (stream.read(chunk) >= 0) Unit }
        } catch (_: IOException) {
        }
    }

    private fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
    }

    companion object {
        const val DOWNLOAD_FIRST = "Download Claude Code in Settings first."
        private const val TURN_OVER = "The task has already finished."
        private const val NOT_FOUND = "No conversation found"
        private const val MCP_CONFIG_FILE = "mcp.json"
        private const val PROBE_ID = "probe-initialize"
        private val STOPPED = ToolResult("Run stopped. No device action was performed.", success = false)
        private const val HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 20_000L
        private const val COMPACT_TIMEOUT_MS = 10 * 60_000L
        private const val LOGIN_URL_TIMEOUT_MS = 60_000L
        private const val LOGIN_EXIT_TIMEOUT_MS = 120_000L
        private const val STOP_GRACE_MS = 2_000L
        private const val ACCOUNT_CACHE_NS = 30_000_000_000L
        private const val MAX_CODE_CHARS = 4_096
        private const val MAX_SKILL_BYTES = 256L * 1024
        private const val MAX_AGENTS_MD_BYTES = 1024L * 1024
        private const val MAX_LOGIN_OUTPUT = 64 * 1024
        private const val MAX_COMMAND_OUTPUT = 256 * 1024
        private const val MAX_STDERR_LINES = 80
    }
}
