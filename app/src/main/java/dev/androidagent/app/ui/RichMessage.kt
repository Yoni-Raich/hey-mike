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

import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.movement.MovementMethodPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import java.io.File

@Composable
internal fun MarkdownMessage(value: String, textColor: Color) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.secondary.toArgb()
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize.value
    val renderer = remember(context, accent) {
        val density = context.resources.displayMetrics.density
        Markwon.builder(context)
            .usePlugin(TablePlugin.create { table ->
                table.tableBorderColor(MarkdownBorder)
                    .tableBorderWidth(density.toInt().coerceAtLeast(1))
                    .tableCellPadding((8 * density).toInt())
                    .tableHeaderRowBackgroundColor(MarkdownSurface)
                    .tableEvenRowBackgroundColor(0)
                    .tableOddRowBackgroundColor(0)
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
            .usePlugin(CodeLanguages.syntax())
            .usePlugin(object : AbstractMarkwonPlugin() {
                // After the syntax plugin, so these colors win over its theme.
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeBackgroundColor(MarkdownInlineCode)
                        .codeTextColor(MarkdownCodeInk)
                        .codeBlockBackgroundColor(MarkdownCodeBlock)
                        .codeBlockMargin((12 * density).toInt())
                        .blockQuoteColor(accent)
                        .blockQuoteWidth((3 * density).toInt())
                        .linkColor(accent)
                        .bulletWidth((5 * density).toInt())
                        .thematicBreakColor(MarkdownBorder)
                        .headingBreakHeight(0)
                        .headingTextSizeMultipliers(floatArrayOf(1.4f, 1.25f, 1.12f, 1f, 0.95f, 0.9f))
                }
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        // Only user-tapped web links are opened. No file, intent, or script URLs.
                        if (android.net.Uri.parse(link).scheme in setOf("https", "http", "mailto")) {
                            runCatching { view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
                        }
                    }
                }
            }).build()
    }
    val rendered = remember(renderer, value) { renderer.toMarkdown(keepListsTogether(value)) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().semantics { text = AnnotatedString(rendered.toString()) },
        factory = { TextView(it).apply {
            // Follows the app's type scale; sp keeps the phone's font size setting.
            textSize = fontSize
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            // Decided per line: any Hebrew or Arabic letter makes the whole
            // line right-to-left, so "Yoni Raich (את/ה)" no longer flips a
            // Hebrew list to the left just because it starts with Latin.
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        } },
        update = { view ->
            view.setTextColor(textColor.toArgb())
            if (view.tag != value) { renderer.setParsedMarkdown(view, rendered); view.tag = value }
        },
    )
}

// Markdown surfaces, matched to the dark chat palette in ChatComposer.
private const val MarkdownSurface = 0xFF1B1B1B.toInt()
private const val MarkdownBorder = 0xFF333333.toInt()
private const val MarkdownInlineCode = 0xFF2A2A2A.toInt()
private const val MarkdownCodeBlock = 0xFF0C0C0C.toInt()
private const val MarkdownCodeInk = 0xFFE6E6E6.toInt()

internal fun isImagePath(path: String): Boolean = File(path).extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

@Composable
internal fun InlineImages(paths: List<String>) {
    var selected by remember { mutableStateOf<String?>(null) }
    paths.filter(::isImagePath).distinct().forEach { path ->
        SubcomposeAsyncImage(
            model = File(path), contentDescription = "Open image ${File(path).name}",
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).clickable { selected = path },
            contentScale = ContentScale.Fit,
            loading = { Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = { Text("Image is no longer available: ${File(path).name}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    selected?.let { path ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var zoom by remember(path) { mutableFloatStateOf(1f) }
            var x by remember(path) { mutableFloatStateOf(0f) }
            var y by remember(path) { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                SubcomposeAsyncImage(model = File(path), contentDescription = "Expanded image", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(path) {
                        detectTransformGestures { _, pan, scale, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 5f)
                            x = if (zoom == 1f) 0f else (x + pan.x).coerceIn(-size.width * zoom, size.width * zoom)
                            y = if (zoom == 1f) 0f else (y + pan.y).coerceIn(-size.height * zoom, size.height * zoom)
                        }
                    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = x; translationY = y })
                TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Close image", color = Color.White) }
            }
        }
    }
}
