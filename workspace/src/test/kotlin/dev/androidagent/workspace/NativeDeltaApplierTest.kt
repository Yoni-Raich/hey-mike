package dev.androidagent.workspace

import dev.androidagent.core.TransferBlob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** The same JNI adapter compiled for the host JVM. Android packaging/runtime are separate evidence. */
class NativeDeltaApplierTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var applier: NativeDeltaApplier
    private val old get() = File(root, "snapshots/1132-input.apk")
    private val patch get() = File(root, "patches/wd-s64-zstd3.hdiff")
    private val expected get() = TransferBlob("e32d19a7539d3447689f3d831c7b77b7ab1684a7569ca7d43fedfcc80eac0b6f", 331102984)
    @Before fun fixture() {
        val path = System.getProperty("mike.delta.fixtureDir")
        assumeNotNull(path)
        root = File(path!!)
        applier = NativeDeltaApplier()
        assertTrue("Configured JNI library must load", applier.available)
    }
    @Test fun realSignedApkIsReconstructedWithIdenticalBytes() = runBlocking {
        val output = File(temp.root, "rebuilt.apk")
        var written = 0L
        applier.apply(old, patch, output, expected) { bytes, total -> assertEquals(expected.size,total); assertTrue(bytes>=written); written=bytes }
        assertEquals(expected.size,written)
        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0)break; digest.update(buffer,0,n) } }
        assertEquals(expected.sha256,digest.digest().joinToString("") { "%02x".format(it) })
    }
    @Test fun nativeStopExitsBeforeWritingACompleteApk() = runBlocking {
        val output=File(temp.root,"stopped.part")
        lateinit var work: Job
        work=launch { applier.apply(old,patch,output,expected) { bytes,_ -> if(bytes>=65536)work.cancel() } }
        work.join()
        assertTrue(work.isCancelled)
        assertTrue("Stop must reach native callbacks",output.length()<expected.size)
    }
    @Test fun wrongAdvertisedTargetSizeIsRejectedBeforeOpeningOutput() = runBlocking {
        val output=File(temp.root,"bad.part")
        try { applier.apply(old,patch,output,expected.copy(size=expected.size-1)) { _,_-> }; fail() } catch(_:IllegalStateException) { }
        assertFalse(output.exists())
    }
    @Test fun corruptPatchFailsEvenIfOutputWasWritten() = runBlocking {
        val corrupt=File(temp.root,"corrupt.hdiff")
        val bytes=patch.readBytes(); bytes[bytes.size/2]=(bytes[bytes.size/2].toInt() xor 64).toByte(); corrupt.writeBytes(bytes)
        try { applier.apply(old,corrupt,File(temp.root,"corrupt.part"),expected) { _,_-> }; fail() } catch(_:IllegalStateException) { }
    }
}
