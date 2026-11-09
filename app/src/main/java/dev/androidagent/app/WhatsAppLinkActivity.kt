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

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Linking WhatsApp Web inside Hey Mike. The page is WhatsApp's own; this
 * screen only explains the same-phone path (link with a phone number) and
 * hosts the connector's page. Mike reads nothing while the user links.
 */
class WhatsAppLinkActivity : ComponentActivity() {
    private val connector get() = (application as AgentApplication).graph.whatsAppConnector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hebrew = java.util.Locale.getDefault().language in setOf("he", "iw")
        fun t(english: String, he: String) = if (hebrew) he else english
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("Link WhatsApp Web", "קישור ווטסאפ ווב"), Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { finish() }) { Text(t("Done", "סיום")) }
                            }
                            Text(t("1. Below, tap \"Log in with phone number\" and enter your number.\n2. WhatsApp shows an 8-character code.\n3. In WhatsApp: Settings › Linked devices › Link a device › Link with phone number instead, and type the code.\n4. When your chats appear below, tap Done.",
                                "1. למטה, לחצו \"התחברות עם מספר טלפון\" והזינו את המספר.\n2. יופיע קוד של 8 תווים.\n3. בווטסאפ: הגדרות › מכשירים מקושרים › קישור מכשיר › קישור עם מספר טלפון, והקלידו את הקוד.\n4. כשהשיחות מופיעות למטה, לחצו סיום."),
                                style = MaterialTheme.typography.bodySmall)
                            Text(t("If you already linked WhatsApp Web in Mike's browser, your chats appear below; just tap Done. Mike only reads chats already saved here, and only those allowed in Sources.",
                                "אם כבר קישרתם ווטסאפ ווב בדפדפן של מייק, השיחות יופיעו למטה, ופשוט לוחצים סיום. מייק קורא רק שיחות ששמורות כבר כאן, ורק כאלה שמותרות במקורות."),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        AndroidView(factory = { context -> FrameLayout(context).also { connector.attach(it, this@WhatsAppLinkActivity) } },
                            modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        connector.detach()
        (application as AgentApplication).graph.taskDesk.refreshConnections()
        super.onDestroy()
    }
}
