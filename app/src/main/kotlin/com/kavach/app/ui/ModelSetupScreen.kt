package com.kavach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.app.R
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState

/**
 * Model setup.
 *
 * Two steps on purpose. The app has no INTERNET permission, so it cannot fetch
 * the file itself — it hands the official URL to the browser, and takes the
 * finished file back through the system file picker. The screen says so plainly
 * rather than hiding it, because "this app cannot reach the network at all" is
 * the most reassuring thing we can tell someone installing a scam detector.
 */
@Composable
fun ModelSetupScreen(
    spec: ModelSpec,
    state: ModelState,
    freeSpaceBytes: Long,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.model_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.model_intro),
            style = MaterialTheme.typography.bodyLarge,
        )

        ModelCard(spec, freeSpaceBytes)

        when (state) {
            is ModelState.Importing -> ImportProgress(state)
            is ModelState.Ready ->
                StatusLine(
                    stringResource(R.string.model_ready, ModelCatalog.formatBytes(state.sizeBytes)),
                )
            is ModelState.Invalid -> StatusLine(state.reason)
            ModelState.Absent -> StatusLine(stringResource(R.string.model_absent))
        }

        if (state !is ModelState.Importing) {
            Step(1, stringResource(R.string.model_step_download))
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_action_download), fontSize = 18.sp)
            }

            Step(2, stringResource(R.string.model_step_import))
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_action_import), fontSize = 18.sp)
            }
        }

        if (state is ModelState.Ready) {
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_action_delete), fontSize = 16.sp)
            }
        }

        Text(
            text = stringResource(R.string.model_privacy_note),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.model_optional_note),
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back), fontSize = 18.sp)
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    freeSpaceBytes: Long,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Detail(stringResource(R.string.model_detail_size), ModelCatalog.formatBytes(spec.sizeBytes))
            Detail(stringResource(R.string.model_detail_backend), spec.backend)
            Detail(stringResource(R.string.model_detail_licence), spec.licence)
            Detail(stringResource(R.string.model_detail_source), ModelCatalog.PROVIDER)
            Detail(stringResource(R.string.model_detail_free), ModelCatalog.formatBytes(freeSpaceBytes))
            Text(
                spec.notes,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Step(
    number: Int,
    text: String,
) {
    Text(
        text = "$number.  $text",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ImportProgress(state: ModelState.Importing) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                stringResource(
                    R.string.model_importing,
                    ModelCatalog.formatBytes(state.copiedBytes),
                    ModelCatalog.formatBytes(state.totalBytes),
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun ModelSetupPreview() {
    KavachTheme {
        ModelSetupScreen(
            spec = ModelCatalog.default,
            state = ModelState.Absent,
            freeSpaceBytes = 12L * 1024 * 1024 * 1024,
            onDownload = {},
            onImport = {},
            onDelete = {},
            onBack = {},
        )
    }
}
