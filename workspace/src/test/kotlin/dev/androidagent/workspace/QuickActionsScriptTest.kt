package dev.androidagent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the shipped act.sh under a POSIX sh. The phone has only sh, awk, sed
 * and od, and the script is written to that, so any POSIX host exercises the
 * same code. Skipped on Windows and where no sh is on the PATH.
 */
class QuickActionsScriptTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var script: File
    private lateinit var data: File

    @Before fun setUp() {
        // Windows rewrites process arguments on the way to an MSYS sh: quotes
        // and braces are stripped, so what arrives is not what was passed.
        assumeTrue("needs a POSIX host", !System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeTrue("needs a POSIX sh", runCatching { run(listOf("sh", "-c", "command -v awk && command -v od")).first == 0 }.getOrDefault(false))
        val skill = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/agent_stack/skills/quick-actions/scripts") }
            .first { it.isDirectory }
        val installed = tempFolder.newFolder("scripts")
        data = tempFolder.newFolder("data")
        script = File(installed, "act.sh").apply {
            writeText(File(skill, "act.sh").readText().replace("\r\n", "\n").replace(WorkspaceSeeder.QUICK_ACTIONS_DIR_PLACEHOLDER, data.absolutePath))
        }
        File(skill, "intents.tsv").copyTo(File(installed, "intents.tsv"))
    }

    @Test fun anUnsavedContactSaysHowToSaveIt() {
        val (code, out) = act("run", "whatsapp.send", "contact=אבא", "text=hi")
        assertEquals(4, code)
        assertTrue(out, out.contains("save-contact"))
    }

    @Test fun aSavedContactTurnsIntoOneOpenIntentCall() {
        assertEquals(0, act("save-contact", "dad", "Yossi Cohen", "+972 50-123-4567", "אבא,dad").first)

        val (code, out) = act("run", "whatsapp.send", "contact=אבא", "text=on my way & \"almost\" there")

        assertEquals(out, 0, code)
        assertEquals(
            """{"tool":"open_intent","arguments":{"uri":"https://wa.me/972501234567","package":"com.whatsapp","text":"on my way & \"almost\" there"}}""",
            out.trim(),
        )
    }

    @Test fun aLocalNumberIsRefusedUntilItIsInternational() {
        val (code, out) = act("save-contact", "mom", "Dana", "050-123-4567")
        assertEquals(2, code)
        assertTrue(out, out.contains("international"))
    }

    @Test fun uriParametersArePercentEncoded() {
        val (code, out) = act("run", "waze.navigate", "place=דיזנגוף 50, תל אביב")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("q=%D7%93%D7%99%D7%96%D7%A0%D7%92%D7%95%D7%A3%2050%2C%20"))
    }

    @Test fun aSavedIntentIsRunnableAndOverridesABuiltInOfTheSameName() {
        assertEquals(0, act("save-intent", "spotify.search", "query", "-", "spotify:search:{query}", "com.spotify.music", "-", "Search Spotify").first)
        assertEquals(
            """{"tool":"open_intent","arguments":{"uri":"spotify:search:Omer%20Adam","package":"com.spotify.music"}}""",
            act("run", "spotify.search", "query=Omer Adam").second.trim(),
        )

        act("save-contact", "dad", "Yossi", "972501234567")
        act("save-intent", "whatsapp.chat", "contact", "-", "https://wa.me/{phone}?x=1", "com.whatsapp", "-", "Override")
        assertTrue(act("run", "whatsapp.chat", "contact=dad").second.contains("https://wa.me/972501234567?x=1"))
    }

    @Test fun aMissingParameterIsNamed() {
        act("save-contact", "dad", "Yossi", "972501234567")
        val (code, out) = act("run", "whatsapp.send", "contact=dad")
        assertEquals(2, code)
        assertTrue(out, out.contains("text="))
    }

    private fun act(vararg args: String): Pair<Int, String> = run(listOf("sh", script.absolutePath) + args)

    private fun run(command: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor(20, TimeUnit.SECONDS)
        return process.exitValue() to out
    }
}
