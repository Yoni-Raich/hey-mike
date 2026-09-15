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

package dev.androidagent.app.assist

import android.service.voice.VoiceInteractionService

/**
 * Registers Hey Mike as a digital assistant, so the user can pick it under
 * Default apps > Digital assistant app and reach Mike by holding the power
 * button instead of Gemini.
 *
 * The service holds no logic. Android keeps it bound while the app is the
 * assistant and starts [AgentVoiceInteractionSessionService] on each press.
 *
 * It opens no always-on hotword detector: replacing "Hey Google" needs a
 * preinstalled or privileged app, and a detector that can never trigger would
 * only hold the microphone.
 */
class AgentVoiceInteractionService : VoiceInteractionService()
