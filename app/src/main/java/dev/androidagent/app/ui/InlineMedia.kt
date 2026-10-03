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

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import dev.androidagent.core.ChatTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun isImagePath(path: String): Boolean = ChatTools.isImage(path)
internal fun isVideoPath(path: String): Boolean = ChatTools.isVideo(path)
internal fun isMediaPath(path: String): Boolean = ChatTools.isMedia(path)

/** How long a video is, as the tile shows it: 0:07, 12:40, 1:02:03. */
internal fun videoLength(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}

private val MediaShape = RoundedCornerShape(14.dp)

/**
 * The pictures and videos among [paths], in the message that carries them.
 *
 * One is shown whole, at its own shape. Several share a two-column grid of
 * square tiles, so ten pictures do not make a message ten screens long. A tap
 * opens a picture to pinch and pan, or plays a video, full screen.
 */
@Composable
internal fun InlineMedia(paths: List<String>) {
    val media = remember(paths) { paths.filter(::isMediaPath).distinct() }
    if (media.isEmpty()) return
    var selected by remember { mutableStateOf<String?>(null) }
    if (media.size == 1) {
        MediaTile(media.single(), grid = false) { selected = media.single() }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            media.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { path ->
                        Box(Modifier.weight(1f)) { MediaTile(path, grid = true) { selected = path } }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
    selected?.let { path ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (isVideoPath(path)) VideoPlayer(path) else ZoomableImage(path)
                TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MediaTile(path: String, grid: Boolean, onOpen: () -> Unit) {
    val name = File(path).name
    val frame = if (grid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
    val scale = if (grid) ContentScale.Crop else ContentScale.Fit
    if (isVideoPath(path)) {
        VideoTile(path, frame.clip(MediaShape).clickable(onClickLabel = "Play video $name", onClick = onOpen), scale)
    } else {
        SubcomposeAsyncImage(
            model = File(path), contentDescription = "Open image $name",
            modifier = frame.clip(MediaShape).clickable(onClick = onOpen),
            contentScale = scale,
            loading = { Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = { Text("Image is no longer available: $name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

/** What a video tile needs from the file. A null frame with [readable] is a video Android cannot preview. */
private class VideoPreview(val frame: ImageBitmap?, val durationMs: Long?, val readable: Boolean)

private fun videoPreview(path: String): VideoPreview {
    if (!File(path).isFile) return VideoPreview(null, null, readable = false)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        VideoPreview(
            frame = retriever.frameAtTime?.asImageBitmap(),
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            readable = true,
        )
    } catch (_: Exception) {
        // A format the phone cannot read a frame from may still play.
        VideoPreview(null, null, readable = true)
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun VideoTile(path: String, modifier: Modifier, scale: ContentScale) {
    var preview by remember(path) { mutableStateOf<VideoPreview?>(null) }
    LaunchedEffect(path) { preview = withContext(Dispatchers.IO) { videoPreview(path) } }
    val name = File(path).name
    val shown = preview
    if (shown != null && !shown.readable) {
        Text("Video is no longer available: $name", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Box(modifier.background(Color(0xFF151515)), contentAlignment = Alignment.Center) {
        val frame = shown?.frame
        if (frame != null) {
            Image(frame, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxWidth())
        } else {
            // Holds the tile's shape before the frame arrives, and for a video with none.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        if (shown == null) {
            CircularProgressIndicator()
        } else {
            Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        shown?.durationMs?.let { millis ->
            Text(
                videoLength(millis),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(path: String) {
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var x by remember(path) { mutableFloatStateOf(0f) }
    var y by remember(path) { mutableFloatStateOf(0f) }
    SubcomposeAsyncImage(model = File(path), contentDescription = "Expanded image", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().pointerInput(path) {
            detectTransformGestures { _, pan, scale, _ ->
                zoom = (zoom * scale).coerceIn(1f, 5f)
                x = if (zoom == 1f) 0f else (x + pan.x).coerceIn(-size.width * zoom, size.width * zoom)
                y = if (zoom == 1f) 0f else (y + pan.y).coerceIn(-size.height * zoom, size.height * zoom)
            }
        }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = x; translationY = y })
}

/**
 * The platform player, with its own play, pause and seek bar. The file is in
 * the chat's folder, so nothing leaves the app to play it.
 */
@Composable
private fun VideoPlayer(path: String) {
    var failed by remember(path) { mutableStateOf(false) }
    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This phone cannot play ${File(path).name}.", color = Color.White)
        }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnErrorListener { _, _, _ -> failed = true; true }
                    setOnPreparedListener { start() }
                    setVideoURI(Uri.fromFile(File(path)))
                }
            },
            onRelease = { it.stopPlayback() },
        )
    }
}
