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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kavach.app.ui.FixturePickerDialog
import com.kavach.app.ui.KavachTheme
import com.kavach.app.ui.ModelSetupScreen
import com.kavach.app.ui.ReportScreen
import com.kavach.app.ui.ShieldScreen
import com.kavach.app.ui.ShieldViewModel
import com.kavach.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    var showFixtures by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var showModelSetup by remember { mutableStateOf(false) }

    val modelState by viewModel.modelState.collectAsStateWithLifecycle()

    // The system file picker: the user grants access to exactly one file, so no
    // storage permission is needed and Kavach can read nothing else.
    val pickModel =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importModel)
        }

    // Monitoring is off by default and starts only on an explicit tap, with the
    // permission asked for at that moment rather than at launch (docs/SAFETY.md 7).
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) viewModel.startLive()
        }

    if (showModelSetup) {
        ModelSetupRoute(
            viewModel = viewModel,
            state = modelState,
            onPickFile = { pickModel.launch(MODEL_MIME_TYPES) },
            onBack = { showModelSetup = false },
        )
        return
    }

    report?.let { text ->
        ReportRoute(text, onBack = { report = null })
        return
    }

    ShieldScreen(
        state = state,
        onStartLive = { permissionLauncher.launch(requiredPermissions()) },
        onStartDemo = { showFixtures = true },
        onStop = viewModel::stop,
        onOpenReport = { report = viewModel.report() },
        onOpenModelSetup = {
            viewModel.refreshModel()
            showModelSetup = true
        },
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

@Composable
private fun ModelSetupRoute(
    viewModel: ShieldViewModel,
    state: ModelState,
    onPickFile: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Read free space when the screen opens and when the import finishes —
    // never as a parameter expression. Import progress emits roughly every
    // 8 MB, so an inline call would stat the filesystem on the composition
    // thread hundreds of times during a single copy.
    var freeSpaceBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state::class) {
        freeSpaceBytes = withContext(Dispatchers.IO) { viewModel.freeSpaceBytes() }
    }

    ModelSetupScreen(
        spec = viewModel.modelSpec,
        state = state,
        freeSpaceBytes = freeSpaceBytes,
        // Handing the URL to the browser is the whole design: Kavach has no
        // INTERNET permission and must never acquire one.
        onDownload = { context.startActivity(viewModel.downloadIntent()) },
        onImport = onPickFile,
        onDelete = { viewModel.deleteModel() },
        onBack = onBack,
    )
}

@Composable
private fun ReportRoute(
    text: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
        onBack = onBack,
    )
}

/** `.litertlm` has no registered MIME type, so accept anything and verify by size. */
private val MODEL_MIME_TYPES = arrayOf("*/*")

private fun requiredPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
