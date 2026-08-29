package com.kavach.app.ui

import androidx.compose.ui.graphics.Color
import com.kavach.domain.RiskBand

/**
 * Risk-state colours for the three states in docs/PRD.md 5.
 *
 * Calm and high contrast, never a panic aesthetic (docs/SAFETY.md 5). The red
 * state is a deep brick rather than an alarm red: the person reading it is
 * already frightened by the scammer, and the UI must not add to that.
 */
object RiskColors {
    val WatchingSurface = Color(0xFFE8F2E9)
    val CautionSurface = Color(0xFFFDF0D5)
    val HighRiskSurface = Color(0xFF8C1D18)

    val OnLight = Color(0xFF1B1C1B)
    val OnDark = Color(0xFFFFFFFF)

    fun surfaceFor(band: RiskBand): Color =
        when (band) {
            RiskBand.WATCHING -> WatchingSurface
            RiskBand.CAUTION -> CautionSurface
            RiskBand.HIGH_RISK -> HighRiskSurface
        }

    fun onSurfaceFor(band: RiskBand): Color = if (band == RiskBand.HIGH_RISK) OnDark else OnLight
}
