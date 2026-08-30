package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kavach.app.R

/**
 * The gap between opening a shared message and having a verdict on it.
 *
 * It exists so the analysis can run off the main thread without the screen
 * flashing a "nothing suspicious found" it has not earned yet. On any recent
 * phone it is on screen for a few frames; on a slow one, under a ten-thousand
 * character message, it is the difference between a considered answer and a
 * dropped frame.
 */
@Composable
fun MessageCheckingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = KavachTokens.Gutter, vertical = 22.dp),
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onBack).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorGlyph(Ph.arrowLeft, 20.dp, KavachTokens.InkSoft)
            GapW(10.dp)
            Text(
                stringResource(R.string.action_back),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )
        }

        Gap(28.dp)
        Rule(KavachTokens.Cyan, thickness = 3.dp)
        SmallCaps(
            stringResource(R.string.sms_analysis_label),
            KavachTokens.InkSoft,
            Modifier.padding(vertical = 9.dp),
        )
        Rule(KavachTokens.Ink)
        Gap(28.dp)
        Text(
            stringResource(R.string.sms_checking_title),
            style = MaterialTheme.typography.headlineMedium,
            color = KavachTokens.Ink,
        )
        Gap(8.dp)
        Text(
            stringResource(R.string.sms_privacy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTokens.InkSoft,
        )
    }
}
