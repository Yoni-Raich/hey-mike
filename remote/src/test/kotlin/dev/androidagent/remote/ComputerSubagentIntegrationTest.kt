package dev.androidagent.remote

import dev.androidagent.core.*
import dev.androidagent.enginecodex.CodexEngine
import dev.androidagent.enginecodex.EngineProfile
import dev.androidagent.enginecodex.ExternalChatgptTokens
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real task/coordinator/router/Codex stdio path. The peer simulates app-server, not SSH or a model. */
class ComputerSubagentIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun authorizedTaskUsesSavedAccessAndReturnsItsRealThreadAndResultToTheSource() = runBlocking<Unit> {
        for (access in RemoteAccess.entries) {
            val rig = Rig(File(temp.root, access.name).apply { mkdirs() }, access)
            try {
                val exact = "  בדוק את הפרויקט\n  preserve these spaces  "
                val accepted = rig.call("mode" to "start_task", "project" to "project", "message" to exact,
                    "requestId" to "same-task", "model" to "fixture-model").getValue("task").jsonObject
                val id = accepted.getValue("taskId").jsonPrimitive.content
                rig.remote.awaitRequest("thread/name/set") // The coordinator has activated the turn.
                val retry = rig.call("mode" to "start_task", "project" to "C:/SRC/project/", "message" to exact,
                    "requestId" to "same-task", "model" to "fixture-model").getValue("task").jsonObject
                assertEquals(id, retry.getValue("taskId").jsonPrimitive.content)
                assertEquals(1, rig.sessions.created)
                assertNull(rig.ui.value)
                assertEquals(0, rig.foregroundRequests)
                val threadStart = rig.remote.onlyRequest("thread/start").getValue("params").jsonObject
                assertEquals("C:\\src\\project", threadStart.getValue("cwd").jsonPrimitive.content)
                assertEquals(access.sandbox, threadStart.getValue("sandbox").jsonPrimitive.content)
                assertEquals(access.approvalPolicy, threadStart.getValue("approvalPolicy").jsonPrimitive.content)
                assertEquals(access.approvalPolicy, rig.profiles.single().approvalPolicy)
                val instructions = threadStart.getValue("developerInstructions").jsonPrimitive.content
                assertTrue(instructions.contains("Desk"))
                assertTrue(instructions.contains("A user request to build, install, debug or test on a connected Android device authorizes computer-side ADB for that task"))
                assertFalse(instructions.contains("Do not use adb on this computer to reach the phone"))
                val turnStart = rig.remote.onlyRequest("turn/start").getValue("params").jsonObject
                val inputs = turnStart.getValue("input").jsonArray.map { it.jsonObject }
                assertEquals(exact, inputs.last().getValue("text").jsonPrimitive.content)
                assertEquals("fixture-model", turnStart.getValue("model").jsonPrimitive.content)
                assertTrue(inputs.first().getValue("text").jsonPrimitive.content.contains("Trusted Android Agent runtime context"))
                assertFalse(rig.local.requests.value.any { it.method() in setOf("thread/start", "turn/start") })
                assertEquals(exact, rig.sessions.history.value.single { it.sessionId == "child-1" && it.role == "user" }.text)

                rig.remote.notification("item/started", buildJsonObject {
                    put("threadId", "computer-thread-1"); put("turnId", "computer-turn-1")
                    put("item", buildJsonObject { put("id", "cmd"); put("type", "commandExecution"); put("command", "git status"); put("status", "inProgress") })
                })
                val progress = withTimeout(5_000) {
                    rig.manager.state.first { state -> state.tasks[id]?.activity?.contains("git status") == true }.tasks.getValue(id)
                }
                assertEquals("computer-thread-1", progress.threadId)
                withTimeout(5_000) { rig.sessions.history.first { all -> all.any { it.id == id && it.text.contains("git status") } } }
                rig.remote.complete("computer-thread-1", "computer-turn-1", "Done: checked the project")
                val completed = rig.awaitTask(id, "completed")
                assertEquals("child-1", completed.sessionId)
                assertEquals("computer-thread-1", completed.threadId)
                assertEquals("Done: checked the project", completed.result)
                val status = rig.call("mode" to "task_status", "requestId" to "same-task").getValue("task").jsonObject
                assertEquals("completed", status.getValue("status").jsonPrimitive.content)
                assertEquals(completed.result, status.getValue("result").jsonPrimitive.content)
                withTimeout(5_000) { rig.sessions.history.first { all -> all.any { it.id == id && it.text.contains(completed.result!!) } } }
                assertFalse(rig.sessions.history.value.any { it.sessionId == "source" && it.role == "assistant" })
                rig.call("mode" to "start_task", "project" to "project", "message" to exact, "requestId" to "same-task", "model" to "fixture-model")
                assertEquals(1, rig.remote.requests.value.count { it.method() == "thread/start" })
                assertEquals(1, rig.remote.requests.value.count { it.method() == "turn/start" })
            } finally { rig.close() }
        }
    }

    @Test fun computerApprovalIsNamespacedAndOnlyAUserReplyInItsSourceAnswersIt() = runBlocking<Unit> {
        val rig = Rig(temp.root, RemoteAccess.ASK)
        try {
            val task = rig.start()
            rig.remote.awaitRequest("thread/name/set")
            rig.remote.approval(7, "computer-thread-1", "computer-turn-1")
            val waiting = rig.awaitTask(task.id, "waiting_for_user")
            assertEquals("computer-thread-1", waiting.threadId)
            val source = rig.manager.sourceState("source", rig.runs.sessionStates.value)
            assertEquals("remote|pc|7", source.approval!!.requestId)
            assertTrue(rig.remote.responses.value.isEmpty())
            assertFalse(rig.runs.answerApprovalByReply("yes", sessionId = "other"))
            assertTrue(rig.remote.responses.value.isEmpty())
            assertTrue(rig.runs.answerApprovalByReply("no", sessionId = "source"))
            val answers = withTimeout(5_000) { rig.remote.responses.first { it.isNotEmpty() } }
            assertEquals(JsonPrimitive(7), answers.single()["id"])
            assertEquals("decline", answers.single().getValue("result").jsonObject.getValue("decision").jsonPrimitive.content)
            assertTrue(rig.local.responses.value.isEmpty())
            rig.remote.complete("computer-thread-1", "computer-turn-1", "Approval declined; no command ran")
            assertEquals("Approval declined; no command ran", rig.awaitTask(task.id, "completed").result)
        } finally { rig.close() }
    }

    @Test fun sourceStopInterruptsOnlyItsChildAndDoesNotStartAReplacementOnRetry() = runBlocking<Unit> {
        val rig = Rig(temp.root, RemoteAccess.ASK)
        try {
            val task = rig.start()
            rig.remote.awaitRequest("thread/name/set")
            rig.runs.send("other", "Unrelated phone task")
            rig.local.awaitRequest("thread/name/set")
            rig.runs.stop("source")
            val interrupt = rig.remote.awaitRequest("turn/interrupt").getValue("params").jsonObject
            assertEquals("computer-thread-1", interrupt.getValue("threadId").jsonPrimitive.content)
            assertEquals("computer-turn-1", interrupt.getValue("turnId").jsonPrimitive.content)
            rig.awaitTask(task.id, "cancelled")
            assertTrue(rig.runs.stateOf("other")!!.active)
            assertFalse(rig.local.requests.value.any { it.method() == "turn/interrupt" })
            assertTrue(rig.local.alive)
            val retry = rig.call("mode" to "start_task", "project" to "project", "message" to "Exact task", "requestId" to "key").getValue("task").jsonObject
            assertEquals(task.id, retry.getValue("taskId").jsonPrimitive.content)
            assertEquals("cancelled", retry.getValue("status").jsonPrimitive.content)
            assertEquals(1, rig.remote.requests.value.count { it.method() == "thread/start" })
            assertEquals(1, rig.remote.requests.value.count { it.method() == "turn/start" })
            rig.local.complete("phone-thread-1", "phone-turn-1", "Other task completed")
            withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") } }
        } finally { rig.close() }
    }

    @Test fun aComputerDisconnectOrUnscopedErrorDoesNotEndAPhoneTurnInEitherStartOrder() = runBlocking<Unit> {
        for (disconnect in listOf(true, false)) for (phoneLast in listOf(true, false)) {
            val rig = Rig(File(temp.root, "disconnect-$disconnect-phoneLast-$phoneLast").apply { mkdirs() }, RemoteAccess.ASK)
            try {
                if (!phoneLast) {
                    rig.runs.send("other", "Phone first")
                    rig.local.awaitRequest("thread/name/set")
                }
                val child = rig.start()
                rig.remote.awaitRequest("thread/name/set")
                if (phoneLast) {
                    rig.runs.send("other", "Phone last")
                    rig.local.awaitRequest("thread/name/set")
                }
                if (disconnect) rig.remote.stop() else rig.remote.notification("error", buildJsonObject {
                    put("willRetry", false); put("error", buildJsonObject { put("message", "Computer connection failed") })
                })
                val unknown = rig.awaitTask(child.id, "unknown")
                assertTrue(unknown.result!!.contains(if (disconnect) "Codex connection ended" else "Computer connection failed"))
                assertTrue(rig.runs.stateOf("other")!!.active)
                assertFalse(rig.runs.outcomes.value.containsKey("other"))
                rig.local.complete("phone-thread-1", "phone-turn-1", "Phone kept working")
                withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") } }
            } finally { rig.close() }
        }
    }

    @Test fun aComputerServerFailureEndsAllItsChildrenAndLeavesThePhoneRunning() = runBlocking<Unit> {
        val rig = Rig(temp.root, RemoteAccess.ASK)
        try {
            val first = rig.start()
            rig.remote.awaitRequest("thread/name/set")
            val second = ComputerTask.fromJson(rig.call("mode" to "start_task", "project" to "project",
                "message" to "Second task", "requestId" to "second-key").getValue("task").jsonObject)
            rig.remote.awaitRequests("thread/name/set", 2)
            rig.runs.send("other", "Phone beside two children")
            rig.local.awaitRequest("thread/name/set")
            rig.remote.stop()
            rig.awaitTask(first.id, "unknown")
            rig.awaitTask(second.id, "unknown")
            assertTrue(rig.runs.stateOf("other")!!.active)
            rig.local.complete("phone-thread-1", "phone-turn-1", "Phone kept working")
            withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") } }
        } finally { rig.close() }
    }

    @Test fun aScopedPhoneTurnErrorDoesNotEndTheComputerChild() = runBlocking<Unit> {
        val rig = Rig(temp.root, RemoteAccess.ASK)
        try {
            val child = rig.start()
            rig.remote.awaitRequest("thread/name/set")
            rig.runs.send("other", "Fail this phone turn")
            rig.local.awaitRequest("thread/name/set")
            rig.local.notification("error", buildJsonObject {
                put("threadId", "phone-thread-1"); put("turnId", "phone-turn-1"); put("willRetry", false)
                put("error", buildJsonObject { put("message", "Phone turn failed") })
            })
            val phone = withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") }.getValue("other") }
            assertEquals(RunPhase.ERROR, phone.phase)
            assertTrue(rig.runs.stateOf("child-1")!!.active)
            assertFalse(rig.manager.find("source", child.id, null)!!.terminal)
            rig.remote.complete("computer-thread-1", "computer-turn-1", "Child kept working")
            assertEquals("Child kept working", rig.awaitTask(child.id, "completed").result)
        } finally { rig.close() }
    }

    @Test fun aPhoneDisconnectOrUnscopedErrorDoesNotEndTheComputerChild() = runBlocking<Unit> {
        for (disconnect in listOf(true, false)) {
            val rig = Rig(File(temp.root, "phone-disconnect-$disconnect").apply { mkdirs() }, RemoteAccess.ASK)
            try {
                val child = rig.start()
                rig.remote.awaitRequest("thread/name/set")
                rig.runs.send("other", "Phone task")
                rig.local.awaitRequest("thread/name/set")
                if (disconnect) rig.local.stop() else rig.local.notification("error", buildJsonObject {
                    put("willRetry", false); put("error", buildJsonObject { put("message", "Phone connection failed") })
                })
                val phone = withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") }.getValue("other") }
                assertEquals(RunPhase.ERROR, phone.phase)
                assertTrue(phone.outcomeUnknown)
                assertTrue(rig.runs.stateOf("child-1")!!.active)
                assertFalse(rig.manager.find("source", child.id, null)!!.terminal)
                rig.remote.complete("computer-thread-1", "computer-turn-1", "Child survived phone failure")
                assertEquals("Child survived phone failure", rig.awaitTask(child.id, "completed").result)
            } finally { rig.close() }
        }
    }

    @Test fun disconnectingTheSavedComputerMarksItsActiveTaskUnknownWithoutEndingThePhone() = runBlocking<Unit> {
        val rig = Rig(temp.root, RemoteAccess.ASK)
        try {
            val child = rig.start()
            rig.remote.awaitRequest("thread/name/set")
            rig.runs.send("other", "Keep this phone task")
            rig.local.awaitRequest("thread/name/set")
            rig.disconnectComputer()
            val unknown = rig.awaitTask(child.id, "unknown")
            assertTrue(unknown.result!!.contains("Computer disconnected"))
            assertTrue(rig.runs.stateOf("other")!!.active)
            val retry = rig.start()
            assertEquals(child.id, retry.id)
            assertEquals("unknown", retry.status)
            assertEquals(1, rig.remote.requests.value.count { it.method() == "turn/start" })
            assertEquals(1, rig.profiles.size)
            rig.local.complete("phone-thread-1", "phone-turn-1", "Phone survived computer disconnect")
            withTimeout(5_000) { rig.runs.outcomes.first { it.containsKey("other") } }
        } finally { rig.close() }
    }

    private class Rig(root: File, access: RemoteAccess) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = ProtocolPeer("phone")
        val remote = ProtocolPeer("computer")
        val profiles = mutableListOf<EngineProfile>()
        private val box = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = sealed
        }
        private val store = RemoteStore(File(root, "computers.bin"), box)
        val sessions = Sessions(root)
        private val computer = RemoteComputer("pc", "Desk", "test.invalid", user = "fixture", access = access)
        private val hub = RemoteHub(store, { _, _ -> ExternalChatgptTokens("fixture-token", "fixture-account") }) { _, profile ->
            profiles += profile
            CodexEngine(remote, profile)
        }
        private val router = RoutingAgentEngine(CodexEngine(local), hub, sessions = sessions)
        val runs: AgentRuns = AgentRuns(scope, router, onStopSession = { origin ->
            if (origin != null) manager.stopOrigin(origin)
        }) { share -> AgentCoordinator(scope, router, sessions, NoTools(), NoOverlay, share = share) }
        val manager: ComputerTasks = ComputerTasks(store, sessions, scope, { RunsComputerTaskRunner(runs) }) { computerId, _ ->
            // Account exchange goes through the real RemoteHub; SSH is outside this fixture.
            hub.engine(computerId)
        }
        val ui = MutableStateFlow<ComputerUiRequest?>(null)
        var foregroundRequests = 0
        private val gateway = ComputerToolGateway(hub, sessions, ui, manager) { foregroundRequests++ }

        init {
            store.save(computer, "fixture-password")
            store.addProject(computer.id, "C:\\src\\project")
            gateway.beginRun("source-run", sessions.workspace("source"))
        }

        suspend fun call(vararg args: Pair<String, String>): JsonObject = withTimeout(5_000) {
            Json.parseToJsonElement(gateway.invoke("computers", JsonObject(args.associate { it.first to JsonPrimitive(it.second) })).text).jsonObject
        }
        suspend fun start(): ComputerTask {
            val reply = call("mode" to "start_task", "project" to "project", "message" to "Exact task", "requestId" to "key")
            return ComputerTask.fromJson(reply.getValue("task").jsonObject)
        }
        suspend fun disconnectComputer() = hub.disconnect("pc")
        suspend fun awaitTask(id: String, status: String): ComputerTask =
            withTimeoutOrNull(5_000) { manager.state.first { it.tasks[id]?.status == status }.tasks.getValue(id) }
                ?: error("Task $id never reached $status. Receipt=${manager.find("source", id, null)}; states=${runs.sessionStates.value}; outcomes=${runs.outcomes.value}")
        suspend fun close() {
            scope.cancel()
            router.close()
            local.stop()
            remote.stop()
        }
    }

    private class Sessions(private val root: File) : SessionStore {
        override val sessions = MutableStateFlow(listOf("source", "other").map { ChatSession(it, it, 0, 0) })
        val history = MutableStateFlow<List<ChatMessage>>(emptyList())
        var created = 0
        override suspend fun createSession(engine: EngineKind) = ChatSession("child-${++created}", "Child", 0, 0, engine = engine).also { sessions.update { all -> all + it } }
        override suspend fun getSession(id: String) = sessions.value.firstOrNull { it.id == id }
        override fun messages(sessionId: String) = history.map { it.filter { m -> m.sessionId == sessionId } }
        override suspend fun append(message: ChatMessage) { history.update { it + message } }
        override suspend fun updateMessage(id: String, text: String, state: String) { history.update { all -> all.map { if (it.id == id) it.copy(text = text, state = state) else it } } }
        override suspend fun setThread(sessionId: String, threadId: String) { sessions.update { all -> all.map { if (it.id == sessionId) it.copy(engineThreadId = threadId) else it } } }
        override suspend fun rename(sessionId: String, title: String) { sessions.update { all -> all.map { if (it.id == sessionId) it.copy(title = title) else it } } }
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun workspace(sessionId: String) = File(root, "sessions/$sessionId/workspace").apply { mkdirs() }
    }

    private class NoTools : DeviceToolGateway {
        override val definitions = emptyList<ToolDefinition>()
        override fun beginRun(runId: String, workspace: File) = Unit
        override fun revoke() = Unit
        override fun needsControl(name: String) = false
        override suspend fun invoke(name: String, arguments: JsonObject): ToolResult = error("No phone tool was requested")
        override suspend fun cancel() = Unit
    }
    private object NoOverlay : ControlOverlay {
        override suspend fun show(status: String) = Unit
        override fun update(status: String) = Unit
        override fun hide() = Unit
    }

    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content

    /** Concurrent protocol peer on actual pipes, with all request and response JSON retained. */
    private class ProtocolPeer(private val label: String) : RuntimeHost {
        override val status = MutableStateFlow(RuntimeStatus())
        override val homeDirectory = File("fixture-home")
        val requests = MutableStateFlow<List<JsonObject>>(emptyList())
        val responses = MutableStateFlow<List<JsonObject>>(emptyList())
        private val serverInput = PipedInputStream(64 * 1024)
        private val clientOutput = PipedOutputStream(serverInput)
        private val clientInput = PipedInputStream(64 * 1024)
        private val replies = PipedOutputStream(clientInput)
        private val writer = replies.bufferedWriter(Charsets.UTF_8)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var threads = 0
        private var turns = 0
        @Volatile var alive = true
            private set
        private val process = object : Process() {
            override fun getOutputStream() = clientOutput
            override fun getInputStream() = clientInput
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun isAlive() = alive
            override fun waitFor() = 0
            override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
            override fun destroy() { alive = false }
        }
        init {
            scope.launch {
                try {
                    serverInput.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                        val message = Json.parseToJsonElement(line).jsonObject
                        if ("method" !in message) {
                            responses.update { it + message }
                        } else {
                            requests.update { it + message }
                            val id = message["id"] ?: return@forEach
                            val method = message["method"]!!.jsonPrimitive.content
                            val result = when (method) {
                                "account/read" -> buildJsonObject { put("account", buildJsonObject { put("type", "chatgpt"); put("email", "fixture@example.invalid") }) }
                                "thread/start" -> buildJsonObject { put("thread", buildJsonObject { put("id", "$label-thread-${++threads}") }) }
                                "thread/read" -> buildJsonObject { put("thread", buildJsonObject { put("id", message["params"]!!.jsonObject["threadId"]!!) }) }
                                "turn/start" -> buildJsonObject { put("turn", buildJsonObject { put("id", "$label-turn-${++turns}") }) }
                                else -> buildJsonObject {}
                            }
                            write(buildJsonObject { put("id", id); put("result", result) })
                        }
                    } }
                } catch (_: java.io.IOException) {
                    // Closing the fixture cancels its reader and closes all pipes.
                }
            }
        }
        suspend fun awaitRequest(method: String): JsonObject = withTimeout(5_000) {
            requests.first { list -> list.any { it["method"]?.jsonPrimitive?.content == method } }.first { it["method"]?.jsonPrimitive?.content == method }
        }
        suspend fun awaitRequests(method: String, count: Int) = withTimeout(5_000) {
            requests.first { list -> list.count { it["method"]?.jsonPrimitive?.content == method } >= count }
        }
        fun onlyRequest(method: String) = requests.value.single { it["method"]?.jsonPrimitive?.content == method }
        fun notification(method: String, params: JsonObject) = write(buildJsonObject { put("method", method); put("params", params) })
        fun approval(id: Int, thread: String, turn: String) = write(buildJsonObject {
            put("id", id); put("method", "item/commandExecution/requestApproval")
            put("params", buildJsonObject { put("threadId", thread); put("turnId", turn); put("command", "write outside project") })
        })
        fun complete(thread: String, turn: String, text: String) {
            notification("item/completed", buildJsonObject {
                put("threadId", thread); put("turnId", turn)
                put("item", buildJsonObject { put("id", "answer"); put("type", "agentMessage"); put("text", text); put("phase", "final_answer") })
            })
            notification("turn/completed", buildJsonObject {
                put("threadId", thread); put("turn", buildJsonObject { put("id", turn); put("status", "completed") })
            })
        }
        @Synchronized private fun write(message: JsonObject) { writer.write(message.toString()); writer.newLine(); writer.flush() }
        override suspend fun prepare() = Unit
        override suspend fun startAppServer(): Process = process
        override suspend fun stop() {
            alive = false
            scope.cancel()
            listOf(serverInput, clientOutput, clientInput, replies).forEach { runCatching { it.close() } }
        }
    }
}
