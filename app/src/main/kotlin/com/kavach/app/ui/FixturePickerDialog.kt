package com.kavach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kavach.app.R
import com.kavach.demo.FixtureTranscriptSource

/** Picks which scripted conversation DemoMode replays. */
@Composable
fun FixturePickerDialog(
    fixtures: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.demo_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fixtures.isEmpty()) {
                    Text(stringResource(R.string.demo_picker_empty))
                }
                fixtures.forEach { asset ->
                    TextButton(onClick = { onPick(asset) }, modifier = Modifier.fillMaxWidth()) {
                        val marker = if (FixtureTranscriptSource.isPositive(asset)) "▲" else "●"
                        Text("$marker  ${asset.substringAfterLast('/').removeSuffix(".txt")}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) }
        },
    )
}
