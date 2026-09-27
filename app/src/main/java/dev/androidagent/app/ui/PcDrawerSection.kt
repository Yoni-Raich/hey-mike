package dev.androidagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Shared drawer surfaces and connection recovery cards.
internal val DrawerSurface = Color(0xFF161618)
internal val DrawerMuted = Color(0xFF9A9A9E)
private val PcReady = Color(0xFF83D9CA)

@Composable
internal fun DrawerSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = DrawerMuted,
        modifier = modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
internal fun PcProblemCard(
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                secondary?.let {
                    TextButton(onClick = onSecondary) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
                Button(
                    onClick = onPrimary,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color(0xFF561E19)),
                ) { Text(primary) }
            }
        }
    }
}

@Composable
internal fun QuietRow(
    text: String,
    icon: ImageVector? = null,
    indent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = if (indent) 44.dp else 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = PcReady, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = PcReady)
        }
    }
}

/**
 * Tailscale SSH answered instead of the computer's own SSH server: not an
 * error, a step. It signs in with the user's Tailscale account, so the
 * user approves once in the browser and connects again.
 */
@Composable
internal fun TailscaleApprovalCard(
    computer: String,
    url: String?,
    onOpen: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = PcReady, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Approve this phone in Tailscale", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (url != null) {
                    "$computer signs in with your Tailscale account instead of a password. Approve once on the Tailscale page; coming back here connects."
                } else {
                    "$computer signs in with your Tailscale account instead of a password, and its Tailscale rules do not let this phone in yet."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, start = 34.dp),
            )
            // Stacked, full width: side by side they squeezed each other on a phone.
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (url != null) {
                    Button(
                        onClick = { onOpen(url) },
                        colors = ButtonDefaults.buttonColors(containerColor = PcReady, contentColor = Color(0xFF003731)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open approval page", maxLines = 1)
                    }
                }
                TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (url != null) "Already approved? Connect" else "Connect again", color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
                }
            }
            Text(
                "Rather use a password? On the computer: sudo tailscale set --ssh=false",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp, start = 34.dp),
            )
        }
    }
}
