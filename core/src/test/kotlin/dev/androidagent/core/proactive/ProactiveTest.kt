package dev.androidagent.core.proactive

import dev.androidagent.core.SignalLane
import dev.androidagent.core.TaskChatRole
import dev.androidagent.core.TaskSignal
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun at(hour: Int, minute: Int = 0, day: Int = 6) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun dir(): File = Files.createTempDirectory("proactive").toFile()

    private val noon = at(12)
    private val prefs = AlertPrefs()

    private fun gate(level: AlertLevel, match: ProfileMatch = ProfileMatch(), counts: AlertCounts = AlertCounts(), last: Long? = null, now: Long = noon, p: AlertPrefs = prefs) =
        AlertGate.decide(AlertRequest(level, "com.whatsapp", "Dana"), p, match, counts, last, now, zone)

    private fun entry(weight: Int, origin: ProfileOrigin = ProfileOrigin.OWNER, always: Boolean = false) =
        ProfileEntry("e$weight$origin", ProfileKind.PERSON, "dana", "Dana", weight, always, origin = origin, updatedAt = 0)

    @Test fun gateKeepsAnOrdinaryRequest() {
        val d = gate(AlertLevel.URGENT)
        assertEquals(AlertLevel.URGENT, d.level)
        assertTrue(d.reasons.isEmpty())
    }

    @Test fun gateMutesOwnerMutedPeople() {
        assertEquals(AlertLevel.NONE, gate(AlertLevel.URGENT, ProfileMatch(listOf(entry(-2)))).level)
        assertEquals(listOf("muted_person"), gate(AlertLevel.URGENT, ProfileMatch(listOf(entry(-2)))).reasons)
    }

    @Test fun gateLowersForLearnedLowButNotWhenOwnerStarred() {
        assertEquals(AlertLevel.NOTICE, gate(AlertLevel.URGENT, ProfileMatch(listOf(entry(-1, ProfileOrigin.LEARNED)))).level)
        assertEquals(AlertLevel.DIGEST, gate(AlertLevel.NOTICE, ProfileMatch(listOf(entry(-1, ProfileOrigin.LEARNED)))).level)
        val starred = ProfileMatch(listOf(entry(2), entry(-1, ProfileOrigin.LEARNED).copy(kind = ProfileKind.SOURCE, key = "com.whatsapp")))
        assertEquals(AlertLevel.URGENT, gate(AlertLevel.URGENT, starred).level)
    }

    @Test fun quietHoursSendToDigestUnlessStarred() {
        val night = at(23, 30)
        assertEquals(AlertLevel.DIGEST, gate(AlertLevel.URGENT, now = night).level)
        assertEquals(AlertLevel.DIGEST, gate(AlertLevel.NOTICE, now = at(3)).level)
        assertEquals(AlertLevel.URGENT, gate(AlertLevel.URGENT, ProfileMatch(listOf(entry(2))), now = night).level)
        assertEquals(AlertLevel.URGENT, gate(AlertLevel.URGENT, ProfileMatch(listOf(entry(0, always = true))), now = night).level)
        assertEquals(AlertLevel.NOTICE, gate(AlertLevel.NOTICE, now = at(7)).level)
    }

    @Test fun budgetsAndCooldownLowerOneStep() {
        assertEquals(AlertLevel.NOTICE, gate(AlertLevel.URGENT, counts = AlertCounts(urgent = 3)).level)
        assertEquals(AlertLevel.DIGEST, gate(AlertLevel.NOTICE, counts = AlertCounts(notice = 10)).level)
        assertEquals(AlertLevel.NOTICE, gate(AlertLevel.NOTICE, ProfileMatch(listOf(entry(0, always = true))), counts = AlertCounts(notice = 10)).level)
        val cooled = gate(AlertLevel.URGENT, last = noon - 10 * 60_000)
        assertEquals(AlertLevel.NOTICE, cooled.level)
        assertEquals(listOf("cooldown"), cooled.reasons)
        assertEquals(AlertLevel.URGENT, gate(AlertLevel.URGENT, last = noon - 40 * 60_000).level)
    }

    @Test fun gateNeverRaisesAndRespectsMasterSwitch() {
        AlertLevel.entries.forEach { level ->
            assertTrue(gate(level, ProfileMatch(listOf(entry(2, always = true)))).level <= level)
        }
        assertEquals(AlertLevel.NONE, gate(AlertLevel.URGENT, p = prefs.copy(enabled = false)).level)
    }

    @Test fun digestTimesRollToTheNextSlot() {
        assertEquals(at(18), prefs.nextDigestAt(noon, zone))
        assertEquals(at(8, day = 7), prefs.nextDigestAt(at(18), zone))
        assertEquals(at(8), prefs.nextDigestAt(at(2), zone))
        assertNull(prefs.copy(digestMinutes = emptyList()).nextDigestAt(noon, zone))
    }

    @Test fun ownerEntriesOutrankAndBlockLearning() {
        val store = OwnerProfileStore(File(dir(), "profile.json")) { noon }
        store.learn(ProfileKind.PERSON, "Dana Levi", null, -1, "ignored twice", listOf("f1"), "run1")
        assertEquals(-1, store.match("com.whatsapp", "Dana Levi", null, null).learnedLow?.weight)
        store.setOwner(ProfileKind.PERSON, "dana  levi", "Dana", 2)
        val match = store.match("com.whatsapp", "Dana Levi", null, null)
        assertTrue(match.starred)
        assertNull(match.learnedLow)
        assertEquals(1, store.profile.entries.size)
        val refused = runCatching { store.learn(ProfileKind.PERSON, "Dana Levi", null, -1, "again", listOf("f2"), "run2") }
        assertTrue(refused.isFailure)
        // Learned weights stay in -1..1.
        store.learn(ProfileKind.TOPIC, "School trip", null, 5, "always opened", listOf("f3"), "run3")
        assertEquals(1, store.profile.entries.first { it.kind == ProfileKind.TOPIC }.weight)
    }

    @Test fun profileSurvivesReloadAndUndoRestoresPrevious() {
        val file = File(dir(), "profile.json")
        val store = OwnerProfileStore(file) { noon }
        store.learn(ProfileKind.CONVERSATION, "com.whatsapp|Parents", "Parents", -1, "first", listOf("a"), "r1")
        store.learn(ProfileKind.CONVERSATION, "com.whatsapp|Parents", "Parents", 1, "second", listOf("b"), "r2")
        val reloaded = OwnerProfileStore(file) { noon }
        assertEquals(1, reloaded.profile.entries.single().weight)
        reloaded.undoRun("r2")
        assertEquals(-1, reloaded.profile.entries.single().weight)
        reloaded.undoRun("r1")
        assertTrue(reloaded.profile.entries.isEmpty())
    }

    @Test fun rejectTurnsLearnedIntoOwnerNormal() {
        val store = OwnerProfileStore(File(dir(), "p.json")) { noon }
        store.learn(ProfileKind.SOURCE, "com.shop", null, -1, "noise", listOf("x"), "r")
        store.reject(store.profile.entries.single().id)
        val e = store.profile.entries.single()
        assertEquals(ProfileOrigin.OWNER, e.origin)
        assertEquals(0, e.weight)
        assertTrue(runCatching { store.learn(ProfileKind.SOURCE, "com.shop", null, -1, "noise", listOf("y"), "r2") }.isFailure)
    }

    private class FakeSink : AlertSink {
        val shown = mutableListOf<MikeAlert>(); val cancelled = mutableListOf<String>(); val digests = mutableListOf<List<MikeAlert>>()
        override fun show(alert: MikeAlert) { shown += alert }
        override fun cancel(alertId: String) { cancelled += alertId }
        override fun digest(alerts: List<MikeAlert>) { digests += alerts }
    }

    private fun desk(clock: () -> Long, sink: FakeSink = FakeSink(), broker: ContextBroker? = null): Pair<ProactiveDesk, FakeSink> {
        val d = dir()
        return ProactiveDesk(OwnerProfileStore(File(d, "p.json"), clock), AlertBook(File(d, "a.json"), clock), sink, broker, { zone }, clock) to sink
    }

    private fun input(level: AlertLevel, signal: String? = "s1", revision: Long = 1, conversation: String = "Dana") =
        AlertInput(level, "Dana asks", "Can you sign the form?", "She needs it today", "com.whatsapp", conversation, "Dana",
            signalId = signal, signalRevision = revision, actor = "scout")

    @Test fun raiseShowsOnceAndDedupesRetries() {
        var now = noon
        val (desk, sink) = desk({ now })
        val first = desk.raise(input(AlertLevel.URGENT))
        assertEquals(AlertLevel.URGENT, first.alert.level)
        assertEquals(1, sink.shown.size)
        val retry = desk.raise(input(AlertLevel.URGENT))
        assertTrue(retry.duplicate)
        assertEquals(1, sink.shown.size)
        assertEquals(AlertCounts(urgent = 1), desk.book.countsToday(now, zone))
        // A new revision of the same conversation replaces the live alert and falls under the cooldown.
        now += 5 * 60_000
        val next = desk.raise(input(AlertLevel.URGENT, revision = 2))
        assertEquals(AlertLevel.NOTICE, next.alert.level)
        assertEquals(listOf(first.alert.id), sink.cancelled)
    }

    @Test fun digestWaitsForItsTimeAndSnoozeComesBack() {
        var now = noon
        val (desk, sink) = desk({ now })
        desk.raise(input(AlertLevel.DIGEST, signal = "d1"))
        desk.tick()
        assertTrue(sink.digests.isEmpty())
        assertEquals(at(18), desk.nextWakeAt())
        now = at(18, 1)
        desk.tick()
        assertEquals(1, sink.digests.single().size)

        now = at(19)
        val urgent = desk.raise(input(AlertLevel.NOTICE, signal = "n1", conversation = "Yossi"))
        assertTrue(desk.onFeedback(urgent.alert.id, FeedbackAction.LATER))
        assertTrue(urgent.alert.id in sink.cancelled)
        val shownBefore = sink.shown.size
        now = at(20, 1)
        desk.tick()
        assertEquals(shownBefore + 1, sink.shown.size)
    }

    @Test fun threeNotImportantTeachALearnedMinusOne() {
        var now = noon
        val (desk, _) = desk({ now })
        repeat(3) { i ->
            now += 60 * 60_000
            val a = desk.raise(input(AlertLevel.NOTICE, signal = "x$i", conversation = "Shop group"))
            desk.onFeedback(a.alert.id, FeedbackAction.NOT_IMPORTANT)
        }
        val learned = desk.profile.profile.entries.single()
        assertEquals(ProfileKind.CONVERSATION, learned.kind)
        assertEquals("com.whatsapp|Shop group", learned.key)
        assertEquals(-1, learned.weight)
        assertEquals(3, learned.evidence.size)
        now += 60 * 60_000
        assertEquals(AlertLevel.DIGEST, desk.raise(input(AlertLevel.NOTICE, signal = "x9", conversation = "Shop group")).alert.level)
    }

    private class FakeConnector(var state: ConnectorState = ConnectorState.READY, val slow: Boolean = false) : ContextConnector {
        override val id = "whatsapp-web"
        override val packages = setOf("com.whatsapp")
        var queries = mutableListOf<ConversationQuery>()
        override suspend fun status() = ConnectorStatus(state)
        override suspend fun conversation(query: ConversationQuery): ConversationContext {
            queries += query
            if (slow) delay(5_000)
            return ConversationContext(id, query.conversation, listOf(
                ContextMessage("in", "Dana", "Hi, did you see the form?", 1),
                ContextMessage("in", "Bank", "Your verification code is 123456", 2),
                ContextMessage("out", null, "Not yet", 3),
            ), "text", hasEarlier = true)
        }
    }

    private fun row(id: String, app: String = "com.whatsapp", conversation: String = "Dana") =
        TaskSignal(id, "whatsapp", app, conversation, "Dana", "Please sign today", at = noon, capturedAt = noon, lane = SignalLane.PEOPLE)

    @Test fun brokerPullsCleanContextOnlyWhereAllowed() = runBlocking {
        val connector = FakeConnector()
        var on = true
        val broker = ContextBroker({ listOf(connector) }, { on }, { _, c -> c != "Secret" }, "dev.androidagent.app.dev")
        val rows = broker.enrich(listOf(row("a"), row("b", conversation = "Secret"), row("c", app = "com.google.android.gm")))
        assertEquals(listOf("a"), rows.map { it.signalId })
        val messages = rows.single().context!!.messages
        assertEquals(2, messages.size)
        assertFalse(messages.any { "123456" in it.text })
        assertEquals("Please sign today", connector.queries.single().hintText)
        on = false
        assertTrue(broker.enrich(listOf(row("a"))).isEmpty())
    }

    @Test fun brokerTurnsFailuresIntoNotes() = runBlocking {
        val broker = ContextBroker({ listOf(FakeConnector(ConnectorState.NOT_LINKED)) }, { true }, { _, _ -> true }, "x")
        val note = broker.enrich(listOf(row("a"))).single()
        assertNull(note.context)
        assertEquals("whatsapp-web is not linked.", note.note)
        val slow = ContextBroker({ listOf(FakeConnector(slow = true)) }, { true }, { _, _ -> true }, "x", timeoutMs = 50)
        assertTrue(slow.enrich(listOf(row("a"))).single().note!!.contains("did not answer"))
    }

    private fun tools(desk: ProactiveDesk, signals: Map<String, TaskSignal> = mapOf("s1" to row("s1")), learned: MutableList<LearnRequest>? = null) =
        ProactiveTools(desk, { _, id -> signals[id] }, { null }, runId = "run-x",
            learn = learned?.let { l -> { r: LearnRequest -> l += r } } ?: { r -> desk.profile.learn(r.kind, r.key, r.label, r.weight, r.why, r.evidence, r.runId) },
            zone = { zone }, now = { noon })

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = Json.parseToJsonElement(
        pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + if (v is String && !v.startsWith("[")) Json.encodeToString(String.serializer(), v) else v.toString() }).jsonObject

    @Test fun alertModeResolvesTheSignalAndReportsTheDecision() = runBlocking {
        val (desk, sink) = desk({ noon })
        val out = tools(desk).invoke("alert", TaskChatRole.SCOUT, "scout", args("mode" to "alert", "signalId" to "s1", "alertLevel" to "urgent",
            "title" to "Dana", "body" to "Sign the form", "whyNow" to "Due today"), false)
        assertEquals("urgent", out["level"]!!.jsonPrimitive.content)
        assertEquals("com.whatsapp", sink.shown.single().app)
        val unknown = runCatching { tools(desk).invoke("alert", TaskChatRole.SCOUT, "scout", args("signalId" to "nope", "alertLevel" to "notice", "title" to "t", "body" to "b", "whyNow" to "w"), false) }
        assertTrue(unknown.isFailure)
    }

    @Test fun profileSetNeedsTheOwnerAndLearnNeedsEvidence() = runBlocking {
        val (desk, _) = desk({ noon })
        val set = args("operation" to "set", "profileKind" to "person", "key" to "Dana", "weight" to 2)
        assertTrue(runCatching { tools(desk).invoke("profile", TaskChatRole.MANAGER, "m", set, false) }.isFailure)
        assertTrue(runCatching { tools(desk).invoke("profile", TaskChatRole.SCOUT, "s", set, true) }.isFailure)
        tools(desk).invoke("profile", TaskChatRole.MANAGER, "m", set, true)
        assertTrue(desk.profile.match(null, null, "Dana", null).starred)

        val a = desk.raise(input(AlertLevel.NOTICE, conversation = "Group"))
        desk.onFeedback(a.alert.id, FeedbackAction.NOT_IMPORTANT)
        val fid = desk.book.feedback.single().id
        val staged = mutableListOf<LearnRequest>()
        val learn = args("operation" to "learn", "profileKind" to "conversation", "key" to "com.whatsapp|Group", "weight" to -1, "why" to "not important",
            "eventIds" to "[\"$fid\"]")
        assertTrue(runCatching { tools(desk, learned = staged).invoke("profile", TaskChatRole.COACH, "c",
            args("operation" to "learn", "profileKind" to "conversation", "key" to "k", "weight" to -1, "why" to "w", "eventIds" to "[\"made-up\"]"), false) }.isFailure)
        assertTrue(runCatching { tools(desk, learned = staged).invoke("profile", TaskChatRole.COACH, "c",
            args("operation" to "learn", "profileKind" to "person", "key" to "Dana", "weight" to -1, "why" to "w", "eventIds" to "[\"$fid\"]"), false) }.isFailure)
        tools(desk, learned = staged).invoke("profile", TaskChatRole.COACH, "c", learn, false)
        assertEquals("run-x", staged.single().runId)
        val read = tools(desk).invoke("profile", TaskChatRole.COACH, "c", args("operation" to "read"), false)
        assertNotNull(read["attention"])
        assertEquals(1, read["entries"]!!.jsonArray.size)
    }

    @Test fun rolesGetOnlyTheirModes() {
        assertTrue(ProactiveTools.allowed(TaskChatRole.SCOUT, "alert"))
        assertFalse(ProactiveTools.allowed(TaskChatRole.COACH, "alert"))
        assertFalse(ProactiveTools.allowed(TaskChatRole.COORDINATOR, "profile"))
        assertFalse(ProactiveTools.allowed(TaskChatRole.ORDINARY, "context"))
    }

    @Test fun chatNameWithoutAppMatchesInEveryApp() {
        val store = OwnerProfileStore(File(dir(), "p.json")) { noon }
        store.setOwner(ProfileKind.CONVERSATION, "Family", null, 2, alwaysNotify = true)
        assertTrue(store.match("com.whatsapp", "Family", "Dana", null, group = true).alwaysNotify)
        assertTrue(store.match("org.telegram.messenger", "Family", null, null, group = true).starred)
        assertFalse(store.match("com.whatsapp", "Work", "Dana", null, group = true).starred)
        store.setOwner(ProfileKind.CONVERSATION, "com.whatsapp|Work", null, -2)
        assertTrue(store.match("com.whatsapp", "Work", null, null, group = true).muted != null)
        assertFalse(store.match("org.telegram.messenger", "Work", null, null, group = true).muted != null)
    }
}
