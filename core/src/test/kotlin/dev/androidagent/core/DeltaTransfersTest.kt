package dev.androidagent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DeltaTransfersTest {
    @get:Rule val temp = TemporaryFolder()
    private fun identity(bytes: ByteArray) = TransferBlob(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    private val oldBytes = "PK old APK bytes".toByteArray()
    private val newBytes = "PK new complete signed APK bytes".toByteArray()
    private val patchBytes = "diff".toByteArray()
    private val old get() = identity(oldBytes)
    private val new get() = identity(newBytes)
    private val patch get() = identity(patchBytes)

    private inner class Cache : TransferBlobCache {
        val root = temp.newFolder()
        val items = mutableMapOf<TransferBlob, File>()
        var space = true
        override suspend fun <T> locked(block: suspend () -> T) = block()
        override suspend fun candidates() = items.keys.toList()
        override suspend fun verified(blob: TransferBlob) = items[blob]?.takeIf { identity(it.readBytes()) == blob }
        override fun temporary() = File.createTempFile("part-", ".part", root)
        override fun hasSpace(bytes: Long) = space
        override suspend fun publish(file: File, blob: TransferBlob): File {
            check(identity(file.readBytes()) == blob) { "Wrong bytes" }
            val ready = File(root, blob.sha256).apply { writeBytes(file.readBytes()) }
            items[blob] = ready
            return ready
        }
        override suspend fun materialize(blob: File, target: File) { target.writeBytes(blob.readBytes()) }
        fun seed(bytes: ByteArray) { val blob = identity(bytes); items[blob] = File(root, blob.sha256).apply { writeBytes(bytes) } }
    }
    private inner class Peer(var useDelta: Boolean = false) : DeltaPeer {
        val prepared = mutableListOf<Pair<String, Boolean>>()
        var fetched = 0
        var fallbackTarget = new
        var corruptPayload = false
        var beforeFetch: suspend () -> Unit = {}
        override suspend fun prepare(path: String, bases: List<TransferBlob>, allowDelta: Boolean, phase: (FileTransfer.Phase) -> Unit): DeltaManifest {
            prepared += path to allowDelta
            return if (useDelta && allowDelta) DeltaManifest(new, patch, "patch", "next.apk", old, DeltaManifest.FORMAT)
                else DeltaManifest(if (path.startsWith("sha256:")) fallbackTarget else new, if (path.startsWith("sha256:")) fallbackTarget else new, "full", "next.apk")
        }
        override suspend fun fetch(path: String, target: File, limit: Long, progress: (Long, Long) -> Unit) {
            beforeFetch(); fetched++
            val bytes = if (path == "patch") patchBytes else newBytes
            check(bytes.size <= limit)
            target.writeBytes(if (corruptPayload) ByteArray(bytes.size) else bytes)
            progress(bytes.size.toLong(), bytes.size.toLong())
        }
    }
    private class Applier : DeltaApplier {
        override var available = true
        var calls = 0
        var body: suspend (File) -> Unit = {}
        override suspend fun apply(base: File, patch: File, output: File, expected: TransferBlob, progress: (Long, Long) -> Unit) { calls++; body(output) }
    }
    private fun target() = File(temp.root, "destination-${System.nanoTime()}.apk")

    @Test fun firstFullCopyIsVerifiedAndAnotherChatReusesItWithoutFetching() = runBlocking {
        val cache = Cache(); val peer = Peer(); val service = DeltaTransfers(cache, Applier()); val first = target()
        assertEquals(new.size, service.download(peer, "build/new.apk", first) { _, _ -> }.size)
        val otherChat = target(); service.download(peer, "different/path.apk", otherChat) { _, _ -> }
        assertArrayEquals(newBytes, first.readBytes()); assertArrayEquals(newBytes, otherChat.readBytes()); assertEquals(1, peer.fetched)
    }
    @Test fun matchingBaseReceivesOnlyThePatchAndVerifiesTheReconstructedApk() = runBlocking {
        val cache = Cache().apply { seed(oldBytes) }; val peer = Peer(true)
        val applier = Applier().apply { body = { it.writeBytes(newBytes) } }; val destination = target()
        DeltaTransfers(cache, applier).download(peer, "new.apk", destination) { _, _ -> }
        assertEquals(1, peer.fetched); assertEquals(1, applier.calls); assertArrayEquals(newBytes, destination.readBytes()); assertNotNull(cache.verified(new))
    }
    @Test fun missingBaseFallsBackToTheSameImmutableTarget() = runBlocking {
        val peer = Peer(true); val destination = target()
        DeltaTransfers(Cache(), Applier()).download(peer, "mutable/build.apk", destination) { _, _ -> }
        assertEquals("sha256:${new.sha256}", peer.prepared[1].first); assertFalse(peer.prepared[1].second)
        assertArrayEquals(newBytes, destination.readBytes())
    }
    @Test fun badReconstructionFallsBackButNeverPublishesTheBadBytes() = runBlocking {
        val cache = Cache().apply { seed(oldBytes) }; val peer = Peer(true); val destination = target()
        val applier = Applier().apply { body = { it.writeBytes(ByteArray(newBytes.size)) } }
        DeltaTransfers(cache, applier).download(peer, "new.apk", destination) { _, _ -> }
        assertEquals(2, peer.fetched); assertArrayEquals(newBytes, destination.readBytes())
    }
    @Test fun stopInsideNativeDoesNotStartFullTransferOrPublish() = runBlocking {
        val cache = Cache().apply { seed(oldBytes) }; val peer = Peer(true); val destination = target()
        val applier = Applier().apply { body = { throw CancellationException("Stopped") } }
        try { DeltaTransfers(cache, applier).download(peer, "new.apk", destination) { _, _ -> }; fail() } catch (_: CancellationException) { }
        assertEquals(1, peer.prepared.size); assertEquals(1, peer.fetched); assertFalse(destination.exists())
    }
    @Test fun cancelledFetchDoesNotGenerateFallback() = runBlocking {
        val peer = Peer(true).apply { beforeFetch = { throw CancellationException("Stopped") } }; val destination = target()
        try { DeltaTransfers(Cache(), Applier()).download(peer, "new.apk", destination) { _, _ -> }; fail() } catch (_: CancellationException) { }
        assertEquals(1, peer.prepared.size); assertFalse(destination.exists())
    }
    @Test fun fallbackCannotSilentlyChangeToANewerBuild() = runBlocking {
        val peer = Peer(true).apply { fallbackTarget = old }; val destination = target().apply { writeText("previous destination") }
        try { DeltaTransfers(Cache(), Applier()).download(peer, "new.apk", destination) { _, _ -> }; fail() } catch (_: IllegalStateException) { }
        assertEquals("previous destination", destination.readText())
    }
    @Test fun corruptFullPayloadLeavesExistingDestinationUntouched() = runBlocking {
        val destination = target().apply { writeText("previous destination") }
        try { DeltaTransfers(Cache(), Applier()).download(Peer().apply { corruptPayload = true }, "new.apk", destination) { _, _ -> }; fail() } catch (_: IllegalStateException) { }
        assertEquals("previous destination", destination.readText())
    }
    @Test fun missingNativeSupportNegotiatesFullAndStillUsesTheCache() = runBlocking {
        val peer = Peer(); val applier = Applier().apply { available = false }
        DeltaTransfers(Cache(), applier).download(peer, "new.apk", target()) { _, _ -> }
        assertFalse(peer.prepared.single().second); assertEquals(0, applier.calls)
    }
    @Test fun stoppedWaiterDoesNotInterruptTheOtherChatsTransfer() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val peer = Peer().apply { beforeFetch = { started.complete(Unit); finish.await() } }
        val service = DeltaTransfers(Cache(), Applier()); val firstTarget = target()
        val first = async { service.download(peer, "first.apk", firstTarget) { _, _ -> } }
        started.await()
        val waiting = async { service.download(Peer(), "second.apk", target()) { _, _ -> } }
        waiting.cancelAndJoin(); finish.complete(Unit); first.await()
        assertArrayEquals(newBytes, firstTarget.readBytes())
    }
    @Test fun manifestRejectsUnknownFormatAndPathTraversalName() {
        for (manifest in listOf(DeltaManifest(new, new, "cache", "../escape.apk"), DeltaManifest(new, patch, "cache", "file.apk", old, "unknown"))) {
            try { manifest.validate(); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
