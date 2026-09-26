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

package dev.androidagent.core

/**
 * The first-launch screens, in order. Only [SIGN_IN] and [SCREEN_ACCESS] are
 * things the user has to do by hand; the rest explain, ask for consent, or
 * hand the remaining setup to Mike.
 */
enum class OnboardingStep { WELCOME, CONSENT, SIGN_IN, SCREEN_ACCESS, HANDOVER, DONE }

/** What the user has already been through, as the app stores it. */
data class OnboardingProgress(
    val welcomed: Boolean = false,
    /** The consent text version the user agreed to, or null when never or withdrawn. */
    val consentVersion: Int? = null,
    val consentAt: Long? = null,
    val finished: Boolean = false,
) {
    val consented: Boolean get() = consentVersion != null && consentVersion >= Onboarding.CONSENT_VERSION
}

/** One thing the handover screen offers to finish after the two manual steps. */
data class HandoverOffer(
    val item: SetupItem,
    val done: Boolean,
    /** False when this offer has to wait for another one first. */
    val available: Boolean,
)

object Onboarding {

    /**
     * Raise this when the consent text changes in substance. Everyone who
     * agreed to an older version is asked again before the next run.
     */
    const val CONSENT_VERSION = 1

    /**
     * The screen to show. Sign-in and screen access are skipped once they are
     * done, so a user who already set the phone up only sees the consent and
     * the handover. A withdrawn or outdated consent always comes back.
     */
    fun step(progress: OnboardingProgress, signals: SetupSignals): OnboardingStep = when {
        !progress.consented && !progress.welcomed && signals.signedIn != true -> OnboardingStep.WELCOME
        !progress.consented -> OnboardingStep.CONSENT
        progress.finished -> OnboardingStep.DONE
        signals.signedIn != true -> OnboardingStep.SIGN_IN
        !signals.a11yConnected -> OnboardingStep.SCREEN_ACCESS
        else -> OnboardingStep.HANDOVER
    }

    /**
     * What the handover screen lists. The floating control comes first:
     * device control refuses to start without it, so Mike cannot run the
     * wireless-debugging setup until it is allowed.
     */
    fun handover(signals: SetupSignals): List<HandoverOffer> = listOf(
        HandoverOffer(SetupItem.FLOATING_CONTROL, signals.overlayGranted, available = true),
        HandoverOffer(SetupItem.NOTIFICATIONS, signals.notificationsGranted, available = true),
        HandoverOffer(
            SetupItem.WIRELESS_ADB,
            signals.adbPhase == ConnectionPhase.CONNECTED,
            available = signals.overlayGranted && signals.a11yConnected,
        ),
    )

    /** 1-based position among the two manual steps, or null for the other screens. */
    fun manualStepNumber(step: OnboardingStep): Int? = when (step) {
        OnboardingStep.SIGN_IN -> 1
        OnboardingStep.SCREEN_ACCESS -> 2
        else -> null
    }

    const val MANUAL_STEPS = 2
}
