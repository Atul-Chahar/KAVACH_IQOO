package com.kavach.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B5E20),
        surface = Color(0xFFFBFDF8),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9BD79E),
        surface = Color(0xFF101410),
    )

private val KavachTypography =
    Typography(
        bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    )

@Composable
fun KavachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KavachTypography,
        content = content,
    )
}
