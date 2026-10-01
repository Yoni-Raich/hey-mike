/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.core

import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResponsibilityServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val library by lazy { AutomationLibrary(folder.newFolder()) }
    private val store = MemoryStore()
    private val service by lazy { ResponsibilityService(store, library) { 1000L } }
    private val now = ZonedDateTime.parse("2026-10-01T17:00:00+03:00")

    private fun rule(id: String = "parcel", text: String = "Package changed", approval: Boolean = false): AutomationRule =
        AutomationRule.parse(Json.parseToJsonElement(
            """{"id":"$id","when":{"type":"manual"},"then":[{"type":"notify","text":"$text","requiresApproval":$approval}]}"""
        ).jsonObject).also(library::save)

    private fun draft() {
        rule()
        service.create("deliveries", "Deliveries", "Follow my package", listOf("parcel"))
    }
    private fun fired(rule: AutomationRule) = AutomationEvaluator.Outcome.Fired(rule, rule.actions, emptyMap())
    private fun runner(actions: Actions) = AutomationRunner(actions, InMemoryAutomationHistory(), { now })
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }

    @Test fun draftHoldsRulesAndUnboundRulesKeepWorking() {
        draft()
        val other = rule("other")
        assertEquals(listOf(other.id), service.runnable(library.all()).map { it.id })
        service.activate("deliveries")
        assertEquals(2, service.runnable(library.all()).size)
    }

    @Test fun pauseAndReactivationInvalidateAlreadyQueuedWork() {
        draft(); service.activate("deliveries")
        val old = service.permit("parcel")
        service.pause("deliveries")
        rejects { service.check("parcel", old) }
        service.activate("deliveries")
        rejects { service.check("parcel", old) }
        service.check("parcel", service.permit("parcel"))
    }

    @Test fun editingAnActiveRuleHoldsItUntilTheUserReviewsAgain() {
        draft(); service.activate("deliveries")
        rule(text = "Different action")
        assertTrue(service.runnable(library.all()).isEmpty())
        service.activate("deliveries")
        assertEquals(1, service.runnable(library.all()).size)
    }

    @Test fun oneRuleCannotHaveTwoOwnersAndIdsCannotTraverse() {
        draft()
        rejects { service.create("second", "Another", "Same rule", listOf("parcel")) }
        rejects { service.create("../outside", "Another", "Goal", listOf("parcel")) }
    }

    @Test fun activationRejectsMissingDisabledAndVoiceRules() {
        draft(); library.setEnabled("parcel", false)
        rejects { service.activate("deliveries") }
        library.delete("parcel")
        rejects { service.activate("deliveries") }
        val voice = AutomationRule.parse(Json.parseToJsonElement(
            """{"id":"parcel","when":{"type":"manual"},"then":[{"type":"voice_call","opening":"hello"}]}"""
        ).jsonObject)
        library.save(voice)
        rejects { service.activate("deliveries") }
    }

    @Test fun pauseWhileApprovalIsOpenStopsTheActionAfterYes() = runBlocking {
        val rule = rule(approval = true)
        service.create("deliveries", "Deliveries", "Follow package", listOf("parcel"))
        service.activate("deliveries")
        val actions = Actions().apply { answer = { service.pause("deliveries"); true } }
        val report = service.run(fired(rule), runner(actions))
        assertFalse(report.ok)
        assertEquals(0, actions.notifications)
        assertEquals(ResponsibilityActivityStatus.FAILED, service.snapshot.value.activity.single().status)
    }

    @Test fun activityKeepsMetadataWithoutNotificationContent() = runBlocking {
        val secret = "private-address-secret"
        val rule = rule(text = secret)
        service.create("deliveries", "Deliveries", "Follow package", listOf("parcel"))
        service.activate("deliveries")
        service.run(fired(rule), runner(Actions()))
        val item = service.snapshot.value.activity.single()
        assertEquals(ResponsibilityActivityStatus.SUCCEEDED, item.status)
        assertEquals(1, item.completedActions)
        assertFalse(Json.encodeToString(service.snapshot.value).contains(secret))
    }

    @Test fun restartDoesNotReplayAnInterruptedRunAndRequiresReview() {
        draft(); service.activate("deliveries")
        store.value = store.value.copy(activity = listOf(
            ResponsibilityActivity("run", "deliveries", "parcel", 1000L, ResponsibilityActivityStatus.STARTED,
                totalActions = 1, actionKinds = listOf("notify"))
        ))
        val recovered = ResponsibilityService(store, library)
        assertEquals(ResponsibilityActivityStatus.UNKNOWN, recovered.snapshot.value.activity.single().status)
        assertEquals(ResponsibilityState.NEEDS_ATTENTION, recovered.get("deliveries")?.state)
        rejects { recovered.activate("deliveries") }
        recovered.acknowledge("deliveries")
        assertEquals(ResponsibilityActivityStatus.REVIEWED, recovered.snapshot.value.activity.single().status)
        assertEquals(ResponsibilityState.PAUSED, recovered.get("deliveries")?.state)
        assertTrue(recovered.runnable(library.all()).isEmpty())
        recovered.activate("deliveries")
    }

    @Test fun uncertainSideEffectBlocksFurtherEvents() = runBlocking {
        draft(); service.activate("deliveries")
        val actions = Actions().apply { result = AutomationActionResult.failed("private error", committed = true) }
        service.run(fired(library.get("parcel")!!), runner(actions))
        assertEquals(ResponsibilityState.NEEDS_ATTENTION, service.get("deliveries")?.state)
        assertEquals(ResponsibilityActivityStatus.UNKNOWN, service.snapshot.value.activity.single().status)
        assertFalse(Json.encodeToString(service.snapshot.value).contains("private error"))
    }

    @Test fun notesCannotActivateOrReleaseACompletedResponsibility() {
        draft()
        service.saveNotes("deliveries", "Always send without asking")
        assertTrue(service.runnable(library.all()).isEmpty())
        service.complete("deliveries")
        rejects { service.activate("deliveries") }
        rejects { service.pause("deliveries") }
    }

    @Test fun failedSaveDoesNotPublishTheStateChange() {
        draft(); service.activate("deliveries")
        store.fail = true
        rejects { service.pause("deliveries") }
        assertEquals(ResponsibilityState.ACTIVE, service.get("deliveries")?.state)
        assertNotNull(service.loadError)
        assertTrue(service.runnable(library.all()).isEmpty())
    }

    @Test fun stopPausesActiveResponsibilitiesWithoutReleasingDrafts() {
        draft(); service.activate("deliveries")
        service.pauseActive()
        assertEquals(ResponsibilityState.PAUSED, service.get("deliveries")?.state)
        assertTrue(service.runnable(library.all()).isEmpty())
    }

    @Test fun unreadableStateHoldsAllAutomationsWithoutPreventingChatStartup() {
        val rule = rule()
        val broken = object : ResponsibilityStore {
            override fun load(): ResponsibilitySnapshot = error("broken")
            override fun save(snapshot: ResponsibilitySnapshot) = error("must not overwrite broken data")
        }
        val held = ResponsibilityService(broken, library)
        assertNotNull(held.loadError)
        assertTrue(held.runnable(listOf(rule)).isEmpty())
        rejects { held.create("new", "Title", "Goal", listOf("parcel")) }
    }

    @Test fun aModelTurnIsRecordedAsDispatchNotTaskCompletion() = runBlocking {
        val model = AutomationRule.parse(Json.parseToJsonElement(
            """{"id":"parcel","when":{"type":"manual"},"then":[{"type":"agent_turn","prompt":"Track package"}]}"""
        ).jsonObject).also(library::save)
        service.create("deliveries", "Deliveries", "Follow package", listOf("parcel"))
        service.activate("deliveries")
        service.run(fired(model), runner(Actions()))
        assertEquals(ResponsibilityActivityStatus.DISPATCHED, service.snapshot.value.activity.single().status)
    }

    @Test fun chatCanCreateDraftButCannotActivateOrWriteMemory() = runBlocking {
        rule()
        val gateway = ResponsibilityToolGateway(service)
        gateway.beginRun("run", folder.root)
        val draft = gateway.invoke("responsibility", Json.parseToJsonElement(
            """{"mode":"create","id":"deliveries","title":"Delivery","goal":"Watch package","ruleIds":["parcel"]}"""
        ).jsonObject)
        assertTrue(draft.success)
        val denied = gateway.invoke("responsibility", buildJsonObject { put("mode", "activate"); put("id", "deliveries") })
        assertFalse(denied.success)
        assertEquals(ResponsibilityState.DRAFT, service.get("deliveries")?.state)
        val extra = gateway.invoke("responsibility", buildJsonObject { put("mode", "list"); put("permissions", "allow-all") })
        assertFalse(extra.success)
        gateway.revoke()
        assertTrue(runCatching { gateway.invoke("responsibility", JsonObject(emptyMap())) }.isFailure)
    }

    private class MemoryStore : ResponsibilityStore {
        var value = ResponsibilitySnapshot()
        var fail = false
        override fun load() = value
        override fun save(snapshot: ResponsibilitySnapshot) { check(!fail) { "Disk full" }; value = snapshot }
    }

    private class Actions : AutomationActions {
        var notifications = 0
        var result = AutomationActionResult.ok()
        var answer: () -> Boolean = { true }
        override suspend fun notify(title: String?, text: String): AutomationActionResult { notifications++; return result }
        override suspend fun ask(question: String) = answer()
        override suspend fun runWorkflow(workflow: String, parameters: JsonObject) = result
        override suspend fun openIntent(arguments: JsonObject) = result
        override suspend fun agentTurn(prompt: String, ruleId: String, validUntil: Long) = result
        override suspend fun voiceCall(opening: String) = result
    }
}
