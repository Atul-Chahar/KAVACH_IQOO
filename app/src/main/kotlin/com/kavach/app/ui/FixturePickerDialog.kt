package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kavach.app.R
import com.kavach.demo.FixtureTranscriptSource

/**
 * Picks which scripted conversation DemoMode replays.
 *
 * Each entry states plainly whether it is a scam script or a legitimate call,
 * because the negative fixtures are the more interesting half of the demo: they
 * are the evidence that Kavach stays quiet on a real bank fraud desk.
 *
 * Scrollable — the fixture corpus grows, and a picker that silently hides its
 * last entries below the fold would make part of the corpus unreachable.
 */
@Composable
fun FixturePickerDialog(
    fixtures: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(KavachTokens.RadiusSheet))
                .background(KavachTokens.Paper)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                stringResource(R.string.demo_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = KavachTokens.Ink,
            )
            Gap(14.dp)

            if (fixtures.isEmpty()) {
                Text(
                    stringResource(R.string.demo_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTokens.InkSoft,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(fixtures, key = { it }) { asset ->
                        FixtureRow(asset, onPick)
                    }
                }
            }

            Gap(16.dp)
            PillButton(
                label = stringResource(R.string.action_back),
                onClick = onDismiss,
                filled = false,
                content = KavachTokens.Ink,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FixtureRow(
    asset: String,
    onPick: (String) -> Unit,
) {
    val positive = FixtureTranscriptSource.isPositive(asset)
    val name =
        asset
            .substringAfterLast('/')
            .removeSuffix(".txt")
            .replace('-', ' ')

    Column {
        Rule(KavachTokens.Ink.copy(alpha = 0.12f))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { onPick(asset) })
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorGlyph(
                if (positive) Ph.shieldWarning else Ph.shieldCheck,
                22.dp,
                if (positive) KavachTokens.Amber else KavachTokens.Cyan,
            )
            GapW(12.dp)
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.Ink,
            )
        }
    }
}
