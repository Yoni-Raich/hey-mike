package dev.androidagent.workspace

import dev.androidagent.core.TransferBlob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class TransferBlobStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun blob(bytes: ByteArray) = TransferBlob(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    @Test fun cacheSurvivesANewStoreAndDoesNotTrustSameSizeCorruption() = runBlocking {
        val folder = temp.newFolder(); val store = TransferBlobStore(folder); val bytes = "PK signed APK".toByteArray(); val id = blob(bytes)
        store.locked { store.publish(store.temporary().apply { writeBytes(bytes) }, id) }
        val reopened = TransferBlobStore(folder)
        reopened.locked {
            assertArrayEquals(bytes, reopened.verified(id)!!.readBytes())
            File(folder, id.sha256).writeBytes(ByteArray(bytes.size))
            assertNull(reopened.verified(id))
            assertTrue(reopened.candidates().isEmpty())
        }
    }
    @Test fun corruptOutputIsNeverPublishedUnderItsExpectedDigest() = runBlocking {
        val folder = temp.newFolder(); val store = TransferBlobStore(folder); val id = blob("expected".toByteArray())
        store.locked {
            try { store.publish(store.temporary().apply { writeText("badbytes") }, id); fail() } catch (_: IllegalStateException) { }
            assertFalse(File(folder, id.sha256).exists())
        }
        assertTrue(folder.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
    @Test fun materializationPreservesTheImmutableCacheWhenDestinationIsEdited() = runBlocking {
        val store = TransferBlobStore(temp.newFolder()); val bytes = "PK source".toByteArray(); val id = blob(bytes); val destination = File(temp.newFolder(), "result.apk")
        store.locked {
            val ready = store.publish(store.temporary().apply { writeBytes(bytes) }, id)
            store.materialize(ready, destination); destination.writeText("edited")
            assertArrayEquals(bytes, store.verified(id)!!.readBytes())
        }
    }
    @Test fun patchBlobsAreNotOfferedAsApkBases() = runBlocking {
        val store = TransferBlobStore(temp.newFolder())
        store.locked {
            for (bytes in listOf("HDIFFW26 patch".toByteArray(), "PK APK".toByteArray())) store.publish(store.temporary().apply { writeBytes(bytes) }, blob(bytes))
            assertEquals(listOf(blob("PK APK".toByteArray())), store.candidates())
        }
    }
    @Test fun bootstrapUsesTheInstalledApkWithoutAnotherTransfer() = runBlocking {
        val store=TransferBlobStore(temp.newFolder())
        val installed=temp.newFile("installed.apk").apply { writeText("PK installed signed APK") }
        store.seedInstalled(installed)
        store.locked { assertNotNull(store.verified(blob(installed.readBytes()))) }
    }
    @Test fun evictionDeletesOnlyOwnedBlobsAndKeepsUnrelatedFiles() = runBlocking {
        val folder = temp.newFolder(); File(folder, "keep-user-file").writeText("unrelated")
        val store = TransferBlobStore(folder, 1)
        for (n in 1..4) store.locked { val data = "PK$n".toByteArray(); store.publish(store.temporary().apply { writeBytes(data) }, blob(data)) }
        assertEquals("unrelated", File(folder, "keep-user-file").readText())
        assertEquals(2, folder.listFiles()!!.count { it.name.matches(Regex("[a-f0-9]{64}")) })
    }
}
