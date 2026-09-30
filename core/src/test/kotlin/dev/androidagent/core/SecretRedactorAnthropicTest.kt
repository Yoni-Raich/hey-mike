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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorAnthropicTest {
    private val shapes = listOf(
        "sk-ant-api03-AbCdEf0123456789_-xyz",
        "sk-ant-oat01-AbCdEf0123456789_-xyz",
        "sk-ant-ort01-AbCdEf0123456789_-xyz",
        "sk-ant-short",
    )

    @Test fun anthropicKeysAndOauthTokensAreRedactedEverywhere() {
        for (token in shapes) {
            val line = "claude: request failed with $token (401)"
            for (clean in listOf(SecretRedactor.redact(line), SecretRedactor.redactUiText(line), SecretRedactor.redactStderrLine(line))) {
                assertFalse("$token leaked: $clean", clean.contains(token))
                assertTrue(clean.contains("[REDACTED_API_KEY]"))
            }
        }
    }

    @Test fun ordinaryTextAroundItIsKept() {
        val clean = SecretRedactor.redactUiText("Invalid API key · Please run /login")
        assertTrue(clean == "Invalid API key · Please run /login")
    }
}
