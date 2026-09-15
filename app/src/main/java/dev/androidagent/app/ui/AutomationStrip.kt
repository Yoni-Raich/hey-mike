package dev.androidagent.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androidagent.core.AutomationOverview
import dev.androidagent.core.AutomationSummary

/** On and able to run. The same teal the top bar uses for a live backend. */
internal val RuleReady = Color(0xFF83D9CA)

/**
 * On, and cannot run.
 *
 * The same amber the status orb uses for a blocked backend, because it is the
 * same kind of fact: something is switched on and the phone will not serve it.
 */
internal val RuleBlocked = Color(0xFFF6B86A)

/** Off, because the user said so. */
internal val RuleOff = Color(0xFF8F8F8F)

/**
 * The amber chip's fill and edge.
 *
 * The one pair of values this design adds. The scheme defines `errorContainer`
 * for red and nothing for amber, so these are derived the same way: a very dark
 * tint of the signal colour, with an edge one step up.
 */
private val BlockedContainer = Color(0xFF241E14)
private val BlockedOutline = Color(0xFF4A3A24)

/**
 * The standing rules, above the chats, as a glance.
 *
 * Its whole job is to answer "is it working" before anyone taps anything, which
 * is also the constraint: it may not grow. At most
 * [AutomationOverview.MAX_CHIPS] chips and exactly one sentence, however many
 * rules exist — everything else lives behind [onOpen]. Which chips and which
 * sentence are decided in `:core`, so this renders and does not choose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomationStrip(
    overview: AutomationOverview,
    onOpen: () -> Unit,
    onOpenRule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        onClick = onOpen,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = RuleReady,
                )
                Text(
                    "Running for you",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (overview.enabled > 0) {
                    Text(
                        overview.enabled.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (overview.chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    overview.chips.forEach { rule -> RuleChip(rule) { onOpenRule(rule.id) } }
                    if (overview.hidden > 0) MoreChip(overview.hidden, onOpen)
                }
            }

            // One sentence, and always the most useful true thing: `:core`
            // chose between a rule that cannot run and the next thing due.
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(6.dp)
                        .let { it },
                ) {
                    Surface(shape = CircleShape, color = if (overview.lineIsWarning) RuleBlocked else RuleOff) {
                        Box(Modifier.size(6.dp))
                    }
                }
                Text(
                    overview.line,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overview.lineIsWarning) RuleBlocked else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RuleChip(rule: AutomationSummary, onClick: () -> Unit) {
    val blocked = rule.status == AutomationSummary.Status.BLOCKED
    val off = rule.status == AutomationSummary.Status.OFF
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (blocked) BlockedContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (blocked) BorderStroke(1.dp, BlockedOutline) else null,
        onClick = onClick,
        // The chip is 34dp tall by design; this is what keeps the target 48dp
        // without growing it.
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "${rule.chipName}. ${rule.detail}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(rule.status)
            Text(
                rule.chipName,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    blocked -> RuleBlocked
                    off -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoreChip(hidden: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "$hidden more rules" },
    ) {
        Text(
            "+$hidden",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** The one mark that carries a rule's state, used by the strip, the list and the sheet. */
@Composable
internal fun StatusDot(status: AutomationSummary.Status, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Surface(
        shape = CircleShape,
        color = when (status) {
            AutomationSummary.Status.BLOCKED -> RuleBlocked
            AutomationSummary.Status.ON -> RuleReady
            AutomationSummary.Status.OFF -> RuleOff
        },
    ) {
        Spacer(Modifier.size(size))
    }
}
