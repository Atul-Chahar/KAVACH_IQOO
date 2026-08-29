package com.kavach.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.R
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand

/**
 * The alert surface.
 *
 * Designed for someone who is elderly, frightened, and being actively
 * manipulated (docs/SAFETY.md 5): large type, high contrast, calm instructional
 * copy, no sirens and no countdowns. Every alerting state names the tactics that
 * caused it, because "87% risk" is unfalsifiable while "this caller claims to be
 * police and is asking for your OTP" can be checked in one second.
 */
@Composable
fun ShieldScreen(
    state: ShieldUiState,
    onStartLive: () -> Unit,
    onStartDemo: () -> Unit,
    onStop: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenModelSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(RiskColors.surfaceFor(state.band), label = "risk-surface")

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusHeader(state)

        if (state.band != RiskBand.WATCHING && state.tactics.isNotEmpty()) {
            TacticsCard(state)
        }

        if (state.band == RiskBand.HIGH_RISK) {
            ActionCard()
        }

        state.failureReason?.let { CautionNote(stringResource(R.string.capture_failed, it), state.band) }
        state.degradedReason?.let { CautionNote(it, state.band) }

        Spacer(Modifier.height(4.dp))
        Controls(state, onStartLive, onStartDemo, onStop, onOpenReport, onOpenModelSetup)

        // The single most important sentence in the app.
        Text(
            text = stringResource(R.string.safety_no_guarantee),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = RiskColors.onSurfaceFor(state.band),
        )
        Text(
            text = stringResource(R.string.safety_pattern_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = RiskColors.onSurfaceFor(state.band),
        )
        if (state.engineName.isNotEmpty()) {
            Text(
                text = stringResource(R.string.engine_label, state.engineName),
                style = MaterialTheme.typography.bodySmall,
                color = RiskColors.onSurfaceFor(state.band),
            )
        }
    }
}

@Composable
private fun StatusHeader(state: ShieldUiState) {
    val onSurface = RiskColors.onSurfaceFor(state.band)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text =
                stringResource(
                    when (state.band) {
                        RiskBand.WATCHING ->
                            if (state.monitoring) R.string.state_watching_title else R.string.state_idle_title
                        RiskBand.CAUTION -> R.string.state_caution_title
                        RiskBand.HIGH_RISK -> R.string.state_high_risk_title
                    },
                ),
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            color = onSurface,
        )
        Text(
            text =
                stringResource(
                    when (state.band) {
                        RiskBand.WATCHING ->
                            if (state.monitoring) R.string.state_watching_body else R.string.state_idle_body
                        RiskBand.CAUTION -> R.string.state_caution_body
                        RiskBand.HIGH_RISK -> R.string.state_high_risk_body
                    },
                ),
            style = MaterialTheme.typography.bodyLarge,
            color = onSurface,
        )
        if (state.monitoring) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = elapsed(state.elapsedMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface,
                )
                if (state.mode == MonitorMode.DEMO) {
                    Text(
                        text = stringResource(R.string.demo_badge),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TacticsCard(state: ShieldUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = CARD_ALPHA)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.tactics_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            state.tactics.forEach { tactic ->
                Text("•  $tactic", style = MaterialTheme.typography.bodyLarge)
            }
            state.assessment.tier2Reason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ActionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.action_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            listOf(R.string.action_hang_up, R.string.action_never_otp, R.string.action_call_1930).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Band-aware colour: the red surface needs light text, or the note is unreadable. */
@Composable
private fun CautionNote(
    text: String,
    band: RiskBand,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = RiskColors.onSurfaceFor(band),
    )
}

@Composable
private fun Controls(
    state: ShieldUiState,
    onStartLive: () -> Unit,
    onStartDemo: () -> Unit,
    onStop: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenModelSetup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.monitoring) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_stop), fontSize = 18.sp)
            }
        } else {
            Button(onClick = onStartLive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_start), fontSize = 18.sp)
            }
            OutlinedButton(onClick = onStartDemo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_demo), fontSize = 18.sp)
            }
        }
        if (state.incidents.isNotEmpty()) {
            OutlinedButton(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_report, state.incidents.size), fontSize = 18.sp)
            }
        }
        if (!state.monitoring) {
            TextButton(onClick = onOpenModelSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_action_setup), fontSize = 16.sp)
            }
        }
    }
}

private fun elapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private const val CARD_ALPHA = 0.92f

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ShieldScreenHighRiskPreview() {
    KavachTheme {
        ShieldScreen(
            state =
                ShieldUiState(
                    monitoring = true,
                    assessment =
                        RiskAssessment(
                            score = 87,
                            band = RiskBand.HIGH_RISK,
                            matchedFamilies = listOf("AUTHORITY_IMPERSONATION"),
                            evidence = emptyList(),
                        ),
                    tactics =
                        listOf(
                            "Caller claims to be law enforcement or a bank",
                            "You are being told to stay on the call and keep it secret",
                            "Someone is asking for an OTP, PIN or password",
                        ),
                    elapsedMs = 154_000,
                    engineName = "System on-device ASR (en-IN)",
                ),
            onStartLive = {},
            onStartDemo = {},
            onStop = {},
            onOpenReport = {},
            onOpenModelSetup = {},
        )
    }
}
