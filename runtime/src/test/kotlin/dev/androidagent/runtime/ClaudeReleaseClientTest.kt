/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClaudeReleaseClientTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/claude/$name")!!.use { it.readBytes() }
    private val manifest get() = bytes("manifest-2.1.293.json")
    private val signature get() = bytes("manifest-2.1.293.json.sig")

    @Test fun officialSignedManifestMatchesBundledPin() {
        ClaudeReleaseClient.verifySignature(manifest, signature)
        assertEquals(ClaudeBinaryPin.CURRENT, ClaudeReleaseClient.parseManifest("2.1.293", manifest.toString(Charsets.UTF_8)))
    }

    @Test fun changedManifestCannotUseOriginalSignature() {
        val changed = manifest.copyOf().also { it[it.indexOf('2'.code.toByte())] = '3'.code.toByte() }
        assertThrows(Exception::class.java) { ClaudeReleaseClient.verifySignature(changed, signature) }
    }

    @Test fun invalidOrMissingSignatureIsRefused() {
        assertThrows(Exception::class.java) { ClaudeReleaseClient.verifySignature(manifest, byteArrayOf()) }
        assertThrows(Exception::class.java) { ClaudeReleaseClient.verifySignature(manifest, "not signed".toByteArray()) }
    }

    @Test fun latestChecksSignatureBeforeReturningMetadata() = runBlocking {
        val requests = mutableListOf<String>()
        val client = ClaudeReleaseClient { url ->
            requests += url.toString()
            val body = when {
                url.path.endsWith("/latest") -> "2.1.293\n".toByteArray()
                url.path.endsWith(".sig") -> signature
                else -> manifest
            }
            Connection(url, body)
        }
        assertEquals(ClaudeBinaryPin.CURRENT, client.latest())
        assertEquals(listOf(
            "${ClaudeReleaseClient.BASE}/latest",
            "${ClaudeReleaseClient.BASE}/2.1.293/manifest.json",
            "${ClaudeReleaseClient.BASE}/2.1.293/manifest.json.sig",
        ), requests)
    }

    @Test fun redirectsAndHtmlVersionRepliesAreRejected() = runBlocking {
        for ((code, reply) in listOf(302 to "2.1.293", 200 to "<html>error</html>", 200 to "../../escape", 200 to "2.1.294\nextra")) {
            val client = ClaudeReleaseClient { Connection(it, reply.toByteArray(), code) }
            try { client.latest(); fail("bad metadata accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }

    @Test fun oversizedMetadataIsRejectedEvenWithoutContentLength() = runBlocking {
        val client = ClaudeReleaseClient { Connection(it, ByteArray(1024), length = -1) }
        try { client.latest(); fail("oversized reply accepted") } catch (_: IllegalStateException) {}
    }

    @Test fun unknownProtocolMajorVersionMissingArm64AndInvalidSizeAreRefused() {
        val text = manifest.toString(Charsets.UTF_8)
        for (bad in listOf(
            text.replace("\"harnessSchema\": 1", "\"harnessSchema\": 2"),
            text.replace("linux-arm64-musl", "unavailable"),
            text.replace("244463424", "-1"),
            text.replace("244463424", "99999999999999999999999"),
            text.replace("\"binary\": \"claude\"", "\"binary\": \"../claude\""),
        )) {
            assertThrows(Exception::class.java) { ClaudeReleaseClient.parseManifest("2.1.293", bad) }
        }
        assertThrows(Exception::class.java) { ClaudeReleaseClient.parseManifest("3.1.293", text.replace("2.1.293", "3.1.293")) }
        assertThrows(Exception::class.java) { ClaudeReleaseClient.parseManifest("2.1.294", text) }
    }

    @Test fun versionComparisonIsNumericAndNeverAcceptsUnsafePathsOrPrereleases() {
        assertTrue(ClaudeReleaseClient.newer("2.1.300", "2.1.99"))
        assertFalse(ClaudeReleaseClient.newer("2.1.293", "2.1.293"))
        assertFalse(ClaudeReleaseClient.newer("2.1.9", "2.1.293"))
        listOf("../2.1.300", "2.1.300-beta", "2.1.300/claude", "999999999999.1.1").forEach {
            assertFalse(ClaudeReleaseClient.validVersion(it))
            assertFalse(ClaudeReleaseClient.newer(it, "2.1.293"))
        }
    }

    private class Connection(url: URL, val body: ByteArray, val code: Int = 200, val length: Long = body.size.toLong()) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = length
    }
}
