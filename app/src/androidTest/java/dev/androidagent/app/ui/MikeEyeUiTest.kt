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
 */

package dev.androidagent.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fixture only: draws every mood at the sizes the app uses, so the redesign can be looked at. */
class MikeEyeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyMoodAtEverySizeCanBeCaptured() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    Modifier.background(Color(0xFF0F0E11)).padding(16.dp).size(width = 420.dp, height = 700.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (mood in EyeMood.values()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            MikeEye(mood, Modifier.size(96.dp), satellites = 3)
                            MikeEye(mood, Modifier.size(64.dp), satellites = 2)
                            MikeEye(mood, Modifier.size(40.dp), satellites = 1)
                            MikeEye(mood, Modifier.size(30.dp), satellites = 1)
                            MikeEye(mood, Modifier.size(24.dp), animate = false)
                            MikeEye(mood, Modifier.size(56.dp), tint = memberColor(210f), satellites = 1)
                        }
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((name, at) in listOf("early" to 600L, "late" to 1270L)) {
            compose.mainClock.advanceTimeBy(at)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(context.getExternalFilesDir(null), "ui-evidence/mike-moods-$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        }
    }
}
