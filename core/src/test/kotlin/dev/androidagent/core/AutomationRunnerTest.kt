package dev.androidagent.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationRunnerTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = ZonedDateTime.parse("2026-09-15T19:00:00+03:00[Asia/Jerusalem]")
    private val history = InMemoryAutomationHistory(zone)

    private open class Recorder(
        var workflowResult: AutomationActionResult = AutomationActionResult.ok(),
        var answer: Boolean = true,
        var askable: Boolean = true,
    ) : AutomationActions {
        val calls = mutableListOf<String>()
        val questions = mutableListOf<String>()

        override suspend fun runWorkflow(workflow: String, parameters: JsonObject): AutomationActionResult {
            calls += "runWorkflow:$workflow"
            return workflowResult
        }

        override suspend fun openIntent(arguments: JsonObject): AutomationActionResult {
            calls += "openIntent:" + arguments.str("action")
            return AutomationActionResult.ok()
        }

        override suspend fun notify(title: String?, text: String): AutomationActionResult {
            calls += "notify:$text"
            return AutomationActionResult.ok()
        }

        var lastValidUntil: Long? = null
        override suspend fun agentTurn(prompt: String, ruleId: String, validUntil: Long): AutomationActionResult {
            calls += "agentTurn:$prompt"
            lastValidUntil = validUntil
            return AutomationActionResult.ok()
        }

        override suspend fun voiceCall(opening: String): AutomationActionResult {
            calls += "voiceCall:$opening"
            return AutomationActionResult.ok()
        }

        override suspend fun ask(question: String): Boolean {
            questions += question
            calls += "ask"
            return answer
        }

        override fun canAsk(): Boolean = askable
    }

    private fun fired(json: String): AutomationEvaluator.Outcome.Fired {
        val rule = AutomationRule.parse(Json.parseToJsonElement(json.trimIndent()).jsonObject)
        return AutomationEvaluator.Outcome.Fired(rule, rule.actions, emptyMap())
    }

    private fun run(actions: Recorder, rule: String): AutomationRunReport =
        runBlocking { AutomationRunner(actions, history, { now }).run(fired(rule)) }

    @Test fun actionsRunInOrder() {
        val actions = Recorder()
        val report = run(
            actions,
            """
            {"id":"evening","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"post-to-facebook"},
                     {"type":"notify","text":"Posted"}]}
            """,
        )
        assertTrue(report.ok)
        assertEquals(listOf("runWorkflow:post-to-facebook", "notify:Posted"), actions.calls)
        assertEquals(2, report.completed.size)
    }

    @Test fun theFireIsRecordedBeforeTheFirstAction() {
        // `SessionRunQueue` dequeues before it starts for the same reason: a
        // crash between acting and recording would let the rule post again on
        // the next trigger, and a double post is worse than a missed one.
        var recordedBeforeAction = false
        val actions = object : Recorder() {
            override suspend fun runWorkflow(workflow: String, parameters: JsonObject): AutomationActionResult {
                recordedBeforeAction = history.lastFiredAt("evening") != null
                return super.runWorkflow(workflow, parameters)
            }
        }
        run(
            actions,
            """{"id":"evening","when":{"type":"schedule","at":"19:00"},"then":[{"type":"run_workflow","workflow":"x"}]}""",
        )
        assertTrue(recordedBeforeAction)
    }

    @Test fun aFailedActionIsRecordedAnywaySoItCannotLoop() {
        val actions = Recorder(workflowResult = AutomationActionResult.failed("no such workflow"))
        run(
            actions,
            """{"id":"evening","when":{"type":"schedule","at":"19:00"},"then":[{"type":"run_workflow","workflow":"x"}]}""",
        )
        assertEquals(1, history.firedOn("evening", now.toLocalDate()))
    }

    @Test fun aFailureStopsTheRestAndNeverClaimsTheEarlierOnesWereUndone() {
        val actions = Recorder(workflowResult = AutomationActionResult.failed("the app moved", committed = true))
        val report = run(
            actions,
            """
            {"id":"evening","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"notify","text":"Starting"},
                     {"type":"run_workflow","workflow":"post"},
                     {"type":"notify","text":"Done"}]}
            """,
        )
        assertFalse(report.ok)
        assertEquals(1, report.stoppedAt)
        assertEquals("action_failed", report.errorType)
        assertEquals(listOf("notify:Starting", "runWorkflow:post"), actions.calls)
        assertEquals(listOf("notify: Starting"), report.completed)
        val json = report.toJson()
        assertEquals("true", json["earlierActionsAlreadyRan"].toString())
        assertEquals("true", json["stoppedActionMayHaveRun"].toString())
    }

    @Test fun anActionThatThrowsIsTreatedAsAPossiblyCommittedFailure() {
        val actions = object : Recorder() {
            override suspend fun runWorkflow(workflow: String, parameters: JsonObject): AutomationActionResult =
                throw IllegalStateException("the runtime is down")
        }
        val report = run(
            actions,
            """{"id":"x","when":{"type":"schedule","at":"19:00"},"then":[{"type":"run_workflow","workflow":"p"}]}""",
        )
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("the runtime is down"))
        assertTrue(report.committed)
    }

    @Test fun anActionMarkedRequiresApprovalAsksFirst() {
        val actions = Recorder()
        run(
            actions,
            """
            {"id":"x","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"pay-the-bill","requiresApproval":true}]}
            """,
        )
        assertEquals(listOf("ask", "runWorkflow:pay-the-bill"), actions.calls)
        assertTrue(actions.questions.single().contains("pay-the-bill"))
    }

    @Test fun sayingNoStopsTheRunWithoutRunningTheAction() {
        val actions = Recorder(answer = false)
        val report = run(
            actions,
            """
            {"id":"x","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"pay","requiresApproval":true},
                     {"type":"notify","text":"paid"}]}
            """,
        )
        assertFalse(report.ok)
        assertEquals("declined", report.errorType)
        assertEquals(listOf("ask"), actions.calls)
    }

    @Test fun aHostThatCannotAskRefusesTheActionRatherThanRunningIt() {
        // A gate that disappears when unwired is not a gate — the same rule
        // `WorkflowRunner` applies to `confirmation_unavailable`.
        val actions = Recorder(askable = false)
        val report = run(
            actions,
            """
            {"id":"x","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"run_workflow","workflow":"pay","requiresApproval":true}]}
            """,
        )
        assertFalse(report.ok)
        assertEquals("confirmation_unavailable", report.errorType)
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun anAskActionGatesEverythingAfterIt() {
        val yes = Recorder(answer = true)
        val report = run(
            yes,
            """
            {"id":"x","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"ask","question":"Post tonight's update?"},
                     {"type":"run_workflow","workflow":"post"}]}
            """,
        )
        assertTrue(report.ok)
        assertEquals(listOf("ask", "runWorkflow:post"), yes.calls)
        assertEquals("Post tonight's update?", yes.questions.single())

        val no = Recorder(answer = false)
        assertFalse(
            run(
                no,
                """
                {"id":"x","when":{"type":"schedule","at":"19:00"},
                 "then":[{"type":"ask","question":"Post?"},{"type":"run_workflow","workflow":"post"}]}
                """,
            ).ok,
        )
        assertEquals(listOf("ask"), no.calls)
    }

    @Test fun everyActionKindReachesItsOwnMethod() {
        val actions = Recorder()
        run(
            actions,
            """
            {"id":"x","when":{"type":"manual"},
             "then":[{"type":"open_intent","action":"android.intent.action.VIEW"},
                     {"type":"agent_turn","prompt":"think"},
                     {"type":"voice_call","opening":"hello"}]}
            """,
        )
        assertEquals(
            listOf("openIntent:android.intent.action.VIEW", "agentTurn:think", "voiceCall:hello"),
            actions.calls,
        )
    }

    @Test fun theRuleIdReachesTheTurnSoItLandsInItsOwnChat() {
        var seen: String? = null
        val actions = object : Recorder() {
            override suspend fun agentTurn(prompt: String, ruleId: String, validUntil: Long): AutomationActionResult {
                seen = ruleId
                return super.agentTurn(prompt, ruleId, validUntil)
            }
        }
        run(actions, """{"id":"dad-after-seven","when":{"type":"manual"},"then":[{"type":"agent_turn","prompt":"p"}]}""")
        assertEquals("dad-after-seven", seen)
    }

    @Test fun aQueuedTurnCarriesTheRulesOwnDeadline() {
        // "Post this at 19:00" that reaches the front at 23:40 is the wrong
        // action, not a late one, so the turn expires rather than waiting.
        val actions = Recorder()
        run(
            actions,
            """
            {"id":"x","when":{"type":"schedule","at":"19:00"},
             "then":[{"type":"agent_turn","prompt":"p"}],
             "guard":{"validForMinutes":20}}
            """,
        )
        assertEquals(now.toInstant().toEpochMilli() + 20 * 60_000L, actions.lastValidUntil)
    }

    @Test fun theDefaultDeadlineIsHalfAnHour() {
        val actions = Recorder()
        run(actions, """{"id":"x","when":{"type":"manual"},"then":[{"type":"agent_turn","prompt":"p"}]}""")
        assertEquals(now.toInstant().toEpochMilli() + AutomationGuard.DEFAULT_VALID_FOR_MS, actions.lastValidUntil)
    }
}
