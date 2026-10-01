/*
 * Hey Mike - Copyright (C) 2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package dev.androidagent.core

import kotlinx.serialization.Serializable

/** Persistent ownership of existing rules. This does not grant any tool permission. */
@Serializable
data class Responsibility(
    val id: String,
    val title: String,
    val goal: String,
    val ruleIds: List<String>,
    val state: ResponsibilityState = ResponsibilityState.DRAFT,
    val version: Long = 1,
    val createdAt: Long,
    val updatedAt: Long,
    /** Only the user's editor writes these notes. No inferred memory in this version. */
    val notes: String = "",
    /** Definitions reviewed when activated; editing a rule requires another activation. */
    val reviewedRules: Map<String, String> = emptyMap(),
)

@Serializable
enum class ResponsibilityState { DRAFT, ACTIVE, PAUSED, NEEDS_ATTENTION, COMPLETED }

@Serializable
data class ResponsibilityPermit(val id: String, val version: Long)

@Serializable
enum class ResponsibilityActivityStatus { STARTED, SUCCEEDED, DISPATCHED, FAILED, UNKNOWN, REVIEWED }

/** Metadata only: no notification bodies, model responses, tool arguments or error payloads. */
@Serializable
data class ResponsibilityActivity(
    val id: String,
    val responsibilityId: String,
    val ruleId: String,
    val at: Long,
    val status: ResponsibilityActivityStatus,
    val completedActions: Int = 0,
    val totalActions: Int,
    val actionKinds: List<String>,
)

@Serializable
data class ResponsibilitySnapshot(
    val schemaVersion: Int = 1,
    val responsibilities: List<Responsibility> = emptyList(),
    val activity: List<ResponsibilityActivity> = emptyList(),
)

/** Save replaces the whole snapshot atomically or throws, leaving the old one intact. */
interface ResponsibilityStore {
    fun load(): ResponsibilitySnapshot
    fun save(snapshot: ResponsibilitySnapshot)
}
