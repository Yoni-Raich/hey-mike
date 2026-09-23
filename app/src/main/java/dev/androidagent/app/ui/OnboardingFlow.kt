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

package dev.androidagent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.HandoverOffer
import dev.androidagent.core.Onboarding
import dev.androidagent.core.OnboardingStep
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RuntimePhase
import dev.androidagent.core.SetupItem

// First launch. The user does two things by hand, sign-in and the
// accessibility switch, because no app may do either for them. Everything
// before is explanation and consent; everything after is offered, not
// required, and Mike does the parts it can while the user watches.

internal val ActionBlue = VoiceButtonBlue
private val CardFill = Color(0xFF141414)
private val CardBorder = Color(0xFF2A2A2A)
private val StepTrack = Color(0xFF262626)
private val Teal = Color(0xFF83D9CA)
private val Amber = Color(0xFFF6B86A)
private val Muted = Color(0xFF8F8F8F)
private val Body = Color(0xFFB9B9B9)

internal const val POLICIES_URL = "https://openai.com/policies/"

/** The consent statements, shared by the first-launch screen and Settings. */
internal val ConsentStatements = listOf(
    "Full control of this phone is a real risk" to
        "With your permission, Mike can see the screen, tap, type, open apps and change settings, " +
        "including apps with private data such as messages and banking. Only continue if you accept that risk.",
    "Mike can make mistakes" to
        "It can misread the screen or tap the wrong thing. Watch what it does, check anything important, " +
        "and press Stop if something looks wrong. Actions already done may not be undone.",
    "Your data goes only to Codex" to
        "Hey Mike has no servers of its own. What Mike sees and what you type goes to Codex (OpenAI) to " +
        "answer you, and is covered only by the Codex policies. Chats and your sign-in stay on this phone.",
)

@Composable
internal fun OnboardingFlow(state: AgentUiState, actions: AgentUiActions) {
    val step = state.onboardingStep()
    if (step == OnboardingStep.DONE) return
    // Nothing behind this screen is usable yet, and backing out of it would
    // leave the chat half set up. Back is a no-op rather than an exit trap:
    // the system still leaves the app on a second gesture from Home.
    BackHandler { }
    val animate = animationsEnabled()
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val time = if (animate) 260 else 0
                fadeIn(tween(time)) togetherWith fadeOut(tween(time))
            },
            label = "onboarding-step",
        ) { shown ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                when (shown) {
                    OnboardingStep.WELCOME -> WelcomeStep(state, actions)
                    OnboardingStep.CONSENT -> ConsentStep(actions)
                    OnboardingStep.SIGN_IN -> SignInStep(state, actions)
                    OnboardingStep.SCREEN_ACCESS -> ScreenAccessStep(state, actions)
                    OnboardingStep.HANDOVER -> HandoverStep(state, actions)
                    OnboardingStep.DONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(state: AgentUiState, actions: AgentUiActions) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        AgentOrb(modifier = Modifier.size(220.dp), phase = RunPhase.THINKING)
        Title("Hi, I'm Mike.", center = true)
        Text(
            "Tell me what you need, and I'll do it on this phone while you watch. You can stop me at any moment.",
            style = MaterialTheme.typography.bodyLarge,
            color = Body,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OnboardingCard {
            Text("Two quick steps · about a minute", style = MaterialTheme.typography.bodySmall, color = Muted)
            NumberedLine(1, "Sign in to your account")
            NumberedLine(2, "Let me see and tap the screen")
            Text(
                "I'll set up the rest myself and ask before I change anything.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
    RuntimeLine(state, actions)
    PrimaryButton("Let's start", onClick = actions.onOnboardingWelcomed)
}

@Composable
private fun ColumnScope.ConsentStep(actions: AgentUiActions) {
    val uriHandler = LocalUriHandler.current
    var checked by rememberSaveable { mutableStateOf(List(ConsentStatements.size) { false }) }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentOrb(modifier = Modifier.size(64.dp), phase = RunPhase.STOPPING)
            Column(Modifier.padding(start = 8.dp)) {
                Title("Before we start")
                Text("Please read and confirm all three", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        ConsentStatements.forEachIndexed { index, (title, body) ->
            val on = checked[index]
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardFill,
                border = BorderStroke(1.dp, if (on) ActionBlue else CardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = on,
                        role = Role.Checkbox,
                        onValueChange = { value -> checked = checked.toMutableList().also { it[index] = value } },
                    ),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    // The row carries the toggle, so the box itself stays silent.
                    Checkbox(
                        checked = on,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = ActionBlue),
                        modifier = Modifier.padding(end = 12.dp, top = 2.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = Body)
                    }
                }
            }
        }
        TextButton(onClick = { uriHandler.openUri(POLICIES_URL) }) { Text("Read the Codex and OpenAI policies") }
    }
    val left = checked.count { !it }
    PrimaryButton(
        label = if (left == 0) "I understand and agree" else "Confirm all $left to continue",
        enabled = left == 0,
        onClick = actions.onAcceptConsent,
    )
    Text(
        "You can read this again, or withdraw, in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun ColumnScope.SignInStep(state: AgentUiState, actions: AgentUiActions) {
    val uriHandler = LocalUriHandler.current
    val account = state.accountStatus
    val waiting = account?.loginUrl != null
    StepBar(OnboardingStep.SIGN_IN)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        AgentOrb(modifier = Modifier.size(120.dp), phase = RunPhase.IDLE)
        Title("Sign in so I can think")
        Text(
            "I use your ChatGPT account. Your browser opens, you sign in there, and you come straight back here.",
            style = MaterialTheme.typography.bodyLarge,
            color = Body,
        )
        OnboardingCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Teal, modifier = Modifier.padding(end = 12.dp))
                Text("I never see your password. The sign-in is kept on this phone only.", color = Body)
            }
        }
        account?.userCode?.takeIf { waiting && it.isNotBlank() }?.let { code ->
            OnboardingCard {
                Text("If the page asks for a code, enter:", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(code, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            }
        }
    }
    RuntimeLine(state, actions)
    val runtimeReady = state.runtimeStatus.phase in setOf(RuntimePhase.READY, RuntimePhase.RUNNING)
    PrimaryButton(
        label = when {
            waiting -> "Open the sign-in page again"
            runtimeReady -> "Sign in with ChatGPT"
            else -> "Getting ready…"
        },
        enabled = waiting || runtimeReady,
        onClick = { account?.loginUrl?.let(uriHandler::openUri) ?: actions.onLogin() },
    )
}

@Composable
private fun ColumnScope.ScreenAccessStep(state: AgentUiState, actions: AgentUiActions) {
    val blocked = state.a11yStatus.blockedByRestrictedSetting
    StepBar(OnboardingStep.SCREEN_ACCESS, warn = blocked)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (blocked) {
            AgentOrb(modifier = Modifier.size(96.dp), phase = RunPhase.STOPPING)
            Title("Android blocked the switch. That's normal.")
            Text(
                "Apps installed outside the Play Store need one more tap first. Flipping the same switch again won't help.",
                style = MaterialTheme.typography.bodyLarge,
                color = Body,
            )
            OnboardingCard {
                NumberedLine(1, "Tap Open App info below", accent = Amber)
                NumberedLine(2, "Tap the ⋮ menu at the top right, then Allow restricted settings", accent = Amber)
                NumberedLine(3, "Come back, then turn on Use Hey Mike again", accent = Amber)
            }
            Text(
                "No ⋮ menu? Try the switch once more first. The menu shows up only after Android has blocked it once.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else {
            AgentOrb(modifier = Modifier.size(96.dp), phase = RunPhase.IDLE)
            Title("Let me see and tap the screen")
            Text(
                "Android lets only you turn this on. After that, I can set up the rest myself.",
                style = MaterialTheme.typography.bodyLarge,
                color = Body,
            )
            OnboardingCard {
                Text("In Settings, you'll tap:", style = MaterialTheme.typography.bodySmall, color = Muted)
                NumberedLine(1, "Installed apps → Hey Mike")
                NumberedLine(2, "Turn on Use Hey Mike")
                NumberedLine(3, "Press back. I'll notice by myself.")
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Teal, modifier = Modifier.padding(end = 12.dp))
                Text(
                    "I only act during a task you started, a card shows what I'm doing, and Stop is always on screen. " +
                        "While a task runs, what's on the screen is sent to Codex.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Body,
                )
            }
        }
    }
    if (blocked) {
        PrimaryButton("Open App info", onClick = actions.onOpenAppInfo)
        SecondaryButton("Open accessibility settings", onClick = actions.onOpenAccessibilitySettings)
    } else {
        PrimaryButton("Open Settings", onClick = actions.onOpenAccessibilitySettings)
    }
}

@Composable
private fun ColumnScope.HandoverStep(state: AgentUiState, actions: AgentUiActions) {
    var confirmWireless by rememberSaveable { mutableStateOf(false) }
    val offers = Onboarding.handover(state.setupSignals())
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentOrb(modifier = Modifier.size(150.dp), phase = RunPhase.TOOL)
        Title("You're done. I'll take it from here.", center = true)
        Text(
            "These make me more useful. Each one is optional, and I ask before I change anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = Body,
            textAlign = TextAlign.Center,
        )
        offers.forEach { offer ->
            HandoverRow(
                offer = offer,
                onClick = when (offer.item) {
                    SetupItem.FLOATING_CONTROL -> actions.onOpenOverlayPermission
                    SetupItem.NOTIFICATIONS -> actions.onOpenNotificationSettings
                    else -> ({ confirmWireless = true })
                },
            )
        }
        Text(
            "Microphone, app updates and reading notifications wait until a task needs them.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
    PrimaryButton("Start chatting", onClick = actions.onFinishOnboarding)

    if (confirmWireless) {
        WirelessSetupDialog(
            onAllow = { confirmWireless = false; actions.onLetMikeSetUpWireless() },
            onDismiss = { confirmWireless = false },
        )
    }
}

/** The one question asked before Mike changes a system setting on its own. */
@Composable
internal fun WirelessSetupDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on Wireless debugging?") },
        text = {
            Text(
                "I'll open Developer options, turn on Wireless debugging and pair with this phone, so I can run " +
                    "commands, move files and install apps. It works only on your Wi-Fi, and you can turn it off in " +
                    "Settings anytime. You'll watch me do it, and Stop is always on screen.",
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun HandoverRow(offer: HandoverOffer, onClick: () -> Unit) {
    val (title, note, button) = when (offer.item) {
        SetupItem.FLOATING_CONTROL -> Triple(
            "Floating Stop button",
            "Lets you see and stop me while I work in other apps. Needed before I can control the phone.",
            "Allow",
        )
        SetupItem.NOTIFICATIONS -> Triple(
            "Progress notifications",
            "See what I'm doing from the notification shade. You tap Allow once.",
            "Allow",
        )
        else -> Triple(
            "Wireless debugging",
            if (offer.available) "Lets me run commands, move files and install apps. I turn it on and pair myself."
            else "Allow the floating Stop button first.",
            "Let Mike do it",
        )
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardFill,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(note, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (offer.done) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Done", tint = Teal)
            } else {
                TextButton(onClick = onClick, enabled = offer.available) { Text(button, color = if (offer.available) ActionBlueInk else Muted) }
            }
        }
    }
}

/** Blue for text actions on dark fills, where [ActionBlue] is too dark to read. */
internal val ActionBlueInk = Color(0xFF8AB4F8)

@Composable
private fun StepBar(step: OnboardingStep, warn: Boolean = false) {
    val number = Onboarding.manualStepNumber(step) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(Onboarding.MANUAL_STEPS) { index ->
                val color = when {
                    index + 1 < number -> Teal
                    index + 1 == number -> if (warn) Amber else Teal
                    else -> StepTrack
                }
                Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
            }
        }
        Text(
            if (number == Onboarding.MANUAL_STEPS) "Step $number of ${Onboarding.MANUAL_STEPS} · the last thing you do by hand"
            else "Step $number of ${Onboarding.MANUAL_STEPS}",
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) Amber else Muted,
        )
    }
}

/** The engine prepares in the background; this is its only trace, plus a retry when it fails. */
@Composable
private fun RuntimeLine(state: AgentUiState, actions: AgentUiActions) {
    val status = state.runtimeStatus
    when (status.phase) {
        RuntimePhase.READY, RuntimePhase.RUNNING -> Unit
        RuntimePhase.ERROR -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Getting ready failed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onPrepareRuntime) { Text("Try again") }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val progress = status.progress
            val bar = Modifier.weight(1f).height(4.dp)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = bar, color = Teal, trackColor = StepTrack)
            } else {
                LinearProgressIndicator(modifier = bar, color = Teal, trackColor = StepTrack)
            }
            Text(
                "Getting ready in the background",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun Title(text: String, center: Boolean = false) {
    Text(
        text,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Medium,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CardFill,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun NumberedLine(number: Int, text: String, accent: Color = Color(0xFFF2F2F2)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(26.dp).background(StepTrack, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ActionBlue,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF1F1F1F),
            disabledContentColor = Color(0xFF6B6B6B),
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Text(label, fontSize = 17.sp) }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFF333333)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
    ) { Text(label, color = Color(0xFFF2F2F2)) }
}
