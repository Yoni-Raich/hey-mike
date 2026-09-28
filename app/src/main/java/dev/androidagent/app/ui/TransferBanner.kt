package dev.androidagent.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.FileTransfer
import kotlinx.coroutines.delay

private val TransferInk = Color(0xFFE8C9A0)
private val TransferTrack = Color(0xFF2A2724)
private val TransferDone = Color(0xFF83D9CA)

/**
 * A file on its way between a computer and the phone: which file, which
 * way, how far, how fast and how long is left. It lands with a check for a
 * moment, then folds away.
 */
@Composable
internal fun TransferBanner(transfer: FileTransfer?, modifier: Modifier = Modifier) {
    // Keeps the last transfer drawn while the banner folds away.
    val shown = remember { mutableListOf<FileTransfer>() }
    if (transfer != null) { shown.clear(); shown += transfer }
    AnimatedVisibility(
        visible = transfer != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        shown.firstOrNull()?.let { TransferCard(it) }
    }
}

@Composable
private fun TransferCard(transfer: FileTransfer) {
    // The rate and time left read the clock, which moves between updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transfer.state) {
        while (transfer.state == FileTransfer.State.MOVING) { now = System.currentTimeMillis(); delay(500) }
    }
    val fraction by animateFloatAsState(transfer.fraction ?: 0f, tween(250), label = "transfer")
    val failed = transfer.state == FileTransfer.State.FAILED
    val done = transfer.state == FileTransfer.State.DONE
    val direction = "${transfer.from} → ${transfer.to}"
    val spoken = when {
        done -> "${transfer.name} copied, $direction"
        failed -> "${transfer.name} not copied: ${transfer.error.orEmpty()}"
        else -> "Copying ${transfer.name}, $direction, ${((transfer.fraction ?: 0f) * 100).toInt()} percent"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1C1B19),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Route(transfer.from, transfer.to, done, failed)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(transfer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            done -> "Copied · $direction"
                            failed -> "Not copied · ${transfer.error ?: "failed"}"
                            else -> direction
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when { done -> TransferDone; failed -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!failed) {
                    Text(
                        if (done) sizeLabel(transfer.total) else "${((transfer.fraction ?: 0f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (done) TransferDone else TransferInk,
                    )
                }
            }
            if (!done && !failed) {
                Spacer(Modifier.height(10.dp))
                if (transfer.fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = TransferInk, trackColor = TransferTrack)
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = TransferInk, trackColor = TransferTrack, gapSize = 0.dp, drawStopIndicator = {},
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        if (transfer.total > 0) "${sizeLabel(transfer.bytes)} of ${sizeLabel(transfer.total)}" else sizeLabel(transfer.bytes),
                        transfer.rate(now)?.let { "${sizeLabel(it)}/s" },
                        transfer.secondsLeft(now)?.let { timeLeft(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where from and where to, with a dot travelling along the arrow while it moves. */
@Composable
private fun Route(from: String, to: String, done: Boolean, failed: Boolean) {
    val travel by rememberInfiniteTransition(label = "route").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "route-dot",
    )
    Box(Modifier.size(width = 64.dp, height = 36.dp).background(Color(0xFF26241F), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        when {
            done -> Icon(Icons.Outlined.Check, null, tint = TransferDone, modifier = Modifier.size(20.dp))
            failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(placeIcon(from), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.width(22.dp).height(15.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.offset(x = (travel * 16).dp).size(5.dp).background(TransferInk, RoundedCornerShape(3.dp)))
                }
                Icon(placeIcon(to), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The phone and this chat's folder are on the phone; any other place is a computer. */
private fun placeIcon(place: String) =
    if (place == "phone" || place == "this chat") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10} KB"
    else -> "$bytes B"
}

internal fun timeLeft(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s left"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
    else -> "${seconds / 3600}h ${seconds % 3600 / 60}m left"
}
