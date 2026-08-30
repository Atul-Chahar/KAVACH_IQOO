package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kavach.app.R
import com.kavach.app.message.MessageDetection
import com.kavach.domain.SmsMessageAnalyzer
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageGuardScreen(
    granted: Boolean,
    connected: Boolean,
    detections: List<MessageDetection>,
    onEnable: () -> Unit,
    onOpenDetection: (MessageDetection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = KavachTokens.Gutter, vertical = 22.dp),
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onBack).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorGlyph(Ph.arrowLeft, 20.dp, KavachTokens.InkSoft)
            GapW(10.dp)
            Text(stringResource(R.string.action_back), color = KavachTokens.InkSoft)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Gap(22.dp)
            SmallCaps(stringResource(R.string.message_guard_kicker), KavachTokens.Cyan)
            Gap(8.dp)
            Text(
                stringResource(R.string.message_guard_title),
                style = MaterialTheme.typography.headlineMedium,
                color = KavachTokens.Ink,
                modifier = Modifier.semantics { heading() },
            )
            Gap(8.dp)
            Text(
                stringResource(R.string.message_guard_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )
            Gap(20.dp)
            AccessCard(granted, connected, onEnable)
            Gap(24.dp)
            SmallCaps(stringResource(R.string.message_guard_recent), KavachTokens.InkSoft)
            Gap(10.dp)
            if (detections.isEmpty()) {
                Text(
                    stringResource(R.string.message_guard_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTokens.InkMuted,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                detections.forEach { detection ->
                    DetectionCard(detection, onOpenDetection)
                    Gap(10.dp)
                }
            }
            Gap(20.dp)
            Text(
                stringResource(R.string.message_guard_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTokens.InkMuted,
            )
        }
    }
}

/**
 * Says what Message Guard is actually doing, which is not the same question as
 * what the user granted.
 *
 * Three states, not two. Android unbinds notification listeners on its own and
 * does not always rebind, so "the user granted notification access" and "we are
 * being handed notifications" come apart — and when they do, the honest answer
 * is the middle one. Reporting the grant as the capability is how this screen
 * would end up claiming messages are checked while nothing is, which is the
 * message-side version of a calm shield over a call we cannot hear.
 */
@Composable
private fun AccessCard(
    granted: Boolean,
    connected: Boolean,
    onEnable: () -> Unit,
) {
    val working = granted && connected
    val color =
        when {
            working -> KavachTokens.Cyan
            granted -> KavachTokens.PressRed
            else -> KavachTokens.Amber
        }
    val title =
        when {
            working -> R.string.message_guard_on
            granted -> R.string.message_guard_stalled
            else -> R.string.message_guard_off
        }
    val body =
        when {
            working -> R.string.message_guard_on_body
            granted -> R.string.message_guard_stalled_body
            else -> R.string.message_guard_off_body
        }
    Column(
        Modifier
            .fillMaxWidth()
            .background(KavachTokens.Card, RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorGlyph(if (working) Ph.shieldCheck else Ph.warning, 24.dp, color)
            GapW(10.dp)
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = KavachTokens.Ink,
            )
        }
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTokens.InkSoft,
        )
        if (!working) {
            PillButton(
                label = stringResource(R.string.message_guard_enable),
                onClick = onEnable,
                container = KavachTokens.Cyan,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
            )
        }
    }
}

@Composable
private fun DetectionCard(
    detection: MessageDetection,
    onOpen: (MessageDetection) -> Unit,
) {
    val color = severityColor(detection.result.severity)
    Row(
        Modifier
            .fillMaxWidth()
            .background(KavachTokens.Card, RoundedCornerShape(KavachTokens.RadiusCard))
            .clickable(role = Role.Button) { onOpen(detection) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorGlyph(
            if (detection.result.severity == SmsMessageAnalyzer.Severity.CLEAR) Ph.shieldCheck else Ph.shieldWarning,
            25.dp,
            color,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(severityTitle(detection.result.severity)),
                style = MaterialTheme.typography.labelLarge,
                color = KavachTokens.Ink,
            )
            Text(
                listOfNotNull(detection.conversation, formatTimestamp(detection.detectedAtMillis))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = KavachTokens.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhosphorGlyph(Ph.arrowRight, 18.dp, KavachTokens.InkMuted)
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun severityColor(severity: SmsMessageAnalyzer.Severity): Color =
    when (severity) {
        SmsMessageAnalyzer.Severity.CLEAR -> KavachTokens.Cyan
        SmsMessageAnalyzer.Severity.CAUTION -> KavachTokens.Amber
        SmsMessageAnalyzer.Severity.HIGH_RISK -> KavachTokens.PressRed
    }

private fun severityTitle(severity: SmsMessageAnalyzer.Severity): Int =
    when (severity) {
        SmsMessageAnalyzer.Severity.CLEAR -> R.string.sms_clear_title
        SmsMessageAnalyzer.Severity.CAUTION -> R.string.sms_caution_title
        SmsMessageAnalyzer.Severity.HIGH_RISK -> R.string.sms_high_risk_title
    }
