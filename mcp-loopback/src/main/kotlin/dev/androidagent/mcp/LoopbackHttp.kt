package dev.androidagent.mcp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** The request line and headers of one HTTP/1.1 request. Header names are lower case. */
internal class HttpHead(val method: String, val path: String, val headers: Map<String, String>)

/** A request we answer with [status] and a short JSON error, without reading further. */
internal class HttpRejection(val status: Int, val extraHeaders: List<String> = emptyList()) : Exception()

internal const val MAX_HEAD_BYTES = 16 * 1024
private const val MAX_HEADERS = 100
private const val MAX_DRAIN_BYTES = 16L * 1024 * 1024

/** Reads the request line and headers. Throws [HttpRejection] for a bad head, EOFException if the peer left. */
internal fun readHead(input: InputStream): HttpHead {
    var budget = MAX_HEAD_BYTES
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("peer closed")
            if (--budget < 0) throw HttpRejection(431)
            if (b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    val parts = line().split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpRejection(400)
    val headers = HashMap<String, String>()
    while (true) {
        val header = line()
        if (header.isEmpty()) break
        val colon = header.indexOf(':')
        if (colon <= 0 || headers.size >= MAX_HEADERS) throw HttpRejection(400)
        val name = header.substring(0, colon).trim().lowercase()
        // Two lengths could make us read a different body than a proxy would.
        if (name == "content-length" && name in headers) throw HttpRejection(400)
        headers[name] = header.substring(colon + 1).trim()
    }
    return HttpHead(parts[0], parts[1].substringBefore('?'), headers)
}

/** Writes one complete response; every connection carries a single exchange. */
internal fun writeResponse(output: OutputStream, status: Int, body: String, extraHeaders: List<String> = emptyList()) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        if (bytes.isNotEmpty()) append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(bytes.size).append("\r\n")
        append("Connection: close\r\n")
        extraHeaders.forEach { append(it).append("\r\n") }
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.ISO_8859_1))
    output.write(bytes)
    output.flush()
}

/**
 * Ends a connection whose request body we did not read. Closing with unread input sends a reset,
 * which can discard the response before the client reads it, so read and drop what is left first.
 */
internal fun finishUnread(socket: Socket, input: InputStream) {
    runCatching {
        socket.shutdownOutput()
        socket.soTimeout = 1_000
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total < MAX_DRAIN_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
        }
    }
}

private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    202 -> "Accepted"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    411 -> "Length Required"
    413 -> "Content Too Large"
    431 -> "Request Header Fields Too Large"
    else -> "Error"
}
