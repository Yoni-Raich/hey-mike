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

package dev.androidagent.runtime

import android.content.Context
import android.util.Log
import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.EngineKind
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the official, unmodified Claude Code binary on this phone.
 *
 * Launch: `<nativeLibraryDir>/libld_musl.so <files>/runtime/claude/<version>/claude <args>`.
 * targetSdk 35 cannot exec app files, so the pinned musl loader (packaged as
 * a lib*.so by tools/prepare_runtime.py) maps the binary. The binary is never
 * exec'd directly, never patched and never bundled: [installer] downloads
 * and verifies it.
 *
 * Environment: see [ClaudeEnvironment]. HOME is `<files>/runtime/claude-home`,
 * CLAUDE_CONFIG_DIR is `$HOME/.claude`, TMPDIR is `<files>/runtime/claude-tmp`,
 * and all traffic goes through this host's own [LocalhostConnectProxy], which
 * only reaches the Claude hosts (`NetDiagnostics.claudeAllowedHosts`) on 443.
 *
 * The app never reads anything under CLAUDE_CONFIG_DIR. This host only
 * creates the directory; sign-in state comes from `claude auth status`.
 * Both claude-home and the binary are excluded from backup and transfer.
 *
 * Only arm64-v8a APKs carry the loader. Elsewhere the state is UNSUPPORTED
 * and nothing is downloaded or started.
 */
class AndroidClaudeHost(
    private val appContext: Context,
    val installer: ClaudeRuntimeInstaller = defaultInstaller(appContext),
) : ClaudeProcessHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val status: StateFlow<RuntimeStatus> = installer.state
        .map(::statusOf)
        .stateIn(scope, SharingStarted.Eagerly, statusOf(installer.state.value))

    /** Download progress with bytes, for the UI. */
    val installState: StateFlow<ClaudeInstallState> get() = installer.state

    /** <files>/runtime */
    val runtimeRoot: File get() = runtimeRoot(appContext)

    /** Private HOME for `claude`. */
    override val homeDirectory: File get() = File(runtimeRoot, "claude-home")

    /** CLAUDE_CONFIG_DIR. Holds the user's Claude sign-in; the app never reads it. */
    val configDirectory: File get() = ClaudeEnvironment.configDirectory(homeDirectory)

    /** Private TMPDIR, a sibling of HOME. */
    val tmpDirectory: File get() = File(runtimeRoot, "claude-tmp")

    /** The packaged musl loader. Present only in arm64-v8a installs. */
    val loaderFile: File get() = loaderFile(appContext)

    /** False when this install has no arm64 loader, for example on an x86_64 emulator. */
    val isSupported: Boolean get() = loaderFile.isFile

    private val lock = Mutex()
    private val processes = mutableListOf<Process>()
    private var proxy: LocalhostConnectProxy? = null

    override suspend fun prepare() {
        withContext(Dispatchers.IO) { createDirectories() }
        installer.install()
    }

    override suspend fun start(args: List<String>, workingDirectory: File, extraEnv: Map<String, String>): Process =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val loader = loaderFile
                if (!loader.isFile) error(ClaudeRuntimeInstaller.UNSUPPORTED_MESSAGE)
                val binary = installer.refresh() ?: error("Download Claude Code in Settings")
                createDirectories()
                require(workingDirectory.isDirectory) { "Working directory missing: ${workingDirectory.absolutePath}" }
                val proxyUrl = ensureProxyLocked()
                val caPath = runCatching { BundledCaFile.stage(appContext, runtimeRoot) }
                    .onFailure { Log.w(TAG, "CA bundle unavailable: ${it.javaClass.simpleName}") }
                    .getOrNull()
                val childEnv = ClaudeEnvironment.build(
                    inherited = System.getenv().orEmpty(),
                    homeDirectory = homeDirectory,
                    tmpDirectory = tmpDirectory,
                    proxyUrl = proxyUrl,
                    caFileAbsolutePath = caPath,
                    extraEnv = extraEnv,
                )
                val process = ProcessBuilder(launchCommand(loader, binary, args))
                    .directory(workingDirectory)
                    .apply {
                        environment().clear()
                        environment().putAll(childEnv)
                        redirectErrorStream(false)
                    }
                    .start()
                synchronized(processes) {
                    processes.removeAll { !it.isAlive }
                    processes += process
                }
                process
            }
        }

    override suspend fun stopAll() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val running = synchronized(processes) { processes.toList().also { processes.clear() } }
                stopProcesses(running, STOP_GRACE_MILLIS)
                proxy?.stop()
                proxy = null
            }
        }
    }

    private fun createDirectories() {
        listOf(runtimeRoot, homeDirectory, configDirectory, tmpDirectory).forEach { dir ->
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("Cannot create ${dir.absolutePath}")
        }
    }

    /** One proxy for all `claude` processes; caller holds [lock]. */
    private fun ensureProxyLocked(): String {
        val current = proxy
        if (current != null && current.verifyListening()) return current.proxyUrl()
        current?.stop()
        val next = LocalhostConnectProxy.forEngine(EngineKind.CLAUDE, ProxyLog)
        proxy = next
        runCatching { next.start() }.getOrElse {
            proxy = null
            throw IllegalStateException("Could not start localhost proxy", it)
        }
        if (!next.verifyListening()) {
            next.stop()
            proxy = null
            error("Localhost proxy is not listening; refusing to start Claude Code")
        }
        return next.proxyUrl()
    }

    /** host:port and allow/deny only; never tunnel bytes. */
    private object ProxyLog : LocalhostConnectProxy.ProxyEventListener {
        override fun onListening(port: Int) = Unit
        override fun onAllowed(host: String, port: Int) { Log.i(TAG, "CONNECT $host:$port") }
        override fun onDenied(host: String, port: Int, reason: String) {
            Log.w(TAG, "denied ${host.ifBlank { "unknown" }}${if (port > 0) ":$port" else ""}: $reason")
        }
        override fun onError(category: String) { Log.w(TAG, "proxy-error:$category") }
        override fun onStopped() = Unit
    }

    companion object {
        private const val TAG = "AndroidClaudeHost"
        const val LOADER_LIB_NAME = "libld_musl.so"
        const val STOP_GRACE_MILLIS = 2_000L

        fun runtimeRoot(context: Context): File = File(context.filesDir, "runtime")

        fun loaderFile(context: Context): File = File(context.applicationInfo.nativeLibraryDir, LOADER_LIB_NAME)

        /** `<files>/runtime/claude/<version>/claude`, UNSUPPORTED without the loader. */
        fun defaultInstaller(context: Context): ClaudeRuntimeInstaller = ClaudeRuntimeInstaller(
            installRoot = File(runtimeRoot(context), "claude"),
            supported = loaderFile(context).isFile,
        )

        /** The loader runs the binary; the binary itself is never exec'd. */
        fun launchCommand(loader: File, binary: File, args: List<String>): List<String> =
            listOf(loader.absolutePath, binary.absolutePath) + args

        fun statusOf(state: ClaudeInstallState): RuntimeStatus = when (state.phase) {
            ClaudeInstallPhase.NOT_INSTALLED, ClaudeInstallPhase.CANCELLED ->
                RuntimeStatus(RuntimePhase.MISSING, state.message.ifBlank { "Claude Code is not downloaded" })
            ClaudeInstallPhase.DOWNLOADING, ClaudeInstallPhase.VERIFYING -> RuntimeStatus(
                RuntimePhase.PREPARING,
                state.message,
                progress = if (state.totalBytes > 0) (state.bytesDownloaded.toFloat() / state.totalBytes).coerceIn(0f, 1f) else null,
            )
            ClaudeInstallPhase.INSTALLED -> RuntimeStatus(RuntimePhase.READY, state.message)
            ClaudeInstallPhase.FAILED -> RuntimeStatus(RuntimePhase.ERROR, state.message)
            ClaudeInstallPhase.UNSUPPORTED -> RuntimeStatus(RuntimePhase.ERROR, ClaudeRuntimeInstaller.UNSUPPORTED_MESSAGE)
        }

        /** destroy() every process, wait up to [graceMillis] in total, then destroyForcibly() the rest. */
        fun stopProcesses(processes: List<Process>, graceMillis: Long) {
            processes.forEach { runCatching { it.destroy() } }
            val deadline = System.nanoTime() + graceMillis * 1_000_000
            while (processes.any { it.isAlive } && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            processes.filter { it.isAlive }.forEach { process ->
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
        }
    }
}
