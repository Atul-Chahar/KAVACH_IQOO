package com.kavach.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.R
import com.kavach.app.message.MessageDetection
import com.kavach.app.message.MessageGuardStrings
import com.kavach.domain.SmsMessageAnalyzer
import kotlinx.coroutines.delay

/**
 * The message capsule — the same Dynamic Island language the call shield uses,
 * saying the one thing that matters about a message that just arrived.
 *
 * It is a capsule and not a full-screen warning on purpose. A message is not
 * live: nobody is on the line applying pressure, and there is nothing the user
 * has to decide in the next three seconds. Taking the whole screen for one
 * would train people to dismiss Kavach reflexively, which costs us the call
 * alert — the one that does need the screen.
 *
 * It draws no background of its own beyond the capsule, because it is hosted in
 * a window exactly its own size — see [MessageIslandOverlay]. Everything
 * outside the capsule belongs to whatever app the user is actually using.
 */
@Composable
fun MessageIsland(
    detection: MessageDetection,
    onOpenDetails: () -> Unit,
    onCall1930: () -> Unit,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Auto-dismiss, so the capsule can never be the thing left covering the top
    // of someone's screen. A coroutine delay and not a posted Runnable: it dies
    // with the composition if the user acts first.
    LaunchedEffect(detection.id) {
        delay(VISIBLE_MS)
        onDismiss()
    }

    val highRisk = detection.result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK
    val accent = if (highRisk) KavachTokens.PressRedUnderline else KavachTokens.Amber

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(min = 200.dp, max = 360.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (highRisk) Color(0xFF2A1513) else Color(0xFF261D11))
                .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(accent)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = headline(detection),
                        color = Color(0xFFF3F4F6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detection.result.ranked(1).forEach { evidence ->
                        Text(
                            text = stringResource(MessageGuardStrings.evidence(evidence)),
                            color = Color(0xFFD8D2CE),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.size(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val cell = Modifier.weight(1f)
                IslandAction(stringResource(R.string.message_island_details), accent, cell, onOpenDetails)
                IslandAction(stringResource(R.string.action_dial_1930), accent, cell, onCall1930)
                IslandAction(stringResource(R.string.message_action_trust), null, cell, onTrust)
            }
        }
    }
}

@Composable
private fun headline(detection: MessageDetection): String {
    val highRisk = detection.result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK
    val conversation = detection.conversation
    return when {
        conversation != null && highRisk -> stringResource(R.string.message_warning_high_from, conversation)
        conversation != null -> stringResource(R.string.message_warning_caution_from, conversation)
        highRisk -> stringResource(R.string.message_warning_high)
        else -> stringResource(R.string.message_warning_caution)
    }
}

@Composable
private fun PulsingDot(accent: Color) {
    val transition = rememberInfiniteTransition(label = "messagePulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "messagePulseScale",
    )
    Box(
        Modifier
            .size(10.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(accent),
    )
}

@Composable
private fun IslandAction(
    label: String,
    accent: Color?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF2B2725))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = accent ?: Color(0xFFBFB8B3),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How long the capsule stays up before removing itself. */
private const val VISIBLE_MS = 9_000L
