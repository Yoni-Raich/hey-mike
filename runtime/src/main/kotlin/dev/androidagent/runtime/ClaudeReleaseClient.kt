/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 * Dual-licensed under AGPL-3.0-only or a commercial license. See LICENSE,
 * LICENSE-COMMERCIAL.md and NOTICE. Distributed without any warranty.
 */

package dev.androidagent.runtime

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider

/** Official HTTPS release metadata. The bundled GPG key authenticates the exact manifest bytes. */
class ClaudeReleaseClient(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun latest(): ClaudeBinaryPin = withContext(Dispatchers.IO) {
        val version = read("$BASE/latest", 128).toString(Charsets.UTF_8).trim()
        require(validVersion(version)) { "Invalid Claude release version" }
        val manifest = read("$BASE/$version/manifest.json", MAX_MANIFEST_BYTES)
        val signature = read("$BASE/$version/manifest.json.sig", 64 * 1024)
        verifySignature(manifest, signature)
        parseManifest(version, manifest.toString(Charsets.UTF_8))
    }

    private suspend fun read(address: String, limit: Int): ByteArray {
        val connection = openConnection(URL(address))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            // Refuse redirects: metadata and binaries must stay at the official origin.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            check(connection.responseCode == 200) { "Claude update metadata unavailable" }
            check(connection.contentLengthLong <= limit) { "Claude update metadata is too large" }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= limit) { "Claude update metadata is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE = "https://downloads.claude.ai/claude-code-releases"
        const val SIGNING_FINGERPRINT = "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE"
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        private val VERSION = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
        private val HASH = Regex("[0-9a-fA-F]{64}")
        fun validVersion(version: String): Boolean = VERSION.matches(version)

        /** Numeric comparison; no prereleases, URL components or downgrades. */
        fun newer(candidate: String, current: String): Boolean {
            if (!validVersion(candidate) || !validVersion(current)) return false
            val a = candidate.split('.').map(String::toInt)
            val b = current.split('.').map(String::toInt)
            for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }

        fun parseManifest(version: String, text: String): ClaudeBinaryPin {
            require(validVersion(version) && text.length <= MAX_MANIFEST_BYTES) { "Invalid Claude manifest" }
            val root = Json.parseToJsonElement(text) as JsonObject
            require(root["version"]?.jsonPrimitive?.content == version) { "Claude manifest version mismatch" }
            // Unknown major versions or stream harness schemas need an app compatibility update.
            require(version.substringBefore('.') == ClaudeBinaryPin.CURRENT.version.substringBefore('.')) {
                "This Claude release needs a Mike update"
            }
            val compat = root["sdkCompat"] as? JsonObject
            require(compat?.get("harnessSchema")?.jsonPrimitive?.longOrNull == 1L) {
                "Unsupported Claude stream protocol"
            }
            val platform = (root["platforms"] as? JsonObject)?.get("linux-arm64-musl") as? JsonObject
                ?: error("Claude release has no ARM64 musl binary")
            require(platform["binary"]?.jsonPrimitive?.content == "claude") { "Unexpected Claude binary" }
            val hash = platform["checksum"]?.jsonPrimitive?.content.orEmpty()
            val size = platform["size"]?.jsonPrimitive?.longOrNull ?: 0
            require(HASH.matches(hash) && size in 1..(1024L * 1024 * 1024)) { "Invalid Claude binary metadata" }
            return ClaudeBinaryPin(version, "$BASE/$version/linux-arm64-musl/claude", hash.lowercase(), size)
        }

        fun verifySignature(manifest: ByteArray, detached: ByteArray) {
            require(manifest.size <= MAX_MANIFEST_BYTES && detached.size <= 64 * 1024)
            val keyBytes = ClaudeReleaseClient::class.java.getResourceAsStream("/claude-code-release-key.asc")
                ?.use { it.readBytes() } ?: error("Claude release signing key missing")
            val rings = PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(keyBytes.inputStream()), BcKeyFingerprintCalculator())
            val signatures = PGPObjectFactory(PGPUtil.getDecoderStream(detached.inputStream()), BcKeyFingerprintCalculator())
                .nextObject() as? PGPSignatureList ?: error("Invalid Claude manifest signature")
            require(signatures.size() == 1) { "Invalid Claude manifest signature" }
            val signature = signatures[0]
            val key = rings.getPublicKey(signature.keyID) ?: error("Unknown Claude signing key")
            val fingerprint = key.fingerprint.joinToString("") { "%02X".format(it) }
            require(fingerprint == SIGNING_FINGERPRINT) { "Unknown Claude signing key" }
            require(signature.signatureType == PGPSignature.BINARY_DOCUMENT &&
                signature.hashAlgorithm in setOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512)) {
                "Unsupported Claude manifest signature"
            }
            signature.init(BcPGPContentVerifierBuilderProvider(), key)
            signature.update(manifest)
            check(signature.verify()) { "Claude manifest signature does not match" }
        }
    }
}
