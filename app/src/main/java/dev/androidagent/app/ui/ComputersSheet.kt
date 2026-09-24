package dev.androidagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.remote.RemoteAccess
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteSetup

private val SheetFill = Color(0xFF1B1B1B)
private val Muted = Color(0xFF8F8F8F)
private val CardFill = Color(0xFF242424)
private val ReadyInk = Color(0xFF83D9CA)
private val WaitInk = Color(0xFFF6B86A)

/**
 * Computers Mike can work on. Three views in one sheet: the list, the add or
 * edit form, and the folder picker a chat is opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComputersSheet(state: AgentUiState, actions: AgentUiActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf<ComputerDraft?>(null) }
    ModalBottomSheet(onDismissRequest = actions.onCloseComputers, sheetState = sheet, containerColor = SheetFill) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            val browser = state.folderBrowser
            val editing = draft
            when {
                browser != null -> FolderPicker(state, browser, actions)
                editing != null -> ComputerForm(
                    initial = editing,
                    onCancel = { draft = null },
                    onSave = { actions.onSaveComputer(it); draft = null },
                )
                else -> ComputerList(
                    state = state,
                    actions = actions,
                    onAdd = { draft = ComputerDraft() },
                    onEdit = { c ->
                        draft = ComputerDraft(c.id, c.label, c.host, c.port.toString(), c.user, "", c.access)
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = Muted)
        }
    }
}

@Composable
private fun ComputerList(state: AgentUiState, actions: AgentUiActions, onAdd: () -> Unit, onEdit: (RemoteComputer) -> Unit) {
    SheetHeader("Computers", "Mike works on your Windows PC over SSH, with Codex running there.")
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (state.computersUnreadable) {
            Text(
                "Saved computers could not be opened on this phone, so none of them is trusted. Add them again.",
                fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (state.computers.isEmpty()) {
            Text(
                "On the PC: Settings > System > Optional features > add \"OpenSSH Server\", then start the " +
                    "\"OpenSSH SSH Server\" service. The phone must reach the PC: the same Wi-Fi, or a VPN such as Tailscale.",
                fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        state.computers.forEach { computer ->
            ComputerCard(computer, state.computerSetup[computer.id], actions, onEdit)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a computer")
        }
    }
}

@Composable
private fun ComputerCard(
    computer: RemoteComputer,
    setup: RemoteSetup?,
    actions: AgentUiActions,
    onEdit: (RemoteComputer) -> Unit,
) {
    var confirmRemove by rememberSaveable(computer.id) { mutableStateOf(false) }
    val busy = setup is RemoteSetup.Working
    Column(Modifier.fillMaxWidth().background(CardFill, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Computer, contentDescription = null, tint = ReadyInk, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(computer.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(computer.address, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onEdit(computer) }, enabled = !busy) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit ${computer.label}", tint = Muted)
            }
            IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove ${computer.label}", tint = Muted)
            }
        }
        Text(
            when (computer.access) {
                RemoteAccess.ASK -> "Asks before commands outside the project folder"
                RemoteAccess.FULL -> "Full access, no questions"
            },
            fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
        )
        computer.fingerprint?.let {
            Text("Host key $it", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SetupLine(computer, setup, actions)
        Button(
            onClick = { actions.onConnectComputer(computer.id) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open a project folder")
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${computer.label}?") },
            text = { Text("Its chats are deleted from this phone. Nothing on the computer changes, and Codex stays installed there.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; actions.onRemoveComputer(computer.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetupLine(computer: RemoteComputer, setup: RemoteSetup?, actions: AgentUiActions) {
    when (setup) {
        null -> Unit
        is RemoteSetup.Working -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(setup.step, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        is RemoteSetup.Ready -> Text(
            "Connected to ${setup.probe.computerName.ifBlank { computer.label }} · Codex signed in as ${setup.account}",
            fontSize = 13.sp, color = ReadyInk, modifier = Modifier.padding(top = 10.dp),
        )
        is RemoteSetup.Failed -> Text(setup.message, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        is RemoteSetup.NeedsSignIn -> Column(Modifier.padding(top = 10.dp)) {
            Text("Sign in to Codex on this computer", fontSize = 14.sp, color = WaitInk, fontWeight = FontWeight.Medium)
            Text("Open the page, sign in, and enter this code:", fontSize = 13.sp, color = Muted)
            setup.code?.let { Text(it, fontSize = 24.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 6.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                setup.url?.let { url -> OutlinedButton(onClick = { actions.onOpenUrl(url) }) { Text("Open sign-in page") } }
                TextButton(onClick = { actions.onCheckComputerSignIn(computer.id) }) { Text("I signed in") }
            }
        }
    }
}

@Composable
private fun ComputerForm(initial: ComputerDraft, onCancel: () -> Unit, onSave: (ComputerDraft) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val editing = initial.id != null
    SheetHeader(if (editing) "Edit computer" else "Add a computer", "Your Windows sign-in, sent only to this computer over SSH.", onBack = onCancel)
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = draft.host, onValueChange = { draft = draft.copy(host = it.trim()) },
            label = { Text("IP address or name") }, placeholder = { Text("192.168.1.20") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.user, onValueChange = { draft = draft.copy(user = it) },
            label = { Text("Windows user name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = draft.password, onValueChange = { draft = draft.copy(password = it) },
            label = { Text(if (editing) "Password (leave empty to keep)" else "Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.label, onValueChange = { draft = draft.copy(label = it) },
                label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.port, onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit).take(5)) },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
            )
        }
        Text("What Mike may do there", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        AccessOption(
            selected = draft.access == RemoteAccess.ASK,
            title = "Ask me first (recommended)",
            detail = "Edits and commands inside the project folder run. Anything else waits for your answer on the phone.",
        ) { draft = draft.copy(access = RemoteAccess.ASK) }
        AccessOption(
            selected = draft.access == RemoteAccess.FULL,
            title = "Full access",
            detail = "Mike runs any command as your Windows user without asking.",
        ) { draft = draft.copy(access = RemoteAccess.FULL) }
        val ready = draft.host.isNotBlank() && draft.user.isNotBlank() && (editing || draft.password.isNotEmpty())
        Button(onClick = { onSave(draft) }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text(if (editing) "Save and connect" else "Connect")
        }
    }
}

@Composable
private fun AccessOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
        Column {
            Text(title, fontSize = 15.sp)
            Text(detail, fontSize = 12.5.sp, lineHeight = 17.sp, color = Muted)
        }
    }
}

@Composable
private fun FolderPicker(state: AgentUiState, browser: FolderBrowserState, actions: AgentUiActions) {
    val computer = state.computers.firstOrNull { it.id == browser.computerId }
    val listing = browser.listing
    SheetHeader(computer?.label ?: "Computer", "Choose the folder Mike works in", onBack = actions.onCloseFolderBrowser)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listing?.path ?: "Loading…",
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (browser.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
        }
        if (listing?.isGitRepo == true) Text("Git repository", fontSize = 12.sp, color = ReadyInk)
        browser.error?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        if (listing != null && listing.drives.size > 1) {
            LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listing.drives) { drive ->
                    AssistChip(onClick = { actions.onBrowseFolder(browser.computerId, drive) }, label = { Text(drive) })
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp)) {
        listing?.parent?.let { parent ->
            item(key = "..") { FolderRow("Up one folder", Icons.Outlined.ArrowUpward, enabled = !browser.loading) { actions.onBrowseFolder(browser.computerId, parent) } }
        }
        if (listing != null && listing.folders.isEmpty() && !browser.loading) {
            item(key = "empty") { Text("No folders here.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
        }
        items(listing?.folders.orEmpty(), key = { it }) { name ->
            FolderRow(name, Icons.Outlined.Folder, enabled = !browser.loading) {
                actions.onBrowseFolder(browser.computerId, childPath(listing!!.path, name))
            }
        }
    }
    Button(
        onClick = { listing?.let { actions.onOpenFolderChat(browser.computerId, it.path) } },
        enabled = listing != null && !browser.loading,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
    ) {
        Text("Start a chat in ${listing?.path?.let(::folderName) ?: "this folder"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(CardFill, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Windows child path: `C:\` + `src` is `C:\src`, `C:\src` + `app` is `C:\src\app`. */
internal fun childPath(parent: String, name: String): String =
    if (parent.endsWith("\\") || parent.endsWith("/")) parent + name else "$parent\\$name"

internal fun folderName(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
