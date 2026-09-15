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

import dev.androidagent.core.NetDiagnostics
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Lifecycle-owned localhost HTTP CONNECT proxy for the Codex app-server.
 *
 * Why it exists: the musl app-server cannot resolve DNS on Android
 * (/etc/resolv.conf is absent under the app UID), while Android/Bionic
 * (java.net) resolves fine. Tunnelling TLS through this proxy lets the
 * app-server reuse the platform resolver without any TLS interception.
 *
 * Safety contract:
 * - Binds only 127.0.0.1 on an ephemeral port; never 0.0.0.0.
 * - Accepts CONNECT only, port 443 only, strict host allowlist
 *   ([NetDiagnostics.defaultAllowedHosts] plus observed additions).
 * - Tunnels bytes blindly: no TLS MITM, no decryption, no header/body/token
 *   logging. Log output is at most host:port plus allow/deny/error category.
 * - Supports long-lived streaming (no read timeout) and TCP half-close.
 * - [stop] closes the listener and every active tunnel immediately.
 */
class LocalhostConnectProxy(
    allowedHosts: Set<String> = NetDiagnostics.defaultAllowedHosts,
    private val allowedPorts: Set<Int> = NetDiagnostics.defaultAllowedPorts,
    private val listener: ProxyEventListener? = null,
    private val upstreamConnectTimeoutMs: Int = 30_000
) : Closeable {

    interface ProxyEventListener {
        fun onListening(port: Int)
        fun onAllowed(host: String, port: Int)
        fun onDenied(host: String, port: Int, reason: String)
        fun onError(category: String)
        fun onStopped()
    }

    private val allowedHostSet: Set<String> = allowedHosts.map { it.lowercase() }.toSet()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** Bound port, or -1 when stopped. */
    val localPort: Int get() = server?.takeIf { running.get() }?.localPort ?: -1

    /** Proxy URL for HTTPS_PROXY/HTTP_PROXY (upper and lowercase). */
    fun proxyUrl(): String = "http://127.0.0.1:$localPort"

    fun isRunning(): Boolean = running.get() && server?.let { it.isBound && !it.isClosed } == true

    /**
     * Start the listener. Returns the ephemeral port. Throws if the socket
     * cannot be bound to 127.0.0.1.
     */
    @Synchronized
    fun start(): Int {
        if (isRunning()) return localPort
        val socket = ServerSocket()
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server = socket
        running.set(true)
        acceptThread = thread(name = "codex-connect-proxy", isDaemon = true) { acceptLoop() }
        listener?.onListening(socket.localPort)
        return socket.localPort
    }

    /**
     * Prove the proxy is actually accepting connections before the caller
     * spawns the app-server. Opens a short TCP probe to 127.0.0.1:[port]
     * and immediately closes it; the probe sends nothing.
     */
    fun verifyListening(timeoutMs: Int = 2_000): Boolean {
        val port = localPort
        if (port <= 0 || !isRunning()) return false
        return runCatching {
            Socket().use { probe ->
                probe.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    /** Stop the listener and close every active tunnel immediately. */
    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
        val thread = acceptThread
        acceptThread = null
        if (thread != null && Thread.currentThread() !== thread) {
            runCatching { thread.join(2_000) }
        }
        listener?.onStopped()
    }

    override fun close() = stop()

    private fun acceptLoop() {
        val srv = server ?: return
        while (running.get()) {
            val client = try {
                srv.accept()
            } catch (_: Exception) {
                break
            }
            // Defense in depth: only loopback peers are ever served.
            if (!client.inetAddress.isLoopbackAddress) {
                runCatching { client.close() }
                listener?.onDenied("", 0, "non-loopback-peer")
                continue
            }
            activeSockets.add(client)
            thread(name = "codex-connect-tunnel", isDaemon = true) {
                try {
                    handleClient(client)
                } finally {
                    activeSockets.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.keepAlive = true
        client.soTimeout = 15_000
        val head = readHead(client.getInputStream()) ?: run {
            listener?.onDenied("", 0, "unreadable-head")
            return
        }
        when (val check = NetDiagnostics.checkConnectRequest(head, allowedHostSet, allowedPorts)) {
            is NetDiagnostics.ConnectCheck.Deny -> {
                val status = when (check.reason) {
                    "method-not-allowed" -> "HTTP/1.1 405 Method Not Allowed\r\n"
                    else -> "HTTP/1.1 403 Forbidden\r\n"
                }
                runCatching {
                    client.getOutputStream().write((status + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                }
                listener?.onDenied("", 0, check.reason)
            }
            is NetDiagnostics.ConnectCheck.Allow -> {
                val target = check.target
                val upstream = Socket()
                upstream.keepAlive = true
                // No read timeout: streaming responses stay open for minutes.
                upstream.soTimeout = 0
                try {
                    upstream.connect(InetSocketAddress(target.host, target.port), upstreamConnectTimeoutMs)
                } catch (e: java.net.UnknownHostException) {
                    denyUpstream(client, "502", "dns")
                    return
                } catch (e: java.net.SocketTimeoutException) {
                    denyUpstream(client, "504", "timeout")
                    return
                } catch (e: java.io.IOException) {
                    denyUpstream(client, "502", "connection")
                    return
                }
                activeSockets.add(upstream)
                try {
                    client.getOutputStream().write(
                        ("HTTP/1.1 200 Connection Established\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Proxy-Agent: AndroidAgent-Localhost\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    )
                    client.getOutputStream().flush()
                    // Bytes flow blindly from here on; nothing is inspected.
                    client.soTimeout = 0
                    listener?.onAllowed(target.host, target.port)
                    relay(client, upstream)
                } finally {
                    activeSockets.remove(upstream)
                    runCatching { upstream.close() }
                }
            }
        }
    }

    private fun denyUpstream(client: Socket, status: String, category: String) {
        runCatching {
            client.getOutputStream().write(
                ("HTTP/1.1 $status Proxy Upstream Failed\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            client.getOutputStream().flush()
        }
        listener?.onError(category)
    }

    private fun relay(client: Socket, upstream: Socket) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val upstreamIn = upstream.getInputStream()
        val upstreamOut = upstream.getOutputStream()
        val first = thread(name = "codex-relay-up", isDaemon = true) {
            copyBlind(clientIn, upstreamOut, upstream)
        }
        copyBlind(upstreamIn, clientOut, client)
        runCatching { first.join(5_000) }
    }

    /**
     * Blind byte copy. On EOF of [input], half-close [halfCloseTarget] so
     * long-lived streaming sessions terminate cleanly in each direction.
     */
    private fun copyBlind(input: InputStream, output: OutputStream, halfCloseTarget: Socket) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
            // Tunnel reset by either peer; the other direction ends with it.
        } finally {
            runCatching { halfCloseTarget.shutdownOutput() }
        }
    }

    companion object {
        /**
         * Read HTTP request head bytes (headers only) up to 8 KiB.
         * Returns null when the head is missing, oversized, or unreadable.
         */
        fun readHead(input: InputStream, maxBytes: Int = 8 * 1024): String? {
            return runCatching {
                val sink = ByteArrayOutputStream()
                val one = ByteArray(1)
                while (sink.size() < maxBytes) {
                    val count = input.read(one)
                    if (count < 0) break
                    sink.write(one[0].toInt())
                    val bytes = sink.toByteArray()
                    if (bytes.size >= 4 &&
                        bytes[bytes.size - 4] == '\r'.code.toByte() &&
                        bytes[bytes.size - 3] == '\n'.code.toByte() &&
                        bytes[bytes.size - 2] == '\r'.code.toByte() &&
                        bytes[bytes.size - 1] == '\n'.code.toByte()
                    ) {
                        break
                    }
                }
                if (sink.size() >= maxBytes) return null
                val text = sink.toString(Charsets.US_ASCII.name())
                if (!text.contains("\r\n\r\n")) null else text
            }.getOrNull()
        }
    }
}
