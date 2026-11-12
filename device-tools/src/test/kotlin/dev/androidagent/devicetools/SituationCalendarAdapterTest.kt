package dev.androidagent.devicetools

import dev.androidagent.core.*
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SituationCalendarAdapterTest {
    private class Cursor(private val rows: List<List<Any?>>) : SituationCalendarCursor {
        var position = -1
        var closed = false
        override fun moveToNext() = (++position < rows.size)
        override fun isNull(column: Int) = rows[position][column] == null
        override fun string(column: Int) = rows[position][column] as String?
        override fun long(column: Int) = (rows[position][column] as Number).toLong()
        override fun int(column: Int) = (rows[position][column] as Number).toInt()
        override fun close() { closed = true }
    }
    private class Source : SituationCalendarSource {
        var allowed = true
        var profile: String? = "current_android_user:0"
        var cursor: Cursor? = Cursor(emptyList())
        var failure: Exception? = null
        var queries = 0
        var window: Pair<Long, Long>? = null
        override fun permitted() = allowed
        override fun personalProfile() = profile
        override fun query(startMs: Long, endMs: Long): SituationCalendarCursor? {
            queries++
            window = startMs to endMs
            failure?.let { throw it }
            return cursor
        }
    }
    private fun read(source: Source, start: Long = 10, end: Long = 100, limit: Int = 2) =
        SituationCalendarAdapter.read(source, start, end, limit, 20)

    @Test fun emptyCursorIsCompleteButNullAndFailureAreNot() {
        val source = Source()
        val empty = source.cursor!!
        assertEquals("complete", read(source).status)
        assertTrue(empty.closed)
        source.cursor = null
        assertEquals("provider returned no cursor", read(source).status)
        source.failure = IllegalStateException("fixture")
        assertEquals("provider error", read(source).status)
        source.failure = SecurityException("revoked during query")
        assertEquals("permission denied", read(source).status)
    }

    @Test fun validatesWindowAndLimitAndDetectsOnlyActualTruncation() {
        val source = Source()
        val rows = listOf(listOf(1L, "one", 11L, 12L, 0, "UTC"), listOf(2L, "two", 13L, 14L, 0, "UTC"))
        source.cursor = Cursor(rows)
        val exact = read(source)
        assertEquals("complete", exact.status)
        assertEquals(10L to 100L, source.window)
        assertEquals(2, exact.events.size)
        source.cursor = Cursor(rows)
        val limited = read(source, limit = 1)
        assertEquals("truncated", limited.status)
        assertEquals(listOf("1"), limited.events.map { it.id })
        assertEquals(1, limited.limit)
        val before = source.queries
        assertEquals("invalid query", read(source, start = 100, end = 100).status)
        assertEquals("invalid query", read(source, limit = 101).status)
        assertEquals(before, source.queries)
    }

    @Test fun permissionAndUnknownOrExcludedProfileStopBeforeProviderContent() {
        val source = Source()
        source.allowed = false
        assertEquals("permission denied", read(source).status)
        source.allowed = true
        for (profile in listOf(
            SituationCalendarAdapter.personalProfile(null, 0),
            SituationCalendarAdapter.personalProfile(false, 11),
            SituationCalendarAdapter.personalProfile(true, -1))) {
            source.profile = profile
            assertEquals("profile unknown or excluded", read(source).status)
        }
        assertEquals(0, source.queries)
    }

    @Test fun utcAllDayAndDstInstantsAndTimezoneSurviveWithoutReinterpretation() {
        val start = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        val dstStart = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli()
        val dstEnd = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()
        val source = Source().apply { cursor = Cursor(listOf(
            listOf(1L, "All day", start, start + 86_400_000, 1, "UTC"),
            listOf(2L, "DST", dstStart, dstEnd, 0, "Europe/London"))) }
        val events = read(source, start, start + 172_800_000).events
        assertEquals(SituationCalendarEvent("1", "All day", start, start + 86_400_000, true, "UTC"), events[0])
        assertEquals(SituationCalendarEvent("2", "DST", dstStart, dstEnd, false, "Europe/London"), events[1])
        assertEquals(0, Instant.ofEpochMilli(events[1].startMs).atZone(ZoneId.of("Europe/London")).hour)
        assertEquals(2, Instant.ofEpochMilli(events[1].endMs).atZone(ZoneId.of("Europe/London")).hour)
    }

    @Test fun managerToolReceivesAdapterAndSavedPlacesAndFiltersRevocationProfileAndExpiry() = runTest {
        val dir = Files.createTempDirectory("manager-calendar-adapter")
        val prefs = dir.resolve("preferences.json").toFile().apply {
            writeText("""{"addresses":{"home":"Home fixture","work":"Work fixture"}}""")
        }
        val source = Source().apply { cursor = Cursor(listOf(listOf(1L, "Fixture", 30L, 40L, 0, "UTC"))) }
        val situation = SharedSituationStore(dir.resolve("state.json").toFile())
        val zone = ZoneId.of("UTC")
        var now = 20L
        var enabled = true
        var profile: String? = source.profile
        situation.refresh(emptyList(), now, zone, true, true, SavedPlaces.read(prefs), read(source)) { true }
        val boardStore = InMemoryTaskBoardStore()
        val tools = TaskTools(TaskBoard(boardStore).apply { load() }, MemoryBook(boardStore).apply { load() },
            TeamRoster(boardStore).apply { load() }, { TaskChatRole.MANAGER }, { "unused" },
            situation = { situation.snapshot(now, zone, emptySet(), enabled && source.allowed && profile != null, profile) })
        suspend fun snapshot(): JsonObject {
            val result = tools.invoke("manager", buildJsonObject { put("mode", "situation") }, false)
            assertTrue(result.success)
            return Json.parseToJsonElement(result.text).jsonObject.getValue("situation").jsonObject
        }
        suspend fun assertCalendarHidden() {
            val calendar = snapshot()["calendar"]
            assertTrue(calendar == null || calendar == JsonNull)
        }
        val fresh = snapshot()
        assertEquals("Fixture", fresh.getValue("calendar").jsonObject.getValue("events").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(fresh.getValue("claims").jsonArray.any { it.jsonObject["value"]?.jsonPrimitive?.content == "Home fixture" })
        source.allowed = false
        assertCalendarHidden()
        source.allowed = true
        enabled = false
        assertCalendarHidden()
        enabled = true
        profile = "current_android_user:1"
        assertCalendarHidden()
        profile = null
        assertCalendarHidden()
        profile = source.profile
        now += 120_000
        val stale = snapshot()
        assertTrue(stale["calendar"] == null || stale["calendar"] == JsonNull)
        // An unknown list equal to the default is not encoded.
        val unknown = stale["unknown"]?.jsonArray?.map { it.jsonPrimitive.content } ?: SharedSituation().unknown
        assertTrue("calendar_plan" in unknown)
        // A saved home is a lasting preference: it stays known after the refresh tick expires.
        assertFalse("saved_place_home" in unknown)
        assertTrue(stale.getValue("claims").jsonArray.any { it.jsonObject["value"]?.jsonPrimitive?.content == "Home fixture" })
    }
}
