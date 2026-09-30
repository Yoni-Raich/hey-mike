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

package dev.androidagent.adb

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.shell.AdbShellPacket
import dev.androidagent.core.AdbEndpoint
import dev.androidagent.core.AdbStatus
import dev.androidagent.core.AdbTransport
import dev.androidagent.core.CommandResult
import dev.androidagent.core.ConnectionPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Optional binary operations exposed by the concrete transport. */
interface AdbFileTransport {
    suspend fun pullFile(localFile: File, remotePath: String, timeoutMs: Long = 30_000): CommandResult
    suspend fun pushFile(localFile: File, remotePath: String, timeoutMs: Long = 30_000): CommandResult
    suspend fun installApk(localFile: File, replace: Boolean = true, timeoutMs: Long = 30_000): CommandResult
}

/**
 * Android ADB-over-Wi-Fi transport backed by Kadb 1.3.0.
 *
 * Pair and Connect use the same app-private Kadb identity. Every lifecycle or
 * command operation is serialized, while cancelActive can close the active
 * Kadb socket from another coroutine to break a blocking read.
 */
class AndroidAdbTransport(context: Context) : AdbTransport, AdbFileTransport {

    private val appContext = context.applicationContext
    private val identityLock = Any()
    private val lifecycleLock = Any()
    private val operationMutex = Mutex()
    private val _status = MutableStateFlow(AdbStatus())
    private var identityReady = false
    private var client: Kadb? = null
    private var activeClient: Kadb? = null
    private var activeJob: Job? = null
    private var generation = 0L
    private var connectedPort: Int? = null
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val reconnectLock = Any()
    private var reconnectJob: Job? = null
    private var switchObserver: ContentObserver? = null

    /** Set when adbd refused Mike's key; cleared by a new pairing or the switch turning on. */
    @Volatile private var pairingRejected = false

    /** Wakes the reconnect loop out of its backoff: the switch moved, or a new pairing exists. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override val status: StateFlow<AdbStatus> = _status.asStateFlow()

    init {
        _status.value = statusOf(ConnectionPhase.DISCONNECTED, "Not connected", null)
    }

    /**
     * Keep the last successful connect port and rediscover it while the app's
     * foreground service is alive. Discovery uses Android NSD only; it never
     * probes arbitrary LAN addresses.
     */
    fun startAutoReconnect(scope: CoroutineScope) {
        synchronized(reconnectLock) {
            if (reconnectJob?.isActive == true) return
            watchSwitch()
            val job = scope.launch(Dispatchers.IO) { reconnectLoop() }
            reconnectJob = job
            job.invokeOnCompletion {
                synchronized(reconnectLock) {
                    if (reconnectJob === job) reconnectJob = null
                }
            }
        }
    }

    fun stopAutoReconnect() {
        synchronized(reconnectLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            switchObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
            switchObserver = null
        }
    }

    /**
     * Whether [switchWirelessDebuggingOn] can work: Mike holds
     * WRITE_SECURE_SETTINGS, which it grants itself over its own ADB
     * connection the first time it connects.
     */
    fun canSwitchWirelessDebugging(): Boolean =
        appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Turn Wireless debugging on the way its switch in Settings does: by
     * writing the same global setting. On a network the user has not allowed
     * before, Android shows its own "Allow wireless debugging on this network?"
     * prompt; without Wi-Fi it switches straight back off. Returns false when
     * Mike cannot write the setting.
     */
    fun switchWirelessDebuggingOn(): Boolean {
        if (!canSwitchWirelessDebugging()) return false
        val written = runCatching {
            Settings.Global.putInt(appContext.contentResolver, WIRELESS_DEBUGGING_SETTING, 1)
        }.getOrDefault(false)
        if (written) {
            pairingRejected = false
            wake.trySend(Unit)
        }
        return written
    }

    /**
     * Follow the switch as it moves instead of finding out on the next poll:
     * show "on" as soon as it is on, drop a connection whose adbd just went
     * away, and retry at once rather than after the backoff.
     */
    private fun watchSwitch() {
        if (switchObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val on = wirelessDebuggingEnabled()
                // A fresh start of adbd deserves one more try with the old key.
                if (on == true) pairingRejected = false
                if (on == false && status.value.phase == ConnectionPhase.CONNECTED) {
                    dropConnection("Wireless Debugging was turned off")
                } else {
                    refreshStatus()
                }
                wake.trySend(Unit)
            }
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(WIRELESS_DEBUGGING_SETTING), false, observer,
            )
            switchObserver = observer
        }
    }

    override suspend fun discover(): List<AdbEndpoint> {
        // Looking is not losing a connection. Announcing it while connected
        // used to replace CONNECTED with "Discovery complete", and the
        // reconnect loop then closed the live connection to make a new one.
        val announce = status.value.phase !in BUSY_OR_CONNECTED
        if (announce) setStatus(ConnectionPhase.DISCOVERING, "Looking for Wireless Debugging services", null)
        return try {
            val services = AdbServiceDiscovery(appContext).discover()
            services.map { AdbEndpoint(it.port, it.isPairingService(), LOOPBACK) }
                .distinctBy { Triple(it.port, it.pairing, it.host) }
                .sortedWith(compareBy<AdbEndpoint> { it.pairing }.thenBy { it.port })
                .also {
                    val current = status.value
                    if (current.phase == ConnectionPhase.DISCOVERING) {
                        val message = if (it.isEmpty()) {
                            AdbReconnectPolicy.noServiceMessage(wirelessDebuggingEnabled())
                        } else {
                            "Discovery complete"
                        }
                        setStatus(ConnectionPhase.DISCONNECTED, message, connectedPort)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (announce) setStatus(ConnectionPhase.ERROR, safeMessage(e, "Discovery failed"), connectedPort)
            emptyList()
        }
    }

    override suspend fun pair(port: Int, code: String) {
        requireValidPort(port)
        require(code.matches(Regex("\\d{6}"))) { "Pairing code must contain exactly six digits" }
        operationMutex.withLock {
            val token = beginOperation(ConnectionPhase.PAIRING, "Pairing with Wireless Debugging", null)
            val job = currentCoroutineContext()[Job]
            setActive(job, null)
            try {
                ensureIdentity()
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        Kadb.pair(LOOPBACK, port, code, DEVICE_NAME)
                    }
                }
                pairingRejected = false
                wake.trySend(Unit)
                synchronized(lifecycleLock) {
                    if (generation == token) {
                        _status.value = statusOf(ConnectionPhase.DISCONNECTED, "Paired; connect using the Wireless Debugging port", null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setErrorIfCurrent(token, safeMessage(e, "Pairing failed"))
                throw IOException(safeMessage(e, "Pairing failed"), e)
            } finally {
                clearActive(job, null)
            }
        }
    }

    /**
     * Pair, then find the connect port and use it. Wireless Debugging only
     * advertises its connect service once pairing is accepted, and it can take
     * a moment to appear, so this looks more than once instead of making the
     * user read the port off the system dialog.
     *
     * Returns the port it connected on. On failure the pairing identity is
     * still stored, so [startAutoReconnect] keeps trying in the background.
     */
    suspend fun pairAndConnect(pairingPort: Int, code: String, onProgress: (String) -> Unit): Int {
        pair(pairingPort, code)
        val deadline = System.currentTimeMillis() + AdbAutoConnectPlan.TOTAL_BUDGET_MS
        val tried = mutableSetOf<Int>()
        var attempt = 0
        while (attempt < AdbAutoConnectPlan.MAX_ATTEMPTS && System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val endpoints = discover()
            // Re-read the saved port every pass: a successful connect writes it.
            val port = AdbAutoConnectPlan.target(savedConnectPort(), endpoints, tried)
            onProgress(AdbAutoConnectPlan.attemptMessage(attempt, port))
            if (port != null) {
                tried += port
                try {
                    connect(port)
                    return port
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the failure quiet and try the next endpoint; connect()
                    // has already published the error status.
                }
            }
            attempt++
            delay(AdbReconnectPolicy.retryDelayMs(attempt))
        }
        throw IOException(AdbAutoConnectPlan.giveUpMessage(wirelessDebuggingEnabled()))
    }

    override suspend fun connect(port: Int) {
        requireValidPort(port)
        operationMutex.withLock {
            val oldClient: Kadb?
            val token: Long
            synchronized(lifecycleLock) {
                token = ++generation
                oldClient = client
                client = null
                connectedPort = null
                _status.value = statusOf(ConnectionPhase.CONNECTING, "Connecting to $LOOPBACK:$port", port)
            }
            closeQuietly(oldClient)

            var newClient: Kadb? = null
            val job = currentCoroutineContext()[Job]
            try {
                ensureIdentity()
                setActive(job, null)
                newClient = withTimeout(DEFAULT_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) {
                        Kadb.create(LOOPBACK, port, CONNECT_TIMEOUT_MS.toInt(), SOCKET_TIMEOUT_MS.toInt())
                    }
                }
                val probe = withTimeout(DEFAULT_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) {
                        newClient!!.shell("echo ANDROID_AGENT_CONNECTED")
                    }
                }
                check(probe.exitCode == 0 && probe.output.contains("ANDROID_AGENT_CONNECTED")) {
                    probe.allOutput.ifBlank { "ADB connection check failed" }
                }
                pairingRejected = false
                val connectedClient = newClient!!
                withTimeoutOrNull(GRANT_TIMEOUT_MS) {
                    runInterruptible(Dispatchers.IO) { grantSwitchPermission(connectedClient) }
                }
                synchronized(lifecycleLock) {
                    if (generation != token) {
                        closeQuietly(newClient)
                        newClient = null
                        throw CancellationException("ADB connection was cancelled")
                    }
                    client = newClient
                    connectedPort = port
                    _status.value = statusOf(ConnectionPhase.CONNECTED, "Connected to $LOOPBACK:$port", port)
                    preferences.edit().putInt(KEY_CONNECT_PORT, port).apply()
                }
                newClient = null
            } catch (e: CancellationException) {
                closeQuietly(newClient)
                throw e
            } catch (e: Exception) {
                closeQuietly(newClient)
                // Class and message only: neither holds a key or a code.
                Log.w(TAG, "connect to port $port failed: ${e.javaClass.name}: ${e.message}")
                val rejected = AdbReconnectPolicy.isPairingRejected(e)
                if (rejected) pairingRejected = true
                val message = if (rejected) AdbReconnectPolicy.PAIRING_REJECTED_MESSAGE else safeMessage(e, "Connection failed")
                setErrorIfCurrent(token, message)
                throw IOException(message, e)
            } finally {
                clearActive(job, newClient)
            }
        }
    }

    /**
     * Grant Mike WRITE_SECURE_SETTINGS through its own ADB shell, so it can
     * later switch Wireless debugging on without sending the user to Settings.
     * This adds no power: the shell it uses already writes those settings.
     * Best effort; without it the user turns the switch on by hand as before.
     */
    private fun grantSwitchPermission(adb: Kadb) {
        if (canSwitchWirelessDebugging()) return
        val user = android.os.Process.myUid() / PER_USER_RANGE
        runCatching { adb.shell("pm grant --user $user ${appContext.packageName} $WRITE_SECURE_SETTINGS") }
            .onFailure { Log.w(TAG, "could not grant the Wireless debugging switch: ${it.message}") }
    }

    /** Close a live connection whose adbd has gone, and say why. */
    private fun dropConnection(message: String) {
        val old: Kadb?
        synchronized(lifecycleLock) {
            generation++
            old = client
            client = null
            connectedPort = null
            _status.value = statusOf(ConnectionPhase.DISCONNECTED, message, null)
        }
        closeQuietly(old)
    }

    /** Re-publish the current phase with a fresh reading of the switch, the pairing and the permission. */
    private fun refreshStatus() {
        synchronized(lifecycleLock) {
            val current = _status.value
            _status.value = statusOf(current.phase, current.message, current.port)
        }
    }

    private fun statusOf(phase: ConnectionPhase, message: String, port: Int?) = AdbStatus(
        phase = phase,
        message = message,
        port = port,
        wirelessDebugging = wirelessDebuggingEnabled(),
        paired = hasStoredIdentity(),
        pairingRejected = pairingRejected && phase != ConnectionPhase.CONNECTED,
        canSwitchOn = canSwitchWirelessDebugging(),
    )

    /** Sleep, unless the switch moves or a new pairing arrives first. */
    private suspend fun rest(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private suspend fun reconnectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (status.value.phase == ConnectionPhase.CONNECTED) {
                attempt = 0
                // The observer usually sees the switch go off first; this is
                // the fallback, so a dead connection never reads as live.
                if (wirelessDebuggingEnabled() == false) {
                    dropConnection("Wireless Debugging was turned off")
                    continue
                }
                rest(RECONNECT_CONNECTED_DELAY_MS)
                continue
            }

            val wirelessEnabled = wirelessDebuggingEnabled()
            if (wirelessEnabled == false) {
                setStatus(ConnectionPhase.DISCONNECTED, AdbReconnectPolicy.noServiceMessage(false), null)
                attempt = 0
                // Nothing to connect to until the switch moves, and the
                // observer wakes this the moment it does.
                rest(SWITCH_OFF_POLL_MS)
                continue
            }

            // A new install must be paired by the user first. In particular,
            // do not generate a fresh identity just because NSD advertises a
            // connect port; that would also make Forget Pairing ineffective
            // while this loop is between discovery and connect.
            if (!hasStoredIdentity()) {
                setStatus(ConnectionPhase.DISCONNECTED, "Pair this phone once to enable automatic reconnect", null)
                attempt = 0
                rest(RECONNECT_CONNECTED_DELAY_MS)
                continue
            }

            // adbd refused this key. Every retry costs five TLS handshakes in
            // Kadb and cannot succeed, so wait for a new pairing instead.
            if (pairingRejected) {
                setStatus(ConnectionPhase.ERROR, AdbReconnectPolicy.PAIRING_REJECTED_MESSAGE, null)
                rest(AdbReconnectPolicy.REJECTED_RETRY_MS)
                pairingRejected = false
                continue
            }

            val savedPort = savedConnectPort()
            var connected = false
            if (savedPort != null) {
                connected = tryReconnect(savedPort)
            }
            if (!connected && !pairingRejected && currentCoroutineContext().isActive) {
                val endpoints = discover()
                val target = if (savedPort == null) {
                    AdbReconnectPolicy.preferredConnectPort(null, endpoints)
                } else {
                    AdbReconnectPolicy.fallbackConnectPort(savedPort, endpoints)
                }
                if (target != null) {
                    connected = tryReconnect(target)
                }
            }
            if (connected || pairingRejected) {
                attempt = 0
                continue
            }

            setStatus(
                ConnectionPhase.DISCONNECTED,
                AdbReconnectPolicy.noServiceMessage(wirelessDebuggingEnabled()),
                savedPort,
            )
            rest(AdbReconnectPolicy.retryDelayMs(attempt))
            attempt++
        }
    }

    private suspend fun tryReconnect(port: Int): Boolean {
        if (!hasStoredIdentity()) return false
        return try {
            connect(port)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun savedConnectPort(): Int? =
        preferences.getInt(KEY_CONNECT_PORT, -1).takeIf { AdbServiceDiscovery.isValidAdbPort(it) }

    private fun hasStoredIdentity(): Boolean {
        val directory = identityDirectory()
        return File(directory, CERTIFICATE_FILE).isFile &&
            File(directory, PRIVATE_KEY_FILE).isFile &&
            File(directory, CERTIFICATE_FILE).length() > 0 &&
            File(directory, PRIVATE_KEY_FILE).length() > 0
    }

    private fun wirelessDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getString(appContext.contentResolver, WIRELESS_DEBUGGING_SETTING)
            ?.trim()
            ?.let { value ->
                when (value) {
                    "1", "true" -> true
                    "0", "false" -> false
                    else -> null
                }
            }
    }.getOrNull()

    override suspend fun execute(command: String, timeoutMs: Long): CommandResult {
        requireCommand(command)
        return runWithClient(timeoutMs) { adb ->
            val result = adb.shell(command)
            CommandResult(boundText(result.allOutput), result.exitCode)
        }
    }

    /** Execute a shell command and preserve stdout bytes exactly. */
    override suspend fun executeBytes(command: String, timeoutMs: Long): ByteArray {
        requireCommand(command)
        return runWithClient(timeoutMs) { adb ->
            val output = ByteArrayOutputStream()
            var exitCode = 0
            var errorOutput = ""
            adb.openShell(command).use { shell ->
                while (true) {
                    val packet = shell.read()
                    when (packet) {
                        is AdbShellPacket.StdOut -> {
                            if (output.size() + packet.payload.size > MAX_BINARY_BYTES) {
                                throw IOException("Binary command output exceeds ${MAX_BINARY_BYTES / (1024 * 1024)} MiB")
                            }
                            output.write(packet.payload)
                        }
                        is AdbShellPacket.StdError -> errorOutput = boundText(
                            errorOutput + String(packet.payload, StandardCharsets.UTF_8)
                        )
                        is AdbShellPacket.Exit -> {
                            exitCode = packet.payload.firstOrNull()?.toInt() ?: 0
                            break
                        }
                    }
                }
            }
            if (exitCode != 0) {
                throw IOException("Command failed with exit code $exitCode${if (errorOutput.isBlank()) "" else ": $errorOutput"}")
            }
            output.toByteArray()
        }
    }

    override suspend fun cancelActive() {
        val job: Job?
        val adb: Kadb?
        synchronized(lifecycleLock) {
            job = activeJob
            adb = activeClient ?: client
            generation++
        }
        job?.cancel(CancellationException("ADB operation cancelled"))
        closeQuietly(adb)
        synchronized(lifecycleLock) {
            if (client === adb) client = null
            activeClient = null
            activeJob = null
            connectedPort = null
            _status.value = statusOf(ConnectionPhase.DISCONNECTED, "ADB operation cancelled", null)
        }
    }

    override suspend fun disconnect() {
        val old: Kadb?
        synchronized(lifecycleLock) {
            generation++
            old = client
            client = null
            activeClient = null
            activeJob?.cancel(CancellationException("ADB disconnected"))
            activeJob = null
            connectedPort = null
            _status.value = statusOf(ConnectionPhase.DISCONNECTED, "Disconnected", null)
        }
        closeQuietly(old)
    }

    override suspend fun forgetPairing() {
        operationMutex.withLock {
            val old: Kadb?
            synchronized(lifecycleLock) {
                generation++
                old = client
                client = null
                activeClient = null
                activeJob?.cancel(CancellationException("Pairing forgotten"))
                activeJob = null
                connectedPort = null
                _status.value = statusOf(ConnectionPhase.DISCONNECTED, "Pairing identity removed", null)
            }
            closeQuietly(old)
            synchronized(identityLock) {
                val directory = identityDirectory()
                directory.listFiles()?.forEach { file ->
                    if (file.name == CERTIFICATE_FILE || file.name == PRIVATE_KEY_FILE) {
                        file.delete()
                    }
                }
                clearKadbCertMemory()
                identityReady = false
            }
            pairingRejected = false
            preferences.edit().remove(KEY_CONNECT_PORT).apply()
            refreshStatus()
        }
    }

    override suspend fun pullFile(localFile: File, remotePath: String, timeoutMs: Long): CommandResult =
        runWithClient(timeoutMs) { adb ->
            localFile.parentFile?.mkdirs()
            adb.pull(localFile, remotePath)
            CommandResult("Pulled ${localFile.length()} bytes", 0)
        }

    override suspend fun pushFile(localFile: File, remotePath: String, timeoutMs: Long): CommandResult =
        runWithClient(timeoutMs) { adb ->
            check(localFile.isFile) { "Local file does not exist: ${localFile.name}" }
            adb.push(localFile, remotePath)
            CommandResult("Pushed ${localFile.length()} bytes", 0)
        }

    override suspend fun installApk(localFile: File, replace: Boolean, timeoutMs: Long): CommandResult =
        runWithClient(timeoutMs) { adb ->
            check(localFile.isFile) { "APK file does not exist: ${localFile.name}" }
            val options = if (replace) arrayOf("-r", "-t") else arrayOf("-t")
            adb.install(localFile, *options)
            CommandResult("Installed ${localFile.name}", 0)
        }

    private suspend fun <T> runWithClient(
        timeoutMs: Long,
        block: (Kadb) -> T
    ): T = operationMutex.withLock {
        val adb: Kadb
        val token: Long
        synchronized(lifecycleLock) {
            adb = client ?: throw IOException("ADB is not connected")
            token = generation
        }
        val job = currentCoroutineContext()[Job]
        setActive(job, adb)
        try {
            currentCoroutineContext().ensureActive()
            return@withLock withTimeout(timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                runInterruptible(Dispatchers.IO) { block(adb) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setErrorIfCurrent(token, safeMessage(e, "ADB command failed"))
            throw e
        } finally {
            clearActive(job, adb)
        }
    }

    private fun beginOperation(phase: ConnectionPhase, message: String, port: Int?): Long {
        synchronized(lifecycleLock) {
            val token = ++generation
            _status.value = statusOf(phase, message, port)
            return token
        }
    }

    private fun setActive(job: Job?, adb: Kadb?) {
        synchronized(lifecycleLock) {
            activeJob = job
            activeClient = adb
        }
    }

    private fun clearActive(job: Job?, adb: Kadb?) {
        synchronized(lifecycleLock) {
            if (activeJob === job) activeJob = null
            if (activeClient === adb) activeClient = null
        }
    }

    private fun setErrorIfCurrent(token: Long, message: String) {
        synchronized(lifecycleLock) {
            if (generation == token) {
                _status.value = statusOf(ConnectionPhase.ERROR, message, connectedPort)
            }
        }
    }

    private fun setStatus(phase: ConnectionPhase, message: String, port: Int?) {
        synchronized(lifecycleLock) { _status.value = statusOf(phase, message, port) }
    }

    private fun ensureIdentity() {
        synchronized(identityLock) {
            if (identityReady) return
            val directory = identityDirectory().apply { mkdirs() }
            val certFile = File(directory, CERTIFICATE_FILE)
            val keyFile = File(directory, PRIVATE_KEY_FILE)
            if (certFile.isFile && keyFile.isFile && certFile.length() > 0 && keyFile.length() > 0) {
                try {
                    KadbCert.set(certFile.readBytes(), keyFile.readBytes())
                    identityReady = true
                    return
                } catch (_: Exception) {
                    certFile.delete()
                    keyFile.delete()
                }
            }
            val generated = KadbCert.get()
            atomicWrite(certFile, generated.first)
            atomicWrite(keyFile, generated.second)
            identityReady = true
        }
    }

    private fun clearKadbCertMemory() {
        // Kadb 1.3.0 has no public clear/rotate method. Its object fields are
        // private static state; clearing them here makes a fresh identity take
        // effect immediately after Forget Pairing instead of after process death.
        runCatching {
            listOf("cert", "key").forEach { name ->
                KadbCert::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                    set(null, byteArrayOf())
                }
            }
        }.getOrElse { throw IOException("Could not reset Kadb identity", it) }
    }

    private fun identityDirectory(): File = File(appContext.filesDir, IDENTITY_DIRECTORY)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.outputStream().use { it.write(bytes); it.flush() }
        check(temporary.renameTo(target)) { "Could not store Kadb identity" }
    }

    private fun closeQuietly(adb: Kadb?) {
        runCatching { adb?.close() }
    }

    private fun requireValidPort(port: Int) {
        require(AdbServiceDiscovery.isValidAdbPort(port)) { "ADB port must be between 1024 and 65535" }
    }

    private fun requireCommand(command: String) {
        require(command.isNotBlank()) { "ADB command cannot be empty" }
        require(!command.contains('\u0000')) { "ADB command contains NUL" }
    }

    private fun boundText(value: String): String =
        if (value.length <= MAX_TEXT_CHARS) value
        else value.take(MAX_TEXT_CHARS) + "\n[output truncated]"

    private fun safeMessage(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun DiscoveredAdbService.isPairingService(): Boolean =
        serviceType.trimEnd('.') == AdbServiceDiscovery.SERVICE_TYPE_PAIRING.trimEnd('.')

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val PREFERENCES_NAME = "adb_transport"
        private const val KEY_CONNECT_PORT = "last_connect_port"
        private const val WIRELESS_DEBUGGING_SETTING = "adb_wifi_enabled"
        private const val RECONNECT_CONNECTED_DELAY_MS = 5_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MAX_TEXT_CHARS = 1_000_000
        private const val MAX_BINARY_BYTES = 32 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
        private const val LOOPBACK = "127.0.0.1"
        private const val DEVICE_NAME = "hey-mike"
        private const val IDENTITY_DIRECTORY = "kadb_identity"
        private const val CERTIFICATE_FILE = "certificate.pem"
        private const val PRIVATE_KEY_FILE = "private_key.pem"
        private const val TAG = "AdbTransport"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private const val PER_USER_RANGE = 100_000
        private const val GRANT_TIMEOUT_MS = 10_000L
        private const val SWITCH_OFF_POLL_MS = 60_000L
        private val BUSY_OR_CONNECTED = setOf(ConnectionPhase.CONNECTED, ConnectionPhase.CONNECTING, ConnectionPhase.PAIRING)
    }
}
