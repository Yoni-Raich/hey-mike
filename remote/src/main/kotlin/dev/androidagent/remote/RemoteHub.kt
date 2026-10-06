package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.EngineKind
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.engineclaude.ClaudeComputer
import dev.androidagent.engineclaude.McpToolServerFactory
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.CodexThread
import dev.androidagent.enginecodex.CodexThreadMessage
import dev.androidagent.enginecodex.EngineProfile
import dev.androidagent.enginecodex.ExternalChatgptTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** An engine event and the computer it came from. [engine] is the engine that sent it there. */
data class RemoteEvent(val computerId: String, val event: EngineEvent, val engine: EngineKind = EngineKind.CODEX)

/** Where setting a computer up has got to, for the sheet to show. */
sealed interface RemoteSetup {
    data class Working(val step: String) : RemoteSetup
    /** Tailscale SSH answered and wants the user to approve at [url] (null: its rule has no browser check). */
    data class NeedsTailscaleApproval(val url: String?) : RemoteSetup
    /** [route] is the address that answered, for the sheet to name. */
    data class Ready(val probe: HostProbe, val account: String, val route: RemoteRoute? = null) : RemoteSetup
    data class Failed(val message: String) : RemoteSetup
}

/** Which of a computer's addresses a connection uses. */
data class RemoteRoute(val host: String, val viaVpn: Boolean)

/**
 * The computers' connections and the Codex running on each.
 *
 * One SSH link and one app-server per computer, started when a chat on it
 * first needs them. Every event carries the computer it came from, so the
 * router can keep requests from two app-servers apart.
 *
 * A computer that has its own Claude Code, signed in by its user, can run
 * chats on Claude too ([claude]). That is the computer's install and sign-in:
 * unlike Codex, which is handed the phone's ChatGPT account for each run,
 * Claude credentials stay on that computer. Its official login link and
 * the user's pasted code can be relayed through the phone.
 */
class RemoteHub(
    val store: RemoteStore,
    private val authTokens: suspend (refresh: Boolean, previousAccountId: String?) -> ExternalChatgptTokens =
        { _, _ -> error("Sign in to ChatGPT in Mike before using a computer chat.") },
    /** Where the Claude engines keep local scratch files; nothing of the computer's is stored there. */
    private val claudeScratch: File = File(System.getProperty("java.io.tmpdir") ?: ".", "hey-mike-claude"),
    private val createClaude: (ClaudeProcessHost, ClaudeComputer) -> ClaudeCodeEngine = { host, computer ->
        // The tools ride on the process's own streams there, so no server is ever made.
        ClaudeCodeEngine(host, McpToolServerFactory { _, _, _ -> error("A computer chat has no tool server") }, maxLiveChats = 4, computer = computer)
    },
    private val createEngine: (RuntimeHost, EngineProfile) -> CodexEngine = ::CodexEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val linkLock = Mutex()
    private val links = ConcurrentHashMap<String, Connection>()
    private val engines = ConcurrentHashMap<String, Pair<CodexEngine, Job>>()
    private val claudeEngines = ConcurrentHashMap<String, Triple<ClaudeCodeEngine, ComputerClaudeHost, Job>>()
    /** The address that last answered per computer, tried first next time. */
    private val lastHost = ConcurrentHashMap<String, String>()
    private val stream = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RemoteEvent> = stream.asSharedFlow()

    private val mutableSetup = MutableStateFlow<Map<String, RemoteSetup>>(emptyMap())
    /** The latest setup step per computer. */
    val setup: StateFlow<Map<String, RemoteSetup>> = mutableSetup.asStateFlow()

    private val mutableThreads = MutableStateFlow<Map<String, List<CodexThread>>>(emptyMap())
    /** Per computer, the conversations Codex keeps there, as last listed. */
    val threads: StateFlow<Map<String, List<CodexThread>>> = mutableThreads.asStateFlow()

    /** Reflect a successful rename at once; another Mike reads the same name on its next refresh. */
    internal fun threadRenamed(computerId: String, threadId: String, title: String) {
        mutableThreads.update { all ->
            val list = all[computerId] ?: return@update all
            all + (computerId to list.map { if (it.id == threadId) it.copy(title = title, name = title) else it })
        }
    }

    /**
     * List the computer's conversations again. Only while it is set up: this
     * never starts a connection the user did not ask for. A failure keeps
     * the last list.
     */
    suspend fun refreshThreads(computerId: String) {
        if (mutableSetup.value[computerId] !is RemoteSetup.Ready) return
        mutableRefreshing.value = mutableRefreshing.value + computerId
        try {
            runCatching { engine(computerId).listThreads() }
                .onSuccess { list -> mutableThreads.value = mutableThreads.value + (computerId to list) }
        } finally {
            mutableRefreshing.value = mutableRefreshing.value - computerId
        }
    }

    private val mutableClaude = MutableStateFlow<Map<String, ComputerClaude>>(emptyMap())
    /** Per computer, its own Claude Code as last checked. A computer not checked yet has no entry. */
    val claudeState: StateFlow<Map<String, ComputerClaude>> = mutableClaude.asStateFlow()

    val claudeSignIn = ComputerClaudeSignIn(
        scope = scope,
        begin = { claude(it).login() },
        complete = { id, code -> claude(id).completeLogin(code) },
        stop = { id -> claudeEngines[id]?.first?.cancelLogin() },
        refresh = { id ->
            val found = checkClaude(id)
            check(found.error == null) { "Could not check Claude" }
        },
    )

    /**
     * The Claude Code of [computerId]. Its first use looks for `claude` on the
     * computer and fails with words for the user when there is none.
     */
    suspend fun claude(computerId: String): ClaudeCodeEngine = lock.withLock {
        claudeEngines[computerId]?.first ?: run {
            store.computer(computerId) ?: error("That computer was removed.")
            val host = ComputerClaudeHost(computerId)
            val created = createClaude(host, host)
            val job = scope.launch { created.events.collect { stream.emit(RemoteEvent(computerId, it, EngineKind.CLAUDE)) } }
            claudeEngines[computerId] = Triple(created, host, job)
            created
        }
    }

    /**
     * Look for Claude Code on the computer and read its sign-in and models,
     * for the model menu. Quiet: a computer that cannot be asked reads as
     * having none.
     */
    suspend fun checkClaude(computerId: String): ComputerClaude {
        val found = runCatching {
            val engine = claude(computerId)
            val probe = claudeEngines[computerId]?.second?.probe(refresh = true)
            if (probe?.path == null) return@runCatching ComputerClaude(installed = false)
            val account: AccountStatus = engine.account()
            ComputerClaude(
                installed = true,
                version = probe.version,
                signedIn = account.signedIn,
                account = account.label.takeIf { account.signedIn }.orEmpty(),
                models = if (account.signedIn) engine.modelCatalog() else emptyList(),
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            (mutableClaude.value[computerId] ?: ComputerClaude(installed = false))
                .copy(error = "Could not check Claude. Check the computer connection and try again.")
        }
        mutableClaude.update { it + (computerId to found) }
        return found
    }

    private val mutableRefreshing = MutableStateFlow<Set<String>>(emptySet())
    /** Computers whose conversations are being listed right now, for a progress line. */
    val refreshing: StateFlow<Set<String>> = mutableRefreshing.asStateFlow()

    /**
     * Connect in the background, for the side panel, once per app run: a
     * computer the user added should show its projects without being asked.
     * Nothing is installed, and a failure only shows on the computer's card.
     */
    suspend fun connectQuietly(computerId: String) {
        if (mutableSetup.value[computerId] != null) return
        setUp(computerId, install = false)
    }

    /** Another Codex on the computer (its desktop app) holds [threadId] open for writing. */
    suspend fun isThreadBusy(computerId: String, threadId: String): Boolean {
        val held = withContext(Dispatchers.IO) {
            val connection = connection(computerId)
            WindowsHost.parseBusy(connection.link.run(connection.scripts.threadBusy(threadId), 30_000))
        }
        if (!held) return false
        // The lock may be this app's own: its Codex holds every thread it has
        // opened. Only a Codex that is already running is asked; none running
        // means the holder is someone else.
        val ours = engines[computerId]?.first ?: return true
        return threadId !in runCatching { ours.loadedThreadIds() }.getOrDefault(emptySet())
    }

    /** A copy of [threadId] with its whole history, which this app's Codex can continue. */
    suspend fun forkThread(computerId: String, cwd: String, threadId: String): String =
        engine(computerId).forkThreadAt(cwd, threadId)

    /** One conversation's messages, read from the computer. */
    suspend fun readThread(computerId: String, threadId: String): List<CodexThreadMessage> =
        engine(computerId).readThreadMessages(threadId)

    /** The Codex for [computerId], started on first use. */
    suspend fun engine(computerId: String): CodexEngine = lock.withLock {
        val engine = engines[computerId]?.first ?: run {
            val computer = store.computer(computerId) ?: error("That computer was removed.")
            val created = createEngine(ComputerRuntime(computerId), profileFor(computer))
            val job = scope.launch { created.events.collect { stream.emit(RemoteEvent(computerId, it)) } }
            engines[computerId] = created to job
            created
        }
        // Every entry point checks the active phone account. A failed exchange
        // stops here; the computer's own saved sign-in is never used for a run.
        engine.ensureExternalAuth(authTokens(false, null)) { previous -> authTokens(true, previous) }
        engine
    }

    /**
     * Connect, trust the host key the first time, install Codex if it is
     * missing and connect Mike's active account. Progress goes to [setup].
     */
    suspend fun setUp(computerId: String, install: Boolean = true): RemoteSetup {
        val result = runCatching<RemoteSetup> {
            report(computerId, RemoteSetup.Working("Connecting"))
            val connection = connection(computerId) { route ->
                report(computerId, RemoteSetup.Working(if (route.viaVpn) "Trying the VPN address ${route.host}" else "Connecting to ${route.host}"))
            }
            var probe = withContext(Dispatchers.IO) { connection.probe(refresh = true) }
            if (!probe.installed) {
                // A background connect never downloads 120 MB on its own.
                check(install) { "Codex is not on this computer yet. Start a new project on it to set it up." }
                report(computerId, RemoteSetup.Working("Installing Codex on the computer (one time, about 120 MB)"))
                withContext(Dispatchers.IO) { connection.install() }
                probe = withContext(Dispatchers.IO) { connection.probe(refresh = true) }
                check(probe.installed) { "Codex did not install on the computer." }
            }
            report(computerId, RemoteSetup.Working("Starting Codex on ${probe.computerName.ifBlank { "the computer" }}"))
            val account = engine(computerId).account()
            check(account.signedIn) { "Mike could not use the phone account on this computer." }
            RemoteSetup.Ready(probe, account.label, connection.route)
        }.getOrElse { if (it is TailscaleCheck) RemoteSetup.NeedsTailscaleApproval(it.url) else RemoteSetup.Failed(it.message ?: it.toString()) }
        report(computerId, result)
        if (result is RemoteSetup.Ready) {
            refreshThreads(computerId)
            // Beside the setup, never part of it: a computer without Claude is still ready.
            scope.launch { checkClaude(computerId) }
        }
        return result
    }

    /** Copy a file from the computer to this phone. */
    suspend fun download(
        computerId: String,
        remotePath: String,
        target: File,
        maxBytes: Long,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        connection(computerId).link.download(remotePath, target, maxBytes, progress)
    }

    /** The size of a file on the computer, without copying it. */
    suspend fun size(computerId: String, remotePath: String): Long = withContext(Dispatchers.IO) {
        connection(computerId).link.size(remotePath)
    }

    /** Copy a file from this phone to the computer. */
    suspend fun upload(
        computerId: String,
        source: File,
        remotePath: String,
        replace: Boolean = true,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        connection(computerId).link.upload(source, remotePath, replace, progress)
    }

    /**
     * Put files the user attached to a message onto the computer the chat runs
     * on, and return where each one landed, in the computer's own spelling, for
     * the prompt. They go to `.hey-mike/attachments/<time>/` in the chat's
     * project folder: inside the folder Codex may work in, and a folder per
     * message so two files with one name never overwrite each other.
     *
     * [files] are the name to show and the local file. A chat with no computer
     * has nothing to upload to.
     */
    suspend fun sendAttachments(sessionId: String, files: List<Pair<String, File>>): List<String> {
        val binding = requireNotNull(store.binding(sessionId)) { "This chat does not run on a computer." }
        val computer = requireNotNull(store.computer(binding.computerId)) { "That computer is no longer saved." }
        val folder = ".hey-mike/attachments/" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return files.map { (name, file) ->
            val full = ComputerPlace.fullPath("$folder/${safeRemoteName(name)}", binding.cwd, computer.label)
            upload(computer.id, file, full, replace = true)
            full
        }
    }

    /** Every saved computer as a place `copy_file` can read from and write to. */
    fun filePlaces(): List<dev.androidagent.core.FilePlace> = store.state.value.computers.map { ComputerPlace(this, it) }

    /** In a chat that runs on a computer, bare paths mean that computer's project folder. */
    fun fileHome(workspace: File): dev.androidagent.core.FileHome? {
        val binding = RoutingAgentEngine.sessionIdOf(workspace)?.let(store::binding) ?: return null
        val computer = store.computer(binding.computerId) ?: return null
        return dev.androidagent.core.FileHome(computer.label, binding.cwd)
    }

    /** The folders in [path] on the computer; with [create], the folder is made first. */
    suspend fun listFolders(computerId: String, path: String, create: Boolean = false): FolderListing = withContext(Dispatchers.IO) {
        val connection = connection(computerId)
        WindowsHost.parseListing(connection.link.run(connection.scripts.list(path, create), 30_000))
    }

    /** Close the computer's Codex and its connection. Chats on it stay bound. */
    suspend fun disconnect(computerId: String) {
        claudeSignIn.cancel(computerId)
        val engine = lock.withLock { engines.remove(computerId) }
        engine?.let { (codex, job) -> runCatching { codex.close() }; job.cancel() }
        lock.withLock { claudeEngines.remove(computerId) }?.let { (claude, _, job) -> runCatching { claude.close() }; job.cancel() }
        mutableClaude.update { it - computerId }
        links.remove(computerId)?.let { withContext(Dispatchers.IO) { runCatching { it.link.close() } } }
        mutableSetup.value = mutableSetup.value - computerId
        if (store.computer(computerId) == null) mutableThreads.value = mutableThreads.value - computerId
    }

    /** Settings changed (address, password, access): the next use reconnects with them. */
    suspend fun reload(computerId: String) = disconnect(computerId)

    suspend fun closeAll() {
        store.state.value.computers.forEach { disconnect(it.id) }
        (engines.keys + claudeEngines.keys + links.keys).toSet().forEach { disconnect(it) }
    }

    private fun report(computerId: String, step: RemoteSetup) {
        mutableSetup.value = mutableSetup.value + (computerId to step)
    }

    private fun dropLink(computerId: String) {
        links.remove(computerId)?.let { runCatching { it.link.close() } }
    }

    private suspend fun connection(computerId: String, onAttempt: (RemoteRoute) -> Unit = {}): Connection = linkLock.withLock {
        links[computerId]?.let { return@withLock it }
        openConnection(computerId, onAttempt)
    }

    /**
     * Try each address of the computer, the one that answered last time
     * first. Only an address that does not answer at all moves on to the
     * next: a refused password or a changed host key stops here.
     */
    private suspend fun openConnection(computerId: String, onAttempt: (RemoteRoute) -> Unit): Connection = withContext(Dispatchers.IO) {
        val computer = store.computer(computerId) ?: error("That computer was removed.")
        val password = store.password(computerId) ?: error("No password saved for ${computer.label}.")
        val hosts = computer.hosts.sortedByDescending { it == lastHost[computerId] }
        val missed = mutableListOf<String>()
        for ((index, host) in hosts.withIndex()) {
            val route = RemoteRoute(host, viaVpn = host != computer.host)
            onAttempt(route)
            // With another address still to try, give up on this one sooner.
            val timeout = if (index < hosts.lastIndex) FALLBACK_TIMEOUT_MS else SshLink.CONNECT_TIMEOUT_MS
            val link = SshLink(SshTarget(host, computer.port, computer.user, password, computer.hostKey, timeout))
            val seen = try {
                link.connect()
            } catch (error: SshUnreachable) {
                missed += "$host: ${error.message}"
                continue
            }
            if (computer.hostKey == null) {
                // First contact: this key is the computer from now on.
                store.update(computerId) { it.copy(hostKey = seen.key, fingerprint = seen.fingerprint) }
            }
            // Which system it runs decides every script; asked once, then kept.
            val os = computer.os ?: try {
                HostOs.fromUname(link.run("uname -s", 15_000))
                    ?: error("This computer runs macOS, which Hey Mike does not support yet. Windows and Linux work.")
            } catch (error: Exception) {
                link.close()
                throw error
            }
            if (computer.os == null) store.update(computerId) { it.copy(os = os) }
            lastHost[computerId] = host
            return@withContext Connection(link, route, os).also { links[computerId] = it }
        }
        error(
            if (missed.size == 1) missed.single().substringAfter(": ")
            else "No address answered.\n" + missed.joinToString("\n"),
        )
    }

    private class Connection(val link: SshLink, val route: RemoteRoute, val os: HostOs) {
        val scripts: HostScripts = if (os == HostOs.LINUX) LinuxHost else WindowsHost

        @Volatile private var cached: HostProbe? = null

        fun probe(refresh: Boolean): HostProbe {
            if (!refresh) cached?.let { return it }
            return WindowsHost.parseProbe(link.run(scripts.probe(), 60_000))
                .also { cached = it }
        }

        fun install() {
            // Downloading about 120 MB on the computer's own connection.
            val installed = WindowsHost.parseInstall(link.run(scripts.install(), 15 * 60_000))
            check(installed) { "Codex did not install on the computer." }
            cached = null
        }
    }

    /** The app-server on one computer, as the engine's [RuntimeHost]. */
    private inner class ComputerRuntime(private val computerId: String) : RuntimeHost {
        private val mutableStatus = MutableStateFlow(RuntimeStatus())
        override val status: StateFlow<RuntimeStatus> = mutableStatus
        @Volatile private var process: Process? = null

        // No phone folder belongs to a computer's Codex.
        override val homeDirectory: File get() = File("/")

        override suspend fun prepare() {
            val probe = withContext(Dispatchers.IO) { connection(computerId).probe(refresh = false) }
            check(probe.installed) { "Codex is not set up on this computer yet. Open Computers and connect it." }
            mutableStatus.value = RuntimeStatus(RuntimePhase.READY, "Codex ready on ${probe.computerName}")
        }

        override suspend fun startAppServer(): Process = withContext(Dispatchers.IO) {
            process?.destroy()
            val started = try {
                launchOnce()
            } catch (error: Exception) {
                // The link may have dropped while idle: the computer slept or
                // the phone changed networks. One fresh connection, then the
                // error stands.
                dropLink(computerId)
                launchOnce()
            }
            process = started
            mutableStatus.value = RuntimeStatus(RuntimePhase.RUNNING, "Codex running on the computer")
            started
        }

        private suspend fun launchOnce(): Process {
            val connection = connection(computerId)
            return connection.link.start(connection.scripts.appServer(connection.probe(refresh = false)))
        }

        override suspend fun stop() {
            withContext(Dispatchers.IO) { process?.destroy() }
            process = null
            mutableStatus.value = RuntimeStatus(RuntimePhase.READY, "Codex stopped on the computer")
        }
    }

    /**
     * The computer's own Claude Code, as the Claude engine's host and place.
     *
     * Short commands (`auth status`, the model probe) and each chat's process
     * all start the same way: a script file under `~/.hey-mike/claude` on the
     * computer, then one command that runs it.
     */
    private inner class ComputerClaudeHost(private val computerId: String) : ClaudeProcessHost, ClaudeComputer {
        private val mutableStatus = MutableStateFlow(RuntimeStatus())
        override val status: StateFlow<RuntimeStatus> = mutableStatus
        // Nothing of the computer's lives here: the engine only looks for app-installed skills, and finds none.
        override val homeDirectory: File get() = File(claudeScratch, computerId).apply { mkdirs() }
        @Volatile private var cached: ClaudeProbe? = null
        private val started = java.util.concurrent.CopyOnWriteArrayList<Process>()

        private fun computer(): RemoteComputer = store.computer(computerId) ?: error("That computer was removed.")

        override val instructions: String get() = RemoteInstructions.forComputer(computer(), EngineKind.CLAUDE)
        override val permissionMode: String get() = if (computer().access == RemoteAccess.FULL) "bypassPermissions" else "acceptEdits"
        override val askUser: Boolean get() = computer().access != RemoteAccess.FULL
        override val notReady: String
            get() = "Claude Code is not on ${computer().label}. Install it there, sign in with `claude`, and connect the computer again."

        suspend fun probe(refresh: Boolean): ClaudeProbe {
            if (!refresh) cached?.let { return it }
            val found = withContext(Dispatchers.IO) {
                val connection = connection(computerId)
                ClaudeLaunch.parseProbe(connection.link.run(ClaudeLaunch.probe(connection.os), 60_000))
            }
            cached = found
            mutableStatus.value =
                if (found.path != null) RuntimeStatus(RuntimePhase.READY, "Claude Code ${found.version}".trim())
                else RuntimeStatus(RuntimePhase.MISSING, notReady)
            return found
        }

        /** Find `claude` once per connection. Nothing is downloaded or installed. */
        override suspend fun prepare() { probe(refresh = false) }

        override suspend fun start(args: List<String>, workingDirectory: File, extraEnv: Map<String, String>): Process =
            startScript("run-" + Integer.toHexString(args.hashCode()), cwd = null, files = emptyMap(), env = extraEnv) { args }

        override suspend fun startChat(
            chatId: String,
            cwd: String,
            files: Map<String, String>,
            env: Map<String, String>,
            args: (paths: Map<String, String>) -> List<String>,
        ): Process {
            require(chatId.matches(LinuxHost.THREAD_ID)) { "Unexpected chat id" }
            return startScript("chat-$chatId", cwd, files, env, args)
        }

        private suspend fun startScript(
            name: String,
            cwd: String?,
            files: Map<String, String>,
            env: Map<String, String>,
            args: (Map<String, String>) -> List<String>,
        ): Process = withContext(Dispatchers.IO) {
            try {
                launchOnce(name, cwd, files, env, args)
            } catch (error: Exception) {
                // The link may have dropped while idle; one fresh connection, then the error stands.
                if (error is IllegalArgumentException || error is CancellationException) throw error
                dropLink(computerId)
                cached = null
                launchOnce(name, cwd, files, env, args)
            }.also { started += it; started.removeAll { old -> !old.isAlive } }
        }

        private suspend fun launchOnce(
            name: String,
            cwd: String?,
            files: Map<String, String>,
            env: Map<String, String>,
            args: (Map<String, String>) -> List<String>,
        ): Process {
            val connection = connection(computerId)
            val found = probe(refresh = false)
            val claude = found.path ?: error(notReady)
            val folder = ClaudeLaunch.folder(found.home, connection.os)
            val paths = files.mapValues { (file, text) -> put(connection, ClaudeLaunch.file(folder, file, connection.os), text) }
            val script = ClaudeLaunch.script(connection.os, claude, cwd, env, args(paths))
            val scriptPath = put(connection, ClaudeLaunch.file(folder, ClaudeLaunch.scriptName(name, connection.os), connection.os), script)
            return connection.link.start(ClaudeLaunch.command(connection.os, scriptPath, connection.probe(refresh = false).powerShellDefault))
        }

        /** Write [text] to [remotePath] on the computer and return the path. */
        private suspend fun put(connection: Connection, remotePath: String, text: String): String {
            val local = File.createTempFile("claude-launch", ".tmp", homeDirectory)
            try {
                local.writeText(text)
                connection.link.upload(local, remotePath, replace = true)
            } finally {
                local.delete()
            }
            return remotePath
        }

        override suspend fun stopAll() {
            withContext(Dispatchers.IO) { started.forEach { runCatching { it.destroy() } } }
            started.clear()
        }
    }

    companion object {
        private const val FALLBACK_TIMEOUT_MS = 6_000

        /**
         * A file name both Windows and POSIX accept: no separators, no
         * characters Windows forbids, no trailing dot or space.
         */
        internal fun safeRemoteName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.', ' ').take(120).ifBlank { "attachment" }

        fun profileFor(computer: RemoteComputer): EngineProfile = EngineProfile(
            developerInstructions = RemoteInstructions.forComputer(computer),
            sandbox = computer.access.sandbox,
            approvalPolicy = computer.access.approvalPolicy,
            inlineImages = true,
        )
    }
}
