package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationOverviewTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = ZonedDateTime.parse("2026-09-15T13:00:00+03:00[Asia/Jerusalem]")
    private val history = InMemoryAutomationHistory(zone)
    private val everything = AutomationTriggerKind.entries.toSet()
    private val noPlaces = everything - AutomationTriggerKind.PLACE

    private fun rule(json: String) = AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)

    private fun overview(rules: List<AutomationRule>, supported: Set<AutomationTriggerKind> = everything) =
        AutomationOverview.of(rules, history, supported, now) { pkg ->
            if (pkg == "com.whatsapp") "WhatsApp" else null
        }

    private val dad = rule(
        """
        {"id":"dad-after-seven","description":"Reply to Dad after hours",
         "when":{"type":"notification","package":"com.whatsapp","from":"Dad"},
         "if":[{"type":"time_between","after":"19:00","before":"07:00"}],
         "then":[{"type":"agent_turn","prompt":"reply"}]}
        """,
    )
    private val evening = rule(
        """
        {"id":"evening-post","description":"Post the evening update",
         "when":{"type":"schedule","at":"19:00"},
         "then":[{"type":"run_workflow","workflow":"post"}]}
        """,
    )
    private val homeLights = rule(
        """
        {"id":"home-lights","description":"Lights when I get home",
         "when":{"type":"place","place":"home","transition":"enter"},
         "then":[{"type":"run_workflow","workflow":"lights"}]}
        """,
    )
    private val morning = rule(
        """
        {"id":"morning-briefing","enabled":false,
         "when":{"type":"schedule","at":"07:00","days":["mon","tue","wed","thu","fri"]},
         "then":[{"type":"voice_call","opening":"Morning"}]}
        """,
    )

    // --- the line a person reads ---

    @Test fun aTriggerAndItsConditionsReadAsOneSentence() {
        val summary = AutomationSummaries.of(dad, history, everything, now) { "WhatsApp" }
        assertEquals("WhatsApp from Dad, after 19:00 and before 07:00", summary.trigger)
    }

    @Test fun aDailyScheduleSaysEveryDay() {
        assertEquals("Every day at 19:00", AutomationSummaries.triggerLine(evening))
    }

    @Test fun namedDaysAreListedTheWayPeopleSayThem() {
        assertEquals("Mon, Tue, Wed, Thu and Fri at 07:00", AutomationSummaries.triggerLine(morning))
    }

    @Test fun anIntervalReadsInHoursWhenItDividesEvenly() {
        val hourly = rule("""{"id":"x","when":{"type":"schedule","everyMinutes":120},"then":[{"type":"notify","text":"x"}]}""")
        assertEquals("Every 2 hours", AutomationSummaries.triggerLine(hourly))
        val quarterly = rule("""{"id":"y","when":{"type":"schedule","everyMinutes":45},"then":[{"type":"notify","text":"x"}]}""")
        assertEquals("Every 45 minutes", AutomationSummaries.triggerLine(quarterly))
    }

    @Test fun anAppWithNoLabelStillReadsLikeAnApp() {
        val other = rule("""{"id":"z","when":{"type":"notification","package":"com.instagram.android"},"then":[{"type":"notify","text":"x"}]}""")
        assertEquals("Android", AutomationSummaries.triggerLine(other))
    }

    @Test fun theChipNameComesFromTheIdSoItIsAlwaysShort() {
        // A description is a sentence; a chip has room for two or three words.
        assertEquals("Home lights", AutomationSummaries.chipName("home-lights"))
        assertEquals("Dad after seven", AutomationSummaries.chipName("dad-after-seven"))
    }

    // --- the three states ---

    @Test fun aRuleThisPhoneCannotServeIsBlockedRatherThanOn() {
        val summary = AutomationSummaries.of(homeLights, history, noPlaces, now)
        assertEquals(AutomationSummary.Status.BLOCKED, summary.status)
        assertTrue(summary.detail, summary.detail.contains("never run"))
        assertEquals("Mike can’t track location yet", summary.blockedReason)
    }

    @Test fun aBlockedRuleThatUsedToRunSaysItStopped() {
        history.record("home-lights", now.minusDays(2))
        val summary = AutomationSummaries.of(homeLights, history, noPlaces, now)
        assertTrue(summary.detail, summary.detail.contains("stopped running"))
    }

    @Test fun aDisabledRuleIsOffWhateverElseIsTrue() {
        assertEquals(
            AutomationSummary.Status.OFF,
            AutomationSummaries.of(morning, history, emptySet(), now).status,
        )
    }

    @Test fun aScheduledRuleCountsDownToItsNextRun() {
        val summary = AutomationSummaries.of(evening, history, everything, now)
        assertEquals("Next run in 6 hours", summary.detail)
    }

    @Test fun anEventRuleReportsWhenItLastRan() {
        history.record("dad-after-seven", now.minusDays(1).withHour(21).withMinute(40))
        val summary = AutomationSummaries.of(dad, history, everything, now)
        assertTrue(summary.detail, summary.detail.startsWith("Ran yesterday at 21:40"))
    }

    @Test fun severalRunsTodayAreCounted() {
        history.record("dad-after-seven", now.minusHours(3))
        history.record("dad-after-seven", now.minusHours(1))
        val summary = AutomationSummaries.of(dad, history, everything, now)
        assertTrue(summary.detail, summary.detail.contains("2 times today"))
    }

    // --- the strip ---

    @Test fun whatNeedsYouSortsFirst() {
        val view = overview(listOf(evening, morning, homeLights, dad), noPlaces)
        assertEquals("home-lights", view.chips.first().id)
        assertEquals("morning-briefing", view.summaries.last().id)
    }

    @Test fun theStripNeverGrowsPastThreeChips() {
        val view = overview(listOf(evening, morning, homeLights, dad), noPlaces)
        assertEquals(3, view.chips.size)
        assertEquals(1, view.hidden)
    }

    @Test fun theSentenceNamesTheRuleThatCannotRun() {
        val view = overview(listOf(evening, homeLights), noPlaces)
        assertEquals("Home lights can’t run — Mike can’t track location yet", view.line)
        assertTrue(view.lineIsWarning)
        assertTrue(view.needsAttention)
    }

    @Test fun withNothingWrongTheSentenceIsTheNextRun() {
        val view = overview(listOf(evening, dad))
        assertEquals("Next: Evening post, in 6 hours", view.line)
        assertFalse(view.lineIsWarning)
        assertFalse(view.needsAttention)
    }

    @Test fun theSoonestRuleWinsTheSentence() {
        val earlier = rule("""{"id":"tea","when":{"type":"schedule","at":"16:00"},"then":[{"type":"notify","text":"x"}]}""")
        assertEquals("Next: Tea, in 3 hours", overview(listOf(evening, earlier)).line)
    }

    @Test fun withNoScheduledRuleTheSentenceStillSaysSomethingTrue() {
        val view = overview(listOf(dad))
        assertEquals("1 watching for their moment", view.line)
    }

    @Test fun everyRuleOffSaysSo() {
        val view = overview(listOf(morning))
        assertEquals("Every rule is turned off", view.line)
        assertEquals(0, view.enabled)
        assertFalse(view.needsAttention)
    }

    @Test fun noRulesAtAllInvitesRatherThanReportsZero() {
        val view = overview(emptyList())
        assertTrue(view.isEmpty)
        assertEquals("Ask Mike to do something on a schedule", view.line)
        assertFalse(view.needsAttention)
    }

    @Test fun theCountsSplitOnAndOffAndBlocked() {
        val view = overview(listOf(evening, morning, homeLights, dad), noPlaces)
        assertEquals(3, view.enabled)
        assertEquals(1, view.off)
        assertEquals(1, view.blocked)
    }

    // --- the rule's own screen ---

    @Test fun theChainSplitsTheTriggerFromItsConditions() {
        val summary = AutomationSummaries.of(dad, history, everything, now) { "WhatsApp" }
        assertEquals("WhatsApp from Dad", summary.whenLine)
        assertEquals(listOf("After 19:00 and before 07:00"), summary.conditions)
    }

    @Test fun anActionReadsAsWhatItDoes() {
        assertEquals(
            listOf("Runs the \"post\" sequence on your phone"),
            AutomationSummaries.of(evening, history, everything, now).actions,
        )
        assertEquals(
            listOf("Mike works out what to do and does it"),
            AutomationSummaries.of(dad, history, everything, now).actions,
        )
    }

    @Test fun theCostIsNamedOnlyWhenThereIsOne() {
        assertEquals("Costs a thinking turn", AutomationSummaries.of(dad, history, everything, now).cost)
        assertEquals("Needs you there", AutomationSummaries.of(morning, history, everything, now).cost)
        assertNull(AutomationSummaries.of(evening, history, everything, now).cost)
    }

    @Test fun whatLeavesThePhoneIsNamedFieldByField() {
        // The rule reads the body to decide and sends only the sender.
        val summary = AutomationSummaries.of(dad, history, everything, now)
        assertEquals("Nothing", summary.sends)

        val withName = rule(
            """
            {"id":"dad2","when":{"type":"notification","package":"com.whatsapp"},
             "if":[{"type":"text","field":"notification.text","contains":"dinner"}],
             "then":[{"type":"agent_turn","prompt":"Reply to {{notification.title}}"}]}
            """,
        )
        val named = AutomationSummaries.of(withName, history, everything, now)
        assertEquals("Only the sender\u2019s name", named.sends)
        assertTrue(named.sendsNote, named.sendsNote.contains("never sent"))
    }

    @Test fun aRuleThatSendsNothingSaysSoPlainly() {
        val summary = AutomationSummaries.of(evening, history, everything, now)
        assertEquals("Nothing", summary.sends)
        assertTrue(summary.sendsNote, summary.sendsNote.contains("nothing transmitted"))
    }

    @Test fun twoExportedFieldsReadAsAList() {
        val both = rule(
            """
            {"id":"both","when":{"type":"notification","package":"com.whatsapp"},
             "then":[{"type":"notify","text":"{{notification.title}} at {{now.time}}"}]}
            """,
        )
        assertEquals("Only the sender\u2019s name and the time", AutomationSummaries.of(both, history, everything, now).sends)
    }
}