package com.kavach.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.kavach.app.R

/**
 * Source Serif 4, the app's voice in Latin script.
 *
 * A variable font: Compose derives the `wght` axis from the [FontWeight] on each
 * entry, so 500 and 600 are real cut weights rather than a synthetic smear.
 */
private val SourceSerif =
    FontFamily(
        Font(R.font.source_serif4, FontWeight.Normal),
        Font(R.font.source_serif4, FontWeight.Medium),
        Font(R.font.source_serif4, FontWeight.SemiBold),
    )

/**
 * Tiro Devanagari Hindi — Source Serif's true Devanagari companion, and the
 * reason the Hindi build does not look like a translation bolted onto an English
 * app. It carries Latin too, so a code-switched line sets in one voice.
 */
private val TiroDevanagari =
    FontFamily(
        Font(R.font.tiro_devanagari, FontWeight.Normal),
        Font(R.font.tiro_devanagari, FontWeight.Medium),
        Font(R.font.tiro_devanagari, FontWeight.SemiBold),
    )

/**
 * Type scale from the canvas, in sp so it honours the user's font-size setting.
 *
 * Devanagari needs more leading than Latin at the same size — its matras sit
 * above and below the base line — so the Hindi scale adds a little to every
 * line height rather than reusing the Latin metrics and clipping.
 */
private fun typographyFor(
    family: FontFamily,
    hindi: Boolean,
): Typography {
    val lead = if (hindi) 1.18f else 1f

    fun style(
        size: Int,
        line: Int,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = (line * lead).sp,
        fontWeight = weight,
        lineHeightStyle =
            LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
    )

    return Typography(
        // The single dangerous headline, and the "nothing unusual" counterweight.
        displaySmall = style(40, 46, FontWeight.SemiBold),
        headlineLarge = style(34, 38, FontWeight.SemiBold),
        headlineMedium = style(32, 38, FontWeight.SemiBold),
        headlineSmall = style(27, 33, FontWeight.SemiBold),
        titleLarge = style(20, 27, FontWeight.SemiBold),
        titleMedium = style(19, 27, FontWeight.SemiBold),
        titleSmall = style(17, 23, FontWeight.SemiBold),
        // Body copy. 18/27 is the reading size on every alerting screen.
        bodyLarge = style(18, 27),
        bodyMedium = style(16, 23),
        bodySmall = style(15, 23),
        labelLarge = style(16, 22, FontWeight.SemiBold),
        labelMedium = style(14, 21),
        labelSmall = style(13, 18),
    )
}

/**
 * Kavach is a light-ground product by design: the paper *is* the identity, and a
 * dark inversion would turn the one full-bleed red state into just another dark
 * screen. So there is no dark scheme — every surface states its ground
 * explicitly rather than inheriting one.
 */
private val KavachColors =
    lightColorScheme(
        primary = KavachTokens.Cyan,
        onPrimary = KavachTokens.Card,
        secondary = KavachTokens.Magenta,
        error = KavachTokens.PressRed,
        onError = KavachTokens.PressRedPaper,
        background = KavachTokens.Paper,
        onBackground = KavachTokens.Ink,
        surface = KavachTokens.Card,
        onSurface = KavachTokens.Ink,
        onSurfaceVariant = KavachTokens.InkMuted,
        outline = KavachTokens.InkMuted,
    )

@Composable
fun KavachTheme(content: @Composable () -> Unit) {
    val locales = LocalConfiguration.current.locales
    val hindi = remember(locales) { !locales.isEmpty && locales[0].language == "hi" }
    val typography = remember(hindi) { typographyFor(if (hindi) TiroDevanagari else SourceSerif, hindi) }

    MaterialTheme(
        colorScheme = KavachColors,
        typography = typography,
        content = content,
    )
}
