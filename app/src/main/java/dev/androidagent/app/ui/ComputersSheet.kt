package dev.androidagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.remote.RemoteAccess
import dev.androidagent.remote.RemoteComputer
import dev.androidagent.remote.RemoteSetup

private val SheetFill = Color(0xFF1B1B1B)
private val Ink = Color(0xFFEDEDED)
private val Muted = Color(0xFF9A9A9A)
private val CardFill = Color(0xFF242424)
private val CodeFill = Color(0xFF151515)
private val Hairline = Color(0xFF343434)
private val ReadyInk = Color(0xFF83D9CA)
private val WaitInk = Color(0xFFF6B86A)

/** Turns on OpenSSH Server; the capability also opens port 22 in the firewall. */
private const val INSTALL_SSH_COMMAND =
    "Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0; Start-Service sshd; Set-Service sshd -StartupType Automatic"

/** Turns on the SSH server on Ubuntu and starts it now and at every boot. */
private const val UBUNTU_SSH_COMMAND = "sudo apt install -y openssh-server && sudo systemctl enable --now ssh"

/** The same steps as the Windows guide, as plain text to send to the PC. */
private val SETUP_STEPS_TEXT = """
Hey Mike: let Mike work on this Windows PC

1. Turn on OpenSSH Server. Open PowerShell as administrator and run:
   $INSTALL_SSH_COMMAND
   (Or: Settings > System > Optional features > View features > OpenSSH Server > Install.)

2. Check that it runs:
   Get-Service sshd
   It should say Running.

3. Find the addresses:
   ipconfig
   "IPv4 Address" (like 192.168.1.20) is the home network address.
   With Tailscale on the PC and the phone, the PC's 100.x.x.x address also works away from home.

4. Find the user name:
   whoami
   The part after the \ is the user name. The password is your Windows password.
   With a Microsoft account, use the account password, not the PIN.

5. On the phone: Hey Mike > side panel > Computer > Add a computer.
""".trimIndent()

/** The Ubuntu guide as plain text to send to the computer. */
private val UBUNTU_STEPS_TEXT = """
Hey Mike: let Mike work on this Ubuntu computer

1. Turn on the SSH server. In a terminal:
   $UBUNTU_SSH_COMMAND
   If the firewall is on: sudo ufw allow ssh

2. Check that it runs:
   systemctl is-active ssh
   It should say active.

3. Find the addresses:
   hostname -I
   The first address (like 192.168.1.20) is the home network address.
   With Tailscale on the computer and the phone, its 100.x.x.x address also works away from home.

4. Find the user name:
   whoami
   The password is your Ubuntu login password.
   Password sign-in over SSH must be on; it is on Ubuntu desktop by default.

5. On the phone: Hey Mike > side panel > Computer > Add a computer.
""".trimIndent()

private fun stepsText(os: dev.androidagent.remote.HostOs) =
    if (os == dev.androidagent.remote.HostOs.LINUX) UBUNTU_STEPS_TEXT else SETUP_STEPS_TEXT

/**
 * Computers Mike can work on, on a screen of its own: the list, adding or
 * editing one (the PC's setup first, then the sign-in), and the folder picker
 * a project is started from. A full screen, so scrolling a long folder list
 * never closes it; Back steps back one view.
 */
@Composable
internal fun ComputersSheet(state: AgentUiState, actions: AgentUiActions) {
    var draft by remember { mutableStateOf<ComputerDraft?>(null) }
    // A new computer starts on the PC's setup steps; an edit skips them.
    var onSetupStep by remember { mutableStateOf(false) }
    var guideOs by remember { mutableStateOf(dev.androidagent.remote.HostOs.WINDOWS) }
    val back: () -> Unit = {
        when {
            state.folderBrowser != null -> actions.onCloseFolderBrowser()
            draft != null && !onSetupStep && draft?.id == null -> onSetupStep = true
            draft != null -> draft = null
            else -> actions.onCloseComputers()
        }
    }
    // Mike filled in a computer: straight to the form, for the user to check.
    LaunchedEffect(state.computerProposal) {
        val proposal = state.computerProposal ?: return@LaunchedEffect
        draft = proposal
        onSetupStep = false
        actions.onComputerProposalShown()
    }
    // A layer over the app rather than a dialog window: it takes the app's
    // own edge-to-edge insets, so the bottom button clears the gesture bar,
    // and Back steps back one view.
    BackHandler(onBack = back)
    run {
        Surface(Modifier.fillMaxSize(), color = SheetFill, contentColor = Ink) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp, bottom = 12.dp)) {
                val browser = state.folderBrowser
                val editing = draft
                when {
                    browser != null -> FolderPicker(state, browser, actions)
                    editing != null && onSetupStep -> SetupStep(
                        os = guideOs,
                        onOs = { guideOs = it },
                        onShare = { actions.onShareText(stepsText(guideOs)) },
                        onBack = back,
                        onDone = { onSetupStep = false },
                    )
                    editing != null -> ComputerForm(
                        initial = editing,
                        onSetupSteps = { onSetupStep = true },
                        onCancel = back,
                        onSave = { actions.onSaveComputer(it); draft = null },
                    )
                    else -> ComputerList(
                        state = state,
                        actions = actions,
                        onClose = actions.onCloseComputers,
                        onAdd = { draft = ComputerDraft(isDefault = state.computers.isEmpty()); onSetupStep = true },
                        onEdit = { c ->
                            draft = ComputerDraft(
                                id = c.id, label = c.label, host = c.host, vpnHost = c.vpnHost.orEmpty(), port = c.port.toString(),
                                user = c.user, access = c.access, isDefault = c.id == state.defaultComputerId,
                            )
                            onSetupStep = false
                        },
                    )
                }
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
            Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = Muted)
        }
    }
}

@Composable
private fun ColumnScope.ComputerList(
    state: AgentUiState,
    actions: AgentUiActions,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (RemoteComputer) -> Unit,
) {
    SheetHeader("Computers", "Mike runs Codex on your Windows or Ubuntu computer and works in a folder you pick.", onBack = onClose)
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (state.computersUnreadable) {
            Text(
                "Saved computers could not be opened on this phone, so none of them is trusted. Add them again.",
                fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (state.computers.isEmpty()) {
            EmptyComputers(onAdd)
            return@Column
        }
        // The default first: it is the one a chat opens on when none is picked.
        state.computers.sortedByDescending { it.id == state.defaultComputerId }.forEach { computer ->
            ComputerCard(
                computer = computer,
                isDefault = computer.id == state.defaultComputerId,
                setup = state.computerSetup[computer.id],
                actions = actions,
                onEdit = onEdit,
            )
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add another computer")
        }
    }
}

@Composable
private fun EmptyComputers(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CardFill, RoundedCornerShape(16.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Computer, contentDescription = null, tint = ReadyInk, modifier = Modifier.size(36.dp))
        Text("No computers yet", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Add your Windows or Ubuntu computer and Mike can read, edit and run code there, in chats organized by project. " +
                "You need a few minutes at the computer once, to turn on SSH. The next screen shows how.",
            fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a computer")
        }
    }
}

@Composable
private fun ComputerCard(
    computer: RemoteComputer,
    isDefault: Boolean,
    setup: RemoteSetup?,
    actions: AgentUiActions,
    onEdit: (RemoteComputer) -> Unit,
) {
    var confirmRemove by rememberSaveable(computer.id) { mutableStateOf(false) }
    val busy = setup is RemoteSetup.Working
    val outline = if (isDefault) ReadyInk.copy(alpha = 0.45f) else Hairline
    Column(
        Modifier.fillMaxWidth()
            .background(CardFill, RoundedCornerShape(16.dp))
            .border(1.dp, outline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Computer, contentDescription = null, tint = ReadyInk, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    computer.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) DefaultPill(Modifier.padding(start = 8.dp))
            }
            IconButton(onClick = { onEdit(computer) }, enabled = !busy) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit ${computer.label}", tint = Muted)
            }
            IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove ${computer.label}", tint = Muted)
            }
        }
        Column(Modifier.padding(start = 32.dp)) {
            val port = if (computer.port == 22) "" else ":${computer.port}"
            if (computer.host.isNotBlank()) DetailLine("Home", computer.host + port)
            computer.vpnHost?.takeIf { it.isNotBlank() }?.let { DetailLine("VPN", it + port) }
            computer.os?.let { DetailLine("System", it.label) }
            DetailLine("User", computer.user)
            DetailLine(
                "Access",
                when (computer.access) {
                    RemoteAccess.ASK -> "Asks before anything outside the project folder"
                    RemoteAccess.FULL -> "Full access, no questions"
                },
            )
            computer.fingerprint?.let {
                Text("Host key $it", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        SetupLine(computer, setup, actions)
        Button(
            onClick = { actions.onConnectComputer(computer.id) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New project")
        }
        if (!isDefault) {
            TextButton(onClick = { actions.onSetDefaultComputer(computer.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Make this the default")
            }
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
private fun DefaultPill(modifier: Modifier = Modifier) {
    Text(
        "Default",
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ReadyInk,
        modifier = modifier.background(ReadyInk.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailLine(name: String, value: String) {
    Row(Modifier.padding(top = 2.dp)) {
        Text(name, fontSize = 12.5.sp, color = Muted, modifier = Modifier.width(52.dp))
        Text(value, fontSize = 12.5.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SetupLine(computer: RemoteComputer, setup: RemoteSetup?, actions: AgentUiActions) {
    when (setup) {
        null -> Unit
        is RemoteSetup.Working -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(setup.step, fontSize = 14.sp, color = Ink)
        }
        is RemoteSetup.Ready -> Text(
            buildString {
                append("Connected to ${setup.probe.computerName.ifBlank { computer.label }}")
                setup.route?.let { append(if (it.viaVpn) " over the VPN" else " on the home network") }
                append(" · Codex signed in as ${setup.account}")
            },
            fontSize = 13.sp, lineHeight = 18.sp, color = ReadyInk, modifier = Modifier.padding(top = 12.dp),
        )
        is RemoteSetup.Failed -> Column(Modifier.padding(top = 12.dp)) {
            Text(setup.message, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
            var help by rememberSaveable(computer.id) { mutableStateOf(false) }
            TextButton(onClick = { help = !help }) { Text(if (help) "Hide the checklist" else "What to check on the computer") }
            val os = computer.os ?: dev.androidagent.remote.HostOs.WINDOWS
            if (help) PcChecklist(os, onShare = { actions.onShareText(stepsText(os)) })
        }
        is RemoteSetup.NeedsTailscaleApproval -> TailscaleApprovalCard(
            computer = computer.label,
            url = setup.url,
            onOpen = { url -> actions.onOpenTailscaleApproval(computer.id, url) },
            onConnect = { actions.onConnectComputer(computer.id) },
            modifier = Modifier.padding(top = 12.dp),
        )
        is RemoteSetup.NeedsSignIn -> Column(Modifier.padding(top = 12.dp)) {
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

/** Step one of adding a computer: what has to be true on it first, for Windows or Ubuntu. */
@Composable
private fun ColumnScope.SetupStep(
    os: dev.androidagent.remote.HostOs,
    onOs: (dev.androidagent.remote.HostOs) -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    SheetHeader("Get the computer ready", "Step 1 of 2 · once per computer, a few minutes at it", onBack = onBack)
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            listOf(dev.androidagent.remote.HostOs.WINDOWS to "Windows", dev.androidagent.remote.HostOs.LINUX to "Ubuntu").forEach { (value, label) ->
                FilterChip(selected = os == value, onClick = { onOs(value) }, label = { Text(label) })
            }
        }
        PcChecklist(os, onShare)
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).heightIn(min = 48.dp)) {
        Text("The computer is ready")
    }
}

@Composable
private fun PcChecklist(os: dev.androidagent.remote.HostOs, onShare: () -> Unit) {
    if (os == dev.androidagent.remote.HostOs.LINUX) UbuntuChecklist(onShare) else WindowsChecklist(onShare)
}

@Composable
private fun UbuntuChecklist(onShare: () -> Unit) {
    SetupStep(1, "Turn on the SSH server", "In a terminal. If the firewall is on, also run: sudo ufw allow ssh") {
        CodeLine(UBUNTU_SSH_COMMAND)
    }
    SetupStep(2, "Check that it runs", "It should say active.") { CodeLine("systemctl is-active ssh") }
    SetupStep(
        3, "Find the addresses",
        "The first address (like 192.168.1.20) is the home network address. With Tailscale on the computer and the phone, " +
            "its 100.x.x.x address also works away from home.",
    ) { CodeLine("hostname -I") }
    SetupStep(
        4, "Find the user name",
        "The password is your Ubuntu login password. Password sign-in over SSH must be on; it is on Ubuntu desktop by default.",
    ) { CodeLine("whoami") }
    SetupStep(
        5, "Nothing else to install",
        "Mike installs Codex on the computer the first time it connects and uses your Codex sign-in there, " +
            "or shows a code to sign in.",
    )
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 44.dp)) {
        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Send these steps to the computer")
    }
}

@Composable
private fun WindowsChecklist(onShare: () -> Unit) {
    SetupStep(1, "Turn on OpenSSH Server", "Run it in PowerShell as administrator. Or: Settings › System › Optional features › View features › OpenSSH Server › Install.") {
        CodeLine(INSTALL_SSH_COMMAND)
    }
    SetupStep(2, "Check that it runs", "It should say Running.") { CodeLine("Get-Service sshd") }
    SetupStep(
        3, "Find the addresses",
        "\"IPv4 Address\" (like 192.168.1.20) is the home network address. With Tailscale on the PC and the phone, " +
            "the PC's 100.x.x.x address also works away from home.",
    ) { CodeLine("ipconfig") }
    SetupStep(
        4, "Find the user name",
        "The part after the \\ is the user name. The password is your Windows password. " +
            "With a Microsoft account, use the account password, not the PIN.",
    ) { CodeLine("whoami") }
    SetupStep(
        5, "Nothing else to install",
        "Mike installs Codex on the PC the first time it connects (about 120 MB) and uses your Codex sign-in there, " +
            "or shows a code to sign in.",
    )
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 44.dp)) {
        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Send these steps to the PC")
    }
}

@Composable
private fun SetupStep(number: Int, title: String, detail: String, code: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).background(ReadyInk.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ReadyInk)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
            code?.let { Box(Modifier.padding(vertical = 4.dp)) { it() } }
            Text(detail, fontSize = 12.5.sp, lineHeight = 17.sp, color = Muted)
        }
    }
}

/** A command to type on the PC, with a copy button. */
@Composable
private fun CodeLine(command: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().background(CodeFill, RoundedCornerShape(10.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(command, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = Ink, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy command", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ColumnScope.ComputerForm(
    initial: ComputerDraft,
    onSetupSteps: () -> Unit,
    onCancel: () -> Unit,
    onSave: (ComputerDraft) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val editing = initial.id != null
    SheetHeader(
        if (editing) "Edit computer" else "Connect",
        if (editing) "The sign-in is kept sealed on this phone." else "Step 2 of 2 · the sign-in is kept sealed on this phone",
        onBack = onCancel,
    )
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (initial.proposedByMike) {
            Text(
                "Mike filled this in. Check that ${initial.host.ifBlank { initial.vpnHost }} is your computer before you type " +
                    "your password: a password typed here goes to that address.",
                fontSize = 13.sp, lineHeight = 18.sp, color = WaitInk,
                modifier = Modifier.fillMaxWidth().background(WaitInk.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        TextButton(onClick = onSetupSteps, modifier = Modifier.padding(start = 0.dp)) { Text("How to set up the computer") }

        FormSection("Where to reach it")
        Text("Fill in one address or both.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = draft.host, onValueChange = { draft = draft.copy(host = it.trim()) },
            label = { Text("Home network address") }, placeholder = { Text("192.168.1.20") }, singleLine = true,
            supportingText = { Text("From ipconfig (Windows) or hostname -I (Ubuntu). Works on the same Wi-Fi.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.vpnHost, onValueChange = { draft = draft.copy(vpnHost = it.trim()) },
            label = { Text("VPN address") }, placeholder = { Text("100.64.0.5") }, singleLine = true,
            supportingText = { Text("Such as Tailscale. Works away from home too.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        FormSection("Sign-in on the computer")
        OutlinedTextField(
            value = draft.user, onValueChange = { draft = draft.copy(user = it) },
            label = { Text("User name") }, singleLine = true,
            supportingText = { Text("From whoami. On Windows, the part after the \\.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.password, onValueChange = { draft = draft.copy(password = it) },
            label = { Text("Password") }, singleLine = true,
            supportingText = {
                Text(if (editing) "Leave empty to keep the saved password." else "The computer's login password. On Windows with a Microsoft account, its password, not the PIN.")
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        FormSection("This computer in Mike")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.label, onValueChange = { draft = draft.copy(label = it) },
                label = { Text("Name") }, placeholder = { Text("Desk PC") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.port, onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit).take(5)) },
                label = { Text("SSH port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(104.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp)
                .toggleable(value = draft.isDefault, enabled = !initial.isDefault, role = Role.Switch) { draft = draft.copy(isDefault = it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Default computer", fontSize = 15.sp, color = Ink)
                Text(
                    when {
                        initial.isDefault && !editing -> "Your first computer is the default."
                        initial.isDefault -> "This is the default. Make another computer the default to change it."
                        else -> "Used when you ask Mike for a computer without naming one."
                    },
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = Muted,
                )
            }
            Switch(checked = draft.isDefault, onCheckedChange = null, enabled = !initial.isDefault)
        }

        FormSection("What Mike may do there")
        AccessOption(
            selected = draft.access == RemoteAccess.ASK,
            title = "Ask me first (recommended)",
            detail = "Edits and commands inside the project folder run. Anything else waits for your answer on the phone.",
        ) { draft = draft.copy(access = RemoteAccess.ASK) }
        AccessOption(
            selected = draft.access == RemoteAccess.FULL,
            title = "Full access",
            detail = "Mike runs any command as your user on the computer without asking.",
        ) { draft = draft.copy(access = RemoteAccess.FULL) }

        val missing = buildList {
            if (draft.host.isBlank() && draft.vpnHost.isBlank()) add("an address")
            if (draft.user.isBlank()) add("the user name")
            if (!editing && draft.password.isEmpty()) add("the password")
        }
        if (missing.isNotEmpty()) {
            Text(
                "Still needed: ${missing.joinToString(", ")}.",
                fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(onClick = { onSave(draft) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
            Text(if (editing) "Save and connect" else "Connect")
        }
    }
}

@Composable
private fun FormSection(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun AccessOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
        Column {
            Text(title, fontSize = 15.sp, color = Ink)
            Text(detail, fontSize = 12.5.sp, lineHeight = 17.sp, color = Muted)
        }
    }
}

@Composable
private fun ColumnScope.FolderPicker(state: AgentUiState, browser: FolderBrowserState, actions: AgentUiActions) {
    val computer = state.computers.firstOrNull { it.id == browser.computerId }
    val listing = browser.listing
    SheetHeader("New project on ${computer?.label ?: "the computer"}", "Pick the project's folder. It stays in the side panel.", onBack = actions.onCloseFolderBrowser)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listing?.path ?: "Loading…",
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink,
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
    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
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
        Text("Start ${listing?.path?.let(::folderName) ?: "this folder"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderRow(name: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(CardFill, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A child path in the computer's style: `C:\src` + `app` is `C:\src\app`, `/home/me` + `app` is `/home/me/app`. */
internal fun childPath(parent: String, name: String): String {
    val separator = if (parent.startsWith("/")) "/" else "\\"
    return if (parent.endsWith("\\") || parent.endsWith("/")) parent + name else "$parent$separator$name"
}

internal fun folderName(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
