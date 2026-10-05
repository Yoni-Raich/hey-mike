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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.androidagent.core.UserQuestion

/**
 * What Mike asked with `ask_user`, pinned above the composer like an approval:
 * the chat list follows its newest message, and a card in it would scroll away.
 *
 * Each option is one tap. Free text is typed in the composer below, which is
 * already there with the keyboard and voice; [typeHint] says so. Skip tells
 * Mike the user is not answering, so he does not wait out the timeout.
 */
@Composable
internal fun QuestionCard(
    question: UserQuestion,
    onAnswer: (questionId: String, answer: String?) -> Unit,
    modifier: Modifier = Modifier,
    typeHint: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            // A long question with six options must not push the composer off a small screen.
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Mike is asking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
            )
            // Blocks the run until answered, so a screen reader is interrupted, as for an approval.
            Text(
                question.question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
            )
            question.options.forEach { option ->
                OutlinedButton(
                    onClick = { onAnswer(question.id, option) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.4f)),
                ) {
                    Text(
                        option,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !typeHint -> ""
                        question.options.isEmpty() -> "Type your answer below"
                        else -> "Or type your own answer below"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
                TextButton(onClick = { onAnswer(question.id, null) }) { Text("Skip") }
            }
        }
    }
}
