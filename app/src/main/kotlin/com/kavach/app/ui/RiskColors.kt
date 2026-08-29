package com.kavach.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Risk-state colours for the three states in docs/PRD.md §5. Calm and high
 * contrast, no panic aesthetics (docs/SAFETY.md §5).
 */
object RiskColors {
    val Watching = Color(0xFF1B5E20)
    val Caution = Color(0xFF8C5000)
    val HighRisk = Color(0xFFB3261E)
}
