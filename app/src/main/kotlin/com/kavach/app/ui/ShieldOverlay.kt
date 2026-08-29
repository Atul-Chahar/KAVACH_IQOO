package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.capture.CaptureState
import com.kavach.app.capture.audioModeName
import com.kavach.app.monitor.ShieldUiState
import com.kavach.domain.RiskBand

/**
 * What the user sees during a monitored call.
 *
 * Designed for the person it is actually for: someone in their sixties, probably
 * not wearing their glasses, being shouted at by a stranger claiming to be the
 * police, with roughly three seconds of attention to spare. So: one enormous
 * word, one sentence saying *why*, at most three pieces of evidence in the
 * caller's own paraphrased words, and exactly two buttons. The 0-100 score is
 * engineering telemetry and never the hero — a number cannot be checked against
 * the conversation, but "they told you to keep this call secret" can be, in one
 * second, by the person living it.
 *
 * It occupies the top of the screen only. The hang-up control must always be
 * reachable without dismissing us first.
 */
@Composable
fun ShieldOverlay(
    state: ShieldUiState,
    capture: CaptureState,
    onCall1930: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val deaf = capture.silenced || capture.provenSilent
    val alerting = state.band == RiskBand.HIGH_RISK && !state.alertDismissed
    val palette = RiskColors.paletteFor(if (deaf) RiskBand.CAUTION else state.band)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            when {
                deaf -> DeafCard(capture, onStop)
                alerting -> AlertCard(state, palette, onCall1930, onDismiss)
                else -> PassiveBanner(state, capture, palette)
            }
        }
    }
}

/**
 * The honest failure state, and the most important card here.
 *
 * If the platform is feeding us silence we say so in plain words. A calm green
 * "no scam detected" drawn over a microphone that is not receiving anything is
 * worse than showing nothing at all: it converts our own blindness into the
 * user's false confidence.
 */
@Composable
private fun DeafCard(
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
            "Android is muting our microphone. We are not analysing anything — " +
                "do not treat the absence of a warning as safety.",
            color = KavachTokens.Ink,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
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

/** Nothing to say yet. Small, quiet, and touch passes straight through it to the call. */
@Composable
private fun PassiveBanner(
    state: ShieldUiState,
    capture: CaptureState,
    palette: BandPalette,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(palette.ground.copy(alpha = 0.96f))
            .border(1.dp, palette.rule, RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (capture.hearing) palette.accent else KavachTokens.InkMuted),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (state.band == RiskBand.CAUTION) "Something in this call looks off" else "Kavach is listening",
                color = palette.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                state.tactics.firstOrNull() ?: diagnosticLine(capture),
                color = palette.inkMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/** The warning. One word, one because, the evidence, two choices. */
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

        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OverlayButton("Call 1930", palette.accent, palette.onAccent, Modifier.weight(1f), onCall1930)
            OverlayButton("I'm fine", Color.Transparent, palette.ink, Modifier.weight(1f), onDismiss, palette.rule)
        }

        Text(
            "Kavach can be wrong. It never blocks or ends a call for you.",
            color = palette.inkMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun OverlayButton(
    label: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    border: Color? = null,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(background)
            .then(
                if (border != null) {
                    Modifier.border(1.dp, border, RoundedCornerShape(KavachTokens.RadiusCard))
                } else {
                    Modifier
                },
            ).clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The sentence that turns a warning into a decision.
 *
 * Assembled from the tactic families the engine actually matched, never from raw
 * model text (CLAUDE.md hard rule 4).
 */
private fun because(state: ShieldUiState): String {
    val named = state.tactics.take(2)
    return when (named.size) {
        0 -> "This call is following a pattern we recognise from scam calls."
        1 -> "This caller is using ${named[0].lowercase()}."
        else -> "This caller is using ${named[0].lowercase()} and ${named[1].lowercase()}."
    }
}

/** Ground truth, unedited. It is on screen because it is what makes the claim checkable. */
private fun diagnosticLine(capture: CaptureState): String {
    val level = if (capture.rmsDb.isFinite()) "${capture.rmsDb.toInt()} dB" else "silence"
    return "${audioModeName(capture.audioMode)} · $level · ${capture.transcripts} transcripts"
}

private const val MAX_EVIDENCE = 3
