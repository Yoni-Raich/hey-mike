/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.a11y

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import dev.androidagent.core.IntentExtra
import dev.androidagent.core.IntentExtras
import dev.androidagent.core.IntentPolicy
import dev.androidagent.core.LocalIntentRequest
import dev.androidagent.core.ToolNotServiceable
import dev.androidagent.core.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reaching a destination directly instead of walking there through the UI.
 *
 * Every task used to start at a launcher screen and navigate, even when a
 * single intent would land exactly on the target. That is slower, more
 * fragile, and spends model turns on navigation that carries no decision.
 *
 * Both tools run in-process, so they work with no ADB connection. Neither
 * accepts an explicit component from the model: [IntentPolicy] rejects the
 * `intent:` scheme for the same reason, since a named component would route
 * around every check.
 */
internal class IntentTools(
    private val context: Context,
    /** Max apps reported by a resolve, so a broad action cannot flood the reply. */
    private val maxMatches: Int = 20,
    private val authorizeIntent: suspend (LocalIntentRequest, () -> ToolResult) -> ToolResult,
) {

    /**
     * Read-only. Reports which apps would handle an intent, so the model can
     * check before committing to something with a side effect.
     */
    fun resolve(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")
        val uri = arguments.string("uri")
        return when (val decision = IntentPolicy.evaluate(action, uri)) {
            is IntentPolicy.Decision.Deny -> denied(decision)
            is IntentPolicy.Decision.NeedsConfirmation -> resolveAllowed(decision.action, decision.uri)
            is IntentPolicy.Decision.Allow -> resolveAllowed(decision.action, decision.uri)
        }
    }

    /**
     * Launches. A sensitive intent is suspended on an app-owned approval gate;
     * model arguments can neither grant nor reuse that approval.
     */
    suspend fun open(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")
        val pkg = arguments.string("package")
        if (pkg != null) require(PACKAGE_RE.matches(pkg)) { "package is not a valid Android package name" }
        val extras = when (val parsed = IntentExtras.parse(arguments["extras"])) {
            is IntentExtras.Parsed.Invalid -> return denied(IntentPolicy.Decision.Deny(parsed.reason, parsed.message))
            is IntentExtras.Parsed.Ok -> parsed.extras
        }
        // A prefilled body is composed into the uri before the policy runs, so
        // it is checked as the payload it is rather than slipping past as an
        // argument the policy never sees.
        val uri = when (val composed = IntentPolicy.withText(arguments.string("uri"), arguments.string("text"))) {
            is IntentPolicy.Decision.Deny -> return denied(composed)
            is IntentPolicy.Decision.Allow -> composed.uri
            is IntentPolicy.Decision.NeedsConfirmation -> composed.uri
        }
        return when (val decision = IntentPolicy.evaluate(action, uri, extras)) {
            is IntentPolicy.Decision.Deny -> denied(decision)
            is IntentPolicy.Decision.NeedsConfirmation -> authorizeIntent(
                LocalIntentRequest(
                    decision.action,
                    decision.uri,
                    pkg,
                    // What the extras say is part of what is being approved.
                    if (extras.isEmpty()) decision.what else "${decision.what} (${IntentExtras.describe(extras)})",
                ),
            ) { launch(IntentPolicy.Decision.Allow(decision.uri, decision.action), pkg, extras) }
            is IntentPolicy.Decision.Allow -> launch(decision, pkg, extras)
        }
    }

    private fun resolveAllowed(action: String, uri: String?): ToolResult {
        val intent = build(action, uri)
        val matches = query(intent)
        return ToolResult(
            buildJsonObject {
                put("ok", true)
                put("action", action)
                uri?.let { put("uri", it) }
                put("resolves", matches.isNotEmpty())
                put("handlers", JsonArray(matches.map { it.toJson() }))
                if (matches.isEmpty()) {
                    put(
                        "hint",
                        "Nothing on this device handles that intent. Either the app is not installed, " +
                            "or it is not visible to this app. Fall back to open_app and UI navigation.",
                    )
                }
            }.toString(),
        )
    }

    private fun launch(
        decision: IntentPolicy.Decision.Allow,
        pkg: String?,
        extras: Map<String, IntentExtra>,
    ): ToolResult {
        val intent = build(decision.action, decision.uri)
        for ((key, value) in extras) {
            when (value) {
                is IntentExtra.Text -> intent.putExtra(key, value.value)
                is IntentExtra.Int32 -> intent.putExtra(key, value.value)
                is IntentExtra.Int64 -> intent.putExtra(key, value.value)
                is IntentExtra.Real -> intent.putExtra(key, value.value)
                is IntentExtra.Flag -> intent.putExtra(key, value.value)
                is IntentExtra.TextList -> intent.putExtra(key, value.values.toTypedArray())
            }
        }
        if (pkg != null) {
            // A package hint narrows an ambiguous link to one app. It is not a
            // component: the app still picks its own entry point.
            intent.setPackage(pkg)
        }
        if (query(intent).isEmpty()) {
            throw ToolNotServiceable(
                "no_handler",
                "Nothing on this device handles ${decision.action}" +
                    (decision.uri?.let { " $it" } ?: "") +
                    (pkg?.let { " in $it" } ?: "") + ".",
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (extras.isNotEmpty()) {
            // Many apps read extras only when the activity is created. On a
            // Xiaomi phone SET_TIMER with LENGTH started a timer from a cold
            // clock and did nothing when the clock's task already existed: the
            // task was brought to the front and the extras were dropped. A new
            // task instance is what makes the extras arrive.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("launched", decision.action)
                    decision.uri?.let { put("uri", it) }
                    put(
                        "note",
                        "The intent was dispatched. Confirm with read_ui that the expected " +
                            "screen actually opened before acting on it.",
                    )
                }.toString(),
            )
        } catch (error: SecurityException) {
            ToolResult(
                buildJsonObject {
                    put("ok", false)
                    put("errorType", "launch_denied")
                    put("message", error.message ?: "The system refused the launch.")
                    put(
                        "remedy",
                        "Background activity launches are restricted from Android 10. This app " +
                            "is exempt while it holds Display over other apps (SYSTEM_ALERT_WINDOW) " +
                            "for the floating card. Ask the user to confirm that permission is " +
                            "still granted.",
                    )
                }.toString(),
                success = false,
            )
        } catch (_: ActivityNotFoundException) {
            throw ToolNotServiceable(
                "no_handler",
                "The app that handled this intent is no longer available. Resolve it again or " +
                    "fall back to open_app and UI navigation.",
            )
        }
    }

    /**
     * `FLAG_GRANT_*_URI_PERMISSION` is never set. Handing another app a
     * permission on our data is not something an intent built from model text
     * gets to do.
     */
    private fun build(action: String, uri: String?): Intent =
        if (uri == null) Intent(action) else Intent(action, Uri.parse(uri))

    private fun query(intent: Intent): List<ResolveInfo> =
        runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList()).take(maxMatches)

    private fun ResolveInfo.toJson(): JsonObject = buildJsonObject {
        put("package", activityInfo?.packageName ?: "unknown")
        // The label is what the user sees in a chooser, which is what makes
        // the reply legible when several apps claim the same link.
        runCatching { loadLabel(context.packageManager)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { put("label", it) }
        put("default", isDefault)
    }

    private fun denied(decision: IntentPolicy.Decision.Deny): ToolResult = ToolResult(
        buildJsonObject {
            put("ok", false)
            put("errorType", decision.reason)
            put("message", decision.message)
        }.toString(),
        success = false,
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private val PACKAGE_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        val DEFINITIONS_SPEC: List<IntentToolSpec> = listOf(
            IntentToolSpec(
                name = "resolve_intent",
                description = "Check which installed apps would handle an action and/or uri, without " +
                    "launching anything. Read-only. Use it before open_intent when you are not sure " +
                    "the deep link is supported, instead of launching and hoping.",
                properties = mapOf("action" to "string", "uri" to "string"),
                required = emptyList(),
            ),
            IntentToolSpec(
                name = "open_intent",
                description = "Open a destination or run an Android action directly by intent instead of " +
                    "navigating there through the UI — a maps route, a specific chat, a settings screen " +
                    "(action android.settings.WIRELESS_SETTINGS, no uri), a timer (action " +
                    "android.intent.action.SET_TIMER with extras). Prefer this over open_app plus taps " +
                    "when an intent reaches the target. extras is an object of extra name to value: a " +
                    "string, boolean, integer (sent as int when it fits), decimal, list of strings, or " +
                    "{\"type\":\"long\",\"value\":...} when the receiver reads another type. Only plain " +
                    "values: no uri grants, no component. Pass a prefilled message body " +
                    "as text rather than building \"?text=\" into the uri yourself; it is encoded for you, " +
                    "and an unencoded space or & in a hand-built uri truncates the message or fails to " +
                    "parse. Anything that sends on the user's behalf — a prefilled message, a payment — " +
                    "pauses for an approval the user must answer in the Hey Mike app, so the call " +
                    "does not return until they do. Say that you are waiting before you call it. Always " +
                    "verify with read_ui that the expected screen opened.",
                properties = mapOf(
                    "action" to "string",
                    "uri" to "string",
                    "package" to "string",
                    "text" to "string",
                    "extras" to "object",
                ),
                required = emptyList(),
            ),
        )
    }
}

/** Schema for one intent tool, rendered by the gateway's own `tool` helper. */
internal data class IntentToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, String>,
    val required: List<String>,
)
