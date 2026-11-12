package dev.androidagent.core

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The agent must notice missing information the current request needs, try the allowed source, ask the
 * user once when the source fails, save the answer where it belongs and not repeat the question.
 */
class MissingSituationInfoTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private var clock = 1_794_481_469_818L
    private val dir: File = Files.createTempDirectory("missing-info-test").toFile()
    private val preferences = File(dir, "preferences.json")
    private val store = SharedSituationStore(File(dir, "situation.json"))
    private var role = TaskChatRole.MANAGER
    private val fixes = ArrayDeque<String>()
    private var reads = 0

    private fun snapshot() = store.snapshot(clock, zone, emptySet())
    private fun refresh() = store.refresh(emptyList(), clock, zone, false, false, SavedPlaces.read(preferences)) { true }

    private val tools: TaskTools = run {
        val backing = InMemoryTaskBoardStore()
        val board = TaskBoard(backing, now = { clock }); val memory = MemoryBook(backing, now = { clock }); val roster = TeamRoster(backing)
        kotlinx.coroutines.runBlocking { board.load(); memory.load(); roster.load() }
        TaskTools(board, memory, roster, { role }, { "unused" }, now = { clock }, zone = { zone },
            situation = ::snapshot,
            correctSituation = { field, value, until -> store.correct(field, value, clock, until); snapshot() },
            refreshLocation = { reads++; SituationLocation(fixes.removeFirst(), clock, clock).also { store.recordLocation(it) } },
            recordSituationAsk = { field, purpose -> store.recordAsk(field, purpose, clock); snapshot() },
            declineSituation = { field, purpose -> store.decline(field, purpose, clock); snapshot() },
            savePlace = { key, value -> SavedPlaces.write(preferences, key, value); store.answered("saved_place_$key", clock); refresh(); snapshot() })
    }

    @After fun cleanUp() { dir.deleteRecursively() }

    private suspend fun call(fromUser: Boolean = true, vararg pairs: Pair<String, String>): JsonObject {
        val result = tools.invoke("manager", buildJsonObject { put("mode", "situation"); pairs.forEach { (k, v) -> put(k, v) } }, fromUser)
        assertTrue(result.text, result.success)
        return Json.parseToJsonElement(result.text).jsonObject
    }
    private suspend fun need(field: String, purpose: String = "user asked where they are", fromUser: Boolean = true) =
        call(fromUser, "operation" to "need", "field" to field, "purpose" to purpose)["need"]!!.jsonObject
    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.content
    private fun JsonObject.next() = this["next"]?.jsonPrimitive?.content.orEmpty()

    @Test fun failedLocationSourcesLeadToOneQuestionInsteadOfAnUnknownDeadEnd() = runTest {
        preferences.writeText("""{"apps":{"maps":"Waze"},"addresses":{"work":"Intel Petah Tikva"}}""")
        refresh()
        // Nothing read yet: try the device source first, not the user.
        assertEquals("try_source", need("current_location").status())
        fixes += "permission denied"
        val denied = call(true, "operation" to "refresh_location")
        assertTrue(denied.next().contains("request_permissions"))
        assertEquals("try_source", need("current_location").status()) // request the permission once
        clock += 40_000
        fixes += "no fresh fix"
        val noFix = call(true, "operation" to "refresh_location")
        assertTrue(noFix.next(), noFix.next().contains("ask them where they are"))
        assertFalse(noFix.next().contains("request_permissions"))
        val ask = need("current_location")
        assertEquals("ask", ask.status())
        assertTrue(ask["saveWith"]!!.jsonPrimitive.content.contains("field=current_location"))
        // The question is recorded so the next turn sees it.
        assertEquals("asked", snapshot().asks.single { it.field == "current_location" }.status)
        assertEquals(2, reads)
    }

    @Test fun repeatedPermissionDenialStopsRequestingThePermission() = runTest {
        fixes += "permission denied"; fixes += "permission denied"
        call(true, "operation" to "refresh_location")
        clock += 3 * 60_000
        val second = call(true, "operation" to "refresh_location")
        assertTrue(second.next().contains("do not request it again"))
        assertEquals("ask", need("current_location").status())
    }

    @Test fun aWorkAddressIsNeverReturnedAsTheCurrentLocation() = runTest {
        preferences.writeText("""{"addresses":{"work":"Intel Petah Tikva"}}""")
        refresh()
        fixes += "no fresh fix"
        call(true, "operation" to "refresh_location")
        val resolved = need("current_location")
        assertNotEquals("known", resolved.status())
        assertNull(resolved["value"])
        assertTrue(resolved["note"]!!.jsonPrimitive.content.contains("work address is not where the user is now"))
        assertTrue("current_location" in snapshot().unknown)
    }

    @Test fun knownInformationNeedsNoQuestionAndAnAnswerExpiresByDefault() = runTest {
        call(true, "operation" to "correct", "field" to "current_location", "value" to "At the Tel Aviv office")
        val known = need("current_location")
        assertEquals("known", known.status())
        assertEquals("At the Tel Aviv office", known["value"]!!.jsonPrimitive.content)
        assertTrue(snapshot().asks.isEmpty())
        assertFalse("current_location" in snapshot().unknown)
        clock += 2 * 3_600_000L
        assertTrue("current_location" in snapshot().unknown)
        assertNotEquals("known", need("current_location").status())
        // availability has no safe default; the user must say until when.
        val result = tools.invoke("manager", buildJsonObject { put("mode", "situation"); put("operation", "correct")
            put("field", "availability"); put("value", "quiet") }, true)
        assertFalse(result.success)
    }

    @Test fun answeringCloseTheOpenQuestionAndUpdatesTheInjectedState() = runTest {
        assertEquals("ask", need("travel_mode", "plan the trip").status())
        call(true, "operation" to "correct", "field" to "travel_mode", "value" to "bus")
        assertEquals("answered", snapshot().asks.single().status)
        assertEquals("known", need("travel_mode", "plan the trip").status())
    }

    @Test fun unknownFieldsTheRequestDoesNotNeedCauseNoQuestionsAndBackgroundTurnsNeverAsk() = runTest {
        refresh()
        call(true, "operation" to "read")
        assertTrue(snapshot().asks.isEmpty())
        assertTrue(snapshot().unknown.containsAll(listOf("availability", "travel_mode", "eta")))
        // A turn that is not the user's own words (child update, briefing) never asks.
        val background = need("current_location", fromUser = false)
        assertEquals("unresolved", background.status())
        assertTrue(background.next().contains("Do not ask, alert or notify"))
        role = TaskChatRole.COORDINATOR
        assertEquals("unresolved", need("availability", "brief the owner").status())
        assertTrue(snapshot().asks.isEmpty())
        assertEquals(0, reads)
    }

    @Test fun aQuestionIsNotRepeatedAndARefusalIsRespected() = runTest {
        assertEquals("ask", need("departure_or_return", "remind before leaving").status())
        clock += 60_000
        assertEquals("asked_recently", need("departure_or_return", "remind before leaving").status())
        assertEquals("ask", need("departure_or_return", "book a taxi").status()) // a new purpose is a new question
        call(true, "operation" to "decline", "field" to "departure_or_return", "purpose" to "book a taxi")
        clock += 3_600_000L
        assertEquals("declined", need("departure_or_return", "book a taxi").status())
        assertEquals("declined", need("departure_or_return", "something else").status())
        clock += SituationNeeds.DAY_MS
        assertEquals("ask", need("departure_or_return", "book a taxi").status())
        // Only the user's own words can decline.
        assertFalse(tools.invoke("manager", buildJsonObject { put("mode", "situation"); put("operation", "decline")
            put("field", "travel_mode") }, false).success)
    }

    @Test fun aSavedPlaceIsLastingKeepsOtherPreferencesAndStaysKnownBetweenRefreshes() = runTest {
        preferences.writeText("""{"apps":{"maps":"Waze"},"addresses":{"work":"Intel Petah Tikva"}}""")
        refresh()
        assertEquals("ask", need("saved_place_home", "navigate home").status())
        assertFalse(tools.invoke("manager", buildJsonObject { put("mode", "situation"); put("operation", "save_place")
            put("field", "saved_place_home"); put("value", "Herzl 1, Tel Aviv") }, false).success)
        call(true, "operation" to "save_place", "field" to "saved_place_home", "value" to "Herzl 1, Tel Aviv")
        val file = Json.parseToJsonElement(preferences.readText()).jsonObject
        assertEquals("Waze", file["apps"]!!.jsonObject["maps"]!!.jsonPrimitive.content)
        assertEquals("Intel Petah Tikva", file["addresses"]!!.jsonObject["work"]!!.jsonPrimitive.content)
        assertEquals("answered", snapshot().asks.single().status)
        // A late refresh tick does not turn a lasting saved place back into a question.
        clock += 10 * 60_000L
        assertFalse("saved_place_home" in snapshot().unknown)
        assertEquals("known", need("saved_place_home", "navigate home").status())
        // A saved home is not where the user is: current location stays unknown.
        assertTrue("current_location" in snapshot().unknown)
        // An address goes through save_place, not a temporary correction.
        assertFalse(tools.invoke("manager", buildJsonObject { put("mode", "situation"); put("operation", "correct")
            put("field", "saved_place_home"); put("value", "elsewhere") }, true).success)
    }

    @Test fun aBrokenPreferencesFileIsNotOverwritten() {
        preferences.writeText("{broken")
        assertTrue(runCatching { SavedPlaces.write(preferences, "home", "Herzl 1") }.isFailure)
        assertEquals("{broken", preferences.readText())
    }

    @Test fun forgettingCollectedDataClearsQuestionsAndLocationHistory() = runTest {
        need("travel_mode", "plan")
        fixes += "no fresh fix"
        call(true, "operation" to "refresh_location")
        assertTrue(snapshot().asks.isNotEmpty() && snapshot().locationAttempts.isNotEmpty())
        store.forgetObserved()
        val reopened = SharedSituationStore(File(dir, "situation.json")).snapshot(clock, zone, emptySet())
        assertTrue(reopened.asks.isEmpty() && reopened.locationAttempts.isEmpty())
    }

    @Test fun managerContextCarriesTheMissingInformationPolicy() {
        val manager = TeamMember("manager", "Mike", "Manager", MemberKind.MANAGER, 0f, sessionId = "manager", createdAt = 0)
        val text = TaskPrompts.ownerContext(manager, emptyList(), emptyList(), zone, "/playbook")
        assertTrue(text.contains("operation=need"))
        assertTrue(text.contains("next step is to ask"))
    }
}
