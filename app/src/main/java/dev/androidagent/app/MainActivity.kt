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

package dev.androidagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.androidagent.app.assist.AssistLaunch
import dev.androidagent.app.ui.*
import dev.androidagent.core.KeepAwakePolicy

class MainActivity : ComponentActivity() {
    private val model: AgentViewModel by viewModels()
    private var askedForNotifications = false
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::addAttachment) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshPermissions() }
    private val capabilityPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        model.graph.runtimePermissions.complete(result)
        model.refreshPermissions()
    }
    // Set while the permission dialog is up for an assistant press, so the
    // grant starts voice the assistant way rather than toggling it.
    private var voiceForAssistant = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshPermissions()
        val forAssistant = voiceForAssistant.also { voiceForAssistant = false }
        if (granted) { ensureService(); if (forAssistant) model.startAssistantVoice() else model.toggleVoice() }
        else model.error("Microphone permission is required for voice.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.graph.runtimePermissions.attach(this, capabilityPermissions)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK))
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            // The visible activity uses a window flag only. The background
            // foreground service owns the screen wake lock while active, so
            // the user's display timeout and system settings are never written.
            val keepAwake = KeepAwakePolicy.shouldKeepAwake(state.runState, state.voiceState)
            LaunchedEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            DisposableEffect(Unit) {
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            // Handed down as a reader so the sphere redraws on every level
            // change without recomposing the screen.
            val voiceLevel = model.graph.voice.level.collectAsStateWithLifecycle()
            val readVoiceLevel = remember(voiceLevel) { { voiceLevel.value } }
            AndroidAgentScreen(state, actions(), voiceLevel = readVoiceLevel)
        }
        ensureService()
        model.prepare()
        // Not on recreation: a rotation must not reopen a conversation the
        // user already ended.
        val fromAssistant = savedInstanceState == null && handleAssistantPress(intent)
        val fromCapabilityRequest = intent.getBooleanExtra(RuntimePermissionBroker.EXTRA_CAPABILITY_PERMISSION_REQUEST, false)
        intent.removeExtra(RuntimePermissionBroker.EXTRA_CAPABILITY_PERMISSION_REQUEST)
        // First launch asks for this on its own screen, with a reason; asking
        // at launch put a system dialog in front of the welcome.
        val onboarded = model.ui.value.onboarding.finished
        if (onboarded && !fromAssistant && !fromCapabilityRequest && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAssistantPress(intent)
    }

    /**
     * The power button was held with Mike as the digital assistant: close
     * whatever sheet is open and go straight into a live voice conversation.
     * Returns whether the intent was an assistant press.
     */
    private fun handleAssistantPress(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(AssistLaunch.EXTRA_START_VOICE, false) != true) return false
        // Reopening from Recents replays the press that created the task.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        intent.removeExtra(AssistLaunch.EXTRA_START_VOICE)
        model.editUi { it.copy(isSettingsOpen = false, isDrawerOpen = false, isWorkspaceOpen = false) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensureService()
            model.startAssistantVoice()
        } else {
            voiceForAssistant = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        return true
    }
    override fun onStart() {
        super.onStart()
        // The app owns the foreground surface. Keep the run state in the
        // overlay, but remove its window until another app is visible.
        model.graph.overlay.setAppForeground(true)
    }
    override fun onStop() {
        model.graph.overlay.setAppForeground(false)
        super.onStop()
    }
    // Every grant the checklist tracks is flipped in a system Settings screen,
    // so the app is always stopped and resumed around the change.
    override fun onResume() {
        super.onResume()
        model.graph.runtimePermissions.resumed(this)
        model.graph.foregroundActivity = java.lang.ref.WeakReference(this)
        model.refreshAccount(); model.refreshPermissions(); model.refreshAssistantRole(); model.refreshAutomations()
    }
    override fun onPause() {
        model.graph.runtimePermissions.paused(this)
        if (model.graph.foregroundActivity?.get() === this) model.graph.foregroundActivity = null
        super.onPause()
    }
    override fun onDestroy() {
        model.graph.runtimePermissions.detach(this)
        super.onDestroy()
    }
    private fun ensureService() { runCatching { ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java)) }.onFailure { model.error("Could not start the agent service: ${it.message}") } }
    private fun actions() = AgentUiActions(
        onDrawerChanged = { open -> model.editUi { it.copy(isDrawerOpen = open) } },
        onNewChat = { model.newChat() },
        onSelectSession = model::select,
        onAttach = { filePicker.launch(arrayOf("*/*")) },
        onRemoveAttachment = model::removeAttachment,
        onSend = { text, attachments -> ensureService(); model.send(text, attachments) },
        onSteer = { model.graph.coordinator.steer(it) },
        onStop = model::stop,
        onCancelQueued = model::cancelQueued,
        onResumeQueue = { model.resumeQueue() },
        onVoiceToggle = ::toggleVoice,
        onVoiceMuteToggle = model::toggleVoiceMute,
        onOpenSettings = { model.editUi { it.copy(isSettingsOpen = true) } },
        onCloseSettings = { model.editUi { it.copy(isSettingsOpen = false) } },
        onOpenAutomations = {
            // The strip is a snapshot from the last resume; re-read before
            // showing the list, so a rule turned on elsewhere is already there.
            model.refreshAutomations()
            model.editUi { it.copy(isAutomationsOpen = true) }
        },
        onCloseAutomations = { model.editUi { it.copy(isAutomationsOpen = false) } },
        onToggleRule = { id, enabled -> model.setRuleEnabled(id, enabled) },
        onRunRule = { id -> ensureService(); model.runRule(id) },
        onDeleteRule = { id -> model.deleteRule(id) },
        ruleEditFields = { id -> model.ruleEditFields(id) },
        onSaveRule = { id, values -> model.saveRuleEdits(id, values) },
        onPrepareRuntime = { ensureService(); model.prepare() },
        onLogin = { ensureService(); model.login() },
        onLogout = { model.logout() },
        onAddAccount = { ensureService(); model.addAccount() },
        onSwitchAccount = { id -> ensureService(); model.switchAccount(id) },
        onRemoveAccount = { id -> model.removeAccount(id) },
        onRefreshAccount = { model.refreshAccount() },
        onOpenWirelessSettings = ::openWirelessDebugging,
        onOpenAccessibilitySettings = { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onOpenAssistantSettings = {
            if (AssistLaunch.settingsIntents().none { openSettings(it, report = false) }) {
                model.error("This phone has no assistant settings screen to open. Look under Default apps.")
            }
        },
        onOpenAppInfo = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
        // Notification access has no per-app screen on most builds: the list is
        // the only way in, and no app can grant it to itself.
        onOpenNotificationAccess = {
            openSettings(dev.androidagent.automations.AutomationNotificationListener.settingsIntent())
        },
        onOpenExactAlarmSettings = {
            val exact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            if (!openSettings(exact, report = false)) {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        },
        onOpenOverlayPermission = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
        onDisconnect = { model.disconnect() },
        onForgetPairing = { model.forgetPairing() },
        onPair = { code, port -> model.pair(code, port) },
        onConnect = { model.connect(it) },
        onDiscover = { model.discover() },
        onModelSelected = model::model,
        onReasoningEffortSelected = model::reasoningEffort,
        onRenameSession = { id, title -> model.rename(id, title) },
        onCompact = { ensureService(); model.compact() },
        onTogglePlanMode = model::togglePlanMode,
        onShowStatus = model::showStatus,
        onDeleteSession = { model.delete(it) },
        onRetry = { model.prepare() },
        onDismissError = { model.editUi { it.copy(errorMessage = null) } },
        onOpenWorkspaceFiles = { model.editUi { it.copy(isWorkspaceOpen = true) }; model.listFiles() },
        onOpenWorkspaceFile = { item -> openFile(item) },
        onShareWorkspaceFile = { item -> shareFile(item) },
        onCloseWorkspaceFiles = { model.editUi { it.copy(isWorkspaceOpen = false) } },
        onApproval = { requestId, allow -> model.graph.coordinator.approve(requestId, allow) },
        onApproveAlways = { requestId, scope ->
            val before = model.graph.sendGrants.grants.value.size
            model.graph.coordinator.approve(requestId, true, scope)
            // Say so either way: a standing permission that silently did not
            // take is how the demo run kept asking.
            val saved = model.graph.sendGrants.grants.value.size > before
            model.editUi { it.copy(infoMessage = if (saved) "Saved. Mike won't ask again for these sends." else "Sent once. Nothing was saved to always allow.") }
        },
        onRemoveSendGrant = { grant -> model.graph.sendGrants.remove(grant) },
        onCheckForUpdates = { model.checkForUpdates(manual = true) },
        onDownloadUpdate = { model.downloadUpdate() },
        onInstallUpdate = { model.installUpdate() },
        onDismissUpdateBanner = { model.dismissUpdateBanner() },
        onOpenInstallPermission = { model.openInstallPermission() },
        onOpenNotificationSettings = ::requestNotifications,
        // Arm the reader before the dialog can appear, then hand the user over.
        onCapturePairing = {
            model.capturePairing()
            openWirelessDebugging()
        },
        onDismissInfo = { model.editUi { it.copy(infoMessage = null) } },
        onOnboardingWelcomed = model::markWelcomed,
        onAcceptConsent = model::acceptConsent,
        onFinishOnboarding = model::finishOnboarding,
        onWithdrawConsent = {
            model.editUi { it.copy(isSettingsOpen = false) }
            model.withdrawConsent()
            // The switch is the user's to turn off; the app cannot.
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onLetMikeSetUpWireless = {
            ensureService()
            model.editUi { it.copy(isSettingsOpen = false) }
            model.letMikeSetUpWireless()
        },
    )

    /**
     * Wireless debugging is not reachable by its own action on every build -
     * HyperOS does not resolve it at all - so this walks down to developer
     * options and then to the settings root rather than throwing.
     */
    private fun openWirelessDebugging() {
        val candidates = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        if (candidates.none { openSettings(it, report = false) }) {
            model.error("This phone has no Wireless debugging screen to open. Turn it on from Developer options.")
        }
    }

    /** Returns false when nothing on the phone can handle the intent. */
    private fun openSettings(intent: Intent, report: Boolean = true): Boolean {
        val started = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!started && report) model.error("This phone has no settings screen for that.")
        return started
    }

    /**
     * Ask for notifications, or send the user to the app's notification screen
     * once Android stops showing the dialog — a launch after a permanent denial
     * is a silent no-op.
     */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            openNotificationSettings()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!granted && !askedForNotifications) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }.onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
    private fun toggleVoice() {
        if (model.graph.voice.state.value.active) {
            model.toggleVoice()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensureService()
            model.toggleVoice()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun openFile(item: WorkspaceFileItem) {
        runCatching {
            val (uri, type) = workspaceUri(item)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
        }.onFailure { model.error(it.message ?: "No app can open this file.") }
    }

    private fun shareFile(item: WorkspaceFileItem) {
        runCatching {
            val (uri, type) = workspaceUri(item)
            val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share file"))
        }.onFailure { model.error(it.message ?: "Could not share this file.") }
    }

    // A content URI other apps may read, and a type they will claim: notes
    // and JSON go out as plain text, which almost every viewer accepts.
    private fun workspaceUri(item: WorkspaceFileItem): Pair<Uri, String> {
        check(!item.isDirectory) { "Choose a file inside this folder." }
        val file = model.resolveWorkspaceFile(item)
        check(file.isFile) { "This file is no longer in the chat's folder." }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        return uri to mimeTypeFor(file.name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }
}
