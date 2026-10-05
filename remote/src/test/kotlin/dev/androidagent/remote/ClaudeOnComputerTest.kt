package dev.androidagent.remote

import dev.androidagent.core.AccountStatus
import dev.androidagent.core.AgentEngine
import dev.androidagent.core.ChatMessage
import dev.androidagent.core.ChatSession
import dev.androidagent.core.ClaudeProcessHost
import dev.androidagent.core.EngineEvent
import dev.androidagent.core.EngineKind
import dev.androidagent.core.ParkedThread
import dev.androidagent.core.RealtimeTransport
import dev.androidagent.core.RuntimeHost
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.RuntimeStatus
import dev.androidagent.core.SessionStore
import dev.androidagent.core.ToolDefinition
import dev.androidagent.core.ToolResult
import dev.androidagent.engineclaude.ClaudeCodeEngine
import dev.androidagent.engineclaude.ClaudeComputer
import dev.androidagent.engineclaude.McpToolServerFactory
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.ExternalChatgptTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeOnComputerTest {
    @get:Rule val temp = TemporaryFolder()

    // ---- finding and starting claude ---------------------------------------------

    @Test fun theProbeAnswerNamesClaudeOrSaysThereIsNone() {
        val found = ClaudeLaunch.parseProbe(
            ExecResult("noise\nHEYMIKE {\"claude\":\"C:\\\\Users\\\\me\\\\.local\\\\bin\\\\claude.exe\",\"version\":\"2.1.286 (Claude Code)\",\"home\":\"C:\\\\Users\\\\me\"}\n", "", 0),
        )
        assertEquals("C:\\Users\\me\\.local\\bin\\claude.exe", found.path)
        assertEquals("2.1.286 (Claude Code)", found.version)
        assertEquals("C:\\Users\\me", found.home)

        val none = ClaudeLaunch.parseProbe(ExecResult("HEYMIKE {\"claude\":\"\",\"version\":\"\",\"home\":\"/home/me\"}\n", "", 0))
        assertNull(none.path)
    }

    @Test fun theProbeLooksWhereAnSshSessionsPathDoesNotReach() {
        val linux = ClaudeLaunch.unwrap(ClaudeLaunch.probe(HostOs.LINUX))
        assertTrue(linux.contains(".local/bin/claude"))
        assertTrue(linux.contains("command -v claude"))
        assertTrue(ClaudeLaunch.probe(HostOs.WINDOWS).startsWith("powershell.exe "))
    }

    @Test fun launchFilesGoUnderTheUsersHomeInTheComputersOwnSpelling() {
        val windows = ClaudeLaunch.folder("C:\\Users\\me", HostOs.WINDOWS)
        assertEquals("C:\\Users\\me\\.hey-mike\\claude", windows)
        assertEquals("C:\\Users\\me\\.hey-mike\\claude\\chat-1.cmd", ClaudeLaunch.file(windows, ClaudeLaunch.scriptName("chat-1", HostOs.WINDOWS), HostOs.WINDOWS))
        val linux = ClaudeLaunch.folder("/home/me/", HostOs.LINUX)
        assertEquals("/home/me/.hey-mike/claude/chat-1.sh", ClaudeLaunch.file(linux, ClaudeLaunch.scriptName("chat-1", HostOs.LINUX), HostOs.LINUX))
    }

    @Test fun aWindowsLaunchKeepsSpacesPercentSignsAndAnEmptyArgument() {
        val script = ClaudeLaunch.script(
            HostOs.WINDOWS,
            claude = "C:\\Users\\me\\.local\\bin\\claude.exe",
            cwd = "D:\\My Projects\\100% done",
            env = mapOf("ENABLE_TOOL_SEARCH" to "false"),
            args = listOf("-p", "--tools", "", "--model", "claude-opus-4-8[1m]"),
        )
        assertEquals(
            "@echo off\r\n" +
                "chcp 65001 >nul\r\n" +
                "cd /d \"D:\\My Projects\\100%% done\"\r\n" +
                "if errorlevel 1 exit /b 1\r\n" +
                "set \"ENABLE_TOOL_SEARCH=false\"\r\n" +
                "\"C:\\Users\\me\\.local\\bin\\claude.exe\" \"-p\" \"--tools\" \"\" \"--model\" \"claude-opus-4-8[1m]\"\r\n",
            script,
        )
    }

    @Test fun aLinuxLaunchHandsItsStreamsToClaude() {
        val script = ClaudeLaunch.script(
            HostOs.LINUX,
            claude = "/home/me/.local/bin/claude",
            cwd = "/home/me/it's here",
            env = mapOf("MCP_TIMEOUT" to "30000"),
            args = listOf("-p", "--tools", ""),
        )
        assertEquals(
            "#!/bin/sh\n" +
                "cd -- '/home/me/it'\\''s here' || exit 1\n" +
                "export MCP_TIMEOUT='30000'\n" +
                "exec '/home/me/.local/bin/claude' '-p' '--tools' ''\n",
            script,
        )
    }

    @Test fun aValueTheScriptCannotQuoteIsRefused() {
        assertTrue(runCatching { ClaudeLaunch.script(HostOs.WINDOWS, "claude", "C:\\a\" & calc & \"", emptyMap(), emptyList()) }.isFailure)
        assertTrue(runCatching { ClaudeLaunch.script(HostOs.WINDOWS, "claude", null, emptyMap(), listOf("a\nb")) }.isFailure)
        assertTrue(runCatching { ClaudeLaunch.script(HostOs.LINUX, "claude", null, emptyMap(), listOf("a\nb")) }.isFailure)
        assertTrue(runCatching { ClaudeLaunch.script(HostOs.LINUX, "claude", null, mapOf("A B" to "x"), emptyList()) }.isFailure)
    }

    @Test fun theCommandIsOnlyTheScriptsPathForEveryShell() {
        assertEquals("sh '/home/me/.hey-mike/claude/chat-1.sh'", ClaudeLaunch.command(HostOs.LINUX, "/home/me/.hey-mike/claude/chat-1.sh", powerShellDefault = false))
        val path = "C:\\Users\\my name\\.hey-mike\\claude\\chat-1.cmd"
        assertEquals("\"$path\"", ClaudeLaunch.command(HostOs.WINDOWS, path, powerShellDefault = false))
        assertEquals("& '$path'", ClaudeLaunch.command(HostOs.WINDOWS, path, powerShellDefault = true))
        assertTrue(runCatching { ClaudeLaunch.command(HostOs.LINUX, "/home/o'brien/x.sh", powerShellDefault = false) }.isFailure)
    }

    @Test fun claudeOnAComputerIsToldItIsClaudeCodeThere() {
        val computer = RemoteComputer("pc", "Studio", "10.0.0.2", user = "me", os = HostOs.WINDOWS)
        val claude = RemoteInstructions.forComputer(computer, EngineKind.CLAUDE)
        assertTrue(claude.contains("you run as Claude Code on their Windows computer \"Studio\""))
        assertTrue(claude.contains("Anthropic's Claude models"))
        assertFalse(claude.contains("Codex"))
        // The Codex text is unchanged.
        assertTrue(RemoteInstructions.forComputer(computer).contains("you run as Codex on their Windows computer \"Studio\""))
    }

    // ---- routing ----------------------------------------------------------------

    @Test fun aClaudeChatOnAComputerRunsOnThatComputersClaudeNotThePhones() = runBlocking {
        val rig = rig(signedIn = true, ChatSession("c1", "C", 0, 0, engine = EngineKind.CLAUDE))
        try {
            val workspace = File(temp.root, "sessions/c1/workspace").apply { mkdirs() }

            rig.router.connect(EngineKind.CLAUDE, workspace)
            assertEquals("pc@example.com", rig.router.account(EngineKind.CLAUDE, workspace).label)
            val thread = rig.router.openSession(workspace, null, "sonnet", emptyList())

            // The phone's own Claude is never asked: it need not even be set up.
            assertEquals(0, rig.phoneClaude.connects)
            assertTrue(rig.phoneClaude.opened.isEmpty())
            assertEquals(listOf(listOf("auth", "status", "--json")), rig.pc.commands)
            assertEquals(36, thread.length)
            // No skill list is read from the phone for the computer's Claude Code.
            assertTrue(rig.router.skillCatalog(workspace).isEmpty())
            val voice = runCatching { rig.router.startVoice(thread, null, RealtimeTransport.WEBSOCKET, null) }.exceptionOrNull()
            assertTrue(voice is IllegalStateException)
        } finally { rig.router.close() }
    }

    @Test fun aComputerWhoseClaudeIsSignedOutSaysWhereToSignIn() = runBlocking {
        val rig = rig(signedIn = false, ChatSession("c1", "C", 0, 0, engine = EngineKind.CLAUDE))
        try {
            val workspace = File(temp.root, "sessions/c1/workspace").apply { mkdirs() }
            val failure = runCatching { rig.router.account(EngineKind.CLAUDE, workspace) }.exceptionOrNull()
            assertEquals("Claude Code on Studio is not signed in. Run `claude` there and sign in, then send again.", failure?.message)
        } finally { rig.router.close() }
    }

    @Test fun aCodexTurnInTheSameComputerChatStillUsesThePhonesCodexSignIn() = runBlocking {
        // The chat moved to Codex and keeps its Claude thread for later.
        val chat = ChatSession("c1", "C", 0, 0, engine = EngineKind.CODEX, parked = mapOf(EngineKind.CLAUDE to ParkedThread("11111111-2222-3333-4444-555555555555", 5)))
        val rig = rig(signedIn = true, chat)
        try {
            val workspace = File(temp.root, "sessions/c1/workspace").apply { mkdirs() }
            // This Codex is not started here and refuses; what matters is who was asked.
            runCatching { rig.router.connect(EngineKind.CODEX, workspace) }
            assertTrue(rig.pc.commands.isEmpty())
            // A call about the parked Claude thread still reaches the computer's Claude, not the phone's.
            rig.router.interrupt("11111111-2222-3333-4444-555555555555", "turn")
            assertTrue(rig.phoneClaude.interrupts.isEmpty())
        } finally { rig.router.close() }
    }

    private class Rig(val router: RoutingAgentEngine, val phoneClaude: PhoneClaude, val pc: ComputerClaudeFake)

    private fun rig(signedIn: Boolean, vararg chats: ChatSession): Rig {
        val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        val store = RemoteStore(File(temp.root, "computers.bin"), box)
        store.save(RemoteComputer("pc", "Studio", "localhost", user = "test"), "test-only")
        chats.forEach { store.bind(it.id, RemoteBinding("pc", "C:\\project")) }
        val pc = ComputerClaudeFake(temp.newFolder("scratch"), signedIn)
        val hub = RemoteHub(
            store,
            { _, _ -> ExternalChatgptTokens("t", "a") },
            createEngine = { _, profile -> CodexEngine(IdleRuntime(), profile) },
            createClaude = { _, _ -> ClaudeCodeEngine(pc, McpToolServerFactory { _, _, _ -> error("unused") }, computer = pc) },
        )
        val phoneClaude = PhoneClaude()
        return Rig(RoutingAgentEngine(CodexEngine(IdleRuntime()), hub, phoneClaude, Sessions(chats.toList())), phoneClaude, pc)
    }

    /** The computer's `claude`, answering `auth status` and nothing else. */
    private class ComputerClaudeFake(override val homeDirectory: File, private val signedIn: Boolean) : ClaudeProcessHost, ClaudeComputer {
        override val status = MutableStateFlow(RuntimeStatus(RuntimePhase.READY, "Claude Code"))
        val commands = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        override suspend fun prepare() = Unit
        override suspend fun start(args: List<String>, workingDirectory: File, extraEnv: Map<String, String>): Process {
            commands += args
            return Printed("""{"loggedIn":$signedIn,"authMethod":"claude.ai","email":"pc@example.com"}""")
        }
        override suspend fun stopAll() = Unit
        override val instructions = "You are Mike."
        override val permissionMode = "acceptEdits"
        override val askUser = true
        override val notReady = "Claude Code is not on Studio."
        override suspend fun startChat(chatId: String, cwd: String, files: Map<String, String>, env: Map<String, String>, args: (Map<String, String>) -> List<String>): Process =
            error("No chat is started in this test")
    }

    /** A process that printed [text] and ended. */
    private class Printed(text: String) : Process() {
        private val out = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream() = out
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() = Unit
    }

    private class PhoneClaude : AgentEngine {
        override val events = MutableSharedFlow<EngineEvent>().asSharedFlow()
        var connects = 0
        val opened = mutableListOf<String?>()
        val interrupts = mutableListOf<String>()
        override suspend fun connect() { connects++ }
        override suspend fun account() = AccountStatus(true, "Phone Claude user")
        override suspend fun login() = account()
        override suspend fun logout() = Unit
        override suspend fun models() = listOf("sonnet")
        override suspend fun openSession(workspace: File, threadId: String?, model: String?, tools: List<ToolDefinition>): String {
            opened += model
            return threadId ?: "phone-claude-thread"
        }
        override suspend fun startTurn(threadId: String, prompt: String, images: List<File>) = "turn"
        override suspend fun steer(threadId: String, turnId: String, prompt: String) = Unit
        override suspend fun interrupt(threadId: String, turnId: String) { interrupts += threadId }
        override suspend fun answerTool(requestId: String, result: ToolResult) = Unit
        override suspend fun answerApproval(requestId: String, allow: Boolean) = Unit
        override suspend fun close() = Unit
    }

    private class Sessions(list: List<ChatSession>) : SessionStore {
        override val sessions = MutableStateFlow(list)
        override suspend fun createSession(engine: EngineKind) = error("not used")
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
        override suspend fun append(message: ChatMessage) = Unit
        override suspend fun updateMessage(id: String, text: String, state: String) = Unit
        override suspend fun setThread(sessionId: String, threadId: String) = Unit
        override suspend fun rename(sessionId: String, title: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(sessionId)
    }

    /** A Codex that is never started. */
    private class IdleRuntime : RuntimeHost {
        override val status = MutableStateFlow(RuntimeStatus())
        override val homeDirectory = File(".")
        override suspend fun prepare() = Unit
        override suspend fun startAppServer(): Process = error("Codex is not started in this test")
        override suspend fun stop() = Unit
    }
}
