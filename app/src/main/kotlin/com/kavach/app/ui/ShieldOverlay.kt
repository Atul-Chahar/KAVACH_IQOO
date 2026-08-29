package com.kavach.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.capture.CaptureState
import com.kavach.app.capture.audioModeName
import com.kavach.app.monitor.ShieldUiState
import com.kavach.domain.RiskBand
import kotlinx.coroutines.delay

/**
 * Universal Dynamic Island / Call Shield Overlay.
 *
 * Renders an interactive, hardware-aligned Dynamic Island capsule around the
 * camera punch-hole during phone calls. Expands smoothly from a compact monitoring
 * capsule into a full Alert Shield when scam tactics are detected.
 */
@Composable
fun ShieldOverlay(
    state: ShieldUiState,
    capture: CaptureState,
    onCall1930: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    // The session takes a moment to come up after the shield appears, so a
    // not-yet-listening state is only believed once the grace window has passed.
    // Without it the card would flash on every call before capture starts.
    var graceElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(state.monitoring) {
        graceElapsed = false
        if (!state.monitoring) {
            delay(STARTUP_GRACE_MS)
            graceElapsed = true
        }
    }

    // Every way this shield can be on screen while hearing nothing.
    //
    // `provenSilent` alone was not enough and the gap was the dangerous kind: it
    // needs 150 frames before it will commit, so a capture that never started at
    // all — foreground service refused, permission revoked, AudioRecord failed —
    // leaves framesRead at 0, reports "not proven silent", and the user gets a
    // calm pulsing pill over a live scam. A false all-clear is the one outcome
    // docs/SAFETY.md forbids, so silence of unknown cause is now said out loud.
    val silentReason = silentReason(state, capture, graceElapsed)
    val deaf = silentReason != null
    val alerting = state.band == RiskBand.HIGH_RISK && !state.alertDismissed
    val palette = RiskColors.paletteFor(if (deaf) RiskBand.CAUTION else state.band)
    var telemetryExpanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 6.dp, start = 12.dp, end = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            // The warning outranks the can't-hear card, and the order is the
            // whole point. Both were reachable at once — the platform mutes us
            // part-way through a call we have *already* scored HIGH_RISK — and
            // with the deaf branch first the live scam warning was replaced by
            // "Kavach can't hear this call". The deaf card exists to prevent a
            // false all-clear; an alert is not an all-clear, so it wins.
            alerting -> AlertCard(state, palette, onCall1930, onDismiss)
            silentReason != null -> DeafCard(silentReason, capture, onStop)
            else ->
                DynamicIslandPill(
                    state = state,
                    capture = capture,
                    expanded = telemetryExpanded,
                    onToggleExpand = { telemetryExpanded = !telemetryExpanded },
                    onStop = onStop,
                )
        }
    }
}

/**
 * The Floating Dynamic Island / Live Capsule.
 *
 * Sits unobtrusively at the top center of the screen around the camera cutout.
 * Shows live pulsing detection indicator, audio levels, and morphs when caution
 * patterns arise. Tapping toggles the diagnostic telemetry drawer.
 */
@Composable
private fun DynamicIslandPill(
    state: ShieldUiState,
    capture: CaptureState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onStop: () -> Unit,
) {
    val isCaution = state.band == RiskBand.CAUTION

    val islandBg by animateColorAsState(
        targetValue = if (isCaution) Color(0xFF261D11) else Color(0xFF141211),
        animationSpec = tween(300),
        label = "islandBg",
    )

    val islandBorder by animateColorAsState(
        targetValue = if (isCaution) KavachTokens.Amber.copy(alpha = 0.8f) else Color(0xFF383330),
        animationSpec = tween(300),
        label = "islandBorder",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseScale",
    )

    Column(
        modifier =
            Modifier
                .widthIn(min = 180.dp, max = 340.dp)
                .animateContentSize()
                .clip(RoundedCornerShape(22.dp))
                .background(islandBg)
                .border(1.dp, islandBorder, RoundedCornerShape(22.dp))
                .clickable { onToggleExpand() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Live Status Indicator Dot
            Box(
                Modifier
                    .size(10.dp)
                    .scale(if (capture.hearing) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCaution -> KavachTokens.Amber
                            capture.hearing -> Color(0xFF22C55E)
                            else -> KavachTokens.InkMuted
                        },
                    ),
            )

            Spacer(Modifier.size(8.dp))

            // Capsule Label
            Text(
                text =
                    when {
                        isCaution -> "⚠️ Caution · " + (state.tactics.firstOrNull() ?: "Pattern detected")
                        else -> "Kavach · Listening"
                    },
                color = if (isCaution) Color(0xFFFDE68A) else Color(0xFFF3F4F6),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.size(8.dp))

            // Language / Telemetry Pill Tag
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2B2725))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (capture.language.isNotBlank()) "HI+EN" else "ASR",
                    color = KavachTokens.Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Expanded Diagnostics Drawer
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "LIVE TELEMETRY",
                    color = KavachTokens.InkMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = diagnosticLine(capture),
                    color = Color(0xFFE5E7EB),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (capture.language.isNotBlank()) {
                    Text(
                        text = "Active Speech Engines: ${capture.language}",
                        color = KavachTokens.Cyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (state.transcriptPreview.isNotBlank()) {
                    Text(
                        text = "“${state.transcriptPreview.takeLast(80)}”",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tap to close",
                        color = KavachTokens.InkMuted,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "Stop",
                        color = KavachTokens.Amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onStop() },
                    )
                }
            }
        }
    }
}

/**
 * The honest failure state.
 *
 * If the platform is feeding us silence we say so in plain words.
 */
@Composable
private fun DeafCard(
    reason: String,
    capture: CaptureState,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(KavachTokens.PaperAmber)
            .border(1.dp, KavachTokens.Amber.copy(alpha = 0.4f), RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(20.dp),
    ) {
        Text(
            "KAVACH CAN'T HEAR THIS CALL",
            color = KavachTokens.Amber,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            reason,
            color = KavachTokens.Ink,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Do not treat the absence of a warning as safety.",
            color = KavachTokens.Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            diagnosticLine(capture),
            color = KavachTokens.InkMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "Stop",
            color = KavachTokens.Amber,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp).clickable(onClick = onStop),
        )
    }
}

/** The high risk warning. One word, one because, the evidence, two choices. */
@Composable
private fun AlertCard(
    state: ShieldUiState,
    palette: BandPalette,
    onCall1930: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(palette.ground)
            .padding(24.dp),
    ) {
        Text(
            "LIKELY SCAM",
            color = palette.ink,
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            because(state),
            color = palette.inkSoft,
            fontSize = 18.sp,
            lineHeight = 25.sp,
            modifier = Modifier.padding(top = 12.dp),
        )

        state.tacticEvidence.take(MAX_EVIDENCE).forEach { evidence ->
            Row(Modifier.padding(top = 10.dp)) {
                Text("—", color = palette.underline, fontSize = 16.sp)
                Text(
                    evidence.displayName,
                    color = palette.ink,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        // Both actions are dragged, not tapped. The person reading this card is
        // the worst candidate for a tap target: frightened, being talked at, and
        // often holding the phone against their face — and a stray touch on
        // "I'm fine" silences the only warning they get. Dragging costs a
        // deliberate half-second and cannot happen by accident.
        //
        // Monochrome on the red, because the design system spends colour once:
        // the full-bleed press-red *is* the signal, and a green affordance laid
        // on it would be a second, competing one.
        Gap(22.dp)
        SlideToConfirm(
            label = "Slide to call 1930",
            icon = Ph.phoneCall,
            track = palette.ink.copy(alpha = 0.16f),
            thumb = palette.accent,
            onThumb = palette.onAccent,
            ink = palette.ink,
            onConfirm = onCall1930,
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(10.dp)
        SlideToConfirm(
            label = "Slide if you're fine",
            icon = Ph.handPalm,
            track = palette.ink.copy(alpha = 0.09f),
            thumb = palette.ink.copy(alpha = 0.22f),
            onThumb = palette.ink,
            ink = palette.inkSoft,
            onConfirm = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Kavach can be wrong. It never blocks or ends a call for you.",
            color = palette.inkMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * The sentence that turns a warning into a decision.
 */
private fun because(state: ShieldUiState): String {
    val named = state.tactics.take(2)
    return when (named.size) {
        0 -> "This call is following a pattern we recognise from scam calls."
        1 -> "This caller is using ${named[0].lowercase()}."
        else -> "This caller is using ${named[0].lowercase()} and ${named[1].lowercase()}."
    }
}

/**
 * Why Kavach cannot hear right now, or null if it can.
 *
 * Ordered most-specific first, because the sentence the user reads has to name
 * the thing they can actually fix. "The service was refused" and "the platform
 * muted us" lead to completely different next steps.
 */
private fun silentReason(
    state: ShieldUiState,
    capture: CaptureState,
    graceElapsed: Boolean,
): String? =
    when {
        state.failureReason != null -> state.failureReason

        !state.monitoring && graceElapsed ->
            "Kavach is not listening. The microphone service did not start — Android may have " +
                "refused it, or the permission was withdrawn. Open Kavach and start a session by hand."

        capture.silenced || capture.provenSilent ->
            "Android is muting our microphone, so nothing is being analysed."

        else -> null
    }

/** Ground truth, unedited. It is on screen because it is what makes the claim checkable. */
private fun diagnosticLine(capture: CaptureState): String {
    val level = if (capture.rmsDb.isFinite()) "${capture.rmsDb.toInt()} dB" else "silence"
    return "${audioModeName(capture.audioMode)} · $level · ${capture.transcripts} transcripts"
}

private const val MAX_EVIDENCE = 3

/** How long the session gets to come up before the shield calls it silent. */
private const val STARTUP_GRACE_MS = 4_000L
