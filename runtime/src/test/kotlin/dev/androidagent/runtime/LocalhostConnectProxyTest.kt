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

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalhostConnectProxyTest {
    @Test
    fun `proxy accepts loopback connect and relays bytes`() {
        val upstream = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val received = CountDownLatch(1)
        val upstreamThread = Thread {
            upstream.use { server ->
                server.accept().use { socket ->
                    val bytes = ByteArray(4)
                    readExact(socket.getInputStream(), bytes)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                    received.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = LocalhostConnectProxy(setOf("localhost"), setOf(upstream.localPort))
        try {
            val proxyPort = proxy.start()
            assertTrue(proxy.verifyListening())
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.getOutputStream().write(
                    "CONNECT localhost:${upstream.localPort} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()
                )
                client.getOutputStream().flush()
                val response = readHead(client)
                assertTrue(response.startsWith("HTTP/1.1 200"))
                client.getOutputStream().write("ping".toByteArray())
                client.getOutputStream().flush()
                val echoed = ByteArray(4)
                readExact(client.getInputStream(), echoed)
                assertEquals("ping", String(echoed))
            }
            assertTrue(received.await(3, TimeUnit.SECONDS))
        } finally {
            proxy.stop()
            upstream.close()
            upstreamThread.join(1_000)
        }
    }

    @Test
    fun `proxy rejects non connect requests`() {
        val proxy = LocalhostConnectProxy(setOf("localhost"), setOf(443))
        try {
            val port = proxy.start()
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                assertTrue(readHead(client).startsWith("HTTP/1.1 405"))
            }
        } finally {
            proxy.stop()
        }
    }

    private fun readHead(socket: Socket): String {
        val out = StringBuilder()
        while (!out.endsWith("\r\n\r\n")) out.append(socket.getInputStream().read().toChar())
        return out.toString()
    }

    private fun readExact(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            assertTrue("unexpected end of stream", count >= 0)
            offset += count
        }
    }
}
