package dev.androidagent.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.androidagent.app.AgentApplication
import dev.androidagent.app.BuildConfig
import dev.androidagent.core.ConnectedAppState
import dev.androidagent.core.ConnectHistory
import dev.androidagent.core.ConnectProvider
import dev.androidagent.core.ConnectionGrant
import dev.androidagent.core.connectedAppStatus
import dev.androidagent.core.connectedAppsSummary
import dev.androidagent.core.policyHash
import dev.androidagent.core.reviewedConnectionGrant
import dev.heymike.connect.Access
import dev.heymike.connect.Capability
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Settings hub row's second line: whether any app is on. */
@Composable internal fun connectedAppsHubSummary(): String {
    val app = LocalContext.current.applicationContext as? AgentApplication ?: return connectedAppsSummary(emptyList())
    val grants by app.graph.connectedApps.store.grants.collectAsState()
    return connectedAppsSummary(grants)
}

@Composable internal fun ConnectedAppsSettings() {
    val graph = (LocalContext.current.applicationContext as AgentApplication).graph
    val controller = graph.connectedApps
    val view by controller.view.collectAsState()
    val grants by controller.store.grants.collectAsState()
    val history by controller.history.collectAsState()
    var confirming by remember { mutableStateOf<ConnectProvider?>(null) }
    var developer by remember { mutableStateOf(false) }

    // Look again whenever the user comes back to this screen, so an app installed a minute ago shows up.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Text(
        "Choose which apps Mike can use. Each app decides what it shares, and you can turn any of them off at any time.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    view.error?.takeIf { view.errorPackage == null }?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = controller::refresh) { Text("Try again") }
    }
    if (view.providers.isEmpty() && view.error == null) NoAppsYet()

    for (app in view.providers) {
        val caps = view.catalogs[app.packageName]
        val status = connectedAppStatus(app, grants, caps)
        val busy = view.loading == app.packageName
        ConnectedAppCard(
            app = app,
            status = status,
            caps = caps,
            grant = grants.find { it.packageName == app.packageName && it.signer == app.signer },
            busy = busy,
            error = view.error?.takeIf { view.errorPackage == app.packageName },
            recent = history.filter { it.packageName == app.packageName }.takeLast(5).asReversed(),
            developer = developer,
            onTurnOn = {
                if (caps != null) confirming = app else controller.review(app) { confirming = app }
            },
            onTurnOff = { controller.turnOff(app) },
            onRetry = { controller.review(app) },
            onLoadDetails = { if (caps == null) controller.review(app) },
        )
    }

    if (BuildConfig.DEBUG) Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = developer, onCheckedChange = { developer = it })
        Text("Developer tools", style = MaterialTheme.typography.bodyMedium)
    }

    confirming?.let { app ->
        val caps = view.catalogs[app.packageName]
        if (caps == null) confirming = null else TurnOnDialog(
            app = app,
            caps = caps,
            previous = grants.find { it.packageName == app.packageName && it.signer == app.signer },
            publisherChanged = connectedAppStatus(app, grants, caps).state == ConnectedAppState.PUBLISHER_CHANGED,
            onConfirm = { controller.enable(app, true); confirming = null },
            onDismiss = { confirming = null },
        )
    }
}

@Composable private fun NoAppsYet() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("No apps to connect yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Apps that support Mike Connect appear here as soon as they are installed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable private fun ConnectedAppCard(
    app: ConnectProvider,
    status: dev.androidagent.core.ConnectedAppStatus,
    caps: List<Capability>?,
    grant: ConnectionGrant?,
    busy: Boolean,
    error: String?,
    recent: List<ConnectHistory>,
    developer: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onRetry: () -> Unit,
    onLoadDetails: () -> Unit,
) {
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    val on = status.isOn
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The whole row is the switch: one big target, read out as "<app>, on".
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("connected-app-" + app.packageName)
                    .toggleable(value = on, enabled = !busy, role = Role.Switch, onValueChange = { if (it) onTurnOn() else onTurnOff() }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(app)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        statusLine(app, status, busy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.state == ConnectedAppState.PUBLISHER_CHANGED) MaterialTheme.colorScheme.error
                        else if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Switch(checked = on, onCheckedChange = null)
            }
            if (app.summary.isNotBlank()) {
                Text(app.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error != null) {
                Text("Could not reach ${app.label}. $error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            val waiting = status.waiting ?: 0
            if (on && waiting > 0) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$waiting new ${if (waiting == 1) "ability" else "abilities"} from this app, off until you allow",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(onClick = { expanded = true; onLoadDetails() }) { Text("Review") }
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded; if (expanded) onLoadDetails() }) {
                Text(if (expanded) "Hide details" else "Details")
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
            if (expanded) ConnectedAppDetails(app, caps, grant, on, recent, developer)
        }
    }
}

private fun statusLine(app: ConnectProvider, status: dev.androidagent.core.ConnectedAppStatus, busy: Boolean): String = when {
    busy -> "Checking what ${app.label} offers…"
    status.state == ConnectedAppState.PUBLISHER_CHANGED -> "Publisher changed. Check the app before turning it on."
    status.isOn -> "On · Mike can use " + abilities(status.allowed)
    else -> "Off"
}

private fun abilities(count: Int) = if (count == 1) "1 ability" else "$count abilities"

@Composable private fun AppIcon(app: ConnectProvider) {
    val context = LocalContext.current
    val image = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(112, 112).asImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(12.dp)
    if (image != null) {
        Image(image, contentDescription = null, modifier = Modifier.size(48.dp).clip(shape))
    } else {
        Box(Modifier.size(48.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable private fun ConnectedAppDetails(
    app: ConnectProvider,
    caps: List<Capability>?,
    grant: ConnectionGrant?,
    on: Boolean,
    recent: List<ConnectHistory>,
    developer: Boolean,
) {
    val controller = ((LocalContext.current.applicationContext) as AgentApplication).graph.connectedApps
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Publisher " + app.signer.take(16).chunked(4).joinToString(" "), style = MaterialTheme.typography.labelSmall)
        Text("Installed by " + (app.installer ?: "a local installer"), style = MaterialTheme.typography.labelSmall)
        if (caps == null) {
            Text("Reading what this app offers…", style = MaterialTheme.typography.bodyMedium)
        } else {
            if (caps.isEmpty()) Text("This app does not offer anything yet.", style = MaterialTheme.typography.bodyMedium)
            if (caps.isNotEmpty() && !on) {
                Text("Turn the app on to choose what Mike may do.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (cap in caps) {
                val saved = grant?.capabilities?.find { it.name == cap.name && it.policyHash == cap.policyHash() }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cap.title, style = MaterialTheme.typography.titleSmall)
                        Text(accessLabel(cap.access), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = on && saved?.enabled == true,
                        enabled = on,
                        onCheckedChange = { controller.capability(app, cap, enabled = it) },
                        modifier = Modifier.semantics { contentDescription = "Allow " + cap.title + " in " + app.label },
                    )
                }
                Text(cap.description, style = MaterialTheme.typography.bodySmall)
                if (on && saved == null) {
                    Text("New or changed since you last looked. Off until you turn it on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (on && cap.access == Access.WRITE && saved?.enabled == true) {
                    Row(Modifier.toggleable(value = saved.askFirst, role = Role.Checkbox, onValueChange = { controller.capability(app, cap, ask = it) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saved.askFirst, onCheckedChange = null)
                        Text("Ask me before this change", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (developer) DeveloperTry(app, cap, on) { arguments ->
                    withContext(Dispatchers.IO) { controller.tryCapability(app, cap, arguments).take(20_000) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            HorizontalDivider()
            Text("Recent activity", style = MaterialTheme.typography.titleSmall)
            recent.forEach {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)) + " · " + it.capability + " · " + it.outcome,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable private fun DeveloperTry(
    app: ConnectProvider,
    cap: Capability,
    on: Boolean,
    run: suspend (String) -> String,
) {
    var input by remember(app.packageName, cap.name) { mutableStateOf("{}") }
    var output by remember(app.packageName, cap.name) { mutableStateOf("") }
    var running by remember(app.packageName, cap.name) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = input, onValueChange = { input = it.take(10_000) },
        label = { Text("Test arguments (JSON)") }, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(enabled = !running && on, onClick = {
        running = true
        scope.launch { try { output = run(input) } finally { running = false } }
    }) { Text(if (running) "Running…" else "Try ability") }
    if (output.isNotBlank()) Text(output, style = MaterialTheme.typography.bodySmall)
}

private fun accessLabel(access: Access) = when (access) {
    Access.READ -> "Only looks things up"
    Access.WRITE -> "Can make changes"
    Access.SENSITIVE -> "Always asks you first"
}

/**
 * The one place the user says yes. It lists what the app offers, grouped by what it can do,
 * so turning an app on is an informed choice made in a single tap rather than a separate review step.
 */
@Composable private fun TurnOnDialog(
    app: ConnectProvider,
    caps: List<Capability>,
    previous: ConnectionGrant?,
    publisherChanged: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val willBeOn = reviewedConnectionGrant(app, caps, previous, true).capabilities.associate { it.name to it.enabled }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let Mike use ${app.label}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (publisherChanged) {
                    Text(
                        "This app is now signed by a different publisher than the one you trusted before. Continue only if you expected that.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (caps.isEmpty()) Text("This app does not offer anything yet.", style = MaterialTheme.typography.bodyMedium)
                for ((access, heading) in listOf(
                    Access.READ to "Look things up",
                    Access.WRITE to "Make changes",
                    Access.SENSITIVE to "Ask you first, every time",
                )) {
                    val group = caps.filter { it.access == access }
                    if (group.isEmpty()) continue
                    Text(heading, style = MaterialTheme.typography.titleSmall)
                    for (cap in group) {
                        Column {
                            Text(cap.title + if (willBeOn[cap.name] == false) " (stays off)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(cap.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
                Text(
                    "You can turn this off at any time. Changes Mike makes show up in the chat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
