package dev.androidagent.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The chat folder's files, as a sheet: what the user attached and what the
// agent saved come first, newest on top; the instructions and app cards the
// app plants in every folder wait folded at the bottom. A tap opens a file in
// another app and the share button hands it on.

internal enum class FileKind { IMAGE, PDF, TEXT, AUDIO, VIDEO, ARCHIVE, OTHER }

internal data class FileEntry(val item: WorkspaceFileItem, val name: String, val folder: String, val kind: FileKind)

internal data class WorkspaceListing(val yours: List<FileEntry>, val agent: List<FileEntry>)

// What WorkspaceSeeder writes into every chat folder.
private val AGENT_ROOTS = setOf("AGENTS.md", "preferences.json")

// Attachments are stored as "<uuid>-name" so two files with one name can coexist.
private val STORED_PREFIX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-")

private val TEXT_TYPES = setOf("md", "markdown", "txt", "log", "json", "yaml", "yml", "csv", "xml", "kt", "py", "js", "ts", "sh")

internal fun workspaceListing(items: List<WorkspaceFileItem>): WorkspaceListing {
    val entries = items.filterNot { it.isDirectory }.map { item ->
        val path = item.path.replace('\\', '/')
        val file = path.substringAfterLast('/')
        FileEntry(item, displayName(file), path.substringBeforeLast('/', ""), kindOf(file))
    }
    val (agent, yours) = entries.partition { isAgentFile(it.item.path) }
    return WorkspaceListing(
        yours = yours.sortedByDescending { it.item.modifiedAt ?: 0L },
        agent = agent.sortedBy { it.item.path.lowercase() },
    )
}

internal fun isAgentFile(path: String): Boolean {
    val top = path.replace('\\', '/').substringBefore('/')
    return top.startsWith(".") || top in AGENT_ROOTS
}

internal fun displayName(fileName: String): String = fileName.replaceFirst(STORED_PREFIX, "").ifBlank { fileName }

internal fun kindOf(fileName: String): FileKind = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "heic", "bmp" -> FileKind.IMAGE
    "pdf" -> FileKind.PDF
    in TEXT_TYPES -> FileKind.TEXT
    "mp3", "m4a", "wav", "ogg", "opus", "aac", "flac" -> FileKind.AUDIO
    "mp4", "mov", "mkv", "webm", "3gp" -> FileKind.VIDEO
    "zip", "tar", "gz", "7z", "rar" -> FileKind.ARCHIVE
    else -> FileKind.OTHER
}

/**
 * The type another app is offered. Notes and JSON go out as plain text,
 * which nearly every viewer claims; the system's own guess would be
 * missing or so specific that no app takes it.
 */
internal fun mimeTypeFor(fileName: String, system: (String) -> String?): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension in TEXT_TYPES) return "text/plain"
    return extension.takeIf { it.isNotEmpty() }?.let(system) ?: "*/*"
}

private val FilesSheetFill = Color(0xFF1B1B1B)
private val FilesMuted = Color(0xFF8F8F8F)
private val AgentTileFill = Color(0xFF262626)
private val AgentTileInk = Color(0xFFB9B9B9)

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.TEXT -> Icons.Outlined.Description
    FileKind.AUDIO -> Icons.Outlined.AudioFile
    FileKind.VIDEO -> Icons.Outlined.VideoFile
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.OTHER -> Icons.Outlined.InsertDriveFile
}

private fun FileKind.tint(): Color = when (this) {
    FileKind.IMAGE -> Color(0xFF69A7FF)
    FileKind.PDF -> Color(0xFFFF8A80)
    FileKind.TEXT -> Color(0xFF83D9CA)
    FileKind.AUDIO -> Color(0xFFF2B155)
    FileKind.VIDEO -> Color(0xFFC8A2FF)
    FileKind.ARCHIVE, FileKind.OTHER -> AgentTileInk
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceFilesSheet(state: AgentUiState, actions: AgentUiActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listing = remember(state.workspaceFiles) { workspaceListing(state.workspaceFiles) }
    val now = remember(state.workspaceFiles) { System.currentTimeMillis() }
    var showAgent by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = actions.onCloseWorkspaceFiles, sheetState = sheet, containerColor = FilesSheetFill) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Files", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Everything in this chat's folder", fontSize = 13.sp, lineHeight = 18.sp, color = FilesMuted)
                }
                if (state.isLoadingWorkspace) {
                    CircularProgressIndicator(modifier = Modifier.padding(14.dp).size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = actions.onOpenWorkspaceFiles) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh files", tint = FilesMuted)
                    }
                }
            }
            state.workspaceError?.let { message ->
                Text(message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
            }
            if (!state.isLoadingWorkspace && state.workspaceError == null && listing.yours.isEmpty()) {
                Text(
                    "No files yet. What you attach and what the agent saves in this chat shows up here.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = FilesMuted,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                items(listing.yours, key = { it.item.path }) { entry -> FileRow(entry, now, actions) }
                if (listing.agent.isNotEmpty()) {
                    item(key = "agent-files") { AgentFilesToggle(listing.agent.size, showAgent) { showAgent = !showAgent } }
                    if (showAgent) items(listing.agent, key = { "agent:${it.item.path}" }) { entry -> FileRow(entry, now, actions) }
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, now: Long, actions: AgentUiActions) {
    val details = listOfNotNull(
        entry.folder.ifBlank { null },
        entry.item.sizeBytes?.let(::formatBytes),
        entry.item.modifiedAt?.takeIf { it > 0L }?.let {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
        },
    ).joinToString(" · ")
    val tint = entry.kind.tint()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable { actions.onOpenWorkspaceFile(entry.item) }
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(entry.kind.icon(), contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details.isNotEmpty()) {
                Text(details, fontSize = 12.5.sp, lineHeight = 17.sp, color = FilesMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { actions.onShareWorkspaceFile(entry.item) }) {
            Icon(Icons.Outlined.Share, contentDescription = "Share ${entry.name}", modifier = Modifier.size(20.dp), tint = FilesMuted)
        }
    }
}

@Composable
private fun AgentFilesToggle(count: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(AgentTileFill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp), tint = AgentTileInk)
        }
        Column(Modifier.weight(1f)) {
            Text("Agent files · $count", fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Instructions, app cards and preferences the agent reads",
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = FilesMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = if (open) "Hide agent files" else "Show agent files",
            tint = FilesMuted,
            modifier = Modifier.rotate(if (open) 180f else 0f),
        )
    }
}
