package dev.androidagent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LinuxHostTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theSystemIsReadFromUname() {
        assertEquals(HostOs.LINUX, HostOs.fromUname(ExecResult("Linux\n", "", 0)))
        assertEquals(HostOs.WINDOWS, HostOs.fromUname(ExecResult("", "'uname' is not recognized", 1)))
        assertEquals(HostOs.WINDOWS, HostOs.fromUname(ExecResult("MINGW64_NT-10.0\n", "", 0)))
        assertNull(HostOs.fromUname(ExecResult("Darwin\n", "", 0)))
    }

    @Test fun linuxPathsKeepTheirStyle() {
        assertEquals("/home/me/app/out/a.png", ComputerFilesGateway.onComputer("/home/me/app/", "out/a.png"))
        assertEquals("/tmp/a.png", ComputerFilesGateway.onComputer("/home/me/app", "/tmp/a.png"))
        assertEquals("/home/me/a.png", SshLink.sftpPath("/home/me/a.png"))
        assertEquals("/home/me/App", ComputerToolGateway.pathKey("/home/me/App/"))
    }

    @Test fun theAppServerPathIsQuotedForSpaces() {
        val probe = HostProbe("box", "/home/me", "x86_64", "/home/me/my data/codex-app-server", installed = true)
        assertEquals("'/home/me/my data/codex-app-server' --listen stdio://", LinuxHost.appServer(probe))
    }

    /** Runs the real scripts where a Linux sh is at hand (Linux CI, a dev box); skipped elsewhere. */
    @Test fun theScriptsAnswerOnTheMarkerLine() {
        assumeTrue(linuxShWorks())
        val home = temp.newFolder("home")
        File(home, "src/app/.git").mkdirs()
        File(home, "src/site").mkdirs()
        File(home, "src/.hidden").mkdirs()

        val probe = WindowsHost.parseProbe(runSh(LinuxHost.probe(), home))
        assertFalse(probe.installed)
        assertTrue(probe.appServer.endsWith("/heymike/codex/${WindowsHost.CODEX_VERSION}/bin/codex-app-server"))

        val listing = WindowsHost.parseListing(runSh(LinuxHost.list(File(home, "src").path, create = false), home))
        assertEquals(listOf("app", "site"), listing.folders)
        assertFalse(listing.isGitRepo)
        assertTrue(WindowsHost.parseListing(runSh(LinuxHost.list(File(home, "src/app").path, create = false), home)).isGitRepo)

        val made = WindowsHost.parseListing(runSh(LinuxHost.list(File(home, "new project").path, create = true), home))
        assertTrue(File(home, "new project").isDirectory)
        assertEquals(emptyList<String>(), made.folders)

        val missing = runCatching { WindowsHost.parseListing(runSh(LinuxHost.list(File(home, "nope").path, create = false), home)) }
        assertTrue(missing.isFailure)
    }

    private fun linuxShWorks(): Boolean = runCatching {
        val p = ProcessBuilder("sh", "-c", "uname -s").redirectErrorStream(true).start()
        p.waitFor(10, TimeUnit.SECONDS) && p.inputStream.bufferedReader().readText().trim() == "Linux"
    }.getOrDefault(false)

    private fun runSh(command: String, home: File): ExecResult {
        val process = ProcessBuilder("sh", "-c", command).apply {
            environment()["HOME"] = home.path
            environment().remove("XDG_DATA_HOME")
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return ExecResult(out, err, process.exitValue())
    }
}
