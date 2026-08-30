package com.kavach.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Kavach mark: one shield printed three times, slightly out of register.
 *
 * It is a press artefact rather than a logo effect — cyan and magenta plates
 * misaligned by a pixel under the black. It carries the whole identity in
 * 26dp, and it is why the app looks printed rather than rendered.
 */
@Composable
fun ShieldMark(
    size: Dp,
    inkColor: Color = KavachTokens.Ink,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clearAndSetSemantics { }) {
        PhosphorGlyph(Ph.shieldCheck, size, KavachTokens.Cyan.copy(alpha = 0.6f), Modifier.offset((-1).dp, 1.dp))
        PhosphorGlyph(Ph.shieldCheck, size, KavachTokens.Magenta.copy(alpha = 0.5f), Modifier.offset(1.dp, (-1).dp))
        PhosphorGlyph(Ph.shieldCheck, size, inkColor)
    }
}

/** Small letter-spaced caps — the standing-head of the printed page. */
@Composable
fun SmallCaps(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 0.08.em,
        modifier = modifier,
    )
}

/**
 * The primary control: a fully-rounded pill, 56dp tall so it clears the 48dp
 * touch-target minimum with room for an elderly user's aim.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: PhosphorIcon? = null,
    filled: Boolean = true,
    container: Color = KavachTokens.Cyan,
    content: Color = KavachTokens.Card,
    height: Dp = 56.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    val border = if (filled) null else BorderStroke(1.dp, content.copy(alpha = 0.55f))

    Row(
        modifier =
            modifier
                .height(height)
                .clip(shape)
                .then(if (filled) Modifier.background(container, shape) else Modifier)
                .then(border?.let { Modifier.border(it, shape) } ?: Modifier)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { PhosphorGlyph(it, 21.dp, content) }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A control you have to mean.
 *
 * The alert card's two actions used to be ordinary taps, and the person reading
 * that card is the worst possible candidate for an ordinary tap: elderly,
 * frightened, being actively talked at, holding the phone against their face.
 * A mis-tap on "I'm fine" silences the only warning they are going to get.
 *
 * Dragging cannot happen by accident. The thumb travels the full width, snaps
 * back if released before [COMMIT_FRACTION], and only then fires. The gesture
 * costs a deliberate half-second, which is the point.
 *
 * It is still one tap for TalkBack: the semantics below expose a plain button
 * action, because "drag precisely" is not an instruction a screen-reader user
 * should have to follow to dismiss an alarm.
 */
@Composable
fun SlideToConfirm(
    label: String,
    icon: PhosphorIcon,
    track: Color,
    thumb: Color,
    onThumb: Color,
    ink: Color,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackWidth by remember { mutableIntStateOf(0) }
    var committed by remember { mutableStateOf(false) }

    val thumbPx = with(LocalDensity.current) { (SLIDE_THUMB - SLIDE_INSET).toPx() }
    val travel = (trackWidth - thumbPx - with(LocalDensity.current) { SLIDE_INSET.toPx() }).coerceAtLeast(1f)
    val shape = RoundedCornerShape(SLIDE_HEIGHT / 2)

    // The label fades out as the thumb crosses it, so the control never shows
    // its instruction and its thumb fighting for the same pixels.
    val progress = (offset.value / travel).coerceIn(0f, 1f)

    Box(
        modifier
            .height(SLIDE_HEIGHT)
            .clip(shape)
            .background(track, shape)
            .onSizeChanged { trackWidth = it.width }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    onConfirm()
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = (1f - progress * LABEL_FADE_RATE).coerceIn(0f, 1f)),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = SLIDE_THUMB, end = 12.dp),
        )

        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(SLIDE_INSET)
                .size(SLIDE_THUMB - SLIDE_INSET * 2)
                .clip(RoundedCornerShape(KavachTokens.RadiusCard))
                .background(thumb)
                .pointerInput(travel) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (committed) return@detectHorizontalDragGestures
                            if (offset.value >= travel * COMMIT_FRACTION) {
                                committed = true
                                scope.launch {
                                    offset.animateTo(travel)
                                    onConfirm()
                                }
                            } else {
                                scope.launch { offset.animateTo(0f) }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dragAmount ->
                        change.consume()
                        if (committed) return@detectHorizontalDragGestures
                        scope.launch {
                            offset.snapTo((offset.value + dragAmount).coerceIn(0f, travel))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PhosphorGlyph(icon, 22.dp, onThumb)
        }
    }
}

/** Track height, thumb size and inset, matched to the 56dp PillButton rhythm. */
private val SLIDE_HEIGHT = 64.dp
private val SLIDE_THUMB = 64.dp
private val SLIDE_INSET = 5.dp

/** How far across the thumb must travel before the action counts as meant. */
private const val COMMIT_FRACTION = 0.72f

/** The label is gone by the time the thumb is two-thirds of the way over it. */
private const val LABEL_FADE_RATE = 1.6f

/**
 * The five-segment family meter.
 *
 * Deliberately not a percentage bar: the rule that governs an alert is "how many
 * *different* tactic families have come up", so the UI counts the same discrete
 * thing the engine does. A user can look at this and predict what Kavach will do
 * next, which is the difference between a tool and an oracle.
 */
@Composable
fun FamilyMeter(
    seen: Int,
    total: Int,
    filled: Color,
    empty: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total.coerceAtLeast(1)) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(if (index < seen) filled else empty),
            )
        }
    }
}

/**
 * One numbered evidence row: rank, what was matched, and which family it was.
 *
 * [dimmed] draws the "nothing yet about X" row that tells the user what would
 * raise the state — showing the absent evidence as well as the present evidence
 * is what makes the threshold legible instead of arbitrary.
 */
@Composable
fun RankedRow(
    rank: Int,
    title: String,
    caption: String,
    palette: BandPalette,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    compact: Boolean = false,
) {
    val rankColor = if (dimmed) palette.ink.copy(alpha = 0.3f) else palette.accent
    val titleColor = if (dimmed) palette.inkMuted else palette.ink

    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 9.dp else 13.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
        Text(
            text = "%02d".format(rank),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = rankColor,
            modifier = Modifier.padding(top = 3.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = titleColor,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkMuted,
            )
        }
    }
}

/** A hairline rule, the width of the column. */
@Composable
fun Rule(
    color: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}

/**
 * The "show the words" switch.
 *
 * Hand-drawn rather than a Material `Switch` so it sits in the paper system, and
 * given a real semantic role so TalkBack announces it as the toggle it is.
 */
@Composable
fun PaperToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    palette: BandPalette,
    modifier: Modifier = Modifier,
) {
    val track = if (checked) palette.accent else palette.ink.copy(alpha = 0.16f)
    Box(
        modifier
            .size(52.dp, 30.dp)
            .clip(CircleShape)
            .background(track)
            .semantics {
                role = Role.Switch
                contentDescription = label
            }.clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(KavachTokens.Card),
        )
    }
}

/** The tiles on the home screen. */
@Composable
fun PaperTile(
    icon: PhosphorIcon,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = KavachTokens.Cyan,
    /** Optional count chip, for a tile with something waiting behind it. */
    badge: String? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(KavachTokens.Card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhosphorGlyph(icon, 26.dp, tint)
            if (badge != null) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(tint)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = KavachTokens.Card,
                    )
                }
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = KavachTokens.Ink)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KavachTokens.InkMuted)
        }
    }
}

/** The floating pill nav. Three destinations, ink ground, always reachable. */
@Composable
fun PillNav(
    selected: Int,
    onSelect: (Int) -> Unit,
    labels: List<String>,
    icons: List<PhosphorIcon>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(KavachTokens.Ink),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .semantics { if (active) contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val tint = KavachTokens.Paper.copy(alpha = if (active) 1f else 0.55f)
                PhosphorGlyph(icons[index], 20.dp, tint)
                Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = tint)
            }
        }
    }
}

/** Vertical spacer, for readability at the call sites. */
@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

/** Horizontal spacer. */
@Composable
fun GapW(width: Dp) = Spacer(Modifier.width(width))
