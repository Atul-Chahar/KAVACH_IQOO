package com.kavach.app.ui

import androidx.compose.ui.graphics.Color
import com.kavach.domain.RiskBand

/**
 * The complete set of colours one risk state is drawn in.
 *
 * Bundling them means a screen never mixes grounds: it asks for the palette
 * once and every rule, label and control on it is guaranteed to be legible
 * against the same paper.
 */
data class BandPalette(
    /** The full-bleed ground. */
    val ground: Color,
    /** Primary copy on [ground]. */
    val ink: Color,
    /** Secondary copy — still readable, never decorative grey. */
    val inkSoft: Color,
    /** Small caps, timestamps, the quiet metadata line. */
    val inkMuted: Color,
    /** The spot colour this state is identified by. */
    val accent: Color,
    /** Copy laid on [accent]. */
    val onAccent: Color,
    /** Hairline rules on this ground. */
    val rule: Color,
    /** The underline drawn beneath words that actually matched. */
    val underline: Color,
)

/**
 * Risk-state colours for the three states in docs/PRD.md §5.
 *
 * Calm and high contrast, never a panic aesthetic (docs/SAFETY.md §5). Cyan
 * means Kavach is working and nothing has come up; amber means something has,
 * and is deliberately an ochre rather than a traffic-light yellow; the one
 * dangerous state is a deep press-red, not an alarm red, because the person
 * reading it is already frightened by the caller and the screen must not add
 * to that.
 */
object RiskColors {
    private val Watching =
        BandPalette(
            ground = KavachTokens.PaperCyan,
            ink = KavachTokens.Ink,
            inkSoft = KavachTokens.InkSoft,
            inkMuted = KavachTokens.InkMuted,
            accent = KavachTokens.Cyan,
            onAccent = KavachTokens.Card,
            rule = KavachTokens.Ink.copy(alpha = 0.14f),
            underline = KavachTokens.Magenta,
        )

    private val Caution =
        BandPalette(
            ground = KavachTokens.PaperAmber,
            ink = KavachTokens.Ink,
            inkSoft = KavachTokens.InkSoft,
            inkMuted = KavachTokens.InkMuted,
            accent = KavachTokens.Amber,
            onAccent = KavachTokens.Paper,
            rule = KavachTokens.Ink.copy(alpha = 0.16f),
            underline = KavachTokens.Magenta,
        )

    private val HighRisk =
        BandPalette(
            ground = KavachTokens.PressRed,
            ink = KavachTokens.PressRedPaper,
            inkSoft = KavachTokens.PressRedSoft,
            inkMuted = KavachTokens.PressRedSoft,
            accent = KavachTokens.PressRedPaper,
            onAccent = KavachTokens.PressRed,
            rule = KavachTokens.PressRedPaper.copy(alpha = 0.28f),
            underline = KavachTokens.PressRedUnderline,
        )

    fun paletteFor(band: RiskBand): BandPalette =
        when (band) {
            RiskBand.WATCHING -> Watching
            RiskBand.CAUTION -> Caution
            RiskBand.HIGH_RISK -> HighRisk
        }
}
