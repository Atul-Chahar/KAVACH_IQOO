package com.kavach.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kavach.app.ui.FixturePickerDialog
import com.kavach.app.ui.KavachTheme
import com.kavach.app.ui.ReportScreen
import com.kavach.app.ui.ShieldScreen
import com.kavach.app.ui.ShieldViewModel

/** Single activity, unidirectional data flow, one StateFlow (CLAUDE.md Stack). */
class MainActivity : ComponentActivity() {
    private val viewModel: ShieldViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KavachTheme {
                KavachApp(viewModel)
            }
        }
    }
}

@Composable
private fun KavachApp(viewModel: ShieldViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showFixtures by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    // Monitoring is off by default and starts only on an explicit tap, with the
    // permission asked for at that moment rather than at launch (docs/SAFETY.md 7).
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) viewModel.startLive()
        }

    report?.let { text ->
        ReportScreen(
            report = text,
            onShare = {
                // Office Kit file transfer picks this up from the share sheet.
                val share =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_title))
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                context.startActivity(Intent.createChooser(share, context.getString(R.string.action_share_report)))
            },
            onBack = { report = null },
        )
        return
    }

    ShieldScreen(
        state = state,
        onStartLive = { permissionLauncher.launch(requiredPermissions()) },
        onStartDemo = { showFixtures = true },
        onStop = viewModel::stop,
        onOpenReport = { report = viewModel.report() },
    )

    if (showFixtures) {
        FixturePickerDialog(
            fixtures = viewModel.fixtures,
            onPick = {
                showFixtures = false
                viewModel.startDemo(it)
            },
            onDismiss = { showFixtures = false },
        )
    }
}

private fun requiredPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
