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
import androidx.compose.ui.unit.dp
import com.kavach.app.R
import com.kavach.app.message.MessageDetection
import com.kavach.domain.SmsMessageAnalyzer
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageGuardScreen(
    enabled: Boolean,
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
            AccessCard(enabled, onEnable)
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

@Composable
private fun AccessCard(
    enabled: Boolean,
    onEnable: () -> Unit,
) {
    val color = if (enabled) KavachTokens.Cyan else KavachTokens.Amber
    Column(
        Modifier
            .fillMaxWidth()
            .background(KavachTokens.Card, RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorGlyph(if (enabled) Ph.shieldCheck else Ph.warning, 24.dp, color)
            GapW(10.dp)
            Text(
                stringResource(if (enabled) R.string.message_guard_on else R.string.message_guard_off),
                style = MaterialTheme.typography.titleSmall,
                color = KavachTokens.Ink,
            )
        }
        Text(
            stringResource(if (enabled) R.string.message_guard_on_body else R.string.message_guard_off_body),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTokens.InkSoft,
        )
        if (!enabled) {
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
                formatTimestamp(detection.detectedAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = KavachTokens.InkMuted,
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
