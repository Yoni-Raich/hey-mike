package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.enginecodex.CodexEngine
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
    data class Ready(val probe: WindowsProbe, val account: String) : RemoteSetup
    data class Failed(val message: String) : RemoteSetup
}

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
    private val stream = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RemoteEvent> = stream.asSharedFlow()

    private val mutableSetup = MutableStateFlow<Map<String, RemoteSetup>>(emptyMap())
    /** The latest setup step per computer. */
    val setup: StateFlow<Map<String, RemoteSetup>> = mutableSetup.asStateFlow()

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
    suspend fun setUp(computerId: String): RemoteSetup {
        val result = runCatching {
            report(computerId, RemoteSetup.Working("Connecting"))
            val connection = connection(computerId)
            var probe = withContext(Dispatchers.IO) { connection.probe(refresh = true) }
            if (!probe.installed) {
                report(computerId, RemoteSetup.Working("Installing Codex on the computer (one time, about 120 MB)"))
                withContext(Dispatchers.IO) { connection.install() }
                probe = withContext(Dispatchers.IO) { connection.probe(refresh = true) }
                check(probe.installed) { "Codex did not install on the computer." }
            }
            report(computerId, RemoteSetup.Working("Starting Codex on ${probe.computerName.ifBlank { "the computer" }}"))
            val account = engine(computerId).account()
            if (account.signedIn) RemoteSetup.Ready(probe, account.label)
            else {
                val login = engine(computerId).login()
                RemoteSetup.NeedsSignIn(login.loginUrl, login.userCode)
            }
        }.getOrElse { RemoteSetup.Failed(it.message ?: it.toString()) }
        report(computerId, result)
        return result
    }

    /** Check whether a device-code sign-in on the computer has finished. */
    suspend fun checkSignIn(computerId: String): RemoteSetup {
        val result = runCatching {
            val account: AccountStatus = engine(computerId).account()
            if (!account.signedIn) return@runCatching null
            val probe = withContext(Dispatchers.IO) { connection(computerId).probe(refresh = false) }
            RemoteSetup.Ready(probe, account.label)
        }.getOrElse { RemoteSetup.Failed(it.message ?: it.toString()) }
        result?.let { report(computerId, it) }
        return result ?: mutableSetup.value[computerId] ?: RemoteSetup.Working("Waiting for sign-in")
    }

    suspend fun listFolders(computerId: String, path: String): FolderListing = withContext(Dispatchers.IO) {
        val connection = connection(computerId)
        WindowsHost.parseListing(connection.link.run(WindowsHost.powershell(WindowsHost.listScript(path)), 30_000))
    }

    /** Close the computer's Codex and its connection. Chats on it stay bound. */
    suspend fun disconnect(computerId: String) {
        val engine = lock.withLock { engines.remove(computerId) }
        engine?.let { (codex, job) -> runCatching { codex.close() }; job.cancel() }
        links.remove(computerId)?.let { withContext(Dispatchers.IO) { runCatching { it.link.close() } } }
        mutableSetup.value = mutableSetup.value - computerId
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

    private suspend fun connection(computerId: String): Connection = linkLock.withLock {
        links[computerId]?.let { return@withLock it }
        openConnection(computerId)
    }

    private suspend fun openConnection(computerId: String): Connection = withContext(Dispatchers.IO) {
        val computer = store.computer(computerId) ?: error("That computer was removed.")
        val password = store.password(computerId) ?: error("No password saved for ${computer.label}.")
        val link = SshLink(SshTarget(computer.host, computer.port, computer.user, password, computer.hostKey))
        val seen = link.connect()
        if (computer.hostKey == null) {
            // First contact: this key is the computer from now on.
            store.update(computerId) { it.copy(hostKey = seen.key, fingerprint = seen.fingerprint) }
        }
        Connection(link).also { links[computerId] = it }
    }

    private class Connection(val link: SshLink) {
        @Volatile private var cached: WindowsProbe? = null

        fun probe(refresh: Boolean): WindowsProbe {
            if (!refresh) cached?.let { return it }
            return WindowsHost.parseProbe(link.run(WindowsHost.powershell(WindowsHost.probeScript()), 60_000))
                .also { cached = it }
        }

        fun install() {
            // Downloading about 120 MB on the computer's own connection.
            val installed = WindowsHost.parseInstall(link.run(WindowsHost.powershell(WindowsHost.installScript()), 15 * 60_000))
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
            return connection.link.start(WindowsHost.appServerCommand(connection.probe(refresh = false)))
        }

        override suspend fun stop() {
            withContext(Dispatchers.IO) { process?.destroy() }
            process = null
            mutableStatus.value = RuntimeStatus(RuntimePhase.READY, "Codex stopped on the computer")
        }
    }

    companion object {
        fun profileFor(computer: RemoteComputer): EngineProfile = EngineProfile(
            developerInstructions = RemoteInstructions.forComputer(computer),
            sandbox = computer.access.sandbox,
            approvalPolicy = computer.access.approvalPolicy,
            inlineImages = true,
        )
    }
}
