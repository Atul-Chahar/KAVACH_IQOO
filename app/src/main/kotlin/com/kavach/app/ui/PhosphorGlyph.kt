package com.kavach.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.kavach.app.R

private val PhosphorFamily = FontFamily(Font(R.font.phosphor_duotone))

/**
 * One Phosphor duotone icon: a solid backing plate and the line drawing that
 * sits on top of it, as two codepoints.
 *
 * The webfont draws duotone by stacking the `:before` glyph at one-fifth opacity
 * under the `:after` glyph at full opacity. [PhosphorGlyph] reproduces exactly
 * that, which is why these match the canvas rather than approximating it with
 * Material shapes.
 */
data class PhosphorIcon(
    val back: Char,
    val front: Char,
)

/**
 * Draws a duotone icon at [size].
 *
 * Icons here are decorative in the strict sense: each sits beside text that
 * already says the same thing, so the default is no accessibility label rather
 * than one TalkBack would read twice. Pass [contentDescription] only where an
 * icon genuinely carries meaning on its own.
 */
@Composable
fun PhosphorGlyph(
    icon: PhosphorIcon,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // Icons deliberately do not rescale with the user's font setting: they sit
    // in rows with text that does, and a 200% icon would break every one.
    val style =
        TextStyle(
            fontFamily = PhosphorFamily,
            fontSize = TextUnit(size.value, TextUnitType.Sp),
            color = tint,
        )
    val semantics =
        if (contentDescription == null) {
            Modifier.clearAndSetSemantics { }
        } else {
            Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
        }

    Box(modifier.then(semantics)) {
        Text(icon.back.toString(), style = style.copy(color = tint.copy(alpha = 0.2f)))
        Text(icon.front.toString(), style = style)
    }
}

/** The Phosphor icons used by the Kavach screens. */
object Ph {
    /** Phosphor `airplane-tilt`. */
    val airplaneTilt = PhosphorIcon('\ue5d6', '\ue5d7')

    /** Phosphor `arrow-left`. */
    val arrowLeft = PhosphorIcon('\ue058', '\ue059')

    /** Phosphor `arrow-right`. */
    val arrowRight = PhosphorIcon('\ue06c', '\ue06d')

    /** Phosphor `arrow-square-out`. */
    val arrowSquareOut = PhosphorIcon('\ue5de', '\ue5df')

    /** Phosphor `battery-high`. */
    val batteryHigh = PhosphorIcon('\ue0c2', '\ue0c3')

    /** Phosphor `brain`. */
    val brain = PhosphorIcon('\ue74e', '\ue74f')

    /** Phosphor `camera`. */
    val camera = PhosphorIcon('\ue10e', '\ue10f')

    /** Phosphor `caret-down`. */
    val caretDown = PhosphorIcon('\ue136', '\ue137')

    /** Phosphor `cpu`. */
    val cpu = PhosphorIcon('\ue610', '\ue611')

    /** Phosphor `dots-three-outline`. */
    val dotsThreeOutline = PhosphorIcon('\ue204', '\ue205')

    /** Phosphor `file-text`. */
    val fileText = PhosphorIcon('\ue23a', '\ue23b')

    /** Phosphor `folder-open`. */
    val folderOpen = PhosphorIcon('\ue256', '\ue257')

    /** Phosphor `gear-six`. */
    val gearSix = PhosphorIcon('\ue272', '\ue273')

    /** Phosphor `grid-nine`. */
    val gridNine = PhosphorIcon('\uec8c', '\uec8d')

    /** Phosphor `hand-palm`. */
    val handPalm = PhosphorIcon('\ue57e', '\ue57f')

    /** Phosphor `house`. */
    val house = PhosphorIcon('\ue2c2', '\ue2c3')

    /** Phosphor `microphone`. */
    val microphone = PhosphorIcon('\ue326', '\ue327')

    /** Phosphor `microphone-slash`. */
    val microphoneSlash = PhosphorIcon('\ue328', '\ue329')

    /** Phosphor `password`. */
    val password = PhosphorIcon('\ue752', '\ue753')

    /** Phosphor `phone`. */
    val phone = PhosphorIcon('\ue3b8', '\ue3b9')

    /** Phosphor `phone-call`. */
    val phoneCall = PhosphorIcon('\ue3ba', '\ue3bb')

    /** Phosphor `phone-x`. */
    val phoneX = PhosphorIcon('\ue3c4', '\ue3c5')

    /** Phosphor `play-circle`. */
    val playCircle = PhosphorIcon('\ue3d2', '\ue3d3')

    /** Phosphor `printer`. */
    val printer = PhosphorIcon('\ue3dc', '\ue3dd')

    /** Phosphor `share-network`. */
    val shareNetwork = PhosphorIcon('\ue408', '\ue40b')

    /** Phosphor `shield-check`. */
    val shieldCheck = PhosphorIcon('\ue40c', '\ue40f')

    /** Phosphor `shield-warning`. */
    val shieldWarning = PhosphorIcon('\ue412', '\ue414')

    /** Phosphor `speaker-high`. */
    val speakerHigh = PhosphorIcon('\ue44a', '\ue44b')

    /** Phosphor `stop-circle`. */
    val stopCircle = PhosphorIcon('\ue46e', '\ue46f')

    /** Phosphor `translate`. */
    val translate = PhosphorIcon('\ue4a2', '\ue4a3')

    /** Phosphor `warning`. */
    val warning = PhosphorIcon('\ue4e0', '\ue4e1')

    /** Phosphor `waveform`. */
    val waveform = PhosphorIcon('\ue802', '\ue803')

    /** Phosphor `wifi-slash`. */
    val wifiSlash = PhosphorIcon('\ue4f2', '\ue4f3')
}
