package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kavach.app.R
import com.kavach.domain.Incident
import com.kavach.domain.RiskBand

/**
 * The incident record, set like a printed page.
 *
 * Metadata only — timestamp, tactic families, score, duration. Never audio and
 * never a transcript (docs/SAFETY.md §6), which is why the page can say plainly
 * that none can be shared: there is nothing to share. The rules and the
 * standing head are doing real work here — this is the artefact a person might
 * hand to a bank or the police, and it should look like a record rather than a
 * screenshot.
 */
@Composable
fun ReportScreen(
    incidents: List<Incident>,
    lexiconVersion: String,
    nameFor: (String) -> String,
    formatDate: (Long) -> String,
    onShare: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = KavachTokens.Gutter)
            .padding(top = 22.dp, bottom = 22.dp),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onBack)
                .padding(vertical = 4.dp),
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

            // The masthead: a heavy rule, the standing head, then a light rule.
            Rule(KavachTokens.Ink, thickness = 3.dp)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SmallCaps(stringResource(R.string.report_title), KavachTokens.InkSoft)
                SmallCaps("Lexicon $lexiconVersion", KavachTokens.InkSoft)
            }
            Rule(KavachTokens.Ink)

            Gap(14.dp)
            Text(
                if (incidents.isEmpty()) {
                    stringResource(R.string.report_empty_title)
                } else {
                    stringResource(R.string.report_count_title, incidents.size)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = KavachTokens.Ink,
                modifier = Modifier.semantics { heading() },
            )
            Gap(8.dp)
            Text(
                stringResource(R.string.report_metadata_note),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )

            incidents.forEach { incident ->
                Gap(24.dp)
                Rule(KavachTokens.Ink.copy(alpha = 0.16f))
                Gap(16.dp)
                IncidentEntry(incident, nameFor, formatDate)
            }

            Gap(14.dp)
            Rule(KavachTokens.Ink.copy(alpha = 0.16f))
            Gap(12.dp)
            Text(
                stringResource(R.string.report_footer_note),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTokens.InkSoft,
            )
            Gap(20.dp)
        }

        Gap(8.dp)
        PillButton(
            label = stringResource(R.string.action_share_report),
            onClick = onShare,
            icon = Ph.shareNetwork,
            container = KavachTokens.Cyan,
            content = KavachTokens.Card,
            height = 52.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One incident: when, how bad, the three numbers, and the families in words. */
@Composable
private fun IncidentEntry(
    incident: Incident,
    nameFor: (String) -> String,
    formatDate: (Long) -> String,
) {
    val bandColor =
        when (incident.band) {
            RiskBand.HIGH_RISK -> KavachTokens.PressRed
            RiskBand.CAUTION -> KavachTokens.Amber
            RiskBand.WATCHING -> KavachTokens.InkMuted
        }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDate(incident.startedAtEpochMs),
            style = MaterialTheme.typography.titleLarge,
            color = KavachTokens.Ink,
        )
        Text(
            incident.band.name.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.06.em,
            color = bandColor,
            modifier =
                Modifier
                    .border(1.dp, bandColor.copy(alpha = 0.35f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }

    Gap(12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Stat(stringResource(R.string.report_stat_score), "${incident.peakScore}/100")
        Stat(stringResource(R.string.report_stat_duration), "${incident.durationMs / 1000}s")
        Stat(stringResource(R.string.report_stat_families), "${incident.tactics.size}")
    }

    Gap(16.dp)
    incident.tactics.forEach { familyId ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = KavachTokens.MagentaDeep)
            Text(
                nameFor(familyId),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.Ink,
            )
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
) {
    Row {
        Text(
            "$label ",
            style = MaterialTheme.typography.bodySmall,
            color = KavachTokens.InkMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = KavachTokens.Ink,
        )
    }
}
