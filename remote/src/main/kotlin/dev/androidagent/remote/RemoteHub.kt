package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.CodexThread
import dev.androidagent.enginecodex.CodexThreadMessage
import dev.androidagent.enginecodex.EngineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** An engine event and the computer it came from. */
data class RemoteEvent(val computerId: String, val event: EngineEvent)

/** Where setting a computer up has got to, for the sheet to show. */
sealed interface RemoteSetup {
    data class Working(val step: String) : RemoteSetup
    /** Codex on the computer is not signed in; the user opens [url] and enters [code]. */
    data class NeedsSignIn(val url: String?, val code: String?) : RemoteSetup
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
 */
class RemoteHub(val store: RemoteStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val linkLock = Mutex()
    private val links = ConcurrentHashMap<String, Connection>()
    private val engines = ConcurrentHashMap<String, Pair<CodexEngine, Job>>()
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

    /** One conversation's messages, read from the computer. */
    suspend fun readThread(computerId: String, threadId: String): List<CodexThreadMessage> =
        engine(computerId).readThreadMessages(threadId)

    /** The Codex for [computerId], started on first use. */
    suspend fun engine(computerId: String): CodexEngine = lock.withLock {
        engines[computerId]?.first?.let { return@withLock it }
        val computer = store.computer(computerId) ?: error("That computer was removed.")
        val engine = CodexEngine(ComputerRuntime(computerId), profileFor(computer))
        val job = scope.launch { engine.events.collect { stream.emit(RemoteEvent(computerId, it)) } }
        engines[computerId] = engine to job
        engine
    }

    /**
     * Connect, trust the host key the first time, install Codex if it is
     * missing and check its sign-in. Progress goes to [setup].
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
            if (account.signedIn) RemoteSetup.Ready(probe, account.label, connection.route)
            else {
                val login = engine(computerId).login()
                RemoteSetup.NeedsSignIn(login.loginUrl, login.userCode)
            }
        }.getOrElse { if (it is TailscaleCheck) RemoteSetup.NeedsTailscaleApproval(it.url) else RemoteSetup.Failed(it.message ?: it.toString()) }
        report(computerId, result)
        if (result is RemoteSetup.Ready) refreshThreads(computerId)
        return result
    }

    /** Check whether a device-code sign-in on the computer has finished. */
    suspend fun checkSignIn(computerId: String): RemoteSetup {
        val result = runCatching {
            val account: AccountStatus = engine(computerId).account()
            if (!account.signedIn) return@runCatching null
            val connection = connection(computerId)
            val probe = withContext(Dispatchers.IO) { connection.probe(refresh = false) }
            RemoteSetup.Ready(probe, account.label, connection.route)
        }.getOrElse { RemoteSetup.Failed(it.message ?: it.toString()) }
        result?.let { report(computerId, it) }
        if (result is RemoteSetup.Ready) refreshThreads(computerId)
        return result ?: mutableSetup.value[computerId] ?: RemoteSetup.Working("Waiting for sign-in")
    }

    /** Copy a file from the computer to this phone. */
    suspend fun download(computerId: String, remotePath: String, target: File, maxBytes: Long) = withContext(Dispatchers.IO) {
        connection(computerId).link.download(remotePath, target, maxBytes)
    }

    /** Copy a file from this phone to the computer. */
    suspend fun upload(computerId: String, source: File, remotePath: String) = withContext(Dispatchers.IO) {
        connection(computerId).link.upload(source, remotePath)
    }

    /** The folders in [path] on the computer; with [create], the folder is made first. */
    suspend fun listFolders(computerId: String, path: String, create: Boolean = false): FolderListing = withContext(Dispatchers.IO) {
        val connection = connection(computerId)
        WindowsHost.parseListing(connection.link.run(connection.scripts.list(path, create), 30_000))
    }

    /** Close the computer's Codex and its connection. Chats on it stay bound. */
    suspend fun disconnect(computerId: String) {
        val engine = lock.withLock { engines.remove(computerId) }
        engine?.let { (codex, job) -> runCatching { codex.close() }; job.cancel() }
        links.remove(computerId)?.let { withContext(Dispatchers.IO) { runCatching { it.link.close() } } }
        mutableSetup.value = mutableSetup.value - computerId
        if (store.computer(computerId) == null) mutableThreads.value = mutableThreads.value - computerId
    }

    /** Settings changed (address, password, access): the next use reconnects with them. */
    suspend fun reload(computerId: String) = disconnect(computerId)

    suspend fun closeAll() {
        store.state.value.computers.forEach { disconnect(it.id) }
        (engines.keys + links.keys).toSet().forEach { disconnect(it) }
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

    companion object {
        private const val FALLBACK_TIMEOUT_MS = 6_000

        fun profileFor(computer: RemoteComputer): EngineProfile = EngineProfile(
            developerInstructions = RemoteInstructions.forComputer(computer),
            sandbox = computer.access.sandbox,
            approvalPolicy = computer.access.approvalPolicy,
            inlineImages = true,
        )
    }
}
