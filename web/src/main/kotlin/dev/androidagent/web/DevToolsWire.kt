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

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * One byte stream to a DevTools server.
 *
 * The protocol on it is the same wherever the server lives: a TCP port on a
 * computer's Chrome, or the abstract local socket the phone's own WebView
 * opens. Keeping the stream apart from the protocol is what lets everything
 * above it run in a JVM test against a desktop browser.
 */
class DevToolsStream(
    val input: InputStream,
    val output: OutputStream,
    private val onClose: () -> Unit,
    val onReady: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { onClose() }
    }
}

/** Opens a fresh stream per call: one for a discovery request, one for the session. */
fun interface DevToolsDialer {
    @Throws(IOException::class)
    fun dial(): DevToolsStream
}

/** The one HTTP call DevTools needs: list the pages. */
internal object DevToolsHttp {
    fun get(dialer: DevToolsDialer, path: String): String = dialer.dial().use { stream ->
        stream.output.write(
            ("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
        )
        stream.output.flush()
        val head = readHead(stream.input)
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 200")) throw IOException("DevTools answered \"$status\" to $path")
        val length = header(head, "content-length")?.toIntOrNull()
        val body = if (length != null) readExactly(stream.input, length) else stream.input.readBytes()
        body.toString(Charsets.UTF_8)
    }
}

/**
 * A WebSocket client with only what DevTools uses: text frames, pings and close.
 *
 * Written out rather than taken from a library because the phone side is a
 * `LocalSocket`, which is not a `java.net.Socket` and so fits no client's
 * socket factory.
 */
internal class DevToolsSocket(private val stream: DevToolsStream) : Closeable {
    private val random = SecureRandom()
    private val writeLock = Any()

    fun handshake(path: String) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        // No Origin header: DevTools refuses a socket that names an origin it
        // was not told to allow, and a native client has none.
        stream.output.write(
            (
                "GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        stream.output.flush()
        val status = readHead(stream.input).lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 101")) throw IOException("DevTools refused the socket: \"$status\"")
        stream.onReady()
    }

    fun send(text: String) = write(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** The next text message, or null once the server closed the socket. */
    fun receive(): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = stream.input.read()
            if (first < 0) return null
            val second = stream.input.read()
            if (second < 0) return null
            val opcode = first and 0x0F
            val length = when (val short = second and 0x7F) {
                126 -> readExactly(stream.input, 2).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                127 -> readExactly(stream.input, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                else -> short.toLong()
            }
            if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("DevTools frame of $length bytes")
            // A server never masks, but one that did would still be readable.
            val mask = if (second and 0x80 != 0) readExactly(stream.input, 4) else null
            val payload = readExactly(stream.input, length.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                OP_CLOSE -> return null
                OP_PING -> write(OP_PONG, payload)
                OP_PONG -> Unit
                else -> {
                    if (message.size().toLong() + payload.size > MAX_FRAME_BYTES) throw IOException("DevTools message is too large")
                    message.write(payload)
                    if (first and 0x80 != 0) return message.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }
    }

    private fun write(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(0x80 or 126)
                frame.write(payload.size ushr 8)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        // A client must mask every frame it sends.
        val mask = ByteArray(4).also(random::nextBytes)
        frame.write(mask)
        for (i in payload.indices) frame.write(payload[i].toInt() xor mask[i % 4].toInt())
        synchronized(writeLock) {
            stream.output.write(frame.toByteArray())
            stream.output.flush()
        }
    }

    override fun close() = stream.close()

    private companion object {
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        /** A full-page screenshot is a few megabytes; nothing honest is larger than this. */
        const val MAX_FRAME_BYTES = 64L * 1024 * 1024
    }
}

private fun readHead(input: InputStream): String {
    val head = ByteArrayOutputStream()
    var matched = 0
    while (matched < 4) {
        val b = input.read()
        if (b < 0) throw EOFException("DevTools closed the connection before answering")
        head.write(b)
        matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        if (head.size() > 64 * 1024) throw IOException("DevTools sent an oversized header")
    }
    return head.toByteArray().toString(Charsets.US_ASCII)
}

private fun header(head: String, name: String): String? =
    head.lineSequence().drop(1)
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')?.trim()

private fun readExactly(input: InputStream, count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(bytes, read, count - read)
        if (n < 0) throw EOFException("DevTools closed the connection mid-message")
        read += n
    }
    return bytes
}
