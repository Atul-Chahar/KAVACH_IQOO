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
import com.kavach.domain.SmsMessageAnalyzer

@Composable
fun SmsAnalysisScreen(
    result: SmsMessageAnalyzer.Result,
    onBack: () -> Unit,
    onCall1930: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = messagePalette(result.severity)
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
            Text(
                stringResource(R.string.action_back),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Gap(20.dp)
            Rule(palette, thickness = 3.dp)
            SmallCaps(
                stringResource(R.string.sms_analysis_label),
                KavachTokens.InkSoft,
                Modifier.padding(vertical = 9.dp),
            )
            Rule(KavachTokens.Ink)
            Gap(24.dp)
            PhosphorGlyph(
                if (result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK) {
                    Ph.shieldWarning
                } else {
                    Ph.warning
                },
                34.dp,
                palette,
            )
            Gap(12.dp)
            Text(
                stringResource(titleFor(result.severity)),
                style = MaterialTheme.typography.headlineMedium,
                color = KavachTokens.Ink,
                modifier = Modifier.semantics { heading() },
            )
            Gap(8.dp)
            Text(
                stringResource(bodyFor(result.severity)),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )

            if (result.evidence.isNotEmpty()) {
                Gap(24.dp)
                SmallCaps(stringResource(R.string.sms_evidence_heading), KavachTokens.InkSoft)
                Gap(10.dp)
                result.evidence.forEach { evidence -> EvidenceRow(stringResource(evidenceString(evidence)), palette) }
            }

            Gap(24.dp)
            SmallCaps(stringResource(R.string.action_heading), KavachTokens.InkSoft)
            Gap(10.dp)
            AdviceRow(stringResource(R.string.sms_action_no_link))
            AdviceRow(stringResource(R.string.sms_action_official_channel))
            AdviceRow(stringResource(R.string.action_never_otp))
            Gap(16.dp)
            Text(
                stringResource(R.string.sms_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTokens.InkMuted,
            )
        }

        if (result.severity != SmsMessageAnalyzer.Severity.CLEAR) {
            PillButton(
                label = stringResource(R.string.action_dial_1930),
                onClick = onCall1930,
                icon = Ph.phone,
                container = KavachTokens.PressRed,
                content = KavachTokens.Card,
                height = 52.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EvidenceRow(
    text: String,
    color: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = KavachTokens.Ink)
    }
}

@Composable
private fun AdviceRow(text: String) = EvidenceRow(text, KavachTokens.Cyan)

private fun messagePalette(severity: SmsMessageAnalyzer.Severity): Color =
    when (severity) {
        SmsMessageAnalyzer.Severity.CLEAR -> KavachTokens.Cyan
        SmsMessageAnalyzer.Severity.CAUTION -> KavachTokens.Amber
        SmsMessageAnalyzer.Severity.HIGH_RISK -> KavachTokens.PressRed
    }

private fun titleFor(severity: SmsMessageAnalyzer.Severity): Int =
    when (severity) {
        SmsMessageAnalyzer.Severity.CLEAR -> R.string.sms_clear_title
        SmsMessageAnalyzer.Severity.CAUTION -> R.string.sms_caution_title
        SmsMessageAnalyzer.Severity.HIGH_RISK -> R.string.sms_high_risk_title
    }

private fun bodyFor(severity: SmsMessageAnalyzer.Severity): Int =
    when (severity) {
        SmsMessageAnalyzer.Severity.CLEAR -> R.string.sms_clear_body
        SmsMessageAnalyzer.Severity.CAUTION -> R.string.sms_caution_body
        SmsMessageAnalyzer.Severity.HIGH_RISK -> R.string.sms_high_risk_body
    }

private fun evidenceString(evidence: SmsMessageAnalyzer.Evidence): Int =
    when (evidence) {
        SmsMessageAnalyzer.Evidence.SUSPICIOUS_LINK -> R.string.sms_evidence_suspicious_link
        SmsMessageAnalyzer.Evidence.LINKED_ACTION -> R.string.sms_evidence_linked_action
        SmsMessageAnalyzer.Evidence.CREDENTIAL_REQUEST -> R.string.sms_evidence_credentials
        SmsMessageAnalyzer.Evidence.PAYMENT_OR_REMOTE_ACCESS -> R.string.sms_evidence_payment
        SmsMessageAnalyzer.Evidence.URGENCY_OR_THREAT -> R.string.sms_evidence_urgency
        SmsMessageAnalyzer.Evidence.IMPERSONATION -> R.string.sms_evidence_impersonation
        SmsMessageAnalyzer.Evidence.SECRECY -> R.string.sms_evidence_secrecy
    }
