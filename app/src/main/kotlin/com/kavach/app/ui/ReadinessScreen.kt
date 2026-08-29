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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.capture.CaptureState
import com.kavach.app.capture.MicOwner
import com.kavach.app.capture.audioModeName
import com.kavach.app.setup.Readiness
import com.kavach.app.setup.Rung

/**
 * The permission ladder, and the only screen that asks the user for anything.
 *
 * Not a wall of dialogs and not a carousel: a checklist that states, per rung,
 * what is lost by leaving it ungranted. Kavach works at every rung and says
 * which one it is on, because an app that quietly does less than the user thinks
 * is worse than one that admits its limits.
 *
 * The live capture readout at the bottom is deliberately raw. It is the evidence
 * for the claim the rest of the app makes, and it is what turns "trust us" into
 * "look".
 */
@Composable
fun ReadinessScreen(
    rungs: List<Rung>,
    tier: String,
    capture: CaptureState,
    showRestrictedHint: Boolean,
    onGrant: (Rung) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            "What Kavach can do right now",
            color = KavachTokens.Ink,
            fontSize = 28.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            tier,
            color = if (tier == Readiness.TIER_IN_CALL) KavachTokens.Cyan else KavachTokens.Amber,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )

        rungs.forEach { rung ->
            RungRow(rung, onGrant)
        }

        if (showRestrictedHint) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(KavachTokens.RadiusCard))
                    .background(KavachTokens.PaperAmber)
                    .padding(18.dp),
            ) {
                Text(
                    "IF THE ACCESSIBILITY TOGGLE IS GREYED OUT",
                    color = KavachTokens.Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    "Android blocks that switch for apps installed outside an app store. " +
                        "Open App info, tap the three dots at the top right, choose " +
                        "“Allow restricted settings”, then come back.",
                    color = KavachTokens.Ink,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        CaptureReadout(capture)

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .clip(RoundedCornerShape(KavachTokens.RadiusCard))
                .background(KavachTokens.Ink)
                .clickable(onClick = onContinue)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Continue", color = KavachTokens.Paper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RungRow(
    rung: Rung,
    onGrant: (Rung) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (rung.granted) KavachTokens.Cyan else KavachTokens.Ink.copy(alpha = 0.12f)),
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(rung.title, color = KavachTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                rung.why,
                color = KavachTokens.InkSoft,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (!rung.granted) {
                Text(
                    "Without it: ${rung.blocks}",
                    color = KavachTokens.Amber,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    "Grant",
                    color = KavachTokens.Cyan,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp).clickable { onGrant(rung) },
                )
            }
        }
    }
}

/**
 * The four numbers that decide whether this app is possible on this device.
 *
 * Printed rather than interpreted. `IN_COMMUNICATION` with `silenced=false` and a
 * live level is the result the whole architecture is built to reach; anything
 * else is the honest answer that we cannot hear the call.
 */
@Composable
private fun CaptureReadout(capture: CaptureState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp)
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .border(1.dp, KavachTokens.Ink.copy(alpha = 0.12f), RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "LIVE CAPTURE",
            color = KavachTokens.InkMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        ReadoutLine("Audio mode", audioModeName(capture.audioMode))
        ReadoutLine("Microphone owner", capture.owner.readable())
        ReadoutLine("Silenced by Android", if (capture.silenced) "YES" else "no")
        ReadoutLine("Level", if (capture.rmsDb.isFinite()) "${capture.rmsDb.toInt()} dBFS" else "digital silence")
        ReadoutLine("Frames / with sound", "${capture.framesRead} / ${capture.nonSilentFrames}")
        ReadoutLine("Language", capture.language)
        ReadoutLine("Transcripts", capture.transcripts.toString())
        capture.captureError?.let { ReadoutLine("Error", it) }
    }
}

@Composable
private fun ReadoutLine(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = KavachTokens.InkMuted, fontSize = 14.sp)
        Text(value, color = KavachTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun MicOwner.readable(): String =
    when (this) {
        MicOwner.KAVACH -> "Kavach (exemption applies)"
        MicOwner.RECOGNISER -> "system recogniser (muted in calls)"
        MicOwner.NONE -> "not recording"
    }
