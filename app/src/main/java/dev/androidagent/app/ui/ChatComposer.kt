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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.AgentSkill
import dev.androidagent.core.ReasoningEffortOption
import dev.androidagent.core.RunPhase
import dev.androidagent.core.RunState
import dev.androidagent.core.VoicePhase

// The composer: one rounded field and one button that is voice while the
// field is empty, send once there is text, and stop while the agent works.
// Steering a running agent keeps a separate stop beside the send button, so
// Stop is never more than one tap away. Model and reasoning share one chip
// under the field, which opens a sheet.

private val FieldBorder = Color(0xFF383838)
private val FieldBorderFocused = Color(0xFF4A4A4A)
private val ChipBorder = Color(0xFF333333)
private val ChipInk = Color(0xFFCFCFCF)
private val SheetFill = Color(0xFF1B1B1B)
private val OptionFill = Color(0xFF252525)
private val AttachmentFill = Color(0xFF2A2A2A)
private val MutedInk = Color(0xFF8F8F8F)

private enum class ComposerAction { VOICE, SEND, STEER, STOP }

@Composable
internal fun AgentComposer(
    state: AgentUiState,
    actions: AgentUiActions,
    // Where the voice button sits, so voice mode can grow out of it.
    onVoiceButtonPlaced: (Offset) -> Unit = {},
) {
    var draft by rememberSaveable(state.activeSessionId) { mutableStateOf("") }
    // A task Mike wrote for this chat waits here for the user to send.
    val seed = state.activeSessionId?.let(state.composerSeeds::get)
    LaunchedEffect(state.activeSessionId, seed) {
        val id = state.activeSessionId ?: return@LaunchedEffect
        if (seed != null) {
            draft = seed
            actions.onComposerSeedUsed(id)
        }
    }
    var choosingModel by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    // A skill picked from the menu rides as a chip until the message is sent.
    var skill by remember(state.activeSessionId) { mutableStateOf<AgentSkill?>(null) }
    var renaming by remember(state.activeSessionId) { mutableStateOf(false) }
    val voiceActive = state.voiceState.active
    val active = state.runState.active && state.runState.sessionId == state.activeSessionId && !voiceActive
    val stopping = state.runState.phase == RunPhase.STOPPING && !voiceActive
    val voiceStopping = state.voiceState.phase == VoicePhase.STOPPING
    val voiceBusy = state.voiceState.phase in setOf(VoicePhase.STARTING, VoicePhase.STOPPING)
    val hasDraft = draft.isNotBlank()
    val canSend = state.activeSessionId != null && hasDraft && !state.isLoadingMessages && !stopping && !voiceStopping
    val action = when {
        active && hasDraft -> ComposerAction.STEER
        active -> ComposerAction.STOP
        hasDraft -> ComposerAction.SEND
        !state.runState.active || voiceActive -> ComposerAction.VOICE
        // Another chat is running; a message typed here waits for it.
        else -> ComposerAction.SEND
    }
    val runCommand: (ComposerCommand) -> Unit = { command ->
        draft = ""
        when (command) {
            ComposerCommand.NEW -> actions.onNewChat()
            ComposerCommand.COMPACT -> actions.onCompact()
            ComposerCommand.PLAN -> actions.onTogglePlanMode()
            ComposerCommand.MODEL -> if (state.availableModels.isEmpty()) actions.onOpenSettings() else choosingModel = true
            ComposerCommand.RENAME -> { skill = null; renaming = true }
            ComposerCommand.STATUS -> actions.onShowStatus()
        }
    }
    val pickSkill: (AgentSkill) -> Unit = { picked ->
        draft = draftAfterPicking(picked, draft)
        skill = picked
        renaming = false
    }
    val commandEnabled: (ComposerCommand) -> Boolean = { command ->
        when (command) {
            ComposerCommand.COMPACT -> !state.runState.active
            ComposerCommand.MODEL -> !active && !voiceActive
            ComposerCommand.RENAME -> state.activeSessionId != null
            else -> true
        }
    }
    val submit: () -> Unit = {
        val text = draft.trim()
        val command = if (skill == null && !renaming) exactCommand(text) else null
        when {
            renaming -> if (text.isNotEmpty()) {
                state.activeSessionId?.let { actions.onRenameSession(it, text) }
                renaming = false
                draft = ""
            }
            command != null -> if (commandEnabled(command)) runCommand(command)
            text.isNotEmpty() -> {
                val message = withSkill(skill, text)
                if (active) actions.onSteer(message) else actions.onSend(message, state.attachments)
                draft = ""
                skill = null
            }
            else -> Unit
        }
    }
    val query = if (renaming) null else menuQuery(draft)
    val menuSkills = remember(query, state.availableSkills) {
        query?.let { q -> state.availableSkills.filter { it.matches(q.text) } }.orEmpty()
    }
    val menuCommands = if (query == null || query.skillsOnly) emptyList() else ComposerCommand.values().filter { it.matches(query.text) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.runState.active) {
            RunStatusRow(state.runState, showStop = !active && !voiceActive, onStop = actions.onStop)
        }
        if (state.queuedTurns.isNotEmpty()) QueuedTurns(state, actions)
        if (query != null) {
            SlashMenu(
                skills = menuSkills,
                commands = menuCommands,
                query = query.text,
                commandEnabled = commandEnabled,
                onSkill = pickSkill,
                onCommand = runCommand,
            )
        }

        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val border by animateColorAsState(if (focused) FieldBorderFocused else FieldBorder, tween(200), label = "composer-border")
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, border)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = actions.onAttach,
                    enabled = !active && !voiceActive && state.activeSessionId != null,
                ) {
                    Icon(Icons.Outlined.Add, "Attach file")
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 2.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.attachments.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.attachments, key = { it.id }) { attachment ->
                                AttachmentChip(attachment.name) { actions.onRemoveAttachment(attachment.id) }
                            }
                        }
                    }
                    skill?.let { picked -> SkillChip(picked) { skill = null } }
                    if (renaming) RenameChip { renaming = false; draft = "" }
                    MessageField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = state.activeSessionId != null,
                        placeholder = when {
                            renaming -> "New name for this chat"
                            skill != null -> "Add details…"
                            active -> "Add an instruction…"
                            else -> "Message, or type / for skills"
                        },
                        interaction = interaction,
                    )
                }
                if (action == ComposerAction.STEER) {
                    ComposerButton(ComposerAction.STOP, enabled = !stopping, onClick = actions.onStop, outlined = true)
                }
                ComposerButton(
                    action = action,
                    enabled = when (action) {
                        ComposerAction.VOICE -> state.activeSessionId != null && !state.isLoadingMessages && !voiceStopping
                        ComposerAction.STOP -> !stopping
                        ComposerAction.SEND, ComposerAction.STEER -> canSend
                    },
                    onClick = when (action) {
                        ComposerAction.VOICE -> actions.onVoiceToggle
                        ComposerAction.STOP -> actions.onStop
                        ComposerAction.SEND, ComposerAction.STEER -> submit
                    },
                    busy = action == ComposerAction.VOICE && voiceBusy,
                    voiceActive = voiceActive,
                    modifier = Modifier.onGloballyPositioned { onVoiceButtonPlaced(it.boundsInRoot().center) },
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkillsChip(enabled = state.activeSessionId != null && !voiceActive) { browsing = true }
            ModelChip(state, enabled = !active && !voiceActive) {
                if (state.availableModels.isEmpty()) actions.onOpenSettings() else choosingModel = true
            }
            if (state.planMode) PlanChip(onClick = actions.onTogglePlanMode)
        }
    }
    if (choosingModel) ModelSheet(state, actions, onDismiss = { choosingModel = false })
    if (browsing) {
        SkillsSheet(
            skills = state.availableSkills,
            loading = state.isLoadingSkills,
            commandEnabled = commandEnabled,
            onSkill = { browsing = false; pickSkill(it) },
            onCommand = { browsing = false; runCommand(it) },
            onDismiss = { browsing = false },
        )
    }
}

@Composable
private fun MessageField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    interaction: MutableInteractionSource,
) {
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 24.sp,
        // Each line takes its own direction, as in the chat; RtlLines makes
        // any line with a Hebrew letter read right to left.
        textDirection = TextDirection.Content,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        minLines = 1,
        maxLines = 6,
        interactionSource = interaction,
        visualTransformation = RtlLines,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Message input" },
        decorationBox = { field ->
            // Full width, and handed down to the text itself: a Box otherwise
            // lets the text shrink to its own width, so a right-to-left line
            // would end mid-field instead of at the field's edge.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart, propagateMinConstraints = true) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            }
        },
    )
}

/**
 * Shows a right-to-left mark before each line that holds a Hebrew or Arabic
 * letter, so the line lays out right to left, without changing the draft.
 */
private object RtlLines : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val marks = rtlLineStarts(text.text)
        if (marks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val shown = buildAnnotatedString {
            var from = 0
            for (at in marks) {
                append(text.subSequence(from, at))
                append(RLM)
                from = at
            }
            append(text.subSequence(from, text.length))
        }
        val mapping = object : OffsetMapping {
            // A caret at the start of a marked line sits after its mark.
            override fun originalToTransformed(offset: Int): Int = offset + marks.count { it <= offset }

            override fun transformedToOriginal(offset: Int): Int {
                val passed = marks.withIndex().count { (index, at) -> at + index < offset }
                return (offset - passed).coerceIn(0, text.length)
            }
        }
        return TransformedText(shown, mapping)
    }
}

@Composable
private fun ComposerButton(
    action: ComposerAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    busy: Boolean = false,
    voiceActive: Boolean = false,
) {
    val voice = action == ComposerAction.VOICE
    val fill by animateColorAsState(
        when {
            outlined -> Color.Transparent
            !enabled -> DisabledFill
            voice -> VoiceButtonBlue
            else -> Color.White
        },
        tween(220),
        label = "composer-button-fill",
    )
    val ink by animateColorAsState(
        when {
            !enabled -> DisabledInk
            outlined || voice -> Color.White
            else -> Color.Black
        },
        tween(220),
        label = "composer-button-ink",
    )
    val description = when (action) {
        ComposerAction.VOICE -> if (voiceActive) "End voice conversation" else "Start voice conversation"
        ComposerAction.SEND -> "Send message"
        ComposerAction.STEER -> "Steer agent"
        ComposerAction.STOP -> "Stop agent"
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .padding(4.dp)
            .then(if (outlined) Modifier.border(1.dp, if (enabled) FieldBorderFocused else DisabledFill, CircleShape) else Modifier)
            .background(fill, CircleShape)
            .semantics { contentDescription = description },
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            AnimatedContent(
                targetState = action,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f))
                },
                label = "composer-button-icon",
            ) { shown ->
                Icon(
                    imageVector = when (shown) {
                        ComposerAction.VOICE -> Icons.Default.GraphicEq
                        ComposerAction.STOP -> Icons.Default.Stop
                        ComposerAction.SEND, ComposerAction.STEER -> Icons.Default.ArrowUpward
                    },
                    contentDescription = null,
                    tint = ink,
                    modifier = if (shown == ComposerAction.STOP) Modifier.size(20.dp) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun RunStatusRow(runState: RunState, showStop: Boolean, onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(start = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentOrb(Modifier.size(24.dp), phase = runState.phase, controlling = runState.controlling)
        Text(
            runStatusText(runState),
            Modifier.weight(1f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showStop) TextButton(onClick = onStop) { Text("Stop active task") }
    }
}

private fun runStatusText(runState: RunState): String {
    val status = runState.status.trim()
    return if (status.isEmpty() || status == "Ready") readableRunPhase(runState.phase) else runStatusLabel(status)
}

@Composable
private fun QueuedTurns(state: AgentUiState, actions: AgentUiActions) {
    Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.queuedTurns.size} queued${if (state.queuePaused) " · paused" else ""}", Modifier.weight(1f))
            if (state.queuePaused) TextButton(onClick = actions.onResumeQueue) { Text("Resume queue") }
        }
        state.queuedTurns.forEach { queued ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(queued.prompt, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { actions.onCancelQueued(queued.id) }) { Text("Cancel task") }
            }
        }
    }
}

private val MenuBorder = Color(0xFF2E2E2E)
private val TileFill = Color(0xFF262626)
private val TileInk = Color(0xFFE6E6E6)
private val TagInk = Color(0xFF6B6B6B)
private val NoteRule = Color(0xFF262626)
private val SkillsAccent = Color(0xFFF2B155)
private val PlanBorder = Color(0xFF2F5F57)
private val SkillFallback = Color(0xFFB9B9B9)

private fun AgentSkill.brand(): Color = brandArgb(brandColor)?.let(::Color) ?: SkillFallback

/** The list a leading `/` (skills and commands) or `$` (skills only) opens above the field. */
@Composable
private fun SlashMenu(
    skills: List<AgentSkill>,
    commands: List<ComposerCommand>,
    query: String,
    commandEnabled: (ComposerCommand) -> Boolean,
    onSkill: (AgentSkill) -> Unit,
    onCommand: (ComposerCommand) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Skills and commands list" },
        shape = RoundedCornerShape(20.dp),
        color = SheetFill,
        border = BorderStroke(1.dp, MenuBorder),
        shadowElevation = 8.dp,
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 336.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp)) {
            menuItems(skills, commands, query, commandEnabled, onSkill, onCommand, empty = "Nothing matches. Keep typing to send it as a message.")
        }
    }
}

private fun LazyListScope.menuItems(
    skills: List<AgentSkill>,
    commands: List<ComposerCommand>,
    query: String,
    commandEnabled: (ComposerCommand) -> Boolean,
    onSkill: (AgentSkill) -> Unit,
    onCommand: (ComposerCommand) -> Unit,
    empty: String,
) {
    if (skills.isNotEmpty()) {
        item(key = "skills-label") { MenuLabel("SKILLS") }
        items(skills, key = { "skill:${it.path}" }) { skill ->
            MenuRow(
                title = skill.label,
                query = query,
                description = skill.summary,
                tag = if (skill.scope.equals("system", ignoreCase = true)) "System" else "",
                enabled = true,
                onClick = { onSkill(skill) },
            ) { SkillTile(skill) }
        }
    }
    if (commands.isNotEmpty()) {
        item(key = "commands-label") { MenuLabel("COMMANDS") }
        items(commands, key = { "command:${it.id}" }) { command ->
            MenuRow(
                title = command.label,
                query = query,
                description = command.description,
                tag = "/${command.id}",
                enabled = commandEnabled(command),
                onClick = { onCommand(command) },
            ) { CommandTile(command) }
        }
    }
    if (skills.isEmpty() && commands.isEmpty()) {
        item(key = "empty") { Text(empty, Modifier.padding(16.dp), fontSize = 14.sp, lineHeight = 20.sp, color = MutedInk) }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MutedInk,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuRow(
    title: String,
    query: String,
    description: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tile: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tile()
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    val hit = matchRange(title, query)
                    if (hit == null) append(title) else {
                        append(title.substring(0, hit.first))
                        withStyle(SpanStyle(color = accent)) { append(title.substring(hit.first, hit.last + 1)) }
                        append(title.substring(hit.last + 1))
                    }
                },
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(description, fontSize = 12.5.sp, lineHeight = 17.sp, color = MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tag.isNotEmpty()) Text(tag, fontSize = 12.sp, lineHeight = 16.sp, color = TagInk)
    }
}

// Codex hands skill icons over as file paths that may be SVG, so the tile
// uses the skill's own colour and initial instead.
@Composable
private fun SkillTile(skill: AgentSkill) {
    val brand = skill.brand()
    Box(
        Modifier.size(34.dp).background(brand.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(skill.label.take(1).uppercase(), color = brand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CommandTile(command: ComposerCommand) {
    Box(Modifier.size(34.dp).background(TileFill, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(command.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = TileInk)
    }
}

private fun ComposerCommand.icon(): ImageVector = when (this) {
    ComposerCommand.NEW -> Icons.Outlined.AddComment
    ComposerCommand.COMPACT -> Icons.Outlined.Compress
    ComposerCommand.PLAN -> Icons.Outlined.Checklist
    ComposerCommand.MODEL -> Icons.Outlined.Tune
    ComposerCommand.RENAME -> Icons.Outlined.DriveFileRenameOutline
    ComposerCommand.STATUS -> Icons.Outlined.Info
}

/** Every skill and command, with search; opened from the Skills chip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsSheet(
    skills: List<AgentSkill>,
    loading: Boolean,
    commandEnabled: (ComposerCommand) -> Boolean,
    onSkill: (AgentSkill) -> Unit,
    onCommand: (ComposerCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    val query = search.trim()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = SheetFill) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(search, { search = it }, Modifier.padding(horizontal = 16.dp))
            if (skills.isEmpty() && query.isEmpty()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (loading) "Loading skills…" else "No skills in this chat yet. Skills you add to Codex show up here.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MutedInk,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                menuItems(
                    skills = skills.filter { it.matches(query) },
                    commands = ComposerCommand.values().filter { it.matches(query) },
                    query = query,
                    commandEnabled = commandEnabled,
                    onSkill = onSkill,
                    onCommand = onCommand,
                    empty = "Nothing matches your search.",
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(23.dp), color = OptionFill) {
        Row(
            Modifier.heightIn(min = 46.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MutedInk)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search skills and commands" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("Search skills and commands", style = style.copy(color = MutedInk))
                        field()
                    }
                },
            )
        }
    }
}

@Composable
private fun SkillsChip(enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) MaterialTheme.colorScheme.onSurface else DisabledInk
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ChipBorder),
        modifier = Modifier.semantics { contentDescription = "Skills and commands" },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = 9.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (enabled) SkillsAccent else DisabledInk)
            Text("Skills", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink)
        }
    }
}

@Composable
private fun PlanChip(onClick: () -> Unit) {
    val teal = MaterialTheme.colorScheme.secondary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = teal.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, PlanBorder),
        modifier = Modifier.semantics { contentDescription = "Turn plan mode off" },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Checklist, contentDescription = null, modifier = Modifier.size(16.dp), tint = teal)
            Text("Plan", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = teal)
            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(12.dp), tint = teal)
        }
    }
}

@Composable
private fun SkillChip(skill: AgentSkill, onRemove: () -> Unit) {
    val brand = skill.brand()
    TokenChip(skill.label, brand, brand.copy(alpha = 0.12f), Icons.Outlined.AutoAwesome, "Remove ${skill.label}", onRemove)
}

@Composable
private fun RenameChip(onCancel: () -> Unit) {
    TokenChip("Rename chat", TileInk, AttachmentFill, Icons.Outlined.DriveFileRenameOutline, "Cancel rename", onCancel)
}

@Composable
private fun TokenChip(label: String, ink: Color, fill: Color, icon: ImageVector, removeLabel: String, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = fill) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, removeLabel, Modifier.size(14.dp), tint = ink)
            }
        }
    }
}

/** A line in the chat recording what a command did, between two rules. */
@Composable
internal fun CommandNote(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = NoteRule)
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MutedInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = NoteRule)
    }
}

@Composable
private fun AttachmentChip(name: String, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = AttachmentFill) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = ChipInk)
            Text(
                name,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, "Remove $name", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelChip(state: AgentUiState, enabled: Boolean, onClick: () -> Unit) {
    val model = state.selectedModel?.removePrefix("gpt-")
    val label = if (model == null) "Choose model" else "$model · ${state.selectedReasoningEffort ?: "default"}"
    val ink = if (enabled) ChipInk else DisabledInk
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ChipBorder),
        modifier = Modifier.semantics { contentDescription = "Choose model and reasoning" },
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(state: AgentUiState, actions: AgentUiActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val efforts = state.modelCatalog.firstOrNull { it.id == state.selectedModel }?.reasoningEfforts.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = SheetFill) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetLabel("MODEL")
            state.availableModels.forEach { model ->
                val selected = model == state.selectedModel
                Surface(
                    onClick = { actions.onModelSelected(model) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) OptionFill else Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(model.removePrefix("gpt-"), Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (efforts.isNotEmpty()) {
                SheetLabel("REASONING")
                (listOf<ReasoningEffortOption?>(null) + efforts).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            EffortOption(
                                option = option,
                                selected = option?.value == state.selectedReasoningEffort,
                                modifier = Modifier.weight(1f),
                            ) { actions.onReasoningEffortSelected(option?.value) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MutedInk,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun EffortOption(option: ReasoningEffortOption?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val note = option?.description?.takeIf { it.isNotBlank() } ?: if (option == null) "Model default" else ""
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color.White else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, ChipBorder),
        modifier = modifier.heightIn(min = 60.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                option?.value ?: "Default",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (note.isNotEmpty()) {
                Text(
                    note,
                    fontSize = 12.sp,
                    color = if (selected) Color(0xFF4A4A4A) else MutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
