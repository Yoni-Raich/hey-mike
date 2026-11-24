package dev.androidagent.remote

import dev.androidagent.core.UsageSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AntigravityUsageTest {

    private val sampleJson = """
        {
          "conversation_id": "",
          "status": "SUCCESS",
          "response": "Gemini Models\tWeekly Limit Remaining\t77%\t2026-11-30T21:25:44Z\nGemini Models\tFive Hour Limit Remaining\t13%\t2026-11-24T09:13:36Z\nClaude and GPT models\tWeekly Limit Remaining\t50%\t2026-11-30T04:39:36Z\nClaude and GPT models\tFive Hour Limit Remaining\t100%\t1970-01-01T00:00:00Z\n",
          "duration_seconds": 0,
          "num_turns": 0,
          "command": {
            "name": "usage",
            "data": {
              "groups": [
                {
                  "name": "Gemini Models",
                  "buckets": [
                    {
                      "id": "gemini-weekly",
                      "name": "Weekly Limit Remaining",
                      "window": "weekly",
                      "remaining_fraction": 0.7744951844215393,
                      "reset_time": "2026-11-30T21:25:44Z"
                    },
                    {
                      "id": "gemini-5h",
                      "name": "Five Hour Limit Remaining",
                      "window": "5h",
                      "remaining_fraction": 0.1341097354888916,
                      "reset_time": "2026-11-24T09:13:36Z"
                    }
                  ]
                },
                {
                  "name": "Claude and GPT models",
                  "buckets": [
                    {
                      "id": "3p-weekly",
                      "name": "Weekly Limit Remaining",
                      "window": "weekly",
                      "remaining_fraction": 0.5,
                      "reset_time": "2026-11-30T04:39:36Z"
                    },
                    {
                      "id": "3p-5h",
                      "name": "Five Hour Limit Remaining",
                      "window": "5h",
                      "remaining_fraction": 1,
                      "reset_time": "1970-01-01T00:00:00Z"
                    }
                  ]
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test fun parsesJsonGroupsIntoUsageLimitsAndDropsEpochZeroResetTimes() {
        val limits = AntigravityUsage.parse(sampleJson)
        assertEquals(4, limits.size)

        val geminiWeekly = limits[0]
        assertEquals("Gemini · Weekly", geminiWeekly.name)
        assertEquals(22.55, geminiWeekly.usedPercent ?: -1.0, 0.01)
        assertEquals(Instant.parse("2026-11-30T21:25:44Z").epochSecond, geminiWeekly.resetsAt)
        assertEquals(10_080L, geminiWeekly.windowMinutes)

        val gemini5h = limits[1]
        assertEquals("Gemini · 5-hour", gemini5h.name)
        assertEquals(86.59, gemini5h.usedPercent ?: -1.0, 0.01)
        assertEquals(Instant.parse("2026-11-24T09:13:36Z").epochSecond, gemini5h.resetsAt)
        assertEquals(300L, gemini5h.windowMinutes)

        val thirdPartyWeekly = limits[2]
        assertEquals("Claude & GPT · Weekly", thirdPartyWeekly.name)
        assertEquals(50.0, thirdPartyWeekly.usedPercent ?: -1.0, 0.01)
        assertEquals(Instant.parse("2026-11-30T04:39:36Z").epochSecond, thirdPartyWeekly.resetsAt)
        assertEquals(10_080L, thirdPartyWeekly.windowMinutes)

        val thirdParty5h = limits[3]
        assertEquals("Claude & GPT · 5-hour", thirdParty5h.name)
        assertEquals(0.0, thirdParty5h.usedPercent ?: -1.0, 0.01)
        assertNull("Unstarted window with epoch 0 reset time should have no reset timestamp", thirdParty5h.resetsAt)
        assertEquals(300L, thirdParty5h.windowMinutes)

        // Verify UsageSummary renders distinct labels in shortest-first order.
        val now = Instant.parse("2026-11-24T08:58:00Z").epochSecond
        val windows = UsageSummary.windows(limits, now)
        assertEquals(
            listOf("Gemini · 5-hour", "Claude & GPT · 5-hour", "Gemini · Weekly", "Claude & GPT · Weekly"),
            windows.map { it.label },
        )
        assertEquals("Gemini · 5-hour", UsageSummary.primary(windows)?.label)
    }

    @Test fun ignoresLeadingNoticeAndAnsiAroundJsonPayload() {
        val noisy = "\u001b[2mChecking quota...\u001b[0m\n${sampleJson.replace("\n", "")}\n"
        assertEquals(4, AntigravityUsage.parse(noisy).size)
    }

    @Test fun fallsBackToTabSeparatedTextWhenGroupsAreAbsent() {
        val tsv = """
            Gemini Models	Weekly Limit Remaining	77%	2026-11-30T21:25:44Z
            Gemini Models	Five Hour Limit Remaining	13%	2026-11-24T09:13:36Z
            Claude and GPT models	Five Hour Limit Remaining	100%	1970-01-01T00:00:00Z
        """.trimIndent()
        val limits = AntigravityUsage.parse(tsv)
        assertEquals(3, limits.size)
        assertEquals("Gemini · Weekly", limits[0].name)
        assertEquals(23.0, limits[0].usedPercent ?: -1.0, 0.01)
        assertEquals(10_080L, limits[0].windowMinutes)
        assertEquals("Gemini · 5-hour", limits[1].name)
        assertEquals(87.0, limits[1].usedPercent ?: -1.0, 0.01)
        assertEquals(300L, limits[1].windowMinutes)
        assertNull(limits[2].resetsAt)
    }

    @Test fun nonSuccessStatusAndMalformedInputReturnEmpty() {
        assertTrue(AntigravityUsage.parse("""{"status":"ERROR","response":"Not signed in"}""").isEmpty())
        assertTrue(AntigravityUsage.parse("not json at all").isEmpty())
        assertTrue(AntigravityUsage.parse("").isEmpty())
    }

    @Test fun clampsOutOfRangeFractionsAndHandlesGenericWindowTokens() {
        val json = """
            {
              "status": "SUCCESS",
              "command": {
                "name": "usage",
                "data": {
                  "groups": [
                    {
                      "name": "Custom Models",
                      "buckets": [
                        {"id": "b1", "window": "2h", "remaining_fraction": -0.2, "reset_time": "2026-11-24T12:00:00Z"},
                        {"id": "b2", "window": "3d", "remaining_fraction": 1.4}
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val limits = AntigravityUsage.parse(json)
        assertEquals(2, limits.size)
        assertEquals("Custom · 2-hour", limits[0].name)
        assertEquals(100.0, limits[0].usedPercent ?: -1.0, 0.001)
        assertEquals(120L, limits[0].windowMinutes)
        assertEquals("Custom · 3-day", limits[1].name)
        assertEquals(0.0, limits[1].usedPercent ?: -1.0, 0.001)
        assertEquals(4_320L, limits[1].windowMinutes)
    }

    @Test fun installedCliReportsStructuredUsageWhenSignedIn() {
        val executable = System.getenv("MIKE_TEST_AGY")
        org.junit.Assume.assumeTrue(!executable.isNullOrBlank())
        val process = ProcessBuilder(executable, "-p", "/usage", "--output-format", "json", "--print-timeout", "15s")
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        val code = process.waitFor()
        if (code != 0) {
            val combined = "$stderr\n$stdout"
            assertTrue("Expected auth failure when exit != 0, got: $combined",
                combined.contains("sign in", ignoreCase = true) || combined.contains("authenticat", ignoreCase = true))
            return
        }
        val limits = AntigravityUsage.parse(stdout)
        assertTrue("Expected at least one Antigravity usage bucket, got stdout=$stdout", limits.isNotEmpty())
        assertTrue(limits.all { it.usedPercent != null && it.usedPercent!! in 0.0..100.0 })
    }
}
