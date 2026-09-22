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

import org.junit.Assert.*
import org.junit.Test

class OnboardingTest {

    private val fresh = SetupSignals()
    private val signedIn = fresh.copy(signedIn = true, runtimePhase = RuntimePhase.READY)
    private val ready = signedIn.copy(a11yConnected = true, a11yDeclared = true)
    private val agreed = OnboardingProgress(welcomed = true, consentVersion = Onboarding.CONSENT_VERSION, consentAt = 1L)

    @Test fun aNewPhoneStartsAtWelcome() {
        assertEquals(OnboardingStep.WELCOME, Onboarding.step(OnboardingProgress(), fresh))
    }

    @Test fun consentComesBeforeSignIn() {
        assertEquals(OnboardingStep.CONSENT, Onboarding.step(OnboardingProgress(welcomed = true), fresh))
    }

    @Test fun thenOnlyTheTwoManualSteps() {
        assertEquals(OnboardingStep.SIGN_IN, Onboarding.step(agreed, fresh))
        assertEquals(OnboardingStep.SCREEN_ACCESS, Onboarding.step(agreed, signedIn))
        assertEquals(OnboardingStep.HANDOVER, Onboarding.step(agreed, ready))
        assertEquals(OnboardingStep.DONE, Onboarding.step(agreed.copy(finished = true), ready))
    }

    @Test fun aSwitchThatAndroidBlockedStaysOnScreenAccess() {
        // Declared but never connected is the restricted-settings block.
        val blocked = signedIn.copy(a11yDeclared = true, a11yConnected = false)
        assertEquals(OnboardingStep.SCREEN_ACCESS, Onboarding.step(agreed, blocked))
    }

    @Test fun anExistingUserSkipsWelcomeButStillConsents() {
        assertEquals(OnboardingStep.CONSENT, Onboarding.step(OnboardingProgress(), ready))
        assertEquals(OnboardingStep.HANDOVER, Onboarding.step(agreed.copy(welcomed = false), ready))
    }

    @Test fun withdrawnOrOutdatedConsentAsksAgainEvenWhenFinished() {
        val withdrawn = agreed.copy(consentVersion = null, consentAt = null, finished = true)
        assertEquals(OnboardingStep.CONSENT, Onboarding.step(withdrawn, ready))
        val outdated = agreed.copy(consentVersion = Onboarding.CONSENT_VERSION - 1, finished = true)
        assertEquals(OnboardingStep.CONSENT, Onboarding.step(outdated, ready))
    }

    @Test fun finishedStaysDoneWhenSomethingLaterBreaks() {
        // A switch Android turns off later is a settings problem, not a reason
        // to walk the user through first launch again.
        val finished = agreed.copy(finished = true)
        assertEquals(OnboardingStep.DONE, Onboarding.step(finished, signedIn))
    }

    @Test fun wirelessDebuggingWaitsForTheFloatingControl() {
        val offers = Onboarding.handover(ready)
        assertEquals(listOf(SetupItem.FLOATING_CONTROL, SetupItem.NOTIFICATIONS, SetupItem.WIRELESS_ADB), offers.map { it.item })
        assertFalse(offers.first { it.item == SetupItem.WIRELESS_ADB }.available)
        val allowed = Onboarding.handover(ready.copy(overlayGranted = true))
        assertTrue(allowed.first { it.item == SetupItem.WIRELESS_ADB }.available)
        assertTrue(allowed.first { it.item == SetupItem.FLOATING_CONTROL }.done)
    }

    @Test fun manualStepsAreNumberedOneAndTwo() {
        assertEquals(1, Onboarding.manualStepNumber(OnboardingStep.SIGN_IN))
        assertEquals(2, Onboarding.manualStepNumber(OnboardingStep.SCREEN_ACCESS))
        assertNull(Onboarding.manualStepNumber(OnboardingStep.CONSENT))
    }
}
