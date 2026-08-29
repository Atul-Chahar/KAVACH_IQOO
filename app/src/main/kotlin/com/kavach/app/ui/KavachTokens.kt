package com.kavach.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Kavach design tokens, transcribed from the "New Direction" canvas.
 *
 * The system in one line: a warm paper ground, everything set in a serif, and
 * spot colour used as ink rather than decoration. The serif is the chrome — it
 * makes the app read like a printed notice rather than a dialog box, which is
 * the whole point for a product whose job is to be believed.
 *
 * Signal colour carries exactly one meaning each and is never decorative:
 * [Cyan] means Kavach is working, [Amber] means something has come up, [Magenta]
 * underlines the exact words that matched, and [PressRed] is reserved for the
 * single dangerous state, where it goes full-bleed so it is unmistakable across
 * a room.
 */
object KavachTokens {
    // ---- Grounds -----------------------------------------------------------

    /** The default paper. Warm, not white — white reads as a system dialog. */
    val Paper = Color(0xFFFAF6F2)

    /** Paper with a cyan cast: the working, nothing-found state. */
    val PaperCyan = Color(0xFFF2F7F9)

    /** Paper with an amber cast: something has come up, but this is not a warning. */
    val PaperAmber = Color(0xFFFAF1E4)

    /** Raised surfaces on paper — tiles and cards. */
    val Card = Color(0xFFFFFFFF)

    /** The device bezel in the canvas, reused for the pill nav. */
    val InkDeep = Color(0xFF2B2725)

    // ---- Ink ---------------------------------------------------------------

    val Ink = Color(0xFF201E1D)
    val InkSoft = Color(0xFF4A443F)
    val InkFaint = Color(0xFF5D564F)
    val InkMuted = Color(0xFF6B625C)

    // ---- Spot colour -------------------------------------------------------

    /** Kavach is working. The only colour used for "running normally". */
    val Cyan = Color(0xFF0088B0)
    val CyanDeep = Color(0xFF00708F)

    /** Something has come up. Deliberately an ochre, not a traffic-light yellow. */
    val Amber = Color(0xFF9A5A06)

    /** Underlines the words that actually matched. Never a background. */
    val Magenta = Color(0xFFD6006C)
    val MagentaDeep = Color(0xFFA01050)

    // ---- The one dangerous state ------------------------------------------

    /**
     * A deep press-red, not an alarm red. The person reading this is already
     * frightened by the caller; the screen must not add to it (docs/SAFETY.md §5).
     */
    val PressRed = Color(0xFF7B1B14)

    /** Paper laid on [PressRed]. */
    val PressRedPaper = Color(0xFFFDF3EF)

    /** Secondary copy on [PressRed]. */
    val PressRedSoft = Color(0xFFF4D9D3)

    /** The matched-word underline on [PressRed], where magenta would disappear. */
    val PressRedUnderline = Color(0xFFFF8EC4)

    // ---- Rules and dividers ------------------------------------------------

    /** Hairline rules read differently on paper and on the red; pick per ground. */
    fun hairline(on: Color): Color = if (on == PressRed) PressRedPaper.copy(alpha = 0.28f) else Ink.copy(alpha = 0.16f)

    // ---- Geometry ----------------------------------------------------------

    /**
     * Cards are near-square (2dp) so they read as set type on a page; only
     * controls are fully rounded. That contrast is the whole visual idea —
     * printed matter with one soft, obviously-tappable affordance on top.
     */
    val RadiusTag = 2.dp
    val RadiusCard = 22.dp
    val RadiusSheet = 24.dp

    val Gutter = 28.dp
}
