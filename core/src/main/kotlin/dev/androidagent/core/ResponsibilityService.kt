/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.core

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persistent wrapper around rules, not another execution engine.
 * A single rule has one owner. A draft already holds it, so it cannot run before review.
 * Failure to load the store holds all automations: treating it as empty could release paused rules.
 */
class ResponsibilityService(
    private val store: ResponsibilityStore,
    private val rules: AutomationLibrary,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val initial = runCatching { recover(store.load()).also(store::save) }
    private val errors = MutableStateFlow(initial.exceptionOrNull()?.let {
        "Responsibility storage is unavailable. Automations are held; existing chats are still available."
    })
    val storageError = errors.asStateFlow()
    val loadError: String? get() = errors.value
    private val mutable = MutableStateFlow(initial.getOrDefault(ResponsibilitySnapshot()))
    val snapshot = mutable.asStateFlow()

    fun create(id: String, title: String, goal: String, ruleIds: List<String>): Responsibility = synchronized(lock) {
        check(loadError == null) { loadError.orEmpty() }
        require(ID.matches(id)) { "Use a lowercase id with letters, digits, '-' or '_'." }
        require(title.isNotBlank() && title.length <= 120) { "Give this responsibility a short title." }
        require(goal.isNotBlank() && goal.length <= 2000) { "Describe the goal in 1–2000 characters." }
        require(ruleIds.isNotEmpty() && ruleIds.size <= 16 && ruleIds.distinct().size == ruleIds.size) {
            "Choose 1–16 different existing rules."
        }
        require(mutable.value.responsibilities.size < 64) { "This phone already holds 64 responsibilities." }
        require(mutable.value.responsibilities.none { it.id == id }) { "That responsibility id already exists." }
        ruleIds.forEach { ruleId ->
            require(rules.get(ruleId) != null) { "Rule '$ruleId' does not exist." }
            require(owner(ruleId) == null) { "Rule '$ruleId' already belongs to a responsibility." }
        }
        val now = clock()
        val item = Responsibility(id, title, goal, ruleIds, createdAt = now, updatedAt = now)
        commit(mutable.value.copy(responsibilities = mutable.value.responsibilities + item))
        item
    }

    fun get(id: String): Responsibility? = snapshot.value.responsibilities.firstOrNull { it.id == id }
    fun owner(ruleId: String): Responsibility? = snapshot.value.responsibilities.firstOrNull { ruleId in it.ruleIds }

    /** Called by the user's review screen; never exposed as an agent tool. */
    fun activate(id: String): Responsibility = change(id) { item ->
        require(item.state != ResponsibilityState.COMPLETED) { "This responsibility is completed." }
        require(item.state != ResponsibilityState.NEEDS_ATTENTION) { "Review the unknown result first." }
        val reviewed = item.ruleIds.associateWith { ruleId ->
            val rule = rules.get(ruleId) ?: error("Rule '$ruleId' no longer exists.")
            require(rule.enabled) { "Enable rule '$ruleId' first." }
            require(rule.actions.none { it.kind == AutomationActionKind.VOICE_CALL }) {
                "Voice rules cannot be attached to responsibilities yet."
            }
            fingerprint(rule)
        }
        item.copy(state = ResponsibilityState.ACTIVE, reviewedRules = reviewed)
    }

    fun pause(id: String): Responsibility = change(id) { item ->
        require(item.state != ResponsibilityState.COMPLETED) { "This responsibility is completed." }
        require(item.state != ResponsibilityState.NEEDS_ATTENTION) { "Review the unknown result first." }
        item.copy(state = ResponsibilityState.PAUSED)
    }

    fun complete(id: String): Responsibility = change(id) { item ->
        require(item.state != ResponsibilityState.NEEDS_ATTENTION) { "Review the unknown result first." }
        item.copy(state = ResponsibilityState.COMPLETED)
    }

    /** Acknowledging an uncertain result leaves execution paused, never retries it. */
    fun acknowledge(id: String): Responsibility = synchronized(lock) {
        check(loadError == null) { loadError.orEmpty() }
        val item = get(id) ?: error("That responsibility no longer exists.")
        require(item.state == ResponsibilityState.NEEDS_ATTENTION) { "There is no unknown result to review." }
        val updated = item.copy(state = ResponsibilityState.PAUSED, version = item.version + 1, updatedAt = clock())
        commit(mutable.value.copy(
            responsibilities = mutable.value.responsibilities.map { if (it.id == id) updated else it },
            activity = mutable.value.activity.map {
                if (it.responsibilityId == id && it.status == ResponsibilityActivityStatus.UNKNOWN) it.copy(status = ResponsibilityActivityStatus.REVIEWED) else it
            },
        ))
        updated
    }

    fun saveNotes(id: String, value: String): Responsibility = change(id) { item ->
        require(value.length <= 2000) { "Notes can hold up to 2000 characters." }
        item.copy(notes = value)
    }

    /** Local Stop holds unattended responsibilities as well as the existing turn queue. */
    fun pauseActive() = synchronized(lock) {
        check(loadError == null) { loadError.orEmpty() }
        val now = clock()
        val items = mutable.value.responsibilities.map {
            if (it.state == ResponsibilityState.ACTIVE) it.copy(state = ResponsibilityState.PAUSED, version = it.version + 1, updatedAt = now) else it
        }
        if (items != mutable.value.responsibilities) commit(mutable.value.copy(responsibilities = items))
    }

    fun permit(ruleId: String): ResponsibilityPermit? = owner(ruleId)?.let { ResponsibilityPermit(it.id, it.version) }

    /** Checked at event matching, after approval, at queue dispatch and at every nested tool call. */
    fun check(ruleId: String, permit: ResponsibilityPermit?) {
        synchronized(lock) {
            check(loadError == null) { loadError.orEmpty() }
            val owner = owner(ruleId)
            if (permit == null) {
                check(owner == null) { "This rule now belongs to a responsibility; the old work is stale." }
                check(rules.get(ruleId)?.enabled == true) { "The rule no longer exists or is off." }
                return
            }
            check(owner != null && owner.id == permit.id && owner.version == permit.version) {
                "The responsibility changed; the old work is stale."
            }
            check(owner.state == ResponsibilityState.ACTIVE) { "The responsibility is not active." }
            val rule = rules.get(ruleId)
            check(rule != null && rule.enabled && owner.reviewedRules[ruleId] == fingerprint(rule)) {
                "The rule changed or is off. Review and activate the responsibility again."
            }
        }
    }

    fun runnable(all: List<AutomationRule>): List<AutomationRule> = all.filter { rule ->
        runCatching { check(rule.id, permit(rule.id)) }.isSuccess
    }

    suspend fun run(
        fired: AutomationEvaluator.Outcome.Fired,
        runner: AutomationRunner,
    ): AutomationRunReport {
        val permit = permit(fired.rule.id)
        val guard = { check(fired.rule.id, permit) }
        guard()
        if (permit == null) return runner.run(fired, check = guard)
        val activity = ResponsibilityActivity(
            id = UUID.randomUUID().toString(), responsibilityId = permit.id, ruleId = fired.rule.id,
            at = clock(), status = ResponsibilityActivityStatus.STARTED,
            totalActions = fired.actions.size, actionKinds = fired.actions.map { it.kind.wire },
        )
        synchronized(lock) {
            guard()
            commit(mutable.value.copy(activity = trim(mutable.value.activity + activity)))
        }
        try {
            val report = runner.run(fired, permit, guard) { count ->
                updateActivity(activity.id) { it.copy(completedActions = count) }
            }
            updateActivity(activity.id) { it.copy(
                status = when {
                    !report.ok && report.committed -> ResponsibilityActivityStatus.UNKNOWN
                    !report.ok -> ResponsibilityActivityStatus.FAILED
                    fired.actions.any { action -> action.kind == AutomationActionKind.AGENT_TURN || action.kind == AutomationActionKind.VOICE_CALL } ->
                        ResponsibilityActivityStatus.DISPATCHED
                    else -> ResponsibilityActivityStatus.SUCCEEDED
                },
                completedActions = report.completed.size,
            ) }
            if (!report.ok && report.committed) needsAttention(permit.id)
            return report
        } catch (failure: Exception) {
            // Cancellation may follow a side effect. Do not claim that nothing ran.
            updateActivity(activity.id) { it.copy(status = ResponsibilityActivityStatus.UNKNOWN) }
            needsAttention(permit.id)
            throw failure
        }
    }

    private fun needsAttention(id: String) = change(id) { it.copy(state = ResponsibilityState.NEEDS_ATTENTION) }

    private fun updateActivity(id: String, edit: (ResponsibilityActivity) -> ResponsibilityActivity) = synchronized(lock) {
        commit(mutable.value.copy(activity = mutable.value.activity.map { if (it.id == id) edit(it) else it }))
    }

    private fun change(id: String, edit: (Responsibility) -> Responsibility): Responsibility = synchronized(lock) {
        check(loadError == null) { loadError.orEmpty() }
        val old = get(id) ?: error("That responsibility no longer exists.")
        val item = edit(old).copy(version = old.version + 1, updatedAt = clock())
        commit(mutable.value.copy(responsibilities = mutable.value.responsibilities.map { if (it.id == id) item else it }))
        item
    }

    private fun commit(value: ResponsibilitySnapshot) {
        validate(value)
        try {
            store.save(value)
        } catch (failure: Exception) {
            errors.value = "Cannot save responsibility state. Automations are held; existing chats are still available."
            throw failure
        }
        mutable.value = value
    }

    companion object {
        val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        const val MAX_ACTIVITY = 512
        private fun fingerprint(rule: AutomationRule): String =
            MessageDigest.getInstance("SHA-256").digest(rule.toJson().toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun trim(activity: List<ResponsibilityActivity>): List<ResponsibilityActivity> {
            // Never discard an unresolved run to make room for ordinary history.
            val unresolved = activity.filter { it.status == ResponsibilityActivityStatus.STARTED || it.status == ResponsibilityActivityStatus.UNKNOWN }
            require(unresolved.size < MAX_ACTIVITY) { "Review the unresolved activity before adding more." }
            val retained = activity.filterNot { it in unresolved }.takeLast(MAX_ACTIVITY - unresolved.size)
            return (unresolved + retained).sortedBy { it.at }
        }

        private fun recover(value: ResponsibilitySnapshot): ResponsibilitySnapshot {
            validate(value)
            val interrupted = value.activity.filter { it.status == ResponsibilityActivityStatus.STARTED }.map { it.responsibilityId }.toSet()
            return value.copy(
                activity = value.activity.map { if (it.status == ResponsibilityActivityStatus.STARTED) it.copy(status = ResponsibilityActivityStatus.UNKNOWN) else it },
                responsibilities = value.responsibilities.map { if (it.id in interrupted) it.copy(state = ResponsibilityState.NEEDS_ATTENTION, version = it.version + 1) else it },
            )
        }

        fun validate(value: ResponsibilitySnapshot) {
            require(value.schemaVersion == 1) { "Unsupported responsibility store version." }
            require(value.responsibilities.size <= 64 && value.activity.size <= MAX_ACTIVITY) { "Responsibility store is too large." }
            require(value.responsibilities.map { it.id }.distinct().size == value.responsibilities.size) { "Duplicate responsibility ids." }
            val ids = value.responsibilities.map { it.id }.toSet()
            val bindings = value.responsibilities.flatMap { it.ruleIds }
            require(bindings.distinct().size == bindings.size) { "A rule cannot have two owners." }
            value.responsibilities.forEach {
                require(ID.matches(it.id) && it.version > 0 && it.title.isNotBlank() && it.title.length <= 120 &&
                    it.goal.isNotBlank() && it.goal.length <= 2000 && it.notes.length <= 2000 &&
                    it.ruleIds.isNotEmpty() && it.ruleIds.size <= 16 && it.ruleIds.all(ID::matches)) { "Invalid responsibility." }
            }
            require(value.activity.all { it.responsibilityId in ids && it.completedActions in 0..it.totalActions &&
                it.totalActions in 1..4 && it.actionKinds.size == it.totalActions }) { "Invalid responsibility activity." }
        }
    }
}
