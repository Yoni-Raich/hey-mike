package dev.androidagent.runtime

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodexUpdateCheckTest {
    private fun metadata(version: String, preview: Boolean = false) =
        """{"tag_name":"rust-v$version","draft":false,"prerelease":$preview}"""

    @Test fun newerReleaseReportsApkUpdateAndRestoresCachedCheckOffline() = runBlocking {
        val folder = Files.createTempDirectory("codex-update-test").toFile()
        try {
            val cache = File(folder, "check.json")
            var requests = 0
            val check = CodexUpdateCheck(cache, { "0.159.2" }, { requests++; metadata("0.163.0") }, { 2_000_000L })
            check.check()
            assertEquals("0.163.0", check.state.value.latest)
            assertTrue(check.state.value.message.contains("Update the Mike app"))
            check.check()
            assertEquals(1, requests)
            val restored = CodexUpdateCheck(cache, { "0.159.2" }, { error("must not use the network") }, { 2_000_001L })
            restored.check()
            assertEquals("0.163.0", restored.state.value.latest)
            assertEquals("0.159.2", restored.state.value.installed)
        } finally { folder.deleteRecursively() }
    }

    @Test fun previewMalformedAndOfflineChecksKeepInstalledVersion() = runBlocking {
        val folder = Files.createTempDirectory("codex-update-test").toFile()
        try {
            for (source in listOf<() -> String>({ metadata("0.164.0", true) }, { metadata("../evil") }, { error("offline") })) {
                val check = CodexUpdateCheck(File(folder, "check.json"), { "0.163.0" }, source)
                check.check(force = true)
                assertEquals("0.163.0", check.state.value.installed)
                assertNull(check.state.value.latest)
                assertTrue(check.state.value.message.contains("unchanged"))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun numericVersionsDoNotAdvertiseDowngrades() {
        assertFalse(CodexUpdateCheck.info("0.163.0", "0.99.0").message.contains("is available"))
        assertTrue(CodexUpdateCheck.info("0.99.0", "0.163.0").message.contains("is available"))
    }
}
