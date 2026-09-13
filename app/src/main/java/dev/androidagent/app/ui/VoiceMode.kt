package dev.androidagent.app.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.core.VoicePhase
import dev.androidagent.core.VoiceState

// Voice mode takes over the whole screen instead of living in the composer.
// One transition drives every moving part, each with its own delay: the chat
// lifts and blurs away, the top bar fades, the composer sinks, the voice
// button's disc flies to the middle and grows into the sphere, and then the
// status, the live caption and the controls rise in. Ending voice plays the
// same choreography backwards into the voice button.

private val Emphasized = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val ControlOutline = Color(0xFF383838)
private val MutedFill = Color(0xFFF2F2F2)
private val MutedInk = Color(0xFF111111)

/**
 * How far each moving part is into view: 1 is fully shown. Chat parts are
 * shown outside voice mode, voice parts inside it. [flight] is the sphere's
 * linear 0..1 trip from the voice button to the stage.
 */
@Stable
internal class VoiceModeMotion(
    val shown: Boolean,
    val flight: State<Float>,
    val chat: State<Float>,
    val topBar: State<Float>,
    val composer: State<Float>,
    val header: State<Float>,
    val status: State<Float>,
    val caption: State<Float>,
    val mute: State<Float>,
    val end: State<Float>,
    /** Centre of the composer's voice button, in root coordinates. */
    val dock: MutableState<Offset>,
)

/** Voice mode is on screen from the first connect until the user ends it. */
internal fun voiceModeShown(voice: VoiceState): Boolean = voice.active && voice.phase != VoicePhase.STOPPING

@Composable
internal fun rememberVoiceModeMotion(shown: Boolean): VoiceModeMotion {
    val transition = updateTransition(targetState = shown, label = "voice-mode")
    val dock = remember { mutableStateOf(Offset.Unspecified) }
    return VoiceModeMotion(
        shown = shown,
        flight = transition.animateFloat(
            transitionSpec = { tween(if (targetState) 1000 else 850, easing = LinearEasing) },
            label = "voice-flight",
        ) { voice -> if (voice) 1f else 0f },
        chat = transition.part(inVoice = false, show = 250, hide = 0, duration = 560, label = "voice-chat"),
        topBar = transition.part(inVoice = false, show = 380, hide = 40, duration = 420, label = "voice-top-bar"),
        composer = transition.part(inVoice = false, show = 180, hide = 60, duration = 620, label = "voice-composer"),
        header = transition.part(inVoice = true, show = 260, hide = 0, duration = 380, label = "voice-header"),
        status = transition.part(inVoice = true, show = 560, hide = 0, duration = 480, label = "voice-status"),
        caption = transition.part(inVoice = true, show = 640, hide = 0, duration = 560, label = "voice-caption"),
        mute = transition.part(inVoice = true, show = 720, hide = 30, duration = 560, label = "voice-mute"),
        end = transition.part(inVoice = true, show = 790, hide = 0, duration = 560, label = "voice-end"),
        dock = dock,
    )
}

/** One part's visibility; [show] and [hide] are its delays in each direction. */
@Composable
private fun Transition<Boolean>.part(
    inVoice: Boolean,
    show: Int,
    hide: Int,
    duration: Int,
    label: String,
): State<Float> = animateFloat(
    transitionSpec = { tween(duration, delayMillis = if (targetState == inVoice) show else hide, easing = Emphasized) },
    label = label,
) { voice -> if (voice == inVoice) 1f else 0f }

/**
 * Moves a part with its [visible] progress, read at draw time so the
 * choreography never recomposes the chat. A hidden part sits [lift] away,
 * scaled to [scaleFrom] and, on Android 12+, blurred by [blur].
 */
internal fun Modifier.voiceStage(
    visible: State<Float>,
    lift: Dp = 0.dp,
    scaleFrom: Float = 1f,
    blur: Dp = 0.dp,
): Modifier = graphicsLayer {
    val shown = visible.value
    alpha = shown
    translationY = (1f - shown) * lift.toPx()
    val scale = scaleFrom + (1f - scaleFrom) * shown
    scaleX = scale
    scaleY = scale
    if (blur > 0.dp && Build.VERSION.SDK_INT >= 31) {
        val radius = (1f - shown) * blur.toPx()
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius) else null
    }
}

/** The voice-mode screen, drawn over the chat while voice is on or still animating out. */
@Composable
internal fun VoiceModeLayer(
    motion: VoiceModeMotion,
    state: AgentUiState,
    actions: AgentUiActions,
    voiceLevel: () -> Float,
) {
    val onScreen by remember(motion.flight) { derivedStateOf { motion.flight.value > 0f } }
    if (!motion.shown && !onScreen) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    var stage by remember { mutableStateOf(Rect.Zero) }
    val voice = state.voiceState
    val live = voiceModeShown(voice)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // The chat underneath is not reachable while voice mode is up.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        VoiceSphere(
            modifier = Modifier.matchParentSize(),
            flight = motion.flight,
            shown = motion.shown,
            phase = voice.phase,
            muted = state.voiceMuted,
            level = voiceLevel,
            dock = { motion.dock.value.let { if (it.isSpecified) it - origin else it } },
            stage = { stage.translate(-origin) },
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            VoiceHeader(state.activeSessionTitle, Modifier.voiceStage(motion.header, lift = 6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { stage = it.boundsInRoot() },
            )
            // The chat's approval card is under this screen, so a request made
            // during a voice conversation is answered here: by tapping, or by
            // saying "yes" or "no".
            state.runState.approval?.let { approval ->
                ApprovalCard(
                    approval = approval,
                    onApproval = actions.onApproval,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            VoiceStatus(voice, state.voiceMuted, Modifier.voiceStage(motion.status, lift = 10.dp))
            VoiceCaption(
                transcript = state.voiceTranscript,
                role = state.voiceTranscriptRole,
                modifier = Modifier.voiceStage(motion.caption, lift = 14.dp, blur = 6.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceMuteButton(
                    muted = state.voiceMuted,
                    enabled = live,
                    onClick = actions.onVoiceMuteToggle,
                    modifier = Modifier.voiceStage(motion.mute, lift = 28.dp, scaleFrom = 0.9f),
                )
                EndVoiceButton(
                    enabled = live,
                    onClick = actions.onStop,
                    modifier = Modifier.voiceStage(motion.end, lift = 28.dp, scaleFrom = 0.9f),
                )
            }
        }
    }
}

// Lines up with the chat top bar's title so the title seems to stay put while
// the connection pill below it gives way to "Voice conversation".
@Composable
private fun VoiceHeader(title: String?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().height(64.dp).padding(start = 52.dp, end = 16.dp)) {
        Text(
            text = title?.takeIf { it.isNotBlank() } ?: "Hey Mike",
            style = MaterialTheme.typography.titleMedium,
            // Without an explicit colour the title inherits dark content
            // colour and disappears on the voice screen's dark background.
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = "Voice conversation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceStatus(voice: VoiceState, muted: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val silenced = muted && voice.phase == VoicePhase.LISTENING
    val dot by animateColorAsState(
        targetValue = when (voice.phase) {
            VoicePhase.STARTING, VoicePhase.SPEAKING -> VoiceBlue
            VoicePhase.LISTENING -> if (muted) scheme.error else VoiceTeal
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "voice-status-dot",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(
            text = if (silenced) "Microphone off" else voice.message,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.26.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceCaption(transcript: String, role: String?, modifier: Modifier) {
    val text = captionTail(transcript.trim())
    val codex = role.equals("assistant", ignoreCase = true)
    Column(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(start = 32.dp, end = 32.dp, top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = if (codex) "MIKE" else "YOU",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = if (codex) VoiceBlue else VoiceTeal,
            )
            Text(
                text = text,
                style = TextStyle(fontSize = 22.sp, lineHeight = 31.sp, textDirection = TextDirection.Content),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The end of a live transcript, so the words being spoken are the ones on screen. */
internal fun captionTail(text: String, limit: Int = 120): String {
    if (text.length <= limit) return text
    val cut = text.length - limit
    val space = text.indexOf(' ', cut)
    val start = if (space in cut until text.length - 1) space + 1 else cut
    return "…" + text.substring(start)
}

@Composable
private fun VoiceMuteButton(muted: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (muted) MutedFill else scheme.surface, tween(durationMillis = 220), label = "voice-mute-fill")
    val ink by animateColorAsState(if (muted) MutedInk else scheme.onSurface, tween(durationMillis = 220), label = "voice-mute-ink")
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = if (muted) "Unmute microphone" else "Mute microphone" },
        shape = CircleShape,
        color = fill,
        contentColor = ink,
        border = BorderStroke(1.dp, if (muted) MutedFill else ControlOutline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, contentDescription = null)
        }
    }
}

@Composable
private fun EndVoiceButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(56.dp)
            .semantics { contentDescription = "End voice conversation" },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("End voice", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
