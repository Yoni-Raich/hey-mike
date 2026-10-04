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
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import dev.androidagent.app.RemoteMediaLoader
import dev.androidagent.app.RemoteMediaState
import dev.androidagent.core.ChatTools
import dev.androidagent.core.RemoteMediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** Where a picture or video that stays on a computer is loaded from. Null where nothing can load it. */
internal val LocalRemoteMedia = staticCompositionLocalOf<RemoteMediaLoader?> { null }

/** A picture under this size loads as soon as its message is on screen; bigger waits for a tap. */
private const val AUTO_LOAD_BYTES = 20L * 1024 * 1024

/** A size for a tile: 340 KB, 12 MB, 1.4 GB. */
internal fun fileSize(bytes: Long): String = when {
    bytes < 1_000_000 -> "%d KB".format(maxOf(1L, (bytes + 999) / 1_000))
    bytes < 1_000_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.1f GB".format(bytes / 1_000_000_000.0)
}

/** A file open full screen: the copy on the phone, and where it came from when it came from a computer. */
private class Opened(val file: String, val ref: RemoteMediaRef?)

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
    var selected by remember { mutableStateOf<Opened?>(null) }
    if (media.size == 1) {
        MediaTile(media.single(), grid = false) { selected = it }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            media.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { path ->
                        Box(Modifier.weight(1f)) { MediaTile(path, grid = true) { selected = it } }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
    selected?.let { opened ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (isVideoPath(opened.file)) VideoPlayer(opened.file) else ZoomableImage(opened.file)
                TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Text("Close", color = Color.White)
                }
                opened.ref?.let { ref ->
                    LocalRemoteMedia.current?.let { loader ->
                        SaveButton(ref, loader, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp))
                    }
                }
            }
        }
    }
}

/** One attachment: a file on the phone, or a reference to one that is still on a computer. */
@Composable
private fun MediaTile(path: String, grid: Boolean, onOpen: (Opened) -> Unit) {
    RemoteMediaRef.parse(path)?.let { ref ->
        RemoteMediaTile(ref, grid) { onOpen(Opened(it.path, ref)) }
        return
    }
    LocalMediaTile(path, grid) { onOpen(Opened(path, null)) }
}

@Composable
private fun LocalMediaTile(path: String, grid: Boolean, onOpen: () -> Unit) {
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

/**
 * A picture or video that is still on a computer.
 *
 * Nothing was copied when Mike showed it. A picture loads when its message
 * comes on screen (a big one waits for a tap), a video loads when it is
 * tapped, and either can be saved to the phone with the button in the corner.
 * Until then the tile says where the file is and how big it is.
 */
@Composable
private fun RemoteMediaTile(ref: RemoteMediaRef, grid: Boolean, onOpen: (File) -> Unit) {
    val loader = LocalRemoteMedia.current
    if (loader == null) {
        Text("${ref.name} is on ${ref.place}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val state by remember(ref) { loader.state(ref) }.collectAsState()
    LaunchedEffect(ref) { if (ref.isImage && ref.size <= AUTO_LOAD_BYTES) loader.load(ref) }
    // A tap on a tile that is still loading opens it the moment it is ready.
    var openWhenReady by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(state, openWhenReady) {
        val ready = state as? RemoteMediaState.Ready ?: return@LaunchedEffect
        if (openWhenReady) {
            openWhenReady = false
            onOpen(ready.file)
        }
    }
    val scale = if (grid) ContentScale.Crop else ContentScale.Fit
    Box {
        when (val now = state) {
            is RemoteMediaState.Ready -> {
                val frame = if (grid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
                val open = Modifier.clip(MediaShape).clickable(onClickLabel = "Open ${ref.name}") { onOpen(now.file) }
                if (ref.isVideo) {
                    VideoTile(now.file.path, frame.then(open), scale)
                } else {
                    SubcomposeAsyncImage(
                        model = now.file, contentDescription = "Open image ${ref.name}",
                        modifier = frame.then(open), contentScale = scale,
                        loading = { Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                        error = { Text("Could not show ${ref.name}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            else -> RemoteTilePlaceholder(
                ref = ref, state = now,
                modifier = (if (grid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().height(180.dp))
                    .clip(MediaShape)
                    .clickable(onClickLabel = if (ref.isVideo) "Play ${ref.name}" else "Load ${ref.name}") {
                        loader.load(ref)
                        openWhenReady = true
                    },
            )
        }
        SaveButton(ref, loader, Modifier.align(Alignment.TopEnd).padding(2.dp))
    }
}

@Composable
private fun RemoteTilePlaceholder(ref: RemoteMediaRef, state: RemoteMediaState, modifier: Modifier) {
    val muted = Color.White.copy(alpha = 0.7f)
    Box(modifier.background(Color(0xFF151515)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (state) {
                is RemoteMediaState.Loading -> {
                    if (state.total > 0) {
                        CircularProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, color = Color.White)
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                else -> Icon(
                    if (ref.isVideo) Icons.Filled.PlayArrow else Icons.Outlined.CloudDownload,
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp),
                )
            }
            Text(ref.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val line = when (state) {
                is RemoteMediaState.Loading ->
                    if (state.total > 0) "Loading from ${ref.place} · ${(state.done * 100 / state.total).coerceIn(0, 100)}%"
                    else "Loading from ${ref.place}"
                is RemoteMediaState.Failed -> state.message + " · tap to retry"
                else -> "${ref.place} · ${fileSize(ref.size)} · tap to ${if (ref.isVideo) "play" else "load"}"
            }
            Text(line, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Saves a file that lives on a computer to the phone: loads it if it has not
 * been, then puts it in Pictures or Movies (Downloads for other files) under
 * "Hey Mike", where the gallery finds it.
 */
@Composable
private fun SaveButton(ref: RemoteMediaRef, loader: RemoteMediaLoader, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 = not saved, 1 = saving, 2 = saved
    var phase by remember(ref) { mutableStateOf(0) }
    Box(
        modifier
            .size(48.dp)
            .clickable(enabled = phase != 1, onClickLabel = "Save ${ref.name} to the phone") {
                phase = 1
                scope.launch {
                    val saved = loader.saveToPhone(ref)
                    phase = if (saved.isSuccess) 2 else 0
                    val folder = ref.savePath.substringBeforeLast('/')
                    Toast.makeText(
                        context,
                        saved.fold({ "Saved to $folder" }, { "Could not save: ${it.message ?: "unknown error"}" }),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            when (phase) {
                1 -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                2 -> Icon(Icons.Outlined.Check, contentDescription = "Saved", tint = Color.White, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Outlined.Download, contentDescription = "Save to phone", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
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
