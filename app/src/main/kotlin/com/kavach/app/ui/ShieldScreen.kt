package com.kavach.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kavach.app.capture.CaptureState
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.app.monitor.TacticEvidence
import com.kavach.app.setup.Capability
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sin

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
    capture: CaptureState = CaptureState(),
    onStartLive: () -> Unit,
    onStartDemo: () -> Unit,
    onStop: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenMessageGuard: () -> Unit,
    onOpenModelSetup: () -> Unit,
    modifier: Modifier = Modifier,
    unreviewedMessages: Int = 0,
    onToggleTranscript: (Boolean) -> Unit = {},
    onDismissAlert: () -> Unit = {},
    onCall1930: () -> Unit = {},
    modelInstalled: Boolean = false,
    capabilities: List<Capability> = emptyList(),
    onFixCapability: (String) -> Unit = {},
) {
    if (state.monitoring) {
        MonitorSurface(state, capture, onStop, onToggleTranscript, onDismissAlert, onCall1930, modifier)
    } else {
        HomeSurface(
            state,
            onStartLive,
            onStartDemo,
            onOpenReport,
            onOpenMessageGuard,
            unreviewedMessages,
            onOpenModelSetup,
            modelInstalled,
            capabilities,
            onFixCapability,
            modifier,
        )
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
    onOpenMessageGuard: () -> Unit,
    unreviewedMessages: Int,
    onOpenModelSetup: () -> Unit,
    modelInstalled: Boolean,
    capabilities: List<Capability>,
    onFixCapability: (String) -> Unit,
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

            Gap(24.dp)
            CapabilityStrip(capabilities, onFixCapability)

            Gap(14.dp)
            PaperTile(
                icon = Ph.shieldWarning,
                title = stringResource(R.string.message_guard_title),
                subtitle = stringResource(R.string.message_guard_tile_subtitle),
                onClick = onOpenMessageGuard,
                tint = KavachTokens.MagentaDeep,
                modifier = Modifier.fillMaxWidth(),
                // Only ever a count of warnings, never of messages: the store
                // does not keep the ones it found nothing wrong with.
                badge =
                    unreviewedMessages
                        .takeIf { it > 0 }
                        ?.let { stringResource(R.string.message_guard_badge, it) },
            )

            Gap(12.dp)
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
 * What this install can actually do, one line per capability.
 *
 * Modelled on the Google Personal Safety app's feature list, where every
 * feature carries its own status rather than the app carrying one overall
 * verdict. The difference matters here: Kavach's permission chain has four
 * independent links and failing any one of them removes a *different*
 * capability, so a single "Manual sessions only" tells the user they are
 * degraded without telling them what stopped working.
 *
 * Colour follows the tokens' existing meanings rather than a traffic light —
 * cyan is already "Kavach is working" and amber is already "something has come
 * up", so no new signal is invented and green never appears, because green
 * anywhere in this app would read as "you are safe".
 */
@Composable
private fun CapabilityStrip(
    capabilities: List<Capability>,
    onFix: (String) -> Unit,
) {
    if (capabilities.isEmpty()) return
    val allReady = capabilities.all { it.ready }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(KavachTokens.Card)
            .border(1.dp, KavachTokens.Ink.copy(alpha = 0.10f), RoundedCornerShape(KavachTokens.RadiusCard))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        SmallCaps(
            stringResource(if (allReady) R.string.home_status_ready else R.string.home_status_partial),
            if (allReady) KavachTokens.Cyan else KavachTokens.Amber,
        )
        Gap(12.dp)
        capabilities.forEachIndexed { index, capability ->
            if (index > 0) {
                Gap(10.dp)
                Rule(KavachTokens.Ink.copy(alpha = 0.08f))
                Gap(10.dp)
            }
            CapabilityRow(capability, onFix)
        }
    }
}

@Composable
private fun CapabilityRow(
    capability: Capability,
    onFix: (String) -> Unit,
) {
    val tint = if (capability.ready) KavachTokens.Cyan else KavachTokens.Amber
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                capability.fix?.let { fix ->
                    Modifier.clickable(role = Role.Button) { onFix(fix) }
                } ?: Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorGlyph(
            if (capability.ready) Ph.shieldCheck else Ph.warning,
            18.dp,
            tint,
        )
        GapW(11.dp)
        Column(Modifier.weight(1f)) {
            Text(
                capability.name,
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.Ink,
            )
            capability.missing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = KavachTokens.Amber,
                )
            }
        }
        if (!capability.ready) {
            PhosphorGlyph(Ph.arrowRight, 16.dp, KavachTokens.InkMuted)
        }
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
    capture: CaptureState,
    onStop: () -> Unit,
    onToggleTranscript: (Boolean) -> Unit,
    onDismissAlert: () -> Unit,
    onCall1930: () -> Unit,
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

            if (state.mode == MonitorMode.DEMO && state.band != RiskBand.WATCHING) {
                Gap(12.dp)
                SimulationNote(palette)
            }

            when (state.band) {
                RiskBand.WATCHING -> WatchingBody(state, capture, palette, onToggleTranscript)
                RiskBand.CAUTION -> CautionBody(state, capture, palette, onToggleTranscript)
                RiskBand.HIGH_RISK -> HighRiskBody(state, palette)
            }
        }

        Gap(12.dp)
        SessionFooter(state, palette, highRisk, onStop, onDismissAlert, onCall1930)
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
    capture: CaptureState,
    palette: BandPalette,
    onToggleTranscript: (Boolean) -> Unit,
) {
    Gap(28.dp)
    Waveform(palette.accent, capture)
    Gap(12.dp)
    HearingLine(capture, palette)

    Gap(26.dp)
    Rule(palette.rule)
    Gap(20.dp)
    RiskTrack(state, palette)

    Gap(24.dp)
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
    capture: CaptureState,
    palette: BandPalette,
    onToggleTranscript: (Boolean) -> Unit,
) {
    Gap(20.dp)
    RiskTrack(state, palette)
    Gap(10.dp)
    HearingLine(capture, palette)

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
        Gap(12.dp)
        TranscriptBlock(state.transcriptPreview, palette)
    }
    state.degradedReason?.let {
        Gap(10.dp)
        Text(it, style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
    }
}

/**
 * The simulation notice, borrowed from the Google Personal Safety app's demo
 * flow, which says plainly: "Keep in mind, this is a simulation. No emergency
 * actions will be started."
 *
 * A badge in the header is not enough on the HIGH_RISK surface. That screen is
 * full-bleed press-red, reads LIKELY SCAM at 40sp and offers the cybercrime
 * helpline; a small chip is not what a person takes away from it. Anyone shown
 * a replay — a relative, a judge, someone who picked up the phone mid-demo —
 * has to be able to tell in one glance that nothing was really detected.
 *
 * Drawn as a hairline box with no fill, so it reads as a printed marginal note
 * rather than another alert competing with the one above it.
 */
@Composable
private fun SimulationNote(palette: BandPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.ink.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhosphorGlyph(Ph.playCircle, 20.dp, palette.ink)
        Text(
            stringResource(R.string.demo_simulation_note),
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink,
        )
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
        TranscriptBlock(state.transcriptPreview, palette)
    }
}

/**
 * The words Kavach just heard, set to be read rather than skimmed past.
 *
 * This was one line of `bodySmall` in the secondary ink, on paper of nearly the
 * same value — indistinguishable from the explanatory copy above it, which made
 * the single most useful signal on the screen invisible. It is now a block: full
 * -strength ink, a tinted ground, and a spot-colour rule down the left edge, so
 * it reads as quoted material and can be found at a glance from arm's length.
 */
@Composable
private fun TranscriptBlock(
    transcript: String,
    palette: BandPalette,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(KavachTokens.RadiusTag))
            .background(palette.accent.copy(alpha = TRANSCRIPT_WASH)),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(palette.accent),
        )
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            SmallCaps(stringResource(R.string.heard_label), palette.accent)
            Gap(6.dp)
            Text(
                transcript.takeLast(HEARD_CHARS),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
            )
        }
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
    onDismissAlert: () -> Unit,
    onCall1930: () -> Unit,
) {
    state.failureReason?.let {
        FailureNote(it)
        Gap(10.dp)
    }

    if (highRisk) {
        // The screen already tells the user to report the call on 1930, and
        // until now offered no way to do it — the helpline was a sentence, not
        // an action. It is the first control here because it is the only one
        // that helps, and it is dragged rather than tapped for the same reason
        // the overlay's is.
        SlideToConfirm(
            label = stringResource(R.string.action_slide_to_dial_1930),
            icon = Ph.phoneCall,
            track = palette.ink.copy(alpha = 0.14f),
            thumb = palette.accent,
            onThumb = palette.onAccent,
            ink = palette.ink,
            onConfirm = onCall1930,
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                label = stringResource(R.string.action_not_a_scam),
                onClick = onDismissAlert,
                filled = false,
                content = palette.ink,
                height = 50.dp,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                label = stringResource(R.string.action_stop_listening),
                onClick = onStop,
                icon = Ph.stopCircle,
                filled = false,
                content = palette.ink,
                height = 50.dp,
                modifier = Modifier.weight(1f),
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

/**
 * The live risk track.
 *
 * The whole screen used to change only at 40 and 70 — the ground went amber,
 * then red — and between those two moments it looked exactly the same whether
 * the engine was scoring hard or had stopped receiving audio an hour ago. There
 * was no way to tell a working session from a dead one until it happened to
 * cross a line.
 *
 * So the number is drawn, moving, with both thresholds marked on the track
 * itself. The thresholds come from [ShieldUiState], which reads them from the
 * lexicon the scorer uses, so this can never disagree with the engine about
 * where the lines are.
 */
@Composable
private fun RiskTrack(
    state: ShieldUiState,
    palette: BandPalette,
) {
    val target = state.score.coerceIn(0, RiskAssessment.MAX_SCORE)
    val fill by animateFloatAsState(
        targetValue = target.toFloat() / RiskAssessment.MAX_SCORE,
        animationSpec = tween(SCORE_ANIM_MS, easing = FastOutSlowInEasing),
        label = "risk-fill",
    )
    val shown by animateIntAsState(
        targetValue = target,
        animationSpec = tween(SCORE_ANIM_MS),
        label = "risk-score",
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        SmallCaps(stringResource(R.string.live_risk_label), palette.inkMuted)
        Text(
            shown.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
        )
    }
    Gap(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .clip(RoundedCornerShape(KavachTokens.RadiusTag))
            .background(palette.accent.copy(alpha = TRACK_EMPTY_ALPHA))
            .semantics { contentDescription = "" },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fill)
                .fillMaxHeight()
                .background(palette.accent),
        )
        ThresholdTick(state.cautionThreshold, palette)
        ThresholdTick(state.highRiskThreshold, palette)
    }
    Gap(8.dp)
    Text(
        stringResource(R.string.risk_thresholds, state.cautionThreshold, state.highRiskThreshold),
        style = MaterialTheme.typography.labelSmall,
        color = palette.inkMuted,
    )
}

/** One hairline on the track, at the score where the band changes. */
@Composable
private fun ThresholdTick(
    at: Int,
    palette: BandPalette,
) {
    if (at <= 0) return
    Row(
        Modifier
            .fillMaxWidth(at.toFloat() / RiskAssessment.MAX_SCORE)
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            Modifier
                .width(TICK_WIDTH)
                .fillMaxHeight()
                .background(palette.ink.copy(alpha = TICK_ALPHA)),
        )
    }
}

/**
 * Whether the microphone is actually delivering sound, in one line.
 *
 * The dot and the sentence read from [CaptureState], not from whether a session
 * is nominally running — the platform hands a muted app frames of zeroes rather
 * than an error, so "we started capture" and "we can hear" are different claims
 * and only the second one is worth making.
 */
@Composable
private fun HearingLine(
    capture: CaptureState,
    palette: BandPalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (capture.hearing) palette.accent else palette.inkMuted.copy(alpha = 0.45f)),
        )
        GapW(8.dp)
        Text(
            stringResource(if (capture.hearing) R.string.hearing_yes else R.string.hearing_no),
            style = MaterialTheme.typography.labelMedium,
            color = palette.inkSoft,
        )
    }
}

/**
 * The live waveform.
 *
 * It was eighteen fixed heights — a picture of a microphone rather than a report
 * from one, identical whether or not a single frame had arrived. It now moves,
 * and it moves at the amplitude the microphone is genuinely delivering:
 * [CaptureState.rmsDb] is the same number the diagnostics panel prints.
 *
 * Crucially it flattens to a line when nothing is being heard. A wave animating
 * over a muted microphone would be decoration that reads as reassurance, which
 * is the false all-clear docs/SAFETY.md forbids — so the animation is allowed to
 * be pretty only while it is also true.
 *
 * Three things make it legible rather than merely correct, all of which it
 * lacked on device, where it sat nearly still through an entire call:
 *
 * 1. **A speech-shaped scale.** The old mapping spread -60..0 dBFS across the
 *    full height. Nothing in a room ever reaches 0 dBFS, and a phone hearing a
 *    call at arm's length sits around -45 dB, so every bar lived in the bottom
 *    quarter and the loud/quiet difference was a few pixels. The window is now
 *    [WAVE_DB_FLOOR]..[WAVE_DB_CEILING] — the range speech actually occupies —
 *    with a gamma curve that lifts the quiet half. Both ends still saturate
 *    honestly: silence is flat, and clipping is full height.
 * 2. **Attack and release, not a tween.** [MicCapture] reports every 20 ms, so
 *    a 260 ms `animateFloatAsState` was re-targeted thirteen times before it
 *    could finish once and never travelled more than a fraction of the way —
 *    a low-pass filter that erased exactly the movement it was meant to show.
 *    It rises in [LEVEL_ATTACK_MS] and falls in [LEVEL_RELEASE_MS], which is
 *    how a meter behaves and why a voice reads as a voice.
 * 3. **A sliding history.** Every bar used to carry the same number, so a
 *    steady speaker drew a static block. Each bar now holds the level from one
 *    [WAVE_STEP_MS] tick further back, and the row is the last second or so of
 *    the microphone travelling leftwards.
 */
@Composable
private fun Waveform(
    color: Color,
    capture: CaptureState,
) {
    val target = captureLevel(capture)
    val level = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val rising = target > level.value
        level.animateTo(
            targetValue = target,
            animationSpec =
                tween(
                    durationMillis = if (rising) LEVEL_ATTACK_MS else LEVEL_RELEASE_MS,
                    easing = LinearEasing,
                ),
        )
    }

    // Oldest at index 0, newest at the right-hand end. Seeded flat so a screen
    // opened before the first frame arrives shows a line, not invented audio.
    val history = remember { mutableStateListOf<Float>().also { list -> repeat(WAVE_BARS) { list.add(0f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(WAVE_STEP_MS)
            history.removeAt(0)
            history.add(level.value)
        }
    }

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MS, easing = LinearEasing)),
        label = "wave-phase",
    )

    Row(
        Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(WAVE_BARS) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // Read inside the layer block, not in composition: eighteen
                    // bars re-measured at 60 Hz would be the most expensive
                    // thing on the screen. This only re-draws them.
                    .graphicsLayer {
                        val height = barHeight(history[index], index, phase)
                        scaleY = height
                        alpha = WAVE_MIN_ALPHA + height * (1f - WAVE_MIN_ALPHA)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }.background(color, RoundedCornerShape(KavachTokens.RadiusTag)),
            )
        }
    }
}

/**
 * One bar: how loud the microphone was [index] ticks ago, with a shimmer.
 *
 * [level] is the whole amplitude, so the row collapses to a flat line when the
 * microphone is delivering nothing — the honesty constraint above. The two
 * out-of-phase sines only ever ripple the top [WAVE_BASE]..1 of that amplitude,
 * so a held note still breathes rather than freezing into a plateau, and no
 * shimmer can lift a silent bar off the floor.
 */
private fun barHeight(
    level: Float,
    index: Int,
    phase: Float,
): Float {
    val slow = 0.5f + 0.5f * sin(phase + index * WAVE_SPREAD_A)
    val fast = 0.5f + 0.5f * sin(phase * WAVE_BEAT + index * WAVE_SPREAD_B)
    val shimmer = WAVE_BASE + (1f - WAVE_BASE) * (WAVE_MIX * slow + (1f - WAVE_MIX) * fast)
    return (WAVE_FLOOR + level * shimmer * (1f - WAVE_FLOOR)).coerceIn(WAVE_FLOOR, 1f)
}

/**
 * dBFS to a 0..1 bar height. Digital silence is negative infinity and lands
 * flat, which is the case this mapping exists to render honestly.
 *
 * The window is the one speech occupies through a phone at conversational
 * distance, not the full digital range: see [Waveform].
 */
private fun captureLevel(capture: CaptureState): Float {
    if (!capture.hearing || !capture.rmsDb.isFinite()) return 0f
    val span = WAVE_DB_CEILING - WAVE_DB_FLOOR
    val fraction = ((capture.rmsDb - WAVE_DB_FLOOR) / span).toFloat().coerceIn(0f, 1f)
    return fraction.pow(WAVE_GAMMA)
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

/** Live risk track. */
private val TRACK_HEIGHT = 10.dp
private val TICK_WIDTH = 2.dp
private const val TICK_ALPHA = 0.45f
private const val TRACK_EMPTY_ALPHA = 0.16f
private const val SCORE_ANIM_MS = 600
private const val TRANSCRIPT_WASH = 0.10f

/** Waveform. */
private const val WAVE_BARS = 18
private const val WAVE_PERIOD_MS = 1_500
private const val TWO_PI = 6.2831855f
private const val WAVE_FLOOR = 0.06f
private const val WAVE_MIN_ALPHA = 0.32f
private const val WAVE_SPREAD_A = 0.55f
private const val WAVE_SPREAD_B = 0.31f
private const val WAVE_BEAT = 1.7f
private const val WAVE_MIX = 0.6f

/** The shimmer rides the top 38% of the amplitude; the rest tracks the microphone. */
private const val WAVE_BASE = 0.62f

/** How fast a bar rises to a new peak, and how slowly it falls back. */
private const val LEVEL_ATTACK_MS = 70
private const val LEVEL_RELEASE_MS = 420

/** One bar per tick: eighteen bars is a shade under a second of history. */
private const val WAVE_STEP_MS = 55L

/**
 * The dBFS window the bars span.
 *
 * Room tone with nobody speaking measures about -58 dB on the iQOO; a voice on
 * speakerphone at arm's length runs -45 to -25; holding the phone to the mic
 * clips near -14. Mapping that range rather than the full -60..0 is the
 * difference between bars that visibly answer a voice and bars that do not.
 */
private const val WAVE_DB_FLOOR = -58.0
private const val WAVE_DB_CEILING = -14.0

/** Below 1, so the quiet half of the window gets more of the height than a linear map gives it. */
private const val WAVE_GAMMA = 0.65f

private fun elapsed(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
