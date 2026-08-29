package com.kavach.app.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kavach.app.R
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.app.monitor.TacticEvidence
import com.kavach.domain.RiskBand
import java.util.Locale

/**
 * The alert surface.
 *
 * Designed for someone who is elderly, frightened, and being actively
 * manipulated (docs/SAFETY.md §5): a warm paper ground, large serif type, calm
 * instructional copy, no sirens and no countdowns. Every alerting state names
 * the tactics that caused it, because "87% risk" is unfalsifiable while "this
 * caller claims to be police and is asking for your OTP" can be checked in one
 * second.
 *
 * Two surfaces live here. Idle is a quiet home page with a single hero action;
 * a running session is the alerting surface, whose ground changes with the band
 * — cyan-tinted paper while nothing has come up, amber once something has, and
 * one full-bleed press-red for the single dangerous state.
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
    onToggleTranscript: (Boolean) -> Unit = {},
    modelInstalled: Boolean = false,
) {
    if (state.monitoring) {
        MonitorSurface(state, onStop, onToggleTranscript, modifier)
    } else {
        HomeSurface(state, onStartLive, onStartDemo, onOpenReport, onOpenModelSetup, modelInstalled, modifier)
    }
}

// ---------------------------------------------------------------------------
// Idle
// ---------------------------------------------------------------------------

@Composable
private fun HomeSurface(
    state: ShieldUiState,
    onStartLive: () -> Unit,
    onStartDemo: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenModelSetup: () -> Unit,
    modelInstalled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KavachTokens.Gutter)
                .padding(top = 22.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShieldMark(26.dp)
                    GapW(10.dp)
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleSmall,
                        color = KavachTokens.Ink,
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, KavachTokens.Ink.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhosphorGlyph(Ph.translate, 16.dp, KavachTokens.Cyan)
                    GapW(7.dp)
                    Text(
                        stringResource(R.string.home_language_chip),
                        style = MaterialTheme.typography.labelMedium,
                        color = KavachTokens.InkSoft,
                    )
                }
            }

            Gap(30.dp)
            HeroAction(onStartLive)

            Gap(26.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperTile(
                    icon = Ph.playCircle,
                    title = stringResource(R.string.tile_demo_title),
                    subtitle = stringResource(R.string.tile_demo_subtitle),
                    onClick = onStartDemo,
                    modifier = Modifier.weight(1f),
                )
                PaperTile(
                    icon = Ph.fileText,
                    title = stringResource(R.string.tile_incidents_title),
                    subtitle = stringResource(R.string.tile_incidents_subtitle, state.incidents.size),
                    onClick = onOpenReport,
                    modifier = Modifier.weight(1f),
                )
            }
            Gap(12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperTile(
                    icon = Ph.cpu,
                    title = stringResource(R.string.tile_engine_title),
                    subtitle =
                        stringResource(
                            R.string.tile_engine_subtitle,
                            state.lexiconVersion,
                            state.familiesTotal,
                        ),
                    onClick = onOpenModelSetup,
                    modifier = Modifier.weight(1f),
                )
                PaperTile(
                    icon = Ph.brain,
                    title = stringResource(R.string.tile_model_title),
                    subtitle =
                        stringResource(
                            if (modelInstalled) {
                                R.string.tile_model_subtitle_ready
                            } else {
                                R.string.tile_model_subtitle_absent
                            },
                        ),
                    onClick = onOpenModelSetup,
                    tint = KavachTokens.MagentaDeep,
                    modifier = Modifier.weight(1f),
                )
            }

            Gap(16.dp)
            HonestyNote()
            state.failureReason?.let {
                Gap(12.dp)
                FailureNote(it)
            }
            Gap(20.dp)
        }

        PillNav(
            selected = 0,
            onSelect = { index ->
                if (index == 1) {
                    onOpenReport()
                } else if (index == 2) {
                    onOpenModelSetup()
                }
            },
            labels =
                listOf(
                    stringResource(R.string.nav_home),
                    stringResource(R.string.nav_report),
                    stringResource(R.string.nav_setup),
                ),
            icons = listOf(Ph.house, Ph.fileText, Ph.gearSix),
            modifier = Modifier.padding(horizontal = KavachTokens.Gutter, vertical = 10.dp),
        )
    }
}

/**
 * The one hero action. A soft cyan halo breathes behind it — the only motion in
 * the app, and it stops entirely once a session starts, because a pulsing light
 * on an alerting screen would be exactly the panic aesthetic SAFETY.md forbids.
 */
@Composable
private fun HeroAction(onStart: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "halo")
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 3400),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "halo-scale",
    )
    val startLabel = stringResource(R.string.home_hero_title)

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(164.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onStart)
                .semantics { contentDescription = startLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size((164 * pulse).dp)
                    .background(
                        Brush.radialGradient(
                            listOf(KavachTokens.Cyan.copy(alpha = 0.20f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Box(
                Modifier
                    .size(120.dp)
                    .border(1.dp, KavachTokens.Cyan.copy(alpha = 0.26f), CircleShape),
            )
            Box(
                Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(KavachTokens.Card),
                contentAlignment = Alignment.Center,
            ) {
                PhosphorGlyph(Ph.microphone, 34.dp, KavachTokens.Cyan)
            }
        }
        Gap(18.dp)
        Text(
            startLabel,
            style = MaterialTheme.typography.headlineSmall,
            color = KavachTokens.Ink,
            textAlign = TextAlign.Center,
        )
        Gap(6.dp)
        Text(
            stringResource(R.string.home_hero_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTokens.InkFaint,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// A running session
// ---------------------------------------------------------------------------

@Composable
private fun MonitorSurface(
    state: ShieldUiState,
    onStop: () -> Unit,
    onToggleTranscript: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RiskColors.paletteFor(state.band)
    val ground by animateColorAsState(palette.ground, label = "risk-ground")
    val highRisk = state.band == RiskBand.HIGH_RISK

    Column(
        modifier
            .fillMaxSize()
            .background(ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = KavachTokens.Gutter)
            .padding(top = 24.dp, bottom = 24.dp),
    ) {
        SessionHeader(state, palette)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Gap(if (highRisk) 14.dp else 22.dp)
            Announcement(state, palette)

            when (state.band) {
                RiskBand.WATCHING -> WatchingBody(state, palette, onToggleTranscript)
                RiskBand.CAUTION -> CautionBody(state, palette, onToggleTranscript)
                RiskBand.HIGH_RISK -> HighRiskBody(state, palette)
            }
        }

        Gap(12.dp)
        SessionFooter(state, palette, highRisk, onStop)
    }
}

/** Status, elapsed clock, and — in demo mode — a standing label that says so. */
@Composable
private fun SessionHeader(
    state: ShieldUiState,
    palette: BandPalette,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state.band) {
                RiskBand.WATCHING ->
                    Box(Modifier.size(9.dp).clip(CircleShape).background(palette.accent))
                RiskBand.CAUTION -> PhosphorGlyph(Ph.warning, 20.dp, palette.accent)
                RiskBand.HIGH_RISK -> PhosphorGlyph(Ph.shieldWarning, 20.dp, palette.ink)
            }
            GapW(9.dp)
            Text(
                if (state.band == RiskBand.HIGH_RISK) {
                    stringResource(R.string.app_name)
                } else {
                    stringResource(R.string.status_listening)
                },
                style = MaterialTheme.typography.labelLarge,
                color = palette.ink,
            )
            GapW(8.dp)
            Text(
                elapsed(state.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Normal,
                color = palette.inkSoft,
            )
        }

        // The badge and the family count sit side by side rather than in an
        // either/or. They were exclusive, with HIGH_RISK winning, so the DEMO
        // badge vanished at precisely the moment the screen is least
        // distinguishable from a real scam alert — full-bleed red, "LIKELY
        // SCAM", 1930 on offer. A replayed fixture must never be able to
        // impersonate a live verdict to someone who did not start it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.mode == MonitorMode.DEMO) {
                Text(
                    stringResource(R.string.demo_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.14.em,
                    color = KavachTokens.Paper,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(KavachTokens.RadiusTag))
                            .background(KavachTokens.Ink)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                GapW(8.dp)
            }
            if (state.band == RiskBand.HIGH_RISK) {
                Text(
                    stringResource(R.string.families_of_total, state.familiesSeen, state.familiesTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSoft,
                )
            }
        }
    }
}

/**
 * The headline.
 *
 * "Nothing unusual so far" while a tactic has in fact been heard reads as "I am
 * not listening" — the one thing this screen must never imply. Below the warning
 * threshold Kavach names what it heard instead of denying it.
 */
@Composable
private fun headline(state: ShieldUiState): String =
    when {
        state.band == RiskBand.WATCHING && state.familiesSeen > 0 ->
            stringResource(R.string.state_noticed_title)
        state.band == RiskBand.WATCHING -> stringResource(R.string.state_watching_title)
        state.band == RiskBand.CAUTION -> stringResource(R.string.state_caution_title)
        else -> stringResource(R.string.state_high_risk_title)
    }

/** The sentence under it, which is where the family-diversity rule is explained. */
@Composable
private fun explanation(state: ShieldUiState): String =
    when {
        state.band == RiskBand.WATCHING && state.familiesSeen > 0 ->
            stringResource(R.string.state_noticed_body, state.familiesSeen, state.familiesForWarning)
        state.band == RiskBand.WATCHING ->
            stringResource(R.string.state_watching_body, state.familiesTotal)
        state.band == RiskBand.CAUTION ->
            stringResource(R.string.state_caution_body, state.familiesSeen, state.familiesTotal)
        else -> stringResource(R.string.state_high_risk_body)
    }

/**
 * The headline and its explanation.
 *
 * Marked as an assertive live region so a TalkBack user is told the state
 * changed without having to explore the screen — the one place in this app
 * where interrupting the user is the correct behaviour.
 */
@Composable
private fun Announcement(
    state: ShieldUiState,
    palette: BandPalette,
) {
    val announcement =
        when (state.band) {
            RiskBand.WATCHING -> null
            RiskBand.CAUTION ->
                stringResource(R.string.announce_caution, state.familiesSeen, state.familiesTotal)
            RiskBand.HIGH_RISK ->
                stringResource(R.string.announce_high_risk, state.familiesSeen, state.familiesTotal)
        }

    Column(
        Modifier.semantics {
            heading()
            if (announcement != null) {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = announcement
            }
        },
    ) {
        Text(
            headline(state),
            style =
                if (state.band == RiskBand.HIGH_RISK) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displaySmall
                },
            color = palette.ink,
        )
        Gap(if (state.band == RiskBand.HIGH_RISK) 8.dp else 12.dp)
        Text(
            explanation(state),
            style =
                if (state.band == RiskBand.HIGH_RISK) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            color = palette.inkSoft,
        )
    }
}

/** Nothing has come up: show that Kavach is alive, and state the rule it follows. */
@Composable
private fun ColumnScope.WatchingBody(
    state: ShieldUiState,
    palette: BandPalette,
    onToggleTranscript: (Boolean) -> Unit,
) {
    Gap(34.dp)
    Waveform(palette.accent)

    Gap(34.dp)
    Rule(palette.rule)
    Gap(20.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            stringResource(R.string.families_seen_label),
            style = MaterialTheme.typography.titleSmall,
            color = palette.ink,
        )
        Text(
            stringResource(R.string.families_count, state.familiesSeen, state.familiesTotal),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            color = palette.inkSoft,
        )
    }
    Gap(14.dp)
    FamilyMeter(
        seen = state.familiesSeen,
        total = state.familiesTotal,
        filled = palette.accent,
        empty = palette.accent.copy(alpha = 0.22f),
    )
    Gap(14.dp)
    Text(
        stringResource(R.string.families_rule_explainer, state.familiesForWarning),
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkSoft,
    )

    Gap(26.dp)
    Rule(palette.rule)
    Gap(20.dp)
    TranscriptToggle(state, palette, onToggleTranscript)
}

/** Something has come up: rank it, number it, and say what would raise it. */
@Composable
private fun ColumnScope.CautionBody(
    state: ShieldUiState,
    palette: BandPalette,
    onToggleTranscript: (Boolean) -> Unit,
) {
    Gap(22.dp)
    FamilyMeter(
        seen = state.familiesSeen,
        total = state.familiesTotal,
        filled = palette.accent,
        empty = KavachTokens.Ink.copy(alpha = 0.15f),
    )
    Gap(8.dp)
    SmallCaps(
        stringResource(R.string.families_strongest_first, state.familiesSeen, state.familiesTotal),
        palette.inkMuted,
    )

    Gap(24.dp)
    EvidenceList(state.tacticEvidence, state.elapsedMs, palette)

    // Only shown while a warning is still out of reach. Naming the evidence that
    // is *absent* is what makes the threshold legible instead of arbitrary — but
    // once the families are already there the sentence would be a plain lie.
    val remaining = state.familiesForWarning - state.familiesSeen
    if (remaining > 0) {
        Rule(palette.rule)
        RankedRow(
            rank = state.tacticEvidence.size + 1,
            title = stringResource(R.string.next_family_title),
            caption =
                if (remaining == 1) {
                    stringResource(R.string.next_family_subtitle)
                } else {
                    stringResource(R.string.next_family_subtitle_plural, remaining)
                },
            palette = palette,
            dimmed = true,
        )
    }
    Rule(palette.rule)

    Gap(22.dp)
    TranscriptToggle(state, palette, onToggleTranscript)
}

/**
 * The one dangerous state: what to do, then why, then the words.
 *
 * The instructions come first and sit on paper laid over the red, because
 * someone being actively manipulated needs an action before an explanation.
 */
@Composable
private fun ColumnScope.HighRiskBody(
    state: ShieldUiState,
    palette: BandPalette,
) {
    Gap(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusSheet))
            .background(KavachTokens.PressRedPaper)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionRow(Ph.phoneX, R.string.action_hang_up_lead, R.string.action_hang_up_rest)
        Rule(KavachTokens.Ink.copy(alpha = 0.12f))
        ActionRow(Ph.password, R.string.action_never_otp_lead, R.string.action_never_otp_rest)
        Rule(KavachTokens.Ink.copy(alpha = 0.12f))
        ActionRow(Ph.phoneCall, R.string.action_call_1930_lead, R.string.action_call_1930_rest)
    }

    Gap(16.dp)
    SmallCaps(stringResource(R.string.why_strongest_first), palette.inkSoft)
    Gap(8.dp)
    EvidenceList(state.tacticEvidence, state.elapsedMs, palette, compact = true)

    if (state.transcriptPreview.isNotBlank()) {
        Gap(8.dp)
        Text(
            stringResource(R.string.heard_not_saved, state.transcriptPreview.takeLast(HEARD_CHARS)),
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink,
        )
    }
    state.degradedReason?.let {
        Gap(10.dp)
        Text(it, style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
    }
}

@Composable
private fun ActionRow(
    icon: PhosphorIcon,
    leadRes: Int,
    restRes: Int,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhosphorGlyph(icon, 24.dp, KavachTokens.PressRed)
        Text(
            buildString {
                append(stringResource(leadRes))
                append(' ')
                append(stringResource(restRes))
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            color = KavachTokens.Ink,
        )
    }
}

/** The numbered evidence rows, separated by hairline rules. */
@Composable
private fun EvidenceList(
    evidence: List<TacticEvidence>,
    elapsedMs: Long,
    palette: BandPalette,
    compact: Boolean = false,
) {
    evidence.forEachIndexed { index, item ->
        Rule(palette.rule)
        RankedRow(
            rank = index + 1,
            title = item.displayName,
            caption =
                stringResource(
                    R.string.tactic_caption,
                    item.familyLabel,
                    stringResource(
                        R.string.seconds_ago,
                        ((elapsedMs - item.lastSeenElapsedMs).coerceAtLeast(0L) / 1000L).toInt(),
                    ),
                ),
            palette = palette,
            compact = compact,
        )
    }
}

/** "Show the words Kavach heard" — off by default, and it says why. */
@Composable
private fun TranscriptToggle(
    state: ShieldUiState,
    palette: BandPalette,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.show_words_label),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
            )
            Text(
                stringResource(R.string.show_words_note),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkMuted,
            )
        }
        GapW(12.dp)
        PaperToggle(
            checked = state.showTranscript,
            onCheckedChange = onToggle,
            label = stringResource(R.string.show_words_label),
            palette = palette,
        )
    }

    if (state.showTranscript && state.transcriptPreview.isNotBlank()) {
        Gap(12.dp)
        Text(
            state.transcriptPreview.takeLast(HEARD_CHARS),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSoft,
        )
    }
}

/**
 * The stop control, and above it the standing reminder that silence is not
 * proof of safety — the one sentence that must never be dropped from any state.
 */
@Composable
private fun SessionFooter(
    state: ShieldUiState,
    palette: BandPalette,
    highRisk: Boolean,
    onStop: () -> Unit,
) {
    state.failureReason?.let {
        FailureNote(it)
        Gap(10.dp)
    }

    if (highRisk) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                label = stringResource(R.string.action_not_a_scam),
                onClick = onStop,
                filled = false,
                content = palette.ink,
                height = 50.dp,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = stringResource(R.string.action_stop_listening),
                onClick = onStop,
                icon = Ph.stopCircle,
                container = palette.accent,
                content = palette.onAccent,
                height = 50.dp,
                modifier = Modifier.weight(1.4f),
            )
        }
    } else {
        if (state.band == RiskBand.WATCHING) {
            Text(
                stringResource(R.string.engine_label, state.engineName),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkMuted,
            )
        } else {
            Text(
                stringResource(R.string.safety_no_guarantee),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
        }
        Gap(12.dp)
        PillButton(
            label = stringResource(R.string.action_stop_listening),
            onClick = onStop,
            icon = Ph.stopCircle,
            filled = false,
            content = palette.ink,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** A still bar chart. It suggests a live mic without pretending to be a meter. */
@Composable
private fun Waveform(color: Color) {
    val heights =
        listOf(
            0.18f,
            0.34f,
            0.62f,
            0.88f,
            0.46f,
            0.70f,
            1f,
            0.52f,
            0.26f,
            0.60f,
            0.38f,
            0.74f,
            0.22f,
            0.48f,
            0.30f,
            0.64f,
            0.16f,
            0.42f,
        )
    Row(
        Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { fraction ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .alpha(0.3f + fraction * 0.7f)
                    .background(color, RoundedCornerShape(KavachTokens.RadiusTag)),
            )
        }
    }
}

@Composable
private fun HonestyNote() {
    Text(
        buildString {
            append(stringResource(R.string.safety_no_guarantee))
            append(' ')
            append(stringResource(R.string.safety_pattern_disclaimer))
        },
        style = MaterialTheme.typography.labelMedium,
        color = KavachTokens.InkFaint,
    )
}

/**
 * Capture failed. Never rendered as a quiet footnote: if Kavach has stopped
 * listening the user must be told plainly, because a screen that still says
 * "listening" while nothing is would be the worst failure this app can have.
 */
@Composable
private fun FailureNote(reason: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(KavachTokens.PressRed)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorGlyph(Ph.microphoneSlash, 22.dp, KavachTokens.PressRedPaper)
        Text(
            stringResource(R.string.capture_failed, reason),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTokens.PressRedPaper,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

private const val HEARD_CHARS = 160

private fun elapsed(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
