package dev.androidagent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationEvaluatorTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val history = InMemoryAutomationHistory(zone)
    private val evaluator = AutomationEvaluator(history)

    private fun at(text: String) = ZonedDateTime.parse("$text+03:00[Asia/Jerusalem]")

    private fun rule(json: String) = AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)

    private fun context(
        now: ZonedDateTime,
        places: Set<String> = emptySet(),
        deviceState: Map<String, String> = emptyMap(),
        userReachable: Boolean = true,
        agentAvailable: Boolean = true,
    ) = AutomationContext(now, places, deviceState, userReachable, agentAvailable)

    private fun whatsapp(title: String = "Dad", text: String = "call me", at: ZonedDateTime) =
        AutomationEvent.Notification("com.whatsapp", title, text, at)

    private val dadAfterSeven = rule(
        """
        {"id":"dad-after-seven",
         "when":{"type":"notification","package":"com.whatsapp","from":"Dad"},
         "if":[{"type":"time_between","after":"19:00","before":"07:00"}],
         "then":[{"type":"agent_turn","prompt":"Tell {{notification.title}} I cannot talk."}]}
        """,
    )

    private fun evaluate(rule: AutomationRule, event: AutomationEvent, context: AutomationContext) =
        evaluator.evaluate(listOf(rule), event, context).single()

    private fun skip(outcome: AutomationEvaluator.Outcome) =
        (outcome as AutomationEvaluator.Outcome.Skipped).reason

    @Test fun theRuleFiresWhenTheTriggerAndEveryConditionHold() {
        val now = at("2026-09-15T21:40:00")
        val outcome = evaluate(dadAfterSeven, whatsapp(at = now), context(now))
        assertTrue(outcome is AutomationEvaluator.Outcome.Fired)
        val fired = outcome as AutomationEvaluator.Outcome.Fired
        assertEquals("Tell Dad I cannot talk.", fired.actions.single().raw.str("prompt"))
    }

    @Test fun aWindowThatCrossesMidnightIsNotAnEmptyWindow() {
        // "after 19:00 and before 07:00" read as a plain range is empty, and a
        // rule that silently never fires is this feature's worst failure.
        for (time in listOf("2026-09-15T19:00:00", "2026-09-15T23:59:00", "2026-09-16T02:00:00", "2026-09-16T06:59:00")) {
            val now = at(time)
            assertTrue(time, evaluate(dadAfterSeven, whatsapp(at = now), context(now)) is AutomationEvaluator.Outcome.Fired)
        }
        for (time in listOf("2026-09-15T07:00:00", "2026-09-15T12:00:00", "2026-09-15T18:59:00")) {
            val now = at(time)
            assertEquals(time, AutomationEvaluator.Skip.CONDITION, skip(evaluate(dadAfterSeven, whatsapp(at = now), context(now))))
        }
    }

    @Test fun anotherAppsNotificationDoesNotMatch() {
        val now = at("2026-09-15T21:40:00")
        val other = AutomationEvent.Notification("com.instagram.android", "Dad", "hi", now)
        assertEquals(AutomationEvaluator.Skip.TRIGGER, skip(evaluate(dadAfterSeven, other, context(now))))
    }

    @Test fun anotherSenderInTheRightAppDoesNotMatch() {
        val now = at("2026-09-15T21:40:00")
        assertEquals(
            AutomationEvaluator.Skip.TRIGGER,
            skip(evaluate(dadAfterSeven, whatsapp(title = "Work group", at = now), context(now))),
        )
    }

    @Test fun onlyTheInterpolatedFieldLeavesThePhone() {
        // The rule reads the body to decide, and sends only the title.
        val withBody = rule(
            """
            {"id":"dinner","when":{"type":"notification","package":"com.whatsapp"},
             "if":[{"type":"text","field":"notification.text","contains":"dinner"}],
             "then":[{"type":"agent_turn","prompt":"Reply to {{notification.title}}."}]}
            """,
        )
        val now = at("2026-09-15T21:40:00")
        val event = whatsapp(title = "Dad", text = "are you coming to dinner", at = now)
        val fired = evaluate(withBody, event, context(now)) as AutomationEvaluator.Outcome.Fired
        assertEquals(mapOf("notification.title" to "Dad"), fired.exported)
        assertFalse(fired.actions.single().raw.toString().contains("dinner"))
    }

    @Test fun aConditionOnTheBodyStillDecidesCorrectly() {
        val withBody = rule(
            """
            {"id":"dinner","when":{"type":"notification","package":"com.whatsapp"},
             "if":[{"type":"text","field":"notification.text","contains":"dinner"}],
             "then":[{"type":"notify","text":"ping"}]}
            """,
        )
        val now = at("2026-09-15T21:40:00")
        assertTrue(evaluate(withBody, whatsapp(text = "dinner at 8", at = now), context(now)) is AutomationEvaluator.Outcome.Fired)
        assertEquals(
            AutomationEvaluator.Skip.CONDITION,
            skip(evaluate(withBody, whatsapp(text = "where are you", at = now), context(now))),
        )
    }

    @Test fun aNegatedConditionInverts() {
        val notAtHome = rule(
            """
            {"id":"away","when":{"type":"notification","package":"com.whatsapp"},
             "if":[{"type":"at_place","place":"home","not":true}],
             "then":[{"type":"notify","text":"ping"}]}
            """,
        )
        val now = at("2026-09-15T12:00:00")
        assertTrue(evaluate(notAtHome, whatsapp(at = now), context(now, places = setOf("office"))) is AutomationEvaluator.Outcome.Fired)
        assertEquals(
            AutomationEvaluator.Skip.CONDITION,
            skip(evaluate(notAtHome, whatsapp(at = now), context(now, places = setOf("home")))),
        )
    }

    @Test fun anAlarmThatFiresAtTheWrongMinuteDoesNotPost() {
        // Android coalesces, delays and batches alarms. The schedule is
        // re-checked against the event's own clock for exactly this reason.
        val post = rule(
            """
            {"id":"evening-post","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"post-to-facebook"}]}
            """,
        )
        assertTrue(evaluate(post, AutomationEvent.Clock(at("2026-09-15T19:00:12")), context(at("2026-09-15T19:00:12"))) is AutomationEvaluator.Outcome.Fired)
        val early = at("2026-09-15T18:52:00")
        assertEquals(AutomationEvaluator.Skip.TRIGGER, skip(evaluate(post, AutomationEvent.Clock(early), context(early))))
    }

    @Test fun namingARuleInAManualRunIsItsTrigger() {
        // Otherwise a scheduled rule could only ever be proved by waiting until
        // 19:00 — not a loop anyone actually checks a standing rule with.
        val post = rule(
            """
            {"id":"evening-post","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"post-to-facebook"}]}
            """,
        )
        val now = at("2026-09-15T11:00:00")
        assertTrue(evaluate(post, AutomationEvent.Manual("evening-post", now), context(now)) is AutomationEvaluator.Outcome.Fired)
    }

    @Test fun aManualRunStillObeysTheConditionsAndTheGuards() {
        // Supplying the trigger is not the same as overriding the rule: "run it
        // now" must not post out of hours or past the daily limit.
        val now = at("2026-09-15T12:00:00")
        assertEquals(
            AutomationEvaluator.Skip.CONDITION,
            skip(evaluate(dadAfterSeven, AutomationEvent.Manual("dad-after-seven", now), context(now))),
        )
        val evening = at("2026-09-15T21:40:00")
        history.record(dadAfterSeven.id, evening.minusSeconds(10))
        assertEquals(
            AutomationEvaluator.Skip.COOLDOWN,
            skip(evaluate(dadAfterSeven, AutomationEvent.Manual("dad-after-seven", evening), context(evening))),
        )
    }

    @Test fun aManualRunOnlyRunsTheRuleItNames() {
        val a = rule("""{"id":"a","when":{"type":"manual"},"then":[{"type":"notify","text":"a"}]}""")
        val b = rule("""{"id":"b","when":{"type":"manual"},"then":[{"type":"notify","text":"b"}]}""")
        val now = at("2026-09-15T12:00:00")
        val outcomes = evaluator.evaluate(listOf(a, b), AutomationEvent.Manual("a", now), context(now))
        assertTrue(outcomes[0] is AutomationEvaluator.Outcome.Fired)
        assertEquals(AutomationEvaluator.Skip.TRIGGER, skip(outcomes[1]))
    }

    @Test fun aDisabledRuleSaysSoRatherThanFailingAConditionQuietly() {
        val off = dadAfterSeven.copy(enabled = false)
        val now = at("2026-09-15T21:40:00")
        assertEquals(AutomationEvaluator.Skip.DISABLED, skip(evaluate(off, whatsapp(at = now), context(now))))
    }

    @Test fun theCooldownHoldsASecondFireAndSaysHowLongIsLeft() {
        val now = at("2026-09-15T21:40:00")
        history.record(dadAfterSeven.id, now.minusSeconds(30))
        val outcome = evaluate(dadAfterSeven, whatsapp(at = now), context(now))
        assertEquals(AutomationEvaluator.Skip.COOLDOWN, skip(outcome))
        assertTrue((outcome as AutomationEvaluator.Outcome.Skipped).detail.contains("to go"))
        assertTrue(outcome.reason.retryable)
    }

    @Test fun theCooldownLetsGoOnceItHasPassed() {
        val now = at("2026-09-15T21:40:00")
        history.record(dadAfterSeven.id, now.minusMinutes(5))
        assertTrue(evaluate(dadAfterSeven, whatsapp(at = now), context(now)) is AutomationEvaluator.Outcome.Fired)
    }

    @Test fun theDailyLimitStopsOneBusyGroupChatFromBecomingAHundredTurns() {
        val capped = rule(
            """
            {"id":"capped","when":{"type":"notification","package":"com.whatsapp"},
             "then":[{"type":"agent_turn","prompt":"reply"}],
             "guard":{"cooldownMinutes":0,"maxPerDay":2}}
            """,
        )
        val now = at("2026-09-15T21:40:00")
        repeat(2) { history.record(capped.id, now.minusMinutes(10L * it + 1)) }
        assertEquals(AutomationEvaluator.Skip.DAILY_LIMIT, skip(evaluate(capped, whatsapp(at = now), context(now))))
    }

    @Test fun yesterdaysFiresDoNotCountAgainstToday() {
        val capped = rule(
            """
            {"id":"capped","when":{"type":"notification","package":"com.whatsapp"},
             "then":[{"type":"notify","text":"x"}],
             "guard":{"cooldownMinutes":0,"maxPerDay":1}}
            """,
        )
        val now = at("2026-09-15T09:00:00")
        history.record(capped.id, now.minusDays(1))
        assertTrue(evaluate(capped, whatsapp(at = now), context(now)) is AutomationEvaluator.Outcome.Fired)
    }

    @Test fun aRuleThatWantsToTalkIsHeldWhileYouCannotAnswer() {
        val callMe = rule(
            """
            {"id":"call","when":{"type":"schedule","at":"07:00"},
             "then":[{"type":"voice_call","opening":"Good morning"}]}
            """,
        )
        val now = at("2026-09-15T07:00:00")
        val held = evaluate(callMe, AutomationEvent.Clock(now), context(now, userReachable = false))
        assertEquals(AutomationEvaluator.Skip.NEEDS_USER, skip(held))
        assertTrue(skip(held).retryable)
        assertTrue(evaluate(callMe, AutomationEvent.Clock(now), context(now, userReachable = true)) is AutomationEvaluator.Outcome.Fired)
    }

    @Test fun aRuleThatNeedsNobodyFiresAtALockedPhone() {
        val post = rule(
            """
            {"id":"post","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"post-to-facebook"}]}
            """,
        )
        val now = at("2026-09-15T19:00:00")
        assertTrue(evaluate(post, AutomationEvent.Clock(now), context(now, userReachable = false)) is AutomationEvaluator.Outcome.Fired)
    }

    @Test fun aRuleThatNeedsATurnIsHeldWhenNoTurnCanRun() {
        val now = at("2026-09-15T21:40:00")
        val held = evaluate(dadAfterSeven, whatsapp(at = now), context(now, agentAvailable = false))
        assertEquals(AutomationEvaluator.Skip.NEEDS_AGENT, skip(held))
    }

    @Test fun anOutOfHoursRuleSaysSoRatherThanBlamingItsCooldown() {
        // The cooldown passes on its own; the hour does not. Naming the wrong
        // one sends the user to fix the wrong clause.
        val now = at("2026-09-15T12:00:00")
        history.record(dadAfterSeven.id, now.minusSeconds(5))
        assertEquals(AutomationEvaluator.Skip.CONDITION, skip(evaluate(dadAfterSeven, whatsapp(at = now), context(now))))
    }

    @Test fun evaluatingRecordsNothing() {
        // A dry run must not consume the quota it is reporting on.
        val now = at("2026-09-15T21:40:00")
        repeat(5) { evaluate(dadAfterSeven, whatsapp(at = now), context(now)) }
        assertEquals(0, history.firedOn(dadAfterSeven.id, now.toLocalDate()))
    }

    @Test fun everyRuleIsReportedSoNothingFailsSilently() {
        val now = at("2026-09-15T12:00:00")
        val outcomes = evaluator.evaluate(
            listOf(dadAfterSeven, dadAfterSeven.copy(id = "other", enabled = false)),
            whatsapp(at = now),
            context(now),
        )
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { AutomationEvaluator.toJson(it)["why"] != null })
    }

    @Test fun deviceStateIsReadFromTheContextForAConditionAndTheEventForATrigger() {
        val charging = rule(
            """
            {"id":"charging","when":{"type":"device_state","state":"power","is":"charging"},
             "if":[{"type":"device_state","state":"wifi","equals":"home-net"}],
             "then":[{"type":"run_workflow","workflow":"nightly-backup"}]}
            """,
        )
        val now = at("2026-09-15T23:00:00")
        val event = AutomationEvent.DeviceState("power", "charging", now)
        assertTrue(evaluate(charging, event, context(now, deviceState = mapOf("wifi" to "home-net"))) is AutomationEvaluator.Outcome.Fired)
        assertEquals(
            AutomationEvaluator.Skip.CONDITION,
            skip(evaluate(charging, event, context(now, deviceState = mapOf("wifi" to "cafe")))),
        )
    }
}
